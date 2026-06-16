package com.si.backend.service;

import com.si.backend.vo.TeamsBotQuerySourceVo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

/**
 * Deterministic evaluator for AI Q&A regression baselines.
 */
@Slf4j
@Service
public class QaEvaluationService {

    public record QaEvaluationCase(
            String id,
            String question,
            List<String> expectedAnswerPoints,
            List<String> expectedSourceKeywords) {
    }

    public record QaEvaluationAnswer(
            String answer,
            List<TeamsBotQuerySourceVo> sources) {
    }

    public record QaEvaluationResult(
            String id,
            int answerPointHits,
            int expectedAnswerPoints,
            int sourceKeywordHits,
            int expectedSourceKeywords,
            double answerCoverage,
            double sourceCoverage,
            boolean passed) {
    }

    public List<QaEvaluationResult> evaluateAll(
            List<QaEvaluationCase> cases,
            List<QaEvaluationAnswer> answers,
            double minAnswerCoverage,
            double minSourceCoverage) {
        if (cases == null || cases.isEmpty()) {
            return List.of();
        }
        if (answers == null || answers.size() != cases.size()) {
            throw new IllegalArgumentException("answers size must match cases size");
        }
        log.info("[QaEvaluationService] evaluateAll start, cases={}", cases.size());
        List<QaEvaluationResult> results = java.util.stream.IntStream.range(0, cases.size())
                .mapToObj(i -> evaluate(cases.get(i), answers.get(i), minAnswerCoverage, minSourceCoverage))
                .toList();
        long passed = results.stream().filter(QaEvaluationResult::passed).count();
        log.info("[QaEvaluationService] evaluateAll end, cases={}, passed={}", cases.size(), passed);
        return results;
    }

    public QaEvaluationResult evaluate(
            QaEvaluationCase testCase,
            QaEvaluationAnswer answer,
            double minAnswerCoverage,
            double minSourceCoverage) {
        if (testCase == null) {
            throw new IllegalArgumentException("testCase cannot be null");
        }
        List<String> answerPoints = safeList(testCase.expectedAnswerPoints());
        List<String> sourceKeywords = safeList(testCase.expectedSourceKeywords());
        String answerText = normalize(answer != null ? answer.answer() : "");
        String sourceText = normalize(sourceText(answer != null ? answer.sources() : List.of()));

        int answerHits = countKeywordHits(answerText, answerPoints);
        int sourceHits = countKeywordHits(sourceText, sourceKeywords);
        double answerCoverage = coverage(answerHits, answerPoints.size());
        double sourceCoverage = coverage(sourceHits, sourceKeywords.size());
        boolean passed = answerCoverage >= minAnswerCoverage && sourceCoverage >= minSourceCoverage;
        return new QaEvaluationResult(
                testCase.id(),
                answerHits,
                answerPoints.size(),
                sourceHits,
                sourceKeywords.size(),
                answerCoverage,
                sourceCoverage,
                passed);
    }

    private static List<String> safeList(List<String> values) {
        return values == null ? List.of() : values.stream()
                .filter(value -> value != null && !value.isBlank())
                .toList();
    }

    private static int countKeywordHits(String haystack, List<String> needles) {
        int hits = 0;
        for (String needle : needles) {
            if (haystack.contains(normalize(needle))) {
                hits++;
            }
        }
        return hits;
    }

    private static double coverage(int hits, int total) {
        return total == 0 ? 1.0 : (double) hits / total;
    }

    private static String normalize(String text) {
        return (text == null ? "" : text)
                .replaceAll("\\s+", " ")
                .trim()
                .toLowerCase(Locale.ROOT);
    }

    private static String sourceText(List<TeamsBotQuerySourceVo> sources) {
        if (sources == null || sources.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (TeamsBotQuerySourceVo source : sources) {
            if (source == null) {
                continue;
            }
            append(builder, source.getMeetingTitle());
            append(builder, source.getSourceDate());
            append(builder, source.getSourceName());
            append(builder, source.getTitle());
            append(builder, source.getSnippet());
        }
        return builder.toString();
    }

    private static void append(StringBuilder builder, String value) {
        if (value != null && !value.isBlank()) {
            builder.append(' ').append(value);
        }
    }
}
