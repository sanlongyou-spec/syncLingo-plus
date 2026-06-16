package com.si.backend.dto;

import com.si.backend.dto.PreMeetingChatRequest.ChatTurn;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

@Data
public class QaEvaluationRunRequest {

    private String aadId;

    private String mail;

    private String userPrincipalName;

    private String displayName;

    private double minAnswerCoverage = 0.8;

    private double minSourceCoverage = 0.8;

    @Valid
    @NotEmpty(message = "cases cannot be empty")
    private List<QaEvaluationRunCaseItem> cases;

    @Data
    public static class QaEvaluationRunCaseItem {

        @NotBlank(message = "case id cannot be blank")
        private String id;

        @NotBlank(message = "question cannot be blank")
        private String question;

        private List<String> expectedAnswerPoints;

        private List<String> expectedSourceKeywords;

        private List<ChatTurn> history;
    }
}
