package com.si.backend.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configurable per-unit billing rates and budget thresholds for cost analysis.
 * All USD amounts. Override via environment variables or application-*.yml.
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.cost")
public class CostRatesProperties {

    private Rates rates = new Rates();
    private Budget budget = new Budget();

    @Data
    public static class Rates {
        /** Azure Speech: price per hour of audio */
        private double asrPerHourUsd = 1.00;
        /** Azure Translator: price per million characters */
        private double transPerMillionCharsUsd = 10.00;
        /** Cartesia TTS: price per million characters */
        private double ttsPerMillionCharsUsd = 1.50;
        /** LLM input tokens: price per million tokens */
        private double llmInPerMillionTokensUsd = 0.25;
        /** LLM output tokens: price per million tokens */
        private double llmOutPerMillionTokensUsd = 1.25;
    }

    @Data
    public static class Budget {
        /** Monthly budget cap in USD (0 = disabled) */
        private double monthlyUsd = 0.0;
        /** Per-session budget alert threshold in USD (0 = disabled) */
        private double sessionUsd = 0.0;
    }
}
