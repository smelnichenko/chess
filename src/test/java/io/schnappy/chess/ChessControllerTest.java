package io.schnappy.chess;

import io.schnappy.chess.security.GatewayUser;
import io.schnappy.chess.security.Permission;
import io.schnappy.chess.security.RequirePermission;
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
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Plain unit tests for ChessController.
 *
 * The controller methods are lightweight — they extract parameters, delegate to ChessService,
 * and map results to DTOs. We test that delegation, argument passing, and return-value mapping
 * are correct without spinning up a Spring context.
 *
 * Permission enforcement is handled by the PermissionInterceptor AOP aspect at runtime; the
 * @RequirePermission annotation itself is tested via reflection where relevant.
 */
@ExtendWith(MockitoExtension.class)
class ChessControllerTest {

    @Mock
    private ChessService chessService;

    @InjectMocks
    private ChessController controller;

    private GatewayUser whiteUser;
    private GatewayUser blackUser;

    private static final Long WHITE_ID = 1L;
    private static final Long BLACK_ID = 2L;

    @BeforeEach
    void setUp() {
        whiteUser = new GatewayUser(WHITE_ID, "uuid-white", "white@example.com", List.of("PLAY"));
        blackUser = new GatewayUser(BLACK_ID, "uuid-black", "black@example.com", List.of("PLAY"));
    }

    // -----------------------------------------------------------------------
    // Class-level @RequirePermission annotation
    // -----------------------------------------------------------------------

    @Test
    void controllerClass_hasRequirePlayPermission() {
        RequirePermission annotation = ChessController.class.getAnnotation(RequirePermission.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.value()).isEqualTo(Permission.PLAY);
    }

    // -----------------------------------------------------------------------
    // createGame
    // -----------------------------------------------------------------------

    @Test
    void createGame_aiType_callsCreateAiGameWithDefaultDifficulty() {
        ChessGame game = aiGame(WHITE_ID);
        when(chessService.createAiGame(WHITE_ID, 10)).thenReturn(game);

        var request = new CreateGameRequest(GameType.AI, null);
        var response = controller.createGame(request, whiteUser);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getGameType()).isEqualTo("AI");
        verify(chessService).createAiGame(WHITE_ID, 10);
    }

    @Test
    void createGame_aiTypeWithDifficulty_usesProvidedDifficulty() {
        ChessGame game = aiGame(WHITE_ID);
        when(chessService.createAiGame(WHITE_ID, 15)).thenReturn(game);

        var request = new CreateGameRequest(GameType.AI, 15);
        controller.createGame(request, whiteUser);

        verify(chessService).createAiGame(WHITE_ID, 15);
    }

    @Test
    void createGame_pvpType_callsCreatePvpGame() {
        ChessGame game = pvpWaitingGame(WHITE_ID);
        when(chessService.createPvpGame(WHITE_ID)).thenReturn(game);

        var request = new CreateGameRequest(GameType.PVP, null);
        var response = controller.createGame(request, whiteUser);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(chessService).createPvpGame(WHITE_ID);
    }

    // -----------------------------------------------------------------------
    // getActiveGames
    // -----------------------------------------------------------------------

    @Test
    void getActiveGames_returnsMappedDtos() {
        ChessGame game = pvpInProgressGame(WHITE_ID, BLACK_ID);
        when(chessService.getActiveGames(WHITE_ID)).thenReturn(List.of(game));

        List<ChessGameDto> result = controller.getActiveGames(whiteUser);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getStatus()).isEqualTo("IN_PROGRESS");
    }

    @Test
    void getActiveGames_emptyList_returnsEmpty() {
        when(chessService.getActiveGames(WHITE_ID)).thenReturn(List.of());

        List<ChessGameDto> result = controller.getActiveGames(whiteUser);

        assertThat(result).isEmpty();
    }

    // -----------------------------------------------------------------------
    // getOpenGames
    // -----------------------------------------------------------------------

    @Test
    void getOpenGames_returnsMappedDtos() {
        ChessGame game = pvpWaitingGame(WHITE_ID);
        when(chessService.getOpenGames()).thenReturn(List.of(game));

        List<ChessGameDto> result = controller.getOpenGames();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getStatus()).isEqualTo("WAITING_FOR_OPPONENT");
    }

    // -----------------------------------------------------------------------
    // getGame
    // -----------------------------------------------------------------------

    @Test
    void getGame_existingUuid_returnsDto() {
        UUID uuid = UUID.randomUUID();
        ChessGameDto dto = ChessGameDto.builder()
                .gameUuid(uuid.toString())
                .status("IN_PROGRESS")
                .gameType("PVP")
                .fen("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1")
                .build();
        when(chessService.getGame(uuid)).thenReturn(dto);

        ChessGameDto result = controller.getGame(uuid);

        assertThat(result.getGameUuid()).isEqualTo(uuid.toString());
    }

    @Test
    void getGame_unknownUuid_propagatesException() {
        UUID uuid = UUID.randomUUID();
        when(chessService.getGame(uuid)).thenThrow(new EntityNotFoundException("Game not found: " + uuid));

        assertThatThrownBy(() -> controller.getGame(uuid))
                .isInstanceOf(EntityNotFoundException.class);
    }

    // -----------------------------------------------------------------------
    // getHistory
    // -----------------------------------------------------------------------

    @Test
    void getHistory_returnsMappedPage() {
        ChessGame game = finishedGame(WHITE_ID, BLACK_ID);
        var pageable = PageRequest.of(0, 10);
        when(chessService.getHistory(WHITE_ID, pageable)).thenReturn(new PageImpl<>(List.of(game)));

        var result = controller.getHistory(whiteUser, pageable);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).getStatus()).isEqualTo("FINISHED");
    }

    // -----------------------------------------------------------------------
    // joinGame
    // -----------------------------------------------------------------------

    @Test
    void joinGame_delegatesToService() {
        UUID uuid = UUID.randomUUID();
        ChessGame game = pvpInProgressGame(WHITE_ID, BLACK_ID);
        when(chessService.joinGame(uuid, BLACK_ID)).thenReturn(game);

        ChessGameDto result = controller.joinGame(uuid, blackUser);

        assertThat(result.getStatus()).isEqualTo("IN_PROGRESS");
        verify(chessService).joinGame(uuid, BLACK_ID);
    }

    // -----------------------------------------------------------------------
    // makeMove
    // -----------------------------------------------------------------------

    @Test
    void makeMove_validBody_delegatesToService() {
        UUID uuid = UUID.randomUUID();
        ChessGame game = pvpInProgressGame(WHITE_ID, BLACK_ID);
        when(chessService.makeMove(uuid, "e2e4", WHITE_ID)).thenReturn(game);

        ChessGameDto result = controller.makeMove(uuid, Map.of("move", "e2e4"), whiteUser);

        assertThat(result).isNotNull();
        verify(chessService).makeMove(uuid, "e2e4", WHITE_ID);
    }

    @Test
    void makeMove_moveWithWhitespace_trimmed() {
        UUID uuid = UUID.randomUUID();
        ChessGame game = pvpInProgressGame(WHITE_ID, BLACK_ID);
        when(chessService.makeMove(uuid, "e2e4", WHITE_ID)).thenReturn(game);

        controller.makeMove(uuid, Map.of("move", "  e2e4  "), whiteUser);

        verify(chessService).makeMove(uuid, "e2e4", WHITE_ID);
    }

    @Test
    void makeMove_nullMove_throws() {
        UUID uuid = UUID.randomUUID();

        assertThatThrownBy(() -> controller.makeMove(uuid, Map.of(), whiteUser))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Move is required");
    }

    @Test
    void makeMove_blankMove_throws() {
        UUID uuid = UUID.randomUUID();

        assertThatThrownBy(() -> controller.makeMove(uuid, Map.of("move", "   "), whiteUser))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Move is required");
    }

    // -----------------------------------------------------------------------
    // makeAiMove
    // -----------------------------------------------------------------------

    @Test
    void makeAiMove_validBody_delegatesToService() {
        UUID uuid = UUID.randomUUID();
        ChessGame game = aiGame(WHITE_ID);
        when(chessService.makeAiMove(uuid, "e7e5", WHITE_ID)).thenReturn(game);

        ChessGameDto result = controller.makeAiMove(uuid, Map.of("move", "e7e5"), whiteUser);

        assertThat(result).isNotNull();
        verify(chessService).makeAiMove(uuid, "e7e5", WHITE_ID);
    }

    @Test
    void makeAiMove_nullMove_throws() {
        UUID uuid = UUID.randomUUID();

        assertThatThrownBy(() -> controller.makeAiMove(uuid, Map.of(), whiteUser))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Move is required");
    }

    // -----------------------------------------------------------------------
    // resign
    // -----------------------------------------------------------------------

    @Test
    void resign_delegatesToService() {
        UUID uuid = UUID.randomUUID();
        ChessGame game = finishedGame(WHITE_ID, BLACK_ID);
        when(chessService.resign(uuid, WHITE_ID)).thenReturn(game);

        ChessGameDto result = controller.resign(uuid, whiteUser);

        assertThat(result.getStatus()).isEqualTo("FINISHED");
        verify(chessService).resign(uuid, WHITE_ID);
    }

    // -----------------------------------------------------------------------
    // offerDraw / acceptDraw / declineDraw
    // -----------------------------------------------------------------------

    @Test
    void offerDraw_delegatesToService() {
        UUID uuid = UUID.randomUUID();
        ChessGame game = pvpInProgressGame(WHITE_ID, BLACK_ID);
        game.setDrawOfferedBy(WHITE_ID);
        when(chessService.offerDraw(uuid, WHITE_ID)).thenReturn(game);

        ChessGameDto result = controller.offerDraw(uuid, whiteUser);

        assertThat(result.getDrawOfferedBy()).isEqualTo(WHITE_ID);
        verify(chessService).offerDraw(uuid, WHITE_ID);
    }

    @Test
    void acceptDraw_delegatesToService() {
        UUID uuid = UUID.randomUUID();
        ChessGame game = finishedGame(WHITE_ID, BLACK_ID);
        when(chessService.acceptDraw(uuid, BLACK_ID)).thenReturn(game);

        ChessGameDto result = controller.acceptDraw(uuid, blackUser);

        assertThat(result.getStatus()).isEqualTo("FINISHED");
        verify(chessService).acceptDraw(uuid, BLACK_ID);
    }

    @Test
    void declineDraw_delegatesToService() {
        UUID uuid = UUID.randomUUID();
        ChessGame game = pvpInProgressGame(WHITE_ID, BLACK_ID);
        when(chessService.declineDraw(uuid, BLACK_ID)).thenReturn(game);

        ChessGameDto result = controller.declineDraw(uuid, blackUser);

        assertThat(result.getStatus()).isEqualTo("IN_PROGRESS");
        verify(chessService).declineDraw(uuid, BLACK_ID);
    }

    // -----------------------------------------------------------------------
    // abandon
    // -----------------------------------------------------------------------

    @Test
    void abandon_delegatesToService_returns204() {
        UUID uuid = UUID.randomUUID();
        ChessGame game = pvpWaitingGame(WHITE_ID);
        when(chessService.abandon(uuid, WHITE_ID)).thenReturn(game);

        var response = controller.abandon(uuid, whiteUser);

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        verify(chessService).abandon(uuid, WHITE_ID);
    }

    // -----------------------------------------------------------------------
    // Helper factories
    // -----------------------------------------------------------------------

    private ChessGame pvpWaitingGame(Long whiteId) {
        var game = new ChessGame();
        game.setWhitePlayerId(whiteId);
        game.setGameType(GameType.PVP);
        game.setStatus(GameStatus.WAITING_FOR_OPPONENT);
        return game;
    }

    private ChessGame pvpInProgressGame(Long whiteId, Long blackId) {
        var game = new ChessGame();
        game.setWhitePlayerId(whiteId);
        game.setBlackPlayerId(blackId);
        game.setGameType(GameType.PVP);
        game.setStatus(GameStatus.IN_PROGRESS);
        return game;
    }

    private ChessGame aiGame(Long whiteId) {
        var game = new ChessGame();
        game.setWhitePlayerId(whiteId);
        game.setGameType(GameType.AI);
        game.setStatus(GameStatus.IN_PROGRESS);
        game.setAiDifficulty(10);
        return game;
    }

    private ChessGame finishedGame(Long whiteId, Long blackId) {
        var game = new ChessGame();
        game.setWhitePlayerId(whiteId);
        game.setBlackPlayerId(blackId);
        game.setGameType(GameType.PVP);
        game.setStatus(GameStatus.FINISHED);
        game.setResult(GameResult.WHITE_WINS);
        game.setResultReason(GameResultReason.CHECKMATE);
        return game;
    }
}
