package com.si.backend.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "voicemeeter")
public class VoiceMeeterProperties {

    private boolean enabled = false;
    /** 中文通道（中文原声 + 中文 TTS，语种未知时也走此通道） */
    private int zhChannel = 1;
    /** 印尼语通道（印尼语原声 + 印尼语 TTS） */
    private int idChannel = 2;
}
