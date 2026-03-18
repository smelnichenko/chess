package io.schnappy.chess;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "chess_games")
@Getter
@Setter
@NoArgsConstructor
public class ChessGame {

    private static final Map<GameStatus, Map<GameEvent, GameStatus>> TRANSITIONS;

    static {
        TRANSITIONS = new EnumMap<>(GameStatus.class);

        var waiting = new EnumMap<GameEvent, GameStatus>(GameEvent.class);
        waiting.put(GameEvent.OPPONENT_JOINED, GameStatus.IN_PROGRESS);
        waiting.put(GameEvent.ABANDON, GameStatus.ABANDONED);
        TRANSITIONS.put(GameStatus.WAITING_FOR_OPPONENT, waiting);

        var inProgress = new EnumMap<GameEvent, GameStatus>(GameEvent.class);
        inProgress.put(GameEvent.MOVE_MADE, GameStatus.IN_PROGRESS);
        inProgress.put(GameEvent.CHECKMATE, GameStatus.FINISHED);
        inProgress.put(GameEvent.STALEMATE, GameStatus.FINISHED);
        inProgress.put(GameEvent.RESIGN, GameStatus.FINISHED);
        inProgress.put(GameEvent.DRAW_AGREED, GameStatus.FINISHED);
        TRANSITIONS.put(GameStatus.IN_PROGRESS, inProgress);
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    private Long version;

    @Column(nullable = false, unique = true, updatable = false)
    private UUID uuid = UUID.randomUUID();

    @JsonIgnore
    @Column(name = "white_player_id", nullable = false)
    private Long whitePlayerId;

    @JsonIgnore
    @Column(name = "black_player_id")
    private Long blackPlayerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "game_type", nullable = false, length = 10)
    private GameType gameType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private GameStatus status;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private GameResult result;

    @Enumerated(EnumType.STRING)
    @Column(name = "result_reason", length = 30)
    private GameResultReason resultReason;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String fen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1";

    @Column(columnDefinition = "TEXT")
    private String pgn;

    @Column(name = "ai_difficulty")
    private Integer aiDifficulty;

    @Column(name = "move_count", nullable = false)
    private int moveCount;

    @Column(name = "draw_offered_by")
    private Long drawOfferedBy;

    @Column(name = "last_move_at")
    private Instant lastMoveAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public void transition(GameEvent event) {
        var allowed = TRANSITIONS.get(status);
        if (allowed == null || !allowed.containsKey(event)) {
            throw new IllegalStateException(
                "Invalid transition: " + status + " + " + event);
        }
        status = allowed.get(event);
        updatedAt = Instant.now();
    }

    public Set<GameEvent> allowedEvents() {
        var allowed = TRANSITIONS.get(status);
        return allowed != null ? allowed.keySet() : Set.of();
    }

    public boolean isTerminal() {
        return status == GameStatus.FINISHED || status == GameStatus.ABANDONED;
    }

    public boolean isPlayerInGame(Long userId) {
        return userId.equals(whitePlayerId) || userId.equals(blackPlayerId);
    }

    public boolean isWhiteTurn() {
        return fen.contains(" w ");
    }

    public boolean isPlayersTurn(Long userId) {
        if (isWhiteTurn()) {
            return userId.equals(whitePlayerId);
        }
        return userId.equals(blackPlayerId);
    }
}
