package com.si.backend.service;

import com.si.backend.entity.AuditLog;
import com.si.backend.entity.AuditOutbox;
import com.si.backend.mapper.AuditLogMapper;
import com.si.backend.mapper.AuditOutboxMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * P2 审计:写入字段正确、limit 收敛、写失败不抛(best-effort 不阻断业务)。
 */
class AuditServiceTest {

    private final AuditLogMapper mapper = mock(AuditLogMapper.class);
    private final AuditOutboxMapper outboxMapper = mock(AuditOutboxMapper.class);
    private final AuditOutboxDispatcher dispatcher = mock(AuditOutboxDispatcher.class);
    private final AuditService service = new AuditService(mapper, outboxMapper, dispatcher);

    @Test
    void recordActor_writesOutboxWithFields() {
        service.recordActor("USER", "5", "ADMIN", "ROLE_CHANGE", "SUCCESS", "USER", "9", "role=OPERATOR");
        ArgumentCaptor<AuditOutbox> cap = ArgumentCaptor.forClass(AuditOutbox.class);
        verify(outboxMapper).insert(cap.capture());
        AuditOutbox e = cap.getValue();
        assertEquals("ROLE_CHANGE", e.getAction());
        assertEquals("SUCCESS", e.getResult());
        assertEquals("9", e.getResourceId());
        assertEquals("ADMIN", e.getRole());
        verify(dispatcher).dispatchOneBestEffort(e);
    }

    @Test
    void record_derivesAnonymousActorWithoutRequestContext() {
        service.record("LOGIN", "FAIL", "USER", null, "bad credentials");
        ArgumentCaptor<AuditOutbox> cap = ArgumentCaptor.forClass(AuditOutbox.class);
        verify(outboxMapper).insert(cap.capture());
        assertEquals("ANONYMOUS", cap.getValue().getActorType());
    }

    @Test
    void getRecent_clampsLimit() {
        service.getRecent(99999);
        verify(mapper).findRecent(500);
        service.getRecent(0);
        verify(mapper).findRecent(1);
    }

    @Test
    void record_neverThrows_onOutboxFailure() {
        doThrow(new RuntimeException("db down")).when(outboxMapper).insert(any());
        assertDoesNotThrow(() -> service.record("USER_CREATE", "SUCCESS", "USER", "1", "x"));
    }

    @Test
    void recordCritical_throws_onOutboxFailure() {
        doThrow(new RuntimeException("db down")).when(outboxMapper).insert(any());
        assertThrows(RuntimeException.class,
                () -> service.recordCritical("ROLE_CHANGE", "SUCCESS", "USER", "1", "x"));
    }
}
