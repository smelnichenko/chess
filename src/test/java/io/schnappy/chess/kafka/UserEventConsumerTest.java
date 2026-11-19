package io.schnappy.chess.kafka;

import io.schnappy.chess.ChessUser;
import io.schnappy.chess.ChessUserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The chess-user listener's own logic: admin's state stored per user, and an event naming no user refused with
 * InvalidUserEventException - the error handler keeps it on user.events.DLT, never retried (UserEventRetryKafkaTest).
 */
@ExtendWith(MockitoExtension.class)
class UserEventConsumerTest {

    private static final UUID UUID_1 = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Mock
    private ChessUserRepository repository;

    @InjectMocks
    private UserEventConsumer consumer;

    @Test
    void created_aNewUser_savedWithAdminsState() {
        when(repository.findByUuid(UUID_1)).thenReturn(Optional.empty());

        consumer.handleUserEvent(created(UUID_1.toString(), "new@example.com", true));

        ChessUser saved = saved();
        assertThat(saved.getUuid()).isEqualTo(UUID_1);
        assertThat(saved.getEmail()).isEqualTo("new@example.com");
        assertThat(saved.isEnabled()).isTrue();
    }

    @Test
    void created_aUserDisabledInAdmin_staysDisabled() {
        // admin's sync sends USER_CREATED for every user it knows, the disabled ones too
        when(repository.findByUuid(UUID_1)).thenReturn(Optional.of(user(true)));

        consumer.handleUserEvent(created(UUID_1.toString(), "off@example.com", false));

        assertThat(saved().isEnabled()).isFalse();
    }

    @Test
    void created_withoutEnabled_enabled() {
        when(repository.findByUuid(UUID_1)).thenReturn(Optional.empty());
        Map<String, Object> event = created(UUID_1.toString(), "plain@example.com", true);
        event.remove("enabled");

        consumer.handleUserEvent(event);

        assertThat(saved().isEnabled()).isTrue();
    }

    @Test
    void created_aKnownUser_theSameRowUpdated() {
        ChessUser known = user(true);
        known.setEmail("old@example.com");
        Instant before = known.getUpdatedAt().minusSeconds(60);
        known.setUpdatedAt(before);
        when(repository.findByUuid(UUID_1)).thenReturn(Optional.of(known));

        consumer.handleUserEvent(created(UUID_1.toString(), "renamed@example.com", true));

        ChessUser saved = saved();
        assertThat(saved).isSameAs(known);
        assertThat(saved.getEmail()).isEqualTo("renamed@example.com");
        assertThat(saved.getUpdatedAt()).isAfter(before);
    }

    @Test
    void disabled_aKnownUser_disabled() {
        when(repository.findByUuid(UUID_1)).thenReturn(Optional.of(user(true)));

        consumer.handleUserEvent(event("USER_DISABLED", UUID_1.toString()));

        assertThat(saved().isEnabled()).isFalse();
    }

    @Test
    void enabled_aKnownUser_enabled() {
        when(repository.findByUuid(UUID_1)).thenReturn(Optional.of(user(false)));

        consumer.handleUserEvent(event("USER_ENABLED", UUID_1.toString()));

        assertThat(saved().isEnabled()).isTrue();
    }

    @Test
    void disabled_anUnknownUser_nothingSaved() {
        when(repository.findByUuid(UUID_1)).thenReturn(Optional.empty());

        consumer.handleUserEvent(event("USER_DISABLED", UUID_1.toString()));

        verify(repository, never()).save(any());
    }

    @Test
    void registrationApproved_namesNoUser_ignored() {
        // admin's REGISTRATION_* carry an approval id and an email, no uuid: no user to store, and no dead letter
        Map<String, Object> event = new HashMap<>(Map.of("type", "REGISTRATION_APPROVED", "approvalId", 7,
                "email", "approved@example.com", "decidedBy", "admin"));

        consumer.handleUserEvent(event);

        verifyNoInteractions(repository);
    }

    @Test
    void anEventWithoutAType_refused() {
        Map<String, Object> event = new HashMap<>(Map.of("uuid", UUID_1.toString()));

        assertThatThrownBy(() -> consumer.handleUserEvent(event))
                .isInstanceOf(InvalidUserEventException.class).hasMessageContaining("without a type");
        verifyNoInteractions(repository);
    }

    @Test
    void created_withoutAUuid_refused() {
        Map<String, Object> event = created(UUID_1.toString(), "x@example.com", true);
        event.remove("uuid");

        assertThatThrownBy(() -> consumer.handleUserEvent(event))
                .isInstanceOf(InvalidUserEventException.class).hasMessageContaining("USER_CREATED without a uuid");
        verifyNoInteractions(repository);
    }

    @Test
    void created_withAUuidThatIsNotOne_refused() {
        Map<String, Object> event = created("not-a-uuid", "x@example.com", true);

        assertThatThrownBy(() -> consumer.handleUserEvent(event))
                .isInstanceOf(InvalidUserEventException.class).hasMessageContaining("not-a-uuid")
                .hasCauseInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void created_withoutAnEmail_refused() {
        // chess_users.email is NOT NULL: the save would fail on every retry
        Map<String, Object> event = created(UUID_1.toString(), " ", true);

        assertThatThrownBy(() -> consumer.handleUserEvent(event))
                .isInstanceOf(InvalidUserEventException.class).hasMessageContaining("without an email");
        verifyNoInteractions(repository);
    }

    @Test
    void disabled_withoutAUuid_refused() {
        Map<String, Object> event = new HashMap<>(Map.of("type", "USER_DISABLED"));

        assertThatThrownBy(() -> consumer.handleUserEvent(event))
                .isInstanceOf(InvalidUserEventException.class).hasMessageContaining("USER_DISABLED without a uuid");
        verifyNoInteractions(repository);
    }

    private ChessUser saved() {
        ArgumentCaptor<ChessUser> captor = ArgumentCaptor.forClass(ChessUser.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }

    private static ChessUser user(boolean enabled) {
        var u = new ChessUser();
        u.setUuid(UUID_1);
        u.setEmail("known@example.com");
        u.setEnabled(enabled);
        return u;
    }

    private static Map<String, Object> created(String uuid, String email, boolean enabled) {
        return new HashMap<>(Map.of("type", "USER_CREATED", "uuid", uuid, "email", email, "enabled", enabled));
    }

    private static Map<String, Object> event(String type, String uuid) {
        return new HashMap<>(Map.of("type", type, "uuid", uuid));
    }
}
