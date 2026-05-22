package com.si.backend.integration;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseOutputItem;
import com.openai.models.responses.ResponseOutputMessage;
import com.openai.models.responses.ResponseOutputText;
import com.si.backend.config.OpenAiProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;

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

    private final OpenAiProperties openAiProperties;
    private volatile OpenAIClient openAIClient;

    /**
     * Compresses Indonesian translated text through OpenAI Responses API.
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
    public String summarizeMeeting(String text) throws IOException {
        log.info("[LlmIntegration] summarizeMeeting start, model={}, textLen={}",
                openAiProperties.getSummaryModel(), text != null ? text.length() : 0);
        String result = createTextResponse(
                openAiProperties.getSummaryModel(),
                MEETING_SUMMARY_SYSTEM_PROMPT,
                text,
                openAiProperties.getSummaryMaxOutputTokens()
        );
        log.info("[LlmIntegration] summarizeMeeting end, textLen={}, resultLen={}",
                text != null ? text.length() : 0, result.length());
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

    private String nullToBlank(String value) {
        return value == null ? "" : value;
    }

    private String createTextResponse(
            String model,
            String instructions,
            String input,
            long maxOutputTokens
    ) throws IOException {
        if (input == null || input.isBlank()) {
            return "";
        }
        long start = System.currentTimeMillis();
        try {
            ResponseCreateParams params = ResponseCreateParams.builder()
                    .model(model)
                    .instructions(instructions)
                    .input(input)
                    .maxOutputTokens(maxOutputTokens)
                    .store(false)
                    .build();
            Response response = openAIClient().responses().create(params);
            String outputText = extractOutputText(response);
            log.info("[LlmIntegration] OpenAI response done, model={}, costMs={}, outputLen={}",
                    model, System.currentTimeMillis() - start, outputText.length());
            return outputText;
        } catch (Exception e) {
            if (isExpectedProviderRejection(e)) {
                log.warn("[LlmIntegration] OpenAI response rejected, model={}, inputLen={}, reason={}",
                        model, input.length(), sanitizeErrorMessage(e.getMessage()));
            } else {
                log.error("[LlmIntegration] OpenAI response failed, model={}, inputLen={}",
                        model, input.length(), e);
            }
            throw new IOException("LLM response unavailable: " + sanitizeErrorMessage(e.getMessage()), e);
        }
    }

    private boolean isExpectedProviderRejection(Exception e) {
        String className = e.getClass().getSimpleName();
        String message = e.getMessage();
        return "PermissionDeniedException".equals(className)
                || "UnauthorizedException".equals(className)
                || (message != null && message.contains("provider Terms Of Service"))
                || (message != null && message.contains("User not found"));
    }

    private String sanitizeErrorMessage(String message) {
        if (message == null || message.isBlank()) {
            return "unknown";
        }
        return message.replaceAll("[\\r\\n\\t]+", " ").trim();
    }

    private OpenAIClient openAIClient() {
        OpenAIClient currentClient = openAIClient;
        if (currentClient != null) {
            return currentClient;
        }
        synchronized (this) {
            if (openAIClient == null) {
                if (openAiProperties.getApiKey() == null || openAiProperties.getApiKey().isBlank()) {
                    throw new IllegalStateException("OPENAI_API_KEY is blank");
                }
                OpenAIOkHttpClient.Builder builder = OpenAIOkHttpClient.builder()
                        .apiKey(openAiProperties.getApiKey())
                        .maxRetries(2);
                if (openAiProperties.getBaseUrl() != null && !openAiProperties.getBaseUrl().isBlank()) {
                    builder.baseUrl(openAiProperties.getBaseUrl());
                }
                if (openAiProperties.getReferer() != null && !openAiProperties.getReferer().isBlank()) {
                    builder.putHeader("HTTP-Referer", openAiProperties.getReferer());
                }
                if (openAiProperties.getTitle() != null && !openAiProperties.getTitle().isBlank()) {
                    builder.putHeader("X-Title", openAiProperties.getTitle());
                }
                openAIClient = builder.build();
                log.info("[LlmIntegration] official OpenAI Java client initialized, baseUrl={}",
                        openAiProperties.getBaseUrl());
            }
            return openAIClient;
        }
    }

    private String extractOutputText(Response response) throws IOException {
        StringBuilder result = new StringBuilder();
        for (ResponseOutputItem outputItem : response.output()) {
            if (!outputItem.isMessage()) {
                continue;
            }
            ResponseOutputMessage message = outputItem.asMessage();
            for (ResponseOutputMessage.Content content : message.content()) {
                if (!content.isOutputText()) {
                    continue;
                }
                ResponseOutputText outputText = content.asOutputText();
                result.append(outputText.text());
            }
        }
        String text = result.toString().trim();
        if (text.isBlank()) {
            throw new IOException("OpenAI response text is empty");
        }
        return text;
    }
}
