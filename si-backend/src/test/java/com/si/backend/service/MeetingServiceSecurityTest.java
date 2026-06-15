package com.si.backend.service;

import com.si.backend.common.BizException;
import com.si.backend.entity.Meeting;
import com.si.backend.entity.PersistentPreMeetingFile;
import com.si.backend.mapper.InterpretationSessionMapper;
import com.si.backend.mapper.MeetingActionItemMapper;
import com.si.backend.mapper.MeetingMapper;
import com.si.backend.mapper.PersistentPreMeetingFileMapper;
import com.si.backend.mapper.SpeakerSummaryRecordMapper;
import com.si.backend.security.AuthenticatedActor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Verifies meeting and child-file ownership is enforced with an explicit actor.
 */
class MeetingServiceSecurityTest {

    private final MeetingMapper meetingMapper = mock(MeetingMapper.class);
    private final PersistentPreMeetingFileMapper fileMapper = mock(PersistentPreMeetingFileMapper.class);
    private final PreMeetingService preMeetingService = mock(PreMeetingService.class);
    private final ContentEmbeddingService embeddingService = mock(ContentEmbeddingService.class);
    private final InterpretationSessionMapper sessionMapper = mock(InterpretationSessionMapper.class);
    private final MeetingActionItemMapper actionItemMapper = mock(MeetingActionItemMapper.class);
    private final SpeakerSummaryRecordMapper speakerSummaryMapper = mock(SpeakerSummaryRecordMapper.class);

    private MeetingService service;

    @BeforeEach
    void setUp() {
        service = new MeetingService(
                meetingMapper,
                fileMapper,
                preMeetingService,
                embeddingService,
                sessionMapper,
                actionItemMapper,
                speakerSummaryMapper
        );
    }

    @Test
    void otherUsersMeeting_isHiddenAsNotFound() {
        Meeting meeting = new Meeting();
        meeting.setId(10L);
        meeting.setUserId(1L);
        when(meetingMapper.findById(10L)).thenReturn(meeting);

        BizException error = assertThrows(
                BizException.class,
                () -> service.getMeeting(new AuthenticatedActor(2L), 10L)
        );

        assertEquals(404, error.getCode());
        verifyNoInteractions(fileMapper);
    }

    @Test
    void fileFromDifferentParentMeeting_isHiddenAsNotFound() {
        Meeting meeting = new Meeting();
        meeting.setId(10L);
        meeting.setUserId(1L);
        PersistentPreMeetingFile file = new PersistentPreMeetingFile();
        file.setId(20L);
        file.setMeetingId(11L);
        when(meetingMapper.findById(10L)).thenReturn(meeting);
        when(fileMapper.findById(20L)).thenReturn(file);

        BizException error = assertThrows(
                BizException.class,
                () -> service.getFileWithContent(new AuthenticatedActor(1L), 10L, 20L)
        );

        assertEquals(404, error.getCode());
    }
}
