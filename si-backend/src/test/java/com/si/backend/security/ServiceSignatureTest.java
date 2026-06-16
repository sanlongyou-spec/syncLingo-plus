package com.si.backend.security;

import com.si.backend.common.BizException;
import com.si.backend.config.ServiceSignatureProperties;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * P4 服务签名:签发-验签往返、防篡改、时钟窗口、重放、错误密钥、缺头。
 */
class ServiceSignatureTest {

    private static final String SECRET = "downstream_key_at_least_32_chars_long_xx";
    private static final String KEY_ID = "java-proxy";

    private ServiceSignature newSig() {
        ServiceSignatureProperties props = new ServiceSignatureProperties();
        props.setDownstreamKey(SECRET);
        props.setMaxSkewSeconds(300);
        props.setNonceTtlSeconds(600);
        return new ServiceSignature(props);
    }

    @Test
    void signThenVerify_roundTrip_passes() {
        ServiceSignature sig = newSig();
        byte[] body = "{\"meetingId\":7}".getBytes();
        Map<String, String> headers = sig.sign(SECRET, KEY_ID, "POST", "/api/meetings/summary", "", body);
        assertDoesNotThrow(() ->
                sig.verify(SECRET, headers, "POST", "/api/meetings/summary", "", body));
    }

    @Test
    void tamperedBody_isRejected() {
        ServiceSignature sig = newSig();
        Map<String, String> headers = sig.sign(SECRET, KEY_ID, "POST", "/p", "", "a".getBytes());
        BizException ex = assertThrows(BizException.class,
                () -> sig.verify(SECRET, headers, "POST", "/p", "", "b".getBytes()));
        assertEquals(401, ex.getCode());
    }

    @Test
    void tamperedPathOrMethod_isRejected() {
        ServiceSignature sig = newSig();
        byte[] body = "x".getBytes();
        Map<String, String> headers = sig.sign(SECRET, KEY_ID, "POST", "/a", "", body);
        assertThrows(BizException.class, () -> sig.verify(SECRET, headers, "POST", "/b", "", body));
        assertThrows(BizException.class, () -> sig.verify(SECRET, headers, "GET", "/a", "", body));
    }

    @Test
    void wrongKey_isRejected() {
        ServiceSignature sig = newSig();
        byte[] body = "x".getBytes();
        Map<String, String> headers = sig.sign(SECRET, KEY_ID, "POST", "/a", "", body);
        BizException ex = assertThrows(BizException.class,
                () -> sig.verify("another_secret_32_chars_xxxxxxxxxxxxxxxx", headers, "POST", "/a", "", body));
        assertEquals(401, ex.getCode());
    }

    @Test
    void expiredTimestamp_isRejected() {
        ServiceSignature sig = newSig();
        byte[] body = "x".getBytes();
        Map<String, String> headers = new HashMap<>(sig.sign(SECRET, KEY_ID, "POST", "/a", "", body));
        long old = Instant.now().getEpochSecond() - 4000;
        headers.put(ServiceSignature.HEADER_TIMESTAMP, String.valueOf(old));
        // 时间戳被改后,即便签名头其余不变也应因窗口失败(此处主要验证窗口校验生效)
        BizException ex = assertThrows(BizException.class,
                () -> sig.verify(SECRET, headers, "POST", "/a", "", body));
        assertEquals(401, ex.getCode());
    }

    @Test
    void replayedNonce_isRejected() {
        ServiceSignature sig = newSig();
        byte[] body = "x".getBytes();
        Map<String, String> headers = sig.sign(SECRET, KEY_ID, "POST", "/a", "", body);
        assertDoesNotThrow(() -> sig.verify(SECRET, headers, "POST", "/a", "", body));
        BizException ex = assertThrows(BizException.class,
                () -> sig.verify(SECRET, headers, "POST", "/a", "", body));
        assertEquals(401, ex.getCode());
    }

    @Test
    void missingHeaders_isRejected() {
        ServiceSignature sig = newSig();
        assertThrows(BizException.class,
                () -> sig.verify(SECRET, Map.of(), "POST", "/a", "", "x".getBytes()));
    }

    @Test
    void crossRuntimeVector_matchesDotNetReference() {
        // 固定输入下,Java 必须产出与 .NET(C# Bot)相同的签名,保证跨运行时互验。
        // 参考值由独立 .NET 实现(PowerShell)对相同 canonical 计算得到。
        String canonical = ServiceSignature.canonical(
                "java-backend", "1700000000", "abc123",
                "POST", "/api/meetings/summary", "", "{\"x\":1}".getBytes());
        String signature = ServiceSignature.hmacBase64("test_secret_key_at_least_32_chars_xxxxxx", canonical);
        assertEquals("258QrV2Z2eWLck5cYzmmvFoEeAytfcd8htB2uwqpUi4=", signature);
    }

    @Test
    void hasSignatureHeaders_detectsPresence() {
        ServiceSignature sig = newSig();
        Map<String, String> headers = sig.sign(SECRET, KEY_ID, "POST", "/a", "", "x".getBytes());
        org.junit.jupiter.api.Assertions.assertTrue(sig.hasSignatureHeaders(headers));
        org.junit.jupiter.api.Assertions.assertFalse(sig.hasSignatureHeaders(Map.of()));
    }
}
