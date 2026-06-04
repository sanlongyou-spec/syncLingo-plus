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
    /** Whether a 应到 (expected participants) list has been saved for this meeting from a 会议安排. */
    private boolean hasExpectedParticipants;
    private LocalDateTime createTime;
    private List<MeetingFileVo> files;
}
