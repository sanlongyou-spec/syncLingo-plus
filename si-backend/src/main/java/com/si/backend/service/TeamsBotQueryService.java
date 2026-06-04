package com.si.backend.service;

import com.si.backend.common.BizException;
import com.si.backend.common.Constants;
import com.si.backend.config.TeamsBotProperties;
import com.si.backend.dto.TeamsBotQueryRequest;
import com.si.backend.entity.InterpretationSession;
import com.si.backend.entity.Meeting;
import com.si.backend.entity.SiUser;
import com.si.backend.integration.LlmIntegration;
import com.si.backend.mapper.MeetingMapper;
import com.si.backend.mapper.PersistentPreMeetingFileMapper;
import com.si.backend.mapper.UserMapper;
import com.si.backend.config.CostRatesProperties;
import com.si.backend.entity.MeetingActionItem;
import com.si.backend.mapper.InterpretationSessionMapper;
import com.si.backend.mapper.MeetingActionItemMapper;
import com.si.backend.vo.TeamsBotQueryResponse;
import com.si.backend.vo.TeamsBotQuerySourceVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
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
    private static final String COMMAND_ASK = "ask";
    private static final String RESPONSE_TEXT = "text";
    private static final String RESPONSE_RAG = "rag";
    private static final String RESPONSE_FILE_LIST = "file_list";
    private static final String RESPONSE_MEETING_LIST = "meeting_list";
    private static final String DEFAULT_TITLE = "未命名会议";
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final Pattern QUESTION_DATE_PATTERN = Pattern.compile(
            "(\\d{4})年(\\d{1,2})月(\\d{1,2})日|(\\d{4})[-/](\\d{1,2})[-/](\\d{1,2})|(\\d{1,2})月(\\d{1,2})日");
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final int MAX_STRUCTURED_MEETINGS = 5;
    private static final int MAX_STRUCTURED_SOURCES = 10;

    private final InterpretationSessionService sessionService;
    private final UserMapper userMapper;
    private final TeamsBotProperties teamsBotProperties;
    private final PreMeetingService preMeetingService;
    private final RagEnhancementService ragEnhancementService;
    private final LlmIntegration llmIntegration;
    private final MeetingActionItemMapper actionItemMapper;
    private final InterpretationSessionMapper sessionMapper;
    private final CostRatesProperties costRatesProperties;
    private final MeetingMapper meetingMapper;
    private final PersistentPreMeetingFileMapper persistentFileMapper;

    public TeamsBotQueryResponse query(TeamsBotQueryRequest request, String apiSecret) {
        String message = normalize(request.getMessage());
        BotCommand command = parseCommand(message);
        log.info("[TeamsBotQueryService] query start, aadId={}, command={}, messageLen={}",
                request.getAadId(), command.name(), message.length());
        ensureAuthorized(apiSecret);

        if (COMMAND_HELP.equals(command.name())) {
            return buildResponse(helpText(), RESPONSE_TEXT, List.of(), false, null, command.name());
        }

        SiUser user = resolveUser(request);
        if (user == null) {
            log.info("[TeamsBotQueryService] query user not matched, aadId={}, mail={}, upn={}",
                    request.getAadId(), request.getMail(), request.getUserPrincipalName());
            return buildResponse(noBindingText(request), RESPONSE_TEXT, List.of(), false, null, command.name());
        }

        BotAnswer answer = switch (command.name()) {
            case COMMAND_LIST -> BotAnswer.text(listSessions(user.getId()), RESPONSE_MEETING_LIST);
            case COMMAND_SUMMARY -> BotAnswer.text(summarize(user.getId(), command.argument()), RESPONSE_TEXT);
            case COMMAND_SEARCH -> BotAnswer.text(search(user.getId(), command.argument(), false), RESPONSE_MEETING_LIST);
            case COMMAND_ASK -> answerNaturalQuestion(user.getId(), message);
            default -> BotAnswer.text(helpText(), RESPONSE_TEXT);
        };
        log.info("[TeamsBotQueryService] query end, userId={}, command={}", user.getId(), command.name());
        return buildResponse(answer.replyText(), answer.responseType(), answer.sources(), true, user.getId(), command.name());
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

        Optional<BotAnswer> structuredAnswer = answerStructuredQuestion(user.getId(), message);
        if (structuredAnswer.isPresent()) {
            BotAnswer answer = structuredAnswer.get();
            sendSseAndComplete(emitter, answer.replyText() + formatSourcesSuffix(answer.sources()));
            return;
        }

        try {
            // B2: 从自然语言中提取过滤维度（发言人、会议标题关键词、时间范围）
            PreMeetingService.QuestionFilter filter = extractQuestionFilter(message);
            log.info("[TeamsBotQueryService] queryStream filter, speakerName={}, since={}",
                    filter.speakerName(), filter.since());

            // B3: 带过滤参数的向量检索上下文构建
            PreMeetingService.UnifiedContextResult ragResult =
                    preMeetingService.buildUnifiedContextResult(user.getId(), message, filter);
            String ragContext = ragResult.context();

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
            String sourcesText = formatSourcesBlock(ragResult.sources());
            if (!sourcesText.isBlank()) {
                emitter.send(SseEmitter.event().data("\n\n" + sourcesText));
            }
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
    static PreMeetingService.QuestionFilter extractQuestionFilter(String question) {
        String speakerName = extractSpeakerName(question);
        String since = extractSinceDate(question);
        return new PreMeetingService.QuestionFilter(null, speakerName, since);
    }

    private static final Pattern SPEAKER_PATTERN = Pattern.compile(
            "(?:是)?([\\p{IsHan}]{2,4})(?:说|提到|讲|谈|汇报|表示|提出|指出|强调)");
    private static final Pattern SPEAKER_PLAIN = Pattern.compile(
            "^([\\p{IsHan}]{2,4})(?:说|提到|讲|谈|说的|说过|汇报)");

    private static String extractSpeakerName(String question) {
        Matcher m = SPEAKER_PLAIN.matcher(question.trim());
        if (m.find()) return m.group(1);
        m = SPEAKER_PATTERN.matcher(question);
        if (m.find() && m.group(1) != null) return m.group(1);
        return null;
    }

    private static final Pattern DATE_RECENT_DAYS = Pattern.compile("(?:最近|近)\\s*(\\d+)\\s*天");
    private static final Pattern DATE_N_DAYS = Pattern.compile("(\\d+)\\s*天前");
    private static final Pattern DATE_N_WEEKS = Pattern.compile("(\\d+)\\s*周前|上\\s*(\\d+)?\\s*周");
    private static final Pattern DATE_MONTH = Pattern.compile("上个?月|本月|这个?月");
    private static final Pattern DATE_WEEK = Pattern.compile("本周|这周|上周");
    private static final Pattern DATE_YESTERDAY = Pattern.compile("昨天|昨日");
    private static final Pattern DATE_ABS_YMD = Pattern.compile("(20\\d{2})年(\\d{1,2})月(\\d{1,2})日");
    private static final Pattern DATE_ABS_MD = Pattern.compile("(?<!\\d)(\\d{1,2})月(\\d{1,2})日");
    private static final Pattern DATE_ABS_YM = Pattern.compile("(20\\d{2})年(\\d{1,2})月(?!\\d*日)");

    private static String extractSinceDate(String question) {
        // Absolute dates first (most specific).
        Matcher m = DATE_ABS_YMD.matcher(question);
        if (m.find()) return safeDate(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
        m = DATE_ABS_MD.matcher(question);
        if (m.find()) return safeDate(LocalDate.now().getYear(), Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
        m = DATE_ABS_YM.matcher(question);
        if (m.find()) return safeDate(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), 1);

        m = DATE_RECENT_DAYS.matcher(question);
        if (m.find()) return LocalDate.now().minusDays(Long.parseLong(m.group(1))).toString();
        m = DATE_N_DAYS.matcher(question);
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

    /** Build an ISO date string, returning null for out-of-range month/day (don't filter on junk). */
    private static String safeDate(int year, int month, int day) {
        if (month < 1 || month > 12 || day < 1 || day > 31) return null;
        try {
            return LocalDate.of(year, month, day).toString();
        } catch (java.time.DateTimeException e) {
            return null;
        }
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
        return new BotCommand(COMMAND_ASK, message);
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

    private BotAnswer answerNaturalQuestion(Long userId, String question) {
        log.info("[TeamsBotQueryService] answerNaturalQuestion start, userId={}, questionLen={}", userId, question.length());
        Optional<BotAnswer> structuredAnswer = answerStructuredQuestion(userId, question);
        if (structuredAnswer.isPresent()) {
            log.info("[TeamsBotQueryService] answerNaturalQuestion structured, userId={}, responseType={}",
                    userId, structuredAnswer.get().responseType());
            return structuredAnswer.get();
        }

        PreMeetingService.QuestionFilter filter = extractQuestionFilter(question);
        PreMeetingService.UnifiedContextResult ragResult = retrieveWithAgentic(userId, question, filter);
        KnowledgeContext fallbackContext = KnowledgeContext.empty();
        if (ragResult.context().isBlank()) {
            fallbackContext = buildMeetingKnowledgeContext(userId, question);
        }
        String actionContext = buildActionItemsContext(userId, question);
        String costContext = buildCostContext(userId, question);
        String knowledgeContext = ragResult.context().isBlank() ? fallbackContext.context() : ragResult.context();
        List<TeamsBotQuerySourceVo> sources = ragResult.sources().isEmpty() ? fallbackContext.sources() : ragResult.sources();
        String context = knowledgeContext
                + (actionContext.isBlank() ? "" : "\n" + actionContext)
                + (costContext.isBlank() ? "" : "\n" + costContext);

        if (context.isBlank()) {
            log.info("[TeamsBotQueryService] answerNaturalQuestion no context, userId={}", userId);
            return BotAnswer.text("在所有历史会议记录中，未找到与该问题相关的内容。请尝试换一个关键词，或先确认会议/文件已经上传并完成索引。", RESPONSE_RAG);
        }

        try {
            String answer = llmIntegration.chatCrossMeeting(context, question, List.of());
            log.info("[TeamsBotQueryService] answerNaturalQuestion done, userId={}, sources={}",
                    userId, sources.size());
            return new BotAnswer(answer, RESPONSE_RAG, sources);
        } catch (IOException e) {
            log.error("[TeamsBotQueryService] answerNaturalQuestion llm failed, userId={}", userId, e);
            return BotAnswer.text("已找到相关会议内容，但生成回答时遇到 LLM 错误：" + e.getMessage(), RESPONSE_RAG);
        }
    }

    /**
     * P2-8: agentic iterative retrieval. Runs one retrieval pass, then (if enabled) asks the LLM
     * whether the context is sufficient; if not, retrieves a follow-up query and merges results,
     * up to {@code max-steps}. When agentic is off, this is a single normal retrieval pass.
     */
    private PreMeetingService.UnifiedContextResult retrieveWithAgentic(
            Long userId, String question, PreMeetingService.QuestionFilter filter) {
        PreMeetingService.UnifiedContextResult first =
                preMeetingService.buildUnifiedContextResult(userId, question, filter);
        if (!ragEnhancementService.isAgenticEnabled()) {
            return first;
        }
        StringBuilder ctx = new StringBuilder(first.context());
        LinkedHashMap<String, TeamsBotQuerySourceVo> srcs = new LinkedHashMap<>();
        for (TeamsBotQuerySourceVo s : first.sources()) srcs.putIfAbsent(sourceKey(s), s);

        int steps = ragEnhancementService.getAgenticMaxSteps();
        for (int i = 1; i < steps; i++) {
            RagEnhancementService.AgenticDecision decision =
                    ragEnhancementService.agenticFollowup(question, ctx.toString());
            if (decision.enough() || decision.nextQuery() == null) break;
            log.info("[TeamsBotQueryService] agentic step {} follow-up query: {}", i, decision.nextQuery());
            PreMeetingService.UnifiedContextResult more =
                    preMeetingService.buildUnifiedContextResult(userId, decision.nextQuery(), filter);
            if (!more.context().isBlank()) ctx.append('\n').append(more.context());
            for (TeamsBotQuerySourceVo s : more.sources()) srcs.putIfAbsent(sourceKey(s), s);
        }
        return new PreMeetingService.UnifiedContextResult(ctx.toString(), new ArrayList<>(srcs.values()));
    }

    private static String sourceKey(TeamsBotQuerySourceVo s) {
        return s.getSourceType() + "|" + s.getFileId() + "|" + s.getSessionId() + "|" + s.getSourceName();
    }

    private Optional<BotAnswer> answerStructuredQuestion(Long userId, String question) {
        if (isFileListIntent(question)) {
            return Optional.of(answerMeetingFiles(userId, question));
        }
        if (isMeetingListByDateIntent(question)) {
            LocalDate date = extractQuestionDate(question);
            if (date != null) {
                return Optional.of(answerMeetingsByDate(userId, question, date));
            }
        }
        return Optional.empty();
    }

    private boolean isFileListIntent(String question) {
        String lower = question.toLowerCase(Locale.ROOT);
        boolean mentionsFile = Set.of("文件", "文档", "资料", "报告", "附件", "file", "document")
                .stream().anyMatch(lower::contains);
        boolean asksList = Set.of("哪些", "有什么", "有哪些", "有哪", "列表", "列出", "查看", "多少", "几份", "几"
                ).stream().anyMatch(lower::contains);
        return mentionsFile && asksList;
    }

    private boolean isMeetingListByDateIntent(String question) {
        if (extractQuestionDate(question) == null) return false;
        String lower = question.toLowerCase(Locale.ROOT);
        return lower.contains("会议") && Set.of("哪些", "哪几", "列表", "列出", "查看", "有哪", "最近")
                .stream().anyMatch(lower::contains);
    }

    private BotAnswer answerMeetingFiles(Long userId, String question) {
        log.info("[TeamsBotQueryService] answerMeetingFiles start, userId={}", userId);
        List<Meeting> meetings = rankMeetingsByQuestion(userId, question).stream()
                .limit(MAX_STRUCTURED_MEETINGS)
                .toList();
        if (meetings.isEmpty()) {
            return BotAnswer.text("没有找到与这个问题匹配的会议。可以说得更具体一点，例如“2026年05月26日 班长的战争 有哪些文件”。", RESPONSE_FILE_LIST);
        }

        StringBuilder reply = new StringBuilder();
        List<TeamsBotQuerySourceVo> sources = new ArrayList<>();
        int meetingIndex = 1;
        for (Meeting meeting : meetings) {
            var files = persistentFileMapper.findByMeetingId(meeting.getId());
            if (meetingIndex == 1) {
                reply.append("匹配到的会议文件：\n\n");
            }
            reply.append(meetingIndex++)
                    .append(". ")
                    .append(meeting.getTitle())
                    .append("（")
                    .append(dateOf(meeting))
                    .append("）");
            if (files.isEmpty()) {
                reply.append("\n   暂无上传文件。\n\n");
                continue;
            }
            reply.append("\n");
            for (int i = 0; i < files.size(); i++) {
                var file = files.get(i);
                reply.append("   ")
                        .append(i + 1)
                        .append(") ")
                        .append(file.getFileName());
                if (file.getSummary() != null && !file.getSummary().isBlank()) {
                    reply.append("（已有 AI 总结）");
                }
                reply.append("\n");
                if (sources.size() < MAX_STRUCTURED_SOURCES) {
                    sources.add(TeamsBotQuerySourceVo.builder()
                            .sourceType(ContentEmbeddingService.TYPE_FILE_CONTENT)
                            .title("会议文件")
                            .meetingTitle(meeting.getTitle())
                            .meetingId(meeting.getId())
                            .fileId(file.getId())
                            .sourceName(file.getFileName())
                            .sourceDate(dateOf(meeting))
                            .snippet(file.getSummary() != null && !file.getSummary().isBlank()
                                    ? truncate(file.getSummary().replaceAll("\\s+", " "), 180)
                                    : "文件已上传，可在网页端查看或生成 AI 总结。")
                            .build());
                }
            }
            reply.append("\n");
        }
        log.info("[TeamsBotQueryService] answerMeetingFiles done, userId={}, meetings={}, sources={}",
                userId, meetings.size(), sources.size());
        return new BotAnswer(reply.toString().trim(), RESPONSE_FILE_LIST, sources);
    }

    private BotAnswer answerMeetingsByDate(Long userId, String question, LocalDate date) {
        List<Meeting> meetings = rankMeetingsByQuestion(userId, question).stream()
                .filter(meeting -> matchesDate(meeting, date))
                .limit(MAX_STRUCTURED_MEETINGS)
                .toList();
        if (meetings.isEmpty()) {
            return BotAnswer.text("没有找到 " + DATE_FORMATTER.format(date) + " 的会议。", RESPONSE_MEETING_LIST);
        }

        StringBuilder reply = new StringBuilder();
        reply.append(DATE_FORMATTER.format(date)).append(" 的会议：\n\n");
        for (int i = 0; i < meetings.size(); i++) {
            Meeting meeting = meetings.get(i);
            int fileCount = persistentFileMapper.findByMeetingId(meeting.getId()).size();
            reply.append(i + 1)
                    .append(". ")
                    .append(meeting.getTitle())
                    .append("，文件 ")
                    .append(fileCount)
                    .append(" 个\n");
        }
        return BotAnswer.text(reply.toString().trim(), RESPONSE_MEETING_LIST);
    }

    private KnowledgeContext buildMeetingKnowledgeContext(Long userId, String question) {
        List<Meeting> meetings = rankMeetingsByQuestion(userId, question).stream()
                .limit(3)
                .toList();
        if (meetings.isEmpty()) {
            return KnowledgeContext.empty();
        }

        StringBuilder context = new StringBuilder();
        List<TeamsBotQuerySourceVo> sources = new ArrayList<>();
        for (Meeting meeting : meetings) {
            context.append("[会议：")
                    .append(meeting.getTitle())
                    .append(" · ")
                    .append(dateOf(meeting))
                    .append("]\n");
            for (InterpretationSession session : sessionMapper.findByMeetingId(meeting.getId())) {
                String summary = normalize(session.getMeetingSummary());
                if (!summary.isBlank()) {
                    context.append("[会议总结]\n")
                            .append(truncateForContext(summary, 1200))
                            .append("\n");
                    addStructuredSource(sources, "会议总结", ContentEmbeddingService.TYPE_MEETING_SUMMARY,
                            meeting, null, meeting.getTitle(), summary);
                }
            }
            var files = persistentFileMapper.findByMeetingId(meeting.getId());
            for (var fileItem : files) {
                var file = persistentFileMapper.findById(fileItem.getId());
                if (file == null) continue;
                String summary = normalize(file.getSummary());
                String content = normalize(file.getFileContent());
                String sourceText = !summary.isBlank() ? summary : content;
                if (sourceText.isBlank()) continue;
                context.append("[文件：")
                        .append(file.getFileName())
                        .append("]\n")
                        .append(truncateForContext(sourceText, 1800))
                        .append("\n");
                addStructuredSource(sources,
                        !summary.isBlank() ? "文件总结" : "文件内容",
                        !summary.isBlank() ? ContentEmbeddingService.TYPE_FILE_SUMMARY : ContentEmbeddingService.TYPE_FILE_CONTENT,
                        meeting,
                        file.getId(),
                        file.getFileName(),
                        sourceText);
            }
            context.append("\n");
        }

        log.info("[TeamsBotQueryService] buildMeetingKnowledgeContext done, meetings={}, sources={}",
                meetings.size(), sources.size());
        return new KnowledgeContext(context.toString(), sources);
    }

    private void addStructuredSource(
            List<TeamsBotQuerySourceVo> sources,
            String title,
            String sourceType,
            Meeting meeting,
            Long fileId,
            String sourceName,
            String sourceText) {
        if (sources.size() >= MAX_STRUCTURED_SOURCES) return;
        sources.add(TeamsBotQuerySourceVo.builder()
                .sourceType(sourceType)
                .title(title)
                .meetingTitle(meeting.getTitle())
                .meetingId(meeting.getId())
                .fileId(fileId)
                .sourceName(sourceName)
                .sourceDate(dateOf(meeting))
                .snippet(truncate(sourceText.replaceAll("\\s+", " "), 220))
                .build());
    }

    private List<Meeting> rankMeetingsByQuestion(Long userId, String question) {
        LocalDate date = extractQuestionDate(question);
        List<String> tokens = extractQuestionTokens(question);
        return meetingMapper.findByUserId(userId).stream()
                .map(meeting -> new MeetingScore(meeting, meetingScore(meeting, date, tokens)))
                .filter(item -> item.score() > 0)
                .sorted(Comparator
                        .comparingInt(MeetingScore::score).reversed()
                        .thenComparing(item -> item.meeting().getCreateTime(), Comparator.nullsLast(Comparator.reverseOrder())))
                .map(MeetingScore::meeting)
                .toList();
    }

    private int meetingScore(Meeting meeting, LocalDate date, List<String> tokens) {
        String title = normalize(meeting.getTitle()).toLowerCase(Locale.ROOT);
        int score = 0;
        if (date != null && matchesDate(meeting, date)) {
            score += 12;
        }
        for (String token : tokens) {
            if (title.contains(token.toLowerCase(Locale.ROOT))) {
                score += token.length() >= 4 ? 4 : 2;
            }
        }
        if (tokens.isEmpty() && date == null) {
            score += 1;
        }
        return score;
    }

    private boolean matchesDate(Meeting meeting, LocalDate date) {
        if (date == null || meeting == null) return false;
        if (meeting.getScheduledTime() != null && date.equals(meeting.getScheduledTime().toLocalDate())) {
            return true;
        }
        String title = normalize(meeting.getTitle());
        String compact = String.format("%04d年%02d月%02d日", date.getYear(), date.getMonthValue(), date.getDayOfMonth());
        String loose = date.getYear() + "年" + date.getMonthValue() + "月" + date.getDayOfMonth() + "日";
        return title.contains(compact)
                || title.contains(loose)
                || title.contains(DATE_FORMATTER.format(date));
    }

    private LocalDate extractQuestionDate(String question) {
        Matcher matcher = QUESTION_DATE_PATTERN.matcher(question);
        if (!matcher.find()) return null;
        try {
            if (matcher.group(1) != null) {
                return LocalDate.of(
                        Integer.parseInt(matcher.group(1)),
                        Integer.parseInt(matcher.group(2)),
                        Integer.parseInt(matcher.group(3)));
            }
            if (matcher.group(4) != null) {
                return LocalDate.of(
                        Integer.parseInt(matcher.group(4)),
                        Integer.parseInt(matcher.group(5)),
                        Integer.parseInt(matcher.group(6)));
            }
            return LocalDate.of(
                    LocalDate.now().getYear(),
                    Integer.parseInt(matcher.group(7)),
                    Integer.parseInt(matcher.group(8)));
        } catch (Exception e) {
            log.debug("[TeamsBotQueryService] cannot parse question date from {}", question);
            return null;
        }
    }

    private List<String> extractQuestionTokens(String question) {
        String cleaned = QUESTION_DATE_PATTERN.matcher(question).replaceAll(" ");
        for (String stop : List.of(
                "有哪些文件", "有什么文件", "有哪几个文件", "会议有哪些文件", "会议有什么文件",
                "有哪些会议", "有什么会议", "文件", "文档", "资料", "报告", "附件", "会议",
                "哪些", "哪几", "什么", "查看", "列出", "列表", "里面", "里", "中", "的",
                "请", "一下", "帮我", "关于", "和", "与")) {
            cleaned = cleaned.replace(stop, " ");
        }
        String[] rawTokens = cleaned.split("[\\s,，、:：()（）【】\\[\\]《》]+");
        List<String> tokens = new ArrayList<>();
        for (String raw : rawTokens) {
            String token = normalize(raw);
            if (token.length() >= 2 && tokens.stream().noneMatch(token::equalsIgnoreCase)) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private String dateOf(Meeting meeting) {
        if (meeting.getScheduledTime() != null) {
            return DATE_FORMATTER.format(meeting.getScheduledTime().toLocalDate());
        }
        if (meeting.getCreateTime() != null) {
            return DATE_FORMATTER.format(meeting.getCreateTime().toLocalDate());
        }
        return "日期未知";
    }

    private String truncateForContext(String text, int maxChars) {
        String normalized = normalize(text);
        if (normalized.length() <= maxChars) return normalized;
        return normalized.substring(0, maxChars) + "\n（后续内容已截断）";
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

    private String formatSourcesSuffix(List<TeamsBotQuerySourceVo> sources) {
        String block = formatSourcesBlock(sources);
        return block.isBlank() ? "" : "\n\n" + block;
    }

    private String formatSourcesBlock(List<TeamsBotQuerySourceVo> sources) {
        if (sources == null || sources.isEmpty()) return "";
        StringBuilder builder = new StringBuilder("引用来源：");
        int index = 1;
        for (TeamsBotQuerySourceVo source : sources.stream().limit(5).toList()) {
            builder.append("\n")
                    .append(index++)
                    .append(". ")
                    .append(firstNonBlank(source.getSourceName(), source.getTitle(), source.getMeetingTitle()));
            if (source.getMeetingTitle() != null && !source.getMeetingTitle().isBlank()) {
                builder.append(" / ").append(source.getMeetingTitle());
            }
            if (source.getSourceDate() != null && !source.getSourceDate().isBlank()) {
                builder.append("（").append(source.getSourceDate()).append("）");
            }
            if (source.getSnippet() != null && !source.getSnippet().isBlank()) {
                builder.append("\n   ").append(truncate(source.getSnippet(), 160).replace("\n", " "));
            }
        }
        return builder.toString();
    }

    private TeamsBotQueryResponse buildResponse(
            String replyText,
            String responseType,
            List<TeamsBotQuerySourceVo> sources,
            Boolean userMatched,
            Long userId,
            String command) {
        return TeamsBotQueryResponse.builder()
                .replyText(replyText)
                .responseType(responseType)
                .sources(sources)
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

    private record BotAnswer(String replyText, String responseType, List<TeamsBotQuerySourceVo> sources) {
        static BotAnswer text(String replyText, String responseType) {
            return new BotAnswer(replyText, responseType, List.of());
        }
    }

    private record MeetingScore(Meeting meeting, int score) {
    }

    private record KnowledgeContext(String context, List<TeamsBotQuerySourceVo> sources) {
        static KnowledgeContext empty() {
            return new KnowledgeContext("", List.of());
        }
    }

    private record BotCommand(String name, String argument) {
    }
}
