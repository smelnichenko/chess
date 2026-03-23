package io.schnappy.chess.config;

import io.schnappy.chess.ChessUser;
import io.schnappy.chess.ChessUserRepository;
import io.schnappy.chess.security.UserIdResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.UUID;

@Configuration
public class UserIdResolverConfig {

    @Bean
    public UserIdResolver userIdResolver(ChessUserRepository chessUserRepository) {
        return uuidStr -> {
            try {
                UUID uuid = UUID.fromString(uuidStr);
                return chessUserRepository.findByUuid(uuid).map(ChessUser::getId).orElse(null);
            } catch (IllegalArgumentException _) {
                return null;
            }
        };
    }
}
