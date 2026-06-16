package com.si.backend.service;

import com.si.backend.entity.SiUser;
import com.si.backend.mapper.UserMapper;
import com.si.backend.security.Role;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * P6 Break-glass(离线应急):恢复/创建管理员访问,用于管理员被锁定或首管理员初始化。
 *
 * <p>约束(见方案 §8/§P6):仅服务器本地 CLI 触发,网页不可达;须工单号 + 理由;
 * <b>不提供全库正文访问</b>(仅恢复管理员账号);事务内写关键审计(失败即回滚)+ 独立安全日志。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BreakGlassService {

    /** 独立安全日志通道,部署可单独路由到受控文件。 */
    private static final Logger SECURITY = LoggerFactory.getLogger("SECURITY.BREAKGLASS");
    private static final int MIN_PASSWORD_LENGTH = 6;
    private static final String ACTIVE = "ACTIVE";

    private final UserMapper userMapper;
    private final AuditService auditService;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    /**
     * 恢复或创建一个有效的管理员账号。
     *
     * @param username        目标用户名
     * @param ticket          工单号(必填)
     * @param reason          操作理由(必填)
     * @param newPassword     新密码;恢复既有账号时可为空(不改密),创建新账号时必填
     * @return 人类可读的结果摘要
     */
    @Transactional
    public String restoreAdmin(String username, String ticket, String reason, String newPassword) {
        String user = trim(username);
        require(!user.isEmpty(), "username 不能为空");
        require(!trim(ticket).isEmpty(), "ticket(工单号)必填");
        require(!trim(reason).isEmpty(), "reason(理由)必填");

        SiUser existing = userMapper.findByUsername(user);
        boolean created;
        Long userId;
        if (existing == null) {
            require(isValidPassword(newPassword), "创建新管理员需提供至少 " + MIN_PASSWORD_LENGTH + " 位的 --password");
            SiUser fresh = new SiUser();
            fresh.setUsername(user);
            fresh.setPassword(passwordEncoder.encode(newPassword));
            fresh.setNickname("break-glass admin");
            fresh.setRole(Role.ADMIN.name());
            userMapper.insert(fresh);
            userId = fresh.getId();
            created = true;
        } else {
            userId = existing.getId();
            userMapper.updateRole(userId, Role.ADMIN.name());
            userMapper.updateStatus(userId, ACTIVE);
            if (newPassword != null && !newPassword.isEmpty()) {
                require(isValidPassword(newPassword), "新密码至少 " + MIN_PASSWORD_LENGTH + " 位");
                userMapper.updatePassword(userId, passwordEncoder.encode(newPassword));
            }
            // 作废其既有令牌,确保此前可能泄露的会话失效。
            userMapper.incrementTokenVersion(userId);
            created = false;
        }

        String detail = "ticket=" + trim(ticket) + "; reason=" + trim(reason) + "; op=" + (created ? "create" : "restore");
        // 关键审计:写失败则抛异常,@Transactional 回滚整个恢复操作(不留无审计的提权)。
        auditService.recordActorCritical("SYSTEM", "break-glass-cli", Role.ADMIN.name(),
                "BREAK_GLASS", "SUCCESS", "USER", String.valueOf(userId), detail);
        SECURITY.warn("[BREAK_GLASS] admin access {} for username={}, userId={}, {}",
                created ? "CREATED" : "RESTORED", user, userId, detail);
        log.warn("[BreakGlassService] break-glass {} username={}, userId={}", created ? "create" : "restore", user, userId);

        return String.format("Break-glass %s admin '%s' (id=%d). %s",
                created ? "CREATED" : "RESTORED", user, userId, detail);
    }

    private boolean isValidPassword(String pwd) {
        return pwd != null && pwd.length() >= MIN_PASSWORD_LENGTH;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }
}
