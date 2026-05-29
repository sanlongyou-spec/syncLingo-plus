package com.si.backend.service;

import com.si.backend.common.BizException;
import com.si.backend.common.Constants;
import com.si.backend.config.TeamsBotProperties;
import com.si.backend.dto.TeamsBotQueryRequest;
import com.si.backend.entity.InterpretationSession;
import com.si.backend.entity.SiUser;
import com.si.backend.integration.LlmIntegration;
import com.si.backend.mapper.UserMapper;
import com.si.backend.config.CostRatesProperties;
import com.si.backend.entity.MeetingActionItem;
import com.si.backend.mapper.InterpretationSessionMapper;
import com.si.backend.mapper.MeetingActionItemMapper;
import com.si.backend.vo.TeamsBotQueryResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Map;
import java.util.Set;
import java.time.YearMonth;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Query service for Teams Bot commands against meeting history.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TeamsBotQueryService {

    private static final String COMMAND_HELP = "help";
    private static final String COMMAND_LIST = "list";
    private static final String COMMAND_SEARCH = "search";
    private static final String COMMAND_SUMMARY = "summary";
    private static final String DEFAULT_TITLE = "未命名会议";
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final InterpretationSessionService sessionService;
    private final UserMapper userMapper;
    private final TeamsBotProperties teamsBotProperties;
    private final PreMeetingService preMeetingService;
    private final LlmIntegration llmIntegration;
    private final MeetingActionItemMapper actionItemMapper;
    private final InterpretationSessionMapper sessionMapper;
    private final CostRatesProperties costRatesProperties;

    public TeamsBotQueryResponse query(TeamsBotQueryRequest request, String apiSecret) {
        String message = normalize(request.getMessage());
        BotCommand command = parseCommand(message);
        log.info("[TeamsBotQueryService] query start, aadId={}, command={}, messageLen={}",
                request.getAadId(), command.name(), message.length());
        ensureAuthorized(apiSecret);

        if (COMMAND_HELP.equals(command.name())) {
            return buildResponse(helpText(), false, null, command.name());
        }

        SiUser user = resolveUser(request);
        if (user == null) {
            log.info("[TeamsBotQueryService] query user not matched, aadId={}, mail={}, upn={}",
                    request.getAadId(), request.getMail(), request.getUserPrincipalName());
            return buildResponse(noBindingText(request), false, null, command.name());
        }

        String replyText = switch (command.name()) {
            case COMMAND_LIST -> listSessions(user.getId());
            case COMMAND_SUMMARY -> summarize(user.getId(), command.argument());
            case COMMAND_SEARCH -> search(user.getId(), command.argument(), false);
            default -> helpText();
        };
        log.info("[TeamsBotQueryService] query end, userId={}, command={}", user.getId(), command.name());
        return buildResponse(replyText, true, user.getId(), command.name());
    }

    public void queryStream(TeamsBotQueryRequest request, String apiSecret, SseEmitter emitter) {
        String message = normalize(request.getMessage());
        log.info("[TeamsBotQueryService] queryStream start, aadId={}, messageLen={}",
                request.getAadId(), message.length());
        ensureAuthorized(apiSecret);

        SiUser user = resolveUser(request);
        if (user == null) {
            log.info("[TeamsBotQueryService] queryStream user not matched, aadId={}", request.getAadId());
            sendSseAndComplete(emitter, noBindingText(request));
            return;
        }

        try {
            // B2: 从自然语言中提取过滤维度（发言人、会议标题关键词、时间范围）
            PreMeetingService.QuestionFilter filter = extractQuestionFilter(message);
            log.info("[TeamsBotQueryService] queryStream filter, speakerName={}, since={}",
                    filter.speakerName(), filter.since());

            // B3: 带过滤参数的向量检索上下文构建
            String ragContext = preMeetingService.buildUnifiedContext(user.getId(), message, filter);

            // 行动项上下文（按关键词触发）
            String actionContext = buildActionItemsContext(user.getId(), message);

            // 成本数据上下文（按关键词触发）
            String costContext = buildCostContext(user.getId(), message);

            String context = ragContext
                    + (actionContext.isBlank() ? "" : "\n" + actionContext)
                    + (costContext.isBlank() ? "" : "\n" + costContext);

            if (context.isBlank()) {
                String noDataReply = "在所有历史会议记录中，未找到与该问题相关的内容。请尝试换一个关键词。";
                sendSseAndComplete(emitter, noDataReply);
                return;
            }
            llmIntegration.streamChatUnified(context, message, List.of(), chunk -> {
                try {
                    emitter.send(SseEmitter.event().data(chunk));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
            emitter.send(SseEmitter.event().data("[DONE]"));
            emitter.complete();
            log.info("[TeamsBotQueryService] queryStream done, userId={}", user.getId());
        } catch (UncheckedIOException | IOException e) {
            log.warn("[TeamsBotQueryService] queryStream emitter write failed", e);
            emitter.completeWithError(e);
        } catch (Exception e) {
            log.error("[TeamsBotQueryService] queryStream failed, userId={}", user.getId(), e);
            try {
                emitter.send(SseEmitter.event().data("[ERROR]"));
            } catch (IOException ignored) {
            }
            emitter.completeWithError(e);
        }
    }

    /**
     * B1: 意图检测 — 判断消息是否已知指令。
     * 已知指令由 parseCommand() 路由；未知的自然语言问题走向量 RAG 路径。
     */
    public boolean isKnownCommand(String message) {
        String lower = normalize(message).toLowerCase(Locale.ROOT);
        return lower.isBlank()
                || isHelpCommand(lower)
                || isListCommand(lower)
                || argumentAfterPrefix(message, lower, List.of("摘要", "总结", "纪要", "summary")).isPresent()
                || argumentAfterPrefix(message, lower, List.of("搜索", "查", "查询", "search")).isPresent()
                || isSessionId(message);
    }

    /**
     * B2: 从自然语言问题中提取过滤维度（发言人姓名、时间范围）。
     * 规则优先，无法匹配时返回空过滤器。
     */
    PreMeetingService.QuestionFilter extractQuestionFilter(String question) {
        String speakerName = extractSpeakerName(question);
        String since = extractSinceDate(question);
        return new PreMeetingService.QuestionFilter(null, speakerName, since);
    }

    private static final Pattern SPEAKER_PATTERN = Pattern.compile(
            "(?:关于|关于|(?:是)?([\\p{IsHan}]{2,4})(?:说|提到|讲|谈|汇报|表示|提出|指出|强调))");
    private static final Pattern SPEAKER_PLAIN = Pattern.compile(
            "^([\\p{IsHan}]{2,4})(?:说|提到|讲|谈|说的|说过|汇报)");

    private String extractSpeakerName(String question) {
        Matcher m = SPEAKER_PLAIN.matcher(question.trim());
        if (m.find()) return m.group(1);
        m = SPEAKER_PATTERN.matcher(question);
        if (m.find() && m.group(1) != null) return m.group(1);
        return null;
    }

    private static final Pattern DATE_N_DAYS = Pattern.compile("(\\d+)\\s*天前");
    private static final Pattern DATE_N_WEEKS = Pattern.compile("(\\d+)\\s*周前|上\\s*(\\d+)?\\s*周");
    private static final Pattern DATE_MONTH = Pattern.compile("上个?月|本月|这个?月");
    private static final Pattern DATE_WEEK = Pattern.compile("本周|这周|上周");
    private static final Pattern DATE_YESTERDAY = Pattern.compile("昨天|昨日");

    private String extractSinceDate(String question) {
        Matcher m = DATE_N_DAYS.matcher(question);
        if (m.find()) return LocalDate.now().minusDays(Long.parseLong(m.group(1))).toString();
        m = DATE_N_WEEKS.matcher(question);
        if (m.find()) return LocalDate.now().minusWeeks(1).toString();
        if (DATE_WEEK.matcher(question).find())
            return LocalDate.now().with(java.time.DayOfWeek.MONDAY).toString();
        if (DATE_MONTH.matcher(question).find()) {
            boolean lastMonth = question.contains("上个月") || question.contains("上月");
            return lastMonth
                    ? LocalDate.now().minusMonths(1).withDayOfMonth(1).toString()
                    : LocalDate.now().withDayOfMonth(1).toString();
        }
        if (DATE_YESTERDAY.matcher(question).find())
            return LocalDate.now().minusDays(1).toString();
        return null;
    }

    private void sendSseAndComplete(SseEmitter emitter, String text) {
        try {
            emitter.send(SseEmitter.event().data(text));
            emitter.send(SseEmitter.event().data("[DONE]"));
            emitter.complete();
        } catch (IOException e) {
            emitter.completeWithError(e);
        }
    }

    private SiUser resolveUser(TeamsBotQueryRequest request) {
        log.info("[TeamsBotQueryService] resolveUser start, aadId={}, mail={}, upn={}",
                request.getAadId(), request.getMail(), request.getUserPrincipalName());
        for (String identity : identityCandidates(request)) {
            SiUser user = userMapper.findByUsernameOrEmailIgnoreCase(identity);
            if (user != null) {
                log.info("[TeamsBotQueryService] resolveUser end, matchedBy={}, userId={}",
                        identity, user.getId());
                return user;
            }
        }

        Long defaultUserId = teamsBotProperties.getDefaultUserId();
        if (defaultUserId != null && defaultUserId > 0) {
            SiUser defaultUser = userMapper.findById(defaultUserId);
            if (defaultUser != null) {
                log.info("[TeamsBotQueryService] resolveUser end, matchedBy=defaultUserId, userId={}",
                        defaultUser.getId());
                return defaultUser;
            }
        }

        log.info("[TeamsBotQueryService] resolveUser end, matched=false");
        return null;
    }

    private void ensureAuthorized(String apiSecret) {
        String configuredSecret = normalize(teamsBotProperties.getApiSecret());
        if (configuredSecret.isBlank()) {
            log.warn("[TeamsBotQueryService] apiSecret is not configured; Teams Bot query endpoint is running without shared-secret protection.");
            return;
        }
        if (!configuredSecret.equals(apiSecret)) {
            log.warn("[TeamsBotQueryService] unauthorized Teams Bot query request.");
            throw BizException.of(Constants.HTTP_UNAUTHORIZED, "Bot 查询接口未授权");
        }
    }

    private List<String> identityCandidates(TeamsBotQueryRequest request) {
        List<String> candidates = new ArrayList<>();
        addCandidate(candidates, request.getMail());
        addCandidate(candidates, request.getUserPrincipalName());
        addCandidate(candidates, request.getAadId());

        String upn = normalize(request.getUserPrincipalName());
        int atIndex = upn.indexOf('@');
        if (atIndex > 0) {
            addCandidate(candidates, upn.substring(0, atIndex));
        }
        return candidates;
    }

    private void addCandidate(List<String> candidates, String value) {
        String normalized = normalize(value);
        if (!normalized.isBlank() && candidates.stream().noneMatch(item -> item.equalsIgnoreCase(normalized))) {
            candidates.add(normalized);
        }
    }

    private BotCommand parseCommand(String message) {
        if (message.isBlank()) {
            return new BotCommand(COMMAND_HELP, "");
        }

        String lower = message.toLowerCase(Locale.ROOT);
        if (isHelpCommand(lower)) {
            return new BotCommand(COMMAND_HELP, "");
        }
        if (isListCommand(lower)) {
            return new BotCommand(COMMAND_LIST, "");
        }

        Optional<String> summaryArgument = argumentAfterPrefix(message, lower,
                List.of("摘要", "总结", "纪要", "summary"));
        if (summaryArgument.isPresent()) {
            return new BotCommand(COMMAND_SUMMARY, summaryArgument.get());
        }

        Optional<String> searchArgument = argumentAfterPrefix(message, lower,
                List.of("搜索", "查", "查询", "search"));
        if (searchArgument.isPresent()) {
            return new BotCommand(COMMAND_SEARCH, searchArgument.get());
        }

        if (isSessionId(message)) {
            return new BotCommand(COMMAND_SUMMARY, message);
        }
        return new BotCommand(COMMAND_SEARCH, message);
    }

    private boolean isHelpCommand(String lower) {
        return List.of("help", "hi", "hello", "帮助", "菜单", "说明", "？", "?",
                "你可以做什么", "能做什么", "有什么功能", "怎么用", "使用说明", "使用帮助",
                "查看纪要", "查看摘要", "查看总结").contains(lower);
    }

    private boolean isListCommand(String lower) {
        return List.of("最近", "最近会议", "历史", "历史会议", "会议记录", "list", "history",
                "查看最近", "查看历史", "查看最近会议", "查看历史会议").contains(lower);
    }

    private Optional<String> argumentAfterPrefix(String original, String lower, List<String> prefixes) {
        for (String prefix : prefixes) {
            String normalizedPrefix = prefix.toLowerCase(Locale.ROOT);
            if (lower.equals(normalizedPrefix)) {
                return Optional.of("");
            }
            // English: "search keyword" — split by space
            if (lower.startsWith(normalizedPrefix + " ")) {
                return Optional.of(original.substring(prefix.length()).trim());
            }
            // Chinese: "总结班长的战争..." — no space between prefix and argument.
            // Only apply for multi-char prefixes to avoid false positives (e.g. single char "查").
            if (normalizedPrefix.length() >= 2
                    && lower.length() > normalizedPrefix.length()
                    && lower.startsWith(normalizedPrefix)) {
                return Optional.of(original.substring(prefix.length()).trim());
            }
        }
        return Optional.empty();
    }

    private String listSessions(Long userId) {
        log.info("[TeamsBotQueryService] listSessions start, userId={}", userId);
        List<InterpretationSession> sessions = sessionService.searchUserSessions(userId, null).stream()
                .limit(maxSessions())
                .toList();
        String reply = sessions.isEmpty()
                ? "还没有找到你的历史会议记录。"
                : formatSessionList("最近会议", sessions);
        log.info("[TeamsBotQueryService] listSessions end, userId={}, count={}", userId, sessions.size());
        return reply;
    }

    private String search(Long userId, String keyword, boolean summaryIntent) {
        String normalizedKeyword = normalize(keyword);
        log.info("[TeamsBotQueryService] search start, userId={}, keyword={}, summaryIntent={}",
                userId, normalizedKeyword, summaryIntent);
        if (normalizedKeyword.isBlank()) {
            return listSessions(userId);
        }

        List<InterpretationSession> sessions = sessionService.searchUserSessions(userId, normalizedKeyword).stream()
                .limit(maxSessions())
                .toList();
        if (sessions.isEmpty()) {
            log.info("[TeamsBotQueryService] search end, userId={}, count=0", userId);
            return "没有找到匹配“" + normalizedKeyword + "”的会议记录。可以换一个关键词试试。";
        }

        if (summaryIntent && sessions.size() == 1) {
            return formatSummary(sessions.get(0));
        }

        log.info("[TeamsBotQueryService] search end, userId={}, count={}", userId, sessions.size());
        return formatSessionList("搜索结果：" + normalizedKeyword, sessions);
    }

    private String summarize(Long userId, String argument) {
        String normalizedArgument = normalize(argument);
        log.info("[TeamsBotQueryService] summarize start, userId={}, argument={}", userId, normalizedArgument);
        if (normalizedArgument.isBlank()) {
            return "请在“摘要”后面加会议 ID 或关键词，例如：摘要 产品评审。";
        }

        if (isSessionId(normalizedArgument)) {
            InterpretationSession session = sessionService.getSession(normalizedArgument).orElse(null);
            if (session == null || !userId.equals(session.getUserId())) {
                log.info("[TeamsBotQueryService] summarize end, userId={}, found=false", userId);
                return "没有找到这场会议，或你没有权限查看。";
            }
            log.info("[TeamsBotQueryService] summarize end, userId={}, sessionId={}",
                    userId, normalizedArgument);
            return formatSummary(session);
        }
        return search(userId, normalizedArgument, true);
    }

    private String formatSessionList(String title, List<InterpretationSession> sessions) {
        StringBuilder builder = new StringBuilder();
        builder.append(title).append("：\n\n");
        for (int i = 0; i < sessions.size(); i++) {
            InterpretationSession session = sessions.get(i);
            builder.append(i + 1)
                    .append(". ")
                    .append(titleOf(session))
                    .append("\n")
                    .append("ID: ")
                    .append(session.getSessionId())
                    .append("\n")
                    .append("时间: ")
                    .append(timeOf(session))
                    .append("\n")
                    .append("状态: ")
                    .append(nullToDash(session.getStatus()));
            if (session.getResultCount() != null) {
                builder.append("，记录 ")
                        .append(session.getResultCount())
                        .append(" 条");
            }
            builder.append("\n\n");
        }
        builder.append("回复“摘要 <ID>”查看纪要，或回复“搜索 关键词”继续查找。");
        return builder.toString();
    }

    private String formatSummary(InterpretationSession session) {
        String summary = normalize(session.getMeetingSummary());
        if (summary.isBlank()) {
            return titleOf(session) + "\n\n这场会议还没有生成摘要，可以在网页端历史记录里重新生成。";
        }

        return titleOf(session)
                + "\n"
                + "时间: " + timeOf(session)
                + "\n"
                + "ID: " + session.getSessionId()
                + "\n\n"
                + truncate(summary, Math.max(200, teamsBotProperties.getSummaryPreviewChars()));
    }

    private String helpText() {
        return """
                我可以帮你查询 syncLingo 的历史会议。

                可用指令：
                - 最近：查看最近会议
                - 搜索 关键词：按标题、ID、原文或译文搜索
                - 摘要 会议ID：查看某场会议纪要
                - 摘要 关键词：按关键词找到会议并查看纪要
                """.trim();
    }

    private String noBindingText(TeamsBotQueryRequest request) {
        String identity = firstNonBlank(request.getMail(), request.getUserPrincipalName(), request.getAadId());
        return "还没有把你的 Teams 账号绑定到 syncLingo 用户。\n\n"
                + "当前识别到的 Teams 身份: " + nullToDash(identity) + "\n"
                + "请让管理员把 syncLingo 用户表中的 email 或 username 设置为你的 Teams 邮箱/UPN。";
    }

    private TeamsBotQueryResponse buildResponse(String replyText, Boolean userMatched, Long userId, String command) {
        return TeamsBotQueryResponse.builder()
                .replyText(replyText)
                .userMatched(userMatched)
                .userId(userId)
                .command(command)
                .build();
    }

    private int maxSessions() {
        return Math.max(1, teamsBotProperties.getMaxSessions());
    }

    private boolean isSessionId(String text) {
        return UUID_PATTERN.matcher(normalize(text)).matches();
    }

    private String titleOf(InterpretationSession session) {
        String title = normalize(session.getTitle());
        return title.isBlank() ? DEFAULT_TITLE : title;
    }

    private String timeOf(InterpretationSession session) {
        if (session.getStartTime() != null) {
            return DATE_TIME_FORMATTER.format(session.getStartTime());
        }
        if (session.getCreateTime() != null) {
            return DATE_TIME_FORMATTER.format(session.getCreateTime());
        }
        return "-";
    }

    private String truncate(String text, int maxChars) {
        if (text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars) + "\n\n（内容较长，已截断）";
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            String normalized = normalize(value);
            if (!normalized.isBlank()) {
                return normalized;
            }
        }
        return "";
    }

    private String nullToDash(String value) {
        String normalized = normalize(value);
        return normalized.isBlank() ? "-" : normalized;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }


    /** Builds action-item context snippet when question is about tasks/todos. */
    private String buildActionItemsContext(Long userId, String question) {
        String lower = question.toLowerCase(Locale.ROOT);
        boolean relevant = Set.of("行动项", "待办", "任务", "负责", "跟进",
                "action", "todo", "完成", "follow").stream().anyMatch(lower::contains);
        if (!relevant) return "";
        try {
            List<MeetingActionItem> items = actionItemMapper.findRecentByUserId(userId, 30);
            if (items.isEmpty()) return "";
            StringBuilder sb = new StringBuilder("[行动项清单]\n");
            for (MeetingActionItem item : items) {
                sb.append("- [").append(item.getStatus()).append("] ");
                if (item.getAssignee() != null && !item.getAssignee().isBlank()) {
                    sb.append("【").append(item.getAssignee()).append("】");
                }
                sb.append(item.getContent());
                if (item.getDeadline() != null && !item.getDeadline().isBlank()) {
                    sb.append("（期限：").append(item.getDeadline()).append("）");
                }
                sb.append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("[TeamsBotQueryService] buildActionItemsContext failed: {}", e.getMessage());
            return "";
        }
    }

    /** Builds cost summary context when question mentions cost/budget. */
    private String buildCostContext(Long userId, String question) {
        String lower = question.toLowerCase(Locale.ROOT);
        boolean relevant = Set.of("成本", "费用", "花费", "预算", "多少钱",
                "消费", "账单", "月度", "开销", "cost", "budget").stream().anyMatch(lower::contains);
        if (!relevant) return "";
        try {
            String yearMonth = YearMonth.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM"));
            Map<String, Object> current = sessionMapper.currentMonthSummaryByUser(userId, yearMonth);
            java.util.List<Map<String, Object>> monthly = sessionMapper.monthlySummaryByUser(userId);
            if ((current == null || current.isEmpty()) && (monthly == null || monthly.isEmpty())) return "";

            double asrRate  = costRatesProperties.getRates().getAsrPerHourUsd();
            double transRate = costRatesProperties.getRates().getTransPerMillionCharsUsd();
            double ttsRate  = costRatesProperties.getRates().getTtsPerMillionCharsUsd();
            double llmIn    = costRatesProperties.getRates().getLlmInPerMillionTokensUsd();
            double llmOut   = costRatesProperties.getRates().getLlmOutPerMillionTokensUsd();

            StringBuilder sb = new StringBuilder();
            sb.append("[成本数据 - ").append(yearMonth).append("]\n");

            if (current != null && !current.isEmpty()) {
                long sessions  = toLong(current.get("sessionCount"));
                double cost    = calcMonthlyCost(current, asrRate, transRate, ttsRate, llmIn, llmOut);
                sb.append("当月（").append(yearMonth).append("）：")
                  .append(sessions).append(" 次会话，估计费用 $")
                  .append(String.format("%.4f", cost)).append("\n");
            }

            if (monthly != null && monthly.size() > 1) {
                sb.append("历史月度汇总：\n");
                int shown = 0;
                for (Map<String, Object> row : monthly) {
                    if (shown++ >= 3) break;
                    String month = String.valueOf(row.get("month"));
                    long sessions = toLong(row.get("sessionCount"));
                    double cost   = calcMonthlyCost(row, asrRate, transRate, ttsRate, llmIn, llmOut);
                    sb.append("  ").append(month).append("：").append(sessions)
                      .append(" 次会话，估计费用 $")
                      .append(String.format("%.4f", cost)).append("\n");
                }
            }

            double budget = costRatesProperties.getBudget().getMonthlyUsd();
            if (budget > 0) {
                sb.append("月度预算：$").append(String.format("%.2f", budget)).append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("[TeamsBotQueryService] buildCostContext failed: {}", e.getMessage());
            return "";
        }
    }

    private double calcMonthlyCost(Map<String, Object> row,
            double asrRate, double transRate, double ttsRate, double llmIn, double llmOut) {
        return toLong(row.get("totalAsrMs")) / 3_600_000.0 * asrRate
                + toLong(row.get("totalTransChars")) / 1_000_000.0 * transRate
                + toLong(row.get("totalTtsChars"))   / 1_000_000.0 * ttsRate
                + toLong(row.get("totalLlmIn"))      / 1_000_000.0 * llmIn
                + toLong(row.get("totalLlmOut"))     / 1_000_000.0 * llmOut;
    }

    private long toLong(Object val) {
        if (val == null) return 0L;
        if (val instanceof Number n) return n.longValue();
        try { return Long.parseLong(val.toString()); } catch (Exception e) { return 0L; }
    }

    private record BotCommand(String name, String argument) {
    }
}
