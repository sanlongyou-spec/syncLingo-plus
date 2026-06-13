package com.si.backend.util;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

/**
 * Minimal JWT-like token: si.{base64(userId:username:expiresAt)}.{hmac-sha256}
 * Format matches the 3-part "si.PAYLOAD.SIG" the frontend expects.
 */
public final class JwtUtil {

    private static final String ALGORITHM = "HmacSHA256";

    private JwtUtil() {}

    public static String createToken(long userId, String username, long expirationMs, String secret) {
        long expiresAt = Instant.now().toEpochMilli() + expirationMs;
        String payload = userId + ":" + username + ":" + expiresAt;
        String encodedPayload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        String signature = hmacSha256(encodedPayload, secret);
        return "si." + encodedPayload + "." + signature;
    }

    /**
     * Verifies HMAC signature and expiry. Returns userId on success, null on any failure.
     */
    public static Long verifyAndParseUserId(String token, String secret) {
        try {
            if (token == null) return null;
            if (token.startsWith("Bearer ")) token = token.substring(7);

            String[] parts = token.split("\\.", -1);
            if (parts.length != 3 || !"si".equals(parts[0])) return null;

            String encodedPayload = parts[1];
            String signature = parts[2];

            // Constant-time comparison prevents timing attacks
            if (!constantTimeEquals(hmacSha256(encodedPayload, secret), signature)) return null;

            String payload = new String(Base64.getUrlDecoder().decode(encodedPayload), StandardCharsets.UTF_8);
            // split limit=3 so usernames with ':' don't break parsing
            String[] fields = payload.split(":", 3);
            if (fields.length != 3) return null;

            long expiresAt = Long.parseLong(fields[2]);
            if (Instant.now().toEpochMilli() > expiresAt) return null;

            return Long.parseLong(fields[0]);
        } catch (Exception e) {
            return null;
        }
    }

    private static String hmacSha256(String data, String secret) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException("Token signing failed", e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) return false;
        int diff = 0;
        for (int i = 0; i < a.length(); i++) diff |= a.charAt(i) ^ b.charAt(i);
        return diff == 0;
    }
}
