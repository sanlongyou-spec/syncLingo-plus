package com.si.backend.service;

import com.si.backend.config.AzureSpeechProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 印尼语(id-ID)流式分段完整性 Guard。准确率优先：宁可慢一点，也不让半词/残句进入翻译。
 *
 * <p>纯逻辑、无状态、可单测（对齐本包 {@link IndonesianBoundarySegmenter#completedPrefixEnd} 的可测风格）。
 * 由 {@code AzureAsrIntegration.AsrSession} 在「发段前」与「final remainder 发出前」调用，
 * 以及 {@code RealtimeInterpretationFacade} 在「id→zh 进翻译前」做纵深防御。</p>
 *
 * <p>核心三类判定：
 * <ul>
 *   <li>{@link #check(String)}：段文本完整性一票否决（半词尾 / 连接词尾 / 可疑词头 / 纯噪声）。</li>
 *   <li>{@link #boundaryVeto(String, int)}：候选切点否决（切断固定短语 / 数字↔单位词 / 制造可疑词头）。</li>
 *   <li>{@link #decideEmit(String, int, String)}：综合上面两项 + 强弱边界，给出最终发段动作。</li>
 * </ul></p>
 */
@Slf4j
@Component
public class IndonesianIncompleteGuard {

    /** 段文本完整性判定结果。 */
    public enum Decision { PASS, HOLD, DROP }

    /** 发段动作：EMIT_FINAL=作为 final 送翻译；其余三者都不发 final、不推进 emittedLen。 */
    public enum EmitAction { EMIT_FINAL, DOWNGRADE_PARTIAL, HOLD, DROP }

    public record GuardResult(Decision decision, String reason) {
        public boolean isPass() {
            return decision == Decision.PASS;
        }

        static GuardResult pass() {
            return new GuardResult(Decision.PASS, "OK");
        }

        static GuardResult hold(String reason) {
            return new GuardResult(Decision.HOLD, reason);
        }

        static GuardResult drop(String reason) {
            return new GuardResult(Decision.DROP, reason);
        }
    }

    /** 半词前缀：末 token 恰为其一 → 是被切开的半个词（如 "...peng"）。 */
    private static final Set<String> HALF_WORD_PREFIXES = Set.of(
            "peng", "pem", "pen", "per", "pe", "ber", "ter", "mem", "men", "meng", "me", "di", "ke", "se");

    /** 连接词/介词：句子不应以其结尾（如 "...dengan"）。 */
    private static final Set<String> CONNECTOR_TAILS = Set.of(
            "dan", "atau", "yang", "untuk", "dengan", "karena", "namun", "maka", "oleh", "sebagai", "secara",
            "dari", "pada", "terhadap", "agar", "supaya", "jika", "kalau", "bahwa");

    /** 可疑词头：段不应以其开头（多为被切开的词尾，如 "tuk" 来自 "untuk"）。 */
    private static final Set<String> SUSPICIOUS_HEADS = Set.of(
            "tuk", "omikiran", "depan", "bola", "juta");

    /** 数字后接单位词 → 金额/数量，不可在数字处当编号标题切。 */
    private static final Set<String> UNIT_WORDS = Set.of(
            "juta", "miliar", "ribu", "dolar", "rupiah", "hektar", "tahun", "hari", "bulan", "minggu",
            "orang", "persen", "ton");

    /** 数字后接标题动词/名词 → 编号标题，可在数字前切。 */
    private static final Set<String> TITLE_WORDS = Set.of(
            "membangun", "membeli", "mengembangkan", "berfokus", "manusia", "pemikiran", "kesimpulan",
            "terus", "nilai", "fokus", "mengatur", "membentuk");

    /** 固定短语（token 序列，全小写）：候选切点不可切断它们。 */
    private static final List<String[]> FIXED_PHRASES = List.of(
            new String[]{"masa", "depan"},
            new String[]{"sepak", "bola"},
            new String[]{"amerika", "serikat"},
            new String[]{"oleh", "karena", "itu"},
            new String[]{"benar", "benar"},
            new String[]{"trial", "and", "error"},
            new String[]{"digital", "twin"},
            new String[]{"space", "agriculture"},
            new String[]{"sistem", "keuangan"},
            new String[]{"sistem", "biaya"},
            new String[]{"target", "fiskal"},
            new String[]{"amerika", "dan", "indonesia"},
            new String[]{"dolar", "amerika", "serikat"});

    private static final Pattern TOKEN_PATTERN = Pattern.compile("[\\p{L}\\p{Nd}]+");

    /** 短应答白名单：这些即便短于门槛也可直接 final（真·短句，无需等并句）。 */
    private static final Set<String> SHORT_REPLY_WHITELIST = Set.of(
            "ya", "iya", "tidak", "baik", "setuju", "oke", "ok", "sudah", "silakan", "lanjut",
            "mohon", "maaf", "betul", "benar", "terima kasih", "selamat pagi", "selamat siang", "selamat sore");

    private final boolean enabled;
    /** sentence-wtpsplit 短段门槛（可见字符数）：短于此且非白名单 → HOLD 退回下一轮并句。 */
    private final int minIdChars;

    public IndonesianIncompleteGuard(AzureSpeechProperties properties) {
        this.enabled = properties.getAsr().isIdSegmentGuardEnabled();
        this.minIdChars = properties.getAsr().getMinSentenceEmitIdChars();
        log.info("[IdGuard] init, enabled={}, minIdChars={}", enabled, minIdChars);
    }

    public boolean isEnabled() {
        return enabled;
    }

    // ── 段文本完整性 ───────────────────────────────────────────────────────

    /**
     * 检查一个待发段是否完整可发。HOLD/DROP 的段不允许进入翻译/入库/TTS。
     */
    public GuardResult check(String segment) {
        if (segment == null) {
            return GuardResult.drop("EMPTY");
        }
        List<Token> tokens = tokenize(segment);
        if (tokens.isEmpty()) {
            return GuardResult.drop("NO_WORD");
        }
        String first = tokens.get(0).text();
        String last = tokens.get(tokens.size() - 1).text();
        if (HALF_WORD_PREFIXES.contains(last)) {
            return GuardResult.hold("ID_PREFIX_TAIL");
        }
        if (CONNECTOR_TAILS.contains(last)) {
            return GuardResult.hold("ID_CONNECTOR_TAIL");
        }
        if (SUSPICIOUS_HEADS.contains(first)) {
            return GuardResult.hold("ID_SUSPICIOUS_HEAD");
        }
        return GuardResult.pass();
    }

    // ── 候选切点否决 ───────────────────────────────────────────────────────

    /**
     * 候选切点 {@code cutIndex}（working 内的字符下标）是否应被否决：
     * 切断固定短语 / 切在数字↔单位词或数字↔数字之间 / 切后开头是可疑词头。
     */
    public boolean boundaryVeto(String working, int cutIndex) {
        if (working == null || cutIndex <= 0 || cutIndex >= working.length()) {
            return false;
        }
        List<Token> tokens = tokenize(working);
        if (splitsFixedPhrase(tokens, cutIndex)) {
            return true;
        }
        if (splitsNumberUnit(tokens, cutIndex)) {
            return true;
        }
        Token after = firstTokenAtOrAfter(tokens, cutIndex);
        return after != null && SUSPICIOUS_HEADS.contains(after.text());
    }

    private boolean splitsFixedPhrase(List<Token> tokens, int cutIndex) {
        for (String[] phrase : FIXED_PHRASES) {
            for (int i = 0; i + phrase.length <= tokens.size(); i++) {
                boolean matched = true;
                for (int k = 0; k < phrase.length; k++) {
                    if (!tokens.get(i + k).text().equals(phrase[k])) {
                        matched = false;
                        break;
                    }
                }
                if (!matched) {
                    continue;
                }
                int phraseStart = tokens.get(i).start();
                int phraseEnd = tokens.get(i + phrase.length - 1).end();
                if (cutIndex > phraseStart && cutIndex < phraseEnd) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean splitsNumberUnit(List<Token> tokens, int cutIndex) {
        for (int i = 0; i + 1 < tokens.size(); i++) {
            Token left = tokens.get(i);
            Token right = tokens.get(i + 1);
            // 切点落在「数字 + 数字/单位词」这一对内部(含两 token 之间的空白与各自中部),即视为切断金额/数量
            if (cutIndex <= left.start() || cutIndex >= right.end()) {
                continue;
            }
            boolean leftNumber = isNumber(left.text());
            boolean rightNumberOrUnit = isNumber(right.text()) || UNIT_WORDS.contains(right.text());
            if (leftNumber && rightNumberOrUnit) {
                return true;
            }
        }
        return false;
    }

    // ── 编号标题 ───────────────────────────────────────────────────────────

    /**
     * working 中从 {@code numberTokenStart} 开始的 token 是否构成"编号标题"：
     * 1~2 位数字 + 紧随标题动词/名词（如 "13 pemikiran"）。数字后跟单位词（如 "10 juta"）则不是。
     */
    public boolean isNumberedTitle(String working, int numberTokenStart) {
        if (working == null) {
            return false;
        }
        List<Token> tokens = tokenize(working);
        for (int i = 0; i + 1 < tokens.size(); i++) {
            if (tokens.get(i).start() != numberTokenStart) {
                continue;
            }
            String number = tokens.get(i).text();
            if (!isShortNumber(number)) {
                return false;
            }
            String next = tokens.get(i + 1).text();
            if (UNIT_WORDS.contains(next)) {
                return false;
            }
            return TITLE_WORDS.contains(next);
        }
        return false;
    }

    /**
     * 在 [1, safeEnd) 内查找第一个"编号标题"的数字起点，作为强边界切点（切在数字前，编号+标题进入下一段）。
     * 返回该数字 token 的起始下标；无则返回 -1。要求数字前已有实际内容（不切在最开头）。
     */
    public int findNumberedTitleBoundary(String working, int safeEnd) {
        if (working == null || safeEnd <= 1) {
            return -1;
        }
        List<Token> tokens = tokenize(working);
        for (int i = 1; i + 1 < tokens.size(); i++) {
            Token number = tokens.get(i);
            if (number.start() <= 0 || number.start() >= safeEnd) {
                continue;
            }
            if (!isShortNumber(number.text())) {
                continue;
            }
            String next = tokens.get(i + 1).text();
            if (UNIT_WORDS.contains(next)) {
                continue;
            }
            if (TITLE_WORDS.contains(next)) {
                return number.start();
            }
        }
        return -1;
    }

    // ── 综合发段决策 ───────────────────────────────────────────────────────

    /**
     * 给出最终发段动作。{@code reason} 为边界来源（sentence/sentence-wtpsplit/numbered-title 为强边界，其余为弱边界）。
     * <ul>
     *   <li>切点被否决 → HOLD；</li>
     *   <li>段文本不完整 → HOLD/DROP；</li>
     *   <li>弱边界 → DOWNGRADE_PARTIAL（去掉盲切：不发 final）；</li>
     *   <li>sentence-wtpsplit 短于门槛且非白名单 → HOLD（退回下一轮并句，避免短碎片直接 final）；</li>
     *   <li>强边界且完整、长度达标 → EMIT_FINAL。</li>
     * </ul>
     */
    public EmitAction decideEmit(String working, int end, String reason) {
        if (working == null || end <= 0 || end > working.length()) {
            return EmitAction.HOLD;
        }
        if (boundaryVeto(working, end)) {
            return EmitAction.HOLD;
        }
        String segment = working.substring(0, end).trim();
        GuardResult result = check(segment);
        if (result.decision() == Decision.DROP) {
            return EmitAction.DROP;
        }
        if (result.decision() == Decision.HOLD) {
            return EmitAction.HOLD;
        }
        if (!isStrongBoundary(reason)) {
            return EmitAction.DOWNGRADE_PARTIAL;
        }
        // 短句门槛：wtpsplit 在足量上下文里仍可能在很早处结句(如 "ke depan"/"8 tahun")。
        // 这类短段 HOLD 退回下一轮并句，不直接 final；真·短应答(ya/baik/terima kasih)走白名单放行。
        if ("sentence-wtpsplit".equals(reason)
                && minIdChars > 0
                && visibleCharCount(segment) < minIdChars
                && !isWhitelistedShortReply(segment)) {
            return EmitAction.HOLD;
        }
        return EmitAction.EMIT_FINAL;
    }

    /** 强边界：明确句末标点、wtpsplit 可信句边界、编号标题前。其余（逗号/词边界/超长/超时 backstop）为弱边界。 */
    public static boolean isStrongBoundary(String reason) {
        if (reason == null) {
            return false;
        }
        return switch (reason) {
            case "sentence", "sentence-punct", "sentence-wtpsplit", "numbered-title" -> true;
            default -> false;
        };
    }

    // ── 工具 ───────────────────────────────────────────────────────────────

    private record Token(String text, int start, int end) {
    }

    private List<Token> tokenize(String text) {
        List<Token> tokens = new ArrayList<>();
        Matcher matcher = TOKEN_PATTERN.matcher(text.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            tokens.add(new Token(matcher.group(), matcher.start(), matcher.end()));
        }
        return tokens;
    }

    private Token firstTokenAtOrAfter(List<Token> tokens, int index) {
        for (Token token : tokens) {
            if (token.start() >= index) {
                return token;
            }
        }
        return null;
    }

    private boolean isNumber(String token) {
        if (token == null || token.isEmpty()) {
            return false;
        }
        for (int i = 0; i < token.length(); i++) {
            if (!Character.isDigit(token.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private boolean isShortNumber(String token) {
        return isNumber(token) && token.length() >= 1 && token.length() <= 2;
    }

    private int visibleCharCount(String text) {
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            if (!Character.isWhitespace(text.charAt(i))) {
                count++;
            }
        }
        return count;
    }

    /** 整段(忽略标点/空白后)是否就是一个白名单短应答，如 "Ya."、"Terima kasih."。 */
    private boolean isWhitelistedShortReply(String segment) {
        if (segment == null) {
            return false;
        }
        StringBuilder sb = new StringBuilder();
        Matcher matcher = TOKEN_PATTERN.matcher(segment.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(matcher.group());
        }
        return SHORT_REPLY_WHITELIST.contains(sb.toString());
    }

    /**
     * 终稿 remainder 外科式处理：只反复剥掉结尾的半词前缀 / 连接词介词 token，返回可发出的前缀。
     * <p>不丢整段、不看词头(终稿是 Azure 权威文本，词头如 "Juta" 照常保留)。
     * 若剥到为空(整段都是残片)返回空串，调用方据此抑制。</p>
     */
    public String trimIncompleteTail(String text) {
        if (text == null) {
            return "";
        }
        String current = text.trim();
        while (!current.isEmpty()) {
            List<Token> tokens = tokenize(current);
            if (tokens.isEmpty()) {
                return "";
            }
            String last = tokens.get(tokens.size() - 1).text();
            if (HALF_WORD_PREFIXES.contains(last) || CONNECTOR_TAILS.contains(last)) {
                int cut = tokens.get(tokens.size() - 1).start();
                current = current.substring(0, cut).trim();
                continue;
            }
            return current;
        }
        return "";
    }
}
