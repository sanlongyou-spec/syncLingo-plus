package com.si.backend.service;

import com.si.backend.entity.MeetingActionItem;
import com.si.backend.entity.InterpretationSession;
import com.si.backend.integration.LlmIntegration;
import com.si.backend.mapper.InterpretationResultMapper;
import com.si.backend.mapper.MeetingActionItemMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import com.si.backend.security.AuthenticatedActor;
import com.si.backend.common.BizException;
import com.si.backend.common.ErrorCode;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class MeetingActionItemService {

    private final MeetingActionItemMapper actionItemMapper;
    private final InterpretationResultMapper resultMapper;
    private final LlmIntegration llmIntegration;
    private final ContentEmbeddingService contentEmbeddingService;
    private final ResourceOwnershipPolicy resourceOwnershipPolicy;

    @PostConstruct
    public void initTable() {
        actionItemMapper.createTableIfNotExists();
    }

    /**
     * Extract action items from the full transcript of a session via LLM.
     * Saves each line as a separate MeetingActionItem row.
     */
    public List<MeetingActionItem> extractAndSave(
            AuthenticatedActor actor,
            String sessionId,
            Long requestedMeetingId
    ) {
        InterpretationSession session = resourceOwnershipPolicy.requireOwnedSession(actor, sessionId);
        if (requestedMeetingId != null && !requestedMeetingId.equals(session.getMeetingId())) {
            throw BizException.of(ErrorCode.NOT_FOUND, "Resource not found");
        }
        Long meetingId = session.getMeetingId();
        Long userId = actor.userId();
        log.info("[MeetingActionItemService] extractAndSave start, sessionId={}", sessionId);

        var results = resultMapper.findBySessionId(sessionId);
        if (results.isEmpty()) return List.of();

        StringBuilder transcript = new StringBuilder();
        for (var r : results) {
            if (r.getSpeakerName() != null && !r.getSpeakerName().isBlank()) {
                transcript.append(r.getSpeakerName()).append(": ");
            }
            transcript.append(r.getSourceText()).append("\n");
        }

        try {
            String raw = llmIntegration.extractActionItems(transcript.toString());
            List<MeetingActionItem> saved = new ArrayList<>();
            for (String line : raw.split("\n")) {
                String content = line.trim();
                if (content.isBlank() || content.equals("无")) continue;

                // Parse optional assignee: 【姓名】content
                String assignee = null;
                if (content.startsWith("【")) {
                    int end = content.indexOf('】');
                    if (end > 1) {
                        assignee = content.substring(1, end);
                        content = content.substring(end + 1).trim();
                    }
                }
                if (content.isBlank()) continue;

                MeetingActionItem item = new MeetingActionItem();
                item.setSessionId(sessionId);
                item.setMeetingId(meetingId);
                item.setUserId(userId);
                item.setAssignee(assignee);
                item.setContent(content);
                item.setStatus("pending");
                actionItemMapper.insert(item);
                saved.add(item);
                contentEmbeddingService.asyncEmbedActionItem(item);
            }
            log.info("[MeetingActionItemService] extractAndSave done, sessionId={}, items={}",
                    sessionId, saved.size());
            return saved;
        } catch (Exception e) {
            log.error("[MeetingActionItemService] extractAndSave failed, sessionId={}", sessionId, e);
            throw new RuntimeException("行动项提取失败: " + e.getMessage(), e);
        }
    }

    public List<MeetingActionItem> listBySessionId(AuthenticatedActor actor, String sessionId) {
        resourceOwnershipPolicy.requireOwnedSession(actor, sessionId);
        return actionItemMapper.findBySessionId(sessionId);
    }

    public MeetingActionItem updateStatus(AuthenticatedActor actor, Long id, String status) {
        resourceOwnershipPolicy.requireOwnedActionItem(actor, id);
        actionItemMapper.updateStatus(id, status);
        return actionItemMapper.findById(id);
    }

    public void delete(AuthenticatedActor actor, Long id) {
        resourceOwnershipPolicy.requireOwnedActionItem(actor, id);
        contentEmbeddingService.deleteByTypeAndRefId(ContentEmbeddingService.TYPE_ACTION_ITEM, id);
        actionItemMapper.deleteById(id);
    }
}
