package com.si.backend.service;

import com.si.backend.common.BizException;
import com.si.backend.common.ErrorCode;
import com.si.backend.entity.Meeting;
import com.si.backend.entity.MeetingMember;
import com.si.backend.entity.SiUser;
import com.si.backend.mapper.MeetingMapper;
import com.si.backend.mapper.MeetingMemberMapper;
import com.si.backend.mapper.UserMapper;
import com.si.backend.security.AccessLevel;
import com.si.backend.security.AuthenticatedActor;
import com.si.backend.security.Role;
import com.si.backend.vo.MeetingMemberVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * P3 会议成员授权管理。规则:
 * <ul>
 *   <li>仅会议 owner 或 ADMIN 可管理成员;</li>
 *   <li>owner 只能授 VIEW;OPERATE 须 ADMIN;</li>
 *   <li>不能把 ADMIN 设为会议成员;不能给 owner 自己授权;</li>
 *   <li>OPERATE 成员可操作/编辑,但删除整场会议仍仅 owner(在 MeetingService 强制)。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MeetingMemberService {

    private final MeetingMapper meetingMapper;
    private final MeetingMemberMapper meetingMemberMapper;
    private final UserMapper userMapper;
    private final AuditService auditService;

    public MeetingMemberVo assign(AuthenticatedActor actor, Long meetingId, Long targetUserId, String levelRaw) {
        Meeting meeting = requireManageable(actor, meetingId);
        AccessLevel level = AccessLevel.from(levelRaw);
        if (level == null) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "访问级别不合法(仅 VIEW/OPERATE)");
        }
        if (level == AccessLevel.OPERATE && !actor.isAdmin()) {
            throw BizException.of(ErrorCode.FORBIDDEN, "仅管理员可授予 OPERATE 级别");
        }
        if (targetUserId == null) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "目标用户必填");
        }
        if (targetUserId.equals(meeting.getUserId())) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "会议所有者无需授权");
        }
        SiUser target = userMapper.findById(targetUserId);
        if (target == null) {
            throw BizException.of(ErrorCode.NOT_FOUND, "目标用户不存在");
        }
        if (Role.ADMIN == Role.from(target.getRole())) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "不能将管理员设为会议成员");
        }
        MeetingMember member = new MeetingMember();
        member.setMeetingId(meetingId);
        member.setUserId(targetUserId);
        member.setAccessLevel(level.name());
        member.setAssignedBy(actor.userId());
        meetingMemberMapper.upsert(member);
        auditService.record("MEETING_MEMBER_ASSIGN", "SUCCESS", "MEETING", String.valueOf(meetingId),
                "user=" + targetUserId + ", level=" + level);
        return MeetingMemberVo.builder()
                .userId(targetUserId)
                .username(target.getUsername())
                .accessLevel(level.name())
                .build();
    }

    public List<MeetingMemberVo> list(AuthenticatedActor actor, Long meetingId) {
        requireManageable(actor, meetingId);
        return meetingMemberMapper.findByMeetingId(meetingId).stream()
                .map(m -> {
                    SiUser u = userMapper.findById(m.getUserId());
                    return MeetingMemberVo.builder()
                            .userId(m.getUserId())
                            .username(u != null ? u.getUsername() : null)
                            .accessLevel(m.getAccessLevel())
                            .build();
                })
                .toList();
    }

    public void revoke(AuthenticatedActor actor, Long meetingId, Long targetUserId) {
        requireManageable(actor, meetingId);
        meetingMemberMapper.deleteMember(meetingId, targetUserId);
        auditService.record("MEETING_MEMBER_REVOKE", "SUCCESS", "MEETING", String.valueOf(meetingId),
                "user=" + targetUserId);
    }

    /** 仅 owner 或 ADMIN 可管理成员;否则 404(防枚举)。 */
    private Meeting requireManageable(AuthenticatedActor actor, Long meetingId) {
        if (actor == null || actor.userId() == null) {
            throw BizException.of(ErrorCode.UNAUTHORIZED, "Unauthenticated");
        }
        Meeting meeting = meetingId == null ? null : meetingMapper.findById(meetingId);
        if (meeting == null) {
            throw BizException.of(ErrorCode.NOT_FOUND, "会议不存在");
        }
        boolean owner = actor.userId().equals(meeting.getUserId());
        if (!owner && !actor.isAdmin()) {
            log.warn("[MeetingMemberService] member manage denied, meetingId={}, actorId={}", meetingId, actor.userId());
            throw BizException.of(ErrorCode.NOT_FOUND, "会议不存在");
        }
        return meeting;
    }
}
