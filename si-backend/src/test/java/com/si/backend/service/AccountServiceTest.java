package com.si.backend.service;

import com.si.backend.common.BizException;
import com.si.backend.entity.SiUser;
import com.si.backend.mapper.UserMapper;
import com.si.backend.ws.UserWebSocketRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P5 账号自助:改密校验当前密码/新密码强度/重复;成功后作废旧令牌;全设备登出自增版本。
 */
class AccountServiceTest {

    private final UserMapper userMapper = mock(UserMapper.class);
    private final AuditService auditService = mock(AuditService.class);
    private final UserWebSocketRegistry userWebSocketRegistry = mock(UserWebSocketRegistry.class);
    private final AccountService service = new AccountService(userMapper, auditService, userWebSocketRegistry);

    private SiUser userWith(String rawPassword) {
        SiUser u = new SiUser();
        u.setId(9L);
        u.setRole("OPERATOR");
        u.setPassword(new BCryptPasswordEncoder().encode(rawPassword));
        return u;
    }

    @Test
    void changePassword_wrongCurrent_rejected_andNoUpdate() {
        when(userMapper.findById(9L)).thenReturn(userWith("correct-pass"));
        BizException ex = assertThrows(BizException.class,
                () -> service.changeOwnPassword(9L, "wrong", "new-strong"));
        assertEquals(400, ex.getCode());
        verify(userMapper, never()).updatePassword(anyLong(), anyString());
        verify(userMapper, never()).incrementTokenVersion(anyLong());
    }

    @Test
    void changePassword_weakNew_rejected() {
        when(userMapper.findById(9L)).thenReturn(userWith("correct-pass"));
        assertEquals(400, assertThrows(BizException.class,
                () -> service.changeOwnPassword(9L, "correct-pass", "123")).getCode());
    }

    @Test
    void changePassword_sameAsCurrent_rejected() {
        when(userMapper.findById(9L)).thenReturn(userWith("correct-pass"));
        assertEquals(400, assertThrows(BizException.class,
                () -> service.changeOwnPassword(9L, "correct-pass", "correct-pass")).getCode());
    }

    @Test
    void changePassword_success_updatesAndBumpsTokenVersion() {
        when(userMapper.findById(9L)).thenReturn(userWith("correct-pass"));
        service.changeOwnPassword(9L, "correct-pass", "brand-new-pass");
        verify(userMapper).updatePassword(eq(9L), anyString());
        verify(userMapper).incrementTokenVersion(9L);
        verify(userWebSocketRegistry).closeUser(9L);
    }

    @Test
    void changePassword_unknownUser_unauthorized() {
        when(userMapper.findById(9L)).thenReturn(null);
        assertEquals(401, assertThrows(BizException.class,
                () -> service.changeOwnPassword(9L, "x", "yyyyyy")).getCode());
    }

    @Test
    void logoutAll_bumpsTokenVersion() {
        when(userMapper.findById(9L)).thenReturn(userWith("p"));
        service.logoutAll(9L);
        verify(userMapper).incrementTokenVersion(9L);
        verify(userWebSocketRegistry).closeUser(9L);
    }
}
