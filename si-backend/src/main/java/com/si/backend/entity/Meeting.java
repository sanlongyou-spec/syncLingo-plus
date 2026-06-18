package com.si.backend.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class Meeting {
    private Long id;
    private Long userId;
    private String title;
    private LocalDateTime scheduledTime;
    private String note;
    private String meetingUrl;
    private Boolean deleted;
    private String attendanceJson;
    /** Parsed 应到 (expected participants) from the uploaded 会议安排, persisted so the attendance
     *  comparison survives across sessions without re-uploading the schedule. */
    private String expectedParticipantsJson;
    private LocalDateTime createTime;
}
