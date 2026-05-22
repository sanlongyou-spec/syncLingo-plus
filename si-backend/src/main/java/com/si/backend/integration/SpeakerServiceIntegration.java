package com.si.backend.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.si.backend.common.Constants;
import com.si.backend.config.SpeakerServiceProperties;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * HTTP client for the self-hosted Pyannote/SpeechBrain speaker recognition microservice.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SpeakerServiceIntegration {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final SpeakerServiceProperties properties;
    private final ObjectMapper objectMapper;
    private final OkHttpClient httpClient;

    public boolean isEnabled() {
        return Boolean.TRUE.equals(properties.getEnabled());
    }

    /**
     * Enroll a speaker by name with a WAV audio sample.
     *
     * @param name      speaker name (must match the name stored in SpeakerIdentity.personName)
     * @param audioBytes raw WAV or PCM bytes (PCM will be wrapped in WAV header automatically)
     * @param isPcm     true if audioBytes is raw PCM (16-bit, 16kHz, mono), false if already WAV
     * @return enrollment count for this speaker, or -1 on error
     */
    public int enroll(String name, byte[] audioBytes, boolean isPcm) {
        if (!isEnabled()) return -1;
        try {
            byte[] wavBytes = isPcm ? wrapPcmAsWav(audioBytes) : audioBytes;
            String b64 = Base64.getEncoder().encodeToString(wavBytes);
            String body = objectMapper.writeValueAsString(new java.util.HashMap<>() {{
                put("name", name);
                put("audio_base64", b64);
            }});
            Request request = new Request.Builder()
                    .url(baseUrl() + "/enroll")
                    .post(RequestBody.create(body.getBytes(StandardCharsets.UTF_8), JSON))
                    .build();
            long start = System.currentTimeMillis();
            try (Response response = httpClient.newCall(request).execute()) {
                long costMs = System.currentTimeMillis() - start;
                String respBody = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    log.warn("[SpeakerServiceIntegration] enroll HTTP error, code={}, body={}, costMs={}",
                            response.code(), truncate(respBody), costMs);
                    return -1;
                }
                JsonNode root = objectMapper.readTree(respBody);
                int count = root.path("enrollment_count").asInt(1);
                log.info("[SpeakerServiceIntegration] enroll success, name={}, enrollmentCount={}, costMs={}", name, count, costMs);
                return count;
            }
        } catch (Exception e) {
            log.warn("[SpeakerServiceIntegration] enroll failed, name={}, reason={}", name, e.getMessage());
            return -1;
        }
    }

    /**
     * Identify the speaker in the given PCM sample.
     *
     * @param pcmSample raw PCM bytes (16-bit, 16kHz, mono)
     * @param candidates optional list of speaker names to restrict identification to
     * @return identification result, or empty if not identified
     */
    public Optional<IdentifyResult> identify(byte[] pcmSample, List<String> candidates) {
        if (!isEnabled() || pcmSample == null || pcmSample.length == 0) {
            return Optional.empty();
        }
        try {
            byte[] wavBytes = wrapPcmAsWav(pcmSample);
            String b64 = Base64.getEncoder().encodeToString(wavBytes);
            java.util.Map<String, Object> bodyMap = new java.util.HashMap<>();
            bodyMap.put("audio_base64", b64);
            if (candidates != null && !candidates.isEmpty()) {
                bodyMap.put("candidates", candidates);
            }
            String body = objectMapper.writeValueAsString(bodyMap);
            Request request = new Request.Builder()
                    .url(baseUrl() + "/identify")
                    .post(RequestBody.create(body.getBytes(StandardCharsets.UTF_8), JSON))
                    .build();
            long start = System.currentTimeMillis();
            try (Response response = httpClient.newCall(request).execute()) {
                long costMs = System.currentTimeMillis() - start;
                String respBody = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    log.warn("[SpeakerServiceIntegration] identify HTTP error, code={}, body={}, costMs={}",
                            response.code(), truncate(respBody), costMs);
                    return Optional.empty();
                }
                JsonNode root = objectMapper.readTree(respBody);
                boolean identified = root.path("identified").asBoolean(false);
                double score = root.path("score").asDouble(0D);
                String name = root.path("name").asText(null);
                log.info("[SpeakerServiceIntegration] identify result, identified={}, name={}, score={}, costMs={}",
                        identified, name, score, costMs);
                if (!identified || name == null || name.isBlank()) {
                    return Optional.empty();
                }
                if (score < properties.getMinScore()) {
                    log.info("[SpeakerServiceIntegration] score {} below local threshold {}, rejecting", score, properties.getMinScore());
                    return Optional.empty();
                }
                return Optional.of(IdentifyResult.builder().personName(name).score(score).build());
            }
        } catch (Exception e) {
            log.warn("[SpeakerServiceIntegration] identify failed, reason={}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Delete all enrollments for a speaker.
     */
    public boolean deleteEnrollment(String name) {
        if (!isEnabled()) return false;
        try {
            Request request = new Request.Builder()
                    .url(baseUrl() + "/enroll/" + name)
                    .delete()
                    .build();
            try (Response response = httpClient.newCall(request).execute()) {
                return response.isSuccessful();
            }
        } catch (Exception e) {
            log.warn("[SpeakerServiceIntegration] deleteEnrollment failed, name={}, reason={}", name, e.getMessage());
            return false;
        }
    }

    public boolean isHealthy() {
        try {
            Request request = new Request.Builder().url(baseUrl() + "/health").get().build();
            try (Response response = httpClient.newCall(request).execute()) {
                return response.isSuccessful();
            }
        } catch (Exception e) {
            return false;
        }
    }

    private String baseUrl() {
        String url = properties.getUrl();
        return url != null && url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private String truncate(String value) {
        if (value == null) return "";
        return value.length() <= 300 ? value : value.substring(0, 300);
    }

    private byte[] wrapPcmAsWav(byte[] pcmSample) {
        int dataSize = pcmSample.length;
        int byteRate = Constants.DEFAULT_SAMPLE_RATE_ASR * Constants.AUDIO_CHANNELS_MONO * (Constants.BITS_PER_SAMPLE / 8);
        ByteArrayOutputStream wav = new ByteArrayOutputStream(Constants.WAV_HEADER_BYTES + dataSize);
        wav.writeBytes("RIFF".getBytes(StandardCharsets.US_ASCII));
        wav.writeBytes(intLe(Constants.WAV_HEADER_BYTES - 8 + dataSize));
        wav.writeBytes("WAVE".getBytes(StandardCharsets.US_ASCII));
        wav.writeBytes("fmt ".getBytes(StandardCharsets.US_ASCII));
        wav.writeBytes(intLe(16));
        wav.writeBytes(shortLe((short) 1));
        wav.writeBytes(shortLe((short) Constants.AUDIO_CHANNELS_MONO));
        wav.writeBytes(intLe(Constants.DEFAULT_SAMPLE_RATE_ASR));
        wav.writeBytes(intLe(byteRate));
        wav.writeBytes(shortLe((short) (Constants.AUDIO_CHANNELS_MONO * (Constants.BITS_PER_SAMPLE / 8))));
        wav.writeBytes(shortLe((short) Constants.BITS_PER_SAMPLE));
        wav.writeBytes("data".getBytes(StandardCharsets.US_ASCII));
        wav.writeBytes(intLe(dataSize));
        wav.writeBytes(pcmSample);
        return wav.toByteArray();
    }

    private byte[] intLe(int value) {
        return ByteBuffer.allocate(Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array();
    }

    private byte[] shortLe(short value) {
        return ByteBuffer.allocate(Short.BYTES).order(ByteOrder.LITTLE_ENDIAN).putShort(value).array();
    }

    @Data
    @Builder
    public static class IdentifyResult {
        private String personName;
        private Double score;
    }
}
