package com.si.backend.entity;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 用户实体，对应数据库 si_user 表。
 */
@Data
public class SiUser {
    private Long id;
    private String username;
    private String password;
    private String nickname;
    private String email;
    private String role;
    /** 账号状态:ACTIVE / PENDING / DISABLED(P1)。列不存在时映射为 null,按 ACTIVE 处理。 */
    private String status;
    /** 令牌版本(P5)。改密/撤销时自增,令旧令牌即时失效。列不存在/旧令牌按 0 处理。 */
    private Integer tokenVersion;
    /** 发言摘要自动发送收件人，JSON 数组格式，如 ["a@jlg.co.id","b@jlg.co.id"] */
    private String summaryRecipients;
    /** 当前账号的会议总结默认提示词。 */
    private String meetingSummaryRequirements;
    /** 当前账号的发言摘要默认提示词。 */
    private String speakerSummaryRequirements;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
