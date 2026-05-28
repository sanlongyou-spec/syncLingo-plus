package com.si.backend.dto;

import lombok.Data;

import java.util.List;

@Data
public class PreMeetingChatRequest {
    private String question;
    private String fileId;
    private String sessionId;
    private List<ChatTurn> history;
    private boolean crossMeeting;
    private long userId;
    private int days;  // 0 = 不限时间

    @Data
    public static class ChatTurn {
        private String role;    // "user" | "assistant"
        private String content;
    }
}
