package com.si.backend.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class PreMeetingAttendanceVo {
    private String fileId;
    private String fileName;
    private String meetingTitle;
    private Integer expectedCount;
    private Integer actualCount;
    private Integer presentCount;
    private Integer absentCount;
    private Integer unexpectedCount;
    private List<PreMeetingAttendanceRowVo> rows;
}
