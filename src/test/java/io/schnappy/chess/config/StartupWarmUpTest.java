package io.schnappy.chess.config;

import com.nimbusds.jose.KeySourceException;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import io.schnappy.chess.ChessService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.domain.PageRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * The warm-up fetches Keycloak's keys and runs the game queries for no one; neither a Keycloak nor a database that
 * does not answer stops the start.
 */
class StartupWarmUpTest {

    @SuppressWarnings("unchecked")
    private final JWKSource<SecurityContext> keys = mock(JWKSource.class);
    private final ChessService chessService = mock(ChessService.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<JWKSource<SecurityContext>> keySource = mock(ObjectProvider.class);
    private final StartupWarmUp warmUp = new StartupWarmUp(keySource, chessService);

    @BeforeEach
    void aKeySource() {
        when(keySource.getIfAvailable()).thenReturn(keys);
    }

    @Test
    void fetchesTheKeysAndRunsTheGameQueriesForNoOne() throws KeySourceException {
        when(keys.get(any(JWKSelector.class), isNull())).thenReturn(List.of());

        warmUp.run(new DefaultApplicationArguments());

        verify(keys).get(any(JWKSelector.class), isNull());
        verify(chessService).getActiveGames(StartupWarmUp.NO_PLAYER);
        verify(chessService).getOpenGames();
        verify(chessService).getHistory(StartupWarmUp.NO_PLAYER, PageRequest.of(0, 1));
        verifyNoMoreInteractions(chessService);
    }

    @Test
    void aKeycloakThatDoesNotAnswerDoesNotStopTheStart() throws KeySourceException {
        when(keys.get(any(JWKSelector.class), isNull())).thenThrow(new KeySourceException("connect timed out"));
        var args = new DefaultApplicationArguments();

        assertThatCode(() -> warmUp.run(args)).doesNotThrowAnyException();
        verify(chessService).getActiveGames(StartupWarmUp.NO_PLAYER);
    }

    @Test
    void aDatabaseThatDoesNotAnswerDoesNotStopTheStart() throws KeySourceException {
        when(keys.get(any(JWKSelector.class), isNull())).thenReturn(List.of());
        when(chessService.getActiveGames(StartupWarmUp.NO_PLAYER))
                .thenThrow(new DataAccessResourceFailureException("connection refused"));
        var args = new DefaultApplicationArguments();

        assertThatCode(() -> warmUp.run(args)).doesNotThrowAnyException();
    }

    @Test
    void withoutAKeySourceTheQueriesStillRun() {
        when(keySource.getIfAvailable()).thenReturn(null);
        var args = new DefaultApplicationArguments();

        assertThatCode(() -> warmUp.run(args)).doesNotThrowAnyException();
        verify(chessService).getActiveGames(StartupWarmUp.NO_PLAYER);
    }
}
