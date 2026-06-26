package com.si.backend.service;

import com.si.backend.integration.LlmIntegration;
import com.si.backend.mapper.MeetingKnowledgeMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 会议知识包服务:把上传的会议文件蒸馏成紧凑背景知识(人名/术语/数字/主题),按账号持久保存,
 * 供实时 id→zh 纠错翻译作"接地"上下文,纠正 ASR 听错的专有名词。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MeetingKnowledgeService {

    /** 注入实时 prompt 时的知识包字符上限(控制 prompt 体积/延迟) */
    private static final int MAX_INJECT_CHARS = 4000;

    private final MeetingKnowledgeMapper meetingKnowledgeMapper;
    private final LlmIntegration llmIntegration;

    @PostConstruct
    public void initTable() {
        log.info("[MeetingKnowledgeService] initTable start");
        meetingKnowledgeMapper.createTableIfNotExists();
        log.info("[MeetingKnowledgeService] initTable end");
    }

    /** 取该账号当前会议知识包(截断到注入上限);无则返回 null。 */
    public String getForInject(Long userId) {
        if (userId == null) {
            return null;
        }
        String content = meetingKnowledgeMapper.findContent(userId);
        if (content == null || content.isBlank()) {
            return null;
        }
        return content.length() > MAX_INJECT_CHARS ? content.substring(0, MAX_INJECT_CHARS) : content;
    }

    /** 从会议文件文本蒸馏知识包并保存(覆盖该账号上一份)。上传后异步调用。 */
    public void generateAndSaveFromText(Long userId, String fileText) {
        if (userId == null || fileText == null || fileText.isBlank()) {
            return;
        }
        long start = System.currentTimeMillis();
        log.info("[MeetingKnowledgeService] generate start, userId={}, fileLen={}", userId, fileText.length());
        try {
            String pack = llmIntegration.extractMeetingKnowledgePack(fileText);
            if (pack == null || pack.isBlank()) {
                log.info("[MeetingKnowledgeService] generate empty pack, userId={}", userId);
                return;
            }
            meetingKnowledgeMapper.upsert(userId, pack.trim());
            log.info("[MeetingKnowledgeService] generate end, userId={}, packLen={}, costMs={}",
                    userId, pack.length(), System.currentTimeMillis() - start);
        } catch (Exception e) {
            log.warn("[MeetingKnowledgeService] generate failed, userId={}, reason={}", userId, e.getMessage());
        }
    }
}
