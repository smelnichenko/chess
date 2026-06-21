package io.schnappy.chess.security;

import java.util.List;
import java.util.UUID;

/**
 * User identity derived from the validated Keycloak access token.
 * Primary identifier is uuid (from the JWT subject claim).
 */
public record GatewayUser(
        UUID uuid,
        String email,
        List<String> permissions
) {
    public static final String REQUEST_ATTRIBUTE = "gatewayUser";

    public boolean hasPermission(String permission) {
        return permissions.contains(permission);
    }
}
