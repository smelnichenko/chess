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

    private final ChessGameRepository gameRepository;
    private final ChessGameCacheService cacheService;
    private final ChessKafkaProducer kafkaProducer;

    @Transactional
    public ChessGame createAiGame(Long userId, int difficulty) {
        if (difficulty < 0 || difficulty > 20) {
            throw new IllegalArgumentException("Difficulty must be between 0 and 20");
        }
        var game = new ChessGame();
        game.setWhitePlayerId(userId);
        game.setGameType(GameType.AI);
        game.setStatus(GameStatus.IN_PROGRESS);
        game.setAiDifficulty(difficulty);
        game = gameRepository.save(game);
        cacheService.cache(ChessGameDto.from(game));
        return game;
    }

    @Transactional
    public ChessGame createPvpGame(Long userId) {
        var game = new ChessGame();
        game.setWhitePlayerId(userId);
        game.setGameType(GameType.PVP);
        game.setStatus(GameStatus.WAITING_FOR_OPPONENT);
        game = gameRepository.save(game);
        cacheService.cache(ChessGameDto.from(game));
        return game;
    }

    @Transactional
    public ChessGame joinGame(UUID gameUuid, Long userId) {
        var game = findByUuid(gameUuid);
        if (userId.equals(game.getWhitePlayerId())) {
            throw new IllegalArgumentException("Cannot join your own game");
        }
        if (game.getBlackPlayerId() != null) {
            throw new IllegalStateException("Game already has an opponent");
        }
        game.setBlackPlayerId(userId);
        game.transition(GameEvent.OPPONENT_JOINED);
        game = gameRepository.save(game);
        var dto = ChessGameDto.from(game);
        cacheService.cache(dto);
        kafkaProducer.publishGameEvent(dto);
        return game;
    }

    @Transactional
    public ChessGame makeMove(UUID gameUuid, String moveStr, Long userId) {
        var game = findByUuid(gameUuid);
        validatePlayerTurn(game, userId);

        Board board = new Board();
        board.loadFromFen(game.getFen());

        Move move = parseAndValidateMove(board, moveStr);
        board.doMove(move);

        game.setFen(board.getFen());
        game.setMoveCount(game.getMoveCount() + 1);
        game.setLastMoveAt(Instant.now());
        game.setPgn(buildPgn(game.getPgn(), moveStr, game.getMoveCount()));
        game.setDrawOfferedBy(null);

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
        var dto = ChessGameDto.builder()
            .gameUuid(game.getUuid().toString())
            .fen(game.getFen())
            .pgn(game.getPgn())
            .status(game.getStatus().name())
            .result(game.getResult() != null ? game.getResult().name() : null)
            .resultReason(game.getResultReason() != null ? game.getResultReason().name() : null)
            .gameType(game.getGameType().name())
            .moveCount(game.getMoveCount())
            .lastMove(moveStr)
            .whitePlayerId(game.getWhitePlayerId())
            .blackPlayerId(game.getBlackPlayerId())
            .drawOfferedBy(game.getDrawOfferedBy())
            .aiDifficulty(game.getAiDifficulty())
            .updatedAt(game.getUpdatedAt())
            .build();
        cacheService.cache(dto);

        if (game.getGameType() == GameType.PVP) {
            kafkaProducer.publishGameEvent(dto);
        }

        return game;
    }

    @Transactional
    public ChessGame makeAiMove(UUID gameUuid, String moveStr, Long userId) {
        var game = findByUuid(gameUuid);
        if (game.getGameType() != GameType.AI) {
            throw new IllegalArgumentException("Not an AI game");
        }
        if (!userId.equals(game.getWhitePlayerId())) {
            throw new IllegalArgumentException("Not a player in this game");
        }
        if (game.isTerminal()) {
            throw new IllegalStateException("Game is already finished");
        }
        if (game.getStatus() != GameStatus.IN_PROGRESS) {
            throw new IllegalStateException("Game is not in progress");
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
        var dto = ChessGameDto.from(game);
        cacheService.cache(dto);
        return game;
    }

    @Transactional
    public ChessGame resign(UUID gameUuid, Long userId) {
        var game = findByUuid(gameUuid);
        if (!game.isPlayerInGame(userId)) {
            throw new IllegalArgumentException("Not a player in this game");
        }
        game.transition(GameEvent.RESIGN);
        game.setResult(userId.equals(game.getWhitePlayerId())
            ? GameResult.BLACK_WINS : GameResult.WHITE_WINS);
        game.setResultReason(GameResultReason.RESIGNATION);
        game = gameRepository.save(game);
        var dto = ChessGameDto.from(game);
        cacheService.cache(dto);
        if (game.getGameType() == GameType.PVP) {
            kafkaProducer.publishGameEvent(dto);
        }
        return game;
    }

    @Transactional
    public ChessGame offerDraw(UUID gameUuid, Long userId) {
        var game = findByUuid(gameUuid);
        validatePlayerTurn(game, userId);
        if (game.getGameType() != GameType.PVP) {
            throw new IllegalArgumentException("Draw offers only in PvP games");
        }
        game.setDrawOfferedBy(userId);
        game.setUpdatedAt(Instant.now());
        game = gameRepository.save(game);
        var dto = ChessGameDto.from(game);
        cacheService.cache(dto);
        kafkaProducer.publishGameEvent(dto);
        return game;
    }

    @Transactional
    public ChessGame acceptDraw(UUID gameUuid, Long userId) {
        var game = findByUuid(gameUuid);
        if (!game.isPlayerInGame(userId)) {
            throw new IllegalArgumentException("Not a player in this game");
        }
        if (game.getDrawOfferedBy() == null || game.getDrawOfferedBy().equals(userId)) {
            throw new IllegalArgumentException("No pending draw offer to accept");
        }
        game.transition(GameEvent.DRAW_AGREED);
        game.setResult(GameResult.DRAW);
        game.setResultReason(GameResultReason.AGREEMENT);
        game = gameRepository.save(game);
        var dto = ChessGameDto.from(game);
        cacheService.cache(dto);
        kafkaProducer.publishGameEvent(dto);
        return game;
    }

    @Transactional
    public ChessGame declineDraw(UUID gameUuid, Long userId) {
        var game = findByUuid(gameUuid);
        if (!game.isPlayerInGame(userId)) {
            throw new IllegalArgumentException("Not a player in this game");
        }
        if (game.getDrawOfferedBy() == null || game.getDrawOfferedBy().equals(userId)) {
            throw new IllegalArgumentException("No pending draw offer to decline");
        }
        game.setDrawOfferedBy(null);
        game.setUpdatedAt(Instant.now());
        game = gameRepository.save(game);
        var dto = ChessGameDto.from(game);
        cacheService.cache(dto);
        kafkaProducer.publishGameEvent(dto);
        return game;
    }

    @Transactional
    public ChessGame abandon(UUID gameUuid, Long userId) {
        var game = findByUuid(gameUuid);
        if (!userId.equals(game.getWhitePlayerId())) {
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
                var dto = ChessGameDto.from(game);
                cacheService.cache(dto);
                return dto;
            });
    }

    @Transactional(readOnly = true)
    public List<ChessGame> getActiveGames(Long userId) {
        return gameRepository.findActiveByUserId(userId);
    }

    @Transactional(readOnly = true)
    public List<ChessGame> getOpenGames() {
        return gameRepository.findByStatusOrderByCreatedAtDesc(GameStatus.WAITING_FOR_OPPONENT);
    }

    @Transactional(readOnly = true)
    public Page<ChessGame> getHistory(Long userId, Pageable pageable) {
        return gameRepository.findHistoryByUserId(userId, pageable);
    }

    private ChessGame findByUuid(UUID uuid) {
        return gameRepository.findByUuid(uuid)
            .orElseThrow(() -> new EntityNotFoundException("Game not found: " + uuid));
    }

    private void validatePlayerTurn(ChessGame game, Long userId) {
        if (!game.isPlayerInGame(userId)) {
            throw new IllegalArgumentException("Not a player in this game");
        }
        if (game.isTerminal()) {
            throw new IllegalStateException("Game is already finished");
        }
        if (game.getStatus() != GameStatus.IN_PROGRESS) {
            throw new IllegalStateException("Game is not in progress");
        }
        if (!game.isPlayersTurn(userId)) {
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

    private String buildPgn(String existingPgn, String move, int moveCount) {
        var sb = new StringBuilder();
        if (existingPgn != null && !existingPgn.isEmpty()) {
            sb.append(existingPgn);
        }
        if (moveCount % 2 == 1) {
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append((moveCount + 1) / 2).append('.');
        }
        sb.append(' ').append(move);
        return sb.toString().trim();
    }
}
