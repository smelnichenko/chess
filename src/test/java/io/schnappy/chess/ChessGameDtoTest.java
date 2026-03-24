package io.schnappy.chess;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class ChessGameDtoTest {

    private static final Map<Long, String> UUID_MAP = Map.of(
            1L, "uuid-white",
            2L, "uuid-black"
    );

    private static final Function<Long, String> UUID_RESOLVER = UUID_MAP::get;

    @Test
    void from_mapsAllFieldsCorrectly() {
        ChessGame game = new ChessGame();
        game.setWhitePlayerId(1L);
        game.setBlackPlayerId(2L);
        game.setGameType(GameType.PVP);
        game.setStatus(GameStatus.FINISHED);
        game.setResult(GameResult.WHITE_WINS);
        game.setResultReason(GameResultReason.CHECKMATE);
        game.setFen("custom-fen");
        game.setPgn("1. e4 e5");
        game.setMoveCount(2);
        game.setDrawOfferedBy(1L);
        game.setAiDifficulty(10);

        ChessGameDto dto = ChessGameDto.from(game, UUID_RESOLVER);

        assertThat(dto.getGameUuid()).isEqualTo(game.getUuid().toString());
        assertThat(dto.getFen()).isEqualTo("custom-fen");
        assertThat(dto.getPgn()).isEqualTo("1. e4 e5");
        assertThat(dto.getStatus()).isEqualTo("FINISHED");
        assertThat(dto.getResult()).isEqualTo("WHITE_WINS");
        assertThat(dto.getResultReason()).isEqualTo("CHECKMATE");
        assertThat(dto.getGameType()).isEqualTo("PVP");
        assertThat(dto.getMoveCount()).isEqualTo(2);
        assertThat(dto.getWhitePlayerUuid()).isEqualTo("uuid-white");
        assertThat(dto.getBlackPlayerUuid()).isEqualTo("uuid-black");
        assertThat(dto.getDrawOfferedByUuid()).isEqualTo("uuid-white");
        assertThat(dto.getAiDifficulty()).isEqualTo(10);
        assertThat(dto.getUpdatedAt()).isNotNull();
    }

    @Test
    void from_nullResultFields_mapsToNull() {
        ChessGame game = new ChessGame();
        game.setWhitePlayerId(1L);
        game.setGameType(GameType.AI);
        game.setStatus(GameStatus.IN_PROGRESS);
        // result and resultReason are null

        ChessGameDto dto = ChessGameDto.from(game, UUID_RESOLVER);

        assertThat(dto.getResult()).isNull();
        assertThat(dto.getResultReason()).isNull();
        assertThat(dto.getBlackPlayerUuid()).isNull();
        assertThat(dto.getDrawOfferedByUuid()).isNull();
    }

    @Test
    void from_aiGame_includesDifficulty() {
        ChessGame game = new ChessGame();
        game.setWhitePlayerId(1L);
        game.setGameType(GameType.AI);
        game.setStatus(GameStatus.IN_PROGRESS);
        game.setAiDifficulty(15);

        ChessGameDto dto = ChessGameDto.from(game, UUID_RESOLVER);

        assertThat(dto.getGameType()).isEqualTo("AI");
        assertThat(dto.getAiDifficulty()).isEqualTo(15);
    }

    @Test
    void builder_setsAllFields() {
        Instant now = Instant.now();
        ChessGameDto dto = ChessGameDto.builder()
                .gameUuid("test-uuid")
                .fen("test-fen")
                .pgn("1. e4")
                .status("IN_PROGRESS")
                .result("WHITE_WINS")
                .resultReason("CHECKMATE")
                .gameType("PVP")
                .moveCount(3)
                .lastMove("e2e4")
                .whitePlayerUuid("uuid-white")
                .blackPlayerUuid("uuid-black")
                .drawOfferedByUuid("uuid-white")
                .aiDifficulty(5)
                .updatedAt(now)
                .build();

        assertThat(dto.getGameUuid()).isEqualTo("test-uuid");
        assertThat(dto.getLastMove()).isEqualTo("e2e4");
        assertThat(dto.getUpdatedAt()).isEqualTo(now);
    }
}
