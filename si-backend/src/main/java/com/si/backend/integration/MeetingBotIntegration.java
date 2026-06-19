package com.si.backend.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.si.backend.common.BizException;
import com.si.backend.common.ErrorCode;
import com.si.backend.config.ServiceSignatureProperties;
import com.si.backend.security.ServiceSignature;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Integration client for sending user-confirmed meeting notifications through the Teams bot.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MeetingBotIntegration {

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** 下行签名 keyId,与 BotProxyIntegration 保持一致。 */
    private static final String DOWNSTREAM_KEY_ID = "java-backend";
    private static final String NOTIFICATION_PATH = "/api/meetings/notification";
    private static final int DELIVERY_FAILURE_STATUS = 502;

    @Value("${bot.api.url:http://localhost:3978}")
    private String botApiUrl;

    private final ObjectMapper objectMapper;
    private final ServiceSignature serviceSignature;
    private final ServiceSignatureProperties signatureProperties;

    public SendResult sendNotification(String content, List<String> recipients) {
        return sendNotification(content, recipients, false);
    }

    public SendResult sendMeetingNotification(String content, List<String> recipients) {
        return sendNotification(content, recipients, true);
    }

    private SendResult sendNotification(String content, List<String> recipients, boolean allowDeliveryFailureReport) {
        long startMs = System.currentTimeMillis();
        log.info("[MeetingBotIntegration] sendNotification start, recipientCount={}, contentLen={}, allowDeliveryFailureReport={}",
                recipients.size(), content.length(), allowDeliveryFailureReport);
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("content", content);
            payload.put("recipients", recipients);
            byte[] bodyBytes = objectMapper.writeValueAsString(payload).getBytes(StandardCharsets.UTF_8);
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(botApiUrl.replaceAll("/+$", "") + NOTIFICATION_PATH))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(bodyBytes));
            applyDownstreamSignature(builder, bodyBytes);
            HttpRequest request = builder.build();
            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            long elapsedMs = System.currentTimeMillis() - startMs;
            log.info("[MeetingBotIntegration] sendNotification end, statusCode={}, elapsedMs={}",
                    response.statusCode(), elapsedMs);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                if (allowDeliveryFailureReport
                        && response.statusCode() == DELIVERY_FAILURE_STATUS
                        && response.body() != null
                        && !response.body().isBlank()) {
                    log.warn("[MeetingBotIntegration] sendNotification delivery failure report, statusCode={}, elapsedMs={}",
                            response.statusCode(), elapsedMs);
                    return new SendResult(response.statusCode(), response.body());
                }
                throw BizException.of(
                        ErrorCode.INTERNAL_ERROR,
                        "Teams Bot 发送通知失败，状态码：" + response.statusCode()
                );
            }
            return new SendResult(response.statusCode(), response.body());
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("[MeetingBotIntegration] sendNotification failed, elapsedMs={}",
                    System.currentTimeMillis() - startMs, e);
            throw BizException.of(ErrorCode.INTERNAL_ERROR, "Teams Bot 发送通知失败：" + e.getMessage());
        }
    }

    /** 配置了下行密钥时为通知请求签名;未配置则跳过(C# 兼容忽略)。 */
    private void applyDownstreamSignature(HttpRequest.Builder builder, byte[] bodyBytes) {
        String key = signatureProperties.getDownstreamKey();
        if (key == null || key.isBlank()) {
            return;
        }
        Map<String, String> signed = serviceSignature.sign(
                key, DOWNSTREAM_KEY_ID, "POST", NOTIFICATION_PATH, "", bodyBytes);
        signed.forEach(builder::header);
    }

    public record SendResult(int statusCode, String responseBody) {
    }
}
