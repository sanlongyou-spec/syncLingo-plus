package com.si.backend.facade;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.si.backend.common.BizException;
import com.si.backend.dto.MeetingNotificationPreviewRequest;
import com.si.backend.dto.MeetingNotificationSendRequest;
import com.si.backend.integration.MeetingBotIntegration;
import com.si.backend.security.AuthenticatedActor;
import com.si.backend.service.MeetingNoticeParser;
import com.si.backend.service.MeetingNotificationService;
import com.si.backend.service.MeetingService;
import com.si.backend.service.PreMeetingService;
import com.si.backend.vo.MeetingNotificationPreviewVo;
import com.si.backend.vo.MeetingNotificationSendVo;
import com.si.backend.vo.MeetingVo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

/**
 * Verifies the meeting-notice notification orchestration that backs the meetings page.
 */
@ExtendWith(MockitoExtension.class)
class MeetingNotificationFacadeTest {

    private final MeetingService meetingService = org.mockito.Mockito.mock(MeetingService.class);
    private final PreMeetingService preMeetingService = org.mockito.Mockito.mock(PreMeetingService.class);
    private final MeetingNotificationService notificationService = org.mockito.Mockito.mock(MeetingNotificationService.class);
    private final MeetingBotIntegration botIntegration = org.mockito.Mockito.mock(MeetingBotIntegration.class);
    private final MeetingNotificationFacade facade = new MeetingNotificationFacade(
            meetingService,
            preMeetingService,
            new MeetingNoticeParser(),
            notificationService,
            botIntegration,
            new ObjectMapper()
    );

    private static final AuthenticatedActor ACTOR = new AuthenticatedActor(7L);

    @Test
    void previewUsesNoticeContentWithoutRequiringMeetingUrl() {
        Long meetingId = 11L;
        MeetingNotificationPreviewRequest request = new MeetingNotificationPreviewRequest();
        request.setFileId("notice-1");
        when(meetingService.getMeeting(ACTOR, meetingId)).thenReturn(MeetingVo.builder()
                .id(meetingId)
                .title("TBM（未成熟）专项会议")
                .build());
        when(preMeetingService.getDocText("notice-1")).thenReturn("""
                TBM（未成熟）专项会议
                会议时间：2026 年 06 月 10 日（周三）东八区 UTC+8 时间晚上 20:30-22:40
                会议地点：Teams Meeting 连线
                ID Teams 会议号码：435 961 345 123 2 (Password 密码: dk9c5g5C)
                https://teams.microsoft.com/l/meetup-join/example?context=test
                """);
        when(preMeetingService.extractMeetingEntities("notice-1"))
                .thenReturn(new PreMeetingService.MeetingEntities(List.of("李明"), "Teams"));
        when(notificationService.buildPlan("TBM（未成熟）专项会议", "2026年6月10日（周三）", List.of("李明")))
                .thenReturn(new MeetingNotificationService.NotificationPlan(
                        "TBM（未成熟）专项会议",
                        "2026年6月10日（周三）",
                        List.of(),
                        List.of(),
                        List.of("李明(UNMATCHED)")
                ));
        when(notificationService.buildNotificationContentFromNotice(org.mockito.ArgumentMatchers.contains("TBM（未成熟）专项会议")))
                .thenReturn("notice draft");

        MeetingNotificationPreviewVo preview = facade.preview(ACTOR, meetingId, request);

        assertEquals(null, preview.getMeetingUrl());
        assertEquals("notice draft", preview.getNotificationContent());
        assertEquals(List.of("李明"), preview.getParticipantNames());
        verify(meetingService, never()).setMeetingUrl(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void sendReturnsSuccessfulAndFailedDeliveryDetails() {
        Long meetingId = 12L;
        MeetingNotificationSendRequest request = new MeetingNotificationSendRequest();
        request.setContent("hello");
        request.setRecipients(List.of("liming@jlg.co.id"));
        MeetingNotificationService.Recipient recipient = new MeetingNotificationService.Recipient(
                "李明", "李明", "liming@jlg.co.id", "liming@jlg.co.id");
        when(meetingService.getMeeting(ACTOR, meetingId)).thenReturn(MeetingVo.builder()
                .id(meetingId)
                .title("季度会议")
                .build());
        when(preMeetingService.expectedParticipantNames(meetingId)).thenReturn(List.of("李明"));
        when(notificationService.buildPlan("季度会议", null, List.of("李明")))
                .thenReturn(new MeetingNotificationService.NotificationPlan(
                        "季度会议", null, List.of(recipient), List.of(), List.of()));
        when(notificationService.resolveDeliveryRecipients(List.of("liming@jlg.co.id")))
                .thenReturn(List.of("liming@jlg.co.id"));
        when(botIntegration.sendNotification("hello", List.of("liming@jlg.co.id")))
                .thenReturn(new MeetingBotIntegration.SendResult(200, """
                        {"sent":true,"sentCount":1,"failedCount":1,
                        "recipients":[{"recipient":"liming@jlg.co.id","displayName":"李明"}],
                        "failures":[{"recipient":"wangwu@jlg.co.id","error":"not found"}]}
                        """));

        MeetingNotificationSendVo result = facade.send(ACTOR, meetingId, request);

        assertEquals(1, result.getSelectedRecipientCount());
        assertEquals(1, result.getDeliveryRecipientCount());
        assertEquals(1, result.getSentCount());
        assertEquals(1, result.getFailedCount());
        assertEquals(List.of("liming@jlg.co.id"), result.getSuccessfulRecipients());
        assertEquals(List.of("wangwu@jlg.co.id：not found"), result.getFailedRecipients());
    }

    @Test
    void sendRejectsRecipientOutsideExpectedTeamsList() {
        Long meetingId = 13L;
        MeetingNotificationSendRequest request = new MeetingNotificationSendRequest();
        request.setContent("hello");
        request.setRecipients(List.of("outside@jlg.co.id"));
        when(meetingService.getMeeting(ACTOR, meetingId)).thenReturn(MeetingVo.builder()
                .id(meetingId)
                .title("季度会议")
                .build());
        when(preMeetingService.expectedParticipantNames(meetingId)).thenReturn(List.of("李明"));
        when(notificationService.buildPlan("季度会议", null, List.of("李明")))
                .thenReturn(new MeetingNotificationService.NotificationPlan(
                        "季度会议",
                        null,
                        List.of(new MeetingNotificationService.Recipient("李明", "李明", "liming@jlg.co.id", "liming@jlg.co.id")),
                        List.of(),
                        List.of()));

        assertThrows(BizException.class, () -> facade.send(ACTOR, meetingId, request));
    }
}
