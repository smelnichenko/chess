package io.schnappy.chess.config;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;
import java.util.UUID;

/**
 * Reads X-User-* headers set by the API gateway for WebSocket handshake authentication.
 */
@Slf4j
@Component
public class WebSocketAuthInterceptor implements HandshakeInterceptor {

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        if (request instanceof ServletServerHttpRequest servletRequest) {
            HttpServletRequest httpRequest = servletRequest.getServletRequest();
            String userIdHeader = httpRequest.getHeader("X-User-ID");
            String userUuidHeader = httpRequest.getHeader("X-User-UUID");

            if (userIdHeader != null && !userIdHeader.isBlank()) {
                try {
                    Long userId = Long.parseLong(userIdHeader);
                    attributes.put("userId", userId);
                    if (userUuidHeader != null && !userUuidHeader.isBlank()) {
                        attributes.put("userUuid", UUID.fromString(userUuidHeader));
                    }
                    return true;
                } catch (Exception e) {
                    log.debug("WebSocket auth failed: {}", e.getMessage());
                }
            }
        }
        return false;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // No post-handshake action needed — auth is validated in beforeHandshake
    }
}
