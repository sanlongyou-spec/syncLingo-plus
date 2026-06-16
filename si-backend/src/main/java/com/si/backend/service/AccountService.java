package com.si.backend.service;

import com.si.backend.common.BizException;
import com.si.backend.common.ErrorCode;
import com.si.backend.entity.SiUser;
import com.si.backend.mapper.UserMapper;
import com.si.backend.ws.UserWebSocketRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * P5 账号自助安全操作:改密、全设备登出。均自增 token_version 使旧令牌即时失效。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountService {

    private static final int MIN_PASSWORD_LENGTH = 6;

    private final UserMapper userMapper;
    private final AuditService auditService;
    private final UserWebSocketRegistry userWebSocketRegistry;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    /** 自助改密:校验当前密码,设置新密码,作废本人所有旧令牌(强制重新登录)。 */
    public void changeOwnPassword(Long userId, String currentPassword, String newPassword) {
        log.info("[AccountService] changeOwnPassword start, userId={}", userId);
        SiUser user = userMapper.findById(userId);
        if (user == null) {
            // 已认证却查不到用户,按未授权处理而非暴露细节。
            throw BizException.of(ErrorCode.UNAUTHORIZED);
        }
        if (currentPassword == null || !passwordEncoder.matches(currentPassword, user.getPassword())) {
            auditService.recordActor("USER", String.valueOf(userId), user.getRole(),
                    "PASSWORD_CHANGE", "FAIL", "USER", String.valueOf(userId), "wrong current password");
            throw BizException.of(ErrorCode.BAD_REQUEST, "当前密码不正确");
        }
        if (newPassword == null || newPassword.length() < MIN_PASSWORD_LENGTH) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "新密码至少 " + MIN_PASSWORD_LENGTH + " 位");
        }
        if (passwordEncoder.matches(newPassword, user.getPassword())) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "新密码不能与当前密码相同");
        }
        userMapper.updatePassword(userId, passwordEncoder.encode(newPassword));
        userMapper.incrementTokenVersion(userId);
        userWebSocketRegistry.closeUser(userId);
        auditService.recordActor("USER", String.valueOf(userId), user.getRole(),
                "PASSWORD_CHANGE", "SUCCESS", "USER", String.valueOf(userId), "self change password");
        log.info("[AccountService] changeOwnPassword end, userId={}", userId);
    }

    /** 全设备登出:自增 token_version,作废本人所有已签发令牌。 */
    public void logoutAll(Long userId) {
        log.info("[AccountService] logoutAll, userId={}", userId);
        SiUser user = userMapper.findById(userId);
        if (user == null) {
            throw BizException.of(ErrorCode.UNAUTHORIZED);
        }
        userMapper.incrementTokenVersion(userId);
        userWebSocketRegistry.closeUser(userId);
        auditService.recordActor("USER", String.valueOf(userId), user.getRole(),
                "LOGOUT_ALL", "SUCCESS", "USER", String.valueOf(userId), null);
    }
}
