package com.si.backend.service;

import com.si.backend.common.BizException;
import com.si.backend.common.ErrorCode;
import com.si.backend.config.JwtProperties;
import com.si.backend.dto.LoginResponse;
import com.si.backend.entity.AuthSession;
import com.si.backend.entity.SiUser;
import com.si.backend.mapper.AuthSessionMapper;
import com.si.backend.mapper.UserMapper;
import com.si.backend.util.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.UUID;

/**
 * Persistent refresh-token rotation with family reuse detection.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthSessionService {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_ROTATED = "ROTATED";
    public static final String STATUS_REVOKED = "REVOKED";
    public static final long REFRESH_COOKIE_MAX_AGE_SECONDS = 12 * 60 * 60;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final long ABSOLUTE_HOURS = 12;
    private static final long IDLE_HOURS = 8;

    private final AuthSessionMapper authSessionMapper;
    private final UserMapper userMapper;
    private final JwtProperties jwtProperties;

    public IssuedAuth issueForUser(SiUser user) {
        log.info("[AuthSessionService] issueForUser start, userId={}", user.getId());
        String refreshToken = randomToken();
        AuthSession session = newSession(user.getId(), UUID.randomUUID().toString(), null, hash(refreshToken));
        authSessionMapper.insert(session);
        LoginResponse response = new LoginResponse(user.getId(), createAccessToken(user));
        log.info("[AuthSessionService] issueForUser end, userId={}, sessionId={}", user.getId(), session.getSessionId());
        return new IssuedAuth(response, refreshToken);
    }

    public IssuedAuth refresh(String refreshToken) {
        log.info("[AuthSessionService] refresh start");
        String refreshHash = requireHash(refreshToken);
        AuthSession session = authSessionMapper.findByRefreshTokenHash(refreshHash);
        if (session == null) {
            AuthSession rotatedChild = authSessionMapper.findByRotatedFromHash(refreshHash);
            if (rotatedChild != null) {
                authSessionMapper.revokeFamily(rotatedChild.getFamilyId());
                log.warn("[AuthSessionService] refresh reuse detected via rotated_from, familyId={}", rotatedChild.getFamilyId());
            }
            throw unauthorized();
        }
        if (!STATUS_ACTIVE.equals(session.getStatus()) || session.getRevokedAt() != null) {
            authSessionMapper.revokeFamily(session.getFamilyId());
            log.warn("[AuthSessionService] refresh reuse detected, familyId={}, status={}",
                    session.getFamilyId(), session.getStatus());
            throw unauthorized();
        }
        if (isExpired(session)) {
            authSessionMapper.revokeFamily(session.getFamilyId());
            log.warn("[AuthSessionService] refresh expired, familyId={}", session.getFamilyId());
            throw unauthorized();
        }

        SiUser user = userMapper.findById(session.getUserId());
        if (user == null || "DISABLED".equalsIgnoreCase(user.getStatus())) {
            authSessionMapper.revokeFamily(session.getFamilyId());
            throw unauthorized();
        }

        if (authSessionMapper.markRotated(session.getId()) != 1) {
            authSessionMapper.revokeFamily(session.getFamilyId());
            throw unauthorized();
        }
        String nextRefresh = randomToken();
        AuthSession next = newSession(user.getId(), session.getFamilyId(), refreshHash, hash(nextRefresh));
        authSessionMapper.insert(next);

        log.info("[AuthSessionService] refresh end, userId={}, familyId={}", user.getId(), session.getFamilyId());
        return new IssuedAuth(new LoginResponse(user.getId(), createAccessToken(user)), nextRefresh);
    }

    public void logout(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            return;
        }
        String refreshHash = hash(refreshToken);
        AuthSession session = authSessionMapper.findByRefreshTokenHash(refreshHash);
        if (session != null) {
            authSessionMapper.revokeFamily(session.getFamilyId());
            log.info("[AuthSessionService] logout, familyId={}", session.getFamilyId());
        } else {
            authSessionMapper.revokeByRefreshTokenHash(refreshHash);
        }
    }

    private AuthSession newSession(Long userId, String familyId, String rotatedFromHash, String refreshTokenHash) {
        LocalDateTime now = LocalDateTime.now();
        AuthSession session = new AuthSession();
        session.setUserId(userId);
        session.setSessionId(UUID.randomUUID().toString());
        session.setFamilyId(familyId);
        session.setRefreshTokenHash(refreshTokenHash);
        session.setRotatedFromHash(rotatedFromHash);
        session.setStatus(STATUS_ACTIVE);
        session.setAbsoluteExpiresAt(now.plusHours(ABSOLUTE_HOURS));
        session.setIdleExpiresAt(now.plusHours(IDLE_HOURS));
        session.setLastSeenAt(now);
        return session;
    }

    private boolean isExpired(AuthSession session) {
        LocalDateTime now = LocalDateTime.now();
        return session.getAbsoluteExpiresAt() == null
                || session.getIdleExpiresAt() == null
                || !session.getAbsoluteExpiresAt().isAfter(now)
                || !session.getIdleExpiresAt().isAfter(now);
    }

    private String createAccessToken(SiUser user) {
        String secret = jwtProperties.getSecret() == null ? "" : jwtProperties.getSecret();
        int tokenVersion = user.getTokenVersion() == null ? 0 : user.getTokenVersion();
        return JwtUtil.createToken(user.getId(), user.getUsername(), tokenVersion, jwtProperties.getExpirationMs(), secret);
    }

    private String requireHash(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw unauthorized();
        }
        return hash(refreshToken);
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hash(String raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(md.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw BizException.of(ErrorCode.SYSTEM_ERROR, "令牌处理失败");
        }
    }

    private BizException unauthorized() {
        return BizException.of(ErrorCode.UNAUTHORIZED, "Refresh token invalid or expired");
    }

    public record IssuedAuth(LoginResponse response, String refreshToken) {
    }
}
