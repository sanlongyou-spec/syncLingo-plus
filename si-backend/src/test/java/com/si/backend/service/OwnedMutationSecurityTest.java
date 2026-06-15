package com.si.backend.service;

import com.si.backend.common.BizException;
import com.si.backend.entity.AsrHotword;
import com.si.backend.entity.Terminology;
import com.si.backend.mapper.AsrHotwordMapper;
import com.si.backend.mapper.TerminologyMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Verifies user-owned mutations fail closed instead of silently succeeding.
 */
class OwnedMutationSecurityTest {

    @Test
    void terminologyWithoutUserId_neverFallsBackToFixedAccount() {
        TerminologyMapper mapper = mock(TerminologyMapper.class);
        TerminologyService service = new TerminologyService(mapper);
        Terminology terminology = new Terminology();

        BizException error = assertThrows(
                BizException.class,
                () -> service.createTerminology(terminology)
        );

        assertEquals(401, error.getCode());
        verifyNoInteractions(mapper);
    }

    @Test
    void terminologyMutationOutsideOwnership_returnsNotFound() {
        TerminologyMapper mapper = mock(TerminologyMapper.class);
        TerminologyService service = new TerminologyService(mapper);
        when(mapper.update(any(Terminology.class))).thenReturn(0);

        BizException error = assertThrows(
                BizException.class,
                () -> service.updateTerminology(10L, 2L, new Terminology())
        );

        assertEquals(404, error.getCode());
    }

    @Test
    void hotwordMutationOutsideOwnership_returnsNotFound() {
        AsrHotwordMapper mapper = mock(AsrHotwordMapper.class);
        AsrHotwordService service = new AsrHotwordService(mapper);
        when(mapper.update(any(AsrHotword.class))).thenReturn(0);

        BizException error = assertThrows(
                BizException.class,
                () -> service.update(10L, 2L, new AsrHotword())
        );

        assertEquals(404, error.getCode());
    }
}
