package com.si.backend.facade;

import com.si.backend.dto.HotwordSuggestion;
import com.si.backend.dto.SaveAsrHotwordRequest;
import com.si.backend.entity.AsrHotword;
import com.si.backend.entity.Terminology;
import com.si.backend.service.AsrHotwordService;
import com.si.backend.service.HotwordExtractionService;
import com.si.backend.service.TerminologyService;
import com.si.backend.service.ResourceOwnershipPolicy;
import com.si.backend.security.AuthenticatedActor;
import com.si.backend.vo.AsrHotwordVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Facade for user-owned ASR hotword workflows.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AsrHotwordFacade {

    private final AsrHotwordService hotwordService;
    private final TerminologyService terminologyService;
    private final HotwordExtractionService extractionService;
    private final ResourceOwnershipPolicy resourceOwnershipPolicy;

    public List<AsrHotwordVo> list(Long userId, String keyword, Boolean enabled, String language, String category) {
        log.info("[AsrHotwordFacade] list start, userId={}", userId);
        List<AsrHotwordVo> result = hotwordService.list(userId, keyword, enabled, language, category).stream()
                .map(this::toVo)
                .toList();
        log.info("[AsrHotwordFacade] list end, userId={}, count={}", userId, result.size());
        return result;
    }

    public AsrHotwordVo create(Long userId, SaveAsrHotwordRequest request) {
        log.info("[AsrHotwordFacade] create start, userId={}", userId);
        AsrHotwordVo result = toVo(hotwordService.create(userId, toEntity(request)));
        log.info("[AsrHotwordFacade] create end, userId={}, id={}", userId, result.getId());
        return result;
    }

    public List<AsrHotwordVo> createBatch(Long userId, List<SaveAsrHotwordRequest> requests) {
        log.info("[AsrHotwordFacade] createBatch start, userId={}, count={}", userId, requests != null ? requests.size() : 0);
        List<AsrHotword> hotwords = requests == null ? List.of() : requests.stream().map(this::toEntity).toList();
        List<AsrHotwordVo> result = hotwordService.createBatch(userId, hotwords).stream().map(this::toVo).toList();
        log.info("[AsrHotwordFacade] createBatch end, userId={}, count={}", userId, result.size());
        return result;
    }

    public List<AsrHotwordVo> createFromTerminology(Long userId, Long terminologyId) {
        log.info("[AsrHotwordFacade] createFromTerminology start, userId={}, terminologyId={}", userId, terminologyId);
        Terminology terminology = terminologyService.findOwnedTerminology(userId, terminologyId);
        List<AsrHotwordVo> result = hotwordService.createFromTerminology(userId, terminology).stream()
                .map(this::toVo)
                .toList();
        log.info("[AsrHotwordFacade] createFromTerminology end, userId={}, terminologyId={}, count={}",
                userId, terminologyId, result.size());
        return result;
    }

    public List<AsrHotwordVo> createFromMeeting(Long userId, List<String> names, String venue) {
        log.info("[AsrHotwordFacade] createFromMeeting start, userId={}, names={}, hasVenue={}",
                userId, names != null ? names.size() : 0, venue != null && !venue.isBlank());
        List<AsrHotwordVo> result = hotwordService.saveMeetingEntities(userId, names, venue, "TEAMS_MEETING").stream()
                .map(this::toVo)
                .toList();
        log.info("[AsrHotwordFacade] createFromMeeting end, userId={}, created={}", userId, result.size());
        return result;
    }

    public void update(Long id, Long userId, SaveAsrHotwordRequest request) {
        log.info("[AsrHotwordFacade] update start, id={}, userId={}", id, userId);
        hotwordService.update(id, userId, toEntity(request));
        log.info("[AsrHotwordFacade] update end, id={}, userId={}", id, userId);
    }

    public void updateEnabled(Long id, Long userId, Boolean enabled) {
        log.info("[AsrHotwordFacade] updateEnabled start, id={}, userId={}", id, userId);
        hotwordService.updateEnabled(id, userId, enabled);
        log.info("[AsrHotwordFacade] updateEnabled end, id={}, userId={}", id, userId);
    }

    public void delete(Long id, Long userId) {
        log.info("[AsrHotwordFacade] delete start, id={}, userId={}", id, userId);
        hotwordService.delete(id, userId);
        log.info("[AsrHotwordFacade] delete end, id={}, userId={}", id, userId);
    }

    public List<HotwordSuggestion> previewExtractedHotwords(
            AuthenticatedActor actor,
            String sessionId,
            Long userId
    ) {
        resourceOwnershipPolicy.requireOwnedSession(actor, sessionId);
        log.info("[AsrHotwordFacade] previewExtractedHotwords start, sessionId={}, userId={}", sessionId, userId);
        List<HotwordSuggestion> result = extractionService.previewFromSession(sessionId, userId);
        log.info("[AsrHotwordFacade] previewExtractedHotwords end, sessionId={}, count={}", sessionId, result.size());
        return result;
    }

    public List<AsrHotwordVo> confirmExtractedHotwords(Long userId, List<HotwordSuggestion> selected) {
        log.info("[AsrHotwordFacade] confirmExtractedHotwords start, userId={}, count={}", userId, selected != null ? selected.size() : 0);
        List<AsrHotwordVo> result = extractionService.confirmSuggestions(userId, selected).stream()
                .map(this::toVo)
                .toList();
        log.info("[AsrHotwordFacade] confirmExtractedHotwords end, userId={}, saved={}", userId, result.size());
        return result;
    }

    private AsrHotword toEntity(SaveAsrHotwordRequest request) {
        AsrHotword hotword = new AsrHotword();
        if (request == null) {
            return hotword;
        }
        hotword.setPhrase(request.getPhrase());
        hotword.setLanguage(request.getLanguage());
        hotword.setCategory(request.getCategory());
        hotword.setWeight(request.getWeight());
        hotword.setEnabled(request.getEnabled());
        hotword.setExpiresAt(request.getExpiresAt());
        return hotword;
    }

    private AsrHotwordVo toVo(AsrHotword hotword) {
        return AsrHotwordVo.builder()
                .id(hotword.getId())
                .userId(hotword.getUserId())
                .phrase(hotword.getPhrase())
                .language(hotword.getLanguage())
                .category(hotword.getCategory())
                .weight(hotword.getWeight())
                .sourceType(hotword.getSourceType())
                .sourceTerminologyId(hotword.getSourceTerminologyId())
                .enabled(hotword.getEnabled())
                .expiresAt(hotword.getExpiresAt())
                .lastUsedTime(hotword.getLastUsedTime())
                .build();
    }
}
