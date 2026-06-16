package com.si.backend.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

@Data
public class QaEvaluationRequest {

    private double minAnswerCoverage = 0.8;

    private double minSourceCoverage = 0.8;

    @Valid
    @NotEmpty(message = "cases cannot be empty")
    private List<QaEvaluationCaseItem> cases;

    @Valid
    @NotEmpty(message = "answers cannot be empty")
    private List<QaEvaluationAnswerItem> answers;

    @Data
    public static class QaEvaluationCaseItem {

        @NotBlank(message = "case id cannot be blank")
        private String id;

        @NotBlank(message = "question cannot be blank")
        private String question;

        private List<String> expectedAnswerPoints;

        private List<String> expectedSourceKeywords;
    }

    @Data
    public static class QaEvaluationAnswerItem {

        private String answer;

        private List<QaEvaluationSourceItem> sources;
    }

    @Data
    public static class QaEvaluationSourceItem {

        private String sourceType;

        private String title;

        private String meetingTitle;

        private String sessionId;

        private Long meetingId;

        private Long fileId;

        private String sourceName;

        private String sourceDate;

        private String snippet;

        private Float score;
    }
}
