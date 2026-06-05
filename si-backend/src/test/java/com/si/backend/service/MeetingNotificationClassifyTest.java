package com.si.backend.service;

import com.si.backend.entity.SystemUserInfo;
import com.si.backend.service.MeetingNotificationService.NotificationPlan;
import com.si.backend.service.NameMatchService.MatchResult;
import com.si.backend.service.NameMatchService.Status;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for splitting matched names into Teams recipients / non-Teams skipped / unmatched. */
class MeetingNotificationClassifyTest {

    private static SystemUserInfo user(String name, String email) {
        SystemUserInfo u = new SystemUserInfo();
        u.setPersonName(name);
        u.setEmail(email);
        return u;
    }

    @Test
    void splitsTeamsNonTeamsAndUnmatched() {
        List<MatchResult> matches = List.of(
                new MatchResult("李明", user("李明", "liming@jlg.co.id"), Status.MATCHED),       // teams
                new MatchResult("张伟", user("张伟", "zhangwei@gmail.com"), Status.MATCHED),       // non-teams
                new MatchResult("无邮箱", user("无邮箱", null), Status.MATCHED),                    // non-teams (no email)
                new MatchResult("王五", null, Status.UNMATCHED),                                   // unmatched
                new MatchResult("重名", null, Status.AMBIGUOUS));                                   // unmatched

        NotificationPlan plan = MeetingNotificationService.classify("季度会议", "2026年5月26日", matches, "jlg.co.id");

        assertEquals("季度会议", plan.meetingName());
        assertEquals(List.of("liming@jlg.co.id"),
                plan.teamsRecipients().stream().map(MeetingNotificationService.Recipient::email).toList());
        assertEquals("李明", plan.teamsRecipients().get(0).scheduleName());
        assertEquals(2, plan.nonTeamsSkipped().size());
        assertEquals(2, plan.unmatched().size());
    }

    @Test
    void domainMatchIsCaseInsensitive() {
        List<MatchResult> matches = List.of(
                new MatchResult("A", user("A", "A@JLG.CO.ID"), Status.MATCHED));
        NotificationPlan plan = MeetingNotificationService.classify("m", null, matches, "jlg.co.id");
        assertEquals(1, plan.teamsRecipients().size());
    }

    @Test
    void emptyMatchesGivesEmptyPlan() {
        NotificationPlan plan = MeetingNotificationService.classify("m", null, List.of(), "jlg.co.id");
        assertTrue(plan.teamsRecipients().isEmpty());
        assertTrue(plan.nonTeamsSkipped().isEmpty());
        assertTrue(plan.unmatched().isEmpty());
    }
}
