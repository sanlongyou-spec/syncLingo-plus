package com.si.backend.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

/**
 * Request sent by the Teams Bot when a Teams user asks a question.
 */
@Data
public class TeamsBotQueryRequest {

    private String aadId;

    private String mail;

    private String userPrincipalName;

    private String displayName;

    @NotBlank(message = "message cannot be blank")
    private String message;

    /** Optional recent Teams chat turns; omitted by older bot clients. */
    private List<PreMeetingChatRequest.ChatTurn> history;
}
