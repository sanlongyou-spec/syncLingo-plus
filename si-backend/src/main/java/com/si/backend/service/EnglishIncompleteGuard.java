package com.si.backend.service;

import com.si.backend.config.AzureSpeechProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * English interim segmentation guard for punctuation-less meeting speech.
 *
 * <p>The guard mirrors the Indonesian segmentation flow at a process level:
 * semantic boundary first, then language-specific completeness checks, then final emission.
 * It intentionally avoids meeting-specific hard-coded terms. Multi-word terminology is protected
 * through ASR hotwords so uploaded meeting material and terminology packs can shape segmentation.</p>
 */
@Slf4j
@Component
public class EnglishIncompleteGuard {

    public enum Decision { PASS, HOLD, DROP }

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

    public record BoundaryCandidate(int index, String reason) {
        public boolean found() {
            return index > 0;
        }

        static BoundaryCandidate none() {
            return new BoundaryCandidate(-1, "");
        }
    }

    private static final Pattern TOKEN_PATTERN =
            Pattern.compile("[\\p{L}\\p{Nd}]+(?:'[\\p{L}\\p{Nd}]+)?");
    private static final Pattern INCOMPLETE_NUMBER_TAIL =
            Pattern.compile("(?i)([$]|\\b\\d+[.,:/-])\\s*$");

    private static final Set<String> CONNECTOR_TAILS = Set.of(
            "and", "or", "but", "because", "although", "though", "while", "when", "if", "unless",
            "until", "whereas", "whether", "which", "that", "who", "whom", "whose", "where", "why",
            "how", "so");

    private static final Set<String> PREPOSITION_TAILS = Set.of(
            "of", "to", "for", "with", "from", "by", "in", "on", "at", "into", "onto", "over", "under",
            "through", "between", "among", "around", "about", "against", "within", "without", "during",
            "before", "after");

    private static final Set<String> FUNCTION_TAILS = Set.of(
            "the", "a", "an", "is", "are", "was", "were", "be", "being", "been", "am", "can", "will",
            "would", "should", "could", "may", "might", "must", "shall", "do", "does", "did", "not",
            "we", "we'll", "we're", "i", "i'll", "i'm", "they", "they'll", "it'll", "there");

    private static final Set<String> FILLER_WORDS = Set.of(
            "uh", "um", "er", "ah", "yeah", "yep", "okay", "ok", "like", "just", "actually", "basically",
            "right", "well", "so", "hmm", "mm", "one", "second");

    private static final Set<String> ORPHAN_HEADS = Set.of(
            "pendent", "acing", "tion", "sion", "ment", "ing", "e're", "re", "ld", "autom");

    private static final Set<String> ALLOWED_SHORT_TAILS = Set.of(
            "ai", "ui", "ux", "hr", "it", "api", "rds", "aws", "erp", "crm", "kpi", "okr", "pnl", "p&l",
            "qa", "q&a", "us", "uk", "eu");

    private static final Set<String> UNIT_WORDS = Set.of(
            "percent", "percentage", "kg", "kilogram", "kilograms", "ton", "tons", "tonne", "tonnes",
            "meter", "meters", "metre", "metres", "hectare", "hectares", "acre", "acres", "liter",
            "liters", "litre", "litres", "day", "days", "week", "weeks", "month", "months", "year",
            "years", "quarter", "quarters", "hour", "hours", "minute", "minutes", "second", "seconds",
            "share", "shares", "contract", "contracts", "point", "points", "basis", "bps");

    private static final Set<String> SCALE_WORDS = Set.of(
            "hundred", "thousand", "million", "billion", "trillion");

    private static final Set<String> CURRENCY_WORDS = Set.of(
            "dollar", "dollars", "usd", "rupiah", "idr", "rmb", "yuan", "euro", "euros", "yen", "sgd");

    private static final Set<String> DISCOURSE_BOUNDARY_HEADS = Set.of(
            "then", "next", "now", "finally", "secondly", "thirdly", "however", "therefore", "meanwhile",
            "also", "another", "first", "lastly");

    private static final List<String[]> DISCOURSE_BOUNDARY_PHRASES = List.of(
            new String[]{"and", "then"},
            new String[]{"so", "then"},
            new String[]{"after", "that"},
            new String[]{"in", "terms", "of"},
            new String[]{"moving", "to"},
            new String[]{"talking", "about"},
            new String[]{"for", "the", "first"},
            new String[]{"on", "the", "other", "hand"},
            new String[]{"as", "a", "result"});

    private final AzureSpeechProperties properties;

    public EnglishIncompleteGuard(AzureSpeechProperties properties) {
        this.properties = properties;
        log.info("[EnglishGuard] init, enabled={}, minEmitWords={}, softMaxWords={}, overlongWords={}",
                isEnabled(), minEmitWords(), softMaxWords(), overlongEscalationWords());
    }

    public boolean isEnabled() {
        return properties.getAsr().isEnSegmentGuardEnabled();
    }

    public boolean shouldQuerySentenceBoundary(String working) {
        return wordCount(working) >= Math.max(1, properties.getAsr().getEnSegMinInputWords());
    }

    public boolean shouldEscalateBoundarySearch(String working) {
        return wordCount(working) >= softMaxWords();
    }

    public boolean isOverlong(String working) {
        return wordCount(working) >= overlongEscalationWords();
    }

    public GuardResult check(String segment, List<String> dynamicTerms) {
        if (segment == null) {
            return GuardResult.drop("EN_EMPTY");
        }
        List<Token> tokens = tokenize(segment);
        if (tokens.isEmpty()) {
            return GuardResult.drop("EN_NO_WORD");
        }
        if (isFillerOnly(tokens)) {
            return GuardResult.drop("EN_FILLER_ONLY");
        }
        String first = tokens.get(0).lower();
        if (ORPHAN_HEADS.contains(first)) {
            return GuardResult.hold("EN_ORPHAN_HEAD");
        }
        if (INCOMPLETE_NUMBER_TAIL.matcher(segment).find()) {
            return GuardResult.hold("EN_NUMBER_TAIL");
        }
        String last = tokens.get(tokens.size() - 1).lower();
        if (isIncompleteShortTail(tokens.get(tokens.size() - 1))) {
            return GuardResult.hold("EN_SHORT_TAIL");
        }
        if (CONNECTOR_TAILS.contains(last)) {
            return GuardResult.hold("EN_CONNECTOR_TAIL");
        }
        if (PREPOSITION_TAILS.contains(last)) {
            return GuardResult.hold("EN_PREPOSITION_TAIL");
        }
        if (FUNCTION_TAILS.contains(last)) {
            return GuardResult.hold("EN_FUNCTION_TAIL");
        }
        return GuardResult.pass();
    }

    public EmitAction decideEmit(String working, int end, String reason, List<String> dynamicTerms) {
        if (working == null || end <= 0 || end > working.length()) {
            return EmitAction.HOLD;
        }
        if (boundaryVeto(working, end, dynamicTerms)) {
            return EmitAction.HOLD;
        }
        String segment = working.substring(0, end).trim();
        GuardResult result = check(segment, dynamicTerms);
        if (result.decision() == Decision.DROP) {
            return EmitAction.DROP;
        }
        if (result.decision() == Decision.HOLD) {
            return EmitAction.HOLD;
        }
        if (shouldHoldForOutputFloor(segment)) {
            return EmitAction.HOLD;
        }
        if (!isStrongBoundary(reason)) {
            return EmitAction.DOWNGRADE_PARTIAL;
        }
        return EmitAction.EMIT_FINAL;
    }

    public boolean shouldHoldFinalRemainder(String text, List<String> dynamicTerms) {
        GuardResult result = check(text, dynamicTerms);
        return result.decision() != Decision.PASS;
    }

    public boolean shouldDropFinalRemainder(String text, List<String> dynamicTerms) {
        return check(text, dynamicTerms).decision() == Decision.DROP;
    }

    public boolean shouldHoldForOutputFloor(String segment) {
        return wordCount(segment) < minEmitWords();
    }

    public boolean boundaryVeto(String working, int cutIndex, List<String> dynamicTerms) {
        if (working == null || cutIndex <= 0 || cutIndex >= working.length()) {
            return false;
        }
        List<Token> tokens = tokenize(working);
        if (splitsDynamicTerm(tokens, cutIndex, dynamicTerms)) {
            return true;
        }
        if (splitsNumberExpression(tokens, working, cutIndex)) {
            return true;
        }
        Token after = firstTokenAtOrAfter(tokens, cutIndex);
        return after != null && ORPHAN_HEADS.contains(after.lower());
    }

    public BoundaryCandidate findHeuristicBoundary(String working, int safeEnd, List<String> dynamicTerms) {
        if (working == null || working.isBlank() || safeEnd <= 0) {
            return BoundaryCandidate.none();
        }
        List<Token> tokens = tokenize(working);
        if (tokens.size() < softMaxWords()) {
            return BoundaryCandidate.none();
        }
        int safe = Math.min(safeEnd, working.length());
        int best = -1;
        String bestReason = "";
        int minEmit = minEmitWords();
        int preferredStart = Math.max(minEmit, Math.min(softMaxWords(), tokens.size()));
        int searchStart = isOverlong(working) ? minEmit : preferredStart;

        for (int i = 1; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            if (token.start() > safe) {
                break;
            }
            if (i < searchStart) {
                continue;
            }
            if (!isDiscourseBoundary(tokens, i)) {
                continue;
            }
            int cut = token.start();
            if (cut <= 0 || cut > safe || boundaryVeto(working, cut, dynamicTerms)) {
                continue;
            }
            String candidate = working.substring(0, cut).trim();
            if (decideEmit(working, cut, "sentence-english-heuristic", dynamicTerms) == EmitAction.EMIT_FINAL) {
                best = cut;
                bestReason = wordCount(candidate) >= overlongEscalationWords()
                        ? "sentence-english-overlong"
                        : "sentence-english-heuristic";
            }
        }
        if (best > 0) {
            return new BoundaryCandidate(best, bestReason);
        }
        return BoundaryCandidate.none();
    }

    public static boolean isStrongBoundary(String reason) {
        if (reason == null) {
            return false;
        }
        return switch (reason) {
            case "sentence", "sentence-punct", "sentence-wtpsplit",
                 "sentence-english-heuristic", "sentence-english-overlong" -> true;
            default -> false;
        };
    }

    public int wordCount(String text) {
        return tokenize(text).size();
    }

    private boolean isFillerOnly(List<Token> tokens) {
        int content = 0;
        for (Token token : tokens) {
            if (!FILLER_WORDS.contains(token.lower())) {
                content++;
            }
        }
        return content == 0 && tokens.size() <= 8;
    }

    private boolean isIncompleteShortTail(Token token) {
        String lower = token.lower();
        if (ALLOWED_SHORT_TAILS.contains(lower)) {
            return false;
        }
        return lower.length() <= 2 && !isNumeric(lower);
    }

    private boolean splitsDynamicTerm(List<Token> tokens, int cutIndex, List<String> dynamicTerms) {
        for (List<String> term : termTokenSequences(dynamicTerms)) {
            if (term.size() < 2) {
                continue;
            }
            for (int i = 0; i + term.size() <= tokens.size(); i++) {
                boolean matched = true;
                for (int j = 0; j < term.size(); j++) {
                    if (!tokens.get(i + j).lower().equals(term.get(j))) {
                        matched = false;
                        break;
                    }
                }
                if (!matched) {
                    continue;
                }
                int start = tokens.get(i).start();
                int end = tokens.get(i + term.size() - 1).end();
                if (cutIndex > start && cutIndex < end) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean splitsNumberExpression(List<Token> tokens, String working, int cutIndex) {
        Token beforeCut = lastTokenBefore(tokens, cutIndex);
        Token afterCut = firstTokenAtOrAfter(tokens, cutIndex);
        if (beforeCut != null && afterCut != null && isProtectedNumberPair(beforeCut, afterCut, working)) {
            return true;
        }
        for (int i = 0; i + 1 < tokens.size(); i++) {
            Token left = tokens.get(i);
            Token right = tokens.get(i + 1);
            if (cutIndex <= left.start() || cutIndex >= right.end()) {
                continue;
            }
            String between = working.substring(left.end(), right.start());
            if (isProtectedNumberPair(left, right, working)) {
                return true;
            }
        }
        return false;
    }

    private boolean isProtectedNumberPair(Token left, Token right, String working) {
        String between = working.substring(left.end(), right.start());
        if (isNumeric(left.lower()) && isNumeric(right.lower())
                && between.matches(".*[.,:/-].*")) {
            return true;
        }
        if (isNumeric(left.lower()) && isNumberFollower(right.lower())) {
            return true;
        }
        if (isCurrencyWord(left.lower()) && isNumeric(right.lower())) {
            return true;
        }
        if (SCALE_WORDS.contains(left.lower()) && isCurrencyWord(right.lower())) {
            return true;
        }
        if (("fy".equals(left.lower()) || "fiscal".equals(left.lower())) && isNumeric(right.lower())) {
            return true;
        }
        return ("year".equals(left.lower()) || "fy".equals(left.lower())) && isNumeric(right.lower());
    }

    private boolean isNumberFollower(String token) {
        return UNIT_WORDS.contains(token)
                || SCALE_WORDS.contains(token)
                || CURRENCY_WORDS.contains(token)
                || "percent".equals(token)
                || "percentage".equals(token)
                || "year".equals(token)
                || "years".equals(token);
    }

    private boolean isCurrencyWord(String token) {
        return CURRENCY_WORDS.contains(token);
    }

    private boolean isDiscourseBoundary(List<Token> tokens, int index) {
        String current = tokens.get(index).lower();
        if (DISCOURSE_BOUNDARY_HEADS.contains(current)) {
            return true;
        }
        for (String[] phrase : DISCOURSE_BOUNDARY_PHRASES) {
            if (index + phrase.length > tokens.size()) {
                continue;
            }
            boolean matched = true;
            for (int j = 0; j < phrase.length; j++) {
                if (!tokens.get(index + j).lower().equals(phrase[j])) {
                    matched = false;
                    break;
                }
            }
            if (matched) {
                return true;
            }
        }
        return false;
    }

    private List<List<String>> termTokenSequences(List<String> dynamicTerms) {
        if (dynamicTerms == null || dynamicTerms.isEmpty()) {
            return List.of();
        }
        List<List<String>> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String term : dynamicTerms) {
            List<Token> tokens = tokenize(term);
            if (tokens.size() < 2 || tokens.size() > 8) {
                continue;
            }
            List<String> sequence = tokens.stream().map(Token::lower).toList();
            String key = String.join(" ", sequence);
            if (seen.add(key)) {
                result.add(sequence);
            }
        }
        return result;
    }

    private Token firstTokenAtOrAfter(List<Token> tokens, int index) {
        for (Token token : tokens) {
            if (token.start() >= index) {
                return token;
            }
        }
        return null;
    }

    private Token lastTokenBefore(List<Token> tokens, int index) {
        Token result = null;
        for (Token token : tokens) {
            if (token.start() >= index) {
                break;
            }
            result = token;
        }
        return result;
    }

    private List<Token> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<Token> tokens = new ArrayList<>();
        Matcher matcher = TOKEN_PATTERN.matcher(text);
        while (matcher.find()) {
            String raw = matcher.group();
            tokens.add(new Token(raw, raw.toLowerCase(Locale.ROOT), matcher.start(), matcher.end()));
        }
        return tokens;
    }

    private boolean isNumeric(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        for (int i = 0; i < token.length(); i++) {
            if (!Character.isDigit(token.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private int minEmitWords() {
        return Math.max(1, properties.getAsr().getEnMinEmitWords());
    }

    private int softMaxWords() {
        return Math.max(minEmitWords(), properties.getAsr().getEnSoftMaxWords());
    }

    private int overlongEscalationWords() {
        return Math.max(softMaxWords(), properties.getAsr().getEnOverlongEscalationWords());
    }

    private record Token(String raw, String lower, int start, int end) {
    }
}
