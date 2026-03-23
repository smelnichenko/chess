package io.schnappy.chess.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class GatewayAuthFilterTest {

    private static final String TEST_UUID = "550e8400-e29b-41d4-a716-446655440000";

    @Mock
    private FilterChain filterChain;

    private GatewayAuthFilter filter;

    @BeforeEach
    void setUp() {
        UserIdResolver resolver = uuid -> TEST_UUID.equals(uuid) ? 42L : null;
        filter = new GatewayAuthFilter(resolver);
        SecurityContextHolder.clearContext();
    }

    @Test
    void withValidHeaders_populatesSecurityContextAndRequestAttribute() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-User-UUID", TEST_UUID);
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
        assertThat(user.uuid()).isEqualTo(TEST_UUID);
        assertThat(user.userId()).isEqualTo(42L);
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
    void withBlankUuid_treatsAsUnauthenticated() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-User-UUID", "   ");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void withUserNotInTable_setsNullUserId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-User-UUID", "00000000-0000-0000-0000-000000000099");
        request.addHeader("X-User-Email", "user@example.com");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        GatewayUser user = (GatewayUser) request.getAttribute(GatewayUser.REQUEST_ATTRIBUTE);
        assertThat(user).isNotNull();
        assertThat(user.uuid()).isEqualTo("00000000-0000-0000-0000-000000000099");
        assertThat(user.userId()).isNull();
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void withEmptyPermissions_createsUserWithNoPermissions() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-User-UUID", TEST_UUID);
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
        request.addHeader("X-User-UUID", TEST_UUID);
        request.addHeader("X-User-Permissions", "METRICS");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        GatewayUser user = (GatewayUser) request.getAttribute(GatewayUser.REQUEST_ATTRIBUTE);
        assertThat(user.hasPermission("METRICS")).isTrue();
        assertThat(user.hasPermission("PLAY")).isFalse();
    }

    @Test
    void filterChainAlwaysContinues() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
    }
}
