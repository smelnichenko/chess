package io.schnappy.chess.kafka;

import io.schnappy.chess.ChessGameDto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ChessEventConsumerTest {

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @InjectMocks
    private ChessEventConsumer consumer;

    @Test
    void deliverGameEvent_sendsToCorrectTopic() {
        ChessGameDto dto = ChessGameDto.builder()
                .gameUuid("abc-123")
                .fen("some-fen")
                .status("IN_PROGRESS")
                .gameType("PVP")
                .moveCount(5)
                .build();

        consumer.deliverGameEvent(dto);

        verify(messagingTemplate).convertAndSend("/topic/chess.abc-123", dto);
    }
}
