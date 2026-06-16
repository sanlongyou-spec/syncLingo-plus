package com.si.backend.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P5 JWT: standard HS256 claims, legacy token compatibility, expiry, and tamper rejection.
 */
class JwtUtilTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SECRET = "unit-test-secret-key-0123456789-abcdefgh";

    @Test
    void createToken_issuesStandardJwtWithRequiredClaims() throws Exception {
        String token = JwtUtil.createToken(42L, "operator", 3, 60_000L, SECRET);
        assertFalse(token.startsWith("si."), "new tokens must use RFC 7519 JWT structure");

        String[] parts = token.split("\\.");
        assertEquals(3, parts.length);
        JsonNode header = decodeJson(parts[0]);
        JsonNode payload = decodeJson(parts[1]);

        assertEquals("HS256", header.path("alg").asText());
        assertEquals("JWT", header.path("typ").asText());
        assertEquals("42", payload.path("sub").asText());
        assertEquals("operator", payload.path("name").asText());
        assertEquals(3, payload.path("tv").asInt());
        assertTrue(payload.path("iat").asLong() > 0);
        assertTrue(payload.path("exp").asLong() >= payload.path("iat").asLong());
        assertFalse(payload.path("jti").asText().isBlank());
    }

    @Test
    void createAndParse_preservesUserIdTokenVersionAndJwtClaims() {
        String token = JwtUtil.createToken(42L, "operator", 3, 60_000L, SECRET);
        JwtUtil.TokenClaims claims = JwtUtil.verifyAndParseClaims(token, SECRET);

        assertNotNull(claims);
        assertEquals(42L, claims.userId());
        assertEquals(3, claims.tokenVersion());
        assertTrue(claims.issuedAtEpochSecond() > 0);
        assertTrue(claims.expiresAtEpochSecond() >= Instant.now().getEpochSecond());
        assertNotNull(claims.jti());
        assertFalse(claims.jti().isBlank());
    }

    @Test
    void legacyThreeFieldToken_parsesAsVersionZeroDuringCompatibilityWindow() {
        long expiresAt = System.currentTimeMillis() + 60_000L;
        String legacyPayload = "7:operator:" + expiresAt;
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(legacyPayload.getBytes(StandardCharsets.UTF_8));
        String token = "si." + encoded + "." + sign(encoded);

        JwtUtil.TokenClaims claims = JwtUtil.verifyAndParseClaims(token, SECRET);

        assertNotNull(claims);
        assertEquals(7L, claims.userId());
        assertEquals(0, claims.tokenVersion());
        assertEquals(0, claims.issuedAtEpochSecond());
        assertNull(claims.jti());
    }

    @Test
    void expiredToken_isRejected() {
        String token = JwtUtil.createToken(1L, "u", 0, -1_000L, SECRET);
        assertNull(JwtUtil.verifyAndParseClaims(token, SECRET));
    }

    @Test
    void missingRequiredJwtClaim_isRejected() {
        String token = standardJwt("{\"alg\":\"HS256\",\"typ\":\"JWT\"}",
                "{\"sub\":\"1\",\"iat\":100,\"exp\":9999999999,\"tv\":0}");

        assertNull(JwtUtil.verifyAndParseClaims(token, SECRET));
    }

    @Test
    void tamperedSignature_isRejected() {
        String token = JwtUtil.createToken(1L, "u", 0, 60_000L, SECRET);
        assertNull(JwtUtil.verifyAndParseClaims(token + "x", SECRET));
        assertNull(JwtUtil.verifyAndParseClaims(token, "wrong-secret-key-0123456789-abcdefgh"));
    }

    @Test
    void unsupportedAlgorithm_isRejected() {
        String token = standardJwt("{\"alg\":\"none\",\"typ\":\"JWT\"}",
                "{\"sub\":\"1\",\"iat\":100,\"exp\":9999999999,\"jti\":\"id\",\"tv\":0}");

        assertNull(JwtUtil.verifyAndParseClaims(token, SECRET));
    }

    private JsonNode decodeJson(String encoded) throws Exception {
        return JSON.readTree(Base64.getUrlDecoder().decode(encoded));
    }

    private String standardJwt(String headerJson, String payloadJson) {
        String header = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(headerJson.getBytes(StandardCharsets.UTF_8));
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
        String signingInput = header + "." + payload;
        return signingInput + "." + sign(signingInput);
    }

    private String sign(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
