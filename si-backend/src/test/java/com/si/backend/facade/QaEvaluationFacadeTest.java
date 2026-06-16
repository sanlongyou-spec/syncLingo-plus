package com.si.backend.facade;

import com.si.backend.config.TeamsBotProperties;
import com.si.backend.dto.QaEvaluationRequest;
import com.si.backend.dto.QaEvaluationRunRequest;
import com.si.backend.dto.TeamsBotQueryRequest;
import com.si.backend.service.QaEvaluationService;
import com.si.backend.service.TeamsBotQueryService;
import com.si.backend.vo.QaEvaluationRunResponseVo;
import com.si.backend.vo.QaEvaluationResponseVo;
import com.si.backend.vo.TeamsBotQueryResponse;
import com.si.backend.vo.TeamsBotQuerySourceVo;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QaEvaluationFacadeTest {

    @Test
    void evaluateMapsRequestAndReturnsSummary() {
        QaEvaluationFacade facade = new QaEvaluationFacade(new QaEvaluationService(), null, null);
        QaEvaluationRequest request = new QaEvaluationRequest();
        request.setMinAnswerCoverage(0.5);
        request.setMinSourceCoverage(0.5);

        QaEvaluationRequest.QaEvaluationCaseItem testCase = new QaEvaluationRequest.QaEvaluationCaseItem();
        testCase.setId("baseline-1");
        testCase.setQuestion("Who owns the deadline?");
        testCase.setExpectedAnswerPoints(List.of("Alice", "Friday"));
        testCase.setExpectedSourceKeywords(List.of("Sprint planning", "00:02:10"));
        request.setCases(List.of(testCase));

        QaEvaluationRequest.QaEvaluationAnswerItem answer = new QaEvaluationRequest.QaEvaluationAnswerItem();
        answer.setAnswer("Alice owns the follow-up before Friday.");
        QaEvaluationRequest.QaEvaluationSourceItem source = new QaEvaluationRequest.QaEvaluationSourceItem();
        source.setMeetingTitle("Sprint planning");
        source.setSnippet("00:02:10 Alice accepted the deadline.");
        answer.setSources(List.of(source));
        request.setAnswers(List.of(answer));

        QaEvaluationResponseVo response = facade.evaluate(request);

        assertEquals(1, response.getTotal());
        assertEquals(1, response.getPassed());
        assertEquals(0, response.getFailed());
        assertEquals(0.5, response.getMinAnswerCoverage());
        assertEquals(0.5, response.getMinSourceCoverage());
        assertTrue(response.getResults().get(0).isPassed());
        assertEquals(2, response.getResults().get(0).getAnswerPointHits());
        assertEquals(2, response.getResults().get(0).getSourceKeywordHits());
    }

    @Test
    void runCallsTeamsBotAndScoresGeneratedAnswers() {
        TeamsBotQueryService teamsBotQueryService = mock(TeamsBotQueryService.class);
        TeamsBotProperties teamsBotProperties = new TeamsBotProperties();
        teamsBotProperties.setApiSecret("bot-secret");
        QaEvaluationFacade facade = new QaEvaluationFacade(
                new QaEvaluationService(),
                teamsBotQueryService,
                teamsBotProperties);

        QaEvaluationRunRequest request = new QaEvaluationRunRequest();
        request.setMail("alice@example.com");
        request.setDisplayName("Alice");
        request.setMinAnswerCoverage(0.5);
        request.setMinSourceCoverage(0.5);
        QaEvaluationRunRequest.QaEvaluationRunCaseItem testCase =
                new QaEvaluationRunRequest.QaEvaluationRunCaseItem();
        testCase.setId("case-1");
        testCase.setQuestion("Who owns the deadline?");
        testCase.setExpectedAnswerPoints(List.of("Alice", "Friday"));
        testCase.setExpectedSourceKeywords(List.of("Sprint planning"));
        request.setCases(List.of(testCase));

        TeamsBotQuerySourceVo source = TeamsBotQuerySourceVo.builder()
                .meetingTitle("Sprint planning")
                .snippet("Alice accepted the Friday deadline.")
                .build();
        when(teamsBotQueryService.query(org.mockito.ArgumentMatchers.any(TeamsBotQueryRequest.class),
                org.mockito.ArgumentMatchers.eq("bot-secret")))
                .thenReturn(TeamsBotQueryResponse.builder()
                        .replyText("Alice owns the follow-up before Friday.")
                        .responseType("rag")
                        .sources(List.of(source))
                        .userMatched(true)
                        .userId(9L)
                        .command("ask")
                        .build());

        QaEvaluationRunResponseVo response = facade.run(request);

        assertEquals(1, response.getEvaluation().getTotal());
        assertEquals(1, response.getEvaluation().getPassed());
        assertEquals(1, response.getGeneratedAnswers().size());
        assertEquals("case-1", response.getGeneratedAnswers().get(0).getId());
        assertEquals(1, response.getGeneratedAnswers().get(0).getSourceCount());
        ArgumentCaptor<TeamsBotQueryRequest> queryCaptor = ArgumentCaptor.forClass(TeamsBotQueryRequest.class);
        verify(teamsBotQueryService).query(queryCaptor.capture(), org.mockito.ArgumentMatchers.eq("bot-secret"));
        assertEquals("alice@example.com", queryCaptor.getValue().getMail());
        assertEquals("Who owns the deadline?", queryCaptor.getValue().getMessage());
    }
}
