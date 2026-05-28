package com.si.backend.vo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class MeetingFileVo {
    private Long id;
    private Long meetingId;
    private String fileName;
    private String fileType;
    private String summary;
    private String createTime;
}
