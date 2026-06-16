package com.si.backend.security;

import com.si.backend.common.BizException;
import com.si.backend.config.ServiceSignatureProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * P4 服务间 HMAC-SHA256 请求签名。Java 与 C# Bot 共用同一规范串与算法:
 *
 * <pre>
 * canonical = keyId \n timestamp \n nonce \n METHOD \n path \n rawQuery \n hexSha256(body)
 * signature = Base64( HmacSHA256(secret, canonical) )
 * </pre>
 *
 * 防护:HMAC 完整性 + 时钟偏移窗口 + nonce 单次使用(进程内去重,防重放)。
 * 纯密码学部分为静态方法便于单测;实例方法持有 nonce 缓存并做窗口/重放校验。
 */
@Slf4j
@Component
public class ServiceSignature {

    public static final String HEADER_KEY_ID = "X-Svc-Key-Id";
    public static final String HEADER_TIMESTAMP = "X-Svc-Timestamp";
    public static final String HEADER_NONCE = "X-Svc-Nonce";
    public static final String HEADER_SIGNATURE = "X-Svc-Signature";

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final int MAX_NONCE_ENTRIES = 50_000;

    private final ServiceSignatureProperties properties;
    /** nonce -> 过期 epoch 秒;命中即视为重放。 */
    private final ConcurrentHashMap<String, Long> seenNonces = new ConcurrentHashMap<>();

    public ServiceSignature(ServiceSignatureProperties properties) {
        this.properties = properties;
    }

    /** 生成签名所需的全部请求头(出站方调用)。 */
    public Map<String, String> sign(String secret, String keyId, String method,
                                    String path, String rawQuery, byte[] body) {
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String nonce = UUID.randomUUID().toString().replace("-", "");
        String canonical = canonical(keyId, timestamp, nonce, method, path, rawQuery, body);
        String signature = hmacBase64(secret, canonical);
        return Map.of(
                HEADER_KEY_ID, keyId,
                HEADER_TIMESTAMP, timestamp,
                HEADER_NONCE, nonce,
                HEADER_SIGNATURE, signature
        );
    }

    /**
     * 验签(入站方调用)。失败抛 {@link BizException} 401。校验顺序:
     * 头齐全 → 时间窗口 → nonce 未用过 → HMAC 常量时间比对 → 记录 nonce。
     */
    public void verify(String secret, Map<String, String> headers, String method,
                       String path, String rawQuery, byte[] body) {
        String keyId = header(headers, HEADER_KEY_ID);
        String timestamp = header(headers, HEADER_TIMESTAMP);
        String nonce = header(headers, HEADER_NONCE);
        String signature = header(headers, HEADER_SIGNATURE);
        if (keyId.isBlank() || timestamp.isBlank() || nonce.isBlank() || signature.isBlank()) {
            throw unauthorized("缺少服务签名头");
        }

        long ts;
        try {
            ts = Long.parseLong(timestamp);
        } catch (NumberFormatException ex) {
            throw unauthorized("签名时间戳非法");
        }
        long skew = Math.abs(Instant.now().getEpochSecond() - ts);
        if (skew > properties.getMaxSkewSeconds()) {
            throw unauthorized("签名已过期或时钟偏移过大");
        }

        evictExpiredNonces();
        if (seenNonces.size() >= MAX_NONCE_ENTRIES) {
            // 缓存被打满时拒绝而非放行,防止重放保护被绕过。
            throw unauthorized("签名校验暂不可用");
        }

        String canonical = canonical(keyId, timestamp, nonce, method, path, rawQuery, body);
        String expected = hmacBase64(secret, canonical);
        if (!constantTimeEquals(expected, signature)) {
            throw unauthorized("服务签名不匹配");
        }

        long nonceExpiry = Instant.now().getEpochSecond() + properties.getNonceTtlSeconds();
        if (seenNonces.putIfAbsent(nonce, nonceExpiry) != null) {
            throw unauthorized("签名已被使用(重放)");
        }
    }

    /** 入站头中是否带有任意一个签名头(用于"双接受"迁移判定)。 */
    public boolean hasSignatureHeaders(Map<String, String> headers) {
        return !header(headers, HEADER_SIGNATURE).isBlank();
    }

    // ---- 纯密码学(static,便于单测) ----

    static String canonical(String keyId, String timestamp, String nonce, String method,
                            String path, String rawQuery, byte[] body) {
        return String.join("\n",
                keyId,
                timestamp,
                nonce,
                method == null ? "" : method.toUpperCase(),
                path == null ? "" : path,
                rawQuery == null ? "" : rawQuery,
                hexSha256(body));
    }

    static String hmacBase64(String secret, String canonical) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            byte[] raw = mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8));
            return java.util.Base64.getEncoder().encodeToString(raw);
        } catch (Exception ex) {
            throw new IllegalStateException("HMAC 计算失败", ex);
        }
    }

    static String hexSha256(byte[] body) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(body == null ? new byte[0] : body);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 计算失败", ex);
        }
    }

    static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }

    private void evictExpiredNonces() {
        long now = Instant.now().getEpochSecond();
        seenNonces.entrySet().removeIf(e -> e.getValue() < now);
    }

    private static String header(Map<String, String> headers, String name) {
        if (headers == null) {
            return "";
        }
        String value = headers.get(name);
        if (value == null) {
            value = headers.get(name.toLowerCase());
        }
        return value == null ? "" : value.trim();
    }

    private static BizException unauthorized(String message) {
        return BizException.of(com.si.backend.common.Constants.HTTP_UNAUTHORIZED, message);
    }
}
