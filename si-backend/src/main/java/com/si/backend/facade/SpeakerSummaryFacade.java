package com.si.backend.facade;

import com.si.backend.dto.SpeakerSummaryRequest;
import com.si.backend.dto.UpdateSpeakerSummaryRequest;
import com.si.backend.entity.SpeakerSummaryRecord;
import com.si.backend.service.SpeakerSummaryService;
import com.si.backend.vo.SpeakerSummaryRecordVo;
import com.si.backend.vo.SpeakerSummaryVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Coordinates speaker-summary requests and maps persistence records to API view objects.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SpeakerSummaryFacade {

    private static final String UNKNOWN_SPEAKER = "未知发言人";

    private final SpeakerSummaryService speakerSummaryService;

    public SpeakerSummaryVo summarize(SpeakerSummaryRequest request) {
        String speakerName = resolveSpeakerName(request.getSpeakerName(), request.getSpeakerId());
        log.info("[SpeakerSummaryFacade] summarize start, sessionId={}, speaker={}, textLen={}",
                request.getSessionId(), speakerName, request.getText().length());
        SpeakerSummaryVo summary = speakerSummaryService.summarize(
                speakerName,
                request.getText(),
                request.getSessionId(),
                request.getSpeakerId(),
                request.getRequirements()
        );
        log.info("[SpeakerSummaryFacade] summarize end, sessionId={}, speaker={}",
                request.getSessionId(), speakerName);
        return summary;
    }

    public List<SpeakerSummaryRecordVo> getBySession(String sessionId) {
        log.info("[SpeakerSummaryFacade] getBySession start, sessionId={}", sessionId);
        List<SpeakerSummaryRecordVo> summaries = speakerSummaryService.getBySession(sessionId).stream()
                .map(this::toVo)
                .toList();
        log.info("[SpeakerSummaryFacade] getBySession end, sessionId={}, count={}", sessionId, summaries.size());
        return summaries;
    }

    public SpeakerSummaryVo regenerate(Long id, String requirements) {
        log.info("[SpeakerSummaryFacade] regenerate start, id={}", id);
        SpeakerSummaryVo summary = speakerSummaryService.regenerate(id, requirements);
        log.info("[SpeakerSummaryFacade] regenerate end, id={}", id);
        return summary;
    }

    public SpeakerSummaryRecordVo update(Long id, UpdateSpeakerSummaryRequest request) {
        log.info("[SpeakerSummaryFacade] update start, id={}, speaker={}", id, request.getSpeakerName());
        SpeakerSummaryRecordVo summary = toVo(speakerSummaryService.update(
                id,
                request.getSpeakerName(),
                request.getSummary()
        ));
        log.info("[SpeakerSummaryFacade] update end, id={}, speaker={}", id, summary.getSpeakerName());
        return summary;
    }

    private String resolveSpeakerName(String speakerName, String speakerId) {
        if (speakerName != null && !speakerName.isBlank()) {
            return speakerName.trim();
        }
        if (speakerId != null && !speakerId.isBlank()) {
            return speakerId.trim();
        }
        return UNKNOWN_SPEAKER;
    }

    private SpeakerSummaryRecordVo toVo(SpeakerSummaryRecord record) {
        return SpeakerSummaryRecordVo.builder()
                .id(record.getId())
                .sessionId(record.getSessionId())
                .speakerId(record.getSpeakerId())
                .speakerName(record.getSpeakerName())
                .title(record.getTitle())
                .textSnippet(record.getTextSnippet())
                .summary(record.getSummary())
                .createTime(record.getCreateTime())
                .build();
    }
}
