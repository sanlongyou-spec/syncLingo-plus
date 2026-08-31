package com.si.backend.service;

import com.si.backend.dto.SaveInterpretationResultRequest;
import com.si.backend.config.OpenAiProperties;
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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
@RequiredArgsConstructor
public class InterpretationResultService {

    private static final long MIN_INCREMENTAL_AFTER_ID = 0L;
    private static final int DEFAULT_INCREMENTAL_RESULT_LIMIT = 200;
    private static final int MAX_INCREMENTAL_RESULT_LIMIT = 500;

    private final InterpretationResultMapper resultMapper;
    private final InterpretationEmbeddingMapper embeddingMapper;
    private final InterpretationSessionMapper sessionMapper;
    private final LlmIntegration llmIntegration;
    private final OpenAiProperties openAiProperties;

    @PostConstruct
    public void initTable() {
        log.info("[InterpretationResultService] initTable start");
        resultMapper.createTableIfNotExists();
        addColumnIfMissing("speaker_id", resultMapper::addSpeakerIdColumnIfNotExists);
        addColumnIfMissing("speaker_name", resultMapper::addSpeakerNameColumnIfNotExists);
        addColumnIfMissing("speech_start_at_ms", resultMapper::addSpeechStartAtMsColumnIfNotExists);
        addColumnIfMissing("idx_result_session_id_id index", resultMapper::addSessionIdIdIndexIfNotExists);
        embeddingMapper.createTableIfNotExists();
        addColumnIfMissing("result_id nullable",  embeddingMapper::makeResultIdNullable);
        addColumnIfMissing("source_type",         embeddingMapper::addSourceTypeColumnIfNotExists);
        addColumnIfMissing("source_id",           embeddingMapper::addSourceIdColumnIfNotExists);
        addColumnIfMissing("ref_id",              embeddingMapper::addRefIdColumnIfNotExists);
        addColumnIfMissing("idx_emb_refid index", embeddingMapper::addRefIdIndexIfNotExists);
        addColumnIfMissing("chunk_start",         embeddingMapper::addChunkStartColumnIfNotExists);
        addColumnIfMissing("embedding_model",     embeddingMapper::addEmbeddingModelColumnIfNotExists);
        addColumnIfMissing("embedding_dim",       embeddingMapper::addEmbeddingDimColumnIfNotExists);
        addColumnIfMissing("embedding_profile",   embeddingMapper::addEmbeddingProfileColumnIfNotExists);
        addColumnIfMissing("content_hash",        embeddingMapper::addContentHashColumnIfNotExists);
        addColumnIfMissing("index_status",        embeddingMapper::addIndexStatusColumnIfNotExists);
        addColumnIfMissing("last_embedded_at",    embeddingMapper::addLastEmbeddedAtColumnIfNotExists);
        addColumnIfMissing("idx_emb_profile index", embeddingMapper::addProfileIndexIfNotExists);
        runDdlIfPossible("drop legacy uk_emb_source", embeddingMapper::dropLegacySourceUniqueIndexIfExists);
        runDdlIfPossible("drop legacy uk_emb_result", embeddingMapper::dropLegacyResultUniqueIndexIfExists);
        addColumnIfMissing("uk_emb_source_profile index", embeddingMapper::addSourceProfileUniqueIndexIfNotExists);
        addColumnIfMissing("uk_emb_result_profile index", embeddingMapper::addResultProfileUniqueIndexIfNotExists);
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

    private void runDdlIfPossible(String name, Runnable ddl) {
        try {
            ddl.run();
        } catch (org.springframework.dao.DataAccessException e) {
            String msg = e.getMessage();
            if (msg != null && (msg.contains("check that column/key exists")
                    || msg.contains("Can't DROP")
                    || msg.contains("Duplicate key name"))) {
                log.debug("[InterpretationResultService] schema ddl '{}' skipped: {}", name, msg);
            } else {
                throw e;
            }
        }
    }

    public InterpretationResultItemVo save(SaveInterpretationResultRequest request) {
        log.info("[InterpretationResultService] save start, sessionId={}, sourceLen={}, translatedLen={}, speechStartAtMs={}",
                request.getSessionId(), request.getSourceText().length(), request.getTranslatedText().length(),
                request.getSpeechStartAtMs());
        InterpretationResult result = new InterpretationResult();
        result.setSessionId(request.getSessionId());
        result.setSourceText(request.getSourceText());
        result.setTranslatedText(request.getTranslatedText());
        result.setSourceLang(request.getSourceLang());
        result.setTargetLang(request.getTargetLang());
        result.setSpeakerId(request.getSpeakerId());
        result.setSpeakerName(request.getSpeakerName());
        result.setSpeechStartAtMs(request.getSpeechStartAtMs());
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

    public List<InterpretationResultItemVo> listBySessionIdAfterId(String sessionId, Long afterId, Integer limit) {
        long normalizedAfterId = normalizeAfterId(afterId);
        int normalizedLimit = normalizeIncrementalLimit(limit);
        log.info("[InterpretationResultService] listBySessionIdAfterId start, sessionId={}, afterId={}, limit={}",
                sessionId, normalizedAfterId, normalizedLimit);
        List<InterpretationResultItemVo> results = resultMapper
                .findBySessionIdAfterId(sessionId, normalizedAfterId, normalizedLimit)
                .stream()
                .map(this::toVo)
                .toList();
        log.info("[InterpretationResultService] listBySessionIdAfterId end, sessionId={}, afterId={}, limit={}, count={}",
                sessionId, normalizedAfterId, normalizedLimit, results.size());
        return results;
    }

    private static long normalizeAfterId(Long afterId) {
        if (afterId == null || afterId < MIN_INCREMENTAL_AFTER_ID) {
            return MIN_INCREMENTAL_AFTER_ID;
        }
        return afterId;
    }

    private static int normalizeIncrementalLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_INCREMENTAL_RESULT_LIMIT;
        }
        return Math.min(limit, MAX_INCREMENTAL_RESULT_LIMIT);
    }

    /**
     * Batch-rebuild embeddings for all result rows that have none yet.
     * @return number of embeddings successfully created
     */
    public int rebuildEmbeddings(int batchLimit) {
        String profile = currentEmbeddingProfile(openAiProperties);
        var candidates = embeddingMapper.findResultsWithoutEmbedding(batchLimit, profile);
        log.info("[InterpretationResultService] rebuildEmbeddings start, profile={}, candidates={}",
                profile, candidates.size());
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
                applyEmbeddingMetadata(emb, vec, chunkText, openAiProperties);
                embeddingMapper.insert(emb);
                count++;
            } catch (Exception e) {
                log.warn("[InterpretationResultService] rebuildEmbeddings failed for resultId={}: {}",
                        candidate.getResultId(), e.getMessage());
            }
        }
        log.info("[InterpretationResultService] rebuildEmbeddings done, profile={}, created={}", profile, count);
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
                String profile = currentEmbeddingProfile(openAiProperties);
                if (embeddingMapper.countByResultId(resultId, profile) > 0) return;

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
                applyEmbeddingMetadata(emb, vec, sourceText, openAiProperties);
                embeddingMapper.insert(emb);
                log.debug("[InterpretationResultService] asyncEmbed ok, resultId={}, profile={}, dims={}",
                        resultId, profile, vec.length);
            } catch (Exception e) {
                log.warn("[InterpretationResultService] asyncEmbed failed, resultId={}: {}", resultId, e.getMessage());
            }
        });
    }

    static String currentEmbeddingProfile(OpenAiProperties properties) {
        if (properties == null || properties.getEmbeddingProfile() == null
                || properties.getEmbeddingProfile().isBlank()) {
            return "default";
        }
        return properties.getEmbeddingProfile().trim();
    }

    static String contentHash(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                out.append(String.format("%02x", b));
            }
            return out.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    static void applyEmbeddingMetadata(InterpretationEmbedding emb, float[] vec, String content,
                                       OpenAiProperties properties) {
        float[] safeVec = vec != null ? vec : new float[0];
        emb.setEmbeddingModel(properties.getEmbeddingModel());
        emb.setEmbeddingDim(safeVec.length);
        emb.setEmbeddingProfile(currentEmbeddingProfile(properties));
        emb.setContentHash(contentHash(content));
        emb.setIndexStatus("READY");
        emb.setEmbedding(VectorSearchService.toBytes(safeVec));
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
                .speechStartAtMs(result.getSpeechStartAtMs())
                .createTime(result.getCreateTime() != null ? result.getCreateTime().toString() : null)
                .build();
    }
}
