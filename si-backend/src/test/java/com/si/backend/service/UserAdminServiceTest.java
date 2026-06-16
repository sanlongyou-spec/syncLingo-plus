package com.si.backend.service;

import com.si.backend.common.BizException;
import com.si.backend.dto.CreateUserRequest;
import com.si.backend.entity.SiUser;
import com.si.backend.mapper.UserMapper;
import com.si.backend.vo.UserSummaryVo;
import com.si.backend.ws.UserWebSocketRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P1 用户管理:建号校验、最后一个管理员保护、状态/角色校验、密码不外泄。
 */
class UserAdminServiceTest {

    private final UserMapper userMapper = mock(UserMapper.class);
    private final AuditService auditService = mock(AuditService.class);
    private final UserWebSocketRegistry userWebSocketRegistry = mock(UserWebSocketRegistry.class);
    private final UserAdminService service = new UserAdminService(userMapper, auditService, userWebSocketRegistry);

    private SiUser user(Long id, String role, String status) {
        SiUser u = new SiUser();
        u.setId(id);
        u.setUsername("u" + id);
        u.setPassword("$2a$hash");
        u.setRole(role);
        u.setStatus(status);
        return u;
    }

    @Test
    void create_hashesPassword_andReturnsVoWithoutPassword() {
        CreateUserRequest req = new CreateUserRequest();
        req.setUsername("newuser");
        req.setPassword("secret123");
        req.setRole("OPERATOR");
        when(userMapper.findByUsername("newuser")).thenReturn(null);
        doAnswer(inv -> { inv.getArgument(0, SiUser.class).setId(10L); return 1; }).when(userMapper).insert(any());
        when(userMapper.findById(10L)).thenReturn(user(10L, "OPERATOR", "ACTIVE"));

        UserSummaryVo vo = service.create(req);

        assertEquals("OPERATOR", vo.getRole());
        // VO 不含 password 字段(编译期保证),这里确认插入的是 BCrypt 哈希而非明文
        verify(userMapper).insert(org.mockito.ArgumentMatchers.argThat(u ->
                u.getPassword() != null && u.getPassword().startsWith("$2") && !u.getPassword().equals("secret123")));
    }

    @Test
    void create_rejectsInvalidRole() {
        CreateUserRequest req = new CreateUserRequest();
        req.setUsername("x");
        req.setPassword("secret123");
        req.setRole("superuser");
        assertEquals(400, assertThrows(BizException.class, () -> service.create(req)).getCode());
    }

    @Test
    void create_rejectsDuplicateUsername() {
        CreateUserRequest req = new CreateUserRequest();
        req.setUsername("dup");
        req.setPassword("secret123");
        req.setRole("VIEWER");
        when(userMapper.findByUsername("dup")).thenReturn(user(1L, "VIEWER", "ACTIVE"));
        assertEquals(400, assertThrows(BizException.class, () -> service.create(req)).getCode());
    }

    @Test
    void updateRole_demotingLastAdmin_isRejected() {
        when(userMapper.findById(1L)).thenReturn(user(1L, "ADMIN", "ACTIVE"));
        when(userMapper.countActiveAdmins()).thenReturn(1);
        assertEquals(400, assertThrows(BizException.class, () -> service.updateRole(1L, "OPERATOR")).getCode());
        verify(userMapper, never()).updateRole(anyLong(), anyString());
    }

    @Test
    void updateRole_demotingWhenOtherAdminsExist_isAllowed() {
        when(userMapper.findById(1L)).thenReturn(user(1L, "ADMIN", "ACTIVE"));
        when(userMapper.countActiveAdmins()).thenReturn(2);
        when(userMapper.findById(1L)).thenReturn(user(1L, "ADMIN", "ACTIVE"), user(1L, "OPERATOR", "ACTIVE"));
        service.updateRole(1L, "OPERATOR");
        verify(userMapper).updateRole(1L, "OPERATOR");
        verify(userMapper).incrementTokenVersion(1L);
        verify(userWebSocketRegistry).closeUser(1L);
    }

    @Test
    void updateStatus_disablingLastAdmin_isRejected() {
        when(userMapper.findById(1L)).thenReturn(user(1L, "ADMIN", "ACTIVE"));
        when(userMapper.countActiveAdmins()).thenReturn(1);
        assertEquals(400, assertThrows(BizException.class, () -> service.updateStatus(1L, "DISABLED")).getCode());
        verify(userMapper, never()).updateStatus(anyLong(), anyString());
    }

    @Test
    void updateStatus_rejectsInvalidStatus() {
        when(userMapper.findById(1L)).thenReturn(user(1L, "OPERATOR", "ACTIVE"));
        assertEquals(400, assertThrows(BizException.class, () -> service.updateStatus(1L, "PAUSED")).getCode());
    }

    @Test
    void resetPassword_rejectsShortPassword() {
        when(userMapper.findById(1L)).thenReturn(user(1L, "VIEWER", "ACTIVE"));
        assertEquals(400, assertThrows(BizException.class, () -> service.resetPassword(1L, "123")).getCode());
        verify(userMapper, never()).updatePassword(anyLong(), anyString());
    }

    @Test
    void updateStatus_disablingUser_bumpsTokenAndClosesRealtimeConnections() {
        when(userMapper.findById(2L)).thenReturn(user(2L, "OPERATOR", "ACTIVE"), user(2L, "OPERATOR", "DISABLED"));
        service.updateStatus(2L, "DISABLED");
        verify(userMapper).incrementTokenVersion(2L);
        verify(userWebSocketRegistry).closeUser(2L);
    }

    @Test
    void updateRole_unknownUser_404() {
        when(userMapper.findById(99L)).thenReturn(null);
        assertEquals(404, assertThrows(BizException.class, () -> service.updateRole(99L, "ADMIN")).getCode());
    }
}
