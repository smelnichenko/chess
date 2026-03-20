package io.schnappy.chess;

import io.schnappy.chess.kafka.ChessKafkaProducer;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChessServiceTest {

    @Mock
    private ChessGameRepository gameRepository;

    @Mock
    private ChessGameCacheService cacheService;

    @Mock
    private ChessKafkaProducer kafkaProducer;

    @InjectMocks
    private ChessService chessService;

    private static final Long WHITE_USER = 1L;
    private static final Long BLACK_USER = 2L;

    @BeforeEach
    void setUp() {
        // Make repository.save() return the game it receives.
        // lenient() suppresses UnnecessaryStubbingException for tests that throw before save() is reached.
        lenient().when(gameRepository.save(any(ChessGame.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    // -----------------------------------------------------------------------
    // createAiGame
    // -----------------------------------------------------------------------

    @Test
    void createAiGame_validDifficulty_returnsGame() {
        ChessGame result = chessService.createAiGame(WHITE_USER, 10);

        assertThat(result.getWhitePlayerId()).isEqualTo(WHITE_USER);
        assertThat(result.getGameType()).isEqualTo(GameType.AI);
        assertThat(result.getStatus()).isEqualTo(GameStatus.IN_PROGRESS);
        assertThat(result.getAiDifficulty()).isEqualTo(10);
        verify(cacheService).cache(any(ChessGameDto.class));
    }

    @Test
    void createAiGame_difficultyZero_allowed() {
        ChessGame result = chessService.createAiGame(WHITE_USER, 0);
        assertThat(result.getAiDifficulty()).isZero();
    }

    @Test
    void createAiGame_difficultyTwenty_allowed() {
        ChessGame result = chessService.createAiGame(WHITE_USER, 20);
        assertThat(result.getAiDifficulty()).isEqualTo(20);
    }

    @Test
    void createAiGame_difficultyNegative_throws() {
        assertThatThrownBy(() -> chessService.createAiGame(WHITE_USER, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Difficulty must be between 0 and 20");
    }

    @Test
    void createAiGame_difficultyTooHigh_throws() {
        assertThatThrownBy(() -> chessService.createAiGame(WHITE_USER, 21))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Difficulty must be between 0 and 20");
    }

    // -----------------------------------------------------------------------
    // createPvpGame
    // -----------------------------------------------------------------------

    @Test
    void createPvpGame_returnsWaitingGame() {
        ChessGame result = chessService.createPvpGame(WHITE_USER);

        assertThat(result.getWhitePlayerId()).isEqualTo(WHITE_USER);
        assertThat(result.getGameType()).isEqualTo(GameType.PVP);
        assertThat(result.getStatus()).isEqualTo(GameStatus.WAITING_FOR_OPPONENT);
        verify(cacheService).cache(any(ChessGameDto.class));
    }

    // -----------------------------------------------------------------------
    // joinGame
    // -----------------------------------------------------------------------

    @Test
    void joinGame_validOpponent_transitionsToInProgress() {
        ChessGame game = pvpWaitingGame();
        when(gameRepository.findByUuid(game.getUuid())).thenReturn(Optional.of(game));

        ChessGame result = chessService.joinGame(game.getUuid(), BLACK_USER);

        assertThat(result.getBlackPlayerId()).isEqualTo(BLACK_USER);
        assertThat(result.getStatus()).isEqualTo(GameStatus.IN_PROGRESS);
        verify(kafkaProducer).publishGameEvent(any(ChessGameDto.class));
    }

    @Test
    void joinGame_creatorJoinsOwnGame_throws() {
        ChessGame game = pvpWaitingGame();
        UUID gameUuid = game.getUuid();
        when(gameRepository.findByUuid(gameUuid)).thenReturn(Optional.of(game));

        assertThatThrownBy(() -> chessService.joinGame(gameUuid, WHITE_USER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cannot join own game");
    }

    @Test
    void joinGame_gameAlreadyHasOpponent_throws() {
        ChessGame game = pvpInProgressGame();
        UUID gameUuid = game.getUuid();
        when(gameRepository.findByUuid(gameUuid)).thenReturn(Optional.of(game));

        assertThatThrownBy(() -> chessService.joinGame(gameUuid, 3L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Game already has an opponent");
    }

    @Test
    void joinGame_unknownUuid_throws() {
        UUID unknown = UUID.randomUUID();
        when(gameRepository.findByUuid(unknown)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> chessService.joinGame(unknown, BLACK_USER))
                .isInstanceOf(EntityNotFoundException.class);
    }

    // -----------------------------------------------------------------------
    // makeMove
    // -----------------------------------------------------------------------

    @Test
    void makeMove_validMove_updatesFenAndPgn() {
        ChessGame game = pvpInProgressGame();
        when(gameRepository.findByUuid(game.getUuid())).thenReturn(Optional.of(game));

        // White plays e2e4 — standard opening move
        ChessGame result = chessService.makeMove(game.getUuid(), "e2e4", WHITE_USER);

        assertThat(result.getFen()).isNotEqualTo("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1");
        assertThat(result.getPgn()).contains("e2e4");
        assertThat(result.getMoveCount()).isEqualTo(1);
        verify(kafkaProducer).publishGameEvent(any(ChessGameDto.class));
    }

    @Test
    void makeMove_illegalMove_throws() {
        ChessGame game = pvpInProgressGame();
        UUID gameUuid = game.getUuid();
        when(gameRepository.findByUuid(gameUuid)).thenReturn(Optional.of(game));

        // e2e5 is not a legal pawn move from starting position
        assertThatThrownBy(() -> chessService.makeMove(gameUuid, "e2e5", WHITE_USER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Illegal move");
    }

    @Test
    void makeMove_wrongTurn_throws() {
        ChessGame game = pvpInProgressGame();
        UUID gameUuid = game.getUuid();
        when(gameRepository.findByUuid(gameUuid)).thenReturn(Optional.of(game));

        // It's white's turn but black tries to move
        assertThatThrownBy(() -> chessService.makeMove(gameUuid, "e7e5", BLACK_USER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Not your turn");
    }

    @Test
    void makeMove_nonPlayerMoves_throws() {
        ChessGame game = pvpInProgressGame();
        UUID gameUuid = game.getUuid();
        when(gameRepository.findByUuid(gameUuid)).thenReturn(Optional.of(game));

        assertThatThrownBy(() -> chessService.makeMove(gameUuid, "e2e4", 99L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Not a player in this game");
    }

    @Test
    void makeMove_finishedGame_throws() {
        ChessGame game = finishedGame();
        UUID gameUuid = game.getUuid();
        when(gameRepository.findByUuid(gameUuid)).thenReturn(Optional.of(game));

        assertThatThrownBy(() -> chessService.makeMove(gameUuid, "e2e4", WHITE_USER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Game is already finished");
    }

    @Test
    void makeMove_aiGame_noKafkaPublish() {
        ChessGame game = aiInProgressGame();
        when(gameRepository.findByUuid(game.getUuid())).thenReturn(Optional.of(game));

        chessService.makeMove(game.getUuid(), "e2e4", WHITE_USER);

        verify(kafkaProducer, never()).publishGameEvent(any());
    }

    // -----------------------------------------------------------------------
    // makeAiMove
    // -----------------------------------------------------------------------

    @Test
    void makeAiMove_validMove_updatesGame() {
        // White moved e2e4, now it's black's (AI's) turn
        ChessGame game = aiInProgressGame();
        game.setFen("rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1");
        game.setMoveCount(1);
        when(gameRepository.findByUuid(game.getUuid())).thenReturn(Optional.of(game));

        ChessGame result = chessService.makeAiMove(game.getUuid(), "e7e5", WHITE_USER);

        assertThat(result.getMoveCount()).isEqualTo(2);
        assertThat(result.getPgn()).contains("e7e5");
    }

    @Test
    void makeAiMove_notAiGame_throws() {
        ChessGame game = pvpInProgressGame();
        UUID gameUuid = game.getUuid();
        when(gameRepository.findByUuid(gameUuid)).thenReturn(Optional.of(game));

        assertThatThrownBy(() -> chessService.makeAiMove(gameUuid, "e7e5", WHITE_USER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Not an AI game");
    }

    @Test
    void makeAiMove_nonOwnerCalls_throws() {
        ChessGame game = aiInProgressGame();
        UUID gameUuid = game.getUuid();
        when(gameRepository.findByUuid(gameUuid)).thenReturn(Optional.of(game));

        assertThatThrownBy(() -> chessService.makeAiMove(gameUuid, "e7e5", BLACK_USER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Not a player in this game");
    }

    @Test
    void makeAiMove_whiteTurn_throws() {
        // Still white's turn — AI shouldn't move
        ChessGame game = aiInProgressGame();
        UUID gameUuid = game.getUuid();
        when(gameRepository.findByUuid(gameUuid)).thenReturn(Optional.of(game));

        assertThatThrownBy(() -> chessService.makeAiMove(gameUuid, "e7e5", WHITE_USER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Not the AI's turn");
    }

    // -----------------------------------------------------------------------
    // resign
    // -----------------------------------------------------------------------

    @Test
    void resign_white_blackWins() {
        ChessGame game = pvpInProgressGame();
        when(gameRepository.findByUuid(game.getUuid())).thenReturn(Optional.of(game));

        ChessGame result = chessService.resign(game.getUuid(), WHITE_USER);

        assertThat(result.getResult()).isEqualTo(GameResult.BLACK_WINS);
        assertThat(result.getResultReason()).isEqualTo(GameResultReason.RESIGNATION);
        assertThat(result.getStatus()).isEqualTo(GameStatus.FINISHED);
        verify(kafkaProducer).publishGameEvent(any(ChessGameDto.class));
    }

    @Test
    void resign_black_whiteWins() {
        ChessGame game = pvpInProgressGame();
        when(gameRepository.findByUuid(game.getUuid())).thenReturn(Optional.of(game));

        ChessGame result = chessService.resign(game.getUuid(), BLACK_USER);

        assertThat(result.getResult()).isEqualTo(GameResult.WHITE_WINS);
        assertThat(result.getResultReason()).isEqualTo(GameResultReason.RESIGNATION);
    }

    @Test
    void resign_nonPlayer_throws() {
        ChessGame game = pvpInProgressGame();
        UUID gameUuid = game.getUuid();
        when(gameRepository.findByUuid(gameUuid)).thenReturn(Optional.of(game));

        assertThatThrownBy(() -> chessService.resign(gameUuid, 99L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Not a player in this game");
    }

    // -----------------------------------------------------------------------
    // offerDraw / acceptDraw / declineDraw
    // -----------------------------------------------------------------------

    @Test
    void offerDraw_pvpGame_setsDrawOfferedBy() {
        ChessGame game = pvpInProgressGame();
        when(gameRepository.findByUuid(game.getUuid())).thenReturn(Optional.of(game));

        ChessGame result = chessService.offerDraw(game.getUuid(), WHITE_USER);

        assertThat(result.getDrawOfferedBy()).isEqualTo(WHITE_USER);
        verify(kafkaProducer).publishGameEvent(any(ChessGameDto.class));
    }

    @Test
    void offerDraw_aiGame_throws() {
        ChessGame game = aiInProgressGame();
        UUID gameUuid = game.getUuid();
        when(gameRepository.findByUuid(gameUuid)).thenReturn(Optional.of(game));

        assertThatThrownBy(() -> chessService.offerDraw(gameUuid, WHITE_USER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Draw offers only in PvP games");
    }

    @Test
    void acceptDraw_validOffer_resultsInDraw() {
        ChessGame game = pvpInProgressGame();
        game.setDrawOfferedBy(WHITE_USER);
        when(gameRepository.findByUuid(game.getUuid())).thenReturn(Optional.of(game));

        ChessGame result = chessService.acceptDraw(game.getUuid(), BLACK_USER);

        assertThat(result.getResult()).isEqualTo(GameResult.DRAW);
        assertThat(result.getResultReason()).isEqualTo(GameResultReason.AGREEMENT);
        assertThat(result.getStatus()).isEqualTo(GameStatus.FINISHED);
    }

    @Test
    void acceptDraw_noOfferPending_throws() {
        ChessGame game = pvpInProgressGame();
        UUID gameUuid = game.getUuid();
        when(gameRepository.findByUuid(gameUuid)).thenReturn(Optional.of(game));

        assertThatThrownBy(() -> chessService.acceptDraw(gameUuid, BLACK_USER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No pending draw offer to accept");
    }

    @Test
    void acceptDraw_ownOffer_throws() {
        ChessGame game = pvpInProgressGame();
        game.setDrawOfferedBy(WHITE_USER);
        UUID gameUuid = game.getUuid();
        when(gameRepository.findByUuid(gameUuid)).thenReturn(Optional.of(game));

        // White offered, white tries to accept their own offer
        assertThatThrownBy(() -> chessService.acceptDraw(gameUuid, WHITE_USER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No pending draw offer to accept");
    }

    @Test
    void declineDraw_validOffer_clearsOffer() {
        ChessGame game = pvpInProgressGame();
        game.setDrawOfferedBy(WHITE_USER);
        when(gameRepository.findByUuid(game.getUuid())).thenReturn(Optional.of(game));

        ChessGame result = chessService.declineDraw(game.getUuid(), BLACK_USER);

        assertThat(result.getDrawOfferedBy()).isNull();
        assertThat(result.getStatus()).isEqualTo(GameStatus.IN_PROGRESS);
    }

    @Test
    void declineDraw_noOfferPending_throws() {
        ChessGame game = pvpInProgressGame();
        UUID gameUuid = game.getUuid();
        when(gameRepository.findByUuid(gameUuid)).thenReturn(Optional.of(game));

        assertThatThrownBy(() -> chessService.declineDraw(gameUuid, BLACK_USER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No pending draw offer to decline");
    }

    // -----------------------------------------------------------------------
    // abandon
    // -----------------------------------------------------------------------

    @Test
    void abandon_creator_transitionsToAbandoned() {
        ChessGame game = pvpWaitingGame();
        when(gameRepository.findByUuid(game.getUuid())).thenReturn(Optional.of(game));

        ChessGame result = chessService.abandon(game.getUuid(), WHITE_USER);

        assertThat(result.getStatus()).isEqualTo(GameStatus.ABANDONED);
        verify(cacheService).evict(game.getUuid());
    }

    @Test
    void abandon_nonCreator_throws() {
        ChessGame game = pvpWaitingGame();
        UUID gameUuid = game.getUuid();
        when(gameRepository.findByUuid(gameUuid)).thenReturn(Optional.of(game));

        assertThatThrownBy(() -> chessService.abandon(gameUuid, BLACK_USER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Only the creator can abandon");
    }

    // -----------------------------------------------------------------------
    // getGame
    // -----------------------------------------------------------------------

    @Test
    void getGame_cacheHit_returnsCachedDto() {
        UUID gameUuid = UUID.randomUUID();
        ChessGameDto cached = ChessGameDto.builder()
                .gameUuid(gameUuid.toString())
                .status("IN_PROGRESS")
                .gameType("PVP")
                .fen("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1")
                .build();
        when(cacheService.get(gameUuid)).thenReturn(Optional.of(cached));

        ChessGameDto result = chessService.getGame(gameUuid);

        assertThat(result).isEqualTo(cached);
        verify(gameRepository, never()).findByUuid(any());
    }

    @Test
    void getGame_cacheMiss_loadsFromRepository() {
        ChessGame game = pvpInProgressGame();
        when(cacheService.get(game.getUuid())).thenReturn(Optional.empty());
        when(gameRepository.findByUuid(game.getUuid())).thenReturn(Optional.of(game));

        ChessGameDto result = chessService.getGame(game.getUuid());

        assertThat(result.getGameUuid()).isEqualTo(game.getUuid().toString());
        verify(cacheService).cache(any(ChessGameDto.class));
    }

    @Test
    void getGame_notFound_throws() {
        UUID unknown = UUID.randomUUID();
        when(cacheService.get(unknown)).thenReturn(Optional.empty());
        when(gameRepository.findByUuid(unknown)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> chessService.getGame(unknown))
                .isInstanceOf(EntityNotFoundException.class);
    }

    // -----------------------------------------------------------------------
    // getActiveGames / getOpenGames / getHistory
    // -----------------------------------------------------------------------

    @Test
    void getActiveGames_delegatesToRepository() {
        ChessGame game = pvpInProgressGame();
        when(gameRepository.findActiveByUserId(WHITE_USER)).thenReturn(List.of(game));

        List<ChessGame> result = chessService.getActiveGames(WHITE_USER);

        assertThat(result).containsExactly(game);
    }

    @Test
    void getOpenGames_delegatesToRepository() {
        ChessGame game = pvpWaitingGame();
        when(gameRepository.findByStatusOrderByCreatedAtDesc(GameStatus.WAITING_FOR_OPPONENT))
                .thenReturn(List.of(game));

        List<ChessGame> result = chessService.getOpenGames();

        assertThat(result).containsExactly(game);
    }

    @Test
    void getHistory_delegatesToRepository() {
        ChessGame game = finishedGame();
        var pageable = PageRequest.of(0, 10);
        when(gameRepository.findHistoryByUserId(WHITE_USER, pageable))
                .thenReturn(new PageImpl<>(List.of(game)));

        var result = chessService.getHistory(WHITE_USER, pageable);

        assertThat(result.getContent()).containsExactly(game);
    }

    // -----------------------------------------------------------------------
    // PGN building (via makeMove sequence)
    // -----------------------------------------------------------------------

    @Test
    void pgn_firstWhiteMove_hasFullMoveNumber() {
        ChessGame game = pvpInProgressGame();
        when(gameRepository.findByUuid(game.getUuid())).thenReturn(Optional.of(game));

        ChessGame result = chessService.makeMove(game.getUuid(), "e2e4", WHITE_USER);

        // After move 1 (moveCount=1), PGN should be "1. e2e4"
        assertThat(result.getPgn()).isEqualTo("1. e2e4");
    }

    @Test
    void pgn_secondMove_appendsWithoutMoveNumber() {
        // Start: white played e2e4 (moveCount=1), now black's turn
        ChessGame game = pvpInProgressGame();
        game.setFen("rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1");
        game.setMoveCount(1);
        game.setPgn("1. e2e4");
        when(gameRepository.findByUuid(game.getUuid())).thenReturn(Optional.of(game));

        ChessGame result = chessService.makeMove(game.getUuid(), "e7e5", BLACK_USER);

        assertThat(result.getPgn()).isEqualTo("1. e2e4 e7e5");
    }

    @Test
    void pgn_thirdMove_addsNextMoveNumber() {
        // After "1. e2e4 e7e5", moveCount=2, white's turn
        ChessGame game = pvpInProgressGame();
        game.setFen("rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR w KQkq e6 0 2");
        game.setMoveCount(2);
        game.setPgn("1. e2e4 e7e5");
        when(gameRepository.findByUuid(game.getUuid())).thenReturn(Optional.of(game));

        ChessGame result = chessService.makeMove(game.getUuid(), "g1f3", WHITE_USER);

        assertThat(result.getPgn()).isEqualTo("1. e2e4 e7e5 2. g1f3");
    }

    // -----------------------------------------------------------------------
    // Helper factories
    // -----------------------------------------------------------------------

    private ChessGame pvpWaitingGame() {
        var game = new ChessGame();
        game.setWhitePlayerId(WHITE_USER);
        game.setGameType(GameType.PVP);
        game.setStatus(GameStatus.WAITING_FOR_OPPONENT);
        return game;
    }

    private ChessGame pvpInProgressGame() {
        var game = new ChessGame();
        game.setWhitePlayerId(WHITE_USER);
        game.setBlackPlayerId(BLACK_USER);
        game.setGameType(GameType.PVP);
        game.setStatus(GameStatus.IN_PROGRESS);
        return game;
    }

    private ChessGame aiInProgressGame() {
        var game = new ChessGame();
        game.setWhitePlayerId(WHITE_USER);
        game.setGameType(GameType.AI);
        game.setStatus(GameStatus.IN_PROGRESS);
        game.setAiDifficulty(10);
        return game;
    }

    private ChessGame finishedGame() {
        var game = new ChessGame();
        game.setWhitePlayerId(WHITE_USER);
        game.setBlackPlayerId(BLACK_USER);
        game.setGameType(GameType.PVP);
        game.setStatus(GameStatus.FINISHED);
        game.setResult(GameResult.WHITE_WINS);
        game.setResultReason(GameResultReason.CHECKMATE);
        return game;
    }
}
