package com.si.backend.vo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class UserVoiceVo {
    private String voiceId;
    private String voiceName;
    private Integer durationSeconds;
    private Boolean authorized;
    private String scope;
    private Boolean disabled;
    private String createTime;
    private String updateTime;
}
