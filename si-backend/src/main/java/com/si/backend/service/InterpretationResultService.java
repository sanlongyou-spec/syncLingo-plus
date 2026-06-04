package com.si.backend.service;

import com.si.backend.dto.SaveInterpretationResultRequest;
import com.si.backend.entity.InterpretationEmbedding;
import com.si.backend.entity.InterpretationResult;
import com.si.backend.entity.InterpretationSession;
import com.si.backend.integration.LlmIntegration;
import com.si.backend.mapper.InterpretationEmbeddingMapper;
import com.si.backend.mapper.InterpretationResultMapper;
import com.si.backend.mapper.InterpretationSessionMapper;
import com.si.backend.vo.InterpretationResultItemVo;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
@RequiredArgsConstructor
public class InterpretationResultService {

    private final InterpretationResultMapper resultMapper;
    private final InterpretationEmbeddingMapper embeddingMapper;
    private final InterpretationSessionMapper sessionMapper;
    private final LlmIntegration llmIntegration;

    @PostConstruct
    public void initTable() {
        log.info("[InterpretationResultService] initTable start");
        resultMapper.createTableIfNotExists();
        addColumnIfMissing("speaker_id", resultMapper::addSpeakerIdColumnIfNotExists);
        addColumnIfMissing("speaker_name", resultMapper::addSpeakerNameColumnIfNotExists);
        embeddingMapper.createTableIfNotExists();
        addColumnIfMissing("result_id nullable",  embeddingMapper::makeResultIdNullable);
        addColumnIfMissing("source_type",         embeddingMapper::addSourceTypeColumnIfNotExists);
        addColumnIfMissing("source_id",           embeddingMapper::addSourceIdColumnIfNotExists);
        addColumnIfMissing("ref_id",              embeddingMapper::addRefIdColumnIfNotExists);
        addColumnIfMissing("uk_emb_source index", embeddingMapper::addSourceUniqueIndexIfNotExists);
        addColumnIfMissing("idx_emb_refid index", embeddingMapper::addRefIdIndexIfNotExists);
        addColumnIfMissing("chunk_start",         embeddingMapper::addChunkStartColumnIfNotExists);
        log.info("[InterpretationResultService] initTable end");
    }

    private void addColumnIfMissing(String column, Runnable ddl) {
        try {
            ddl.run();
        } catch (org.springframework.dao.DataAccessException e) {
            String msg = e.getMessage();
            if (msg != null && (msg.contains("Duplicate column") || msg.contains("Duplicate key name"))) {
                log.debug("[InterpretationResultService] schema element '{}' already exists", column);
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

        asyncEmbed(result);

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

    /**
     * Batch-rebuild embeddings for all result rows that have none yet.
     * @return number of embeddings successfully created
     */
    public int rebuildEmbeddings(int batchLimit) {
        var candidates = embeddingMapper.findResultsWithoutEmbedding(batchLimit);
        log.info("[InterpretationResultService] rebuildEmbeddings start, candidates={}", candidates.size());
        int count = 0;
        for (var candidate : candidates) {
            try {
                String chunkText = candidate.getSourceText();
                if (chunkText == null || chunkText.isBlank()) continue;
                float[] vec = llmIntegration.embed(chunkText);
                if (vec.length == 0) continue;

                InterpretationSession session = sessionMapper.findBySessionId(candidate.getSessionId());

                InterpretationEmbedding emb = new InterpretationEmbedding();
                emb.setResultId(candidate.getResultId());
                emb.setSessionId(candidate.getSessionId());
                emb.setMeetingId(session != null ? session.getMeetingId() : null);
                emb.setSessionTitle(session != null ? session.getTitle() : null);
                emb.setSessionDate(session != null && session.getStartTime() != null
                        ? session.getStartTime().toLocalDate() : null);
                emb.setSpeakerName(candidate.getSpeakerName());
                emb.setChunkText(chunkText);
                emb.setTranslatedText(candidate.getTranslatedText());
                emb.setEmbedding(VectorSearchService.toBytes(vec));
                embeddingMapper.insert(emb);
                count++;
            } catch (Exception e) {
                log.warn("[InterpretationResultService] rebuildEmbeddings failed for resultId={}: {}",
                        candidate.getResultId(), e.getMessage());
            }
        }
        log.info("[InterpretationResultService] rebuildEmbeddings done, created={}", count);
        return count;
    }

    private void asyncEmbed(InterpretationResult result) {
        long resultId = result.getId();
        String sessionId = result.getSessionId();
        String sourceText = result.getSourceText();
        String translatedText = result.getTranslatedText();
        String speakerName = result.getSpeakerName();

        CompletableFuture.runAsync(() -> {
            try {
                if (sourceText == null || sourceText.isBlank()) return;
                // P0-1: skip ASR noise so it never pollutes retrieval.
                if (TranscriptQuality.isLikelyNoise(sourceText)) {
                    log.debug("[InterpretationResultService] skip embedding noisy transcript, resultId={}", resultId);
                    return;
                }
                if (embeddingMapper.countByResultId(resultId) > 0) return;

                float[] vec = llmIntegration.embed(sourceText);
                if (vec.length == 0) return;

                InterpretationSession session = sessionMapper.findBySessionId(sessionId);

                InterpretationEmbedding emb = new InterpretationEmbedding();
                emb.setResultId(resultId);
                emb.setSessionId(sessionId);
                emb.setMeetingId(session != null ? session.getMeetingId() : null);
                emb.setSessionTitle(session != null ? session.getTitle() : null);
                emb.setSessionDate(session != null && session.getStartTime() != null
                        ? session.getStartTime().toLocalDate() : null);
                emb.setSpeakerName(speakerName);
                emb.setChunkText(sourceText);
                emb.setTranslatedText(translatedText);
                emb.setEmbedding(VectorSearchService.toBytes(vec));
                embeddingMapper.insert(emb);
                log.debug("[InterpretationResultService] asyncEmbed ok, resultId={}, dims={}", resultId, vec.length);
            } catch (Exception e) {
                log.warn("[InterpretationResultService] asyncEmbed failed, resultId={}: {}", resultId, e.getMessage());
            }
        });
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
