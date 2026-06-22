package com.si.backend.service;

import com.si.backend.common.BizException;
import com.si.backend.common.ErrorCode;
import com.si.backend.entity.Meeting;
import com.si.backend.entity.PersistentPreMeetingFile;
import com.si.backend.mapper.MeetingMapper;
import com.si.backend.mapper.PersistentPreMeetingFileMapper;
import com.si.backend.security.AuthenticatedActor;
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
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class MeetingService {

    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final Set<String> REPORT_FILE_EXTENSIONS = Set.of("pdf", "doc", "docx");
    private static final String REPORT_FILE_TYPE_MESSAGE = "会议文件仅支持 PDF 或 Word（.doc/.docx）";
    private static final Set<String> NOTICE_FILE_NAME_HINTS = Set.of(
            "会议通知", "会议安排", "通知", "安排", "agenda", "notice");

    private final MeetingMapper meetingMapper;
    private final PersistentPreMeetingFileMapper fileMapper;
    private final PreMeetingService preMeetingService;
    private final ContentEmbeddingService contentEmbeddingService;
    private final com.si.backend.mapper.InterpretationSessionMapper sessionMapper;
    private final com.si.backend.mapper.MeetingActionItemMapper actionItemMapper;
    private final com.si.backend.mapper.SpeakerSummaryRecordMapper speakerSummaryMapper;
    private final ResourceOwnershipPolicy resourceOwnershipPolicy;

    @PostConstruct
    public void initTables() {
        log.info("[MeetingService] initTables start");
        meetingMapper.createTableIfNotExists();
        fileMapper.createTableIfNotExists();
        addFileColumnIfMissing("file_data", fileMapper::addFileDataColumnIfNotExists);
        addFileColumnIfMissing("expected_participants_json", meetingMapper::addExpectedParticipantsColumnIfNotExists);
        addFileColumnIfMissing("meeting_url", meetingMapper::addMeetingUrlColumnIfNotExists);
        addFileColumnIfMissing("notification_result_json", meetingMapper::addNotificationResultColumnIfNotExists);
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

    public MeetingVo createMeeting(AuthenticatedActor actor, String title, String scheduledTime, String note) {
        Long userId = actor.userId();
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

    public List<MeetingVo> getMeetings(AuthenticatedActor actor) {
        return meetingMapper.findByUserId(actor.userId()).stream()
                .map(m -> toVo(m, fileMapper.findByMeetingId(m.getId())))
                .toList();
    }

    public MeetingVo getMeeting(AuthenticatedActor actor, Long meetingId) {
        Meeting meeting = requireView(actor, meetingId);
        return toVo(meeting, fileMapper.findByMeetingId(meetingId));
    }

    public MeetingFileVo uploadFile(AuthenticatedActor actor, Long meetingId, MultipartFile file) throws IOException {
        log.info("[MeetingService] uploadFile start, meetingId={}, fileName={}",
                meetingId, file != null ? file.getOriginalFilename() : null);
        requireOwner(actor, meetingId);
        if (file == null || file.isEmpty()) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "请选择要上传的会议文件");
        }
        String originalName = file.getOriginalFilename();
        if (originalName == null || originalName.isBlank()) originalName = "unnamed";
        String ext = extension(originalName).toLowerCase();
        validateReportFileType(meetingId, originalName, ext);
        byte[] rawBytes = file.getBytes();
        String text = preMeetingService.extractFileText(rawBytes, ext, originalName);
        return persistFile(meetingId, originalName, ext, text, rawBytes);
    }

    public MeetingFileVo savePreMeetingFile(AuthenticatedActor actor, Long meetingId, String fileId) {
        log.info("[MeetingService] savePreMeetingFile start, meetingId={}, fileId={}", meetingId, fileId);
        requireOwner(actor, meetingId);
        PreMeetingService.StoredPreMeetingFile storedFile = preMeetingService.requireStoredFile(fileId);
        String originalName = storedFile.fileName();
        if (originalName == null || originalName.isBlank()) originalName = "unnamed";
        String ext = storedFile.fileType() == null || storedFile.fileType().isBlank()
                ? extension(originalName).toLowerCase()
                : storedFile.fileType().toLowerCase();
        validateReportFileType(meetingId, originalName, ext);
        MeetingFileVo saved = persistFile(
                meetingId,
                originalName,
                ext,
                storedFile.text(),
                storedFile.originalBytes());
        log.info("[MeetingService] savePreMeetingFile done, meetingId={}, fileId={}, savedFileId={}",
                meetingId, fileId, saved.getId());
        return saved;
    }

    private MeetingFileVo persistFile(Long meetingId, String originalName, String ext, String text, byte[] rawBytes) {
        String normalizedText = text == null ? "" : text;
        PersistentPreMeetingFile entity = new PersistentPreMeetingFile();
        entity.setMeetingId(meetingId);
        entity.setFileName(originalName);
        entity.setFileType(ext);
        entity.setFileContent(normalizedText);
        entity.setFileData(rawBytes == null ? new byte[0] : rawBytes);
        fileMapper.insert(entity);
        log.info("[MeetingService] uploadFile done, meetingId={}, fileName={}, textLen={}",
                meetingId, originalName, normalizedText.length());
        if (entity.getId() != null && !normalizedText.isBlank()) {
            contentEmbeddingService.asyncEmbedFileContent(entity.getId(), meetingId, originalName, normalizedText);
        }
        return toFileVo(entity);
    }

    private void validateReportFileType(Long meetingId, String originalName, String ext) {
        if (!REPORT_FILE_EXTENSIONS.contains(ext)) {
            log.warn("[MeetingService] uploadFile rejected, meetingId={}, fileName={}, ext={}",
                    meetingId, originalName, ext);
            throw BizException.of(ErrorCode.BAD_REQUEST, REPORT_FILE_TYPE_MESSAGE);
        }
    }

    public List<MeetingFileVo> getFiles(AuthenticatedActor actor, Long meetingId) {
        requireView(actor, meetingId);
        return fileMapper.findByMeetingId(meetingId).stream().map(this::toFileVo).toList();
    }

    public void deleteFile(AuthenticatedActor actor, Long meetingId, Long fileId) {
        requireOwner(actor, meetingId);
        PersistentPreMeetingFile file = fileMapper.findById(fileId);
        if (file == null) throw BizException.of(ErrorCode.NOT_FOUND, "文件不存在");
        if (!meetingId.equals(file.getMeetingId())) {
            throw BizException.of(ErrorCode.NOT_FOUND, "文件不存在");
        }
        fileMapper.deleteById(fileId);
        contentEmbeddingService.deleteByTypeAndRefId(ContentEmbeddingService.TYPE_FILE_SUMMARY, fileId);
        contentEmbeddingService.deleteByTypeAndRefId(ContentEmbeddingService.TYPE_FILE_CONTENT, fileId);
    }

    public PersistentPreMeetingFile getFileWithContent(AuthenticatedActor actor, Long meetingId, Long fileId) {
        requireView(actor, meetingId);
        PersistentPreMeetingFile f = fileMapper.findById(fileId);
        if (f == null) throw BizException.of(ErrorCode.NOT_FOUND, "文件不存在");
        requireFileBelongsToMeeting(f, meetingId);
        return f;
    }

    public void saveFileSummary(AuthenticatedActor actor, Long meetingId, Long fileId, String summary) {
        requireOwner(actor, meetingId);
        PersistentPreMeetingFile owned = fileMapper.findById(fileId);
        if (owned == null) throw BizException.of(ErrorCode.NOT_FOUND, "文件不存在");
        requireFileBelongsToMeeting(owned, meetingId);
        fileMapper.updateSummary(fileId, summary);
        if (summary != null && !summary.isBlank()) {
            PersistentPreMeetingFile f = fileMapper.findById(fileId);
            if (f != null) {
                contentEmbeddingService.asyncEmbedFileSummary(fileId, f.getMeetingId(), f.getFileName(), summary);
            }
        }
    }

    public PersistentPreMeetingFile getFileForDownload(AuthenticatedActor actor, Long meetingId, Long fileId) {
        requireView(actor, meetingId);
        PersistentPreMeetingFile f = fileMapper.findByIdForDownload(fileId);
        if (f == null) throw BizException.of(ErrorCode.NOT_FOUND, "文件不存在");
        requireFileBelongsToMeeting(f, meetingId);
        return f;
    }

    /** Full record incl. both extracted text and original bytes — used to re-load a file for re-summary. */
    public PersistentPreMeetingFile getFileFull(AuthenticatedActor actor, Long meetingId, Long fileId) {
        requireView(actor, meetingId);
        PersistentPreMeetingFile f = fileMapper.findByIdFull(fileId);
        if (f == null) throw BizException.of(ErrorCode.NOT_FOUND, "文件不存在");
        requireFileBelongsToMeeting(f, meetingId);
        return f;
    }

    public void setMeetingUrl(AuthenticatedActor actor, Long meetingId, String meetingUrl) {
        log.info("[MeetingService] setMeetingUrl start, meetingId={}, hasUrl={}",
                meetingId, meetingUrl != null && !meetingUrl.isBlank());
        requireOwner(actor, meetingId);
        String normalized = meetingUrl == null || meetingUrl.isBlank() ? null : meetingUrl.trim();
        meetingMapper.updateMeetingUrl(meetingId, normalized);
        log.info("[MeetingService] setMeetingUrl end, meetingId={}", meetingId);
    }

    public void saveNotificationResult(AuthenticatedActor actor, Long meetingId, String notificationResultJson) {
        log.info("[MeetingService] saveNotificationResult start, meetingId={}, payloadLen={}",
                meetingId, notificationResultJson == null ? 0 : notificationResultJson.length());
        requireOwner(actor, meetingId);
        meetingMapper.updateNotificationResult(meetingId, notificationResultJson);
        log.info("[MeetingService] saveNotificationResult end, meetingId={}", meetingId);
    }

    public String getMeetingNoticeText(AuthenticatedActor actor, Long meetingId) {
        log.info("[MeetingService] getMeetingNoticeText start, meetingId={}", meetingId);
        requireView(actor, meetingId);
        List<PersistentPreMeetingFile> files = fileMapper.findByMeetingId(meetingId);
        if (files.isEmpty()) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "请先上传会议通知");
        }
        PersistentPreMeetingFile selected = selectMeetingNotice(files);
        String content = selected.getFileContent();
        if (content == null || content.isBlank()) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "会议通知内容为空，无法解析");
        }
        log.info("[MeetingService] getMeetingNoticeText end, meetingId={}, fileId={}, fileName={}, textLen={}",
                meetingId, selected.getId(), selected.getFileName(), content.length());
        return content;
    }

    @Transactional
    public void deleteMeeting(AuthenticatedActor actor, Long meetingId) {
        resourceOwnershipPolicy.requireOwnedMeeting(actor, meetingId);   // P3:删除整场会议仅 owner(OPERATE 成员不可删)
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
        meetingMapper.clearAssociatedData(meetingId);              // 应到名单 / 实到核对
        fileMapper.deleteByMeetingId(meetingId);                   // 上传的会议安排 / 会议文件(含二进制)
        actionItemMapper.deleteByMeetingId(meetingId);             // 行动项（按会议）
        contentEmbeddingService.deleteByMeetingId(meetingId);      // 会议级向量
        preMeetingService.deleteUsageByMeetingId(meetingId);       // 会前成本

        log.info("[MeetingService] deleteMeeting done, meetingId={}, cascadedSessions={}, cleanedSessionData={}",
                meetingId, sessions, sessionIds.size());
    }

    /** 写操作:owner 或被授予 OPERATE 的成员(P3)。 */
    private Meeting requireOwner(AuthenticatedActor actor, Long meetingId) {
        return resourceOwnershipPolicy.requireMeetingAccess(
                actor, meetingId, com.si.backend.security.AccessLevel.OPERATE);
    }

    /** 读操作:owner 或被授予 VIEW/OPERATE 的成员(P3,使 VIEWER 能看被分配会议)。 */
    private Meeting requireView(AuthenticatedActor actor, Long meetingId) {
        return resourceOwnershipPolicy.requireMeetingAccess(
                actor, meetingId, com.si.backend.security.AccessLevel.VIEW);
    }

    private void requireFileBelongsToMeeting(PersistentPreMeetingFile file, Long meetingId) {
        if (!meetingId.equals(file.getMeetingId())) {
            throw BizException.of(ErrorCode.NOT_FOUND, "文件不存在");
        }
    }

    private MeetingVo toVo(Meeting m, List<PersistentPreMeetingFile> files) {
        return MeetingVo.builder()
                .id(m.getId())
                .userId(m.getUserId())
                .title(m.getTitle())
                .scheduledTime(m.getScheduledTime() != null ? m.getScheduledTime().format(DT_FMT) : null)
                .note(m.getNote())
                .meetingUrl(m.getMeetingUrl())
                .notificationResultJson(m.getNotificationResultJson())
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

    private PersistentPreMeetingFile selectMeetingNotice(List<PersistentPreMeetingFile> files) {
        return files.stream()
                .filter(file -> isNoticeLike(file.getFileName()) && hasFileContent(file))
                .findFirst()
                .orElseGet(() -> files.stream()
                        .filter(this::hasFileContent)
                        .findFirst()
                        .orElse(files.get(0)));
    }

    private boolean isNoticeLike(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return false;
        }
        String lower = fileName.toLowerCase();
        return NOTICE_FILE_NAME_HINTS.stream().anyMatch(lower::contains);
    }

    private boolean hasFileContent(PersistentPreMeetingFile file) {
        return file != null && file.getFileContent() != null && !file.getFileContent().isBlank();
    }
}
