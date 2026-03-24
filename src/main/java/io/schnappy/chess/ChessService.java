package io.schnappy.chess;

import com.github.bhlangonijr.chesslib.Board;
import com.github.bhlangonijr.chesslib.move.Move;
import io.schnappy.chess.kafka.ChessKafkaProducer;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChessService {

    private static final String NOT_A_PLAYER = "Not a player in this game";
    private static final String GAME_ALREADY_FINISHED = "Game is already finished";
    private static final String GAME_NOT_IN_PROGRESS = "Game is not in progress";

    private final ChessGameRepository gameRepository;
    private final ChessGameCacheService cacheService;
    private final ChessKafkaProducer kafkaProducer;

    @Transactional
    public ChessGame createAiGame(UUID playerUuid, int difficulty) {
        if (difficulty < 0 || difficulty > 20) {
            throw new IllegalArgumentException("Difficulty must be between 0 and 20");
        }
        var game = new ChessGame();
        game.setWhitePlayerUuid(playerUuid);
        game.setGameType(GameType.AI);
        game.setStatus(GameStatus.IN_PROGRESS);
        game.setAiDifficulty(difficulty);
        game = gameRepository.save(game);
        cacheService.cache(toDto(game));
        return game;
    }

    @Transactional
    public ChessGame createPvpGame(UUID playerUuid) {
        var game = new ChessGame();
        game.setWhitePlayerUuid(playerUuid);
        game.setGameType(GameType.PVP);
        game.setStatus(GameStatus.WAITING_FOR_OPPONENT);
        game = gameRepository.save(game);
        cacheService.cache(toDto(game));
        return game;
    }

    @Transactional
    public ChessGame joinGame(UUID gameUuid, UUID playerUuid) {
        var game = findByUuid(gameUuid);
        if (playerUuid.equals(game.getWhitePlayerUuid())) {
            throw new IllegalArgumentException("Cannot join own game");
        }
        if (game.getBlackPlayerUuid() != null) {
            throw new IllegalStateException("Game already has an opponent");
        }
        game.setBlackPlayerUuid(playerUuid);
        game.transition(GameEvent.OPPONENT_JOINED);
        game = gameRepository.save(game);
        var dto = toDto(game);
        cacheService.cache(dto);
        kafkaProducer.publishGameEvent(dto);
        return game;
    }

    @Transactional
    public ChessGame makeMove(UUID gameUuid, String moveStr, UUID playerUuid) {
        var game = findByUuid(gameUuid);
        validatePlayerTurn(game, playerUuid);

        Board board = new Board();
        board.loadFromFen(game.getFen());

        Move move = parseAndValidateMove(board, moveStr);
        board.doMove(move);

        game.setFen(board.getFen());
        game.setMoveCount(game.getMoveCount() + 1);
        game.setLastMoveAt(Instant.now());
        game.setPgn(buildPgn(game.getPgn(), moveStr, game.getMoveCount()));
        game.setDrawOfferedByUuid(null);

        if (board.isMated()) {
            game.transition(GameEvent.CHECKMATE);
            game.setResult(board.getSideToMove().equals(com.github.bhlangonijr.chesslib.Side.WHITE)
                ? GameResult.BLACK_WINS : GameResult.WHITE_WINS);
            game.setResultReason(GameResultReason.CHECKMATE);
        } else if (board.isStaleMate()) {
            game.transition(GameEvent.STALEMATE);
            game.setResult(GameResult.DRAW);
            game.setResultReason(GameResultReason.STALEMATE);
        } else if (board.isInsufficientMaterial()) {
            game.transition(GameEvent.STALEMATE);
            game.setResult(GameResult.DRAW);
            game.setResultReason(GameResultReason.INSUFFICIENT_MATERIAL);
        } else {
            game.transition(GameEvent.MOVE_MADE);
        }

        game = gameRepository.save(game);
        var dto = toDto(game).toBuilder()
            .lastMove(moveStr)
            .build();
        cacheService.cache(dto);

        if (game.getGameType() == GameType.PVP) {
            kafkaProducer.publishGameEvent(dto);
        }

        return game;
    }

    @Transactional
    public ChessGame makeAiMove(UUID gameUuid, String moveStr, UUID playerUuid) {
        var game = findByUuid(gameUuid);
        if (game.getGameType() != GameType.AI) {
            throw new IllegalArgumentException("Not an AI game");
        }
        if (!playerUuid.equals(game.getWhitePlayerUuid())) {
            throw new IllegalArgumentException(NOT_A_PLAYER);
        }
        if (game.isTerminal()) {
            throw new IllegalStateException(GAME_ALREADY_FINISHED);
        }
        if (game.getStatus() != GameStatus.IN_PROGRESS) {
            throw new IllegalStateException(GAME_NOT_IN_PROGRESS);
        }
        if (game.isWhiteTurn()) {
            throw new IllegalStateException("Not the AI's turn");
        }

        Board board = new Board();
        board.loadFromFen(game.getFen());

        Move move = parseAndValidateMove(board, moveStr);
        board.doMove(move);

        game.setFen(board.getFen());
        game.setMoveCount(game.getMoveCount() + 1);
        game.setLastMoveAt(Instant.now());
        game.setPgn(buildPgn(game.getPgn(), moveStr, game.getMoveCount()));

        if (board.isMated()) {
            game.transition(GameEvent.CHECKMATE);
            game.setResult(GameResult.BLACK_WINS);
            game.setResultReason(GameResultReason.CHECKMATE);
        } else if (board.isStaleMate()) {
            game.transition(GameEvent.STALEMATE);
            game.setResult(GameResult.DRAW);
            game.setResultReason(GameResultReason.STALEMATE);
        } else if (board.isInsufficientMaterial()) {
            game.transition(GameEvent.STALEMATE);
            game.setResult(GameResult.DRAW);
            game.setResultReason(GameResultReason.INSUFFICIENT_MATERIAL);
        } else {
            game.transition(GameEvent.MOVE_MADE);
        }

        game = gameRepository.save(game);
        var dto = toDto(game);
        cacheService.cache(dto);
        return game;
    }

    @Transactional
    public ChessGame resign(UUID gameUuid, UUID playerUuid) {
        var game = findByUuid(gameUuid);
        if (!game.isPlayerInGame(playerUuid)) {
            throw new IllegalArgumentException(NOT_A_PLAYER);
        }
        game.transition(GameEvent.RESIGN);
        game.setResult(playerUuid.equals(game.getWhitePlayerUuid())
            ? GameResult.BLACK_WINS : GameResult.WHITE_WINS);
        game.setResultReason(GameResultReason.RESIGNATION);
        game = gameRepository.save(game);
        var dto = toDto(game);
        cacheService.cache(dto);
        if (game.getGameType() == GameType.PVP) {
            kafkaProducer.publishGameEvent(dto);
        }
        return game;
    }

    @Transactional
    public ChessGame offerDraw(UUID gameUuid, UUID playerUuid) {
        var game = findByUuid(gameUuid);
        validatePlayerTurn(game, playerUuid);
        if (game.getGameType() != GameType.PVP) {
            throw new IllegalArgumentException("Draw offers only in PvP games");
        }
        game.setDrawOfferedByUuid(playerUuid);
        game.setUpdatedAt(Instant.now());
        game = gameRepository.save(game);
        var dto = toDto(game);
        cacheService.cache(dto);
        kafkaProducer.publishGameEvent(dto);
        return game;
    }

    @Transactional
    public ChessGame acceptDraw(UUID gameUuid, UUID playerUuid) {
        var game = findByUuid(gameUuid);
        if (!game.isPlayerInGame(playerUuid)) {
            throw new IllegalArgumentException(NOT_A_PLAYER);
        }
        if (game.getDrawOfferedByUuid() == null || game.getDrawOfferedByUuid().equals(playerUuid)) {
            throw new IllegalArgumentException("No pending draw offer to accept");
        }
        game.transition(GameEvent.DRAW_AGREED);
        game.setResult(GameResult.DRAW);
        game.setResultReason(GameResultReason.AGREEMENT);
        game = gameRepository.save(game);
        var dto = toDto(game);
        cacheService.cache(dto);
        kafkaProducer.publishGameEvent(dto);
        return game;
    }

    @Transactional
    public ChessGame declineDraw(UUID gameUuid, UUID playerUuid) {
        var game = findByUuid(gameUuid);
        if (!game.isPlayerInGame(playerUuid)) {
            throw new IllegalArgumentException(NOT_A_PLAYER);
        }
        if (game.getDrawOfferedByUuid() == null || game.getDrawOfferedByUuid().equals(playerUuid)) {
            throw new IllegalArgumentException("No pending draw offer to decline");
        }
        game.setDrawOfferedByUuid(null);
        game.setUpdatedAt(Instant.now());
        game = gameRepository.save(game);
        var dto = toDto(game);
        cacheService.cache(dto);
        kafkaProducer.publishGameEvent(dto);
        return game;
    }

    @Transactional
    public ChessGame abandon(UUID gameUuid, UUID playerUuid) {
        var game = findByUuid(gameUuid);
        if (!playerUuid.equals(game.getWhitePlayerUuid())) {
            throw new IllegalArgumentException("Only the creator can abandon a waiting game");
        }
        game.transition(GameEvent.ABANDON);
        game = gameRepository.save(game);
        cacheService.evict(game.getUuid());
        return game;
    }

    @Transactional(readOnly = true)
    public ChessGameDto getGame(UUID gameUuid) {
        return cacheService.get(gameUuid)
            .orElseGet(() -> {
                var game = findByUuid(gameUuid);
                var dto = toDto(game);
                cacheService.cache(dto);
                return dto;
            });
    }

    @Transactional(readOnly = true)
    public List<ChessGame> getActiveGames(UUID playerUuid) {
        return gameRepository.findActiveByPlayerUuid(playerUuid);
    }

    @Transactional(readOnly = true)
    public List<ChessGame> getOpenGames() {
        return gameRepository.findByStatusOrderByCreatedAtDesc(GameStatus.WAITING_FOR_OPPONENT);
    }

    @Transactional(readOnly = true)
    public Page<ChessGame> getHistory(UUID playerUuid, Pageable pageable) {
        return gameRepository.findHistoryByPlayerUuid(playerUuid, pageable);
    }

    private ChessGame findByUuid(UUID uuid) {
        return gameRepository.findByUuid(uuid)
            .orElseThrow(() -> new EntityNotFoundException("Game not found: " + uuid));
    }

    private void validatePlayerTurn(ChessGame game, UUID playerUuid) {
        if (!game.isPlayerInGame(playerUuid)) {
            throw new IllegalArgumentException(NOT_A_PLAYER);
        }
        if (game.isTerminal()) {
            throw new IllegalStateException(GAME_ALREADY_FINISHED);
        }
        if (game.getStatus() != GameStatus.IN_PROGRESS) {
            throw new IllegalStateException(GAME_NOT_IN_PROGRESS);
        }
        if (!game.isPlayersTurn(playerUuid)) {
            throw new IllegalStateException("Not your turn");
        }
    }

    private Move parseAndValidateMove(Board board, String moveStr) {
        try {
            Move move = new Move(moveStr, board.getSideToMove());
            var legalMoves = board.legalMoves();
            if (!legalMoves.contains(move)) {
                throw new IllegalArgumentException("Illegal move: " + moveStr);
            }
            return move;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception _) {
            throw new IllegalArgumentException("Invalid move format: " + moveStr);
        }
    }

    ChessGameDto toDto(ChessGame game) {
        return ChessGameDto.from(game);
    }

    private String buildPgn(String existingPgn, String move, int moveCount) {
        var sb = new StringBuilder();
        if (existingPgn != null && !existingPgn.isEmpty()) {
            sb.append(existingPgn);
        }
        if (moveCount % 2 != 0) {
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append((moveCount + 1) / 2).append('.');
        }
        sb.append(' ').append(move);
        return sb.toString().trim();
    }
}
