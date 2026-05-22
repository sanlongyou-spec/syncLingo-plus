package com.si.backend.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 会议纪要返回对象。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MeetingSummaryVo {

    private String sessionId;
    private String summary;
    private Integer recordCount;
}
