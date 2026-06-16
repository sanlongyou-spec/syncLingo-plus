package com.si.backend.dto;

import lombok.Data;

@Data
public class PreMeetingSummarizeRequest {
    private String fileId;
    private String requirements;
    /** Optional: tie this 会前总结 usage to a meeting so cost is per-meeting + removed on meeting delete. */
    private Long meetingId;
}
