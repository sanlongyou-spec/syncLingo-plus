package com.si.backend.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Request sent by the Teams Bot when a Teams user asks a question.
 */
@Data
public class TeamsBotQueryRequest {

    private String aadId;

    private String mail;

    private String userPrincipalName;

    private String displayName;

    @NotBlank(message = "message 不能为空")
    private String message;
}
