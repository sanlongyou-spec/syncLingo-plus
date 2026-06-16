package com.si.backend.service;

import com.si.backend.dto.PreMeetingChatRequest.ChatTurn;
import com.si.backend.vo.TeamsBotQuerySourceVo;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TeamsBotQueryHistoryTest {

    @Test
    void boundedHistoryKeepsRecentTurnsAndNormalizesRoles() {
        List<ChatTurn> history = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            ChatTurn turn = new ChatTurn();
            turn.setRole(i % 2 == 0 ? "user" : "assistant");
            turn.setContent(" turn   " + i + " ");
            history.add(turn);
        }
        ChatTurn unsafeRole = new ChatTurn();
        unsafeRole.setRole("system");
        unsafeRole.setContent("latest");
        history.add(unsafeRole);

        List<ChatTurn> bounded = TeamsBotQueryService.boundedHistory(history);

        assertEquals(6, bounded.size());
        assertEquals("assistant", bounded.get(0).getRole());
        assertEquals("turn 3", bounded.get(0).getContent());
        assertEquals("user", bounded.get(5).getRole());
        assertEquals("latest", bounded.get(5).getContent());
    }

    @Test
    void groundedContextIncludesSourceAnchorsAndRules() {
        TeamsBotQuerySourceVo source = TeamsBotQuerySourceVo.builder()
                .meetingTitle("Weekly Review")
                .sourceDate("2026-06-17")
                .sourceName("risk-report.pdf")
                .title("file_summary")
                .snippet("Speaker A discussed the mitigation plan.")
                .build();

        String context = TeamsBotQueryService.buildGroundedAnswerContext("context body", List.of(source));

        assertTrue(context.contains("Answer requirements"));
        assertTrue(context.contains("Weekly Review"));
        assertTrue(context.contains("2026-06-17"));
        assertTrue(context.contains("risk-report.pdf"));
        assertTrue(context.endsWith("context body"));
    }
}
