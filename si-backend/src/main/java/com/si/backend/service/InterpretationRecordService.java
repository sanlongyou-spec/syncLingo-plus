package com.si.backend.service;

import com.si.backend.entity.InterpretationRecord;
import com.si.backend.mapper.InterpretationRecordMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 同传对话记录服务，负责保存 final recognition 与译文。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InterpretationRecordService {

    private final InterpretationRecordMapper recordMapper;
    private final ConcurrentHashMap<String, AtomicInteger> sessionSeqMap = new ConcurrentHashMap<>();

    @PostConstruct
    public void initTable() {
        log.info("[InterpretationRecordService] initTable start");
        recordMapper.createTableIfNotExists();
        log.info("[InterpretationRecordService] initTable end");
    }

    @Transactional
    public InterpretationRecord saveTranslatedRecord(
            String sessionId,
            String sourceLang,
            String targetLang,
            String sourceText,
            String targetText
    ) {
        log.info("[InterpretationRecordService] saveTranslatedRecord start, sessionId={}, sourceLang={}, targetLang={}, sourceLen={}, targetLen={}",
                sessionId, sourceLang, targetLang,
                sourceText != null ? sourceText.length() : 0,
                targetText != null ? targetText.length() : 0);
        int seq = sessionSeqMap.computeIfAbsent(sessionId, this::loadCurrentSeq).incrementAndGet();
        InterpretationRecord record = new InterpretationRecord();
        record.setSessionId(sessionId);
        record.setSeq(seq);
        record.setSourceLang(sourceLang);
        record.setTargetLang(targetLang);
        record.setSourceText(sourceText);
        record.setTargetText(targetText);
        record.setSpokenAt(LocalDateTime.now());
        recordMapper.insert(record);
        log.info("[InterpretationRecordService] saveTranslatedRecord end, sessionId={}, seq={}, recordId={}",
                sessionId, seq, record.getId());
        return record;
    }

    public List<InterpretationRecord> getSessionRecords(String sessionId) {
        log.info("[InterpretationRecordService] getSessionRecords start, sessionId={}", sessionId);
        List<InterpretationRecord> records = recordMapper.findBySessionId(sessionId);
        log.info("[InterpretationRecordService] getSessionRecords end, sessionId={}, count={}", sessionId, records.size());
        return records;
    }

    public void cleanupSession(String sessionId) {
        log.debug("[InterpretationRecordService] cleanupSession, sessionId={}", sessionId);
        sessionSeqMap.remove(sessionId);
    }

    private AtomicInteger loadCurrentSeq(String sessionId) {
        int currentSeq = recordMapper.findMaxSeqBySessionId(sessionId);
        log.info("[InterpretationRecordService] loadCurrentSeq, sessionId={}, currentSeq={}", sessionId, currentSeq);
        return new AtomicInteger(currentSeq);
    }
}
