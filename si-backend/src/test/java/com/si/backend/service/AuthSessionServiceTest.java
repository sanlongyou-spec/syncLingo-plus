package com.si.backend.service;

import com.si.backend.common.BizException;
import com.si.backend.config.JwtProperties;
import com.si.backend.entity.AuthSession;
import com.si.backend.entity.SiUser;
import com.si.backend.mapper.AuthSessionMapper;
import com.si.backend.mapper.UserMapper;
import com.si.backend.util.JwtUtil;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthSessionServiceTest {

    private static final String SECRET = "unit-test-secret-key-0123456789-abcdefgh";

    private final AuthSessionMapper authSessionMapper = mock(AuthSessionMapper.class);
    private final UserMapper userMapper = mock(UserMapper.class);
    private final AuthSessionService service = new AuthSessionService(authSessionMapper, userMapper, jwtProps());

    @Test
    void issueForUser_storesHashedRefreshTokenAndReturnsAccessToken() {
        SiUser user = user(9L, "alice", "ACTIVE", 2);

        AuthSessionService.IssuedAuth issued = service.issueForUser(user);

        ArgumentCaptor<AuthSession> captor = ArgumentCaptor.forClass(AuthSession.class);
        verify(authSessionMapper).insert(captor.capture());
        AuthSession row = captor.getValue();
        assertEquals(9L, row.getUserId());
        assertEquals(AuthSessionService.STATUS_ACTIVE, row.getStatus());
        assertNotEquals(issued.refreshToken(), row.getRefreshTokenHash());
        assertEquals(hash(issued.refreshToken()), row.getRefreshTokenHash());

        JwtUtil.TokenClaims claims = JwtUtil.verifyAndParseClaims(issued.response().getToken(), SECRET);
        assertNotNull(claims);
        assertEquals(9L, claims.userId());
        assertEquals(2, claims.tokenVersion());
    }

    @Test
    void refresh_rotatesTokenAndKeepsFamily() {
        String raw = "refresh-token";
        String currentHash = hash(raw);
        AuthSession current = activeSession(9L, "family-1", currentHash);
        when(authSessionMapper.findByRefreshTokenHash(currentHash)).thenReturn(current);
        when(userMapper.findById(9L)).thenReturn(user(9L, "alice", "ACTIVE", 0));
        when(authSessionMapper.markRotated(10L)).thenReturn(1);

        AuthSessionService.IssuedAuth issued = service.refresh(raw);

        verify(authSessionMapper).markRotated(10L);
        ArgumentCaptor<AuthSession> captor = ArgumentCaptor.forClass(AuthSession.class);
        verify(authSessionMapper).insert(captor.capture());
        AuthSession next = captor.getValue();
        assertEquals("family-1", next.getFamilyId());
        assertEquals(currentHash, next.getRotatedFromHash());
        assertNotEquals(currentHash, next.getRefreshTokenHash());
        assertFalse(issued.refreshToken().isBlank());
        assertEquals(hash(issued.refreshToken()), next.getRefreshTokenHash());
    }

    @Test
    void reusedRotatedToken_revokesFamilyAndRejects() {
        String raw = "old-refresh-token";
        String refreshHash = hash(raw);
        AuthSession rotated = activeSession(9L, "family-2", refreshHash);
        rotated.setStatus(AuthSessionService.STATUS_ROTATED);
        when(authSessionMapper.findByRefreshTokenHash(refreshHash)).thenReturn(rotated);

        BizException ex = assertThrows(BizException.class, () -> service.refresh(raw));

        assertEquals(401, ex.getCode());
        verify(authSessionMapper).revokeFamily("family-2");
    }

    @Test
    void expiredRefresh_revokesFamilyAndRejects() {
        String raw = "expired-refresh";
        String refreshHash = hash(raw);
        AuthSession expired = activeSession(9L, "family-3", refreshHash);
        expired.setIdleExpiresAt(LocalDateTime.now().minusSeconds(1));
        when(authSessionMapper.findByRefreshTokenHash(refreshHash)).thenReturn(expired);

        BizException ex = assertThrows(BizException.class, () -> service.refresh(raw));

        assertEquals(401, ex.getCode());
        verify(authSessionMapper).revokeFamily("family-3");
    }

    private JwtProperties jwtProps() {
        JwtProperties p = new JwtProperties();
        p.setSecret(SECRET);
        p.setExpirationMs(60_000L);
        return p;
    }

    private SiUser user(Long id, String username, String status, int tokenVersion) {
        SiUser user = new SiUser();
        user.setId(id);
        user.setUsername(username);
        user.setStatus(status);
        user.setTokenVersion(tokenVersion);
        return user;
    }

    private AuthSession activeSession(Long userId, String familyId, String refreshHash) {
        AuthSession session = new AuthSession();
        session.setId(10L);
        session.setUserId(userId);
        session.setFamilyId(familyId);
        session.setSessionId("session-1");
        session.setRefreshTokenHash(refreshHash);
        session.setStatus(AuthSessionService.STATUS_ACTIVE);
        session.setAbsoluteExpiresAt(LocalDateTime.now().plusHours(1));
        session.setIdleExpiresAt(LocalDateTime.now().plusHours(1));
        session.setLastSeenAt(LocalDateTime.now());
        return session;
    }

    private String hash(String raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(md.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
