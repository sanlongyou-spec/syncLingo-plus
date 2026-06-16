package com.si.backend.facade;

import com.si.backend.dto.QaEvaluationRequest;
import com.si.backend.dto.QaEvaluationRunRequest;
import com.si.backend.dto.TeamsBotQueryRequest;
import com.si.backend.config.TeamsBotProperties;
import com.si.backend.service.QaEvaluationService;
import com.si.backend.service.TeamsBotQueryService;
import com.si.backend.vo.QaEvaluationRunResponseVo;
import com.si.backend.vo.QaEvaluationResponseVo;
import com.si.backend.vo.TeamsBotQueryResponse;
import com.si.backend.vo.TeamsBotQuerySourceVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class QaEvaluationFacade {

    private final QaEvaluationService qaEvaluationService;
    private final TeamsBotQueryService teamsBotQueryService;
    private final TeamsBotProperties teamsBotProperties;

    public QaEvaluationResponseVo evaluate(QaEvaluationRequest request) {
        List<QaEvaluationService.QaEvaluationCase> cases = request.getCases().stream()
                .map(item -> new QaEvaluationService.QaEvaluationCase(
                        item.getId(),
                        item.getQuestion(),
                        nullToEmpty(item.getExpectedAnswerPoints()),
                        nullToEmpty(item.getExpectedSourceKeywords())))
                .toList();
        List<QaEvaluationService.QaEvaluationAnswer> answers = request.getAnswers().stream()
                .map(item -> new QaEvaluationService.QaEvaluationAnswer(
                        item.getAnswer(),
                        sourceVos(item.getSources())))
                .toList();
        List<QaEvaluationService.QaEvaluationResult> results = qaEvaluationService.evaluateAll(
                cases,
                answers,
                request.getMinAnswerCoverage(),
                request.getMinSourceCoverage());
        long passed = results.stream().filter(QaEvaluationService.QaEvaluationResult::passed).count();
        return QaEvaluationResponseVo.builder()
                .total(results.size())
                .passed((int) passed)
                .failed(results.size() - (int) passed)
                .minAnswerCoverage(request.getMinAnswerCoverage())
                .minSourceCoverage(request.getMinSourceCoverage())
                .results(results.stream().map(this::toVo).toList())
                .build();
    }

    public QaEvaluationRunResponseVo run(QaEvaluationRunRequest request) {
        log.info("[QaEvaluationFacade] run start, cases={}", request.getCases().size());
        List<QaEvaluationRunResponseVo.QaEvaluationGeneratedAnswerVo> generated = new ArrayList<>();
        List<QaEvaluationRequest.QaEvaluationCaseItem> scoreCases = new ArrayList<>();
        List<QaEvaluationRequest.QaEvaluationAnswerItem> scoreAnswers = new ArrayList<>();
        for (QaEvaluationRunRequest.QaEvaluationRunCaseItem testCase : request.getCases()) {
            TeamsBotQueryRequest queryRequest = buildTeamsBotQueryRequest(request, testCase);
            TeamsBotQueryResponse response = teamsBotQueryService.query(
                    queryRequest,
                    teamsBotProperties.getApiSecret());

            generated.add(QaEvaluationRunResponseVo.QaEvaluationGeneratedAnswerVo.builder()
                    .id(testCase.getId())
                    .question(testCase.getQuestion())
                    .replyText(response.getReplyText())
                    .responseType(response.getResponseType())
                    .userMatched(response.getUserMatched())
                    .userId(response.getUserId())
                    .command(response.getCommand())
                    .sourceCount(nullToEmpty(response.getSources()).size())
                    .sources(nullToEmpty(response.getSources()))
                    .build());
            scoreCases.add(toScoreCase(testCase));
            scoreAnswers.add(toScoreAnswer(response));
        }

        QaEvaluationRequest scoreRequest = new QaEvaluationRequest();
        scoreRequest.setMinAnswerCoverage(request.getMinAnswerCoverage());
        scoreRequest.setMinSourceCoverage(request.getMinSourceCoverage());
        scoreRequest.setCases(scoreCases);
        scoreRequest.setAnswers(scoreAnswers);
        QaEvaluationResponseVo evaluation = evaluate(scoreRequest);
        log.info("[QaEvaluationFacade] run end, cases={}, passed={}, failed={}",
                evaluation.getTotal(), evaluation.getPassed(), evaluation.getFailed());
        return QaEvaluationRunResponseVo.builder()
                .evaluation(evaluation)
                .generatedAnswers(generated)
                .build();
    }

    private TeamsBotQueryRequest buildTeamsBotQueryRequest(
            QaEvaluationRunRequest request,
            QaEvaluationRunRequest.QaEvaluationRunCaseItem testCase) {
        TeamsBotQueryRequest queryRequest = new TeamsBotQueryRequest();
        queryRequest.setAadId(request.getAadId());
        queryRequest.setMail(request.getMail());
        queryRequest.setUserPrincipalName(request.getUserPrincipalName());
        queryRequest.setDisplayName(request.getDisplayName());
        queryRequest.setMessage(testCase.getQuestion());
        queryRequest.setHistory(testCase.getHistory());
        return queryRequest;
    }

    private QaEvaluationRequest.QaEvaluationCaseItem toScoreCase(
            QaEvaluationRunRequest.QaEvaluationRunCaseItem testCase) {
        QaEvaluationRequest.QaEvaluationCaseItem item = new QaEvaluationRequest.QaEvaluationCaseItem();
        item.setId(testCase.getId());
        item.setQuestion(testCase.getQuestion());
        item.setExpectedAnswerPoints(testCase.getExpectedAnswerPoints());
        item.setExpectedSourceKeywords(testCase.getExpectedSourceKeywords());
        return item;
    }

    private QaEvaluationRequest.QaEvaluationAnswerItem toScoreAnswer(TeamsBotQueryResponse response) {
        QaEvaluationRequest.QaEvaluationAnswerItem answer = new QaEvaluationRequest.QaEvaluationAnswerItem();
        answer.setAnswer(response.getReplyText());
        answer.setSources(sourceItems(response.getSources()));
        return answer;
    }

    private QaEvaluationResponseVo.QaEvaluationResultVo toVo(QaEvaluationService.QaEvaluationResult result) {
        return QaEvaluationResponseVo.QaEvaluationResultVo.builder()
                .id(result.id())
                .answerPointHits(result.answerPointHits())
                .expectedAnswerPoints(result.expectedAnswerPoints())
                .sourceKeywordHits(result.sourceKeywordHits())
                .expectedSourceKeywords(result.expectedSourceKeywords())
                .answerCoverage(result.answerCoverage())
                .sourceCoverage(result.sourceCoverage())
                .passed(result.passed())
                .build();
    }

    private List<TeamsBotQuerySourceVo> sourceVos(List<QaEvaluationRequest.QaEvaluationSourceItem> sources) {
        return nullToEmpty(sources).stream()
                .map(source -> TeamsBotQuerySourceVo.builder()
                        .sourceType(source.getSourceType())
                        .title(source.getTitle())
                        .meetingTitle(source.getMeetingTitle())
                        .sessionId(source.getSessionId())
                        .meetingId(source.getMeetingId())
                        .fileId(source.getFileId())
                        .sourceName(source.getSourceName())
                        .sourceDate(source.getSourceDate())
                        .snippet(source.getSnippet())
                        .score(source.getScore())
                        .build())
                .toList();
    }

    private List<QaEvaluationRequest.QaEvaluationSourceItem> sourceItems(List<TeamsBotQuerySourceVo> sources) {
        return nullToEmpty(sources).stream()
                .map(source -> {
                    QaEvaluationRequest.QaEvaluationSourceItem item = new QaEvaluationRequest.QaEvaluationSourceItem();
                    item.setSourceType(source.getSourceType());
                    item.setTitle(source.getTitle());
                    item.setMeetingTitle(source.getMeetingTitle());
                    item.setSessionId(source.getSessionId());
                    item.setMeetingId(source.getMeetingId());
                    item.setFileId(source.getFileId());
                    item.setSourceName(source.getSourceName());
                    item.setSourceDate(source.getSourceDate());
                    item.setSnippet(source.getSnippet());
                    item.setScore(source.getScore());
                    return item;
                })
                .toList();
    }

    private static <T> List<T> nullToEmpty(List<T> values) {
        return values == null ? List.of() : values;
    }
}
