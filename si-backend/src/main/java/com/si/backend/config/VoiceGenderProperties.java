package com.si.backend.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "voice-gender.service")
public class VoiceGenderProperties {

    private boolean enabled = false;
    private String url = "http://localhost:7000";
    private int timeoutMs = 1500;
    private int sampleRate = 16000;
    private int minAudioSeconds = 6;
    private int maxAudioSeconds = 8;
    private int maxBufferSeconds = 12;
    private int maxRetries = 3;
    private int retryIntervalSeconds = 10;
    private int queueCapacity = 32;
    private double minConfidence = 0.75D;
    private double minMargin = 0.15D;
}
