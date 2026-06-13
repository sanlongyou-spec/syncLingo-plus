package com.si.backend.service;

import com.si.backend.mapper.InterpretationResultMapper;
import com.si.backend.mapper.SpeakerSummaryRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class SessionSpeakerNameService {

    private final InterpretationResultMapper interpretationResultMapper;
    private final SpeakerSummaryRecordMapper speakerSummaryRecordMapper;

    // in-memory: "sessionId:speakerId" -> personName
    private final ConcurrentHashMap<String, String> nameMap = new ConcurrentHashMap<>();

    public void setName(String sessionId, String speakerId, String personName) {
        String key = sessionId + ":" + speakerId;
        if (personName == null || personName.isBlank()) {
            nameMap.remove(key);
            log.debug("[SpeakerName] cleared name, sessionId={}, speakerId={}", sessionId, speakerId);
        } else {
            nameMap.put(key, personName.trim());
            log.debug("[SpeakerName] set name, sessionId={}, speakerId={}, name={}", sessionId, speakerId, personName.trim());
        }
    }

    public String getName(String sessionId, String speakerId) {
        if (speakerId == null || speakerId.isBlank()) return null;
        String name = nameMap.get(sessionId + ":" + speakerId);
        log.debug("[SpeakerName] lookup sessionId={}, speakerId={}, result={}", sessionId, speakerId, name);
        return name;
    }

    /**
     * Bulk rename: update in-memory + all DB records for this session+speakerId.
     * Returns count of updated records.
     */
    public int bulkRename(String sessionId, String speakerId, String personName) {
        setName(sessionId, speakerId, personName);
        int updated = interpretationResultMapper.updateSpeakerName(sessionId, speakerId, personName);
        log.info("[SpeakerName] bulkRename sessionId={}, speakerId={}, name={}, updatedRecords={}", sessionId, speakerId, personName, updated);
        try {
            int summaryUpdated = speakerSummaryRecordMapper.updateSpeakerName(sessionId, speakerId, personName);
            log.debug("[SpeakerName] bulkRename summary records updated={}", summaryUpdated);
        } catch (Exception e) {
            log.warn("[SpeakerName] bulkRename summary update error: {}", e.getMessage());
        }
        return updated;
    }

    /** Get all known speakerId->name mappings for a session (from in-memory map). */
    public Map<String, String> getSessionMappings(String sessionId) {
        String prefix = sessionId + ":";
        return nameMap.entrySet().stream()
                .filter(e -> e.getKey().startsWith(prefix))
                .collect(Collectors.toMap(
                        e -> e.getKey().substring(prefix.length()),
                        Map.Entry::getValue
                ));
    }

    public void cleanupSession(String sessionId) {
        String prefix = sessionId + ":";
        nameMap.keySet().removeIf(k -> k.startsWith(prefix));
        log.debug("[SpeakerName] cleanup sessionId={}", sessionId);
    }
}
