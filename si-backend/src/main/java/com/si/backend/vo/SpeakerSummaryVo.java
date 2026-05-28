package com.si.backend.vo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class SpeakerSummaryVo {
    private String speakerId;
    private String speakerName;
    private String title;
    private String summary;
}
