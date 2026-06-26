package com.si.backend.service;

import com.si.backend.common.BizException;
import com.si.backend.common.Constants;
import com.si.backend.common.ErrorCode;
import com.si.backend.config.OpenAiProperties;
import com.si.backend.integration.GoogleTranslateIntegration;
import com.si.backend.integration.LlmIntegration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Translation business service.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TranslationService {

    private static final String COMPRESSION_DIRECTION_ZH_TO_ID = "zh->id";
    private static final String COMPRESSION_DIRECTION_ZH_TO_EN = "zh->en";

    // ── 印尼语数字格式归一化 ──────────────────────────────────────────────
    // 印尼语用「.」做千分位、「,」做小数点（与中英相反）。直接送翻译会被误读，
    // 出现量级错/丢小数（如 60.390,8 被读成 60390、丢掉 .8）。翻译前仅对印尼语源文本，
    // 把印尼式数字改写成通用写法（「.」小数点、去千分位）。
    /** 印尼式带千分位的数字：1~3 位 + 若干组「.三位」+ 可选「,小数」，如 60.390,80 / 1.000 */
    private static final Pattern INDONESIAN_GROUPED_NUMBER =
            Pattern.compile("\\d{1,3}(?:\\.\\d{3})+(?:,\\d+)?");
    /** 印尼式小数点：数字之间的「,」（如 0,05 / 1,5），转为「.」 */
    private static final Pattern INDONESIAN_DECIMAL_COMMA =
            Pattern.compile("(?<=\\d),(?=\\d)");

    private final GoogleTranslateIntegration translator;
    private final LlmIntegration llmIntegration;
    private final OpenAiProperties openAiProperties;
    private final TerminologyService terminologyService;

    public String detectLanguage(String text) {
        log.info("[TranslationService] detectLanguage start, textLen={}", text != null ? text.length() : 0);
        if (text == null || text.isBlank()) {
            return Constants.LANG_UNDEFINED;
        }
        long start = System.currentTimeMillis();
        String detectedLang = translator.detectLanguage(text);
        log.info("[TranslationService] detectLanguage end, textLen={}, detectedLang={}, costMs={}",
                text.length(), detectedLang, System.currentTimeMillis() - start);
        return detectedLang;
    }

    public String translate(String text, String sourceLang, String targetLang) {
        return translate(text, sourceLang, targetLang, 1L);
    }

    public String translate(String text, String sourceLang, String targetLang, Long userId) {
        return translate(text, sourceLang, targetLang, userId, true);
    }

    /**
     * @param allowCompress 是否允许实时 LLM 压缩。false 时跳过压缩、直接返回 Google 译文(省 ~2s 首音)。
     *                      由调用方按"该语言通道是否有播放积压"决定(见 RealtimeInterpretationFacade 阶段1)。
     */
    public String translate(String text, String sourceLang, String targetLang, Long userId, boolean allowCompress) {
        return translate(text, sourceLang, targetLang, userId, allowCompress, null);
    }

    /**
     * @param recentContext 最近若干句源文本，仅用于 id→zh LLM 纠错翻译的上下文消歧；其它路径忽略。
     */
    public String translate(String text, String sourceLang, String targetLang, Long userId, boolean allowCompress,
                            String recentContext) {
        log.info("[TranslationService] translate start, userId={}, textLen={}, textHash={}, sourceLang={}, targetLang={}, allowCompress={}",
                userId, text != null ? text.length() : 0, diagnosticHash(text), sourceLang, targetLang, allowCompress);
        if (text == null || text.isBlank()) {
            log.info("[TranslationService] translate end, blankInput=true, targetLang={}", targetLang);
            return "";
        }
        validateLanguages(sourceLang, targetLang);

        long start = System.currentTimeMillis();
        String normalizedText = normalizeIndonesianNumbersIfNeeded(text, sourceLang);
        if (!normalizedText.equals(text)) {
            log.info("[TranslationService] indonesian number normalized, userId={}, textHash={}, beforeLen={}, afterLen={}",
                    userId, diagnosticHash(text), text.length(), normalizedText.length());
            text = normalizedText;
        }

        // 印尼语→中文:走 LLM 纠错翻译(ASR 后处理 + 专业翻译)。失败/超时回退下方 Google 路径。
        if (openAiProperties.isIdZhLlmTranslateEnabled()
                && isIndonesianSource(sourceLang) && isChineseTarget(targetLang)) {
            String llmResult = tryLlmCorrectTranslate(text, sourceLang, targetLang, userId, recentContext);
            if (llmResult != null && !llmResult.isBlank()) {
                log.info("[TranslationService] translate end (llm id->zh), userId={}, textHash={}, resultLen={}, costMs={}",
                        userId, diagnosticHash(text), llmResult.length(), System.currentTimeMillis() - start);
                return llmResult;
            }
            log.warn("[TranslationService] llm id->zh unavailable, fallback to google, userId={}, textHash={}",
                    userId, diagnosticHash(text));
        }

        TerminologyService.TerminologyProtection terminologyProtection =
                terminologyService.applyBeforeTranslate(userId, text, sourceLang, targetLang);
        String protectedText = terminologyProtection.getProtectedText();
        log.info("[TranslationService] terminology before translate done, userId={}, textHash={}, termCount={}, protectedLen={}, changed={}",
                userId, diagnosticHash(text), terminologyProtection.getTargetTermByPlaceholder().size(),
                protectedText != null ? protectedText.length() : 0, protectedText != null && !protectedText.equals(text));

        long mtStart = System.currentTimeMillis();
        String result = translator.translate(protectedText, sourceLang, targetLang, userId);
        log.info("[TranslationService] google translate done, userId={}, textHash={}, sourceLang={}, targetLang={}, protectedLen={}, resultLen={}, costMs={}",
                userId, diagnosticHash(text), sourceLang, targetLang,
                protectedText != null ? protectedText.length() : 0, result != null ? result.length() : 0,
                System.currentTimeMillis() - mtStart);
        String beforeMtRestore = result;
        result = terminologyService.applyAfterTranslate(text, result, sourceLang, targetLang, terminologyProtection, userId);
        log.info("[TranslationService] terminology after mt restore done, userId={}, textHash={}, beforeLen={}, afterLen={}, changed={}",
                userId, diagnosticHash(text), beforeMtRestore != null ? beforeMtRestore.length() : 0,
                result != null ? result.length() : 0, beforeMtRestore != null && !beforeMtRestore.equals(result));
        String beforeRewriteProtection = result;
        result = terminologyService.protectTargetTermsForRewrite(result, terminologyProtection);
        log.info("[TranslationService] terminology before rewrite protect done, userId={}, textHash={}, beforeLen={}, afterLen={}, changed={}",
                userId, diagnosticHash(text), beforeRewriteProtection != null ? beforeRewriteProtection.length() : 0,
                result != null ? result.length() : 0,
                beforeRewriteProtection != null && !beforeRewriteProtection.equals(result));
        String beforeCompress = result;
        result = compressIfNeeded(text, sourceLang, targetLang, result, start, allowCompress);
        log.info("[TranslationService] compress stage done, userId={}, textHash={}, beforeLen={}, afterLen={}, changed={}",
                userId, diagnosticHash(text), beforeCompress != null ? beforeCompress.length() : 0,
                result != null ? result.length() : 0, beforeCompress != null && !beforeCompress.equals(result));
        String beforeFinalRestore = result;
        result = terminologyService.applyAfterTranslate(text, result, sourceLang, targetLang, terminologyProtection, userId);
        log.info("[TranslationService] terminology final restore done, userId={}, textHash={}, beforeLen={}, afterLen={}, changed={}",
                userId, diagnosticHash(text), beforeFinalRestore != null ? beforeFinalRestore.length() : 0,
                result != null ? result.length() : 0, beforeFinalRestore != null && !beforeFinalRestore.equals(result));

        log.info("[TranslationService] translate end, userId={}, textLen={}, textHash={}, targetLang={}, termCount={}, costMs={}, resultLen={}",
                userId, text.length(), diagnosticHash(text), targetLang,
                terminologyProtection.getTargetTermByPlaceholder().size(),
                System.currentTimeMillis() - start, result != null ? result.length() : 0);
        return result;
    }

    public List<String> translateBatch(List<String> texts, String sourceLang, String targetLang) {
        return translateBatch(texts, sourceLang, targetLang, 1L);
    }

    public List<String> translateBatch(List<String> texts, String sourceLang, String targetLang, Long userId) {
        log.info("[TranslationService] translateBatch start, count={}, sourceLang={}, targetLang={}",
                texts != null ? texts.size() : 0, sourceLang, targetLang);
        long start = System.currentTimeMillis();
        List<String> results = translator.translateBatch(texts, sourceLang, targetLang, userId);
        log.info("[TranslationService] translateBatch end, count={}, targetLang={}, costMs={}",
                results.size(), targetLang, System.currentTimeMillis() - start);
        return results;
    }

    private void validateLanguages(String sourceLang, String targetLang) {
        if (!isSupportedLang(sourceLang) && !isAutoDetect(sourceLang)) {
            log.warn("[TranslationService] unsupported source lang: {}", sourceLang);
            throw BizException.of(ErrorCode.UNSUPPORTED_LANGUAGE, "Unsupported source language: " + sourceLang);
        }
        if (!isSupportedLang(targetLang)) {
            log.warn("[TranslationService] unsupported target lang: {}", targetLang);
            throw BizException.of(ErrorCode.UNSUPPORTED_LANGUAGE, "Unsupported target language: " + targetLang);
        }
    }

    private String compressIfNeeded(
            String sourceText,
            String sourceLang,
            String targetLang,
            String translatedText,
            long requestStartMs,
            boolean allowCompress
    ) {
        if (!allowCompress) {
            log.debug("[TranslationService] compress skipped (no backlog / channel idle), targetLang={}, textLen={}",
                    targetLang, sourceText != null ? sourceText.length() : 0);
            return translatedText;
        }
        if (translatedText == null || translatedText.isBlank()
                || !openAiProperties.isCompressionEnabled()
                || !isCompressionDirection(sourceLang, targetLang)
                || sourceText.length() < openAiProperties.getCompressionMinTextLength()) {
            log.debug("[TranslationService] compress skipped, enabled={}, sourceLang={}, targetLang={}, textLen={}, threshold={}",
                    openAiProperties.isCompressionEnabled(), sourceLang, targetLang, sourceText.length(),
                    openAiProperties.getCompressionMinTextLength());
            return translatedText;
        }

        String direction = resolveCompressionDirection(targetLang);
        double ratio = isEnglishTarget(targetLang)
                ? openAiProperties.getCompressionZhToEnTargetRatio()
                : openAiProperties.getCompressionZhToIdTargetRatio();
        log.info("[TranslationService] compress start, direction={}, sourceTextLen={}, translatedLen={}, targetRatio={}%, model={}",
                direction, sourceText.length(), translatedText.length(),
                String.format("%.0f", ratio * 100), openAiProperties.getCompressionModel());
        try {
            String compressed = compressByDirection(translatedText, direction);
            if (compressed == null || compressed.isBlank()) {
                return translatedText;
            }
            double actualRatio = (double) compressed.length() / translatedText.length();
            // Guard: if the model compressed more than 2× the target ratio, the result is
            // semantically impoverished — fall back to the original translation.
            if (actualRatio < ratio * 0.50) {
                log.warn("[TranslationService] compress over-aggressive fallback, direction={}, originalLen={}, compressedLen={}, ratio={}%, targetRatio={}%, totalMs={}",
                        direction, translatedText.length(), compressed.length(),
                        String.format("%.1f", actualRatio * 100),
                        String.format("%.0f", ratio * 100),
                        System.currentTimeMillis() - requestStartMs);
                return translatedText;
            }
            log.info("[TranslationService] compress end, direction={}, originalLen={}, compressedLen={}, ratio={}%, totalMs={}",
                    direction,
                    translatedText.length(),
                    compressed.length(),
                    String.format("%.1f", actualRatio * 100),
                    System.currentTimeMillis() - requestStartMs);
            return compressed.trim();
        } catch (IOException e) {
            log.warn("[TranslationService] compress failed, direction={}, use original translation: {}",
                    direction, e.getMessage());
            return translatedText;
        }
    }

    /**
     * 仅当源语言为印尼语时，把印尼式数字写法归一化为通用写法。
     * 先处理带千分位的数字（去「.」、「,」→「.」），再处理裸小数逗号（数字间「,」→「.」）。
     * 中/英源文本不处理（它们的「,」是千分位，改写会出错）。
     */
    private String normalizeIndonesianNumbersIfNeeded(String text, String sourceLang) {
        if (text == null || text.isBlank() || !isIndonesianSource(sourceLang)) {
            return text;
        }
        Matcher matcher = INDONESIAN_GROUPED_NUMBER.matcher(text);
        StringBuilder grouped = new StringBuilder();
        while (matcher.find()) {
            String normalized = matcher.group().replace(".", "").replace(",", ".");
            matcher.appendReplacement(grouped, Matcher.quoteReplacement(normalized));
        }
        matcher.appendTail(grouped);
        return INDONESIAN_DECIMAL_COMMA.matcher(grouped.toString()).replaceAll(".");
    }

    private boolean isIndonesianSource(String sourceLang) {
        if (sourceLang == null) {
            return false;
        }
        String lower = sourceLang.trim().toLowerCase();
        return lower.startsWith("id") || lower.startsWith("in")
                || Constants.LANG_ID_ISO6391.equalsIgnoreCase(lower);
    }

    private boolean isChineseTarget(String targetLang) {
        return targetLang != null && targetLang.trim().toLowerCase().startsWith("zh");
    }

    /**
     * 调 LLM 做印尼语→中文纠错翻译。把本句命中的术语作为"必须遵守的对照表"一并传入。
     * 任何异常/超时返回 null，由调用方回退到 Google 翻译路径。
     */
    private String tryLlmCorrectTranslate(String text, String sourceLang, String targetLang, Long userId,
                                          String recentContext) {
        try {
            String glossary = buildDynamicGlossary(userId, text, sourceLang, targetLang);
            return llmIntegration.correctAndTranslateIndonesianToChinese(text, recentContext, glossary);
        } catch (Exception e) {
            log.warn("[TranslationService] llm id->zh failed, userId={}, textHash={}, reason={}",
                    userId, diagnosticHash(text), e.getMessage());
            return null;
        }
    }

    /**
     * 用现有术语匹配,提取本句命中的"印尼语 = 中文"对照,作为动态术语表喂给 LLM。
     * 复用 {@link TerminologyService#applyBeforeTranslate}(只取匹配结果,不用其占位符文本)。
     */
    private String buildDynamicGlossary(Long userId, String text, String sourceLang, String targetLang) {
        try {
            TerminologyService.TerminologyProtection protection =
                    terminologyService.applyBeforeTranslate(userId, text, sourceLang, targetLang);
            Map<String, String> sourceByPlaceholder = protection.getSourceTermByPlaceholder();
            Map<String, String> targetByPlaceholder = protection.getTargetTermByPlaceholder();
            if (sourceByPlaceholder == null || sourceByPlaceholder.isEmpty()) {
                return null;
            }
            StringBuilder glossary = new StringBuilder();
            for (Map.Entry<String, String> entry : sourceByPlaceholder.entrySet()) {
                String source = entry.getValue();
                String target = targetByPlaceholder.get(entry.getKey());
                if (source != null && !source.isBlank() && target != null && !target.isBlank()) {
                    glossary.append(source).append(" = ").append(target).append("\n");
                }
            }
            return glossary.length() == 0 ? null : glossary.toString();
        } catch (Exception e) {
            log.debug("[TranslationService] buildDynamicGlossary skipped, reason={}", e.getMessage());
            return null;
        }
    }

    private boolean isAutoDetect(String lang) {
        return lang == null || lang.isBlank() || Constants.LANG_AUTO.equalsIgnoreCase(lang);
    }

    private boolean isSupportedLang(String lang) {
        if (lang == null || lang.isBlank()) {
            return false;
        }
        String lower = lang.toLowerCase();
        return lower.startsWith("zh")
                || lower.startsWith("id")
                || lower.startsWith(Constants.LANG_EN_SHORT);
    }

    private boolean isCompressionDirection(String sourceLang, String targetLang) {
        if (!Constants.LANG_ZH_CN.equalsIgnoreCase(sourceLang)) {
            return false;
        }
        return isIndonesianTarget(targetLang)
                || (openAiProperties.isCompressionZhToEnEnabled() && isEnglishTarget(targetLang));
    }

    private String resolveCompressionDirection(String targetLang) {
        return isEnglishTarget(targetLang) ? COMPRESSION_DIRECTION_ZH_TO_EN : COMPRESSION_DIRECTION_ZH_TO_ID;
    }

    private String compressByDirection(String text, String direction) throws IOException {
        if (COMPRESSION_DIRECTION_ZH_TO_EN.equals(direction)) {
            return llmIntegration.compressEnglish(text);
        }
        return llmIntegration.compressIndonesian(text);
    }

    private boolean isIndonesianTarget(String targetLang) {
        return Constants.LANG_ID_SHORT.equalsIgnoreCase(targetLang)
                || Constants.LANG_ID.equalsIgnoreCase(targetLang);
    }

    private boolean isEnglishTarget(String targetLang) {
        return Constants.LANG_EN_SHORT.equalsIgnoreCase(targetLang)
                || Constants.LANG_EN_US.equalsIgnoreCase(targetLang);
    }

    private static String diagnosticHash(String value) {
        return value == null ? "null" : Integer.toHexString(value.hashCode());
    }
}
