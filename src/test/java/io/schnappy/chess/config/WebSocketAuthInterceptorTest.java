package io.schnappy.chess.config;

import org.junit.jupiter.api.Test;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.socket.WebSocketHandler;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;

class WebSocketAuthInterceptorTest {

    private final WebSocketAuthInterceptor interceptor = new WebSocketAuthInterceptor();
    private final WebSocketHandler wsHandler = mock(WebSocketHandler.class);
    private final ServerHttpResponse response = mock(ServerHttpResponse.class);

    @Test
    void beforeHandshake_validHeaders_populatesAttributes() {
        MockHttpServletRequest httpRequest = new MockHttpServletRequest();
        httpRequest.addHeader("X-User-ID", "42");
        httpRequest.addHeader("X-User-UUID", "550e8400-e29b-41d4-a716-446655440000");
        ServletServerHttpRequest request = new ServletServerHttpRequest(httpRequest);
        Map<String, Object> attributes = new HashMap<>();

        boolean result = interceptor.beforeHandshake(request, response, wsHandler, attributes);

        assertThat(result).isTrue();
        assertThat(attributes)
                .containsEntry("userId", 42L)
                .containsEntry("userUuid", UUID.fromString("550e8400-e29b-41d4-a716-446655440000"));
    }

    @Test
    void beforeHandshake_validUserIdOnly_setsUserIdWithoutUuid() {
        MockHttpServletRequest httpRequest = new MockHttpServletRequest();
        httpRequest.addHeader("X-User-ID", "7");
        ServletServerHttpRequest request = new ServletServerHttpRequest(httpRequest);
        Map<String, Object> attributes = new HashMap<>();

        boolean result = interceptor.beforeHandshake(request, response, wsHandler, attributes);

        assertThat(result).isTrue();
        assertThat(attributes)
                .containsEntry("userId", 7L)
                .doesNotContainKey("userUuid");
    }

    @Test
    void beforeHandshake_blankUserId_rejectsFalse() {
        MockHttpServletRequest httpRequest = new MockHttpServletRequest();
        httpRequest.addHeader("X-User-ID", "   ");
        ServletServerHttpRequest request = new ServletServerHttpRequest(httpRequest);
        Map<String, Object> attributes = new HashMap<>();

        boolean result = interceptor.beforeHandshake(request, response, wsHandler, attributes);

        assertThat(result).isFalse();
    }

    @Test
    void beforeHandshake_noHeaders_rejectsFalse() {
        MockHttpServletRequest httpRequest = new MockHttpServletRequest();
        ServletServerHttpRequest request = new ServletServerHttpRequest(httpRequest);
        Map<String, Object> attributes = new HashMap<>();

        boolean result = interceptor.beforeHandshake(request, response, wsHandler, attributes);

        assertThat(result).isFalse();
    }

    @Test
    void beforeHandshake_malformedUserId_rejectsFalse() {
        MockHttpServletRequest httpRequest = new MockHttpServletRequest();
        httpRequest.addHeader("X-User-ID", "not-a-number");
        ServletServerHttpRequest request = new ServletServerHttpRequest(httpRequest);
        Map<String, Object> attributes = new HashMap<>();

        boolean result = interceptor.beforeHandshake(request, response, wsHandler, attributes);

        assertThat(result).isFalse();
    }

    @Test
    void beforeHandshake_invalidUuid_rejectsFalse() {
        MockHttpServletRequest httpRequest = new MockHttpServletRequest();
        httpRequest.addHeader("X-User-ID", "5");
        httpRequest.addHeader("X-User-UUID", "not-a-uuid");
        ServletServerHttpRequest request = new ServletServerHttpRequest(httpRequest);
        Map<String, Object> attributes = new HashMap<>();

        boolean result = interceptor.beforeHandshake(request, response, wsHandler, attributes);

        assertThat(result).isFalse();
    }

    @Test
    void beforeHandshake_blankUuid_setsUserIdOnly() {
        MockHttpServletRequest httpRequest = new MockHttpServletRequest();
        httpRequest.addHeader("X-User-ID", "3");
        httpRequest.addHeader("X-User-UUID", "   ");
        ServletServerHttpRequest request = new ServletServerHttpRequest(httpRequest);
        Map<String, Object> attributes = new HashMap<>();

        boolean result = interceptor.beforeHandshake(request, response, wsHandler, attributes);

        assertThat(result).isTrue();
        assertThat(attributes)
                .containsEntry("userId", 3L)
                .doesNotContainKey("userUuid");
    }

    @Test
    void beforeHandshake_nonServletRequest_rejectsFalse() {
        org.springframework.http.server.ServerHttpRequest nonServletRequest = mock(org.springframework.http.server.ServerHttpRequest.class);
        Map<String, Object> attributes = new HashMap<>();

        boolean result = interceptor.beforeHandshake(nonServletRequest, response, wsHandler, attributes);

        assertThat(result).isFalse();
    }

    @Test
    void afterHandshake_doesNothing() {
        assertThatCode(() -> interceptor.afterHandshake(
                mock(org.springframework.http.server.ServerHttpRequest.class),
                response, wsHandler, null))
                .doesNotThrowAnyException();
    }
}
