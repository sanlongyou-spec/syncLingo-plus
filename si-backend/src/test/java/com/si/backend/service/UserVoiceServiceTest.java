package com.si.backend.service;

import com.si.backend.common.BizException;
import com.si.backend.entity.UserVoice;
import com.si.backend.integration.CartesiaVoiceCloneIntegration;
import com.si.backend.mapper.UserVoiceMapper;
import com.si.backend.security.AuthenticatedActor;
import com.si.backend.vo.CloneVoiceResponse;
import com.si.backend.vo.UserVoiceVo;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies cloned voices are owned by the operator account and can be selected later.
 */
class UserVoiceServiceTest {

    private final UserVoiceMapper mapper = mock(UserVoiceMapper.class);
    private final CartesiaVoiceCloneIntegration integration = mock(CartesiaVoiceCloneIntegration.class);
    private final UserVoiceService service = new UserVoiceService(mapper, integration);

    @Test
    void listVoicesReturnsAllUsableVoicesCreatedByActor() {
        UserVoice first = voice(7L, "voice-1", "张三");
        UserVoice second = voice(7L, "voice-2", "李四");
        when(mapper.findEnabledByUserId(7L)).thenReturn(List.of(first, second));

        List<UserVoiceVo> result = service.listVoices(new AuthenticatedActor(7L));

        assertEquals(List.of("voice-1", "voice-2"),
                result.stream().map(UserVoiceVo::getVoiceId).toList());
    }

    @Test
    void cloneVoiceCallsCartesiaAndPersistsVoiceForActor() throws Exception {
        MockMultipartFile audio = new MockMultipartFile(
                "file", "sample.webm", "audio/webm", new byte[16_000]);
        when(integration.cloneVoice(eq("王五"), eq("zh"), any(), eq("sample.webm"), eq("audio/webm")))
                .thenReturn(new CartesiaVoiceCloneIntegration.CloneResult("voice-created", "{}"));

        CloneVoiceResponse response = service.cloneVoice(new AuthenticatedActor(7L), audio, "王五", "zh", 3);

        assertEquals("voice-created", response.getVoiceId());
        assertEquals("王五", response.getVoiceName());
        verify(mapper).insert(any(UserVoice.class));
    }

    @Test
    void requireUsableVoiceRejectsVoiceNotOwnedByActor() {
        when(mapper.findByUserIdAndVoiceId(7L, "other-voice")).thenReturn(null);

        BizException error = assertThrows(BizException.class,
                () -> service.requireUsableVoice(7L, "other-voice"));

        assertEquals(403, error.getCode());
    }

    private UserVoice voice(Long userId, String voiceId, String name) {
        UserVoice voice = new UserVoice();
        voice.setUserId(userId);
        voice.setVoiceId(voiceId);
        voice.setVoiceName(name);
        voice.setAuthorized(true);
        voice.setDisabled(false);
        voice.setScope("SELF");
        return voice;
    }
}
