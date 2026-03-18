package io.schnappy.chess.config;

import io.schnappy.chess.ChessGameRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;

import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
@RequiredArgsConstructor
public class SubscriptionGuard {

    private static final Pattern CHESS_TOPIC = Pattern.compile("^/topic/chess\\.([0-9a-f-]+)$");

    private final ChessGameRepository chessGameRepository;

    @EventListener
    public void onSubscribe(SessionSubscribeEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        String destination = accessor.getDestination();
        if (destination == null) {
            return;
        }

        Map<String, Object> attrs = accessor.getSessionAttributes();
        Long userId = attrs != null ? (Long) attrs.get("userId") : null;

        Matcher chessMatcher = CHESS_TOPIC.matcher(destination);
        if (chessMatcher.matches()) {
            UUID gameUuid = UUID.fromString(chessMatcher.group(1));
            var game = chessGameRepository.findByUuid(gameUuid);
            if (userId == null || game.isEmpty() || !game.get().isPlayerInGame(userId)) {
                log.warn("Rejected subscription to chess game {} by user {}", gameUuid, userId);
                throw new MessageDeliveryException("Not a player in game " + gameUuid);
            }
        }
    }
}
