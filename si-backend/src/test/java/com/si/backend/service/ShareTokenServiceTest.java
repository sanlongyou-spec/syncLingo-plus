package com.si.backend.service;

import com.si.backend.common.BizException;
import com.si.backend.entity.InterpretationSession;
import com.si.backend.entity.ShareToken;
import com.si.backend.mapper.ShareTokenMapper;
import com.si.backend.security.AuthenticatedActor;
import com.si.backend.security.Role;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P4 分享令牌:签发、匿名解析(SESSION/CHANNEL)、失效校验、撤销权限。
 */
class ShareTokenServiceTest {

    private final ShareTokenMapper mapper = mock(ShareTokenMapper.class);
    private final ResourceOwnershipPolicy policy = mock(ResourceOwnershipPolicy.class);
    private final InterpretationSessionService sessionService = mock(InterpretationSessionService.class);
    private final AuditService auditService = mock(AuditService.class);
    private final ShareTokenService service = new ShareTokenService(mapper, policy, sessionService, auditService);

    private static final AuthenticatedActor OPERATOR = new AuthenticatedActor(5L, Role.OPERATOR);
    private static final AuthenticatedActor ADMIN = new AuthenticatedActor(1L, Role.ADMIN);

    @Test
    void mintSession_requiresOwnership_andInserts() {
        ShareTokenService.Issued issued = service.mintSessionToken(OPERATOR, "s1");
        verify(policy).requireOwnedSession(OPERATOR, "s1");
        verify(mapper).insert(any());
        assertEquals("SESSION", issued.kind());
        org.junit.jupiter.api.Assertions.assertNotNull(issued.token());
    }

    @Test
    void mintChannel_inserts() {
        ShareTokenService.Issued issued = service.mintChannelToken(OPERATOR);
        verify(mapper).insert(any());
        assertEquals("CHANNEL", issued.kind());
    }

    @Test
    void mint_setsThirtySixHourExpiry() {
        org.mockito.ArgumentCaptor<ShareToken> captor = org.mockito.ArgumentCaptor.forClass(ShareToken.class);
        LocalDateTime before = LocalDateTime.now();
        service.mintChannelToken(OPERATOR);
        verify(mapper).insert(captor.capture());
        LocalDateTime expiresAt = captor.getValue().getExpiresAt();
        org.junit.jupiter.api.Assertions.assertNotNull(expiresAt, "共享令牌必须设置过期时间");
        // 默认 36 小时有效：过期时间应落在 [now+35h59m, now+36h1m] 区间内
        org.junit.jupiter.api.Assertions.assertTrue(expiresAt.isAfter(before.plusHours(36).minusMinutes(1)));
        org.junit.jupiter.api.Assertions.assertTrue(expiresAt.isBefore(before.plusHours(36).plusMinutes(1)));
    }

    @Test
    void resolveSession_returnsSessionId() {
        ShareToken t = new ShareToken();
        t.setKind("SESSION");
        t.setSessionId("sess-1");
        when(mapper.findByHash(anyString())).thenReturn(t);
        assertEquals("sess-1", service.resolveSessionId("anyraw"));
    }

    @Test
    void resolveChannel_resolvesActiveSession() {
        ShareToken t = new ShareToken();
        t.setKind("CHANNEL");
        t.setOwnerUserId(5L);
        when(mapper.findByHash(anyString())).thenReturn(t);
        InterpretationSession active = new InterpretationSession();
        active.setSessionId("live-9");
        when(sessionService.getActiveSessionForUser(5L)).thenReturn(Optional.of(active));
        assertEquals("live-9", service.resolveSessionId("anyraw"));
    }

    @Test
    void resolveChannel_noActiveSession_returnsNull() {
        ShareToken t = new ShareToken();
        t.setKind("CHANNEL");
        t.setOwnerUserId(5L);
        when(mapper.findByHash(anyString())).thenReturn(t);
        when(sessionService.getActiveSessionForUser(5L)).thenReturn(Optional.empty());
        assertNull(service.resolveSessionId("anyraw"));
    }

    @Test
    void resolve_revoked_rejected401() {
        ShareToken t = new ShareToken();
        t.setKind("SESSION");
        t.setSessionId("s1");
        t.setRevokedAt(LocalDateTime.now().minusMinutes(1));
        when(mapper.findByHash(anyString())).thenReturn(t);
        assertEquals(401, assertThrows(BizException.class, () -> service.resolveSessionId("anyraw")).getCode());
    }

    @Test
    void resolve_unknownToken_rejected401() {
        when(mapper.findByHash(anyString())).thenReturn(null);
        assertEquals(401, assertThrows(BizException.class, () -> service.resolveSessionId("bogus")).getCode());
    }

    @Test
    void revokeChannel_byNonOwner_forbidden() {
        ShareToken t = new ShareToken();
        t.setId(3L);
        t.setKind("CHANNEL");
        t.setOwnerUserId(9L); // 属于他人
        when(mapper.findById(3L)).thenReturn(t);
        assertEquals(403, assertThrows(BizException.class, () -> service.revoke(OPERATOR, 3L)).getCode());
        verify(mapper, never()).revoke(any());
    }

    @Test
    void revokeChannel_byOwner_ok() {
        ShareToken t = new ShareToken();
        t.setId(3L);
        t.setKind("CHANNEL");
        t.setOwnerUserId(5L);
        when(mapper.findById(3L)).thenReturn(t);
        service.revoke(OPERATOR, 3L);
        verify(mapper).revoke(3L);
    }

    @Test
    void revoke_byAdmin_ok() {
        ShareToken t = new ShareToken();
        t.setId(3L);
        t.setKind("CHANNEL");
        t.setOwnerUserId(9L);
        when(mapper.findById(3L)).thenReturn(t);
        service.revoke(ADMIN, 3L);
        verify(mapper).revoke(3L);
    }
}
