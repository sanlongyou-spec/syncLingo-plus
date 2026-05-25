package com.si.backend.vo;

import lombok.Data;

@Data
public class PreMeetingDailyUsageVo {
    private String date;
    private Long llmInputTokens;
    private Long llmOutputTokens;
}
