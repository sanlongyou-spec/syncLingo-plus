package com.si.backend.facade;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.si.backend.common.BizException;
import com.si.backend.common.ErrorCode;
import com.si.backend.dto.MeetingNotificationPreviewRequest;
import com.si.backend.dto.MeetingNotificationSendRequest;
import com.si.backend.integration.MeetingBotIntegration;
import com.si.backend.security.AuthenticatedActor;
import com.si.backend.service.MeetingNoticeParser;
import com.si.backend.service.MeetingNotificationService;
import com.si.backend.service.MeetingService;
import com.si.backend.service.PreMeetingService;
import com.si.backend.vo.MeetingNotificationPreviewVo;
import com.si.backend.vo.MeetingNotificationRecipientVo;
import com.si.backend.vo.MeetingNotificationSendVo;
import com.si.backend.vo.MeetingVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Orchestrates meeting-notice parsing, account matching, preview confirmation, and Teams delivery.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MeetingNotificationFacade {

    private final MeetingService meetingService;
    private final PreMeetingService preMeetingService;
    private final MeetingNoticeParser meetingNoticeParser;
    private final MeetingNotificationService meetingNotificationService;
    private final MeetingBotIntegration meetingBotIntegration;
    private final ObjectMapper objectMapper;

    public MeetingNotificationPreviewVo preview(
            AuthenticatedActor actor,
            Long meetingId,
            MeetingNotificationPreviewRequest request
    ) {
        log.info("[MeetingNotificationFacade] preview start, meetingId={}, fileId={}",
                meetingId, request == null ? null : request.getFileId());
        MeetingVo meeting = meetingService.getMeeting(actor, meetingId);
        String fileId = request == null ? null : request.getFileId();
        String noticeText = fileId != null && !fileId.isBlank()
                ? preMeetingService.getDocText(fileId)
                : meetingService.getMeetingNoticeText(actor, meetingId);
        MeetingNoticeParser.MeetingNoticeDetails details = meetingNoticeParser.parse(noticeText, meeting.getTitle());
        String meetingUrl = requireMeetingUrl(request == null ? null : request.getMeetingUrl());
        meetingService.setMeetingUrl(actor, meetingId, meetingUrl);
        List<String> participantNames = resolveParticipantNames(meetingId, fileId);
        MeetingNotificationService.NotificationPlan plan =
                meetingNotificationService.buildPlan(meeting.getTitle(), details.dateText(), participantNames);
        MeetingNotificationPreviewVo preview = toPreview(details, participantNames, plan, meetingUrl);
        log.info("[MeetingNotificationFacade] preview end, meetingId={}, participants={}, matched={}, unmatched={}",
                meetingId, participantNames.size(), preview.getTeamsRecipients().size(), preview.getUnmatched().size());
        return preview;
    }

    public List<MeetingNotificationRecipientVo> recipients(AuthenticatedActor actor, Long meetingId) {
        log.info("[MeetingNotificationFacade] recipients start, meetingId={}", meetingId);
        MeetingVo meeting = meetingService.getMeeting(actor, meetingId);
        List<String> participantNames = preMeetingService.expectedParticipantNames(meetingId);
        MeetingNotificationService.NotificationPlan plan =
                meetingNotificationService.buildPlan(meeting.getTitle(), null, participantNames);
        List<MeetingNotificationRecipientVo> recipients = mapRecipients(plan.teamsRecipients());
        log.info("[MeetingNotificationFacade] recipients end, meetingId={}, expected={}, matched={}",
                meetingId, participantNames.size(), recipients.size());
        return recipients;
    }

    public MeetingNotificationSendVo send(
            AuthenticatedActor actor,
            Long meetingId,
            MeetingNotificationSendRequest request
    ) {
        log.info("[MeetingNotificationFacade] send start, meetingId={}, selectedCount={}",
                meetingId, request == null || request.getRecipients() == null ? 0 : request.getRecipients().size());
        if (request == null || request.getContent() == null || request.getContent().isBlank()) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "拟发送通知不能为空");
        }
        List<String> requested = normalizeRecipients(request.getRecipients());
        if (requested.isEmpty()) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "请至少选择一个通知账号");
        }
        MeetingVo meeting = meetingService.getMeeting(actor, meetingId);
        MeetingNotificationService.NotificationPlan plan = meetingNotificationService.buildPlan(
                meeting.getTitle(),
                null,
                preMeetingService.expectedParticipantNames(meetingId)
        );
        Map<String, String> displayNames = buildRecipientDisplayNames(plan.teamsRecipients());
        Map<String, String> allowed = new LinkedHashMap<>();
        for (MeetingNotificationService.Recipient recipient : plan.teamsRecipients()) {
            allowed.put(recipient.email().toLowerCase(Locale.ROOT), recipient.email());
            allowed.put(recipient.teamsAccount().toLowerCase(Locale.ROOT), recipient.teamsAccount());
        }
        List<String> selected = new ArrayList<>();
        for (String recipient : requested) {
            String allowedRecipient = allowed.get(recipient.toLowerCase(Locale.ROOT));
            if (allowedRecipient == null) {
                throw BizException.of(ErrorCode.BAD_REQUEST, "通知账号不在该会议应参会名单中：" + recipient);
            }
            if (!selected.contains(allowedRecipient)) {
                selected.add(allowedRecipient);
            }
        }
        List<String> deliveryRecipients = meetingNotificationService.resolveDeliveryRecipients(selected);
        MeetingBotIntegration.SendResult sendResult =
                meetingBotIntegration.sendMeetingNotification(request.getContent().trim(), deliveryRecipients);
        DeliveryReport report = parseDeliveryReport(sendResult.responseBody(), deliveryRecipients);
        List<String> successfulRecipients = displaySuccessfulRecipients(report.successfulRecipients(), displayNames);
        List<String> failedRecipients = displayFailedRecipients(report.failedRecipients(), displayNames);
        log.info("[MeetingNotificationFacade] send end, meetingId={}, selected={}, delivered={}, botStatus={}, sent={}, failed={}",
                meetingId, selected.size(), deliveryRecipients.size(), sendResult.statusCode(),
                report.sentCount(), report.failedCount());
        MeetingNotificationSendVo result = MeetingNotificationSendVo.builder()
                .selectedRecipientCount(selected.size())
                .deliveryRecipientCount(deliveryRecipients.size())
                .botStatusCode(sendResult.statusCode())
                .sentCount(report.sentCount())
                .failedCount(report.failedCount())
                .successfulRecipients(successfulRecipients)
                .failedRecipients(failedRecipients)
                .error(report.error())
                .build();
        persistSendResult(actor, meetingId, result);
        return result;
    }

    private void persistSendResult(AuthenticatedActor actor, Long meetingId, MeetingNotificationSendVo result) {
        try {
            meetingService.saveNotificationResult(actor, meetingId, objectMapper.writeValueAsString(result));
        } catch (Exception e) {
            log.warn("[MeetingNotificationFacade] persist send result failed, meetingId={}", meetingId, e);
        }
    }

    private MeetingNotificationPreviewVo toPreview(
            MeetingNoticeParser.MeetingNoticeDetails details,
            List<String> participantNames,
            MeetingNotificationService.NotificationPlan plan,
            String meetingUrl
    ) {
        return MeetingNotificationPreviewVo.builder()
                .meetingName(details.meetingName())
                .dateText(details.dateText())
                .timeLines(details.timeLines())
                .venue(details.venue())
                .meetingCode(details.meetingCode())
                .passcode(details.passcode())
                .meetingUrl(meetingUrl)
                .notificationContent(meetingNotificationService.buildNotificationContent(details, meetingUrl))
                .participantNames(participantNames)
                .teamsRecipients(mapRecipients(plan.teamsRecipients()))
                .nonTeamsSkipped(plan.nonTeamsSkipped())
                .unmatched(plan.unmatched())
                .build();
    }

    private String requireMeetingUrl(String meetingUrl) {
        String normalized = meetingUrl == null ? "" : meetingUrl.trim();
        if (normalized.isBlank()) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "会议链接不能为空");
        }
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "会议链接必须以 http:// 或 https:// 开头");
        }
        return normalized;
    }

    private List<String> resolveParticipantNames(Long meetingId, String fileId) {
        if (fileId != null && !fileId.isBlank()) {
            List<String> freshNames = preMeetingService.extractMeetingEntities(fileId).participantNames();
            if (!freshNames.isEmpty()) {
                return freshNames;
            }
        }
        return preMeetingService.expectedParticipantNames(meetingId);
    }

    private List<MeetingNotificationRecipientVo> mapRecipients(
            List<MeetingNotificationService.Recipient> recipients
    ) {
        return recipients.stream()
                .map(recipient -> MeetingNotificationRecipientVo.builder()
                        .scheduleName(recipient.scheduleName())
                        .accountName(recipient.accountName())
                        .email(recipient.email())
                        .teamsAccount(recipient.teamsAccount())
                        .build())
                .toList();
    }

    private List<String> normalizeRecipients(List<String> recipients) {
        if (recipients == null) {
            return List.of();
        }
        return recipients.stream()
                .filter(recipient -> recipient != null && !recipient.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }

    private Map<String, String> buildRecipientDisplayNames(List<MeetingNotificationService.Recipient> recipients) {
        Map<String, String> displayNames = new LinkedHashMap<>();
        for (MeetingNotificationService.Recipient recipient : recipients) {
            String displayName = recipientDisplayName(recipient);
            registerDisplayName(displayNames, recipient.email(), displayName);
            registerDisplayName(displayNames, recipient.teamsAccount(), displayName);
            registerDisplayName(displayNames, recipient.accountName(), displayName);
            registerDisplayName(displayNames, recipient.scheduleName(), displayName);
        }
        return displayNames;
    }

    private String recipientDisplayName(MeetingNotificationService.Recipient recipient) {
        String scheduleName = cleanText(recipient.scheduleName());
        if (scheduleName != null) {
            return scheduleName;
        }
        String accountName = cleanText(recipient.accountName());
        if (accountName != null) {
            return accountName;
        }
        String email = cleanText(recipient.email());
        if (email != null) {
            return email;
        }
        return cleanText(recipient.teamsAccount());
    }

    private void registerDisplayName(Map<String, String> displayNames, String key, String displayName) {
        String normalizedKey = normalizeLookupKey(key);
        String normalizedDisplayName = cleanText(displayName);
        if (normalizedKey != null && normalizedDisplayName != null) {
            displayNames.putIfAbsent(normalizedKey, normalizedDisplayName);
        }
    }

    private List<String> displaySuccessfulRecipients(
            List<DeliveryRecipient> recipients,
            Map<String, String> displayNames
    ) {
        List<String> values = new ArrayList<>();
        for (DeliveryRecipient recipient : recipients) {
            addUnique(values, displayNameFor(recipient.recipient(), recipient.displayName(), displayNames));
        }
        return values;
    }

    private List<String> displayFailedRecipients(
            List<DeliveryFailure> failures,
            Map<String, String> displayNames
    ) {
        List<String> values = new ArrayList<>();
        for (DeliveryFailure failure : failures) {
            String displayName = displayNameFor(failure.recipient(), failure.displayName(), displayNames);
            String reason = cleanText(failure.error());
            addUnique(values, reason == null ? displayName : displayName + ": " + reason);
        }
        return values;
    }

    private String displayNameFor(String recipient, String fallbackName, Map<String, String> displayNames) {
        String key = normalizeLookupKey(recipient);
        if (key != null && displayNames.containsKey(key)) {
            return displayNames.get(key);
        }
        String displayName = cleanText(fallbackName);
        if (displayName != null) {
            return displayName;
        }
        String rawRecipient = cleanText(recipient);
        return rawRecipient == null ? "unknown recipient" : rawRecipient;
    }

    private void addUnique(List<String> values, String value) {
        if (value != null && !value.isBlank() && !values.contains(value)) {
            values.add(value);
        }
    }

    private DeliveryReport parseDeliveryReport(String responseBody, List<String> fallbackRecipients) {
        if (responseBody == null || responseBody.isBlank()) {
            return new DeliveryReport(fallbackRecipients.size(), 0, fallbackDeliveryRecipients(fallbackRecipients), List.of(), null);
        }
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            int sentCount = root.path("sentCount").asInt(-1);
            int failedCount = root.path("failedCount").asInt(-1);
            List<DeliveryRecipient> successful = parseRecipientList(root.path("recipients"));
            List<DeliveryFailure> failed = parseFailureList(root.path("failures"));
            if (sentCount < 0) {
                sentCount = successful.isEmpty() ? fallbackRecipients.size() : successful.size();
            }
            if (failedCount < 0) {
                failedCount = failed.size();
            }
            if (successful.isEmpty() && sentCount > 0) {
                successful = fallbackDeliveryRecipients(fallbackRecipients);
            }
            String error = root.path("error").isTextual() ? root.path("error").asText() : null;
            return new DeliveryReport(sentCount, failedCount, successful, failed, error);
        } catch (Exception e) {
            log.warn("[MeetingNotificationFacade] parseDeliveryReport failed, bodyLen={}",
                    responseBody.length(), e);
            return new DeliveryReport(fallbackRecipients.size(), 0, fallbackDeliveryRecipients(fallbackRecipients), List.of(), null);
        }
    }

    private List<DeliveryRecipient> fallbackDeliveryRecipients(List<String> fallbackRecipients) {
        return fallbackRecipients.stream()
                .map(recipient -> new DeliveryRecipient(recipient, null))
                .toList();
    }

    private List<DeliveryRecipient> parseRecipientList(JsonNode nodes) {
        if (!nodes.isArray()) {
            return List.of();
        }
        List<DeliveryRecipient> recipients = new ArrayList<>();
        for (JsonNode node : nodes) {
            String recipient = node.isTextual() ? node.asText() : firstText(node, "recipient", "email", "aadId", "displayName");
            String displayName = node.isTextual() ? null : firstText(node, "displayName", "name");
            if (recipient == null || recipient.isBlank()) {
                recipient = displayName;
            }
            DeliveryRecipient value = new DeliveryRecipient(cleanText(recipient), cleanText(displayName));
            if (value.recipient() != null && !recipients.contains(value)) {
                recipients.add(value);
            }
        }
        return recipients;
    }

    private List<DeliveryFailure> parseFailureList(JsonNode nodes) {
        if (!nodes.isArray()) {
            return List.of();
        }
        List<DeliveryFailure> failures = new ArrayList<>();
        for (JsonNode node : nodes) {
            String recipient = node.isTextual() ? node.asText() : firstText(node, "recipient", "email", "aadId", "displayName");
            String displayName = node.isTextual() ? null : firstText(node, "displayName", "name");
            String error = node.isTextual() ? null : firstText(node, "error", "message");
            if (recipient == null || recipient.isBlank()) {
                recipient = displayName;
            }
            DeliveryFailure value = new DeliveryFailure(cleanText(recipient), cleanText(displayName), cleanText(error));
            if ((value.recipient() != null || value.error() != null) && !failures.contains(value)) {
                failures.add(value);
            }
        }
        return failures;
    }

    private String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (value.isTextual() && !value.asText().isBlank()) {
                return value.asText();
            }
        }
        return null;
    }

    private String normalizeLookupKey(String value) {
        String cleaned = cleanText(value);
        return cleaned == null ? null : cleaned.toLowerCase(Locale.ROOT);
    }

    private String cleanText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private record DeliveryRecipient(String recipient, String displayName) {
    }

    private record DeliveryFailure(String recipient, String displayName, String error) {
    }

    private record DeliveryReport(
            int sentCount,
            int failedCount,
            List<DeliveryRecipient> successfulRecipients,
            List<DeliveryFailure> failedRecipients,
            String error
    ) {
    }
}
