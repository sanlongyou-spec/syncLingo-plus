package com.si.backend.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class QaEvaluationResponseVo {

    private int total;

    private int passed;

    private int failed;

    private double minAnswerCoverage;

    private double minSourceCoverage;

    private List<QaEvaluationResultVo> results;

    @Data
    @Builder
    public static class QaEvaluationResultVo {

        private String id;

        private int answerPointHits;

        private int expectedAnswerPoints;

        private int sourceKeywordHits;

        private int expectedSourceKeywords;

        private double answerCoverage;

        private double sourceCoverage;

        private boolean passed;
    }
}
