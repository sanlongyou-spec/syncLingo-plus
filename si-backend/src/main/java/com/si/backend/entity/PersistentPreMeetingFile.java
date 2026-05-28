package com.si.backend.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class PersistentPreMeetingFile {
    private Long id;
    private Long meetingId;
    private String fileName;
    private String fileType;
    private String fileContent;
    private byte[] fileData;
    private String summary;
    private LocalDateTime createTime;
}
