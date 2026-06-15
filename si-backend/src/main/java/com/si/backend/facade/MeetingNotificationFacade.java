package com.si.backend.facade;

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

    public MeetingNotificationPreviewVo preview(
            AuthenticatedActor actor,
            Long meetingId,
            MeetingNotificationPreviewRequest request
    ) {
        log.info("[MeetingNotificationFacade] preview start, meetingId={}, fileId={}",
                meetingId, request == null ? null : request.getFileId());
        if (request == null || request.getMeetingUrl() == null || request.getMeetingUrl().isBlank()) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "请填写会议链接");
        }
        String meetingUrl = request.getMeetingUrl().trim();
        meetingService.setMeetingUrl(actor, meetingId, meetingUrl);
        MeetingVo meeting = meetingService.getMeeting(actor, meetingId);
        String fileId = request.getFileId();
        String noticeText = fileId != null && !fileId.isBlank()
                ? preMeetingService.getDocText(fileId)
                : meetingService.getMeetingNoticeText(actor, meetingId);
        MeetingNoticeParser.MeetingNoticeDetails details = meetingNoticeParser.parse(noticeText, meeting.getTitle());
        List<String> participantNames = resolveParticipantNames(meetingId, fileId);
        MeetingNotificationService.NotificationPlan plan =
                meetingNotificationService.buildPlan(meeting.getTitle(), details.dateText(), participantNames);
        MeetingNotificationPreviewVo preview = toPreview(details, meetingUrl, participantNames, plan);
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
                meetingBotIntegration.sendNotification(request.getContent().trim(), deliveryRecipients);
        log.info("[MeetingNotificationFacade] send end, meetingId={}, selected={}, delivered={}, botStatus={}",
                meetingId, selected.size(), deliveryRecipients.size(), sendResult.statusCode());
        return MeetingNotificationSendVo.builder()
                .selectedRecipientCount(selected.size())
                .deliveryRecipientCount(deliveryRecipients.size())
                .botStatusCode(sendResult.statusCode())
                .build();
    }

    private MeetingNotificationPreviewVo toPreview(
            MeetingNoticeParser.MeetingNoticeDetails details,
            String meetingUrl,
            List<String> participantNames,
            MeetingNotificationService.NotificationPlan plan
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
}
