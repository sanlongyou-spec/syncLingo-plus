package com.si.backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Batch-2 orchestration for meeting notifications:
 * 会议安排 名单 → 按国籍匹配用户表(NameMatchService) → 取邮箱 → @{teams-domain} 判定 Teams 用户 →
 * 自动发会议通知卡片(测试期只发 test-recipient)。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MeetingNotificationService {

    /** A matched Teams recipient: 会议安排原始名 / 系统账号名 / 邮箱. */
    public record Recipient(String scheduleName, String accountName, String email) {}

    /** Outcome of matching a 会议安排 name list against the directory. */
    public record NotificationPlan(
            String meetingName,
            String meetingTime,
            List<Recipient> teamsRecipients,   // jlg.co.id Teams users to notify (name + account + email)
            List<String> nonTeamsSkipped,      // matched but not a Teams user
            List<String> unmatched) {}         // ambiguous / not found (need人工)

    @Value("${notification.teams-domain:jlg.co.id}")
    private String teamsDomain;
    @Value("${notification.test-mode:true}")
    private boolean testMode;
    @Value("${notification.test-recipient:yousanlong@jlg.co.id}")
    private String testRecipient;
    @Value("${bot.api.url:http://localhost:3978}")
    private String botApiUrl;

    private final NameMatchService nameMatchService;

    /** Build the notification plan from the schedule's parsed name list. */
    public NotificationPlan buildPlan(String meetingName, String meetingTime, List<String> scheduleNames) {
        return classify(meetingName, meetingTime, nameMatchService.match(scheduleNames), teamsDomain);
    }

    /**
     * Send the meeting notification card to the plan's Teams recipients (or the test recipient in test
     * mode) via the bot. Best-effort: failures are logged, never thrown.
     */
    public void send(NotificationPlan plan, String meetingUrl) {
        List<String> recipients = testMode
                ? List.of(testRecipient)
                : plan.teamsRecipients().stream().map(Recipient::email).toList();
        if (recipients.isEmpty()) {
            log.info("[MeetingNotificationService] no recipients to notify, meeting={}", plan.meetingName());
            return;
        }
        String botBase = botApiUrl;
        if (botBase == null || botBase.isBlank()) {
            log.warn("[MeetingNotificationService] bot api url not configured; would notify {} about {}",
                    recipients, plan.meetingName());
            return;
        }
        try {
            String body = buildJson(plan.meetingName(), plan.meetingTime(), meetingUrl, recipients);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(botBase.replaceAll("/+$", "") + "/api/meetings/notification"))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(body, java.nio.charset.StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = HttpClient.newHttpClient()
                    .send(req, HttpResponse.BodyHandlers.ofString());
            log.info("[MeetingNotificationService] notification sent, meeting={}, recipients={}, botStatus={}",
                    plan.meetingName(), recipients.size(), resp.statusCode());
        } catch (Exception e) {
            log.warn("[MeetingNotificationService] notification send failed, meeting={}: {}",
                    plan.meetingName(), e.getMessage());
        }
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
                    teams.add(new Recipient(m.scheduleName(), m.user().getPersonName(), email));
                } else {
                    nonTeams.add(m.scheduleName() + (email != null ? "(" + email + ")" : "(无邮箱)"));
                }
            } else {
                unmatched.add(m.scheduleName() + "(" + m.status() + ")");
            }
        }
        return new NotificationPlan(meetingName, meetingTime, teams, nonTeams, unmatched);
    }

    private static String buildJson(String name, String time, String url, List<String> recipients) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"meetingName\":").append(jsonStr(name)).append(',');
        sb.append("\"meetingTime\":").append(jsonStr(time)).append(',');
        sb.append("\"meetingUrl\":").append(jsonStr(url)).append(',');
        sb.append("\"recipients\":[");
        for (int i = 0; i < recipients.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(jsonStr(recipients.get(i)));
        }
        sb.append("]}");
        return sb.toString();
    }

    private static String jsonStr(String s) {
        if (s == null) return "\"\"";
        return '"' + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + '"';
    }
}
