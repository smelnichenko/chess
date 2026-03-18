package io.schnappy.chess.kafka;

import io.schnappy.chess.ChessGameDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ChessEventConsumer {

    private final SimpMessagingTemplate messagingTemplate;

    @KafkaListener(topics = "chess.moves", groupId = "chess-delivery",
        properties = "spring.json.trusted.packages=io.schnappy.chess")
    public void deliverGameEvent(ChessGameDto dto) {
        messagingTemplate.convertAndSend(
            "/topic/chess." + dto.getGameUuid(),
            dto
        );
    }
}
