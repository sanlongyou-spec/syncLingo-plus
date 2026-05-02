package com.si.backend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;
import com.si.backend.ws.AsrWebSocketHandler;
import com.si.backend.ws.JwtHandshakeInterceptor;
import lombok.RequiredArgsConstructor;

@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

    private final AsrWebSocketHandler asrWebSocketHandler;
    private final JwtHandshakeInterceptor jwtHandshakeInterceptor;

    @Value("${app.websocket.max-text-size:10485760}")
    private long maxTextSize;

    @Value("${app.websocket.max-binary-size:10485760}")
    private long maxBinarySize;

    @Bean
    public ServletServerContainerFactoryBean createWebSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize((int) maxTextSize);
        container.setMaxBinaryMessageBufferSize((int) maxBinarySize);
        return container;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(asrWebSocketHandler, "/ws/asr")
                .addInterceptors(jwtHandshakeInterceptor)
                .setAllowedOrigins("*");
    }
}
