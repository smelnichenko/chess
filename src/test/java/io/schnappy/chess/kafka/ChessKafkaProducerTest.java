package io.schnappy.chess.kafka;

import io.schnappy.chess.ChessGameDto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.concurrent.CompletableFuture;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChessKafkaProducerTest {

    @Mock
    private KafkaTemplate<String, ChessGameDto> kafkaTemplate;

    @InjectMocks
    private ChessKafkaProducer producer;

    @Test
    void publishGameEvent_sendsToCorrectTopic() {
        ChessGameDto dto = ChessGameDto.builder()
                .gameUuid("game-uuid-1")
                .fen("some-fen")
                .status("IN_PROGRESS")
                .gameType("PVP")
                .moveCount(1)
                .build();

        when(kafkaTemplate.send("chess.moves", "game-uuid-1", dto))
                .thenReturn(new CompletableFuture<>());

        producer.publishGameEvent(dto);

        verify(kafkaTemplate).send("chess.moves", "game-uuid-1", dto);
    }

    @Test
    void publishGameEvent_nullFuture_doesNotThrow() {
        ChessGameDto dto = ChessGameDto.builder()
                .gameUuid("game-uuid-2")
                .fen("some-fen")
                .status("IN_PROGRESS")
                .gameType("PVP")
                .moveCount(0)
                .build();

        when(kafkaTemplate.send("chess.moves", "game-uuid-2", dto))
                .thenReturn(null);

        // Should not throw NPE
        producer.publishGameEvent(dto);

        verify(kafkaTemplate).send("chess.moves", "game-uuid-2", dto);
    }

    @Test
    void publishGameEvent_failedFuture_logsError() {
        ChessGameDto dto = ChessGameDto.builder()
                .gameUuid("game-uuid-3")
                .fen("some-fen")
                .status("IN_PROGRESS")
                .gameType("AI")
                .moveCount(2)
                .build();

        CompletableFuture<org.springframework.kafka.support.SendResult<String, ChessGameDto>> future =
                new CompletableFuture<>();

        when(kafkaTemplate.send("chess.moves", "game-uuid-3", dto))
                .thenReturn(future);

        producer.publishGameEvent(dto);

        // Complete exceptionally — triggers the error callback
        future.completeExceptionally(new RuntimeException("Kafka unavailable"));

        verify(kafkaTemplate).send("chess.moves", "game-uuid-3", dto);
    }
}
