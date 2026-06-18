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
import com.si.backend.vo.MeetingFileVo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
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
    private final InterpretationSessionService sessionService = mock(InterpretationSessionService.class);
    private final com.si.backend.mapper.SessionAudioRecordMapper audioRecordMapper =
            mock(com.si.backend.mapper.SessionAudioRecordMapper.class);
    private final com.si.backend.mapper.MeetingMemberMapper meetingMemberMapper =
            mock(com.si.backend.mapper.MeetingMemberMapper.class);
    private final com.si.backend.mapper.SupportAccessGrantMapper supportAccessGrantMapper =
            mock(com.si.backend.mapper.SupportAccessGrantMapper.class);

    private MeetingService service;

    @BeforeEach
    void setUp() {
        // 真实归属策略(注入 mock mapper),使 owner/成员判定真实运行
        ResourceOwnershipPolicy policy = new ResourceOwnershipPolicy(
                sessionService, meetingMapper, fileMapper, speakerSummaryMapper,
                actionItemMapper, audioRecordMapper, meetingMemberMapper, supportAccessGrantMapper);
        service = new MeetingService(
                meetingMapper,
                fileMapper,
                preMeetingService,
                embeddingService,
                sessionMapper,
                actionItemMapper,
                speakerSummaryMapper,
                policy
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

    @Test
    void uploadFile_acceptsPdfReportFiles() throws Exception {
        Meeting meeting = new Meeting();
        meeting.setId(10L);
        meeting.setUserId(1L);
        when(meetingMapper.findById(10L)).thenReturn(meeting);
        byte[] content = "report".getBytes(StandardCharsets.UTF_8);
        MockMultipartFile file = new MockMultipartFile(
                "file", "report.pdf", "application/pdf", content);
        when(preMeetingService.extractFileText(any(byte[].class), eq("pdf"), eq("report.pdf")))
                .thenReturn("extracted report");

        MeetingFileVo uploaded = service.uploadFile(new AuthenticatedActor(1L), 10L, file);

        ArgumentCaptor<PersistentPreMeetingFile> captor = ArgumentCaptor.forClass(PersistentPreMeetingFile.class);
        verify(fileMapper).insert(captor.capture());
        assertEquals("report.pdf", uploaded.getFileName());
        assertEquals("pdf", uploaded.getFileType());
        assertEquals("report.pdf", captor.getValue().getFileName());
        assertEquals("pdf", captor.getValue().getFileType());
        assertEquals("extracted report", captor.getValue().getFileContent());
    }

    @Test
    void uploadFile_rejectsUnsupportedReportFilesBeforeParsing() {
        Meeting meeting = new Meeting();
        meeting.setId(10L);
        meeting.setUserId(1L);
        when(meetingMapper.findById(10L)).thenReturn(meeting);
        MockMultipartFile file = new MockMultipartFile(
                "file", "report.txt", "text/plain", "report".getBytes(StandardCharsets.UTF_8));

        BizException error = assertThrows(
                BizException.class,
                () -> service.uploadFile(new AuthenticatedActor(1L), 10L, file)
        );

        assertEquals(400, error.getCode());
        verifyNoInteractions(preMeetingService, embeddingService);
    }
}
