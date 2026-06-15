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
    private final ResourceOwnershipPolicy policy = new ResourceOwnershipPolicy(
            sessionService,
            meetingMapper,
            fileMapper,
            speakerSummaryMapper,
            actionItemMapper,
            audioRecordMapper
    );

    private static final AuthenticatedActor ACTOR = new AuthenticatedActor(5L);

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
