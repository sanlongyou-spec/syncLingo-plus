package com.si.backend.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * System-wide user profile imported from the Robin/Teams registration workbook.
 */
@Data
public class SystemUserInfo {

    private Long id;
    private String department;
    private String personName;
    private String positionTitle;
    private String email;
    private String microsoftId;
    private String robinUid;
    private String teamsVerified;
    private String employmentStatus;
    /** Nationality (中国人 / 华人 / 印尼人), taken from the import sheet name. Drives name-order matching. */
    private String nationality;
    private String sourceSheet;
    private Integer sourceRow;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
