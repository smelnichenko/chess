package io.schnappy.chess.service;

import io.schnappy.chess.ChessUserRepository;
import io.schnappy.chess.security.UserProvisioner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.UUID;

/**
 * Checks if a user exists locally; if not, calls the admin service's
 * ensure-user endpoint to trigger provisioning via Kafka events.
 */
@Component
public class UserProvisionerAdapter implements UserProvisioner {

    private static final Logger log = LoggerFactory.getLogger(UserProvisionerAdapter.class);

    private final ChessUserRepository chessUserRepository;
    private final RestClient restClient;

    public UserProvisionerAdapter(ChessUserRepository chessUserRepository,
                                  @Value("${ADMIN_SERVICE_URL:http://schnappy-admin:8080}") String adminServiceUrl) {
        this.chessUserRepository = chessUserRepository;
        this.restClient = RestClient.builder()
                .baseUrl(adminServiceUrl)
                .build();
    }

    @Override
    public void provisionUser(String uuid, String email, List<String> roles) {
        try {
            if (chessUserRepository.findByUuid(UUID.fromString(uuid)).isPresent()) {
                return;
            }

            log.info("User {} not found locally, calling admin ensure-user", uuid);

            restClient.post()
                    .uri("/api/auth/ensure-user")
                    .header("X-User-UUID", uuid)
                    .header("X-User-Email", email)
                    .header("X-User-Permissions", String.join(",", roles))
                    .retrieve()
                    .toBodilessEntity();

            log.info("Successfully triggered provisioning for user {}", uuid);
        } catch (Exception e) {
            log.warn("Failed to provision user {}: {}", uuid, e.getMessage());
        }
    }
}
