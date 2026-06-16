package com.si.backend.service;

import com.si.backend.common.BizException;
import com.si.backend.common.ErrorCode;
import com.si.backend.entity.InterpretationSession;
import com.si.backend.entity.Meeting;
import com.si.backend.entity.MeetingActionItem;
import com.si.backend.entity.PersistentPreMeetingFile;
import com.si.backend.entity.MeetingMember;
import com.si.backend.entity.SessionAudioRecord;
import com.si.backend.entity.SpeakerSummaryRecord;
import com.si.backend.mapper.MeetingActionItemMapper;
import com.si.backend.mapper.MeetingMapper;
import com.si.backend.mapper.MeetingMemberMapper;
import com.si.backend.mapper.PersistentPreMeetingFileMapper;
import com.si.backend.mapper.SessionAudioRecordMapper;
import com.si.backend.mapper.SpeakerSummaryRecordMapper;
import com.si.backend.mapper.SupportAccessGrantMapper;
import com.si.backend.security.AccessLevel;
import com.si.backend.security.AuthenticatedActor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * P0.5 ownership policy. Every check receives an explicit actor and returns the authorized resource.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResourceOwnershipPolicy {

    private final InterpretationSessionService sessionService;
    private final MeetingMapper meetingMapper;
    private final PersistentPreMeetingFileMapper fileMapper;
    private final SpeakerSummaryRecordMapper speakerSummaryMapper;
    private final MeetingActionItemMapper actionItemMapper;
    private final SessionAudioRecordMapper audioRecordMapper;
    private final MeetingMemberMapper meetingMemberMapper;
    private final SupportAccessGrantMapper supportAccessGrantMapper;

    public InterpretationSession requireOwnedSession(AuthenticatedActor actor, String sessionId) {
        requireActor(actor);
        InterpretationSession session = resolveSession(sessionId);
        requireOwner(actor, session != null ? session.getUserId() : null, "session", sessionId);
        return session;
    }

    public Meeting requireOwnedMeeting(AuthenticatedActor actor, Long meetingId) {
        requireActor(actor);
        Meeting meeting = meetingId == null ? null : meetingMapper.findById(meetingId);
        requireOwner(actor, meeting != null ? meeting.getUserId() : null, "meeting", meetingId);
        return meeting;
    }

    /**
     * P3 数据范围 ASSIGNED:owner 拥有全部权限;否则需 meeting_member 中具备足够级别(OPERATE 蕴含 VIEW)。
     * 非 owner 且无足够授权 → 404(防枚举)。ADMIN 不在此走捷径(内容访问由 support grant 另行处理)。
     */
    public Meeting requireMeetingAccess(AuthenticatedActor actor, Long meetingId, AccessLevel required) {
        requireActor(actor);
        Meeting meeting = meetingId == null ? null : meetingMapper.findById(meetingId);
        if (meeting == null) {
            throwNotFound("meeting", meetingId);
        }
        if (actor.userId().equals(meeting.getUserId())) {
            return meeting; // owner:完全访问
        }
        MeetingMember member = meetingMemberMapper.findMember(meetingId, actor.userId());
        if (member != null && AccessLevel.satisfies(member.getAccessLevel(), required)) {
            return meeting; // 被分配且级别足够
        }
        // P3:ADMIN 持有生效的临时内容授权 → 允许内容读(VIEW)。OPERATE(写)不经此通道。
        if (required == AccessLevel.VIEW && actor.isAdmin()
                && supportAccessGrantMapper.countActive(
                        actor.userId(), SupportAccessGrantService.RESOURCE_MEETING, String.valueOf(meetingId)) > 0) {
            log.info("[ResourceOwnershipPolicy] admin content grant access, meetingId={}, actorId={}", meetingId, actor.userId());
            return meeting;
        }
        log.warn("[ResourceOwnershipPolicy] meeting access denied, meetingId={}, actorId={}, required={}",
                meetingId, actor.userId(), required);
        throwNotFound("meeting", meetingId);
        return meeting; // unreachable
    }

    public PersistentPreMeetingFile requireOwnedFile(AuthenticatedActor actor, Long fileId) {
        requireActor(actor);
        PersistentPreMeetingFile file = fileId == null ? null : fileMapper.findByIdFull(fileId);
        if (file == null) {
            throwNotFound("file", fileId);
        }
        requireOwnedMeeting(actor, file.getMeetingId());
        return file;
    }

    public SpeakerSummaryRecord requireOwnedSpeakerSummary(AuthenticatedActor actor, Long summaryId) {
        requireActor(actor);
        SpeakerSummaryRecord summary = summaryId == null ? null : speakerSummaryMapper.findById(summaryId);
        if (summary == null) {
            throwNotFound("speakerSummary", summaryId);
        }
        requireOwnedSession(actor, summary.getSessionId());
        return summary;
    }

    public MeetingActionItem requireOwnedActionItem(AuthenticatedActor actor, Long actionItemId) {
        requireActor(actor);
        MeetingActionItem actionItem = actionItemId == null ? null : actionItemMapper.findById(actionItemId);
        if (actionItem == null) {
            throwNotFound("actionItem", actionItemId);
        }
        if (actionItem.getSessionId() != null && !actionItem.getSessionId().isBlank()) {
            requireOwnedSession(actor, actionItem.getSessionId());
        } else {
            requireOwner(actor, actionItem.getUserId(), "actionItem", actionItemId);
        }
        return actionItem;
    }

    public SessionAudioRecord requireOwnedAudio(AuthenticatedActor actor, Long audioRecordId) {
        requireActor(actor);
        SessionAudioRecord audio = audioRecordId == null ? null : audioRecordMapper.findById(audioRecordId);
        requireOwner(actor, audio != null ? audio.getUserId() : null, "audioRecord", audioRecordId);
        return audio;
    }

    private InterpretationSession resolveSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        InterpretationSession activeSession = sessionService.getSession(sessionId).orElse(null);
        return activeSession != null ? activeSession : sessionService.getSessionHistory(sessionId);
    }

    private void requireActor(AuthenticatedActor actor) {
        if (actor == null || actor.userId() == null) {
            throw BizException.of(ErrorCode.UNAUTHORIZED, "Unauthenticated");
        }
    }

    private void requireOwner(AuthenticatedActor actor, Long ownerId, String resourceType, Object resourceId) {
        if (ownerId == null || !actor.userId().equals(ownerId)) {
            log.warn("[ResourceOwnershipPolicy] ownership denied, resourceType={}, resourceId={}, actorId={}",
                    resourceType, resourceId, actor.userId());
            throwNotFound(resourceType, resourceId);
        }
    }

    private void throwNotFound(String resourceType, Object resourceId) {
        log.warn("[ResourceOwnershipPolicy] resource unavailable, resourceType={}, resourceId={}",
                resourceType, resourceId);
        throw BizException.of(ErrorCode.NOT_FOUND, "Resource not found");
    }
}
