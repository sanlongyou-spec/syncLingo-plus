package com.si.backend.controller;

import com.si.backend.common.BizException;
import com.si.backend.common.Constants;
import com.si.backend.common.Result;
import com.si.backend.config.AppAdminProperties;
import com.si.backend.dto.QaEvaluationRequest;
import com.si.backend.dto.QaEvaluationRunRequest;
import com.si.backend.facade.QaEvaluationFacade;
import com.si.backend.vo.QaEvaluationResponseVo;
import com.si.backend.vo.QaEvaluationRunResponseVo;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AdminControllerQaEvaluationTest {

    private static final String ADMIN_SECRET = "admin-secret";

    @Test
    void scoreQaEvaluationRequiresAdminSecretBeforeFacadeCall() {
        QaEvaluationFacade facade = mock(QaEvaluationFacade.class);
        AdminController controller = controller(facade);
        QaEvaluationRequest request = scoreRequest();

        BizException error = assertThrows(BizException.class,
                () -> controller.scoreQaEvaluation("bad-secret", request));

        assertEquals(Constants.HTTP_UNAUTHORIZED, error.getCode());
        verifyNoInteractions(facade);
    }

    @Test
    void scoreQaEvaluationReturnsFacadeResultWhenAuthorized() {
        QaEvaluationFacade facade = mock(QaEvaluationFacade.class);
        AdminController controller = controller(facade);
        QaEvaluationRequest request = scoreRequest();
        QaEvaluationResponseVo response = QaEvaluationResponseVo.builder()
                .total(1)
                .passed(1)
                .failed(0)
                .results(List.of())
                .build();
        when(facade.evaluate(request)).thenReturn(response);

        Result<QaEvaluationResponseVo> result = controller.scoreQaEvaluation(ADMIN_SECRET, request);

        assertEquals(Constants.HTTP_OK, result.getCode());
        assertEquals(response, result.getData());
        verify(facade).evaluate(request);
    }

    @Test
    void runQaEvaluationRequiresAdminSecretBeforeFacadeCall() {
        QaEvaluationFacade facade = mock(QaEvaluationFacade.class);
        AdminController controller = controller(facade);
        QaEvaluationRunRequest request = runRequest();

        BizException error = assertThrows(BizException.class,
                () -> controller.runQaEvaluation(null, request));

        assertEquals(Constants.HTTP_UNAUTHORIZED, error.getCode());
        verifyNoInteractions(facade);
    }

    @Test
    void runQaEvaluationReturnsGeneratedReportWhenAuthorized() {
        QaEvaluationFacade facade = mock(QaEvaluationFacade.class);
        AdminController controller = controller(facade);
        QaEvaluationRunRequest request = runRequest();
        QaEvaluationResponseVo evaluation = QaEvaluationResponseVo.builder()
                .total(1)
                .passed(1)
                .failed(0)
                .results(List.of())
                .build();
        QaEvaluationRunResponseVo response = QaEvaluationRunResponseVo.builder()
                .evaluation(evaluation)
                .generatedAnswers(List.of())
                .build();
        when(facade.run(request)).thenReturn(response);

        Result<QaEvaluationRunResponseVo> result = controller.runQaEvaluation(ADMIN_SECRET, request);

        assertEquals(Constants.HTTP_OK, result.getCode());
        assertEquals(response, result.getData());
        verify(facade).run(request);
    }

    private AdminController controller(QaEvaluationFacade facade) {
        AppAdminProperties properties = new AppAdminProperties();
        properties.setApiSecret(ADMIN_SECRET);
        return new AdminController(null, null, null, facade, properties);
    }

    private QaEvaluationRequest scoreRequest() {
        QaEvaluationRequest request = new QaEvaluationRequest();
        QaEvaluationRequest.QaEvaluationCaseItem testCase = new QaEvaluationRequest.QaEvaluationCaseItem();
        testCase.setId("case-1");
        testCase.setQuestion("Who owns the deadline?");
        request.setCases(List.of(testCase));
        QaEvaluationRequest.QaEvaluationAnswerItem answer = new QaEvaluationRequest.QaEvaluationAnswerItem();
        answer.setAnswer("Alice owns the deadline.");
        request.setAnswers(List.of(answer));
        return request;
    }

    private QaEvaluationRunRequest runRequest() {
        QaEvaluationRunRequest request = new QaEvaluationRunRequest();
        request.setMail("alice@example.com");
        QaEvaluationRunRequest.QaEvaluationRunCaseItem testCase =
                new QaEvaluationRunRequest.QaEvaluationRunCaseItem();
        testCase.setId("case-1");
        testCase.setQuestion("Who owns the deadline?");
        request.setCases(List.of(testCase));
        return request;
    }
}
