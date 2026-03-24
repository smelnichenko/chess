package io.schnappy.chess;

import lombok.Builder;
import lombok.Value;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.annotation.JsonPOJOBuilder;

import java.time.Instant;
import java.util.UUID;

@Value
@Builder(toBuilder = true)
@JsonDeserialize(builder = ChessGameDto.ChessGameDtoBuilder.class)
public class ChessGameDto {

    @JsonPOJOBuilder(withPrefix = "")
    public static class ChessGameDtoBuilder {}

    String gameUuid;
    String fen;
    String pgn;
    String status;
    String result;
    String resultReason;
    String gameType;
    int moveCount;
    String lastMove;
    String whitePlayerUuid;
    String blackPlayerUuid;
    String drawOfferedByUuid;
    Integer aiDifficulty;
    Instant updatedAt;

    public static ChessGameDto from(ChessGame game) {
        return ChessGameDto.builder()
            .gameUuid(game.getUuid().toString())
            .fen(game.getFen())
            .pgn(game.getPgn())
            .status(game.getStatus().name())
            .result(game.getResult() != null ? game.getResult().name() : null)
            .resultReason(game.getResultReason() != null ? game.getResultReason().name() : null)
            .gameType(game.getGameType().name())
            .moveCount(game.getMoveCount())
            .whitePlayerUuid(uuidToString(game.getWhitePlayerUuid()))
            .blackPlayerUuid(uuidToString(game.getBlackPlayerUuid()))
            .drawOfferedByUuid(uuidToString(game.getDrawOfferedByUuid()))
            .aiDifficulty(game.getAiDifficulty())
            .updatedAt(game.getUpdatedAt())
            .build();
    }

    private static String uuidToString(UUID uuid) {
        return uuid != null ? uuid.toString() : null;
    }
}
