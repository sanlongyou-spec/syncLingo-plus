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
    private Boolean deleted;
    private String attendanceJson;
    private LocalDateTime createTime;
}
