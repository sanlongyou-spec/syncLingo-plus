package com.si.backend.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class MeetingVo {
    private Long id;
    private Long userId;
    private String title;
    private String scheduledTime;
    private String note;
    private String attendanceJson;
    private LocalDateTime createTime;
    private List<MeetingFileVo> files;
}
