package com.si.backend.service;

import com.si.backend.common.BizException;
import com.si.backend.config.CostRatesProperties;
import com.si.backend.entity.InterpretationSession;
import com.si.backend.entity.SiUser;
import com.si.backend.mapper.InterpretationSessionMapper;
import com.si.backend.mapper.UserMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InterpretationSessionServiceSingleActiveTest {

    @Test
    void startSession_rejectsWhenAccountAlreadyHasRunningSession() {
        InterpretationSessionMapper mapper = mock(InterpretationSessionMapper.class);
        UserMapper userMapper = mock(UserMapper.class);
        InterpretationSessionService service = new InterpretationSessionService(
                mapper,
                userMapper,
                mock(CostRatesProperties.class),
                mock(ContentEmbeddingService.class)
        );
        SiUser user = new SiUser();
        user.setId(5L);
        InterpretationSession running = new InterpretationSession();
        running.setSessionId("already-running");
        when(userMapper.findByIdForUpdate(5L)).thenReturn(user);
        when(mapper.findActiveByUserId(5L)).thenReturn(running);

        BizException error = assertThrows(BizException.class, () -> service.startSession(
                "new-session", 5L, "auto", "auto", "title", null, List.of(), List.of("en"), 1L
        ));

        assertEquals(4002, error.getCode());
        verify(mapper, never()).insert(org.mockito.ArgumentMatchers.any());
    }
}
