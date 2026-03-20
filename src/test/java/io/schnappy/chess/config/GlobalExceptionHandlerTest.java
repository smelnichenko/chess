package io.schnappy.chess.config;

import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void handleNotFound_returns404WithMessage() {
        var response = handler.handleNotFound(new EntityNotFoundException("Game not found"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).containsEntry("error", "Game not found");
    }

    @Test
    void handleBadRequest_returns400WithMessage() {
        var response = handler.handleBadRequest(new IllegalArgumentException("Invalid move"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("error", "Invalid move");
    }

    @Test
    void handleConflict_returns409WithMessage() {
        var response = handler.handleConflict(new IllegalStateException("Game already finished"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).containsEntry("error", "Game already finished");
    }

    @Test
    void handleResponseStatus_returnsCorrectStatusAndReason() {
        var ex = new ResponseStatusException(HttpStatus.FORBIDDEN, "Insufficient permissions");

        var response = handler.handleResponseStatus(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).containsEntry("error", "Insufficient permissions");
    }

    @Test
    void handleResponseStatus_nullReason_returnsDefaultError() {
        var ex = new ResponseStatusException(HttpStatus.UNAUTHORIZED);

        var response = handler.handleResponseStatus(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).containsEntry("error", "Error");
    }

    @Test
    void handleGeneral_returns500WithGenericMessage() {
        var response = handler.handleGeneral(new RuntimeException("unexpected"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).containsEntry("error", "Internal server error");
    }
}
