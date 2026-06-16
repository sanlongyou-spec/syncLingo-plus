package com.si.backend.service;

import com.si.backend.vo.TeamsBotQuerySourceVo;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QaEvaluationServiceTest {

    private final QaEvaluationService service = new QaEvaluationService();

    @Test
    void evaluatePassesWhenAnswerAndSourcesCoverExpectedPoints() {
        QaEvaluationService.QaEvaluationCase testCase = new QaEvaluationService.QaEvaluationCase(
                "case-1",
                "What risks were discussed?",
                List.of("supply delay", "cost increase"),
                List.of("Weekly Review", "2026-06-17"));
        QaEvaluationService.QaEvaluationAnswer answer = new QaEvaluationService.QaEvaluationAnswer(
                "The team discussed supply delay and cost increase.",
                List.of(TeamsBotQuerySourceVo.builder()
                        .meetingTitle("Weekly Review")
                        .sourceDate("2026-06-17")
                        .snippet("supply delay")
                        .build()));

        QaEvaluationService.QaEvaluationResult result = service.evaluate(testCase, answer, 1.0, 1.0);

        assertTrue(result.passed());
        assertEquals(2, result.answerPointHits());
        assertEquals(2, result.sourceKeywordHits());
    }

    @Test
    void evaluateFailsWhenSourcesDoNotMatch() {
        QaEvaluationService.QaEvaluationCase testCase = new QaEvaluationService.QaEvaluationCase(
                "case-2",
                "What risks were discussed?",
                List.of("supply delay"),
                List.of("Quarterly Review"));
        QaEvaluationService.QaEvaluationAnswer answer = new QaEvaluationService.QaEvaluationAnswer(
                "Supply delay was discussed.",
                List.of(TeamsBotQuerySourceVo.builder().meetingTitle("Weekly Review").build()));

        QaEvaluationService.QaEvaluationResult result = service.evaluate(testCase, answer, 1.0, 1.0);

        assertFalse(result.passed());
        assertEquals(1.0, result.answerCoverage());
        assertEquals(0.0, result.sourceCoverage());
    }

    @Test
    void evaluateAllRequiresMatchingAnswerCount() {
        assertThrows(IllegalArgumentException.class, () -> service.evaluateAll(
                List.of(new QaEvaluationService.QaEvaluationCase("id", "q", List.of(), List.of())),
                List.of(),
                1.0,
                1.0));
    }
}
