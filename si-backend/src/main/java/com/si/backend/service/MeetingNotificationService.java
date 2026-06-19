package com.si.backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Builds meeting notification drafts and classifies notice participants against existing Teams users.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MeetingNotificationService {

    /** A matched Teams recipient: 会议安排原始名 / 系统账号名 / 邮箱. */
    public record Recipient(String scheduleName, String accountName, String email, String teamsAccount) {}

    /** Outcome of matching a 会议安排 name list against the directory. */
    public record NotificationPlan(
            String meetingName,
            String meetingTime,
            List<Recipient> teamsRecipients,   // jlg.co.id Teams users to notify (name + account + email)
            List<String> nonTeamsSkipped,      // matched but not a Teams user
            List<String> unmatched) {}         // ambiguous / not found (need人工)

    @Value("${notification.teams-domain:jlg.co.id}")
    private String teamsDomain;
    @Value("${notification.test-mode:false}")
    private boolean testMode;
    @Value("${notification.test-recipient:yousanlong@jlg.co.id}")
    private String testRecipient;

    private final NameMatchService nameMatchService;

    /** Build the notification plan from the schedule's parsed name list. */
    public NotificationPlan buildPlan(String meetingName, String meetingTime, List<String> scheduleNames) {
        log.info("[MeetingNotificationService] buildPlan start, meetingName={}, participantCount={}",
                meetingName, scheduleNames == null ? 0 : scheduleNames.size());
        NotificationPlan plan = classify(
                meetingName,
                meetingTime,
                nameMatchService.match(scheduleNames == null ? List.of() : scheduleNames),
                teamsDomain
        );
        log.info("[MeetingNotificationService] buildPlan end, matched={}, nonTeams={}, unmatched={}",
                plan.teamsRecipients().size(), plan.nonTeamsSkipped().size(), plan.unmatched().size());
        return plan;
    }

    public String buildNotificationContent(
            MeetingNoticeParser.MeetingNoticeDetails details,
            String meetingUrl
    ) {
        List<String> lines = new ArrayList<>();
        lines.add("各位领导及同事好，现将今日的会议安排通知如下：");
        lines.add("【" + details.meetingName() + "】");
        lines.add("");
        if (!details.dateText().isBlank()) {
            lines.add("📅 " + details.dateText());
        }
        for (String timeLine : details.timeLines()) {
            lines.add("⏰ " + timeLine);
        }
        if (!details.venue().isBlank()) {
            lines.add("📍 " + details.venue());
        }
        if (!details.meetingCode().isBlank()) {
            lines.add("💻 会议 ID: " + details.meetingCode());
        }
        if (!details.passcode().isBlank()) {
            lines.add("密码: " + details.passcode());
        }
        if (meetingUrl != null && !meetingUrl.isBlank()) {
            lines.add(meetingUrl.trim());
        }
        lines.add("");
        lines.add("请各位领导及同事提前安排时间准时参会，谢谢 🙏🏻");
        return String.join("\n", lines);
    }

    public List<String> resolveDeliveryRecipients(List<String> selectedRecipients) {
        if (testMode) {
            log.info("[MeetingNotificationService] test mode delivery, selectedCount=1");
            return List.of(testRecipient);
        }
        return selectedRecipients;
    }

    // ── pure classification (testable) ───────────────────────────────────────

    static NotificationPlan classify(String meetingName, String meetingTime,
                                     List<NameMatchService.MatchResult> matches, String teamsDomain) {
        List<Recipient> teams = new ArrayList<>();
        List<String> nonTeams = new ArrayList<>();
        List<String> unmatched = new ArrayList<>();
        String suffix = "@" + (teamsDomain == null ? "" : teamsDomain.toLowerCase(Locale.ROOT));
        for (NameMatchService.MatchResult m : matches) {
            if (m.status() == NameMatchService.Status.MATCHED && m.user() != null) {
                String email = m.user().getEmail();
                if (email != null && email.toLowerCase(Locale.ROOT).endsWith(suffix)) {
                    String teamsAccount = m.user().getMicrosoftId() == null || m.user().getMicrosoftId().isBlank()
                            ? email
                            : m.user().getMicrosoftId();
                    teams.add(new Recipient(m.scheduleName(), m.user().getPersonName(), email, teamsAccount));
                } else {
                    nonTeams.add(m.scheduleName() + (email != null ? "(" + email + ")" : "(无邮箱)"));
                }
            } else {
                unmatched.add(m.scheduleName() + "(" + m.status() + ")");
            }
        }
        return new NotificationPlan(meetingName, meetingTime, teams, nonTeams, unmatched);
    }
}
