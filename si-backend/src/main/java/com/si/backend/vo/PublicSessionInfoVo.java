package com.si.backend.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 分享页公开会话信息（免登录），用于渲染语言选择按钮。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PublicSessionInfoVo {

    private String sessionId;
    private String title;
    private String status;
    /** 会议启用的语言列表（如 zh-CN,id-ID,en-US） */
    private List<String> enabledLanguages;
}
