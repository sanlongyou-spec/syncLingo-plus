package com.si.backend.service;

import com.si.backend.common.BizException;
import com.si.backend.common.ErrorCode;
import com.si.backend.entity.ShareToken;
import com.si.backend.mapper.ShareTokenMapper;
import com.si.backend.security.AuthenticatedActor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;

/**
 * P4 分享能力令牌:不可枚举、可撤销。SESSION 绑定单会话;CHANNEL 绑定操作员→其当前活动会话。
 * 原始令牌仅签发时返回一次,库内只存 SHA-256 哈希。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShareTokenService {

    public static final String KIND_SESSION = "SESSION";
    public static final String KIND_CHANNEL = "CHANNEL";

    private final ShareTokenMapper shareTokenMapper;
    private final ResourceOwnershipPolicy resourceOwnershipPolicy;
    private final InterpretationSessionService sessionService;
    private final AuditService auditService;

    private static final SecureRandom RANDOM = new SecureRandom();

    /** 共享链接有效期（小时）：所有链接自签发起仅在此时长内有效。 */
    @org.springframework.beans.factory.annotation.Value("${share.token-validity-hours:36}")
    private long tokenValidityHours = 36;

    /** 操作员为自己拥有的会话签发单会话分享令牌。返回原始令牌(仅此一次)。 */
    public Issued mintSessionToken(AuthenticatedActor actor, String sessionId) {
        resourceOwnershipPolicy.requireOwnedSession(actor, sessionId); // 必须拥有该会话
        String raw = randomToken();
        ShareToken token = new ShareToken();
        token.setTokenHash(hash(raw));
        token.setKind(KIND_SESSION);
        token.setSessionId(sessionId);
        token.setExpiresAt(LocalDateTime.now().plusHours(tokenValidityHours)); // 36 小时后失效
        shareTokenMapper.insert(token);
        auditService.record("SHARE_TOKEN_MINT", "SUCCESS", "SESSION", sessionId, "kind=SESSION");
        return new Issued(token.getId(), raw, KIND_SESSION);
    }

    /** 操作员签发频道令牌(长期、指向其当前活动会话),可旋转/撤销。 */
    public Issued mintChannelToken(AuthenticatedActor actor) {
        requireActor(actor);
        String raw = randomToken();
        ShareToken token = new ShareToken();
        token.setTokenHash(hash(raw));
        token.setKind(KIND_CHANNEL);
        token.setOwnerUserId(actor.userId());
        token.setExpiresAt(LocalDateTime.now().plusHours(tokenValidityHours)); // 36 小时后失效
        shareTokenMapper.insert(token);
        auditService.record("SHARE_TOKEN_MINT", "SUCCESS", "CHANNEL", String.valueOf(actor.userId()), "kind=CHANNEL");
        return new Issued(token.getId(), raw, KIND_CHANNEL);
    }

    /**
     * 匿名解析:原始令牌 → 当前可收听的 sessionId。无效/撤销/过期 → 401;频道无活动会话 → 返回 null(前端提示暂无直播)。
     */
    public String resolveSessionId(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw BizException.of(ErrorCode.UNAUTHORIZED, "无效分享令牌");
        }
        ShareToken token = shareTokenMapper.findByHash(hash(rawToken));
        if (token == null || token.getRevokedAt() != null
                || (token.getExpiresAt() != null && token.getExpiresAt().isBefore(LocalDateTime.now()))) {
            throw BizException.of(ErrorCode.UNAUTHORIZED, "无效或已失效的分享令牌");
        }
        if (KIND_SESSION.equals(token.getKind())) {
            return token.getSessionId();
        }
        // CHANNEL:解析操作员当前活动会话
        return sessionService.getActiveSessionForUser(token.getOwnerUserId())
                .map(com.si.backend.entity.InterpretationSession::getSessionId)
                .orElse(null);
    }

    public void revoke(AuthenticatedActor actor, Long tokenId) {
        requireActor(actor);
        ShareToken token = tokenId == null ? null : shareTokenMapper.findById(tokenId);
        if (token == null) {
            throw BizException.of(ErrorCode.NOT_FOUND, "分享令牌不存在");
        }
        boolean allowed = actor.isAdmin();
        if (!allowed && KIND_CHANNEL.equals(token.getKind())) {
            allowed = actor.userId().equals(token.getOwnerUserId());
        }
        if (!allowed && KIND_SESSION.equals(token.getKind())) {
            resourceOwnershipPolicy.requireOwnedSession(actor, token.getSessionId()); // 抛错即无权
            allowed = true;
        }
        if (!allowed) {
            throw BizException.of(ErrorCode.FORBIDDEN, "无权撤销该分享令牌");
        }
        shareTokenMapper.revoke(tokenId);
        auditService.record("SHARE_TOKEN_REVOKE", "SUCCESS", token.getKind(),
                KIND_SESSION.equals(token.getKind()) ? token.getSessionId() : String.valueOf(token.getOwnerUserId()),
                "tokenId=" + tokenId);
    }

    /** 列出当前用户自己的频道令牌(不含原始令牌)。 */
    public List<ShareToken> listOwnChannelTokens(AuthenticatedActor actor) {
        requireActor(actor);
        return shareTokenMapper.findByOwner(actor.userId());
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

    private void requireActor(AuthenticatedActor actor) {
        if (actor == null || actor.userId() == null) {
            throw BizException.of(ErrorCode.UNAUTHORIZED, "Unauthenticated");
        }
    }

    public record Issued(Long id, String token, String kind) {
    }
}
