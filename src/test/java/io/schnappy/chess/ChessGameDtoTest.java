package io.schnappy.chess;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ChessGameDtoTest {

    private static final UUID WHITE_UUID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID BLACK_UUID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test
    void from_mapsAllFieldsCorrectly() {
        ChessGame game = new ChessGame();
        game.setWhitePlayerUuid(WHITE_UUID);
        game.setBlackPlayerUuid(BLACK_UUID);
        game.setGameType(GameType.PVP);
        game.setStatus(GameStatus.FINISHED);
        game.setResult(GameResult.WHITE_WINS);
        game.setResultReason(GameResultReason.CHECKMATE);
        game.setFen("custom-fen");
        game.setPgn("1. e4 e5");
        game.setMoveCount(2);
        game.setDrawOfferedByUuid(WHITE_UUID);
        game.setAiDifficulty(10);

        ChessGameDto dto = ChessGameDto.from(game);

        assertThat(dto.getGameUuid()).isEqualTo(game.getUuid().toString());
        assertThat(dto.getFen()).isEqualTo("custom-fen");
        assertThat(dto.getPgn()).isEqualTo("1. e4 e5");
        assertThat(dto.getStatus()).isEqualTo("FINISHED");
        assertThat(dto.getResult()).isEqualTo("WHITE_WINS");
        assertThat(dto.getResultReason()).isEqualTo("CHECKMATE");
        assertThat(dto.getGameType()).isEqualTo("PVP");
        assertThat(dto.getMoveCount()).isEqualTo(2);
        assertThat(dto.getWhitePlayerUuid()).isEqualTo(WHITE_UUID.toString());
        assertThat(dto.getBlackPlayerUuid()).isEqualTo(BLACK_UUID.toString());
        assertThat(dto.getDrawOfferedByUuid()).isEqualTo(WHITE_UUID.toString());
        assertThat(dto.getAiDifficulty()).isEqualTo(10);
        assertThat(dto.getUpdatedAt()).isNotNull();
    }

    @Test
    void from_nullResultFields_mapsToNull() {
        ChessGame game = new ChessGame();
        game.setWhitePlayerUuid(WHITE_UUID);
        game.setGameType(GameType.AI);
        game.setStatus(GameStatus.IN_PROGRESS);
        // result and resultReason are null

        ChessGameDto dto = ChessGameDto.from(game);

        assertThat(dto.getResult()).isNull();
        assertThat(dto.getResultReason()).isNull();
        assertThat(dto.getBlackPlayerUuid()).isNull();
        assertThat(dto.getDrawOfferedByUuid()).isNull();
    }

    @Test
    void from_aiGame_includesDifficulty() {
        ChessGame game = new ChessGame();
        game.setWhitePlayerUuid(WHITE_UUID);
        game.setGameType(GameType.AI);
        game.setStatus(GameStatus.IN_PROGRESS);
        game.setAiDifficulty(15);

        ChessGameDto dto = ChessGameDto.from(game);

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
