package com.si.backend.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Meeting material and material-aware summary response.
 */
@Data
@Builder
public class MeetingMaterialVo {

    private Long id;
    private String sessionId;
    private String title;
    private String agendaText;
    private String reportText;
    private String executiveNames;
    private String summaryText;
    private Integer recordCount;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
