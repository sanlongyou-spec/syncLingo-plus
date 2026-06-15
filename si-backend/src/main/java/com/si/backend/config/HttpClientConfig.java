package com.si.backend.config;

import lombok.extern.slf4j.Slf4j;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Response;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * OkHttp 共享客户端配置。
 *
 * <p>为整个项目提供一个共享的 OkHttpClient 实例，所有 HTTP 调用均使用此 Bean。
 * OkHttp 使用连接池复用 TCP 连接，可显著提升性能。
 * 内置 5xx 自动重试拦截器，最多重试 3 次，指数退避（200ms → 400ms → 800ms）。
 */
@Slf4j
@Configuration
public class HttpClientConfig {

    private static final int HTTP_RETRY_MAX = 3;
    private static final long HTTP_RETRY_BASE_DELAY_MS = 200L;

    @Bean
    public OkHttpClient okHttpClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .addInterceptor(retryInterceptor())
                .build();
    }

    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }

    private static Interceptor retryInterceptor() {
        return chain -> {
            okhttp3.Request request = chain.request();
            Response response = null;
            int attempt = 0;
            while (true) {
                if (response != null) response.close();
                try {
                    response = chain.proceed(request);
                } catch (IOException e) {
                    if (attempt >= HTTP_RETRY_MAX) {
                        if (response != null) response.close();
                        throw e;
                    }
                    log.warn("[HttpClientConfig] 网络异常，第 {} 次重试，url={}, error={}", attempt + 1, request.url(), e.getMessage());
                    sleepRetryDelay(attempt + 1);
                    attempt++;
                    continue;
                }
                if (response.isSuccessful() || response.code() < 500 || attempt >= HTTP_RETRY_MAX) {
                    return response;
                }
                // 非幂等方法（如 POST）不重试，防止重复提交
                if (!isIdempotentMethod(request.method())) {
                    return response;
                }
                log.warn("[HttpClientConfig] HTTP {}，第 {} 次重试，url={}", response.code(), attempt + 1, request.url());
                sleepRetryDelay(attempt + 1);
                attempt++;
            }
        };
    }

    private static boolean isIdempotentMethod(String method) {
        return "GET".equalsIgnoreCase(method)
                || "HEAD".equalsIgnoreCase(method)
                || "PUT".equalsIgnoreCase(method)
                || "DELETE".equalsIgnoreCase(method)
                || "OPTIONS".equalsIgnoreCase(method);
    }

    private static void sleepRetryDelay(int attempt) throws IOException {
        long delay = HTTP_RETRY_BASE_DELAY_MS * (1L << (attempt - 1));
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("HTTP 重试被中断", e);
        }
    }
}
