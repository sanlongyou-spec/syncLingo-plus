package com.si.backend.service;

import com.si.backend.entity.SessionAudioRecord;
import com.si.backend.mapper.SessionAudioRecordMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class AudioRecordService {

    private static final int SAMPLE_RATE = 16000;
    private static final int BITS_PER_SAMPLE = 16;
    private static final int CHANNELS = 1;
    private static final int BYTES_PER_SAMPLE = BITS_PER_SAMPLE / 8;
    private static final int WAV_HEADER_BYTES = 44;
    private static final int COPY_BUFFER_BYTES = 65536;
    private static final String MEETING_RECORD_SESSION_PREFIX = "meeting:";
    private static final String RAW_RECORD_NAME_PREFIX = "原声录音 ";
    private static final String MEETING_RECORD_NAME_PREFIX = "整场会议录音 ";
    private static final DateTimeFormatter NAME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    @Value("${audio.record.dir:/app/audio-records}")
    private String audioDir;

    private final SessionAudioRecordMapper mapper;

    /** sessionId → temp PCM file output stream */
    private final Map<String, RecordingContext> active = new ConcurrentHashMap<>();
    private final Map<String, Object> meetingRefreshLocks = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        mapper.createTableIfNotExists();
        try {
            Files.createDirectories(Paths.get(audioDir));
        } catch (IOException e) {
            log.warn("[AudioRecordService] failed to create audio dir: {}", audioDir, e);
        }
    }

    /** 会话开始时调用，建立录音上下文 */
    public void startRecording(String sessionId, Long userId, Long meetingId) {
        try {
            Path dir = Paths.get(audioDir);
            Files.createDirectories(dir);
            File tmp = File.createTempFile("pcm_" + sessionId + "_", ".raw", dir.toFile());
            FileOutputStream fos = new FileOutputStream(tmp, true);
            RecordingContext previous = active.putIfAbsent(sessionId, new RecordingContext(tmp, fos, userId, meetingId));
            if (previous != null) {
                fos.close();
                tmp.delete();
                return;
            }
            log.info("[AudioRecordService] recording started, sessionId={}, userId={}, meetingId={}",
                    sessionId, userId, meetingId);
        } catch (IOException e) {
            log.warn("[AudioRecordService] failed to start recording, sessionId={}", sessionId, e);
        }
    }

    /** 每帧 PCM 调用一次(16kHz, 16-bit, mono) */
    public void appendPcm(String sessionId, byte[] pcm) {
        RecordingContext ctx = active.get(sessionId);
        if (ctx == null || pcm == null || pcm.length == 0) return;
        synchronized (ctx) {
            if (ctx.closed) return;
            try {
                ctx.fos.write(pcm);
                ctx.totalBytes += pcm.length;
            } catch (IOException e) {
                log.warn("[AudioRecordService] write failed, sessionId={}", sessionId, e);
            }
        }
    }

    /** 会话结束时调用，生成 WAV 文件并存库 */
    public void finalizeRecording(String sessionId) {
        RecordingContext ctx = active.remove(sessionId);
        if (ctx == null) return;
        long totalBytes;
        synchronized (ctx) {
            ctx.closed = true;
            totalBytes = ctx.totalBytes;
            try {
                ctx.fos.flush();
                ctx.fos.close();
            } catch (IOException e) {
                log.warn("[AudioRecordService] flush failed, sessionId={}", sessionId, e);
            }
        }
        if (totalBytes == 0) {
            ctx.tmpFile.delete();
            log.info("[AudioRecordService] empty recording discarded, sessionId={}", sessionId);
            return;
        }
        try {
            String wavName = "audio_" + sessionId + ".wav";
            File wavFile = Paths.get(audioDir, wavName).toFile();
            writeWav(ctx.tmpFile, wavFile, totalBytes);
            ctx.tmpFile.delete();

            long durationMs = durationMs(totalBytes);
            String defaultName = RAW_RECORD_NAME_PREFIX + LocalDateTime.now().format(NAME_FMT);
            SessionAudioRecord record = SessionAudioRecord.builder()
                    .sessionId(sessionId)
                    .userId(ctx.userId)
                    .meetingId(ctx.meetingId)
                    .name(defaultName)
                    .filePath(wavFile.getAbsolutePath())
                    .fileSizeBytes(wavFile.length())
                    .durationMs(durationMs)
                    .sampleRate(SAMPLE_RATE)
                    .build();
            mapper.insert(record);
            if (ctx.userId != null && ctx.meetingId != null) {
                refreshMeetingCombinedRecording(ctx.userId, ctx.meetingId);
            }
            log.info("[AudioRecordService] recording finalized, sessionId={}, userId={}, meetingId={}, durationMs={}, bytes={}",
                    sessionId, ctx.userId, ctx.meetingId, durationMs, wavFile.length());
        } catch (Exception e) {
            log.error("[AudioRecordService] finalize failed, sessionId={}", sessionId, e);
            ctx.tmpFile.delete();
        }
    }

    public List<SessionAudioRecord> search(Long userId, Long meetingId, String keyword, int page, int size) {
        if (meetingId != null) {
            refreshMeetingCombinedRecording(userId, meetingId);
            return mapper.searchMeetingCombined(
                    userId,
                    meetingId,
                    combinedSessionId(meetingId),
                    keyword == null ? "" : keyword,
                    size,
                    offset(page, size));
        }
        return mapper.search(userId, keyword == null ? "" : keyword, size, (page - 1) * size);
    }

    public long count(Long userId, Long meetingId, String keyword) {
        if (meetingId != null) {
            return mapper.countMeetingCombined(
                    userId,
                    meetingId,
                    combinedSessionId(meetingId),
                    keyword == null ? "" : keyword);
        }
        return mapper.count(userId, keyword == null ? "" : keyword);
    }

    public boolean rename(Long userId, Long id, String name) {
        return mapper.updateName(id, userId, name) > 0;
    }

    public boolean delete(Long userId, Long id) {
        SessionAudioRecord record = mapper.findById(id);
        if (record == null || !record.getUserId().equals(userId)) return false;
        if (isMeetingCombinedRecord(record)) {
            deleteMeetingAudioBundle(record);
            return true;
        }
        mapper.deleteById(id, userId);
        if (record.getFilePath() != null) {
            new File(record.getFilePath()).delete();
        }
        if (record.getMeetingId() != null) {
            refreshMeetingCombinedRecording(userId, record.getMeetingId());
        }
        return true;
    }

    /** 返回文件供下载；调用方需校验 userId */
    public File getFile(Long userId, Long id) {
        SessionAudioRecord record = mapper.findById(id);
        if (record == null || !record.getUserId().equals(userId)) return null;
        File f = new File(record.getFilePath());
        return f.exists() ? f : null;
    }

    public SessionAudioRecord getRecord(Long id) {
        return mapper.findById(id);
    }

    public void refreshMeetingCombinedRecording(Long userId, Long meetingId) {
        if (userId == null || meetingId == null) return;
        String lockKey = userId + ":" + meetingId;
        Object lock = meetingRefreshLocks.computeIfAbsent(lockKey, ignored -> new Object());
        synchronized (lock) {
            refreshMeetingCombinedRecordingLocked(userId, meetingId);
        }
    }

    private void refreshMeetingCombinedRecordingLocked(Long userId, Long meetingId) {
        long startMs = System.currentTimeMillis();
        String combinedSessionId = combinedSessionId(meetingId);
        SessionAudioRecord previous = mapper.findByUserMeetingAndSession(userId, meetingId, combinedSessionId);
        List<SessionAudioRecord> sources = mapper.findSourceRecordsByMeetingId(
                userId,
                meetingId,
                MEETING_RECORD_SESSION_PREFIX);

        if (sources.isEmpty()) {
            if (previous != null) {
                mapper.deleteById(previous.getId(), userId);
                deleteFile(previous.getFilePath());
            }
            log.info("[AudioRecordService] meeting recording refresh skipped, userId={}, meetingId={}, reason=noSources, costMs={}",
                    userId, meetingId, System.currentTimeMillis() - startMs);
            return;
        }

        try {
            Files.createDirectories(Paths.get(audioDir));
            File combinedFile = Paths.get(
                    audioDir,
                    "audio_meeting_" + userId + "_" + meetingId + "_" + System.currentTimeMillis() + ".wav"
            ).toFile();
            long pcmBytes = writeCombinedWav(sources, combinedFile);
            if (pcmBytes <= 0) {
                combinedFile.delete();
                if (previous != null) {
                    mapper.deleteById(previous.getId(), userId);
                    deleteFile(previous.getFilePath());
                }
                log.warn("[AudioRecordService] meeting recording refresh skipped, userId={}, meetingId={}, reason=noReadablePcm, sources={}, costMs={}",
                        userId, meetingId, sources.size(), System.currentTimeMillis() - startMs);
                return;
            }

            SessionAudioRecord combined = SessionAudioRecord.builder()
                    .sessionId(combinedSessionId)
                    .userId(userId)
                    .meetingId(meetingId)
                    .name(MEETING_RECORD_NAME_PREFIX + LocalDateTime.now().format(NAME_FMT))
                    .filePath(combinedFile.getAbsolutePath())
                    .fileSizeBytes(combinedFile.length())
                    .durationMs(durationMs(pcmBytes))
                    .sampleRate(SAMPLE_RATE)
                    .build();
            mapper.insert(combined);
            if (previous != null) {
                mapper.deleteById(previous.getId(), userId);
                deleteFile(previous.getFilePath());
            }
            log.info("[AudioRecordService] meeting recording refreshed, userId={}, meetingId={}, sources={}, durationMs={}, bytes={}, costMs={}",
                    userId, meetingId, sources.size(), combined.getDurationMs(), combinedFile.length(),
                    System.currentTimeMillis() - startMs);
        } catch (IOException e) {
            log.error("[AudioRecordService] meeting recording refresh failed, userId={}, meetingId={}",
                    userId, meetingId, e);
        }
    }

    private void deleteMeetingAudioBundle(SessionAudioRecord combined) {
        List<SessionAudioRecord> sources = combined.getMeetingId() == null
                ? List.of()
                : mapper.findSourceRecordsByMeetingId(
                combined.getUserId(),
                combined.getMeetingId(),
                MEETING_RECORD_SESSION_PREFIX);
        List<SessionAudioRecord> records = new ArrayList<>(sources.size() + 1);
        records.add(combined);
        records.addAll(sources);
        for (SessionAudioRecord record : records) {
            mapper.deleteById(record.getId(), combined.getUserId());
            deleteFile(record.getFilePath());
        }
        log.info("[AudioRecordService] meeting recording bundle deleted, userId={}, meetingId={}, records={}",
                combined.getUserId(), combined.getMeetingId(), records.size());
    }

    // WAV header: 44 bytes
    private void writeWav(File rawPcm, File wav, long pcmBytes) throws IOException {
        try (FileOutputStream out = new FileOutputStream(wav);
             FileInputStream in = new FileInputStream(rawPcm)) {
            int byteRate = SAMPLE_RATE * CHANNELS * BYTES_PER_SAMPLE;
            int blockAlign = CHANNELS * BYTES_PER_SAMPLE;
            ByteBuffer hdr = ByteBuffer.allocate(WAV_HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN);
            hdr.put("RIFF".getBytes());
            hdr.putInt((int) (36 + pcmBytes));
            hdr.put("WAVE".getBytes());
            hdr.put("fmt ".getBytes());
            hdr.putInt(16);
            hdr.putShort((short) 1);        // PCM
            hdr.putShort((short) CHANNELS);
            hdr.putInt(SAMPLE_RATE);
            hdr.putInt(byteRate);
            hdr.putShort((short) blockAlign);
            hdr.putShort((short) BITS_PER_SAMPLE);
            hdr.put("data".getBytes());
            hdr.putInt((int) pcmBytes);
            out.write(hdr.array());
            byte[] buf = new byte[COPY_BUFFER_BYTES];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        }
    }

    private long writeCombinedWav(List<SessionAudioRecord> sources, File target) throws IOException {
        long pcmBytes = 0;
        List<File> readableFiles = new ArrayList<>();
        for (SessionAudioRecord source : sources) {
            if (source.getFilePath() == null) continue;
            File file = new File(source.getFilePath());
            if (!file.isFile() || file.length() <= WAV_HEADER_BYTES) {
                log.warn("[AudioRecordService] source recording skipped, id={}, path={}",
                        source.getId(), source.getFilePath());
                continue;
            }
            readableFiles.add(file);
            pcmBytes += file.length() - WAV_HEADER_BYTES;
        }
        if (pcmBytes <= 0) return 0;
        if (pcmBytes > Integer.MAX_VALUE) {
            throw new IOException("meeting recording exceeds wav header size limit: " + pcmBytes);
        }

        File tmpRaw = File.createTempFile("meeting_pcm_", ".raw", Paths.get(audioDir).toFile());
        try (FileOutputStream rawOut = new FileOutputStream(tmpRaw)) {
            byte[] buffer = new byte[COPY_BUFFER_BYTES];
            for (File source : readableFiles) {
                try (FileInputStream in = new FileInputStream(source)) {
                    skipFully(in, WAV_HEADER_BYTES);
                    int n;
                    while ((n = in.read(buffer)) != -1) {
                        rawOut.write(buffer, 0, n);
                    }
                }
            }
        }
        writeWav(tmpRaw, target, pcmBytes);
        tmpRaw.delete();
        return pcmBytes;
    }

    private void skipFully(InputStream in, long bytes) throws IOException {
        long remaining = bytes;
        while (remaining > 0) {
            long skipped = in.skip(remaining);
            if (skipped <= 0 && in.read() == -1) {
                throw new EOFException("Unexpected end of WAV header");
            }
            remaining -= skipped > 0 ? skipped : 1;
        }
    }

    private boolean isMeetingCombinedRecord(SessionAudioRecord record) {
        return record.getSessionId() != null
                && record.getSessionId().startsWith(MEETING_RECORD_SESSION_PREFIX);
    }

    private String combinedSessionId(Long meetingId) {
        return MEETING_RECORD_SESSION_PREFIX + meetingId;
    }

    private int offset(int page, int size) {
        return Math.max(0, page - 1) * size;
    }

    private long durationMs(long pcmBytes) {
        return pcmBytes * 1000L / (SAMPLE_RATE * CHANNELS * BYTES_PER_SAMPLE);
    }

    private void deleteFile(String filePath) {
        if (filePath != null && !filePath.isBlank()) {
            new File(filePath).delete();
        }
    }

    private static class RecordingContext {
        final File tmpFile;
        final FileOutputStream fos;
        final Long userId;
        final Long meetingId;
        long totalBytes = 0;
        boolean closed = false;

        RecordingContext(File tmpFile, FileOutputStream fos, Long userId, Long meetingId) {
            this.tmpFile = tmpFile;
            this.fos = fos;
            this.userId = userId;
            this.meetingId = meetingId;
        }
    }
}
