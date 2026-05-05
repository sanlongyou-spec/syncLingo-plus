package com.si.backend.config;

import okhttp3.OkHttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * OkHttp 共享客户端配置。
 *
 * <p>为整个项目提供一个共享的 OkHttpClient 实例，所有 HTTP 调用均使用此 Bean。
 * OkHttp 使用连接池复用 TCP 连接，可显著提升性能。
 *
 * <p>各集成类如需自定义超时，可在构造时传入并装饰此实例。
 */
@Configuration
public class HttpClientConfig {

    @Bean
    public OkHttpClient okHttpClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build();
    }
}
