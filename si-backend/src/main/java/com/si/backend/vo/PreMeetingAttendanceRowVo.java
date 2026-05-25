package com.si.backend.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class PreMeetingAttendanceRowVo {
    private String name;
    private String department;
    private String role;
    private String email;
    private String actualName;
    private String actualEmail;
    private String status;
    private String sourceText;
}
