package com.si.backend.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.si.backend.common.BizException;
import com.si.backend.common.Constants;
import com.si.backend.common.ErrorCode;
import com.si.backend.config.CartesiaProperties;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.stereotype.Component;

/**
 * Cartesia API 集成层，封装音色克隆 API 调用。
 *
 * <p>参考官方文档：
 * <ul>
 *   <li>Clone Voice: POST https://api.cartesia.ai/voices/clone</li>
 *   <li>Cartesia-Version header: 2026-03-01（2026-06-01 后旧版 API 停用）</li>
 * </ul>
 *
 * <p>说明：Cartesia 目前无官方 Java SDK，使用 OkHttp REST 调用。
 */
@Slf4j
@Component
public class CartesiaTtsIntegration {

    private static final String CLONE_VOICES_URL = Constants.CARTESIA_API_ENDPOINT + "/voices/clone";

    private final CartesiaProperties properties;
    private final ObjectMapper objectMapper;
    private final OkHttpClient httpClient;

    public CartesiaTtsIntegration(
            CartesiaProperties properties,
            ObjectMapper objectMapper,
            OkHttpClient httpClient) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    /**
     * 克隆音色，上传音频样本到 Cartesia 并获取音色 ID。
     *
     * <p>参考 Cartesia Clone Voice API（POST /voices/clone）：
     * <ul>
     *   <li>clip: 二进制音频文件（推荐 5~10 秒，高相似模式）</li>
     *   <li>name: 音色名称</li>
     *   <li>language: 语种代码（zh / id / en 等）</li>
     *   <li>enhance: 设为 false 以获得更高相似度</li>
     * </ul>
     *
     * @param audioSample WAV 音频样本
     * @param voiceName  音色名称
     * @param language   语种代码（如 "zh"、"id"）
     * @return Cartesia 返回的音色 UUID
     */
    public String createVoice(byte[] audioSample, String voiceName, String language) {
        log.info("[CartesiaTtsIntegration] createVoice start, voiceName={}, language={}, audioSampleLen={}",
                voiceName, language, audioSample != null ? audioSample.length : 0);

        long start = System.currentTimeMillis();

        RequestBody body = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("name", voiceName)
                .addFormDataPart("language", language)
                .addFormDataPart("enhance", "false")
                .addFormDataPart("clip", "sample.wav",
                        RequestBody.create(audioSample, MediaType.parse("audio/wav")))
                .build();

        Request request = new Request.Builder()
                .url(CLONE_VOICES_URL)
                .addHeader("Authorization", "Bearer " + properties.getApiKey())
                .addHeader("Cartesia-Version", Constants.CARTESIA_VERSION_HEADER)
                .post(body)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            long cost = System.currentTimeMillis() - start;

            if (!response.isSuccessful()) {
                log.error("[CartesiaTtsIntegration] createVoice HTTP error, voiceName={}, httpCode={}",
                        voiceName, response.code());
                throw BizException.of(ErrorCode.TTS_VOICE_CLONE_ERROR,
                        "音色克隆失败: HTTP " + response.code());
            }

            String bodyStr = response.body() != null ? response.body().string() : "";
            JsonNode node = objectMapper.readTree(bodyStr);
            String voiceId = node.path("id").asText();

            if (voiceId == null || voiceId.isBlank()) {
                log.error("[CartesiaTtsIntegration] createVoice empty voiceId, voiceName={}", voiceName);
                throw BizException.of(ErrorCode.TTS_VOICE_CLONE_ERROR, "音色克隆返回 ID 为空");
            }

            log.info("[CartesiaTtsIntegration] createVoice end, voiceName={}, voiceId={}, costMs={}",
                    voiceName, voiceId, cost);
            return voiceId;

        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("[CartesiaTtsIntegration] createVoice error, voiceName={}", voiceName, e);
            throw BizException.of(ErrorCode.TTS_VOICE_CLONE_ERROR, "音色克隆失败: " + e.getMessage());
        }
    }
}
