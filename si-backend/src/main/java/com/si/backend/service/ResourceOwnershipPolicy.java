package com.si.backend.service;

import com.si.backend.common.BizException;
import com.si.backend.common.ErrorCode;
import com.si.backend.entity.InterpretationSession;
import com.si.backend.entity.Meeting;
import com.si.backend.entity.MeetingActionItem;
import com.si.backend.entity.MeetingMember;
import com.si.backend.entity.PersistentPreMeetingFile;
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
 * Central resource ownership and meeting access policy.
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
        long startMs = System.currentTimeMillis();
        requireActor(actor);
        log.info("[ResourceOwnershipPolicy] requireOwnedSession start, actorId={}, role={}, sessionId={}",
                actor.userId(), actor.role(), sessionId);
        InterpretationSession session = resolveSession(sessionId);
        requireOwner(actor, session != null ? session.getUserId() : null, "session", sessionId);
        log.info("[ResourceOwnershipPolicy] requireOwnedSession allow, actorId={}, sessionId={}, ownerId={}, costMs={}",
                actor.userId(), sessionId, session != null ? session.getUserId() : null,
                System.currentTimeMillis() - startMs);
        return session;
    }

    public Meeting requireOwnedMeeting(AuthenticatedActor actor, Long meetingId) {
        long startMs = System.currentTimeMillis();
        requireActor(actor);
        log.info("[ResourceOwnershipPolicy] requireOwnedMeeting start, actorId={}, role={}, meetingId={}",
                actor.userId(), actor.role(), meetingId);
        Meeting meeting = meetingId == null ? null : meetingMapper.findById(meetingId);
        requireOwner(actor, meeting != null ? meeting.getUserId() : null, "meeting", meetingId);
        log.info("[ResourceOwnershipPolicy] requireOwnedMeeting allow, actorId={}, meetingId={}, ownerId={}, costMs={}",
                actor.userId(), meetingId, meeting != null ? meeting.getUserId() : null,
                System.currentTimeMillis() - startMs);
        return meeting;
    }

    public Meeting requireMeetingAccess(AuthenticatedActor actor, Long meetingId, AccessLevel required) {
        long startMs = System.currentTimeMillis();
        requireActor(actor);
        log.info("[ResourceOwnershipPolicy] requireMeetingAccess start, actorId={}, role={}, meetingId={}, required={}",
                actor.userId(), actor.role(), meetingId, required);
        Meeting meeting = meetingId == null ? null : meetingMapper.findById(meetingId);
        if (meeting == null) {
            throwNotFound("meeting", meetingId);
        }
        if (actor.userId().equals(meeting.getUserId())) {
            log.info("[ResourceOwnershipPolicy] requireMeetingAccess allow, reason=owner, actorId={}, meetingId={}, required={}, costMs={}",
                    actor.userId(), meetingId, required, System.currentTimeMillis() - startMs);
            return meeting;
        }

        MeetingMember member = meetingMemberMapper.findMember(meetingId, actor.userId());
        if (member != null && AccessLevel.satisfies(member.getAccessLevel(), required)) {
            log.info("[ResourceOwnershipPolicy] requireMeetingAccess allow, reason=member, actorId={}, meetingId={}, required={}, granted={}, costMs={}",
                    actor.userId(), meetingId, required, member.getAccessLevel(), System.currentTimeMillis() - startMs);
            return meeting;
        }

        if (required == AccessLevel.VIEW && actor.isAdmin()
                && supportAccessGrantMapper.countActive(
                actor.userId(), SupportAccessGrantService.RESOURCE_MEETING, String.valueOf(meetingId)) > 0) {
            log.info("[ResourceOwnershipPolicy] requireMeetingAccess allow, reason=supportGrant, actorId={}, meetingId={}, required={}, costMs={}",
                    actor.userId(), meetingId, required, System.currentTimeMillis() - startMs);
            return meeting;
        }

        log.warn("[ResourceOwnershipPolicy] requireMeetingAccess deny, reason=insufficientAccess, actorId={}, role={}, meetingId={}, ownerId={}, memberLevel={}, required={}, costMs={}",
                actor.userId(), actor.role(), meetingId, meeting.getUserId(),
                member != null ? member.getAccessLevel() : null, required,
                System.currentTimeMillis() - startMs);
        throwNotFound("meeting", meetingId);
        return meeting;
    }

    public PersistentPreMeetingFile requireOwnedFile(AuthenticatedActor actor, Long fileId) {
        long startMs = System.currentTimeMillis();
        requireActor(actor);
        log.info("[ResourceOwnershipPolicy] requireOwnedFile start, actorId={}, role={}, fileId={}",
                actor.userId(), actor.role(), fileId);
        PersistentPreMeetingFile file = fileId == null ? null : fileMapper.findByIdFull(fileId);
        if (file == null) {
            throwNotFound("file", fileId);
        }
        requireOwnedMeeting(actor, file.getMeetingId());
        log.info("[ResourceOwnershipPolicy] requireOwnedFile allow, actorId={}, fileId={}, meetingId={}, costMs={}",
                actor.userId(), fileId, file.getMeetingId(), System.currentTimeMillis() - startMs);
        return file;
    }

    public SpeakerSummaryRecord requireOwnedSpeakerSummary(AuthenticatedActor actor, Long summaryId) {
        long startMs = System.currentTimeMillis();
        requireActor(actor);
        log.info("[ResourceOwnershipPolicy] requireOwnedSpeakerSummary start, actorId={}, role={}, summaryId={}",
                actor.userId(), actor.role(), summaryId);
        SpeakerSummaryRecord summary = summaryId == null ? null : speakerSummaryMapper.findById(summaryId);
        if (summary == null) {
            throwNotFound("speakerSummary", summaryId);
        }
        requireOwnedSession(actor, summary.getSessionId());
        log.info("[ResourceOwnershipPolicy] requireOwnedSpeakerSummary allow, actorId={}, summaryId={}, sessionId={}, costMs={}",
                actor.userId(), summaryId, summary.getSessionId(), System.currentTimeMillis() - startMs);
        return summary;
    }

    public MeetingActionItem requireOwnedActionItem(AuthenticatedActor actor, Long actionItemId) {
        long startMs = System.currentTimeMillis();
        requireActor(actor);
        log.info("[ResourceOwnershipPolicy] requireOwnedActionItem start, actorId={}, role={}, actionItemId={}",
                actor.userId(), actor.role(), actionItemId);
        MeetingActionItem actionItem = actionItemId == null ? null : actionItemMapper.findById(actionItemId);
        if (actionItem == null) {
            throwNotFound("actionItem", actionItemId);
        }
        if (actionItem.getSessionId() != null && !actionItem.getSessionId().isBlank()) {
            requireOwnedSession(actor, actionItem.getSessionId());
        } else {
            requireOwner(actor, actionItem.getUserId(), "actionItem", actionItemId);
        }
        log.info("[ResourceOwnershipPolicy] requireOwnedActionItem allow, actorId={}, actionItemId={}, sessionId={}, ownerId={}, costMs={}",
                actor.userId(), actionItemId, actionItem.getSessionId(), actionItem.getUserId(),
                System.currentTimeMillis() - startMs);
        return actionItem;
    }

    public SessionAudioRecord requireOwnedAudio(AuthenticatedActor actor, Long audioRecordId) {
        long startMs = System.currentTimeMillis();
        requireActor(actor);
        log.info("[ResourceOwnershipPolicy] requireOwnedAudio start, actorId={}, role={}, audioRecordId={}",
                actor.userId(), actor.role(), audioRecordId);
        SessionAudioRecord audio = audioRecordId == null ? null : audioRecordMapper.findById(audioRecordId);
        requireOwner(actor, audio != null ? audio.getUserId() : null, "audioRecord", audioRecordId);
        log.info("[ResourceOwnershipPolicy] requireOwnedAudio allow, actorId={}, audioRecordId={}, ownerId={}, costMs={}",
                actor.userId(), audioRecordId, audio != null ? audio.getUserId() : null,
                System.currentTimeMillis() - startMs);
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
            log.warn("[ResourceOwnershipPolicy] actor missing");
            throw BizException.of(ErrorCode.UNAUTHORIZED, "Unauthenticated");
        }
    }

    private void requireOwner(AuthenticatedActor actor, Long ownerId, String resourceType, Object resourceId) {
        if (ownerId == null || !actor.userId().equals(ownerId)) {
            log.warn("[ResourceOwnershipPolicy] ownership denied, resourceType={}, resourceId={}, actorId={}, role={}, ownerId={}",
                    resourceType, resourceId, actor.userId(), actor.role(), ownerId);
            throwNotFound(resourceType, resourceId);
        }
    }

    private void throwNotFound(String resourceType, Object resourceId) {
        log.warn("[ResourceOwnershipPolicy] resource unavailable, resourceType={}, resourceId={}",
                resourceType, resourceId);
        throw BizException.of(ErrorCode.NOT_FOUND, "Resource not found");
    }
}
