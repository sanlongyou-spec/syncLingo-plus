package com.si.backend.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.si.backend.common.BizException;
import com.si.backend.common.Constants;
import com.si.backend.common.ErrorCode;
import com.si.backend.config.CartesiaProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;

/**
 * Integration for Cartesia voice cloning. The API key never leaves the backend.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CartesiaVoiceCloneIntegration {

    private static final String CLONE_PATH = "/voices/clone";
    private static final String FORM_FIELD_CLIP = "clip";
    private static final String FORM_FIELD_NAME = "name";
    private static final String FORM_FIELD_LANGUAGE = "language";
    private static final int HTTP_TIMEOUT_SECONDS = 60;

    private final CartesiaProperties properties;
    private final ObjectMapper objectMapper;

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(HTTP_TIMEOUT_SECONDS))
            .readTimeout(Duration.ofSeconds(HTTP_TIMEOUT_SECONDS))
            .writeTimeout(Duration.ofSeconds(HTTP_TIMEOUT_SECONDS))
            .build();

    public CloneResult cloneVoice(
            String voiceName,
            String language,
            byte[] audioBytes,
            String fileName,
            String contentType
    ) {
        long start = System.currentTimeMillis();
        log.info("[CartesiaVoiceCloneIntegration] cloneVoice start, voiceName={}, language={}, fileName={}, bytes={}",
                voiceName, language, fileName, audioBytes == null ? 0 : audioBytes.length);
        if (properties.getApiKey() == null || properties.getApiKey().isBlank()) {
            throw BizException.of(ErrorCode.TTS_VOICE_CLONE_ERROR, "Cartesia API Key 未配置");
        }
        MediaType mediaType = MediaType.parse(contentType == null || contentType.isBlank()
                ? "audio/webm"
                : contentType);
        RequestBody clipBody = RequestBody.create(audioBytes, mediaType);
        RequestBody body = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(FORM_FIELD_NAME, voiceName)
                .addFormDataPart(FORM_FIELD_LANGUAGE, language)
                .addFormDataPart(FORM_FIELD_CLIP, fileName == null || fileName.isBlank() ? "voice.webm" : fileName, clipBody)
                .build();
        Request request = new Request.Builder()
                .url(httpBaseUrl() + CLONE_PATH)
                .addHeader("Authorization", "Bearer " + properties.getApiKey())
                .addHeader("Cartesia-Version", Constants.CARTESIA_VERSION_HEADER)
                .post(body)
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                log.warn("[CartesiaVoiceCloneIntegration] cloneVoice failed, status={}, bodyLen={}, elapsedMs={}",
                        response.code(), responseBody.length(), System.currentTimeMillis() - start);
                throw BizException.of(ErrorCode.TTS_VOICE_CLONE_ERROR, "Cartesia 音色克隆失败：" + response.code());
            }
            String voiceId = extractVoiceId(responseBody);
            log.info("[CartesiaVoiceCloneIntegration] cloneVoice end, status={}, voiceId={}, elapsedMs={}",
                    response.code(), voiceId, System.currentTimeMillis() - start);
            return new CloneResult(voiceId, responseBody);
        } catch (BizException e) {
            throw e;
        } catch (IOException e) {
            log.error("[CartesiaVoiceCloneIntegration] cloneVoice request error, elapsedMs={}",
                    System.currentTimeMillis() - start, e);
            throw BizException.of(ErrorCode.TTS_VOICE_CLONE_ERROR, "Cartesia 音色克隆请求失败：" + e.getMessage());
        }
    }

    private String extractVoiceId(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            String id = firstText(root, "id", "voice_id", "voiceId");
            if (id != null && !id.isBlank()) {
                return id.trim();
            }
        } catch (Exception e) {
            log.warn("[CartesiaVoiceCloneIntegration] parse clone response failed, bodyLen={}",
                    responseBody == null ? 0 : responseBody.length(), e);
        }
        throw BizException.of(ErrorCode.TTS_VOICE_CLONE_ERROR, "Cartesia 未返回音色 ID");
    }

    private String firstText(JsonNode root, String... fields) {
        for (String field : fields) {
            JsonNode value = root.path(field);
            if (value.isTextual() && !value.asText().isBlank()) {
                return value.asText();
            }
        }
        return null;
    }

    private String httpBaseUrl() {
        String configured = properties.getApiUrl();
        if (configured == null || configured.isBlank()) {
            return Constants.CARTESIA_API_ENDPOINT;
        }
        String trimmed = configured.trim();
        if (trimmed.startsWith("wss://")) {
            return "https://" + trimmed.substring("wss://".length());
        }
        if (trimmed.startsWith("ws://")) {
            return "http://" + trimmed.substring("ws://".length());
        }
        return trimmed;
    }

    public record CloneResult(String voiceId, String responseBody) {
    }
}
