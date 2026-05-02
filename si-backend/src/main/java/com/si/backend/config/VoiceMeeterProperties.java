package com.si.backend.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "voicemeeter")
public class VoiceMeeterProperties {

    private boolean enabled = false;
    private int sourceChannel = 1;
    private int targetChannel = 2;
}
