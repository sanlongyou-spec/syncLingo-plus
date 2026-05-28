package com.si.backend.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.si.backend.config.OpenAiProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import com.si.backend.dto.PreMeetingChatRequest.ChatTurn;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * OpenAI LLM integration for real-time Indonesian compression and meeting summaries.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LlmIntegration {

    private static final String INDONESIAN_COMPRESSION_PROMPT_TEMPLATE =
            "You are a simultaneous interpretation compression model.\n\n"
            + "Task: Compress the already-translated Indonesian text into a concise real-time interpretation version.\n\n"
            + "[Input Rules]\n"
            + "- The input text is already in Indonesian. Do NOT translate again.\n"
            + "- Preserve all proper nouns unchanged.\n\n"
            + "[Strict Constraints]\n"
            + "1. Do NOT rephrase or rewrite the original meaning.\n"
            + "2. Do NOT add any information not present in the input.\n"
            + "3. Do NOT add explanations, summaries, or conclusions.\n"
            + "4. Do NOT add emotional or dramatic expressions.\n"
            + "5. Delete podcast promotion, sponsorship, subscription, rating, and call-to-action text.\n"
            + "6. Do not change tone or expression style.\n\n"
            + "[Compression Rules]\n"
            + "- Only delete fillers, repetition, weak modifiers, and non-essential promotional wording.\n"
            + "- Preserve all facts, actions, entities, numbers, and results.\n"
            + "- Keep the original order.\n"
            + "- Target length: keep about %s of the original length when possible.\n\n"
            + "Output only the compressed text, no explanation.";

    private static final String ENGLISH_COMPRESSION_PROMPT_TEMPLATE =
            "You are a simultaneous interpretation compression model.\n\n"
            + "Task: Compress the already-translated English text into a concise real-time interpretation version.\n\n"
            + "[Input Rules]\n"
            + "- The input text is already in English. Do NOT translate again.\n"
            + "- Preserve all proper nouns unchanged.\n\n"
            + "[Strict Constraints]\n"
            + "1. Do NOT rephrase or rewrite the original meaning.\n"
            + "2. Do NOT add any information not present in the input.\n"
            + "3. Do NOT add explanations, summaries, or conclusions.\n"
            + "4. Keep factual details, entities, numbers, and results intact.\n"
            + "5. Do not switch languages.\n\n"
            + "[Compression Rules]\n"
            + "- Only delete fillers, repetition, weak modifiers, and redundant wording.\n"
            + "- Preserve sentence order and the speaker's intent.\n"
            + "- Target length: keep about %s of the original length when possible.\n\n"
            + "Output only the compressed English text, no explanation.";

    private static final String MEETING_SUMMARY_SYSTEM_PROMPT =
            "You are a meeting minutes assistant. Generate concise Chinese minutes from bilingual or trilingual transcripts.\n"
            + "Return only these sections:\n"
            + "1. 会议摘要\n"
            + "2. 重点事项\n"
            + "3. 待办事项\n"
            + "Keep proper nouns unchanged.";

    private static final String HOTWORD_EXTRACTION_SYSTEM_PROMPT =
            "You are an NLP assistant. Extract named entities and domain-specific terms from the meeting transcript that would benefit ASR speech recognition accuracy.\n"
            + "Return a JSON array where each element has exactly three string fields:\n"
            + "  phrase: the exact term as it appears in the text\n"
            + "  category: one of 人名 / 地名 / 组织名 / 专业术语\n"
            + "  language: one of zh-CN / id-ID / en-US (the language the term naturally belongs to)\n"
            + "Rules: maximum 30 items; skip common words and stop words; proper nouns and domain terms only.\n"
            + "Output ONLY a valid JSON array with no explanation or markdown fences.";

    private static final String DOCUMENT_SUMMARY_SYSTEM_PROMPT =
            "你是一个专业的报告分析助手。请对以下报告内容进行结构化总结。\n"
            + "总结应包含以下部分：\n"
            + "1. 报告概要\n"
            + "2. 核心内容\n"
            + "3. 重点结论\n"
            + "4. 关键数据与事实\n"
            + "请使用中文输出，保留专有名词、数字和数据原样。";

    private static final String CHAT_SYSTEM_PROMPT =
            "你是一个专业的会议助手，能够基于提供的参考资料（会议文件或同传记录）回答用户问题。\n"
            + "规则：\n"
            + "1. 只基于参考资料中的内容作答，不要编造资料中没有的信息。\n"
            + "2. 如果参考资料中找不到答案，直接说明【资料中未提及】。\n"
            + "3. 回答简洁、准确，保留原文中的专有名词、数字和数据。\n"
            + "4. 使用中文回答。";

    private static final String CROSS_MEETING_SYSTEM_PROMPT =
            "你是一个会议记录检索助手。用户的问题需要在多场历史会议的同传记录中查找答案。\n"
            + "规则：\n"
            + "1. 只基于提供的会议记录内容回答，不要编造记录中没有的信息。\n"
            + "2. 如果多场会议都有相关内容，按会议逐一列出并对比。\n"
            + "3. 如果找不到相关内容，直接说明【未找到相关记录】。\n"
            + "4. 回答结构清晰，保留原文中的专有名词、数字和数据。\n"
            + "5. 使用中文回答。";

    private static final String MATERIAL_SUMMARY_SYSTEM_PROMPT =
            "You are an executive meeting minutes assistant. Use the prepared agenda, reports, and live transcript together.\n"
            + "Write in polished Chinese. Preserve names, numbers, project names, regions, and bilingual terms.\n"
            + "Prioritize executive speeches when the executive name list is provided.\n"
            + "Do not invent facts not supported by the material or transcript.\n"
            + "Return only these sections:\n"
            + "1. 会议基本信息\n"
            + "2. 议程完成情况\n"
            + "3. 报告要点汇总\n"
            + "4. 高管发言摘要\n"
            + "5. 决议与结论\n"
            + "6. 待办事项与责任人\n"
            + "7. 风险、问题与后续跟进";

    private static final HttpClient STREAM_HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final OpenAiProperties openAiProperties;

    /**
     * Compresses Indonesian translated text through OpenAI chat/completions API.
     *
     * @param text Indonesian text to compress
     * @return compressed Indonesian text
     * @throws IOException when OpenAI does not return usable text
     */
    public String compressIndonesian(String text) throws IOException {
        return compress(text, "zh->id", buildPrompt(
                INDONESIAN_COMPRESSION_PROMPT_TEMPLATE,
                openAiProperties.getCompressionZhToIdTargetRatio()
        ));
    }

    public String compressEnglish(String text) throws IOException {
        return compress(text, "zh->en", buildPrompt(
                ENGLISH_COMPRESSION_PROMPT_TEMPLATE,
                openAiProperties.getCompressionZhToEnTargetRatio()
        ));
    }

    private String compress(String text, String direction, String prompt) throws IOException {
        log.info("[LlmIntegration] compress start, direction={}, model={}, textLen={}",
                direction, openAiProperties.getCompressionModel(), text != null ? text.length() : 0);
        String result = createTextResponse(
                openAiProperties.getCompressionModel(),
                prompt,
                text,
                openAiProperties.getCompressionMaxOutputTokens()
        );
        log.info("[LlmIntegration] compress end, direction={}, originalLen={}, compressedLen={}",
                direction, text != null ? text.length() : 0, result.length());
        return result;
    }

    private String buildPrompt(String template, double targetRatio) {
        return String.format(template, String.format("%.0f%%", targetRatio * 100));
    }

    /**
     * Generates Chinese meeting summary through OpenAI Responses API.
     *
     * @param text meeting transcript text
     * @return meeting summary
     * @throws IOException when OpenAI does not return usable text
     */
    public String extractHotwordsJson(String text) throws IOException {
        log.info("[LlmIntegration] extractHotwordsJson start, model={}, textLen={}",
                openAiProperties.getSummaryModel(), text != null ? text.length() : 0);
        String result = createTextResponse(
                openAiProperties.getSummaryModel(),
                HOTWORD_EXTRACTION_SYSTEM_PROMPT,
                text,
                800L
        );
        log.info("[LlmIntegration] extractHotwordsJson end, resultLen={}", result.length());
        return result;
    }

    public String summarizeDocument(String text, String requirements) throws IOException {
        log.info("[LlmIntegration] summarizeDocument start, model={}, textLen={}, hasRequirements={}",
                openAiProperties.getDocumentSummaryModel(), text != null ? text.length() : 0,
                requirements != null && !requirements.isBlank());
        String systemPrompt = (requirements != null && !requirements.isBlank())
                ? DOCUMENT_SUMMARY_SYSTEM_PROMPT + "\n\n[额外要求]\n" + requirements.trim()
                : DOCUMENT_SUMMARY_SYSTEM_PROMPT;
        String result = createTextResponse(
                openAiProperties.getDocumentSummaryModel(),
                systemPrompt,
                text,
                openAiProperties.getDocumentSummaryMaxOutputTokens()
        );
        log.info("[LlmIntegration] summarizeDocument end, textLen={}, resultLen={}",
                text != null ? text.length() : 0, result.length());
        return result;
    }

    public String summarizeMeeting(String text) throws IOException {
        return summarizeMeeting(text, null);
    }

    public String summarizeMeeting(String text, String customRequirements) throws IOException {
        log.info("[LlmIntegration] summarizeMeeting start, model={}, textLen={}, hasCustomRequirements={}",
                openAiProperties.getSummaryModel(), text != null ? text.length() : 0,
                customRequirements != null && !customRequirements.isBlank());
        String systemPrompt = (customRequirements != null && !customRequirements.isBlank())
                ? MEETING_SUMMARY_SYSTEM_PROMPT + "\n\n[用户额外要求]\n" + customRequirements.trim()
                : MEETING_SUMMARY_SYSTEM_PROMPT;
        String result = createTextResponse(
                openAiProperties.getSummaryModel(),
                systemPrompt,
                text,
                openAiProperties.getSummaryMaxOutputTokens()
        );
        log.info("[LlmIntegration] summarizeMeeting end, textLen={}, resultLen={}",
                text != null ? text.length() : 0, result.length());
        return result;
    }

    public String summarizeMeetingRetry(String text) throws IOException {
        String systemPrompt = "你是专业会议秘书，负责整理正式商务会议纪要。\n"
                + "以下内容是真实商务会议的同声传译文本记录（含中文、英语或印尼语），请提炼结构化中文纪要。\n"
                + "按以下格式输出：\n"
                + "## 会议摘要\n（简要概述会议内容）\n"
                + "## 重点事项\n（逐条列出关键议题与结论）\n"
                + "## 待办事项\n（列出需要跟进的行动项）\n"
                + "保留专有名词和数字原样，只输出纪要正文，无需解释。";
        String userMessage = "【商务会议同声传译记录】\n\n" + text;
        log.info("[LlmIntegration] summarizeMeetingRetry start, textLen={}", text != null ? text.length() : 0);
        String result = createTextResponse(
                openAiProperties.getSummaryModel(),
                systemPrompt,
                userMessage,
                openAiProperties.getSummaryMaxOutputTokens()
        );
        log.info("[LlmIntegration] summarizeMeetingRetry end, resultLen={}", result.length());
        return result;
    }

    public String summarizeMeetingWithMaterials(
            String transcript,
            String agendaText,
            String reportText,
            String executiveNames
    ) throws IOException {
        String input = "[会议安排]\n" + nullToBlank(agendaText)
                + "\n\n[会议报告]\n" + nullToBlank(reportText)
                + "\n\n[高管/重点发言人名单]\n" + nullToBlank(executiveNames)
                + "\n\n[会议实时文本记录]\n" + nullToBlank(transcript);
        log.info("[LlmIntegration] summarizeMeetingWithMaterials start, model={}, inputLen={}",
                openAiProperties.getSummaryModel(), input.length());
        String result = createTextResponse(
                openAiProperties.getSummaryModel(),
                MATERIAL_SUMMARY_SYSTEM_PROMPT,
                input,
                openAiProperties.getSummaryMaxOutputTokens()
        );
        log.info("[LlmIntegration] summarizeMeetingWithMaterials end, inputLen={}, resultLen={}",
                input.length(), result.length());
        return result;
    }

    public String summarizeSpeakerSegment(String speakerName, String text) throws IOException {
        return summarizeSpeakerSegment(speakerName, text, null);
    }

    public String summarizeSpeakerSegment(String speakerName, String text, String requirements) throws IOException {
        String systemPrompt = "你是会议纪要助手。针对下面这段发言内容生成结构化中文摘要。\n"
                + "第一行输出「标题：」加上简短的发言主题标题（不超过10个字）。\n"
                + "然后空一行，根据内容要点逐条列出，每条以「•」开头。\n"
                + "要点数量根据内容自然决定，不强制数量限制，完整覆盖发言要点即可。\n"
                + "保留专有名词、数字、决议和行动项。\n"
                + "只输出标题行和要点列表，不要其他说明。"
                + (requirements != null && !requirements.isBlank() ? "\n额外要求：" + requirements : "");
        String userMessage = "发言人：" + speakerName + "\n\n内容：\n" + text;
        log.info("[LlmIntegration] summarizeSpeakerSegment start, speaker={}, textLen={}, hasReq={}", speakerName, text.length(), requirements != null && !requirements.isBlank());
        String result = createTextResponse(
                openAiProperties.getDocumentSummaryModel(),
                systemPrompt,
                userMessage,
                1200L
        );
        log.info("[LlmIntegration] summarizeSpeakerSegment end, resultLen={}", result.length());
        return result;
    }

    public String summarizeSpeakerSegmentRetry(String speakerName, String text) throws IOException {
        String systemPrompt = "你是专业会议秘书，负责整理正式业务会议的发言要点。"
                + "以下内容来自真实会议的中文发言记录，请提炼为结构化摘要。\n"
                + "输出格式（严格遵守）：\n"
                + "第一行：「标题：」加发言主题（不超过10字）\n"
                + "第二行起：用「•」逐条列出核心观点、数据、决议和行动项\n"
                + "只输出标题行与要点，不要前言、解释或评论。";
        String userMessage = "【会议发言人】" + speakerName + "\n\n【发言记录】\n" + text;
        log.info("[LlmIntegration] summarizeSpeakerSegmentRetry start, speaker={}, textLen={}", speakerName, text.length());
        String result = createTextResponse(
                openAiProperties.getDocumentSummaryModel(),
                systemPrompt,
                userMessage,
                1200L
        );
        log.info("[LlmIntegration] summarizeSpeakerSegmentRetry end, resultLen={}", result.length());
        return result;
    }

    public String chat(String context, String question, List<ChatTurn> history) throws IOException {
        log.info("[LlmIntegration] chat start, model={}, contextLen={}, historySize={}",
                openAiProperties.getDocumentSummaryModel(),
                context != null ? context.length() : 0,
                history != null ? history.size() : 0);

        StringBuilder input = new StringBuilder();
        if (context != null && !context.isBlank()) {
            input.append("[参考资料]\n").append(context).append("\n\n");
        }
        if (history != null) {
            for (ChatTurn turn : history) {
                if ("user".equals(turn.getRole())) {
                    input.append("用户：").append(turn.getContent()).append("\n");
                } else {
                    input.append("助手：").append(turn.getContent()).append("\n");
                }
            }
        }
        input.append("用户：").append(question);

        String result = createTextResponse(
                openAiProperties.getDocumentSummaryModel(),
                CHAT_SYSTEM_PROMPT,
                input.toString(),
                openAiProperties.getDocumentSummaryMaxOutputTokens()
        );
        log.info("[LlmIntegration] chat end, answerLen={}", result.length());
        return result;
    }

    public String chatCrossMeeting(String context, String question, List<ChatTurn> history) throws IOException {
        log.info("[LlmIntegration] chatCrossMeeting start, model={}, contextLen={}, historySize={}",
                openAiProperties.getDocumentSummaryModel(),
                context != null ? context.length() : 0,
                history != null ? history.size() : 0);

        StringBuilder input = new StringBuilder();
        if (context != null && !context.isBlank()) {
            input.append("[历史会议记录]\n").append(context).append("\n\n");
        }
        if (history != null) {
            for (ChatTurn turn : history) {
                if ("user".equals(turn.getRole())) {
                    input.append("用户：").append(turn.getContent()).append("\n");
                } else {
                    input.append("助手：").append(turn.getContent()).append("\n");
                }
            }
        }
        input.append("用户：").append(question);

        String result = createTextResponse(
                openAiProperties.getDocumentSummaryModel(),
                CROSS_MEETING_SYSTEM_PROMPT,
                input.toString(),
                openAiProperties.getDocumentSummaryMaxOutputTokens()
        );
        log.info("[LlmIntegration] chatCrossMeeting end, answerLen={}", result.length());
        return result;
    }

    public void streamChatUnified(
            String context,
            String question,
            List<ChatTurn> history,
            Consumer<String> chunkConsumer) throws IOException {
        log.info("[LlmIntegration] streamChatUnified start, model={}, contextLen={}, historySize={}",
                openAiProperties.getDocumentSummaryModel(),
                context != null ? context.length() : 0,
                history != null ? history.size() : 0);

        StringBuilder inputBuilder = new StringBuilder();
        if (context != null && !context.isBlank()) {
            inputBuilder.append("[历史会议记录]\n").append(context).append("\n\n");
        }
        if (history != null) {
            for (ChatTurn turn : history) {
                inputBuilder.append("user".equals(turn.getRole()) ? "用户：" : "助手：")
                        .append(turn.getContent()).append("\n");
            }
        }
        inputBuilder.append("用户：").append(question);

        String requestBodyJson = buildStreamRequestJson(
                openAiProperties.getDocumentSummaryModel(),
                CROSS_MEETING_SYSTEM_PROMPT,
                inputBuilder.toString(),
                openAiProperties.getDocumentSummaryMaxOutputTokens());

        String chatUrl = openAiProperties.getBaseUrl().replaceAll("/+$", "") + "/chat/completions";
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(chatUrl))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + openAiProperties.getApiKey())
                .timeout(Duration.ofSeconds(120))
                .POST(HttpRequest.BodyPublishers.ofString(requestBodyJson, StandardCharsets.UTF_8));

        if (openAiProperties.getReferer() != null && !openAiProperties.getReferer().isBlank()) {
            requestBuilder.header("HTTP-Referer", openAiProperties.getReferer());
        }
        if (openAiProperties.getTitle() != null && !openAiProperties.getTitle().isBlank()) {
            requestBuilder.header("X-Title", openAiProperties.getTitle());
        }

        long start = System.currentTimeMillis();
        try {
            HttpResponse<java.io.InputStream> response = STREAM_HTTP_CLIENT.send(
                    requestBuilder.build(),
                    HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() != 200) {
                String body = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
                log.error("[LlmIntegration] streamChatUnified failed, status={}, body={}",
                        response.statusCode(), body);
                throw new IOException("OpenAI streaming request failed: HTTP " + response.statusCode());
            }

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("data: ")) {
                        String data = line.substring(6).trim();
                        if ("[DONE]".equals(data)) break;
                        String chunk = parseDeltaContent(data);
                        if (chunk != null && !chunk.isEmpty()) {
                            chunkConsumer.accept(chunk);
                        }
                    }
                }
            }
            log.info("[LlmIntegration] streamChatUnified complete, costMs={}", System.currentTimeMillis() - start);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Streaming interrupted", e);
        }
    }

    private String buildStreamRequestJson(String model, String systemPrompt, String userMessage, long maxTokens) throws IOException {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("model", model);
        req.put("stream", true);
        req.put("max_tokens", maxTokens);
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt));
        messages.add(Map.of("role", "user", "content", userMessage));
        req.put("messages", messages);
        try {
            return OBJECT_MAPPER.writeValueAsString(req);
        } catch (Exception e) {
            throw new IOException("Failed to build stream request JSON", e);
        }
    }

    private String parseDeltaContent(String jsonData) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(jsonData);
            JsonNode choices = root.path("choices");
            if (choices.isArray() && !choices.isEmpty()) {
                JsonNode content = choices.get(0).path("delta").path("content");
                if (!content.isMissingNode() && !content.isNull()) {
                    return content.asText();
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private String nullToBlank(String value) {
        return value == null ? "" : value;
    }

    private String createTextResponse(
            String model,
            String systemPrompt,
            String userMessage,
            long maxOutputTokens
    ) throws IOException {
        if (userMessage == null || userMessage.isBlank()) {
            return "";
        }
        if (openAiProperties.getApiKey() == null || openAiProperties.getApiKey().isBlank()) {
            throw new IOException("OPENAI_API_KEY is blank");
        }
        long start = System.currentTimeMillis();
        String requestBodyJson = buildStreamRequestJson(model, systemPrompt, userMessage, maxOutputTokens);
        // Override stream=false for non-streaming request
        requestBodyJson = requestBodyJson.replace("\"stream\":true", "\"stream\":false");

        String chatUrl = openAiProperties.getBaseUrl().replaceAll("/+$", "") + "/chat/completions";
        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                .uri(URI.create(chatUrl))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + openAiProperties.getApiKey())
                .timeout(Duration.ofSeconds(120))
                .POST(HttpRequest.BodyPublishers.ofString(requestBodyJson, StandardCharsets.UTF_8));
        if (openAiProperties.getReferer() != null && !openAiProperties.getReferer().isBlank()) {
            reqBuilder.header("HTTP-Referer", openAiProperties.getReferer());
        }
        if (openAiProperties.getTitle() != null && !openAiProperties.getTitle().isBlank()) {
            reqBuilder.header("X-Title", openAiProperties.getTitle());
        }
        try {
            HttpResponse<String> response = STREAM_HTTP_CLIENT.send(
                    reqBuilder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                log.error("[LlmIntegration] chat/completions failed, status={}, body={}",
                        response.statusCode(), response.body());
                throw new IOException("LLM request failed: HTTP " + response.statusCode());
            }
            JsonNode root = OBJECT_MAPPER.readTree(response.body());
            String text = root.path("choices").get(0).path("message").path("content").asText("").trim();
            if (text.isBlank()) {
                throw new IOException("LLM response content is empty");
            }
            log.info("[LlmIntegration] chat/completions done, model={}, costMs={}, outputLen={}",
                    model, System.currentTimeMillis() - start, text.length());
            return text;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("LLM request interrupted", e);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            log.error("[LlmIntegration] chat/completions error, model={}", model, e);
            throw new IOException("LLM response unavailable: " + sanitizeErrorMessage(e.getMessage()), e);
        }
    }

    private static final String ACTION_ITEM_SYSTEM_PROMPT =
            "你是会议行动项提取助手。从会议记录中提取所有明确的行动项（待办事项、跟进事项、决议等）。\n"
            + "每条行动项单独一行，格式：【负责人（如有）】行动内容（截止时间（如有））\n"
            + "如果没有明确的负责人或截止时间，省略对应部分。\n"
            + "只输出行动项列表，每行一条，不要编号，不要解释，不要重复原文。\n"
            + "如果没有找到行动项，只输出：无";

    /**
     * Extracts action items from meeting transcript text.
     * Returns a newline-separated list of action items, or "无" if none found.
     */
    public String extractActionItems(String transcriptText) throws IOException {
        if (transcriptText == null || transcriptText.isBlank()) return "无";
        String input = transcriptText.length() > 12000
                ? transcriptText.substring(0, 12000) : transcriptText;
        log.info("[LlmIntegration] extractActionItems start, textLen={}", input.length());
        String result = createTextResponse(
                openAiProperties.getSummaryModel(),
                ACTION_ITEM_SYSTEM_PROMPT,
                input,
                800L
        );
        log.info("[LlmIntegration] extractActionItems end, resultLen={}", result.length());
        return result;
    }

    /**
     * Calls the OpenAI Embeddings API and returns the float vector for {@code text}.
     * Returns an empty array when the API key is missing or the text is blank.
     */
    public float[] embed(String text) throws IOException {
        if (text == null || text.isBlank()) return new float[0];
        if (openAiProperties.getApiKey() == null || openAiProperties.getApiKey().isBlank()) {
            throw new IOException("OPENAI_API_KEY is blank");
        }
        String truncated = text.length() > 8000 ? text.substring(0, 8000) : text;
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("model", openAiProperties.getEmbeddingModel());
        req.put("input", truncated);
        String bodyJson = OBJECT_MAPPER.writeValueAsString(req);

        String embUrl = openAiProperties.getBaseUrl().replaceAll("/+$", "") + "/embeddings";
        HttpRequest.Builder rb = HttpRequest.newBuilder()
                .uri(URI.create(embUrl))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + openAiProperties.getApiKey())
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(bodyJson, StandardCharsets.UTF_8));
        if (openAiProperties.getReferer() != null && !openAiProperties.getReferer().isBlank()) {
            rb.header("HTTP-Referer", openAiProperties.getReferer());
        }
        try {
            HttpResponse<String> resp = STREAM_HTTP_CLIENT.send(rb.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() != 200) {
                throw new IOException("Embeddings API failed: HTTP " + resp.statusCode() + " " + resp.body());
            }
            JsonNode dataArr = OBJECT_MAPPER.readTree(resp.body()).path("data").get(0).path("embedding");
            float[] vec = new float[dataArr.size()];
            for (int i = 0; i < vec.length; i++) vec[i] = (float) dataArr.get(i).asDouble();
            log.debug("[LlmIntegration] embed done, model={}, dims={}", openAiProperties.getEmbeddingModel(), vec.length);
            return vec;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Embedding request interrupted", e);
        }
    }

    private String sanitizeErrorMessage(String message) {
        if (message == null || message.isBlank()) {
            return "unknown";
        }
        return message.replaceAll("[\\r\\n\\t]+", " ").trim();
    }
}
