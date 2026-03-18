package io.schnappy.chess;

public record CreateGameRequest(
    GameType type,
    Integer difficulty
) {}
