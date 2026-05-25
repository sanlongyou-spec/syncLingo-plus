package com.si.backend.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class PreMeetingSummaryVo {
    private String fileId;
    private String fileName;
    private String summary;
    private String extractedText;
}
