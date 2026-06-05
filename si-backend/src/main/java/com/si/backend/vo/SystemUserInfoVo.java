package com.si.backend.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * System user profile response.
 */
@Data
@Builder
public class SystemUserInfoVo {

    private Long id;
    private String department;
    private String personName;
    private String positionTitle;
    private String email;
    private String microsoftId;
    private String robinUid;
    private String teamsVerified;
    private String employmentStatus;
    private String sourceSheet;
    private Integer sourceRow;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
