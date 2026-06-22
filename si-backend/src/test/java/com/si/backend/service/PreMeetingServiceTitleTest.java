package com.si.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.si.backend.mapper.MeetingMapper;
import com.si.backend.vo.PreMeetingFileVo;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Verifies meeting-title derivation from uploaded notice names and saved expected-list behavior.
 */
class PreMeetingServiceTitleTest {

    @Test
    void derivesCleanTitleAndDateFromNotificationFileName() {
        String title = PreMeetingService.deriveMeetingTitle(
                "2026.06.19——人力文化周：劳动力诅咒专项 【会议通知】.pdf",
                "");

        assertEquals("人力文化周：劳动力诅咒专项（2026年6月19日）", title);
    }

    @Test
    void combinesTextTitleWithDateFromFileName() {
        String title = PreMeetingService.deriveMeetingTitle(
                "2026.06.19——人力文化周：劳动力诅咒专项 【会议通知】.pdf",
                "人力文化周：劳动力诅咒专项\n会议地点：Teams Meeting");

        assertEquals("人力文化周：劳动力诅咒专项（2026年6月19日）", title);
    }

    @Test
    void prefersFullDateFromFileNameOverShortDateInText() {
        String title = PreMeetingService.deriveMeetingTitle(
                "2026.06.19——人力文化周：劳动力诅咒专项 【会议通知】.pdf",
                "人力文化周：劳动力诅咒专项\n会议时间：6月19日");

        assertEquals("人力文化周：劳动力诅咒专项（2026年6月19日）", title);
    }

    @Test
    void saveExpectedParticipantsClearsStaleParticipantsWhenNoticeHasNoNames() throws Exception {
        MeetingMapper meetingMapper = mock(MeetingMapper.class);
        PreMeetingService service = new PreMeetingService(
                null, null, null, null, null, null, null, null, null,
                meetingMapper,
                new ObjectMapper());
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "2026.06.19——人力文化周：劳动力诅咒专项 【会议通知】.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                docxBytes("会议通知", "会议时间：2026年6月19日", "会议地点：Teams Meeting"));

        PreMeetingFileVo uploaded = service.upload(file).get(0);
        int count = service.saveExpectedParticipants(uploaded.getFileId(), 9L);

        assertEquals(0, count);
        verify(meetingMapper).updateExpectedParticipants(9L, null);
    }

    private static byte[] docxBytes(String... paragraphs) throws Exception {
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (String paragraph : paragraphs) {
                document.createParagraph().createRun().setText(paragraph);
            }
            document.write(out);
            return out.toByteArray();
        }
    }
}
