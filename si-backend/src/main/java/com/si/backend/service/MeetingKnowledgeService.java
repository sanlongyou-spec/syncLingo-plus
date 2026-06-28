package com.si.backend.service;

import com.si.backend.integration.LlmIntegration;
import com.si.backend.mapper.MeetingKnowledgeMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 会议知识包服务:把上传的会议文件蒸馏成紧凑背景知识(人名/术语/数字/主题),按【会议(meetingId)】持久保存,
 * 供实时 id→zh 纠错翻译作"接地"上下文,纠正 ASR 听错的专有名词。按会议隔离,绝不跨会议串用。
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
        // 旧版按 user_id 建表;知识包是派生缓存,检测到旧结构则重建为按 meeting_id(一次性,下次上传重新生成)。
        if (meetingKnowledgeMapper.hasMeetingIdColumn() == 0) {
            log.warn("[MeetingKnowledgeService] legacy meeting_knowledge(user_id) detected, rebuilding by meeting_id");
            meetingKnowledgeMapper.dropTable();
            meetingKnowledgeMapper.createTableIfNotExists();
        }
        log.info("[MeetingKnowledgeService] initTable end");
    }

    /** 取该【会议】当前知识包(截断到注入上限);无则返回 null。 */
    public String getForInject(Long meetingId) {
        if (meetingId == null) {
            return null;
        }
        String content = meetingKnowledgeMapper.findContent(meetingId);
        if (content == null || content.isBlank()) {
            return null;
        }
        return content.length() > MAX_INJECT_CHARS ? content.substring(0, MAX_INJECT_CHARS) : content;
    }

    /** 从会议文件文本蒸馏知识包并保存到该【会议】(覆盖上一份)。绑定/上传会议材料后异步调用。 */
    public void generateAndSaveFromText(Long meetingId, String fileText) {
        if (meetingId == null || fileText == null || fileText.isBlank()) {
            return;
        }
        long start = System.currentTimeMillis();
        log.info("[MeetingKnowledgeService] generate start, meetingId={}, fileLen={}", meetingId, fileText.length());
        try {
            String pack = llmIntegration.extractMeetingKnowledgePack(fileText);
            if (pack == null || pack.isBlank()) {
                log.info("[MeetingKnowledgeService] generate empty pack, meetingId={}", meetingId);
                return;
            }
            meetingKnowledgeMapper.upsert(meetingId, pack.trim());
            log.info("[MeetingKnowledgeService] generate end, meetingId={}, packLen={}, costMs={}",
                    meetingId, pack.length(), System.currentTimeMillis() - start);
        } catch (Exception e) {
            log.warn("[MeetingKnowledgeService] generate failed, meetingId={}, reason={}", meetingId, e.getMessage());
        }
    }
}
