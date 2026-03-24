package io.schnappy.chess.kafka;

import io.schnappy.chess.ChessUser;
import io.schnappy.chess.ChessUserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Consumes user.events from the admin service to maintain a local user table
 * in the chess service. Uses UUID as the primary identifier.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserEventConsumer {

    private final ChessUserRepository chessUserRepository;

    @KafkaListener(topics = "user.events", groupId = "chess-user",
            properties = "spring.json.trusted.packages=java.util,java.lang")
    public void handleUserEvent(Map<String, Object> event) {
        String type = (String) event.get("type");
        if (type == null) {
            log.warn("Received user event without type: {}", event);
            return;
        }

        switch (type) {
            case "USER_CREATED", "USER_REGISTERED", "REGISTRATION_APPROVED" -> upsertUser(event);
            case "USER_ENABLED" -> updateEnabled(event, true);
            case "USER_DISABLED" -> updateEnabled(event, false);
            default -> log.debug("Ignoring user event type: {}", type);
        }
    }

    private void upsertUser(Map<String, Object> event) {
        String email = (String) event.get("email");
        UUID uuid = toUuid(event.get("uuid"));
        if (uuid == null || email == null) return;

        var user = chessUserRepository.findByUuid(uuid).orElseGet(() -> {
            var u = new ChessUser();
            u.setUuid(uuid);
            return u;
        });
        user.setEmail(email);
        user.setEnabled(true);
        user.setUpdatedAt(Instant.now());
        chessUserRepository.save(user);
        log.info("Synced chess user: {} ({})", uuid, email);
    }

    private void updateEnabled(Map<String, Object> event, boolean enabled) {
        UUID uuid = toUuid(event.get("uuid"));
        if (uuid == null) return;

        ChessUser user = chessUserRepository.findByUuid(uuid).orElse(null);

        if (user != null) {
            user.setEnabled(enabled);
            user.setUpdatedAt(Instant.now());
            chessUserRepository.save(user);
            log.info("Chess user {} {}", uuid, enabled ? "enabled" : "disabled");
        }
    }

    private UUID toUuid(Object value) {
        if (value instanceof String s && !s.isBlank()) {
            try {
                return UUID.fromString(s);
            } catch (IllegalArgumentException _) {
                return null;
            }
        }
        return null;
    }
}
