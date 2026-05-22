package com.si.backend.service;

import com.si.backend.entity.VoiceUsageRecord;
import com.si.backend.mapper.VoiceUsageRecordMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 音色使用记录服务，记录每次 TTS 调用的基础信息。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VoiceUsageRecordService {

    private final VoiceUsageRecordMapper usageRecordMapper;

    @PostConstruct
    public void initTable() {
        log.info("[VoiceUsageRecordService] initTable start");
        usageRecordMapper.createTableIfNotExists();
        log.info("[VoiceUsageRecordService] initTable end");
    }

    @Transactional
    public void recordUsage(String sessionId, Long userId, String voiceId, String targetLang, int textLen) {
        log.info("[VoiceUsageRecordService] recordUsage start, sessionId={}, userId={}, voiceId={}, targetLang={}, textLen={}",
                sessionId, userId, voiceId, targetLang, textLen);
        VoiceUsageRecord record = new VoiceUsageRecord();
        record.setSessionId(sessionId);
        record.setUserId(userId);
        record.setVoiceId(voiceId);
        record.setTargetLang(targetLang);
        record.setTextLen(textLen);
        usageRecordMapper.insert(record);
        log.info("[VoiceUsageRecordService] recordUsage end, sessionId={}, recordId={}", sessionId, record.getId());
    }

    public List<VoiceUsageRecord> getSessionUsage(String sessionId) {
        log.info("[VoiceUsageRecordService] getSessionUsage start, sessionId={}", sessionId);
        List<VoiceUsageRecord> records = usageRecordMapper.findBySessionId(sessionId);
        log.info("[VoiceUsageRecordService] getSessionUsage end, sessionId={}, count={}", sessionId, records.size());
        return records;
    }
}
