package io.schnappy.chess.security;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PermissionInterceptorTest {

    @InjectMocks
    private PermissionInterceptor interceptor;

    @Mock
    private ProceedingJoinPoint joinPoint;

    @Mock
    private MethodSignature methodSignature;

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    // -----------------------------------------------------------------------
    // checkClassPermission — class-level @RequirePermission
    // -----------------------------------------------------------------------

    @Test
    void checkClassPermission_userHasPermission_proceeds() throws Throwable {
        setUpRequestWithUser(new GatewayUser("uuid", "user@test.com", List.of("PLAY"), 1L));
        when(joinPoint.getSignature()).thenReturn(methodSignature);
        Method method = UnAnnotatedMethod.class.getMethod("doSomething");
        when(methodSignature.getMethod()).thenReturn(method);
        when(joinPoint.proceed()).thenReturn("result");

        RequirePermission classAnnotation = createRequirePermission(Permission.PLAY);

        Object result = interceptor.checkClassPermission(joinPoint, classAnnotation);

        assertThat(result).isEqualTo("result");
        verify(joinPoint).proceed();
    }

    @Test
    void checkClassPermission_userLacksPermission_throwsForbidden() throws Throwable {
        setUpRequestWithUser(new GatewayUser("uuid", "user@test.com", List.of("CHAT"), 1L));
        when(joinPoint.getSignature()).thenReturn(methodSignature);
        Method method = UnAnnotatedMethod.class.getMethod("doSomething");
        when(methodSignature.getMethod()).thenReturn(method);

        RequirePermission classAnnotation = createRequirePermission(Permission.PLAY);

        assertThatThrownBy(() -> interceptor.checkClassPermission(joinPoint, classAnnotation))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value()).isEqualTo(403));
    }

    @Test
    void checkClassPermission_methodAnnotationOverridesClass() throws Throwable {
        // User has METRICS but not PLAY — method-level annotation requires METRICS
        setUpRequestWithUser(new GatewayUser("uuid", "user@test.com", List.of("METRICS"), 1L));
        when(joinPoint.getSignature()).thenReturn(methodSignature);
        Method method = MethodAnnotatedController.class.getMethod("metricsEndpoint");
        when(methodSignature.getMethod()).thenReturn(method);
        when(joinPoint.proceed()).thenReturn("ok");

        // Class-level says PLAY, but method has @RequirePermission(METRICS)
        RequirePermission classAnnotation = createRequirePermission(Permission.PLAY);

        Object result = interceptor.checkClassPermission(joinPoint, classAnnotation);

        assertThat(result).isEqualTo("ok");
    }

    @Test
    void checkClassPermission_noRequestAttributes_throwsUnauthorized() throws Throwable {
        // No RequestContextHolder attributes set
        RequestContextHolder.resetRequestAttributes();
        when(joinPoint.getSignature()).thenReturn(methodSignature);
        Method method = UnAnnotatedMethod.class.getMethod("doSomething");
        when(methodSignature.getMethod()).thenReturn(method);

        RequirePermission classAnnotation = createRequirePermission(Permission.PLAY);

        assertThatThrownBy(() -> interceptor.checkClassPermission(joinPoint, classAnnotation))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value()).isEqualTo(401));
    }

    @Test
    void checkClassPermission_noGatewayUser_throwsUnauthorized() throws Throwable {
        // Request exists but no GatewayUser attribute
        MockHttpServletRequest request = new MockHttpServletRequest();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        when(joinPoint.getSignature()).thenReturn(methodSignature);
        Method method = UnAnnotatedMethod.class.getMethod("doSomething");
        when(methodSignature.getMethod()).thenReturn(method);

        RequirePermission classAnnotation = createRequirePermission(Permission.PLAY);

        assertThatThrownBy(() -> interceptor.checkClassPermission(joinPoint, classAnnotation))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value()).isEqualTo(401));
    }

    // -----------------------------------------------------------------------
    // checkMethodPermission — method-level @RequirePermission
    // -----------------------------------------------------------------------

    @Test
    void checkMethodPermission_userHasPermission_proceeds() throws Throwable {
        setUpRequestWithUser(new GatewayUser("uuid", "user@test.com", List.of("MANAGE_USERS"), 1L));
        when(joinPoint.proceed()).thenReturn("admin-result");

        RequirePermission methodAnnotation = createRequirePermission(Permission.MANAGE_USERS);

        Object result = interceptor.checkMethodPermission(joinPoint, methodAnnotation);

        assertThat(result).isEqualTo("admin-result");
    }

    @Test
    void checkMethodPermission_userLacksPermission_throwsForbidden() {
        setUpRequestWithUser(new GatewayUser("uuid", "user@test.com", List.of("PLAY"), 1L));

        RequirePermission methodAnnotation = createRequirePermission(Permission.MANAGE_USERS);

        assertThatThrownBy(() -> interceptor.checkMethodPermission(joinPoint, methodAnnotation))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value()).isEqualTo(403));
    }

    @Test
    void checkMethodPermission_emptyPermissions_throwsForbidden() {
        setUpRequestWithUser(new GatewayUser("uuid", "user@test.com", List.of(), 1L));

        RequirePermission methodAnnotation = createRequirePermission(Permission.PLAY);

        assertThatThrownBy(() -> interceptor.checkMethodPermission(joinPoint, methodAnnotation))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode().value()).isEqualTo(403));
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private void setUpRequestWithUser(GatewayUser user) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(GatewayUser.REQUEST_ATTRIBUTE, user);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private RequirePermission createRequirePermission(Permission permission) {
        return new RequirePermission() {
            @Override
            public Permission value() {
                return permission;
            }

            @Override
            public Class<? extends java.lang.annotation.Annotation> annotationType() {
                return RequirePermission.class;
            }
        };
    }

    // Stub classes for method reflection
    static class UnAnnotatedMethod {
        public void doSomething() {
            // Stub for reflection — no logic needed
        }
    }

    static class MethodAnnotatedController {
        @RequirePermission(Permission.METRICS)
        public void metricsEndpoint() {
            // Stub for reflection — no logic needed
        }
    }
}
