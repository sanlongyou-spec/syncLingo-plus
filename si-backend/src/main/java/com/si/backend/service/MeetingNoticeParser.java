package com.si.backend.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the structured notification fields from a bilingual meeting notice.
 */
@Slf4j
@Service
public class MeetingNoticeParser {

    private static final Pattern DATE_PATTERN = Pattern.compile(
            "(20\\d{2})\\s*年\\s*(\\d{1,2})\\s*月\\s*(\\d{1,2})\\s*日\\s*[（(](周[一二三四五六日天])[）)]");
    private static final Pattern SPECIFIC_MEETING_TITLE_PATTERN = Pattern.compile(
            "([A-Za-z0-9]{1,20}\\s*[（(][^）)]{1,30}[）)]\\s*(?:专项会议|专题会议))");
    private static final Pattern TIME_ZONE_PATTERN = Pattern.compile(
            "(西五区|东七区|东八区)\\s*(?:UTC\\s*[+-]\\s*\\d{1,2})?\\s*时间\\s*"
                    + "(上午|下午|晚上|早上)?\\s*(\\d{1,2}:\\d{2})\\s*[-–—~至]\\s*(\\d{1,2}:\\d{2})",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern VENUE_PATTERN = Pattern.compile(
            "会议地点\\s*[：:]\\s*(.+?)(?=\\s+(?:ID\\s*Teams\\s*)?会议号码\\s*[：:])",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern MEETING_CODE_PATTERN = Pattern.compile(
            "(?:ID\\s*Teams\\s*)?会议号码\\s*[：:]\\s*([0-9][0-9\\s]{7,30}[0-9])",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PASSCODE_PATTERN = Pattern.compile(
            "(?:Password\\s*)?密码\\s*[：:]\\s*([A-Za-z0-9]+)",
            Pattern.CASE_INSENSITIVE);

    /**
     * Parsed fields used to build the user-editable notification draft.
     */
    public record MeetingNoticeDetails(
            String meetingName,
            String dateText,
            List<String> timeLines,
            String venue,
            String meetingCode,
            String passcode
    ) {
    }

    public MeetingNoticeDetails parse(String text, String fallbackMeetingName) {
        log.info("[MeetingNoticeParser] parse start, textLen={}, fallbackMeetingName={}",
                text == null ? 0 : text.length(), fallbackMeetingName);
        String normalized = normalize(text);
        String dateText = parseDate(normalized);
        List<String> timeLines = parseTimeLines(normalized);
        String venue = parseVenue(normalized);
        String meetingCode = findGroup(normalized, MEETING_CODE_PATTERN);
        String passcode = findGroup(normalized, PASSCODE_PATTERN);
        MeetingNoticeDetails details = new MeetingNoticeDetails(
                parseMeetingName(normalized, fallbackMeetingName),
                dateText,
                timeLines,
                venue,
                meetingCode,
                passcode
        );
        log.info("[MeetingNoticeParser] parse end, timeLineCount={}, hasVenue={}, hasMeetingCode={}, hasPasscode={}",
                timeLines.size(), !venue.isBlank(), !meetingCode.isBlank(), !passcode.isBlank());
        return details;
    }

    private String parseDate(String text) {
        Matcher matcher = DATE_PATTERN.matcher(text);
        if (!matcher.find()) {
            return "";
        }
        return "%s年%d月%d日（%s）".formatted(
                matcher.group(1),
                Integer.parseInt(matcher.group(2)),
                Integer.parseInt(matcher.group(3)),
                matcher.group(4)
        );
    }

    private List<String> parseTimeLines(String text) {
        List<String> lines = new ArrayList<>();
        Matcher matcher = TIME_ZONE_PATTERN.matcher(text);
        while (matcher.find()) {
            String period = matcher.group(2) == null ? "" : matcher.group(2) + " ";
            String line = matcher.group(1) + "时间 " + period + matcher.group(3) + "-" + matcher.group(4);
            if (!lines.contains(line)) {
                lines.add(line);
            }
        }
        return List.copyOf(lines);
    }

    private String parseVenue(String text) {
        String venue = findGroup(text, VENUE_PATTERN);
        if (venue.toLowerCase(Locale.ROOT).contains("teams")) {
            return "TEAMS 会议";
        }
        return venue;
    }

    private String findGroup(String text, Pattern pattern) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1).replaceAll("\\s+", " ").trim() : "";
    }

    private String cleanMeetingName(String name) {
        if (name == null || name.isBlank()) {
            return "会议";
        }
        return name.trim().replaceAll("（20\\d{2}年\\d{1,2}月\\d{1,2}日）$", "").trim();
    }

    private String parseMeetingName(String text, String fallbackMeetingName) {
        Matcher matcher = SPECIFIC_MEETING_TITLE_PATTERN.matcher(text);
        if (matcher.find()) {
            return matcher.group(1).replaceAll("\\s+", "").trim();
        }
        return cleanMeetingName(fallbackMeetingName);
    }

    private String normalize(String text) {
        return text == null ? "" : text
                .replace('\u00A0', ' ')
                .replace('\t', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }
}
