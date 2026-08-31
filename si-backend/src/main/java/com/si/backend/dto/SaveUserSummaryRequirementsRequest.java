package com.si.backend.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Partial update request for the authenticated user's summary prompts.
 */
@Data
public class SaveUserSummaryRequirementsRequest {

    public static final int MAX_REQUIREMENTS_LENGTH = 4000;

    @Size(max = MAX_REQUIREMENTS_LENGTH)
    private String meetingSummaryRequirements;

    @Size(max = MAX_REQUIREMENTS_LENGTH)
    private String speakerSummaryRequirements;
}
