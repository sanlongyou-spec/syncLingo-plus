package com.si.backend.dto;

import lombok.Data;

/**
 * Request for creating or updating a system user profile.
 */
@Data
public class SaveSystemUserInfoRequest {

    private String department;
    private String personName;
    private String positionTitle;
    private String email;
    private String microsoftId;
    private String robinUid;
    private String teamsVerified;
    private String employmentStatus;
}
