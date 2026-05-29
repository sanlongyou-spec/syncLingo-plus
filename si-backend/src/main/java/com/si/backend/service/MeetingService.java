package com.si.backend.service;

import com.si.backend.common.BizException;
import com.si.backend.common.ErrorCode;
import com.si.backend.entity.Meeting;
import com.si.backend.entity.PersistentPreMeetingFile;
import com.si.backend.mapper.MeetingMapper;
import com.si.backend.mapper.PersistentPreMeetingFileMapper;
import com.si.backend.vo.MeetingFileVo;
import com.si.backend.vo.MeetingVo;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class MeetingService {

    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final MeetingMapper meetingMapper;
    private final PersistentPreMeetingFileMapper fileMapper;
    private final PreMeetingService preMeetingService;
    private final ContentEmbeddingService contentEmbeddingService;

    @PostConstruct
    public void initTables() {
        log.info("[MeetingService] initTables start");
        meetingMapper.createTableIfNotExists();
        fileMapper.createTableIfNotExists();
        addFileColumnIfMissing("file_data", fileMapper::addFileDataColumnIfNotExists);
        addFileColumnIfMissing("attendance_json", meetingMapper::addAttendanceJsonColumnIfNotExists);
        log.info("[MeetingService] initTables end");
    }

    private void addFileColumnIfMissing(String column, Runnable ddl) {
        try {
            ddl.run();
            log.info("[MeetingService] column added: {}", column);
        } catch (org.springframework.dao.DataAccessException e) {
            if (e.getMessage() != null && e.getMessage().contains("Duplicate column")) {
                log.info("[MeetingService] column already exists: {}", column);
            } else {
                throw e;
            }
        }
    }

    public MeetingVo createMeeting(Long userId, String title, String scheduledTime, String note) {
        Meeting meeting = new Meeting();
        meeting.setUserId(userId);
        meeting.setTitle(title.trim());
        meeting.setNote(note);
        if (scheduledTime != null && !scheduledTime.isBlank()) {
            try {
                meeting.setScheduledTime(LocalDateTime.parse(scheduledTime.trim(), DT_FMT));
            } catch (Exception e) {
                log.warn("[MeetingService] cannot parse scheduledTime={}", scheduledTime);
            }
        }
        meetingMapper.insert(meeting);
        log.info("[MeetingService] createMeeting done, meetingId={}, userId={}", meeting.getId(), userId);
        return toVo(meeting, List.of());
    }

    public List<MeetingVo> getMeetings(Long userId) {
        return meetingMapper.findByUserId(userId).stream()
                .map(m -> toVo(m, fileMapper.findByMeetingId(m.getId())))
                .toList();
    }

    public MeetingVo getMeeting(Long meetingId) {
        Meeting meeting = requireMeeting(meetingId);
        return toVo(meeting, fileMapper.findByMeetingId(meetingId));
    }

    public MeetingFileVo uploadFile(Long meetingId, MultipartFile file) throws IOException {
        requireMeeting(meetingId);
        String originalName = file.getOriginalFilename();
        if (originalName == null || originalName.isBlank()) originalName = "unnamed";
        String ext = extension(originalName).toLowerCase();
        byte[] rawBytes = file.getBytes();
        String text = preMeetingService.extractFileText(rawBytes, ext, originalName);
        PersistentPreMeetingFile entity = new PersistentPreMeetingFile();
        entity.setMeetingId(meetingId);
        entity.setFileName(originalName);
        entity.setFileType(ext);
        entity.setFileContent(text);
        entity.setFileData(rawBytes);
        fileMapper.insert(entity);
        log.info("[MeetingService] uploadFile done, meetingId={}, fileName={}, textLen={}", meetingId, originalName, text.length());
        if (entity.getId() != null && text != null && !text.isBlank()) {
            contentEmbeddingService.asyncEmbedFileContent(entity.getId(), meetingId, originalName, text);
        }
        return toFileVo(entity);
    }

    public List<MeetingFileVo> getFiles(Long meetingId) {
        requireMeeting(meetingId);
        return fileMapper.findByMeetingId(meetingId).stream().map(this::toFileVo).toList();
    }

    public void deleteFile(Long meetingId, Long fileId) {
        requireMeeting(meetingId);
        fileMapper.deleteById(fileId);
        contentEmbeddingService.deleteByTypeAndRefId(ContentEmbeddingService.TYPE_FILE_SUMMARY, fileId);
        contentEmbeddingService.deleteByTypeAndRefId(ContentEmbeddingService.TYPE_FILE_CONTENT, fileId);
    }

    public PersistentPreMeetingFile getFileWithContent(Long fileId) {
        PersistentPreMeetingFile f = fileMapper.findById(fileId);
        if (f == null) throw BizException.of(ErrorCode.NOT_FOUND, "文件不存在");
        return f;
    }

    public void saveFileSummary(Long fileId, String summary) {
        fileMapper.updateSummary(fileId, summary);
        if (summary != null && !summary.isBlank()) {
            PersistentPreMeetingFile f = fileMapper.findById(fileId);
            if (f != null) {
                contentEmbeddingService.asyncEmbedFileSummary(fileId, f.getMeetingId(), f.getFileName(), summary);
            }
        }
    }

    public PersistentPreMeetingFile getFileForDownload(Long fileId) {
        PersistentPreMeetingFile f = fileMapper.findByIdForDownload(fileId);
        if (f == null) throw BizException.of(ErrorCode.NOT_FOUND, "文件不存在");
        return f;
    }

    public void saveAttendance(Long meetingId, String attendanceJson) {
        requireMeeting(meetingId);
        meetingMapper.updateAttendanceJson(meetingId, attendanceJson);
    }

    public void deleteMeeting(Long meetingId) {
        meetingMapper.softDelete(meetingId);
    }

    private Meeting requireMeeting(Long meetingId) {
        Meeting m = meetingMapper.findById(meetingId);
        if (m == null) throw BizException.of(ErrorCode.NOT_FOUND, "会议不存在");
        return m;
    }

    private MeetingVo toVo(Meeting m, List<PersistentPreMeetingFile> files) {
        return MeetingVo.builder()
                .id(m.getId())
                .userId(m.getUserId())
                .title(m.getTitle())
                .scheduledTime(m.getScheduledTime() != null ? m.getScheduledTime().format(DT_FMT) : null)
                .note(m.getNote())
                .attendanceJson(m.getAttendanceJson())
                .createTime(m.getCreateTime())
                .files(files.stream().map(this::toFileVo).toList())
                .build();
    }

    private MeetingFileVo toFileVo(PersistentPreMeetingFile f) {
        return MeetingFileVo.builder()
                .id(f.getId())
                .meetingId(f.getMeetingId())
                .fileName(f.getFileName())
                .fileType(f.getFileType())
                .summary(f.getSummary())
                .createTime(f.getCreateTime() != null ? f.getCreateTime().format(DT_FMT) : null)
                .build();
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1) : "";
    }
}
