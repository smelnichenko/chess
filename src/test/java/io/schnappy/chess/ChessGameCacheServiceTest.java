package io.schnappy.chess;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChessGameCacheServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @InjectMocks
    private ChessGameCacheService cacheService;

    private static final UUID GAME_UUID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    // -----------------------------------------------------------------------
    // cache
    // -----------------------------------------------------------------------

    @Test
    void cache_validDto_writesToRedis() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        ChessGameDto dto = ChessGameDto.builder()
                .gameUuid(GAME_UUID.toString())
                .fen("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1")
                .status("IN_PROGRESS")
                .gameType("PVP")
                .moveCount(0)
                .build();

        cacheService.cache(dto);

        verify(valueOperations).set(eq("chess:game:" + GAME_UUID), anyString(), eq(Duration.ofHours(1)));
    }

    // -----------------------------------------------------------------------
    // get
    // -----------------------------------------------------------------------

    @Test
    void get_cacheHit_returnsDto() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        String json = """
                {"gameUuid":"00000000-0000-0000-0000-000000000001","fen":"startfen","status":"IN_PROGRESS","gameType":"PVP","moveCount":0}""";
        when(valueOperations.get("chess:game:" + GAME_UUID)).thenReturn(json);

        Optional<ChessGameDto> result = cacheService.get(GAME_UUID);

        assertThat(result).isPresent();
        assertThat(result.get().getGameUuid()).isEqualTo(GAME_UUID.toString());
        assertThat(result.get().getStatus()).isEqualTo("IN_PROGRESS");
    }

    @Test
    void get_cacheMiss_returnsEmpty() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("chess:game:" + GAME_UUID)).thenReturn(null);

        Optional<ChessGameDto> result = cacheService.get(GAME_UUID);

        assertThat(result).isEmpty();
    }

    @Test
    void get_invalidJson_returnsEmpty() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("chess:game:" + GAME_UUID)).thenReturn("not-valid-json{{{");

        Optional<ChessGameDto> result = cacheService.get(GAME_UUID);

        assertThat(result).isEmpty();
    }

    // -----------------------------------------------------------------------
    // evict
    // -----------------------------------------------------------------------

    @Test
    void evict_deletesKey() {
        cacheService.evict(GAME_UUID);

        verify(redisTemplate).delete("chess:game:" + GAME_UUID);
    }
}
