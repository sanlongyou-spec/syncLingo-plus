package com.si.backend.service;

import com.si.backend.common.BizException;
import com.si.backend.common.ErrorCode;
import com.si.backend.entity.Meeting;
import com.si.backend.entity.SupportAccessGrant;
import com.si.backend.mapper.MeetingMapper;
import com.si.backend.mapper.SupportAccessGrantMapper;
import com.si.backend.security.AuthenticatedActor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * P3 管理员临时内容授权。ADMIN 申请(理由 + TTL ≤ 2h)→ owner 或另一管理员批准(申请人不能自批)
 * → 有效期内授予该会议 MEETING_CONTENT_READ;全程审计。单管理员部署因"不能自批"自然无紧急正文通道。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SupportAccessGrantService {

    public static final String RESOURCE_MEETING = "MEETING";
    public static final String PERM_CONTENT_READ = "MEETING_CONTENT_READ";
    private static final int MAX_TTL_MINUTES = 120;

    private final SupportAccessGrantMapper grantMapper;
    private final MeetingMapper meetingMapper;
    private final AuditService auditService;

    public SupportAccessGrant request(AuthenticatedActor actor, Long meetingId, String reason, int ttlMinutes) {
        requireActor(actor);
        if (!actor.isAdmin()) {
            throw BizException.of(ErrorCode.FORBIDDEN, "仅管理员可申请临时内容授权");
        }
        if (reason == null || reason.isBlank()) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "必须填写授权理由");
        }
        if (ttlMinutes < 1 || ttlMinutes > MAX_TTL_MINUTES) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "有效期需在 1~" + MAX_TTL_MINUTES + " 分钟之间");
        }
        if (meetingId == null || meetingMapper.findById(meetingId) == null) {
            throw BizException.of(ErrorCode.NOT_FOUND, "会议不存在");
        }
        SupportAccessGrant grant = new SupportAccessGrant();
        grant.setGranteeUserId(actor.userId());
        grant.setResourceType(RESOURCE_MEETING);
        grant.setResourceId(String.valueOf(meetingId));
        grant.setPermissions(PERM_CONTENT_READ);
        grant.setReason(reason.trim());
        grant.setRequestedBy(actor.userId());
        grant.setExpiresAt(LocalDateTime.now().plusMinutes(ttlMinutes));
        grantMapper.insert(grant);
        auditService.record("SUPPORT_GRANT_REQUEST", "SUCCESS", RESOURCE_MEETING, String.valueOf(meetingId),
                "grantee=" + actor.userId() + ", ttlMin=" + ttlMinutes + ", reason=" + reason.trim());
        return grant;
    }

    public void approve(AuthenticatedActor actor, Long grantId) {
        requireActor(actor);
        SupportAccessGrant grant = grantId == null ? null : grantMapper.findById(grantId);
        if (grant == null) {
            throw BizException.of(ErrorCode.NOT_FOUND, "授权申请不存在");
        }
        if (grant.getApprovedBy() != null || grant.getRevokedAt() != null
                || grant.getExpiresAt() == null || grant.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "申请已批准/已撤销/已过期");
        }
        // 申请人不能自批
        if (actor.userId().equals(grant.getRequestedBy())) {
            throw BizException.of(ErrorCode.FORBIDDEN, "申请人不能批准自己的申请");
        }
        // 批准人须为会议 owner 或 另一名管理员
        Meeting meeting = meetingMapper.findById(parseMeetingId(grant));
        boolean owner = meeting != null && actor.userId().equals(meeting.getUserId());
        if (!owner && !actor.isAdmin()) {
            throw BizException.of(ErrorCode.FORBIDDEN, "仅会议所有者或管理员可批准");
        }
        int updated = grantMapper.approve(grantId, actor.userId());
        if (updated == 0) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "申请状态已变更,批准失败");
        }
        auditService.record("SUPPORT_GRANT_APPROVE", "SUCCESS", RESOURCE_MEETING, grant.getResourceId(),
                "grantId=" + grantId + ", grantee=" + grant.getGranteeUserId());
    }

    public void revoke(AuthenticatedActor actor, Long grantId) {
        requireActor(actor);
        SupportAccessGrant grant = grantId == null ? null : grantMapper.findById(grantId);
        if (grant == null) {
            throw BizException.of(ErrorCode.NOT_FOUND, "授权申请不存在");
        }
        Meeting meeting = meetingMapper.findById(parseMeetingId(grant));
        boolean owner = meeting != null && actor.userId().equals(meeting.getUserId());
        boolean requester = actor.userId().equals(grant.getRequestedBy());
        if (!owner && !requester && !actor.isAdmin()) {
            throw BizException.of(ErrorCode.FORBIDDEN, "无权撤销该授权");
        }
        grantMapper.revoke(grantId);
        auditService.record("SUPPORT_GRANT_REVOKE", "SUCCESS", RESOURCE_MEETING, grant.getResourceId(),
                "grantId=" + grantId);
    }

    public List<SupportAccessGrant> listRecent(AuthenticatedActor actor, int limit) {
        requireActor(actor);
        if (!actor.isAdmin()) {
            throw BizException.of(ErrorCode.FORBIDDEN, "需要管理员权限");
        }
        return grantMapper.findRecent(Math.max(1, Math.min(limit, 500)));
    }

    /** 供资源策略调用:该用户当前对该会议是否持有生效的内容授权。 */
    public boolean hasActiveContentGrant(Long userId, Long meetingId) {
        if (userId == null || meetingId == null) {
            return false;
        }
        return grantMapper.countActive(userId, RESOURCE_MEETING, String.valueOf(meetingId)) > 0;
    }

    private Long parseMeetingId(SupportAccessGrant grant) {
        try {
            return Long.valueOf(grant.getResourceId());
        } catch (Exception e) {
            return null;
        }
    }

    private void requireActor(AuthenticatedActor actor) {
        if (actor == null || actor.userId() == null) {
            throw BizException.of(ErrorCode.UNAUTHORIZED, "Unauthenticated");
        }
    }
}
