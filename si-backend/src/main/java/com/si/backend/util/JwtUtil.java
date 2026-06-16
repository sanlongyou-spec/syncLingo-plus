package com.si.backend.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * HS256 JWT utility used for user access tokens.
 *
 * <p>P5 keeps read compatibility for the previous "si.payload.signature" token
 * until all online clients naturally rotate to standard JWTs.
 */
public final class JwtUtil {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String JWT_ALGORITHM = "HS256";
    private static final String JWT_TYPE = "JWT";
    private static final Base64.Encoder BASE64_URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder BASE64_URL_DECODER = Base64.getUrlDecoder();

    private JwtUtil() {}

    /** Verified token claims; tokenVersion is compared with the current DB value by JwtAuthFilter. */
    public record TokenClaims(long userId, int tokenVersion, long issuedAtEpochSecond,
                              long expiresAtEpochSecond, String jti) {}

    public static String createToken(long userId, String username, int tokenVersion, long expirationMs, String secret) {
        long issuedAt = Instant.now().getEpochSecond();
        long expiresAt = Instant.now().plusMillis(expirationMs).getEpochSecond();
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("alg", JWT_ALGORITHM);
        header.put("typ", JWT_TYPE);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sub", String.valueOf(userId));
        payload.put("name", username);
        payload.put("iat", issuedAt);
        payload.put("exp", expiresAt);
        payload.put("jti", UUID.randomUUID().toString());
        payload.put("tv", tokenVersion);

        String encodedHeader = base64Json(header);
        String encodedPayload = base64Json(payload);
        String signingInput = encodedHeader + "." + encodedPayload;
        return signingInput + "." + hmacSha256(signingInput, secret);
    }

    /**
     * Verifies signature and expiry. Returns null for any malformed, expired, or tampered token.
     */
    public static TokenClaims verifyAndParseClaims(String token, String secret) {
        try {
            if (token == null) return null;
            if (token.startsWith("Bearer ")) token = token.substring(7);

            String[] parts = token.split("\\.", -1);
            if (parts.length != 3) return null;
            if ("si".equals(parts[0])) {
                return verifyAndParseLegacyToken(parts, secret);
            }
            return verifyAndParseStandardJwt(parts, secret);
        } catch (Exception e) {
            return null;
        }
    }

    public static Long verifyAndParseUserId(String token, String secret) {
        TokenClaims claims = verifyAndParseClaims(token, secret);
        return claims == null ? null : claims.userId();
    }

    private static TokenClaims verifyAndParseStandardJwt(String[] parts, String secret) throws Exception {
        String signingInput = parts[0] + "." + parts[1];
        if (!constantTimeEquals(hmacSha256(signingInput, secret), parts[2])) return null;

        JsonNode header = JSON.readTree(BASE64_URL_DECODER.decode(parts[0]));
        if (!JWT_ALGORITHM.equals(header.path("alg").asText())) return null;

        JsonNode payload = JSON.readTree(BASE64_URL_DECODER.decode(parts[1]));
        String subject = payload.path("sub").asText(null);
        long expiresAt = payload.path("exp").asLong(0);
        long issuedAt = payload.path("iat").asLong(0);
        String jti = payload.path("jti").asText(null);
        if (subject == null || subject.isBlank() || expiresAt <= 0 || issuedAt <= 0 || jti == null || jti.isBlank()) {
            return null;
        }
        if (Instant.now().getEpochSecond() >= expiresAt) return null;

        int tokenVersion = payload.path("tv").asInt(0);
        return new TokenClaims(Long.parseLong(subject), tokenVersion, issuedAt, expiresAt, jti);
    }

    private static TokenClaims verifyAndParseLegacyToken(String[] parts, String secret) {
        String encodedPayload = parts[1];
        if (!constantTimeEquals(hmacSha256(encodedPayload, secret), parts[2])) return null;

        String payload = new String(BASE64_URL_DECODER.decode(encodedPayload), StandardCharsets.UTF_8);
        String[] fields = payload.split(":", 4);
        if (fields.length < 3) return null;

        long expiresAtMs = Long.parseLong(fields[2]);
        if (Instant.now().toEpochMilli() > expiresAtMs) return null;

        int tokenVersion = fields.length >= 4 ? parseVersion(fields[3]) : 0;
        return new TokenClaims(Long.parseLong(fields[0]), tokenVersion, 0, expiresAtMs / 1000, null);
    }

    private static int parseVersion(String raw) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String base64Json(Map<String, Object> value) {
        try {
            return BASE64_URL_ENCODER.encodeToString(JSON.writeValueAsBytes(value));
        } catch (Exception e) {
            throw new RuntimeException("Token JSON serialization failed", e);
        }
    }

    private static String hmacSha256(String data, String secret) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            return BASE64_URL_ENCODER.encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException("Token signing failed", e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
