package com.si.backend.dto;

import lombok.Data;

/**
 * 管理员创建用户请求(P1)。
 */
@Data
public class CreateUserRequest {
    private String username;
    private String password;
    private String role;       // ADMIN / OPERATOR / VIEWER
    private String nickname;
    private String email;
}
