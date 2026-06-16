package com.si.backend.service;

import com.si.backend.common.BizException;
import com.si.backend.entity.Meeting;
import com.si.backend.entity.SiUser;
import com.si.backend.mapper.MeetingMapper;
import com.si.backend.mapper.MeetingMemberMapper;
import com.si.backend.mapper.UserMapper;
import com.si.backend.security.AuthenticatedActor;
import com.si.backend.security.Role;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P3 会议成员授权规则:owner 只能授 VIEW;OPERATE 须 ADMIN;不能授管理员/owner 自己;仅 owner/admin 可管理。
 */
class MeetingMemberServiceTest {

    private final MeetingMapper meetingMapper = mock(MeetingMapper.class);
    private final MeetingMemberMapper memberMapper = mock(MeetingMemberMapper.class);
    private final UserMapper userMapper = mock(UserMapper.class);
    private final AuditService auditService = mock(AuditService.class);
    private final MeetingMemberService service =
            new MeetingMemberService(meetingMapper, memberMapper, userMapper, auditService);

    private static final AuthenticatedActor OWNER = new AuthenticatedActor(5L, Role.OPERATOR);
    private static final AuthenticatedActor ADMIN = new AuthenticatedActor(1L, Role.ADMIN);
    private static final AuthenticatedActor OUTSIDER = new AuthenticatedActor(8L, Role.OPERATOR);

    private Meeting meetingOwnedBy(long owner) {
        Meeting m = new Meeting();
        m.setId(70L);
        m.setUserId(owner);
        return m;
    }

    private SiUser target(long id, String role) {
        SiUser u = new SiUser();
        u.setId(id);
        u.setUsername("u" + id);
        u.setRole(role);
        return u;
    }

    @Test
    void ownerAssignsView_ok() {
        when(meetingMapper.findById(70L)).thenReturn(meetingOwnedBy(5L));
        when(userMapper.findById(9L)).thenReturn(target(9L, "OPERATOR"));
        service.assign(OWNER, 70L, 9L, "VIEW");
        verify(memberMapper).upsert(any());
    }

    @Test
    void ownerAssignsOperate_forbidden() {
        when(meetingMapper.findById(70L)).thenReturn(meetingOwnedBy(5L));
        when(userMapper.findById(9L)).thenReturn(target(9L, "OPERATOR"));
        assertEquals(403, assertThrows(BizException.class, () -> service.assign(OWNER, 70L, 9L, "OPERATE")).getCode());
        verify(memberMapper, never()).upsert(any());
    }

    @Test
    void adminAssignsOperate_ok() {
        when(meetingMapper.findById(70L)).thenReturn(meetingOwnedBy(5L));
        when(userMapper.findById(9L)).thenReturn(target(9L, "VIEWER"));
        service.assign(ADMIN, 70L, 9L, "OPERATE");
        verify(memberMapper).upsert(any());
    }

    @Test
    void assignAdminTarget_rejected() {
        when(meetingMapper.findById(70L)).thenReturn(meetingOwnedBy(5L));
        when(userMapper.findById(2L)).thenReturn(target(2L, "ADMIN"));
        assertEquals(400, assertThrows(BizException.class, () -> service.assign(OWNER, 70L, 2L, "VIEW")).getCode());
    }

    @Test
    void assignOwnerAsMember_rejected() {
        when(meetingMapper.findById(70L)).thenReturn(meetingOwnedBy(5L));
        assertEquals(400, assertThrows(BizException.class, () -> service.assign(OWNER, 70L, 5L, "VIEW")).getCode());
    }

    @Test
    void invalidLevel_rejected() {
        when(meetingMapper.findById(70L)).thenReturn(meetingOwnedBy(5L));
        assertEquals(400, assertThrows(BizException.class, () -> service.assign(OWNER, 70L, 9L, "BOSS")).getCode());
    }

    @Test
    void nonOwnerNonAdmin_denied404() {
        when(meetingMapper.findById(70L)).thenReturn(meetingOwnedBy(5L));
        assertEquals(404, assertThrows(BizException.class, () -> service.assign(OUTSIDER, 70L, 9L, "VIEW")).getCode());
    }

    @Test
    void revoke_byOwner_ok() {
        when(meetingMapper.findById(70L)).thenReturn(meetingOwnedBy(5L));
        service.revoke(OWNER, 70L, 9L);
        verify(memberMapper).deleteMember(70L, 9L);
    }
}
