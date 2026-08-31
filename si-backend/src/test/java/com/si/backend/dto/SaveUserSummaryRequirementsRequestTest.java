package com.si.backend.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SaveUserSummaryRequirementsRequestTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void acceptsEmptyAndMaximumLengthRequirements() {
        SaveUserSummaryRequirementsRequest request = new SaveUserSummaryRequirementsRequest();
        request.setMeetingSummaryRequirements("");
        request.setSpeakerSummaryRequirements("a".repeat(
                SaveUserSummaryRequirementsRequest.MAX_REQUIREMENTS_LENGTH));

        assertTrue(validator.validate(request).isEmpty());
    }

    @Test
    void rejectsRequirementAboveMaximumLength() {
        SaveUserSummaryRequirementsRequest request = new SaveUserSummaryRequirementsRequest();
        request.setMeetingSummaryRequirements("a".repeat(
                SaveUserSummaryRequirementsRequest.MAX_REQUIREMENTS_LENGTH + 1));

        assertFalse(validator.validate(request).isEmpty());
    }
}
