package com.si.backend.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class PreMeetingUsageRecord {
    private Long id;
    private Long userId;
    /** Meeting this 会前 usage belongs to (so cost is per-meeting and removed when the meeting is deleted). */
    private Long meetingId;
    private String fileName;
    private Long llmInputTokens;
    private Long llmOutputTokens;
    private String createTime;
}
