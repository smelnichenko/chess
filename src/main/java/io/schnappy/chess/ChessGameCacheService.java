package io.schnappy.chess;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChessGameCacheService {

    private static final String KEY_PREFIX = "chess:game:";
    private static final Duration TTL = Duration.ofHours(1);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final StringRedisTemplate redisTemplate;

    public void cache(ChessGameDto dto) {
        try {
            String json = OBJECT_MAPPER.writeValueAsString(dto);
            redisTemplate.opsForValue().set(KEY_PREFIX + dto.getGameUuid(), json, TTL);
        } catch (JacksonException e) {
            log.warn("Failed to cache chess game {}: {}", dto.getGameUuid(), e.getMessage());
        }
    }

    public Optional<ChessGameDto> get(UUID gameUuid) {
        String json = redisTemplate.opsForValue().get(KEY_PREFIX + gameUuid);
        if (json == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(OBJECT_MAPPER.readValue(json, ChessGameDto.class));
        } catch (JacksonException e) {
            log.warn("Failed to deserialize cached chess game {}: {}", gameUuid, e.getMessage());
            return Optional.empty();
        }
    }

    public void evict(UUID gameUuid) {
        redisTemplate.delete(KEY_PREFIX + gameUuid);
    }
}
