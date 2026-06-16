package com.si.backend.entity;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 会议成员授权(P3)。一个会议可把 VIEW/OPERATE 显式授予非 owner 用户。
 */
@Data
public class MeetingMember {
    private Long id;
    private Long meetingId;
    private Long userId;
    private String accessLevel;   // VIEW / OPERATE
    private Long assignedBy;
    private LocalDateTime createTime;
}
