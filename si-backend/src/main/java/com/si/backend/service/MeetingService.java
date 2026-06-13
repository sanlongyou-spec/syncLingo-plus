package com.si.backend.service;

import com.si.backend.common.BizException;
import com.si.backend.common.Constants;
import com.si.backend.common.ErrorCode;
import com.si.backend.entity.Meeting;
import com.si.backend.util.AuthContext;
import com.si.backend.entity.PersistentPreMeetingFile;
import com.si.backend.mapper.MeetingMapper;
import com.si.backend.mapper.PersistentPreMeetingFileMapper;
import com.si.backend.vo.MeetingFileVo;
import com.si.backend.vo.MeetingVo;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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
    private final com.si.backend.mapper.InterpretationSessionMapper sessionMapper;
    private final com.si.backend.mapper.MeetingActionItemMapper actionItemMapper;
    private final com.si.backend.mapper.SpeakerSummaryRecordMapper speakerSummaryMapper;

    @PostConstruct
    public void initTables() {
        log.info("[MeetingService] initTables start");
        meetingMapper.createTableIfNotExists();
        fileMapper.createTableIfNotExists();
        addFileColumnIfMissing("file_data", fileMapper::addFileDataColumnIfNotExists);
        addFileColumnIfMissing("attendance_json", meetingMapper::addAttendanceJsonColumnIfNotExists);
        addFileColumnIfMissing("expected_participants_json", meetingMapper::addExpectedParticipantsColumnIfNotExists);
        addFileColumnIfMissing("meeting_url", meetingMapper::addMeetingUrlColumnIfNotExists);
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
        String trimmedTitle = title == null ? "" : title.trim();
        if (trimmedTitle.isBlank()) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "会议名称不能为空");
        }
        // Disallow duplicate meeting names for the same user (schedule upload / manual name / auto-create).
        if (meetingMapper.countByUserIdAndTitle(userId, trimmedTitle) > 0) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "已存在同名会议「" + trimmedTitle + "」，请换一个会议名称");
        }
        Meeting meeting = new Meeting();
        meeting.setUserId(userId);
        meeting.setTitle(trimmedTitle);
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
        Meeting meeting = requireOwner(meetingId);
        return toVo(meeting, fileMapper.findByMeetingId(meetingId));
    }

    public MeetingFileVo uploadFile(Long meetingId, MultipartFile file) throws IOException {
        requireOwner(meetingId);
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
        requireOwner(meetingId);
        return fileMapper.findByMeetingId(meetingId).stream().map(this::toFileVo).toList();
    }

    public void deleteFile(Long meetingId, Long fileId) {
        requireOwner(meetingId);
        PersistentPreMeetingFile file = fileMapper.findById(fileId);
        if (file == null) throw BizException.of(ErrorCode.NOT_FOUND, "文件不存在");
        if (!meetingId.equals(file.getMeetingId())) {
            throw BizException.of(com.si.backend.common.Constants.HTTP_UNAUTHORIZED, "文件不属于该会议");
        }
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

    /** Full record incl. both extracted text and original bytes — used to re-load a file for re-summary. */
    public PersistentPreMeetingFile getFileFull(Long fileId) {
        PersistentPreMeetingFile f = fileMapper.findByIdFull(fileId);
        if (f == null) throw BizException.of(ErrorCode.NOT_FOUND, "文件不存在");
        return f;
    }

    public String getMeetingNoticeText(Long meetingId) {
        requireOwner(meetingId);
        List<PersistentPreMeetingFile> files = fileMapper.findByMeetingId(meetingId);
        PersistentPreMeetingFile notice = files.stream()
                .filter(file -> file.getFileName() != null
                        && (file.getFileName().contains("会议通知") || file.getFileName().contains("会议安排")))
                .findFirst()
                .orElse(files.isEmpty() ? null : files.get(0));
        if (notice == null || notice.getId() == null) {
            return "";
        }
        PersistentPreMeetingFile full = fileMapper.findById(notice.getId());
        return full == null || full.getFileContent() == null ? "" : full.getFileContent();
    }

    public void saveAttendance(Long meetingId, String attendanceJson) {
        requireOwner(meetingId);
        meetingMapper.updateAttendanceJson(meetingId, attendanceJson);
    }

    public void setMeetingUrl(Long meetingId, String url) {
        requireOwner(meetingId);
        meetingMapper.updateMeetingUrl(meetingId, url);
    }

    @Transactional
    public void deleteMeeting(Long meetingId) {
        requireOwner(meetingId);
        log.info("[MeetingService] deleteMeeting start, meetingId={}", meetingId);

        // Collect the meeting's sessions BEFORE soft-deleting them, to clean their per-session data.
        List<String> sessionIds = sessionMapper.findByMeetingId(meetingId).stream()
                .map(com.si.backend.entity.InterpretationSession::getSessionId)
                .filter(id -> id != null && !id.isBlank())
                .toList();
        for (String sessionId : sessionIds) {
            speakerSummaryMapper.deleteBySessionId(sessionId);     // 发言人摘要
            actionItemMapper.deleteBySessionId(sessionId);          // 行动项（按会话）
            contentEmbeddingService.deleteBySessionId(sessionId);   // 会话级向量
        }

        // Soft-delete the meeting + its sessions so they stop showing in the bot's "最近会议" list
        // (which filters by session.deleted, not the parent meeting).
        meetingMapper.softDelete(meetingId);
        int sessions = sessionMapper.softDeleteByMeetingId(meetingId);

        // Hard-delete every other piece of data tied to this meeting so nothing lingers in the DB.
        meetingMapper.clearAssociatedData(meetingId);              // 应到名单 / 实到核对 / 会议链接
        fileMapper.deleteByMeetingId(meetingId);                   // 上传的会议安排 / 会议文件(含二进制)
        actionItemMapper.deleteByMeetingId(meetingId);             // 行动项（按会议）
        contentEmbeddingService.deleteByMeetingId(meetingId);      // 会议级向量
        preMeetingService.deleteUsageByMeetingId(meetingId);       // 会前成本

        log.info("[MeetingService] deleteMeeting done, meetingId={}, cascadedSessions={}, cleanedSessionData={}",
                meetingId, sessions, sessionIds.size());
    }

    private Meeting requireMeeting(Long meetingId) {
        Meeting m = meetingMapper.findById(meetingId);
        if (m == null) throw BizException.of(ErrorCode.NOT_FOUND, "会议不存在");
        return m;
    }

    private Meeting requireOwner(Long meetingId) {
        Meeting m = requireMeeting(meetingId);
        Long authId = AuthContext.currentUserId();
        if (authId != null && !authId.equals(m.getUserId())) {
            log.warn("[MeetingService] ownership violation, meetingId={}, ownerId={}, authId={}", meetingId, m.getUserId(), authId);
            throw BizException.of(Constants.HTTP_UNAUTHORIZED, "无权操作该会议");
        }
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
                .meetingUrl(m.getMeetingUrl())
                .hasExpectedParticipants(m.getExpectedParticipantsJson() != null
                        && !m.getExpectedParticipantsJson().isBlank())
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
