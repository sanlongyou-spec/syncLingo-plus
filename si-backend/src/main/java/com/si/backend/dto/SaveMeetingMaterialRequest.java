package com.si.backend.dto;

import lombok.Data;

/**
 * Request for saving meeting agenda/report material.
 */
@Data
public class SaveMeetingMaterialRequest {

    private String title;
    private String agendaText;
    private String reportText;
    private String executiveNames;
}
