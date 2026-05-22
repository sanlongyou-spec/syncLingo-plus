package com.si.backend.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * Meeting agenda/report material bound to an interpretation session.
 */
@Data
public class MeetingMaterial {

    private Long id;
    private String sessionId;
    private String title;
    private String agendaText;
    private String reportText;
    private String executiveNames;
    private String summaryText;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
