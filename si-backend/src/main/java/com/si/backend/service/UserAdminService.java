package com.si.backend.service;

import com.si.backend.common.BizException;
import com.si.backend.common.ErrorCode;
import com.si.backend.dto.CreateUserRequest;
import com.si.backend.entity.SiUser;
import com.si.backend.mapper.UserMapper;
import com.si.backend.security.Role;
import com.si.backend.vo.UserSummaryVo;
import com.si.backend.ws.UserWebSocketRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

/**
 * P1 用户管理(仅 ADMIN 可调用,鉴权在控制器 requireAdmin 即时强制)。
 * 含"最后一个管理员"保护:不能停用或降级系统中最后一个有效管理员。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserAdminService {

    private static final Set<String> VALID_STATUS = Set.of("ACTIVE", "PENDING", "DISABLED");

    private final UserMapper userMapper;
    private final AuditService auditService;
    private final UserWebSocketRegistry userWebSocketRegistry;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public List<UserSummaryVo> list() {
        return userMapper.findAll().stream().map(this::toVo).toList();
    }

    /** 当前用户本人资料(/api/users/me)。 */
    public UserSummaryVo getById(Long id) {
        return toVo(requireUser(id));
    }

    @Transactional
    public UserSummaryVo create(CreateUserRequest req) {
        String username = req.getUsername() == null ? "" : req.getUsername().trim();
        if (username.isBlank() || username.length() > 50) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "用户名不合法");
        }
        if (req.getPassword() == null || req.getPassword().length() < 6) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "密码至少 6 位");
        }
        Role role = requireValidRole(req.getRole());
        if (userMapper.findByUsername(username) != null) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "用户名已存在");
        }
        SiUser user = new SiUser();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(req.getPassword()));
        user.setNickname(req.getNickname());
        user.setEmail(req.getEmail());
        user.setRole(role.name());
        userMapper.insert(user);
        log.info("[UserAdminService] create user, id={}, username={}, role={}", user.getId(), username, role);
        auditService.recordCritical("USER_CREATE", "SUCCESS", "USER", String.valueOf(user.getId()), "username=" + username + ", role=" + role);
        return toVo(userMapper.findById(user.getId()));
    }

    @Transactional
    public UserSummaryVo updateRole(Long id, String roleRaw) {
        SiUser target = requireUser(id);
        Role newRole = requireValidRole(roleRaw);
        // 最后一个管理员保护:降级唯一有效管理员被拒
        if (isActiveAdmin(target) && newRole != Role.ADMIN && userMapper.countActiveAdmins() <= 1) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "不能降级系统中最后一个有效管理员");
        }
        userMapper.updateRole(id, newRole.name());
        userMapper.incrementTokenVersion(id);
        userWebSocketRegistry.closeUser(id);
        log.info("[UserAdminService] updateRole, id={}, role={}", id, newRole);
        auditService.recordCritical("ROLE_CHANGE", "SUCCESS", "USER", String.valueOf(id), "role=" + newRole);
        return toVo(userMapper.findById(id));
    }

    @Transactional
    public UserSummaryVo updateStatus(Long id, String statusRaw) {
        SiUser target = requireUser(id);
        String status = statusRaw == null ? "" : statusRaw.trim().toUpperCase();
        if (!VALID_STATUS.contains(status)) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "状态不合法");
        }
        if (isActiveAdmin(target) && "DISABLED".equals(status) && userMapper.countActiveAdmins() <= 1) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "不能停用系统中最后一个有效管理员");
        }
        userMapper.updateStatus(id, status);
        // P5:停用即作废其既有令牌(WS/HTTP 立即失效),不止依赖状态判断。
        if ("DISABLED".equals(status)) {
            userMapper.incrementTokenVersion(id);
            userWebSocketRegistry.closeUser(id);
        }
        log.info("[UserAdminService] updateStatus, id={}, status={}", id, status);
        auditService.recordCritical("STATUS_CHANGE", "SUCCESS", "USER", String.valueOf(id), "status=" + status);
        return toVo(userMapper.findById(id));
    }

    @Transactional
    public void resetPassword(Long id, String newPassword) {
        requireUser(id);
        if (newPassword == null || newPassword.length() < 6) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "密码至少 6 位");
        }
        userMapper.updatePassword(id, passwordEncoder.encode(newPassword));
        // P5:改密后令旧令牌即时失效,强制重新登录。
        userMapper.incrementTokenVersion(id);
        userWebSocketRegistry.closeUser(id);
        log.info("[UserAdminService] resetPassword, id={}", id);
        auditService.recordCritical("PASSWORD_RESET", "SUCCESS", "USER", String.valueOf(id), "admin reset password");
    }

    private SiUser requireUser(Long id) {
        SiUser u = userMapper.findById(id);
        if (u == null) {
            throw BizException.of(ErrorCode.NOT_FOUND, "用户不存在");
        }
        return u;
    }

    private Role requireValidRole(String raw) {
        Role role = Role.from(raw);
        if (role == null) {
            throw BizException.of(ErrorCode.BAD_REQUEST, "角色不合法(仅 ADMIN/OPERATOR/VIEWER)");
        }
        return role;
    }

    private boolean isActiveAdmin(SiUser u) {
        return Role.ADMIN == Role.from(u.getRole()) && !"DISABLED".equalsIgnoreCase(u.getStatus());
    }

    public UserSummaryVo toVo(SiUser u) {
        return UserSummaryVo.builder()
                .id(u.getId())
                .username(u.getUsername())
                .nickname(u.getNickname())
                .email(u.getEmail())
                .role(u.getRole())
                .status(u.getStatus() == null ? "ACTIVE" : u.getStatus())
                .createTime(u.getCreateTime() == null ? null : u.getCreateTime().toString())
                .build();
    }
}
