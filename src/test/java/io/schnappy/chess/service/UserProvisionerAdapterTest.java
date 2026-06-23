package io.schnappy.chess.service;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.schnappy.chess.ChessUser;
import io.schnappy.chess.ChessUserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserProvisionerAdapterTest {

    private static final UUID USER_UUID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
    private static final String UUID_STR = USER_UUID.toString();
    private static final String BEARER = "Bearer header.payload.sig";
    private static final String UNROUTABLE = "http://127.0.0.1:1";

    @Mock
    private ChessUserRepository chessUserRepository;

    private HttpServer server;

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void userAlreadyExists_skipsAdminCall() {
        when(chessUserRepository.findByUuid(USER_UUID)).thenReturn(Optional.of(new ChessUser()));
        setBearerOnRequest(BEARER);

        // Point at an unroutable URL: existing-user path must never reach it.
        var adapter = new UserProvisionerAdapter(chessUserRepository, UNROUTABLE);

        adapter.provisionUser(UUID_STR, "u@example.com", List.of("PLAY"));

        verify(chessUserRepository).findByUuid(USER_UUID);
    }

    @Test
    void noBearerToken_skipsAdminCall() {
        when(chessUserRepository.findByUuid(USER_UUID)).thenReturn(Optional.empty());
        // No request bound → currentBearerToken() returns null, admin is not called.
        var adapter = new UserProvisionerAdapter(chessUserRepository, UNROUTABLE);

        adapter.provisionUser(UUID_STR, "u@example.com", List.of("PLAY"));

        verify(chessUserRepository).findByUuid(USER_UUID);
    }

    @Test
    void missingUser_withBearer_relaysTokenToAdmin() throws IOException {
        when(chessUserRepository.findByUuid(USER_UUID)).thenReturn(Optional.empty());
        setBearerOnRequest(BEARER);

        var capturedAuth = new AtomicReference<String>();
        var capturedPath = new AtomicReference<String>();
        var hits = new AtomicInteger();
        String baseUrl = startStub(exchange -> {
            hits.incrementAndGet();
            capturedAuth.set(exchange.getRequestHeaders().getFirst(HttpHeaders.AUTHORIZATION));
            capturedPath.set(exchange.getRequestURI().getPath());
        });
        var adapter = new UserProvisionerAdapter(chessUserRepository, baseUrl);

        adapter.provisionUser(UUID_STR, "u@example.com", List.of("PLAY"));

        assertThat(hits.get()).isEqualTo(1);
        assertThat(capturedPath.get()).isEqualTo("/api/auth/ensure-user");
        // The validated Bearer token is relayed verbatim; no X-User-* headers.
        assertThat(capturedAuth.get()).isEqualTo(BEARER);
    }

    @Test
    void adminReturnsError_isSwallowed() throws IOException {
        when(chessUserRepository.findByUuid(USER_UUID)).thenReturn(Optional.empty());
        setBearerOnRequest(BEARER);

        // Admin replies 500 → RestClient throws, the catch logs and returns.
        String baseUrl = startStub(exchange -> respond(exchange, 500));
        var adapter = new UserProvisionerAdapter(chessUserRepository, baseUrl);

        // The admin failure must be swallowed, never propagated to the caller.
        assertThatCode(() -> adapter.provisionUser(UUID_STR, "u@example.com", List.of("PLAY")))
                .doesNotThrowAnyException();
    }

    @Test
    void invalidUuid_isSwallowed() {
        // UUID.fromString throws before the repository is touched; the catch
        // swallows it so a malformed subject never breaks the request.
        var adapter = new UserProvisionerAdapter(chessUserRepository, UNROUTABLE);

        adapter.provisionUser("not-a-uuid", "u@example.com", List.of("PLAY"));

        verify(chessUserRepository, never()).findByUuid(any());
    }

    private interface StubHandler {
        void handle(HttpExchange exchange) throws IOException;
    }

    private String startStub(StubHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            handler.handle(exchange);
            if (exchange.getResponseCode() == -1) {
                respond(exchange, 200);
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static void respond(HttpExchange exchange, int status) {
        try {
            exchange.sendResponseHeaders(status, 0);
            exchange.close();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void setBearerOnRequest(String authorization) {
        var request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.AUTHORIZATION, authorization);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }
}
