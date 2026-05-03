package com.si.backend.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * VoiceMeeter 配置。
 *
 * <p>设备关键字用于在 Windows 音频设备列表中匹配虚拟设备：
 * <ul>
 *   <li>VoiceMeeter Input    → 中文声道（B1）</li>
 *   <li>VoiceMeeter Aux Input → 印尼语声道（B2）</li>
 * </ul>
 *
 * <p>VoiceMeeter Potato 路由设置：
 * <ul>
 *   <li>VoiceMeeter Input    → 点亮 B1</li>
 *   <li>VoiceMeeter AUX Input → 点亮 B2</li>
 * </ul>
 */
@Data
@Component
@ConfigurationProperties(prefix = "voicemeeter")
public class VoiceMeeterProperties {

    private boolean enabled = false;

    /**
     * 中文声道设备关键字。
     * 默认 "VoiceMeeter Input"（对应 B1）
     */
    private String zhDevice = "VoiceMeeter Input";

    /**
     * 印尼语声道设备关键字。
     * 默认 "VoiceMeeter Aux Input"（对应 B2）
     */
    private String idDevice = "VoiceMeeter Aux Input";
}
