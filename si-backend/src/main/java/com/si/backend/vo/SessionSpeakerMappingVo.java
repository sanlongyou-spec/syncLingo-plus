package com.si.backend.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SessionSpeakerMappingVo {
    private String speakerId;
    private String personName;
}
