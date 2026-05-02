package com.si.backend.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CloneVoiceResponse {
    private String voiceId;
    private String voiceName;
    private Integer durationSeconds;
    private String createTime;
}
