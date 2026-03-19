package io.schnappy.chess.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GatewayAuthFilterTest {

    private GatewayAuthFilter filter;

    @Mock
    private FilterChain filterChain;

    @BeforeEach
    void setUp() {
        filter = new GatewayAuthFilter();
        SecurityContextHolder.clearContext();
    }

    @Test
    void withValidHeaders_populatesSecurityContextAndRequestAttribute() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-User-ID", "42");
        request.addHeader("X-User-UUID", "some-uuid");
        request.addHeader("X-User-Email", "user@example.com");
        request.addHeader("X-User-Permissions", "PLAY,CHAT");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.isAuthenticated()).isTrue();
        assertThat(auth.getAuthorities()).extracting("authority")
                .containsExactlyInAnyOrder("PLAY", "CHAT");

        GatewayUser user = (GatewayUser) request.getAttribute(GatewayUser.REQUEST_ATTRIBUTE);
        assertThat(user).isNotNull();
        assertThat(user.userId()).isEqualTo(42L);
        assertThat(user.uuid()).isEqualTo("some-uuid");
        assertThat(user.email()).isEqualTo("user@example.com");
        assertThat(user.permissions()).containsExactlyInAnyOrder("PLAY", "CHAT");

        verify(filterChain).doFilter(request, response);
    }

    @Test
    void withNoHeaders_skipsAuthentication() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(request.getAttribute(GatewayUser.REQUEST_ATTRIBUTE)).isNull();
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void withBlankUserId_treatsAsUnauthenticated() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-User-ID", "   ");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void withMalformedUserId_treatsAsUnauthenticated() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-User-ID", "not-a-number");
        request.addHeader("X-User-Email", "user@example.com");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(request.getAttribute(GatewayUser.REQUEST_ATTRIBUTE)).isNull();
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void withEmptyPermissions_createsUserWithNoPermissions() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-User-ID", "5");
        // No X-User-Permissions header
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        GatewayUser user = (GatewayUser) request.getAttribute(GatewayUser.REQUEST_ATTRIBUTE);
        assertThat(user).isNotNull();
        assertThat(user.permissions()).isEmpty();
        assertThat(user.hasPermission("PLAY")).isFalse();
    }

    @Test
    void withSinglePermission_parsedCorrectly() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-User-ID", "7");
        request.addHeader("X-User-Permissions", "METRICS");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        GatewayUser user = (GatewayUser) request.getAttribute(GatewayUser.REQUEST_ATTRIBUTE);
        assertThat(user.hasPermission("METRICS")).isTrue();
        assertThat(user.hasPermission("PLAY")).isFalse();
    }

    @Test
    void filterChainAlwaysContinues_evenOnMalformedHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-User-ID", "bad-value");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        // Filter chain must still be called — no swallowed exceptions
        verify(filterChain).doFilter(request, response);
    }
}
