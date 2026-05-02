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
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
