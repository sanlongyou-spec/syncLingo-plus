package com.si.backend.service;

import com.si.backend.common.BizException;
import com.si.backend.common.ErrorCode;
import com.si.backend.dto.SaveMeetingMaterialRequest;
import com.si.backend.entity.InterpretationRecord;
import com.si.backend.entity.MeetingMaterial;
import com.si.backend.integration.LlmIntegration;
import com.si.backend.mapper.MeetingMaterialMapper;
import com.si.backend.vo.MeetingMaterialVo;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Service for saving agenda/report material and generating material-aware summaries.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MeetingMaterialService {

    private final MeetingMaterialMapper materialMapper;
    private final InterpretationRecordService recordService;
    private final InterpretationSessionService sessionService;
    private final LlmIntegration llmIntegration;

    @PostConstruct
    public void initTable() {
        log.info("[MeetingMaterialService] initTable start");
        materialMapper.createTableIfNotExists();
        log.info("[MeetingMaterialService] initTable end");
    }

    @Transactional
    public MeetingMaterialVo saveMaterial(String sessionId, SaveMeetingMaterialRequest request) {
        log.info("[MeetingMaterialService] saveMaterial start, sessionId={}, title={}, agendaLen={}, reportLen={}",
                sessionId,
                request.getTitle(),
                length(request.getAgendaText()),
                length(request.getReportText()));
        MeetingMaterial existing = materialMapper.findBySessionId(sessionId);
        MeetingMaterial material = new MeetingMaterial();
        material.setSessionId(sessionId);
        material.setTitle(trimToNull(request.getTitle()));
        material.setAgendaText(trimToNull(request.getAgendaText()));
        material.setReportText(trimToNull(request.getReportText()));
        material.setExecutiveNames(trimToNull(request.getExecutiveNames()));
        if (existing == null) {
            materialMapper.insert(material);
        } else {
            material.setId(existing.getId());
            material.setSummaryText(existing.getSummaryText());
            materialMapper.updateMaterial(material);
        }
        MeetingMaterial saved = materialMapper.findBySessionId(sessionId);
        log.info("[MeetingMaterialService] saveMaterial end, sessionId={}, id={}", sessionId, saved != null ? saved.getId() : null);
        return toVo(saved, recordService.getSessionRecords(sessionId).size());
    }

    public MeetingMaterialVo getMaterial(String sessionId) {
        log.info("[MeetingMaterialService] getMaterial start, sessionId={}", sessionId);
        MeetingMaterial material = materialMapper.findBySessionId(sessionId);
        int recordCount = recordService.getSessionRecords(sessionId).size();
        log.info("[MeetingMaterialService] getMaterial end, sessionId={}, found={}, recordCount={}",
                sessionId, material != null, recordCount);
        return toVo(material, recordCount);
    }

    @Transactional
    public MeetingMaterialVo generateSummary(String sessionId) {
        log.info("[MeetingMaterialService] generateSummary start, sessionId={}", sessionId);
        MeetingMaterial material = materialMapper.findBySessionId(sessionId);
        if (material == null) {
            throw BizException.of(ErrorCode.NOT_FOUND, "请先保存会议安排或报告");
        }
        List<InterpretationRecord> records = recordService.getSessionRecords(sessionId);
        if (records.isEmpty()) {
            throw BizException.of(ErrorCode.NOT_FOUND, "该会议没有可用于总结的文本记录");
        }
        String meetingText = records.stream()
                .map(record -> String.format("[%d][%s->%s] %s => %s",
                        record.getSeq(),
                        record.getSourceLang(),
                        record.getTargetLang(),
                        record.getSourceText(),
                        record.getTargetText()))
                .collect(Collectors.joining("\n"));
        try {
            String summary = llmIntegration.summarizeMeetingWithMaterials(
                    meetingText,
                    material.getAgendaText(),
                    material.getReportText(),
                    material.getExecutiveNames()
            );
            materialMapper.updateSummary(sessionId, summary);
            sessionService.addLlmTokens(sessionId, estimateTokens(material.getAgendaText())
                    + estimateTokens(material.getReportText())
                    + estimateTokens(meetingText), estimateTokens(summary));
            MeetingMaterial saved = materialMapper.findBySessionId(sessionId);
            log.info("[MeetingMaterialService] generateSummary end, sessionId={}, recordCount={}, summaryLen={}",
                    sessionId, records.size(), summary.length());
            return toVo(saved, records.size());
        } catch (IOException e) {
            log.error("[MeetingMaterialService] generateSummary failed, sessionId={}", sessionId, e);
            throw BizException.of(ErrorCode.TRANSLATE_ERROR, "会议资料总结生成失败: " + e.getMessage());
        }
    }

    private MeetingMaterialVo toVo(MeetingMaterial material, int recordCount) {
        if (material == null) {
            return MeetingMaterialVo.builder()
                    .recordCount(recordCount)
                    .build();
        }
        return MeetingMaterialVo.builder()
                .id(material.getId())
                .sessionId(material.getSessionId())
                .title(material.getTitle())
                .agendaText(material.getAgendaText())
                .reportText(material.getReportText())
                .executiveNames(material.getExecutiveNames())
                .summaryText(material.getSummaryText())
                .recordCount(recordCount)
                .createTime(material.getCreateTime())
                .updateTime(material.getUpdateTime())
                .build();
    }

    private int length(String value) {
        return value == null ? 0 : value.length();
    }

    private String trimToNull(String value) {
        if (value == null || value.trim().isBlank()) {
            return null;
        }
        return value.trim();
    }

    private long estimateTokens(String text) {
        if (text == null || text.isBlank()) {
            return 0L;
        }
        return Math.max(1L, Math.round(text.length() / 2.0));
    }
}
