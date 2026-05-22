package com.si.backend.service;

import com.si.backend.common.Constants;
import com.si.backend.dto.SaveUserLanguagePreferenceRequest;
import com.si.backend.entity.UserLanguagePreference;
import com.si.backend.mapper.UserLanguagePreferenceMapper;
import com.si.backend.vo.UserLanguagePreferenceVo;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Manages per-user default interpretation language preferences.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserLanguagePreferenceService {

    private static final List<String> DEFAULT_ENABLED_LANGUAGES = List.of(Constants.LANG_ZH_CN, Constants.LANG_ID);

    private final UserLanguagePreferenceMapper preferenceMapper;

    @PostConstruct
    public void initTable() {
        log.info("[UserLanguagePreferenceService] initTable start");
        preferenceMapper.createTableIfNotExists();
        log.info("[UserLanguagePreferenceService] initTable end");
    }

    public UserLanguagePreferenceVo get(Long userId) {
        log.info("[UserLanguagePreferenceService] get start, userId={}", userId);
        UserLanguagePreference preference = preferenceMapper.findByUserId(userId);
        UserLanguagePreferenceVo result = toVo(userId, preference);
        log.info("[UserLanguagePreferenceService] get end, userId={}, enabledCount={}", userId, result.getEnabledLanguages().size());
        return result;
    }

    @Transactional
    public UserLanguagePreferenceVo save(Long userId, SaveUserLanguagePreferenceRequest request) {
        log.info("[UserLanguagePreferenceService] save start, userId={}", userId);
        UserLanguagePreference preference = new UserLanguagePreference();
        preference.setUserId(userId);
        preference.setDefaultSourceLang(normalizeSourceLang(request != null ? request.getDefaultSourceLang() : null));
        preference.setEnabledLanguages(String.join(",", normalizeEnabledLanguages(request != null ? request.getEnabledLanguages() : null)));
        preferenceMapper.upsert(preference);
        UserLanguagePreferenceVo result = get(userId);
        log.info("[UserLanguagePreferenceService] save end, userId={}, enabledCount={}", userId, result.getEnabledLanguages().size());
        return result;
    }

    public List<String> resolveEnabledLanguages(Long userId, List<String> requestedLanguages) {
        if (requestedLanguages != null && !requestedLanguages.isEmpty()) {
            return normalizeEnabledLanguages(requestedLanguages);
        }
        return get(userId).getEnabledLanguages();
    }

    private UserLanguagePreferenceVo toVo(Long userId, UserLanguagePreference preference) {
        if (preference == null) {
            return UserLanguagePreferenceVo.builder()
                    .userId(userId)
                    .defaultSourceLang(Constants.LANG_AUTO)
                    .enabledLanguages(DEFAULT_ENABLED_LANGUAGES)
                    .build();
        }
        return UserLanguagePreferenceVo.builder()
                .userId(userId)
                .defaultSourceLang(normalizeSourceLang(preference.getDefaultSourceLang()))
                .enabledLanguages(normalizeEnabledLanguages(parseLanguages(preference.getEnabledLanguages())))
                .build();
    }

    private String normalizeSourceLang(String sourceLang) {
        if (sourceLang == null || sourceLang.isBlank()) {
            return Constants.LANG_AUTO;
        }
        return normalizeLanguage(sourceLang);
    }

    private List<String> parseLanguages(String enabledLanguages) {
        if (enabledLanguages == null || enabledLanguages.isBlank()) {
            return DEFAULT_ENABLED_LANGUAGES;
        }
        return List.of(enabledLanguages.split(","));
    }

    private List<String> normalizeEnabledLanguages(List<String> enabledLanguages) {
        Set<String> normalized = new LinkedHashSet<>();
        if (enabledLanguages != null) {
            enabledLanguages.stream()
                    .map(this::normalizeLanguage)
                    .filter(value -> value != null && !value.isBlank() && !Constants.LANG_AUTO.equals(value))
                    .forEach(normalized::add);
        }
        if (normalized.size() < 2) {
            normalized.addAll(DEFAULT_ENABLED_LANGUAGES);
        }
        return List.copyOf(normalized);
    }

    private String normalizeLanguage(String lang) {
        if (lang == null || lang.isBlank() || Constants.LANG_AUTO.equalsIgnoreCase(lang)) {
            return Constants.LANG_AUTO;
        }
        String lower = lang.toLowerCase();
        if (lower.startsWith("zh")) return Constants.LANG_ZH_CN;
        if (lower.startsWith("id")) return Constants.LANG_ID;
        if (lower.startsWith(Constants.LANG_EN_SHORT)) return Constants.LANG_EN_US;
        return lang;
    }
}
