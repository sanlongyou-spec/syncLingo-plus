package com.si.backend.facade;

import com.si.backend.service.TranslationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 翻译门面层，聚合 TranslationService，统一对外提供翻译能力。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TranslateFacade {

    private final TranslationService translationService;

    /**
     * 单条文本翻译。
     *
     * @param text       原文
     * @param sourceLang 源语言
     * @param targetLang 目标语言
     * @return 译文
     */
    public String translate(String text, String sourceLang, String targetLang, Long userId) {
        log.info("[TranslateFacade] translate start, textLen={}, sourceLang={}, targetLang={}",
                text != null ? text.length() : 0, sourceLang, targetLang);
        String result = translationService.translate(text, sourceLang, targetLang, userId);
        log.info("[TranslateFacade] translate end, sourceLang={}, targetLang={}, resultLen={}",
                sourceLang, targetLang, result != null ? result.length() : 0);
        return result;
    }

    /**
     * 批量文本翻译。
     *
     * @param texts      原文列表
     * @param sourceLang 源语言
     * @param targetLang 目标语言
     * @return 译文列表
     */
    public List<String> translateBatch(List<String> texts, String sourceLang, String targetLang) {
        return translateBatch(texts, sourceLang, targetLang, 1L);
    }

    public List<String> translateBatch(List<String> texts, String sourceLang, String targetLang, Long userId) {
        log.info("[TranslateFacade] translateBatch start, count={}, sourceLang={}, targetLang={}",
                texts != null ? texts.size() : 0, sourceLang, targetLang);
        List<String> results = translationService.translateBatch(texts, sourceLang, targetLang, userId);
        log.info("[TranslateFacade] translateBatch end, count={}, sourceLang={}, targetLang={}",
                results != null ? results.size() : 0, sourceLang, targetLang);
        return results;
    }
}
