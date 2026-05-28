package com.si.backend.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class MeetingActionItem {
    private Long id;
    private String sessionId;
    private Long meetingId;
    private Long userId;
    private String assignee;
    private String content;
    private String deadline;
    private String status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
