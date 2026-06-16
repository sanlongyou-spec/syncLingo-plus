package com.si.backend.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * P4 服务间 HMAC 签名配置。方向独立密钥,不复用:
 * <ul>
 *   <li>downstream-key:浏览器→Java→C# Bot(Java 出站签名,C# 验签)。</li>
 *   <li>upstream-key:C# Bot→Java /api/teams-bot/**(C# 出站签名,Java 验签)。</li>
 * </ul>
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.service-signature")
public class ServiceSignatureProperties {

    /** Java→C# 下行密钥(对应 C# 的同名密钥)。 */
    private String downstreamKey = "";
    /** C#→Java 上行密钥(对应 C# 的同名密钥)。 */
    private String upstreamKey = "";
    /** 配置 upstream-key 后是否强制要求 C#→Java 请求携带签名。 */
    private boolean upstreamRequired = true;
    /** 允许的时钟偏移秒数。 */
    private long maxSkewSeconds = 300;
    /** nonce 去重缓存保留秒数(应 ≥ maxSkewSeconds)。 */
    private long nonceTtlSeconds = 600;
}
