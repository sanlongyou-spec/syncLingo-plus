package com.si.backend.service;

import com.si.backend.entity.SessionAudioRecord;
import com.si.backend.mapper.SessionAudioRecordMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AudioRecordServiceTest {

    private static final int WAV_HEADER_BYTES = 44;

    @TempDir
    Path tempDir;

    private final SessionAudioRecordMapper mapper = mock(SessionAudioRecordMapper.class);
    private final List<SessionAudioRecord> records = new ArrayList<>();
    private final AtomicLong nextId = new AtomicLong(1L);
    private AudioRecordService service;

    @BeforeEach
    void setUp() {
        service = new AudioRecordService(mapper);
        ReflectionTestUtils.setField(service, "audioDir", tempDir.toString());
        doAnswer(invocation -> {
            SessionAudioRecord record = invocation.getArgument(0);
            record.setId(nextId.getAndIncrement());
            record.setCreateTime(LocalDateTime.now().plusNanos(record.getId()));
            records.add(record);
            return 1;
        }).when(mapper).insert(any(SessionAudioRecord.class));
        when(mapper.findSourceRecordsByMeetingId(anyLong(), anyLong(), anyString())).thenAnswer(invocation -> {
            Long userId = invocation.getArgument(0);
            Long meetingId = invocation.getArgument(1);
            String combinedPrefix = invocation.getArgument(2);
            return records.stream()
                    .filter(record -> Objects.equals(record.getUserId(), userId))
                    .filter(record -> Objects.equals(record.getMeetingId(), meetingId))
                    .filter(record -> record.getSessionId() != null
                            && !record.getSessionId().startsWith(combinedPrefix))
                    .sorted((left, right) -> Long.compare(left.getId(), right.getId()))
                    .toList();
        });
        when(mapper.findByUserMeetingAndSession(anyLong(), anyLong(), anyString())).thenAnswer(invocation -> {
            Long userId = invocation.getArgument(0);
            Long meetingId = invocation.getArgument(1);
            String sessionId = invocation.getArgument(2);
            return records.stream()
                    .filter(record -> Objects.equals(record.getUserId(), userId))
                    .filter(record -> Objects.equals(record.getMeetingId(), meetingId))
                    .filter(record -> Objects.equals(record.getSessionId(), sessionId))
                    .findFirst()
                    .orElse(null);
        });
        when(mapper.findById(anyLong())).thenAnswer(invocation -> {
            Long id = invocation.getArgument(0);
            return records.stream()
                    .filter(record -> Objects.equals(record.getId(), id))
                    .findFirst()
                    .orElse(null);
        });
        doAnswer(invocation -> {
            Long id = invocation.getArgument(0);
            Long userId = invocation.getArgument(1);
            return records.removeIf(record -> Objects.equals(record.getId(), id)
                    && Objects.equals(record.getUserId(), userId)) ? 1 : 0;
        }).when(mapper).deleteById(anyLong(), anyLong());
    }

    @Test
    void finalizeRecordingWritesPcmFramesInAppendOrder() throws Exception {
        byte[] first = new byte[] {1, 2, 3, 4};
        byte[] second = new byte[] {5, 6, 7, 8};

        service.startRecording("session-a", 7L, null);
        service.appendPcm("session-a", first);
        service.appendPcm("session-a", second);
        service.finalizeRecording("session-a");

        assertEquals(1, records.size());
        SessionAudioRecord record = records.getFirst();
        assertEquals("session-a", record.getSessionId());
        assertArrayEquals(concat(first, second), wavPcm(record));
    }

    @Test
    void finalizeRecordingRefreshesMeetingCombinedAudioInSessionOrder() throws Exception {
        byte[] firstSession = new byte[] {10, 11, 12, 13};
        byte[] secondSession = new byte[] {20, 21, 22, 23, 24, 25};

        service.startRecording("session-a", 7L, 9L);
        service.appendPcm("session-a", firstSession);
        service.finalizeRecording("session-a");

        service.startRecording("session-b", 7L, 9L);
        service.appendPcm("session-b", secondSession);
        service.finalizeRecording("session-b");

        SessionAudioRecord combined = records.stream()
                .filter(record -> Objects.equals(record.getSessionId(), "meeting:9"))
                .findFirst()
                .orElse(null);
        assertNotNull(combined);
        assertTrue(new File(combined.getFilePath()).isFile());
        assertArrayEquals(concat(firstSession, secondSession), wavPcm(combined));
    }

    private static byte[] wavPcm(SessionAudioRecord record) throws IOException {
        byte[] bytes = Files.readAllBytes(Path.of(record.getFilePath()));
        assertTrue(bytes.length >= WAV_HEADER_BYTES);
        return Arrays.copyOfRange(bytes, WAV_HEADER_BYTES, bytes.length);
    }

    private static byte[] concat(byte[]... chunks) {
        int length = Arrays.stream(chunks).mapToInt(chunk -> chunk.length).sum();
        byte[] result = new byte[length];
        int offset = 0;
        for (byte[] chunk : chunks) {
            System.arraycopy(chunk, 0, result, offset, chunk.length);
            offset += chunk.length;
        }
        return result;
    }
}
