package io.schnappy.chess.config;

import com.nimbusds.jose.KeySourceException;
import com.nimbusds.jose.jwk.JWKMatcher;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import io.schnappy.chess.ChessService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Does before readiness what the first requests did on the way: Boot runs every {@link ApplicationRunner} before it
 * reports the app ready, so Kubernetes routes no traffic here until this returns.
 *
 * <p>Measured on the Vagrant copy after a restart: the first {@code GET /api/chess/games} took 0.4-0.55 s (the
 * Keycloak key fetch over TLS, the first queries) and the next ones under 30 ms. Both steps are best effort: a Keycloak
 * or database that does not answer here is logged and the first request does the work, as before - Keycloak is no
 * startup dependency.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StartupWarmUp implements ApplicationRunner {

    /** No one: a player without games, so the game queries read nothing of anyone's and write nothing. */
    static final UUID NO_PLAYER = new UUID(0, 0);

    /** None where no JWK set URI is configured (Boot builds no decoder from one there either). */
    private final ObjectProvider<JWKSource<SecurityContext>> keycloakJwkSource;
    private final ChessService chessService;

    @Override
    public void run(ApplicationArguments args) {
        warmKeys();
        warmQueries();
    }

    private void warmKeys() {
        JWKSource<SecurityContext> source = keycloakJwkSource.getIfAvailable();
        if (source == null) {
            return;
        }
        try {
            int keys = source.get(new JWKSelector(new JWKMatcher.Builder().build()), null).size();
            log.info("Keycloak signing keys fetched before readiness: {}", keys);
        } catch (KeySourceException e) {
            log.warn("Keycloak signing keys not fetched at startup ({}) - the first authenticated request fetches them",
                    e.getMessage());
        }
    }

    private void warmQueries() {
        try {
            chessService.getActiveGames(NO_PLAYER);
            chessService.getOpenGames();
            chessService.getHistory(NO_PLAYER, PageRequest.of(0, 1));
        } catch (DataAccessException e) {
            log.warn("Game queries not warmed at startup ({}) - the first requests run them cold", e.getMessage());
        }
    }
}
