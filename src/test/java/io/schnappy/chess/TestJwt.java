package io.schnappy.chess;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Test-only JWT support. Builds unsigned, Keycloak-shaped access tokens and a
 * matching {@link JwtDecoder} that trusts them, so tests can drive the real
 * {@code GatewayAuthFilter} validation path without a live Keycloak or JWKS
 * endpoint. Production verifies signatures against the Keycloak JWKS; this
 * shortcut is test scope only.
 */
public final class TestJwt {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TestJwt() {}

    /** Builds an unsigned {@code header.payload.sig} token with Keycloak claims. */
    public static String token(UUID sub, String email, List<String> roles) {
        String header = b64("{\"alg\":\"none\",\"typ\":\"JWT\"}");
        String rolesJson = roles.stream()
                .map(r -> "\"" + r + "\"")
                .collect(Collectors.joining(","));
        String payload = b64("{\"sub\":\"" + sub + "\",\"email\":\"" + email
                + "\",\"realm_access\":{\"roles\":[" + rolesJson + "]}}");
        return header + "." + payload + ".sig";
    }

    /** A decoder that parses {@link #token} output; rejects anything malformed. */
    public static JwtDecoder decoder() {
        return token -> {
            String[] parts = token.split("\\.");
            if (parts.length < 2) {
                throw new BadJwtException("malformed test token");
            }
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> claims = MAPPER.readValue(
                        Base64.getUrlDecoder().decode(parts[1]), Map.class);
                Instant now = Instant.now();
                return Jwt.withTokenValue(token)
                        .header("alg", "none")
                        .issuedAt(now)
                        .expiresAt(now.plusSeconds(300))
                        .claims(c -> c.putAll(claims))
                        .build();
            } catch (Exception e) {
                throw new BadJwtException("invalid test token", e);
            }
        };
    }

    private static String b64(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes());
    }
}
