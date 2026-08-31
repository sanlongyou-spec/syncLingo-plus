package com.si.backend.vo;

import lombok.Builder;
import lombok.Data;

/**
 * Summary prompts owned by one authenticated user.
 */
@Data
@Builder
public class UserSummaryRequirementsVo {
    private String meetingSummaryRequirements;
    private String speakerSummaryRequirements;
}
