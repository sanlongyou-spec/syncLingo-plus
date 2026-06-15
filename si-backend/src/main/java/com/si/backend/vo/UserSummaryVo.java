package com.si.backend.vo;

import lombok.Builder;
import lombok.Data;

/**
 * 用户管理列表/详情 VO —— **绝不包含密码哈希**。
 */
@Data
@Builder
public class UserSummaryVo {
    private Long id;
    private String username;
    private String nickname;
    private String email;
    private String role;
    private String status;
    private String createTime;
}
