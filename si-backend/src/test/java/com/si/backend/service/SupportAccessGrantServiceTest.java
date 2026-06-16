package com.si.backend.service;

import com.si.backend.common.BizException;
import com.si.backend.entity.Meeting;
import com.si.backend.entity.SupportAccessGrant;
import com.si.backend.mapper.MeetingMapper;
import com.si.backend.mapper.SupportAccessGrantMapper;
import com.si.backend.security.AuthenticatedActor;
import com.si.backend.security.Role;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P3 临时内容授权:仅 ADMIN 申请、不能自批、批准人须 owner/另一管理员、生效校验。
 */
class SupportAccessGrantServiceTest {

    private final SupportAccessGrantMapper grantMapper = mock(SupportAccessGrantMapper.class);
    private final MeetingMapper meetingMapper = mock(MeetingMapper.class);
    private final AuditService auditService = mock(AuditService.class);
    private final SupportAccessGrantService service =
            new SupportAccessGrantService(grantMapper, meetingMapper, auditService);

    private static final AuthenticatedActor ADMIN_A = new AuthenticatedActor(1L, Role.ADMIN);
    private static final AuthenticatedActor ADMIN_B = new AuthenticatedActor(2L, Role.ADMIN);
    private static final AuthenticatedActor OPERATOR = new AuthenticatedActor(5L, Role.OPERATOR);

    private Meeting meetingOwnedBy(long owner) {
        Meeting m = new Meeting();
        m.setId(70L);
        m.setUserId(owner);
        return m;
    }

    private SupportAccessGrant pendingGrant(long requestedBy) {
        SupportAccessGrant g = new SupportAccessGrant();
        g.setId(100L);
        g.setResourceType("MEETING");
        g.setResourceId("70");
        g.setRequestedBy(requestedBy);
        g.setExpiresAt(LocalDateTime.now().plusMinutes(60));
        return g;
    }

    @Test
    void request_nonAdmin_forbidden() {
        assertEquals(403, assertThrows(BizException.class,
                () -> service.request(OPERATOR, 70L, "排障", 60)).getCode());
    }

    @Test
    void request_blankReason_or_badTtl_rejected() {
        when(meetingMapper.findById(70L)).thenReturn(meetingOwnedBy(5L));
        assertEquals(400, assertThrows(BizException.class, () -> service.request(ADMIN_A, 70L, "  ", 60)).getCode());
        assertEquals(400, assertThrows(BizException.class, () -> service.request(ADMIN_A, 70L, "ok", 0)).getCode());
        assertEquals(400, assertThrows(BizException.class, () -> service.request(ADMIN_A, 70L, "ok", 121)).getCode());
    }

    @Test
    void request_ok_inserts() {
        when(meetingMapper.findById(70L)).thenReturn(meetingOwnedBy(5L));
        service.request(ADMIN_A, 70L, "客户投诉排障", 60);
        verify(grantMapper).insert(any());
    }

    @Test
    void approve_selfApprove_forbidden() {
        when(grantMapper.findById(100L)).thenReturn(pendingGrant(1L)); // 申请人=ADMIN_A
        when(meetingMapper.findById(70L)).thenReturn(meetingOwnedBy(5L));
        assertEquals(403, assertThrows(BizException.class, () -> service.approve(ADMIN_A, 100L)).getCode());
        verify(grantMapper, never()).approve(anyLong(), anyLong());
    }

    @Test
    void approve_byOwner_ok() {
        when(grantMapper.findById(100L)).thenReturn(pendingGrant(1L)); // 申请人=ADMIN_A
        when(meetingMapper.findById(70L)).thenReturn(meetingOwnedBy(5L)); // owner=OPERATOR(5)
        when(grantMapper.approve(100L, 5L)).thenReturn(1);
        service.approve(OPERATOR, 100L);
        verify(grantMapper).approve(100L, 5L);
    }

    @Test
    void approve_byAnotherAdmin_ok() {
        when(grantMapper.findById(100L)).thenReturn(pendingGrant(1L));
        when(meetingMapper.findById(70L)).thenReturn(meetingOwnedBy(5L));
        when(grantMapper.approve(100L, 2L)).thenReturn(1);
        service.approve(ADMIN_B, 100L);
        verify(grantMapper).approve(100L, 2L);
    }

    @Test
    void approve_alreadyApproved_rejected() {
        SupportAccessGrant g = pendingGrant(1L);
        g.setApprovedBy(9L);
        when(grantMapper.findById(100L)).thenReturn(g);
        assertEquals(400, assertThrows(BizException.class, () -> service.approve(ADMIN_B, 100L)).getCode());
    }

    @Test
    void hasActiveContentGrant_reflectsCount() {
        when(grantMapper.countActive(1L, "MEETING", "70")).thenReturn(1);
        assertTrue(service.hasActiveContentGrant(1L, 70L));
        when(grantMapper.countActive(1L, "MEETING", "70")).thenReturn(0);
        assertFalse(service.hasActiveContentGrant(1L, 70L));
    }
}
