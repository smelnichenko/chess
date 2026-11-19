package io.schnappy.chess.kafka;

/**
 * A user event no retry can store - no type, no uuid, a uuid that is not one, a USER_CREATED with no email. Never
 * retried: the record goes to user.events.DLT at once, kept for a look (KafkaErrorHandlerConfig).
 */
public class InvalidUserEventException extends RuntimeException {
    public InvalidUserEventException(String message) {
        super(message);
    }

    public InvalidUserEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
