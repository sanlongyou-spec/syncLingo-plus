package com.si.backend.service;

import com.si.backend.dto.SaveInterpretationResultRequest;
import com.si.backend.entity.InterpretationResult;
import com.si.backend.mapper.InterpretationResultMapper;
import com.si.backend.vo.InterpretationResultItemVo;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class InterpretationResultService {

    private final InterpretationResultMapper resultMapper;

    @PostConstruct
    public void initTable() {
        log.info("[InterpretationResultService] initTable start");
        resultMapper.createTableIfNotExists();
        addColumnIfMissing("speaker_id", resultMapper::addSpeakerIdColumnIfNotExists);
        addColumnIfMissing("speaker_name", resultMapper::addSpeakerNameColumnIfNotExists);
        log.info("[InterpretationResultService] initTable end");
    }

    private void addColumnIfMissing(String column, Runnable ddl) {
        try {
            ddl.run();
        } catch (org.springframework.dao.DataAccessException e) {
            if (e.getMessage() != null && e.getMessage().contains("Duplicate column")) {
                log.debug("[InterpretationResultService] column {} already exists", column);
            } else {
                throw e;
            }
        }
    }

    public InterpretationResultItemVo save(SaveInterpretationResultRequest request) {
        log.info("[InterpretationResultService] save start, sessionId={}, sourceLen={}, translatedLen={}",
                request.getSessionId(), request.getSourceText().length(), request.getTranslatedText().length());
        InterpretationResult result = new InterpretationResult();
        result.setSessionId(request.getSessionId());
        result.setSourceText(request.getSourceText());
        result.setTranslatedText(request.getTranslatedText());
        result.setSourceLang(request.getSourceLang());
        result.setTargetLang(request.getTargetLang());
        result.setSpeakerId(request.getSpeakerId());
        result.setSpeakerName(request.getSpeakerName());
        resultMapper.insert(result);
        log.info("[InterpretationResultService] save end, sessionId={}, resultId={}",
                request.getSessionId(), result.getId());
        return toVo(result);
    }

    public List<InterpretationResultItemVo> listBySessionId(String sessionId) {
        log.info("[InterpretationResultService] listBySessionId start, sessionId={}", sessionId);
        List<InterpretationResultItemVo> results = resultMapper.findBySessionId(sessionId).stream()
                .map(this::toVo)
                .toList();
        log.info("[InterpretationResultService] listBySessionId end, sessionId={}, count={}",
                sessionId, results.size());
        return results;
    }

    private InterpretationResultItemVo toVo(InterpretationResult result) {
        return InterpretationResultItemVo.builder()
                .id(result.getId())
                .sessionId(result.getSessionId())
                .sourceText(result.getSourceText())
                .translatedText(result.getTranslatedText())
                .sourceLang(result.getSourceLang())
                .targetLang(result.getTargetLang())
                .speakerId(result.getSpeakerId())
                .speakerName(result.getSpeakerName())
                .createTime(result.getCreateTime() != null ? result.getCreateTime().toString() : null)
                .build();
    }
}
