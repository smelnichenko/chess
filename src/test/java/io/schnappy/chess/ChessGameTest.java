package io.schnappy.chess;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChessGameTest {

    private static final UUID WHITE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID BLACK = UUID.fromString("00000000-0000-0000-0000-000000000002");

    // -----------------------------------------------------------------------
    // transition — state machine
    // -----------------------------------------------------------------------

    @Test
    void transition_waitingToInProgress_onOpponentJoined() {
        ChessGame game = waitingGame();

        game.transition(GameEvent.OPPONENT_JOINED);

        assertThat(game.getStatus()).isEqualTo(GameStatus.IN_PROGRESS);
    }

    @Test
    void transition_waitingToAbandoned_onAbandon() {
        ChessGame game = waitingGame();

        game.transition(GameEvent.ABANDON);

        assertThat(game.getStatus()).isEqualTo(GameStatus.ABANDONED);
    }

    @Test
    void transition_inProgressToInProgress_onMoveMade() {
        ChessGame game = inProgressGame();

        game.transition(GameEvent.MOVE_MADE);

        assertThat(game.getStatus()).isEqualTo(GameStatus.IN_PROGRESS);
    }

    @Test
    void transition_inProgressToFinished_onCheckmate() {
        ChessGame game = inProgressGame();

        game.transition(GameEvent.CHECKMATE);

        assertThat(game.getStatus()).isEqualTo(GameStatus.FINISHED);
    }

    @Test
    void transition_inProgressToFinished_onStalemate() {
        ChessGame game = inProgressGame();

        game.transition(GameEvent.STALEMATE);

        assertThat(game.getStatus()).isEqualTo(GameStatus.FINISHED);
    }

    @Test
    void transition_inProgressToFinished_onResign() {
        ChessGame game = inProgressGame();

        game.transition(GameEvent.RESIGN);

        assertThat(game.getStatus()).isEqualTo(GameStatus.FINISHED);
    }

    @Test
    void transition_inProgressToFinished_onDrawAgreed() {
        ChessGame game = inProgressGame();

        game.transition(GameEvent.DRAW_AGREED);

        assertThat(game.getStatus()).isEqualTo(GameStatus.FINISHED);
    }

    @Test
    void transition_invalidFromWaiting_moveMade_throws() {
        ChessGame game = waitingGame();

        assertThatThrownBy(() -> game.transition(GameEvent.MOVE_MADE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Invalid transition");
    }

    @Test
    void transition_invalidFromFinished_throws() {
        ChessGame game = finishedGame();

        assertThatThrownBy(() -> game.transition(GameEvent.MOVE_MADE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Invalid transition");
    }

    @Test
    void transition_invalidFromAbandoned_throws() {
        ChessGame game = new ChessGame();
        game.setStatus(GameStatus.ABANDONED);

        assertThatThrownBy(() -> game.transition(GameEvent.OPPONENT_JOINED))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Invalid transition");
    }

    @Test
    void transition_updatesTimestamp() {
        ChessGame game = waitingGame();
        var before = game.getUpdatedAt();

        game.transition(GameEvent.OPPONENT_JOINED);

        assertThat(game.getUpdatedAt()).isAfterOrEqualTo(before);
    }

    // -----------------------------------------------------------------------
    // allowedEvents
    // -----------------------------------------------------------------------

    @Test
    void allowedEvents_waitingForOpponent() {
        ChessGame game = waitingGame();

        Set<GameEvent> allowed = game.allowedEvents();

        assertThat(allowed).containsExactlyInAnyOrder(GameEvent.OPPONENT_JOINED, GameEvent.ABANDON);
    }

    @Test
    void allowedEvents_inProgress() {
        ChessGame game = inProgressGame();

        Set<GameEvent> allowed = game.allowedEvents();

        assertThat(allowed).containsExactlyInAnyOrder(
                GameEvent.MOVE_MADE, GameEvent.CHECKMATE, GameEvent.STALEMATE,
                GameEvent.RESIGN, GameEvent.DRAW_AGREED);
    }

    @Test
    void allowedEvents_finished_returnsEmpty() {
        ChessGame game = finishedGame();

        assertThat(game.allowedEvents()).isEmpty();
    }

    @Test
    void allowedEvents_abandoned_returnsEmpty() {
        ChessGame game = new ChessGame();
        game.setStatus(GameStatus.ABANDONED);

        assertThat(game.allowedEvents()).isEmpty();
    }

    // -----------------------------------------------------------------------
    // isTerminal
    // -----------------------------------------------------------------------

    @Test
    void isTerminal_finished_true() {
        ChessGame game = finishedGame();
        assertThat(game.isTerminal()).isTrue();
    }

    @Test
    void isTerminal_abandoned_true() {
        ChessGame game = new ChessGame();
        game.setStatus(GameStatus.ABANDONED);
        assertThat(game.isTerminal()).isTrue();
    }

    @Test
    void isTerminal_inProgress_false() {
        ChessGame game = inProgressGame();
        assertThat(game.isTerminal()).isFalse();
    }

    @Test
    void isTerminal_waiting_false() {
        ChessGame game = waitingGame();
        assertThat(game.isTerminal()).isFalse();
    }

    // -----------------------------------------------------------------------
    // isPlayerInGame
    // -----------------------------------------------------------------------

    @Test
    void isPlayerInGame_whitePlayer_true() {
        ChessGame game = inProgressGame();
        assertThat(game.isPlayerInGame(WHITE)).isTrue();
    }

    @Test
    void isPlayerInGame_blackPlayer_true() {
        ChessGame game = inProgressGame();
        assertThat(game.isPlayerInGame(BLACK)).isTrue();
    }

    @Test
    void isPlayerInGame_otherPlayer_false() {
        ChessGame game = inProgressGame();
        assertThat(game.isPlayerInGame(UUID.fromString("00000000-0000-0000-0000-000000000099"))).isFalse();
    }

    @Test
    void isPlayerInGame_waitingGame_blackNull_onlyWhiteMatches() {
        ChessGame game = waitingGame();
        assertThat(game.isPlayerInGame(WHITE)).isTrue();
        // blackPlayerUuid is null, so non-white should return false
        assertThat(game.isPlayerInGame(BLACK)).isFalse();
    }

    // -----------------------------------------------------------------------
    // isWhiteTurn / isPlayersTurn
    // -----------------------------------------------------------------------

    @Test
    void isWhiteTurn_startingPosition_true() {
        ChessGame game = inProgressGame();
        // Default FEN has " w " indicating white's turn
        assertThat(game.isWhiteTurn()).isTrue();
    }

    @Test
    void isWhiteTurn_blackTurn_false() {
        ChessGame game = inProgressGame();
        game.setFen("rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1");
        assertThat(game.isWhiteTurn()).isFalse();
    }

    @Test
    void isPlayersTurn_whiteOnWhiteTurn_true() {
        ChessGame game = inProgressGame();
        assertThat(game.isPlayersTurn(WHITE)).isTrue();
    }

    @Test
    void isPlayersTurn_blackOnWhiteTurn_false() {
        ChessGame game = inProgressGame();
        assertThat(game.isPlayersTurn(BLACK)).isFalse();
    }

    @Test
    void isPlayersTurn_blackOnBlackTurn_true() {
        ChessGame game = inProgressGame();
        game.setFen("rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1");
        assertThat(game.isPlayersTurn(BLACK)).isTrue();
    }

    @Test
    void isPlayersTurn_whiteOnBlackTurn_false() {
        ChessGame game = inProgressGame();
        game.setFen("rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1");
        assertThat(game.isPlayersTurn(WHITE)).isFalse();
    }

    // -----------------------------------------------------------------------
    // Default values
    // -----------------------------------------------------------------------

    @Test
    void newGame_hasDefaultFen() {
        ChessGame game = new ChessGame();
        assertThat(game.getFen()).isEqualTo("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1");
    }

    @Test
    void newGame_hasUuid() {
        ChessGame game = new ChessGame();
        assertThat(game.getUuid()).isNotNull();
    }

    @Test
    void newGame_hasCreatedAt() {
        ChessGame game = new ChessGame();
        assertThat(game.getCreatedAt()).isNotNull();
    }

    @Test
    void newGame_hasZeroMoveCount() {
        ChessGame game = new ChessGame();
        assertThat(game.getMoveCount()).isZero();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private ChessGame waitingGame() {
        var game = new ChessGame();
        game.setWhitePlayerUuid(WHITE);
        game.setGameType(GameType.PVP);
        game.setStatus(GameStatus.WAITING_FOR_OPPONENT);
        return game;
    }

    private ChessGame inProgressGame() {
        var game = new ChessGame();
        game.setWhitePlayerUuid(WHITE);
        game.setBlackPlayerUuid(BLACK);
        game.setGameType(GameType.PVP);
        game.setStatus(GameStatus.IN_PROGRESS);
        return game;
    }

    private ChessGame finishedGame() {
        var game = new ChessGame();
        game.setWhitePlayerUuid(WHITE);
        game.setBlackPlayerUuid(BLACK);
        game.setGameType(GameType.PVP);
        game.setStatus(GameStatus.FINISHED);
        game.setResult(GameResult.WHITE_WINS);
        game.setResultReason(GameResultReason.CHECKMATE);
        return game;
    }
}
