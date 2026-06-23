package io.schnappy.chess;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Standalone MockMvc tests for InternalController. No Spring context — the
 * controller only parses the query params and delegates a membership lookup,
 * so a mocked repository plus standalone MockMvc exercises every branch.
 */
@ExtendWith(MockitoExtension.class)
class InternalControllerTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID GAME_UUID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Mock
    private ChessGameRepository gameRepository;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new InternalController(gameRepository)).build();
    }

    @Test
    void member_returnsOk() throws Exception {
        var game = gameWithPlayers(PLAYER, OTHER);
        when(gameRepository.findByUuid(GAME_UUID)).thenReturn(Optional.of(game));

        mockMvc.perform(get("/internal/membership")
                        .param("user", PLAYER.toString())
                        .param("channel", "chess:game:" + GAME_UUID))
                .andExpect(status().isOk());
    }

    @Test
    void gameFoundButNotAPlayer_returnsNotFound() throws Exception {
        var game = gameWithPlayers(PLAYER, OTHER);
        when(gameRepository.findByUuid(GAME_UUID)).thenReturn(Optional.of(game));

        UUID stranger = UUID.fromString("00000000-0000-0000-0000-000000000099");
        mockMvc.perform(get("/internal/membership")
                        .param("user", stranger.toString())
                        .param("channel", "chess:game:" + GAME_UUID))
                .andExpect(status().isNotFound());
    }

    @Test
    void gameNotFound_returnsNotFound() throws Exception {
        when(gameRepository.findByUuid(GAME_UUID)).thenReturn(Optional.empty());

        mockMvc.perform(get("/internal/membership")
                        .param("user", PLAYER.toString())
                        .param("channel", "chess:game:" + GAME_UUID))
                .andExpect(status().isNotFound());
    }

    @Test
    void invalidUserUuid_returnsNotFound() throws Exception {
        mockMvc.perform(get("/internal/membership")
                        .param("user", "not-a-uuid")
                        .param("channel", "chess:game:" + GAME_UUID))
                .andExpect(status().isNotFound());
    }

    @Test
    void wrongPartCount_returnsNotFound() throws Exception {
        mockMvc.perform(get("/internal/membership")
                        .param("user", PLAYER.toString())
                        .param("channel", "chess:game"))
                .andExpect(status().isNotFound());
    }

    @Test
    void wrongChannelPrefix_returnsNotFound() throws Exception {
        mockMvc.perform(get("/internal/membership")
                        .param("user", PLAYER.toString())
                        .param("channel", "chat:game:" + GAME_UUID))
                .andExpect(status().isNotFound());
    }

    @Test
    void wrongChannelKind_returnsNotFound() throws Exception {
        mockMvc.perform(get("/internal/membership")
                        .param("user", PLAYER.toString())
                        .param("channel", "chess:lobby:" + GAME_UUID))
                .andExpect(status().isNotFound());
    }

    @Test
    void invalidGameUuidInChannel_returnsNotFound() throws Exception {
        mockMvc.perform(get("/internal/membership")
                        .param("user", PLAYER.toString())
                        .param("channel", "chess:game:not-a-uuid"))
                .andExpect(status().isNotFound());
    }

    private static ChessGame gameWithPlayers(UUID white, UUID black) {
        var game = new ChessGame();
        game.setWhitePlayerUuid(white);
        game.setBlackPlayerUuid(black);
        game.setGameType(GameType.PVP);
        game.setStatus(GameStatus.IN_PROGRESS);
        return game;
    }
}
