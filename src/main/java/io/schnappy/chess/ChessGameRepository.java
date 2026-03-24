package io.schnappy.chess;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ChessGameRepository extends JpaRepository<ChessGame, Long> {

    Optional<ChessGame> findByUuid(UUID uuid);

    @Query("SELECT g FROM ChessGame g WHERE (g.whitePlayerUuid = :playerUuid OR g.blackPlayerUuid = :playerUuid) " +
           "AND g.status IN (io.schnappy.chess.GameStatus.IN_PROGRESS, io.schnappy.chess.GameStatus.WAITING_FOR_OPPONENT)")
    List<ChessGame> findActiveByPlayerUuid(@Param("playerUuid") UUID playerUuid);

    List<ChessGame> findByStatusOrderByCreatedAtDesc(GameStatus status);

    @Query("SELECT g FROM ChessGame g WHERE (g.whitePlayerUuid = :playerUuid OR g.blackPlayerUuid = :playerUuid) " +
           "AND g.status IN (io.schnappy.chess.GameStatus.FINISHED, io.schnappy.chess.GameStatus.ABANDONED) " +
           "ORDER BY g.updatedAt DESC")
    Page<ChessGame> findHistoryByPlayerUuid(@Param("playerUuid") UUID playerUuid, Pageable pageable);
}
