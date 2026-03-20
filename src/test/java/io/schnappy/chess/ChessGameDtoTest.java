package io.schnappy.chess;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ChessGameDtoTest {

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

        ChessGameDto dto = ChessGameDto.from(game);

        assertThat(dto.getGameUuid()).isEqualTo(game.getUuid().toString());
        assertThat(dto.getFen()).isEqualTo("custom-fen");
        assertThat(dto.getPgn()).isEqualTo("1. e4 e5");
        assertThat(dto.getStatus()).isEqualTo("FINISHED");
        assertThat(dto.getResult()).isEqualTo("WHITE_WINS");
        assertThat(dto.getResultReason()).isEqualTo("CHECKMATE");
        assertThat(dto.getGameType()).isEqualTo("PVP");
        assertThat(dto.getMoveCount()).isEqualTo(2);
        assertThat(dto.getWhitePlayerId()).isEqualTo(1L);
        assertThat(dto.getBlackPlayerId()).isEqualTo(2L);
        assertThat(dto.getDrawOfferedBy()).isEqualTo(1L);
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

        ChessGameDto dto = ChessGameDto.from(game);

        assertThat(dto.getResult()).isNull();
        assertThat(dto.getResultReason()).isNull();
        assertThat(dto.getBlackPlayerId()).isNull();
        assertThat(dto.getDrawOfferedBy()).isNull();
    }

    @Test
    void from_aiGame_includesDifficulty() {
        ChessGame game = new ChessGame();
        game.setWhitePlayerId(1L);
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
                .whitePlayerId(1L)
                .blackPlayerId(2L)
                .drawOfferedBy(1L)
                .aiDifficulty(5)
                .updatedAt(now)
                .build();

        assertThat(dto.getGameUuid()).isEqualTo("test-uuid");
        assertThat(dto.getLastMove()).isEqualTo("e2e4");
        assertThat(dto.getUpdatedAt()).isEqualTo(now);
    }
}
