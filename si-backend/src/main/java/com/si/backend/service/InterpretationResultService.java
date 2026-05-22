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
        log.info("[InterpretationResultService] initTable end");
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
                .createTime(result.getCreateTime() != null ? result.getCreateTime().toString() : null)
                .build();
    }
}
