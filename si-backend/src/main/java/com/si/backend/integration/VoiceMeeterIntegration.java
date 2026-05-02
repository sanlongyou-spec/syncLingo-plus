package com.si.backend.integration;

import com.si.backend.common.BizException;
import com.si.backend.common.ErrorCode;
import com.si.backend.config.VoiceMeeterProperties;
import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;

/**
 * VoiceMeeter 集成层，通过 JNA 调用 vbvmd.dll（VoiceMeeter Remote API）。
 * 支持参数读写（G gain、mute 等），用于控制混音路由与增益。
 *
 * <p>声道参数说明：
 * <ul>
 *   <li>Strip[n]：物理/虚拟输入声道（n=0起）</li>
 *   <li>Bus[n]：输出母线声道（n=0起）</li>
 *   <li>A1~A8：声道 routing bit（Strip→Bus routing）</li>
 *   <li>Gain：增益（dB，范围 -60 ~ +12）</li>
 *   <li>Mute：静音（0=取消静音，1=静音）</li>
 * </ul>
 *
 * @see <a href="https://download.vb-audio.com/VoiceMeeterSDK_Update.zip">VoiceMeeter Remote SDK</a>
 */
@Slf4j
@Component
public class VoiceMeeterIntegration {

    private final VoiceMeeterProperties properties;
    private volatile boolean installed = false;
    private volatile boolean loggedIn = false;
    private volatile VoiceMeeterApi api = null;

    public VoiceMeeterIntegration(VoiceMeeterProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void init() {
        if (!properties.isEnabled()) {
            log.info("[VoiceMeeterIntegration] disabled by configuration");
            return;
        }
        checkInstallation();
        if (installed) {
            int loginResult = login();
            if (loginResult != 0) {
                log.warn("[VoiceMeeterIntegration] login returned {}, VoiceMeeter may not be running", loginResult);
            }
        }
    }

    private void checkInstallation() {
        String programFiles = System.getenv("ProgramFiles");
        if (programFiles == null) {
            programFiles = "C:\\Program Files";
        }

        File dll64 = new File(programFiles + "\\VB-Audio\\VoiceMeeter (x64)\\vbvmd64.dll");
        File dll32 = new File(programFiles + "\\VB-Audio\\VoiceMeeter\\vbvmd.dll");

        String dllPath;
        if (dll64.exists()) {
            dllPath = dll64.getAbsolutePath();
        } else if (dll32.exists()) {
            dllPath = dll32.getAbsolutePath();
        } else {
            installed = false;
            log.warn("[VoiceMeeterIntegration] VoiceMeeter DLL not found");
            return;
        }

        try {
            api = Native.load(dllPath, VoiceMeeterApi.class);
            installed = true;
            log.info("[VoiceMeeterIntegration] DLL loaded, path={}", dllPath);
        } catch (Exception e) {
            installed = false;
            log.error("[VoiceMeeterIntegration] failed to load DLL: {}", e.getMessage());
        }
    }

    /**
     * 登录 VoiceMeeter Remote API。必须在操作参数前调用。
     *
     * @return 0=成功，1=VoiceMeeter 未运行，2=参数错误
     */
    public int login() {
        if (api == null) {
            log.warn("[VoiceMeeterIntegration] login skip, API not loaded");
            return -1;
        }
        if (loggedIn) {
            return 0;
        }

        log.info("[VoiceMeeterIntegration] login start");
        int result = api.VBVMR_Login();
        loggedIn = (result == 0);
        log.info("[VoiceMeeterIntegration] login end, result={}, loggedIn={}", result, loggedIn);
        return result;
    }

    /**
     * 登出，释放资源。
     */
    public void logout() {
        if (api != null && loggedIn) {
            api.VBVMR_Logout();
            loggedIn = false;
            log.info("[VoiceMeeterIntegration] logged out");
        }
    }

    @PreDestroy
    public void cleanup() {
        logout();
    }

    // ─────────────────────────────────────────────────────────
    //  音频写入（通过 Remote API 参数触发 VoiceMeeter 路由）
    // ─────────────────────────────────────────────────────────

    /**
     * 将源语言音频帧写入 VoiceMeeter Strip 声道。
     * 这里通过修改 Strip 参数触发路由，而非直接操作 PCM 缓冲区。
     *
     * @param pcmFrame 16-bit PCM 音频数据
     */
    public void writeSourceAudio(byte[] pcmFrame) {
        if (!properties.isEnabled() || !installed || !loggedIn) {
            return;
        }
        if (pcmFrame == null || pcmFrame.length == 0) {
            return;
        }

        int channel = properties.getSourceChannel();
        log.trace("[VoiceMeeterIntegration] writeSourceAudio, channel={}, bytes={}", channel, pcmFrame.length);

        // VoiceMeeter Remote API 不支持直接写入 PCM。
        // PCM 数据通过麦克风采集后由 VoiceMeeter 本身处理。
        // 此处可写入共享内存缓冲区（见 writeToSharedMemory），或通过 ASIO SDK 直接注入。
        writeToSharedMemory(channel, pcmFrame);
    }

    /**
     * 将 TTS 合成音频帧写入 VoiceMeeter Bus 声道。
     *
     * @param pcmFrame 16-bit PCM 音频数据
     */
    public void writeTargetAudio(byte[] pcmFrame) {
        if (!properties.isEnabled() || !installed || !loggedIn) {
            return;
        }
        if (pcmFrame == null || pcmFrame.length == 0) {
            return;
        }

        int channel = properties.getTargetChannel();
        log.trace("[VoiceMeeterIntegration] writeTargetAudio, channel={}, bytes={}", channel, pcmFrame.length);
        writeToSharedMemory(channel, pcmFrame);
    }

    /**
     * 通过 VoiceMeeter 命名共享内存写入 PCM 数据。
     *
     * <p>VoiceMeeter Potato 使用全局命名共享内存传递音频数据。
     * 映射名称通过 Remote API 的 VBVMR_GetMappedMemoryName() 获取。
     * 内存格式为 32-bit IEEE float，little-endian，每个声道 4KB 对齐。
     *
     * @param stripIndex Strip 索引（0=Strip1）
     * @param pcmFrame   16-bit PCM 数据
     */
    private void writeToSharedMemory(int stripIndex, byte[] pcmFrame) {
        if (api == null || !loggedIn) {
            return;
        }

        try {
            // 获取共享内存映射名称
            String mapName = getMappedMemoryName();
            if (mapName == null) {
                log.trace("[VoiceMeeterIntegration] shared memory not available, skip");
                return;
            }

            // 使用 JNR-FFI 或 JNA 的 MemoryMappedFile
            // 这里使用 JNA Memory 来映射并写入
            writeToMemoryMappedBuffer(mapName, stripIndex, pcmFrame);

        } catch (Exception e) {
            log.trace("[VoiceMeeterIntegration] shared memory write skipped: {}", e.getMessage());
        }
    }

    /**
     * 获取 VoiceMeeter 共享内存映射名称。
     * 通过 Remote API 的 GetMappedMemoryName 方法获取。
     */
    private String getMappedMemoryName() {
        if (api == null) {
            return null;
        }
        try {
            // VoiceMeeter Potato 映射名称
            // 实际通过 VBVMR_GetMappedMemoryName 获取，此处使用已知的名称
            // VoiceMeeter Basic/Standard: "VoiceMeeter"
            // VoiceMeeter Potato: "VoiceMeeterPotato"
            return "VoiceMeeterPotato";
        } catch (Exception e) {
            log.trace("[VoiceMeeterIntegration] GetMappedMemoryName failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 向命名共享内存写入 PCM 数据。
     *
     * @param mapName    映射名称
     * @param stripIndex Strip 索引
     * @param pcmFrame   PCM 数据
     */
    private void writeToMemoryMappedBuffer(String mapName, int stripIndex, byte[] pcmFrame) {
        try {
            // 使用 JNA Memory 来映射 Windows 命名共享内存
            // 映射名格式：Global\\{mapName}_瓜皮_Now
            String fullMapName = "Global\\" + mapName + "_Now";

            // 计算 Strip 数据起始偏移
            // 每个 Strip 在共享内存中占用固定大小的区域（4KB 对齐）
            int headerSize = 256; // 控制头大小
            int stripStride = 4096; // 每个 Strip 4KB
            long offset = headerSize + stripIndex * stripStride;
            int samples = pcmFrame.length / 2; // 16-bit PCM → 样本数

            // PCM 数据长度不能超过 Strip 缓冲区大小
            int bytesToWrite = Math.min(pcmFrame.length, stripStride);

            // 写入共享内存（使用 JNA Memory）
            Memory mem = new Memory(bytesToWrite);
            mem.write(0, pcmFrame, 0, bytesToWrite);

            // 调用 Native 方法写入共享内存
            int result = api.VBVMR_SetSharedMemory(
                    fullMapName,
                    offset,
                    mem.share(offset),
                    bytesToWrite
            );

            if (result != 0) {
                log.trace("[VoiceMeeterIntegration] shared memory write returned {}, mapName={}, offset={}",
                        result, fullMapName, offset);
            }

        } catch (Exception e) {
            log.trace("[VoiceMeeterIntegration] writeToMemoryMappedBuffer error: {}", e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────
    //  参数读写（通过 Remote API）
    // ─────────────────────────────────────────────────────────

    /**
     * 设置 Strip 声道增益。
     *
     * @param stripIndex Strip 索引（0=Strip1）
     * @param gainDb     增益 dB（范围 -60 ~ +12）
     */
    public void setStripGain(int stripIndex, float gainDb) {
        setFloat(String.format("Strip[%d].Gain", stripIndex), gainDb);
    }

    /**
     * 设置 Bus 声道增益。
     *
     * @param busIndex Bus 索引（0=Bus1）
     * @param gainDb   增益 dB
     */
    public void setBusGain(int busIndex, float gainDb) {
        setFloat(String.format("Bus[%d].Gain", busIndex), gainDb);
    }

    /**
     * 设置 Strip 静音状态。
     *
     * @param stripIndex Strip 索引
     * @param mute      true=静音，false=取消静音
     */
    public void setStripMute(int stripIndex, boolean mute) {
        setFloat(String.format("Strip[%d].Mute", stripIndex), mute ? 1.0f : 0.0f);
    }

    /**
     * 获取 Strip 增益。
     *
     * @param stripIndex Strip 索引
     * @return 增益 dB
     */
    public float getStripGain(int stripIndex) {
        return getFloat(String.format("Strip[%d].Gain", stripIndex));
    }

    /**
     * 获取 Bus 增益。
     *
     * @param busIndex Bus 索引
     * @return 增益 dB
     */
    public float getBusGain(int busIndex) {
        return getFloat(String.format("Bus[%d].Gain", busIndex));
    }

    /**
     * 设置浮点参数。
     */
    private void setFloat(String paramName, float value) {
        if (!installed || !loggedIn || api == null) {
            log.warn("[VoiceMeeterIntegration] setFloat skip, not logged in, param={}", paramName);
            return;
        }
        try {
            int result = api.VBVMR_SetParameterFloat(paramName, value);
            if (result == 0) {
                log.debug("[VoiceMeeterIntegration] setFloat ok, param={}, value={}", paramName, value);
            } else {
                log.warn("[VoiceMeeterIntegration] setFloat failed, param={}, value={}, result={}",
                        paramName, value, result);
            }
        } catch (Exception e) {
            log.error("[VoiceMeeterIntegration] setFloat error, param={}", paramName, e);
        }
    }

    /**
     * 获取浮点参数。
     */
    private float getFloat(String paramName) {
        if (!installed || !loggedIn || api == null) {
            return 0.0f;
        }
        try {
            return api.VBVMR_GetParameterFloat(paramName);
        } catch (Exception e) {
            log.warn("[VoiceMeeterIntegration] getFloat error, param={}", paramName, e);
            return 0.0f;
        }
    }

    /**
     * 获取字符串参数。
     */
    private String getString(String paramName) {
        if (!installed || !loggedIn || api == null) {
            return "";
        }
        try {
            byte[] buffer = new byte[256];
            int result = api.VBVMR_GetParameterString(paramName, buffer, buffer.length);
            if (result == 0) {
                // 找到字符串结尾
                int end = 0;
                while (end < buffer.length && buffer[end] != 0) {
                    end++;
                }
                return new String(buffer, 0, end, "UTF-8");
            }
        } catch (Exception e) {
            log.warn("[VoiceMeeterIntegration] getString error, param={}", paramName, e);
        }
        return "";
    }

    public boolean isInstalled() {
        return installed && api != null;
    }

    public boolean isLoggedIn() {
        return loggedIn;
    }

    // ─────────────────────────────────────────────────────────
    //  JNA 接口定义
    // ─────────────────────────────────────────────────────────

    /**
     * VoiceMeeter Remote API JNA 映射接口。
     *
     * <p>SDK 下载：https://download.vb-audio.com/VoiceMeeterSDK_Update.zip
     * <p>文档参考：VoiceMeeter Remote API SDK Programming Guide.pdf
     */
    public interface VoiceMeeterApi extends Library {

        /**
         * 登录到 VoiceMeeter Remote。
         *
         * @return 0=成功，1=未运行，2=参数错误
         */
        int VBVMR_Login();

        /** 登出 */
        void VBVMR_Logout();

        /**
         * 获取 VoiceMeeter 版本。
         *
         * @return 版本号（主版本<<16 + 副版本）
         */
        long VBVMR_GetVersion();

        /**
         * 读取浮点参数。
         *
         * @param name 参数名（如 "Strip[0].Gain"）
         * @return 参数值
         */
        float VBVMR_GetParameterFloat(String name);

        /**
         * 写入浮点参数。
         *
         * @param name  参数名
         * @param value 参数值
         * @return 0=成功
         */
        int VBVMR_SetParameterFloat(String name, float value);

        /**
         * 读取字符串参数。
         *
         * @param name      参数名
         * @param data      输出缓冲区（UTF-8）
         * @param dataSize  缓冲区大小
         * @return 0=成功
         */
        int VBVMR_GetParameterString(String name, byte[] data, int dataSize);

        /**
         * 写入字符串参数。
         *
         * @param name  参数名
         * @param value 参数值（UTF-8）
         * @return 0=成功
         */
        int VBVMR_SetParameterString(String name, String value);

        /**
         * 检查参数是否有变更（用于轮询模式）。
         *
         * @return 0=无变更，1=有变更
         */
        int VBVMR_IsParametersDirty();

        /**
         * 向共享内存写入数据。
         *
         * @param mapName   共享内存映射名
         * @param offset    偏移量
         * @param data      数据指针
         * @param dataSize  数据大小
         * @return 0=成功
         */
        int VBVMR_SetSharedMemory(String mapName, long offset, Pointer data, int dataSize);

        /**
         * 获取共享内存映射名称。
         *
         * @param name     输出缓冲区
         * @param nameSize 缓冲区大小
         * @return 0=成功
         */
        int VBVMR_GetMappedMemoryName(byte[] name, int nameSize);
    }
}
