package io.schnappy.chess.kafka;

import io.schnappy.chess.ChessGameDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ChessKafkaProducer {

    private static final String TOPIC = "chess.moves";
    private final KafkaTemplate<String, ChessGameDto> kafkaTemplate;

    public void publishGameEvent(ChessGameDto dto) {
        var future = kafkaTemplate.send(TOPIC, dto.getGameUuid(), dto);
        if (future != null) {
            future.whenComplete((result, ex) -> {
                if (ex != null) {
                    log.error("Failed to publish chess event for game {}: {}",
                        dto.getGameUuid(), ex.getMessage());
                }
            });
        }
    }
}
