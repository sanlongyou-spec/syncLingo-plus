package com.si.backend.service;

import com.si.backend.common.BizException;
import com.si.backend.entity.InterpretationSession;
import com.si.backend.entity.Meeting;
import com.si.backend.entity.MeetingActionItem;
import com.si.backend.entity.PersistentPreMeetingFile;
import com.si.backend.entity.SessionAudioRecord;
import com.si.backend.entity.SpeakerSummaryRecord;
import com.si.backend.mapper.MeetingActionItemMapper;
import com.si.backend.mapper.MeetingMapper;
import com.si.backend.mapper.PersistentPreMeetingFileMapper;
import com.si.backend.mapper.SessionAudioRecordMapper;
import com.si.backend.mapper.SpeakerSummaryRecordMapper;
import com.si.backend.security.AuthenticatedActor;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies fail-closed ownership checks and child-to-parent ownership resolution.
 */
class ResourceOwnershipPolicyTest {

    private final InterpretationSessionService sessionService = mock(InterpretationSessionService.class);
    private final MeetingMapper meetingMapper = mock(MeetingMapper.class);
    private final PersistentPreMeetingFileMapper fileMapper = mock(PersistentPreMeetingFileMapper.class);
    private final SpeakerSummaryRecordMapper speakerSummaryMapper = mock(SpeakerSummaryRecordMapper.class);
    private final MeetingActionItemMapper actionItemMapper = mock(MeetingActionItemMapper.class);
    private final SessionAudioRecordMapper audioRecordMapper = mock(SessionAudioRecordMapper.class);
    private final com.si.backend.mapper.MeetingMemberMapper meetingMemberMapper =
            mock(com.si.backend.mapper.MeetingMemberMapper.class);
    private final com.si.backend.mapper.SupportAccessGrantMapper supportAccessGrantMapper =
            mock(com.si.backend.mapper.SupportAccessGrantMapper.class);
    private final ResourceOwnershipPolicy policy = new ResourceOwnershipPolicy(
            sessionService,
            meetingMapper,
            fileMapper,
            speakerSummaryMapper,
            actionItemMapper,
            audioRecordMapper,
            meetingMemberMapper,
            supportAccessGrantMapper
    );

    private static final AuthenticatedActor ACTOR = new AuthenticatedActor(5L);

    private com.si.backend.entity.Meeting meeting(long id, long ownerId) {
        com.si.backend.entity.Meeting m = new com.si.backend.entity.Meeting();
        m.setId(id);
        m.setUserId(ownerId);
        return m;
    }

    private com.si.backend.entity.MeetingMember member(long meetingId, long userId, String level) {
        com.si.backend.entity.MeetingMember mm = new com.si.backend.entity.MeetingMember();
        mm.setMeetingId(meetingId);
        mm.setUserId(userId);
        mm.setAccessLevel(level);
        return mm;
    }

    @Test
    void meetingAccess_ownerAllowed() {
        when(meetingMapper.findById(7L)).thenReturn(meeting(7L, 5L));
        org.junit.jupiter.api.Assertions.assertEquals(7L,
                policy.requireMeetingAccess(ACTOR, 7L, com.si.backend.security.AccessLevel.OPERATE).getId());
    }

    @Test
    void meetingAccess_assignedViewAllowsView_butNotOperate() {
        when(meetingMapper.findById(7L)).thenReturn(meeting(7L, 9L)); // 非 owner
        when(meetingMemberMapper.findMember(7L, 5L)).thenReturn(member(7L, 5L, "VIEW"));
        // VIEW 满足 VIEW
        org.junit.jupiter.api.Assertions.assertEquals(7L,
                policy.requireMeetingAccess(ACTOR, 7L, com.si.backend.security.AccessLevel.VIEW).getId());
        // VIEW 不满足 OPERATE → 404
        BizException e = assertThrows(BizException.class,
                () -> policy.requireMeetingAccess(ACTOR, 7L, com.si.backend.security.AccessLevel.OPERATE));
        assertEquals(404, e.getCode());
    }

    @Test
    void meetingAccess_assignedOperateSatisfiesViewAndOperate() {
        when(meetingMapper.findById(7L)).thenReturn(meeting(7L, 9L));
        when(meetingMemberMapper.findMember(7L, 5L)).thenReturn(member(7L, 5L, "OPERATE"));
        org.junit.jupiter.api.Assertions.assertEquals(7L,
                policy.requireMeetingAccess(ACTOR, 7L, com.si.backend.security.AccessLevel.VIEW).getId());
        org.junit.jupiter.api.Assertions.assertEquals(7L,
                policy.requireMeetingAccess(ACTOR, 7L, com.si.backend.security.AccessLevel.OPERATE).getId());
    }

    @Test
    void meetingAccess_nonMemberDenied404() {
        when(meetingMapper.findById(7L)).thenReturn(meeting(7L, 9L));
        when(meetingMemberMapper.findMember(7L, 5L)).thenReturn(null);
        BizException e = assertThrows(BizException.class,
                () -> policy.requireMeetingAccess(ACTOR, 7L, com.si.backend.security.AccessLevel.VIEW));
        assertEquals(404, e.getCode());
    }

    @Test
    void ownedSession_returnsAuthorizedResource() {
        InterpretationSession session = session("s1", 5L);
        when(sessionService.getSession("s1")).thenReturn(Optional.of(session));

        assertSame(session, policy.requireOwnedSession(ACTOR, "s1"));
    }

    @Test
    void missingSession_throws404() {
        when(sessionService.getSession("missing")).thenReturn(Optional.empty());
        when(sessionService.getSessionHistory("missing")).thenReturn(null);

        BizException error = assertThrows(
                BizException.class,
                () -> policy.requireOwnedSession(ACTOR, "missing")
        );

        assertEquals(404, error.getCode());
    }

    @Test
    void sessionOwnedByOther_throws404() {
        when(sessionService.getSession("s1")).thenReturn(Optional.of(session("s1", 9L)));

        BizException error = assertThrows(
                BizException.class,
                () -> policy.requireOwnedSession(ACTOR, "s1")
        );

        assertEquals(404, error.getCode());
    }

    @Test
    void missingActor_throws401BeforeResourceLookup() {
        BizException error = assertThrows(
                BizException.class,
                () -> policy.requireOwnedSession(null, "s1")
        );

        assertEquals(401, error.getCode());
    }

    @Test
    void ownedFile_resolvesParentMeetingAndReturnsFile() {
        PersistentPreMeetingFile file = new PersistentPreMeetingFile();
        file.setId(11L);
        file.setMeetingId(22L);
        Meeting meeting = new Meeting();
        meeting.setId(22L);
        meeting.setUserId(5L);
        when(fileMapper.findByIdFull(11L)).thenReturn(file);
        when(meetingMapper.findById(22L)).thenReturn(meeting);

        assertSame(file, policy.requireOwnedFile(ACTOR, 11L));
    }

    @Test
    void speakerSummary_resolvesParentSession() {
        SpeakerSummaryRecord summary = SpeakerSummaryRecord.builder()
                .id(31L)
                .sessionId("s31")
                .build();
        when(speakerSummaryMapper.findById(31L)).thenReturn(summary);
        when(sessionService.getSession("s31")).thenReturn(Optional.of(session("s31", 5L)));

        assertSame(summary, policy.requireOwnedSpeakerSummary(ACTOR, 31L));
    }

    @Test
    void actionItemWithoutResolvableOwner_isDenied() {
        MeetingActionItem actionItem = new MeetingActionItem();
        actionItem.setId(41L);
        when(actionItemMapper.findById(41L)).thenReturn(actionItem);

        BizException error = assertThrows(
                BizException.class,
                () -> policy.requireOwnedActionItem(ACTOR, 41L)
        );

        assertEquals(404, error.getCode());
    }

    @Test
    void ownedAudio_returnsAudio() {
        SessionAudioRecord audio = SessionAudioRecord.builder()
                .id(51L)
                .userId(5L)
                .build();
        when(audioRecordMapper.findById(51L)).thenReturn(audio);

        assertSame(audio, policy.requireOwnedAudio(ACTOR, 51L));
    }

    private InterpretationSession session(String sessionId, Long userId) {
        InterpretationSession session = new InterpretationSession();
        session.setSessionId(sessionId);
        session.setUserId(userId);
        return session;
    }
}
