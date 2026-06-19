package com.si.backend.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.si.backend.common.Constants;
import com.si.backend.config.VoiceGenderProperties;
import com.si.backend.service.VoiceGender;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.stereotype.Component;

import java.util.Base64;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class VoiceGenderIntegration {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final VoiceGenderProperties properties;
    private final ObjectMapper objectMapper;
    private final OkHttpClient client;
    private final String endpointUrl;

    public VoiceGenderIntegration(VoiceGenderProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.endpointUrl = normalizeBaseUrl(properties.getUrl()) + "/voice-gender";
        this.client = new OkHttpClient.Builder()
                .connectTimeout(300, TimeUnit.MILLISECONDS)
                .readTimeout(properties.getTimeoutMs(), TimeUnit.MILLISECONDS)
                .writeTimeout(500, TimeUnit.MILLISECONDS)
                .retryOnConnectionFailure(false)
                .build();
        log.info("[VoiceGenderIntegration] enabled={}, url={}, timeoutMs={}",
                properties.isEnabled(), endpointUrl, properties.getTimeoutMs());
    }

    public VoiceGenderDetectionResult detect(byte[] pcmData, String speakerId) {
        long startMs = System.currentTimeMillis();
        if (!properties.isEnabled() || pcmData == null || pcmData.length == 0) {
            log.info("[VoiceGenderIntegration] detect skipped, enabled={}, speakerId={}, audioBytes={}",
                    properties.isEnabled(), speakerId, pcmData != null ? pcmData.length : 0);
            return VoiceGenderDetectionResult.unavailable(0L);
        }
        try {
            log.info("[VoiceGenderIntegration] detect start, speakerId={}, audioBytes={}, sampleRate={}, timeoutMs={}",
                    speakerId, pcmData.length, properties.getSampleRate(), properties.getTimeoutMs());
            String audioBase64 = Base64.getEncoder().encodeToString(pcmData);
            String body = objectMapper.writeValueAsString(Map.of(
                    "audio_base64", audioBase64,
                    "sample_rate", properties.getSampleRate(),
                    "encoding", Constants.CARTESIA_ENCODING_PCM_S16LE,
                    "speaker_id", speakerId == null ? "" : speakerId
            ));
            log.debug("[VoiceGenderIntegration] request built, speakerId={}, base64Len={}, bodyLen={}",
                    speakerId, audioBase64.length(), body.length());
            Request request = new Request.Builder()
                    .url(endpointUrl)
                    .post(RequestBody.create(body, JSON))
                    .build();
            try (Response response = client.newCall(request).execute()) {
                long costMs = System.currentTimeMillis() - startMs;
                if (!response.isSuccessful()) {
                    log.warn("[VoiceGenderIntegration] HTTP {}, speakerId={}, audioBytes={}, costMs={}",
                            response.code(), speakerId, pcmData.length, costMs);
                    return VoiceGenderDetectionResult.unavailable(costMs);
                }
                String responseBody = response.body() != null ? response.body().string() : "";
                JsonNode node = objectMapper.readTree(responseBody);
                VoiceGender gender = VoiceGender.from(node.path("gender").asText(null));
                double confidence = node.path("confidence").asDouble(0D);
                double maleScore = node.path("male_score").asDouble(0D);
                double femaleScore = node.path("female_score").asDouble(0D);
                double childScore = node.path("child_score").asDouble(0D);
                boolean modelAvailable = node.path("model_available").asBoolean(false);
                long serviceLatencyMs = Math.round(node.path("latency_ms").asDouble(costMs));
                String serviceReason = node.path("reason").asText("unknown");
                log.info("[VoiceGenderIntegration] detected speakerId={}, audioBytes={}, gender={}, serviceReason={}, confidence={}, male={}, female={}, child={}, costMs={}, serviceMs={}, modelAvailable={}, responseLen={}",
                        speakerId, pcmData.length, gender, serviceReason, confidence, maleScore, femaleScore, childScore,
                        costMs, serviceLatencyMs, modelAvailable, responseBody.length());
                return new VoiceGenderDetectionResult(
                        gender, confidence, maleScore, femaleScore, childScore, serviceLatencyMs, modelAvailable, serviceReason);
            }
        } catch (java.io.InterruptedIOException e) {
            long costMs = System.currentTimeMillis() - startMs;
            log.warn("[VoiceGenderIntegration] timeout speakerId={}, audioBytes={}, costMs={}, budgetMs={}",
                    speakerId, pcmData.length, costMs, properties.getTimeoutMs());
            return VoiceGenderDetectionResult.unavailable(costMs);
        } catch (Exception e) {
            long costMs = System.currentTimeMillis() - startMs;
            log.warn("[VoiceGenderIntegration] failed speakerId={}, audioBytes={}, costMs={}, error={}",
                    speakerId, pcmData.length, costMs, e.getMessage());
            return VoiceGenderDetectionResult.unavailable(costMs);
        }
    }

    private static String normalizeBaseUrl(String url) {
        String value = (url == null || url.isBlank()) ? "http://localhost:7000" : url.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }
}
