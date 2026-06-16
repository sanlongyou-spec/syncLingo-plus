package com.si.backend.vo;

import lombok.Builder;
import lombok.Data;

/**
 * 会议成员 VO(P3)。
 */
@Data
@Builder
public class MeetingMemberVo {
    private Long userId;
    private String username;
    private String accessLevel;   // VIEW / OPERATE
}
