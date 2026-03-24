package io.schnappy.chess.config;

import io.schnappy.chess.ChessGame;
import io.schnappy.chess.ChessGameRepository;
import io.schnappy.chess.GameStatus;
import io.schnappy.chess.GameType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SubscriptionGuardTest {

    private static final UUID WHITE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID BLACK = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Mock
    private ChessGameRepository chessGameRepository;

    @InjectMocks
    private SubscriptionGuard guard;

    @Test
    void onSubscribe_playerInGame_allows() {
        UUID gameUuid = UUID.randomUUID();
        ChessGame game = createGame(WHITE, BLACK);
        when(chessGameRepository.findByUuid(gameUuid)).thenReturn(Optional.of(game));

        var event = createSubscribeEvent("/topic/chess." + gameUuid, WHITE);

        assertThatCode(() -> guard.onSubscribe(event)).doesNotThrowAnyException();
    }

    @Test
    void onSubscribe_nonPlayerInGame_rejects() {
        UUID gameUuid = UUID.randomUUID();
        ChessGame game = createGame(WHITE, BLACK);
        when(chessGameRepository.findByUuid(gameUuid)).thenReturn(Optional.of(game));

        UUID stranger = UUID.fromString("00000000-0000-0000-0000-000000000099");
        var event = createSubscribeEvent("/topic/chess." + gameUuid, stranger);

        assertThatThrownBy(() -> guard.onSubscribe(event))
                .isInstanceOf(MessageDeliveryException.class)
                .hasMessageContaining("Not a player in game");
    }

    @Test
    void onSubscribe_gameNotFound_rejects() {
        UUID gameUuid = UUID.randomUUID();
        when(chessGameRepository.findByUuid(gameUuid)).thenReturn(Optional.empty());

        var event = createSubscribeEvent("/topic/chess." + gameUuid, WHITE);

        assertThatThrownBy(() -> guard.onSubscribe(event))
                .isInstanceOf(MessageDeliveryException.class);
    }

    @Test
    void onSubscribe_nullUserUuid_rejects() {
        UUID gameUuid = UUID.randomUUID();
        ChessGame game = createGame(WHITE, BLACK);
        when(chessGameRepository.findByUuid(gameUuid)).thenReturn(Optional.of(game));

        var event = createSubscribeEvent("/topic/chess." + gameUuid, null);

        assertThatThrownBy(() -> guard.onSubscribe(event))
                .isInstanceOf(MessageDeliveryException.class);
    }

    @Test
    void onSubscribe_nonChessTopic_ignored() {
        var event = createSubscribeEvent("/topic/other.something", WHITE);

        assertThatCode(() -> guard.onSubscribe(event)).doesNotThrowAnyException();
        verify(chessGameRepository, never()).findByUuid(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void onSubscribe_nullDestination_ignored() {
        var event = createSubscribeEvent(null, WHITE);

        assertThatCode(() -> guard.onSubscribe(event)).doesNotThrowAnyException();
    }

    @Test
    void onSubscribe_nullSessionAttributes_rejectsChessTopic() {
        UUID gameUuid = UUID.randomUUID();
        ChessGame game = createGame(WHITE, BLACK);
        when(chessGameRepository.findByUuid(gameUuid)).thenReturn(Optional.of(game));

        // Create event without session attributes
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination("/topic/chess." + gameUuid);
        // Don't set session attributes at all
        var message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
        var event = new org.springframework.web.socket.messaging.SessionSubscribeEvent(this, message);

        assertThatThrownBy(() -> guard.onSubscribe(event))
                .isInstanceOf(MessageDeliveryException.class);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private ChessGame createGame(UUID whiteUuid, UUID blackUuid) {
        ChessGame game = new ChessGame();
        game.setWhitePlayerUuid(whiteUuid);
        game.setBlackPlayerUuid(blackUuid);
        game.setGameType(GameType.PVP);
        game.setStatus(GameStatus.IN_PROGRESS);
        return game;
    }

    private org.springframework.web.socket.messaging.SessionSubscribeEvent createSubscribeEvent(
            String destination, UUID userUuid) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination(destination);
        Map<String, Object> sessionAttrs = new HashMap<>();
        if (userUuid != null) {
            sessionAttrs.put("userUuid", userUuid);
        }
        accessor.setSessionAttributes(sessionAttrs);
        var message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
        return new org.springframework.web.socket.messaging.SessionSubscribeEvent(this, message);
    }
}
