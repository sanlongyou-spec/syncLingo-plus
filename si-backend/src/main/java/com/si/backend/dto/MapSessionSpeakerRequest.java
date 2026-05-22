package com.si.backend.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Request for mapping a transient ASR speaker id to a real person for the current meeting.
 */
@Data
public class MapSessionSpeakerRequest {

    @NotBlank
    private String personName;
}
