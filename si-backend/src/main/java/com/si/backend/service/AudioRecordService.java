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
    private static final DateTimeFormatter NAME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    @Value("${audio.record.dir:/app/audio-records}")
    private String audioDir;

    private final SessionAudioRecordMapper mapper;

    /** sessionId → temp PCM file output stream */
    private final Map<String, RecordingContext> active = new ConcurrentHashMap<>();

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
        if (active.containsKey(sessionId)) return;
        try {
            Path dir = Paths.get(audioDir);
            Files.createDirectories(dir);
            File tmp = File.createTempFile("pcm_" + sessionId + "_", ".raw", dir.toFile());
            FileOutputStream fos = new FileOutputStream(tmp, true);
            active.put(sessionId, new RecordingContext(tmp, fos, userId, meetingId));
            log.info("[AudioRecordService] recording started, sessionId={}", sessionId);
        } catch (IOException e) {
            log.warn("[AudioRecordService] failed to start recording, sessionId={}", sessionId, e);
        }
    }

    /** 每帧 PCM 调用一次(16kHz, 16-bit, mono) */
    public void appendPcm(String sessionId, byte[] pcm) {
        RecordingContext ctx = active.get(sessionId);
        if (ctx == null || pcm == null || pcm.length == 0) return;
        try {
            ctx.fos.write(pcm);
            ctx.totalBytes += pcm.length;
        } catch (IOException e) {
            log.warn("[AudioRecordService] write failed, sessionId={}", sessionId, e);
        }
    }

    /** 会话结束时调用，生成 WAV 文件并存库 */
    public void finalizeRecording(String sessionId) {
        RecordingContext ctx = active.remove(sessionId);
        if (ctx == null) return;
        try {
            ctx.fos.flush();
            ctx.fos.close();
        } catch (IOException e) {
            log.warn("[AudioRecordService] flush failed, sessionId={}", sessionId, e);
        }
        if (ctx.totalBytes == 0) {
            ctx.tmpFile.delete();
            log.info("[AudioRecordService] empty recording discarded, sessionId={}", sessionId);
            return;
        }
        try {
            String wavName = "audio_" + sessionId + ".wav";
            File wavFile = Paths.get(audioDir, wavName).toFile();
            writeWav(ctx.tmpFile, wavFile, ctx.totalBytes);
            ctx.tmpFile.delete();

            long durationMs = ctx.totalBytes * 1000L / (SAMPLE_RATE * CHANNELS * BYTES_PER_SAMPLE);
            String defaultName = "原声录音 " + LocalDateTime.now().format(NAME_FMT);
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
            log.info("[AudioRecordService] recording finalized, sessionId={}, durationMs={}, bytes={}",
                    sessionId, durationMs, wavFile.length());
        } catch (IOException e) {
            log.error("[AudioRecordService] finalize failed, sessionId={}", sessionId, e);
            ctx.tmpFile.delete();
        }
    }

    public List<SessionAudioRecord> search(Long userId, String keyword, int page, int size) {
        return mapper.search(userId, keyword == null ? "" : keyword, size, (page - 1) * size);
    }

    public long count(Long userId, String keyword) {
        return mapper.count(userId, keyword == null ? "" : keyword);
    }

    public boolean rename(Long userId, Long id, String name) {
        return mapper.updateName(id, userId, name) > 0;
    }

    public boolean delete(Long userId, Long id) {
        SessionAudioRecord record = mapper.findById(id);
        if (record == null || !record.getUserId().equals(userId)) return false;
        mapper.deleteById(id, userId);
        if (record.getFilePath() != null) {
            new File(record.getFilePath()).delete();
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

    // WAV header: 44 bytes
    private void writeWav(File rawPcm, File wav, long pcmBytes) throws IOException {
        try (FileOutputStream out = new FileOutputStream(wav);
             FileInputStream in = new FileInputStream(rawPcm)) {
            int byteRate = SAMPLE_RATE * CHANNELS * BYTES_PER_SAMPLE;
            int blockAlign = CHANNELS * BYTES_PER_SAMPLE;
            ByteBuffer hdr = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
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
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        }
    }

    private static class RecordingContext {
        final File tmpFile;
        final FileOutputStream fos;
        final Long userId;
        final Long meetingId;
        long totalBytes = 0;

        RecordingContext(File tmpFile, FileOutputStream fos, Long userId, Long meetingId) {
            this.tmpFile = tmpFile;
            this.fos = fos;
            this.userId = userId;
            this.meetingId = meetingId;
        }
    }
}
