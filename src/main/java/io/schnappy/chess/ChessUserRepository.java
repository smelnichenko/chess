package io.schnappy.chess;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ChessUserRepository extends JpaRepository<ChessUser, Long> {

    Optional<ChessUser> findByUuid(UUID uuid);
}
