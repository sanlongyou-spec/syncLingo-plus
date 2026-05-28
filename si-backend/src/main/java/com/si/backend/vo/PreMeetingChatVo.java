package com.si.backend.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class PreMeetingChatVo {
    private String answer;
    private String contextSummary;
    private List<String> referencedSessions;
}
