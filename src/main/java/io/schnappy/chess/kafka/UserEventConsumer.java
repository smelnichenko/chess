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
 * Consumes user.events from the admin service to maintain a local user table in the chess service. Uses UUID as the
 * primary identifier. An event that names no user throws {@link InvalidUserEventException}: KafkaErrorHandlerConfig
 * keeps it on user.events.DLT; a store failing is retried there.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserEventConsumer {

    private static final String USER_CREATED = "USER_CREATED";
    private static final String USER_ENABLED = "USER_ENABLED";
    private static final String USER_DISABLED = "USER_DISABLED";

    private final ChessUserRepository chessUserRepository;

    @KafkaListener(topics = "user.events", groupId = "chess-user",
            properties = "spring.json.trusted.packages=java.util,java.lang")
    public void handleUserEvent(Map<String, Object> event) {
        if (!(event.get("type") instanceof String type)) {
            throw new InvalidUserEventException("a user event without a type");
        }

        // admin's REGISTRATION_* events name no user (no uuid: an approved registration's user comes as its
        // USER_CREATED), and admin sends no USER_REGISTERED - ignored with every other type
        switch (type) {
            case USER_CREATED -> upsertUser(event);
            case USER_ENABLED -> updateEnabled(event, true);
            case USER_DISABLED -> updateEnabled(event, false);
            default -> log.debug("Ignoring user event type: {}", type);
        }
    }

    private void upsertUser(Map<String, Object> event) {
        UUID uuid = uuid(event, USER_CREATED);
        if (!(event.get("email") instanceof String email) || email.isBlank()) {
            // chess_users.email is NOT NULL: no retry would store it
            throw new InvalidUserEventException(USER_CREATED + " without an email [uuid=" + uuid + "]");
        }

        var user = chessUserRepository.findByUuid(uuid).orElseGet(() -> {
            var u = new ChessUser();
            u.setUuid(uuid);
            return u;
        });
        user.setEmail(email);
        // admin's state: a disabled user synced again stays disabled (enabled unless the event says otherwise)
        user.setEnabled(!Boolean.FALSE.equals(event.get("enabled")));
        user.setUpdatedAt(Instant.now());
        chessUserRepository.save(user);
        log.info("Synced chess user: {} ({})", uuid, email);
    }

    private void updateEnabled(Map<String, Object> event, boolean enabled) {
        String type = enabled ? USER_ENABLED : USER_DISABLED;
        UUID uuid = uuid(event, type);

        ChessUser user = chessUserRepository.findByUuid(uuid).orElse(null);
        if (user == null) {
            log.warn("Chess user not found for {} [uuid={}]", type, uuid);
            return;
        }
        user.setEnabled(enabled);
        user.setUpdatedAt(Instant.now());
        chessUserRepository.save(user);
        log.info("Chess user {} {}", uuid, enabled ? "enabled" : "disabled");
    }

    /** The user the event names - an event naming none (no uuid, or one that is not a uuid) no retry can store. */
    private static UUID uuid(Map<String, Object> event, String type) {
        if (!(event.get("uuid") instanceof String text)) {
            throw new InvalidUserEventException(type + " without a uuid");
        }
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException e) {
            throw new InvalidUserEventException(type + " with a uuid that is not one: " + text, e);
        }
    }
}
