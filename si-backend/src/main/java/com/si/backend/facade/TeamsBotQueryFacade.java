package com.si.backend.facade;

import com.si.backend.dto.TeamsBotQueryRequest;
import com.si.backend.service.TeamsBotQueryService;
import com.si.backend.vo.TeamsBotQueryResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Facade for Teams Bot conversational queries.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TeamsBotQueryFacade {

    private final TeamsBotQueryService teamsBotQueryService;

    public TeamsBotQueryResponse query(TeamsBotQueryRequest request, String apiSecret) {
        log.info("[TeamsBotQueryFacade] query start, aadId={}, upn={}",
                request.getAadId(), request.getUserPrincipalName());
        TeamsBotQueryResponse response = teamsBotQueryService.query(request, apiSecret);
        log.info("[TeamsBotQueryFacade] query end, command={}, userMatched={}",
                response.getCommand(), response.getUserMatched());
        return response;
    }

    public void queryStream(TeamsBotQueryRequest request, String apiSecret, SseEmitter emitter) {
        log.info("[TeamsBotQueryFacade] queryStream start, aadId={}", request.getAadId());
        teamsBotQueryService.queryStream(request, apiSecret, emitter);
    }
}
