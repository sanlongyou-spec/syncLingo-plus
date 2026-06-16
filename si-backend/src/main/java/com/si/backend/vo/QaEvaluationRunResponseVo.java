package com.si.backend.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class QaEvaluationRunResponseVo {

    private QaEvaluationResponseVo evaluation;

    private List<QaEvaluationGeneratedAnswerVo> generatedAnswers;

    @Data
    @Builder
    public static class QaEvaluationGeneratedAnswerVo {

        private String id;

        private String question;

        private String replyText;

        private String responseType;

        private Boolean userMatched;

        private Long userId;

        private String command;

        private int sourceCount;

        private List<TeamsBotQuerySourceVo> sources;
    }
}
