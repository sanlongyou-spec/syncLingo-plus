package com.si.backend.service;

import com.si.backend.common.BizException;
import com.si.backend.common.Constants;
import com.si.backend.config.TeamsBotProperties;
import com.si.backend.dto.TeamsBotQueryRequest;
import com.si.backend.entity.InterpretationSession;
import com.si.backend.entity.SiUser;
import com.si.backend.integration.LlmIntegration;
import com.si.backend.mapper.UserMapper;
import com.si.backend.vo.TeamsBotQueryResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
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
            String context = preMeetingService.buildUnifiedContext(user.getId(), message);
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
        return List.of("help", "hi", "hello", "帮助", "菜单", "说明", "？", "?").contains(lower);
    }

    private boolean isListCommand(String lower) {
        return List.of("最近", "最近会议", "历史", "历史会议", "会议记录", "list", "history").contains(lower);
    }

    private Optional<String> argumentAfterPrefix(String original, String lower, List<String> prefixes) {
        for (String prefix : prefixes) {
            String normalizedPrefix = prefix.toLowerCase(Locale.ROOT);
            if (lower.equals(normalizedPrefix)) {
                return Optional.of("");
            }
            if (lower.startsWith(normalizedPrefix + " ")) {
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

    private record BotCommand(String name, String argument) {
    }
}
