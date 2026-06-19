package com.si.backend.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies multi-time-zone meeting-notice parsing and notification draft generation.
 */
class MeetingNoticeParserTest {

    private static final String NOTICE_TEXT = """
            TBM（未成熟）专项会议
            会议时间：2026 年 06 月 10 日（周三）西五区 UTC-5 时间上午 07:30-09:40
            2026 年 06 月 10 日（周三）东七区 UTC+7 时间晚上 19:30-21:40
            2026 年 06 月 10 日（周三）东八区 UTC+8 时间晚上 20:30-22:40
            会议地点：Teams Meeting 连线
            ID Teams 会议号码：435 961 345 123 2 (Password 密码: dk9c5g5C)
            会议链接：https://teams.microsoft.com/l/meetup-join/example?context=test
            """;

    private static final String BRACKET_NOTICE_TEXT = """
            各位领导及同事好，现将今日的会议安排通知如下： 【《九+一》九宫格牵引《人财物 农工商 4G5G6G 环境》】
            📅 2026年6月12日（周五）
            ⏰ 西五区时间 上午 07:30-09:40
            ⏰ 东七区时间 晚上 19:30-21:40
            ⏰ 东八区时间 晚上 20:30-22:40
            📍 TEAMS 会议
            💻 会议 ID: 435 961 345 123 2 密码: dk9c5g5C
            https://teams.microsoft.com/l/meetup-join/example?context=test
            请各位领导及同事提前安排时间准时参会，谢谢 🙏🏻
            """;

    @Test
    void parsesAllTimeZonesAndTeamsCredentials() {
        MeetingNoticeParser.MeetingNoticeDetails details =
                new MeetingNoticeParser().parse(NOTICE_TEXT, "TBM（未成熟）专项会议（2026年6月10日）");

        assertEquals("TBM（未成熟）专项会议", details.meetingName());
        assertEquals("2026年6月10日（周三）", details.dateText());
        assertEquals(List.of(
                "西五区时间 上午 07:30-09:40",
                "东七区时间 晚上 19:30-21:40",
                "东八区时间 晚上 20:30-22:40"
        ), details.timeLines());
        assertEquals("TEAMS 会议", details.venue());
        assertEquals("435 961 345 123 2", details.meetingCode());
        assertEquals("dk9c5g5C", details.passcode());
        assertEquals("https://teams.microsoft.com/l/meetup-join/example?context=test", details.meetingUrl());
    }

    @Test
    void buildsEditableFullNotificationDraft() {
        MeetingNoticeParser.MeetingNoticeDetails details =
                new MeetingNoticeParser().parse(NOTICE_TEXT, "TBM（未成熟）专项会议");
        MeetingNotificationService service = new MeetingNotificationService(null);

        String content = service.buildNotificationContent(details, "https://teams.microsoft.com/example");

        assertEquals("""
                各位领导及同事好，现将今日的会议安排通知如下：
                【TBM（未成熟）专项会议】

                📅 2026年6月10日（周三）
                ⏰ 西五区时间 上午 07:30-09:40
                ⏰ 东七区时间 晚上 19:30-21:40
                ⏰ 东八区时间 晚上 20:30-22:40
                📍 TEAMS 会议
                💻 会议 ID: 435 961 345 123 2
                密码: dk9c5g5C
                https://teams.microsoft.com/example

                请各位领导及同事提前安排时间准时参会，谢谢 🙏🏻""", content);
    }

    @Test
    void buildsCleanDraftFromBracketStyleNotice() {
        MeetingNoticeParser.MeetingNoticeDetails details =
                new MeetingNoticeParser().parse(BRACKET_NOTICE_TEXT, "会议");
        MeetingNotificationService service = new MeetingNotificationService(null);

        String content = service.buildNotificationContent(details, details.meetingUrl());

        assertEquals("《九+一》九宫格牵引《人财物 农工商 4G5G6G 环境》", details.meetingName());
        assertEquals("2026年6月12日（周五）", details.dateText());
        assertEquals("435 961 345 123 2", details.meetingCode());
        assertEquals("""
                各位领导及同事好，现将今日的会议安排通知如下：
                【《九+一》九宫格牵引《人财物 农工商 4G5G6G 环境》】

                📅 2026年6月12日（周五）
                ⏰ 西五区时间 上午 07:30-09:40
                ⏰ 东七区时间 晚上 19:30-21:40
                ⏰ 东八区时间 晚上 20:30-22:40
                📍 TEAMS 会议
                💻 会议 ID: 435 961 345 123 2
                密码: dk9c5g5C
                https://teams.microsoft.com/l/meetup-join/example?context=test

                请各位领导及同事提前安排时间准时参会，谢谢 🙏🏻""", content);
    }
}
