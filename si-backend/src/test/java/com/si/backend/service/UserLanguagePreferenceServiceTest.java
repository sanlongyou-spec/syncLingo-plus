package com.si.backend.service;

import com.si.backend.common.BizException;
import com.si.backend.common.Constants;
import com.si.backend.dto.SaveUserLanguagePreferenceRequest;
import com.si.backend.entity.UserLanguagePreference;
import com.si.backend.mapper.UserLanguagePreferenceMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserLanguagePreferenceServiceTest {

    private final UserLanguagePreferenceMapper mapper = mock(UserLanguagePreferenceMapper.class);
    private final UserLanguagePreferenceService service = new UserLanguagePreferenceService(mapper);

    @Test
    void resolveRequestedLanguages_allowsSingleOutputLanguage() {
        List<String> result = service.resolveEnabledLanguages(7L, List.of(Constants.LANG_EN_US));

        assertEquals(List.of(Constants.LANG_EN_US), result);
    }

    @Test
    void resolveRequestedLanguages_rejectsEmptyOutputLanguages() {
        BizException ex = assertThrows(
                BizException.class,
                () -> service.resolveEnabledLanguages(7L, List.of())
        );

        assertEquals(400, ex.getCode());
    }

    @Test
    void save_keepsSingleOutputLanguageWithoutAddingDefaults() {
        SaveUserLanguagePreferenceRequest request = new SaveUserLanguagePreferenceRequest();
        request.setDefaultSourceLang(Constants.LANG_AUTO);
        request.setEnabledLanguages(List.of(Constants.LANG_EN_US));

        service.save(7L, request);

        ArgumentCaptor<UserLanguagePreference> captor = ArgumentCaptor.forClass(UserLanguagePreference.class);
        verify(mapper).upsert(captor.capture());
        assertEquals(Constants.LANG_EN_US, captor.getValue().getEnabledLanguages());
    }

    @Test
    void save_rejectsEmptyOutputLanguagesWithoutPersisting() {
        SaveUserLanguagePreferenceRequest request = new SaveUserLanguagePreferenceRequest();
        request.setDefaultSourceLang(Constants.LANG_AUTO);
        request.setEnabledLanguages(List.of());

        BizException ex = assertThrows(BizException.class, () -> service.save(7L, request));

        assertEquals(400, ex.getCode());
        verify(mapper, never()).upsert(any());
    }

    @Test
    void get_keepsLegacyStoredSingleOutputLanguage() {
        UserLanguagePreference preference = new UserLanguagePreference();
        preference.setUserId(7L);
        preference.setDefaultSourceLang(Constants.LANG_AUTO);
        preference.setEnabledLanguages(Constants.LANG_EN_US);
        when(mapper.findByUserId(7L)).thenReturn(preference);

        assertEquals(List.of(Constants.LANG_EN_US), service.get(7L).getEnabledLanguages());
    }
}
