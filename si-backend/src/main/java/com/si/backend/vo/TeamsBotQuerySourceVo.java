package com.si.backend.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Source snippet shown with a Teams Bot answer so users can trace the answer.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TeamsBotQuerySourceVo {

    private String sourceType;

    private String title;

    private String meetingTitle;

    private String sessionId;

    private Long meetingId;

    private Long fileId;

    private String sourceName;

    private String sourceDate;

    private String snippet;

    private Float score;
}
