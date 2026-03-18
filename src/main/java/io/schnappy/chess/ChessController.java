package io.schnappy.chess;

import io.schnappy.chess.security.GatewayUser;
import io.schnappy.chess.security.Permission;
import io.schnappy.chess.security.RequirePermission;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/chess")
@RequirePermission(Permission.PLAY)
@RequiredArgsConstructor
public class ChessController {

    private final ChessService chessService;

    @PostMapping("/games")
    public ResponseEntity<ChessGameDto> createGame(
            @RequestBody CreateGameRequest request,
            @RequestAttribute("gatewayUser") GatewayUser auth) {
        Long userId = auth.userId();
        ChessGame game;
        if (request.type() == GameType.AI) {
            game = chessService.createAiGame(userId, request.difficulty() != null ? request.difficulty() : 10);
        } else {
            game = chessService.createPvpGame(userId);
        }
        return ResponseEntity.ok(ChessGameDto.from(game));
    }

    @GetMapping("/games")
    public List<ChessGameDto> getActiveGames(@RequestAttribute("gatewayUser") GatewayUser auth) {
        return chessService.getActiveGames(auth.userId()).stream()
            .map(ChessGameDto::from)
            .toList();
    }

    @GetMapping("/games/open")
    public List<ChessGameDto> getOpenGames() {
        return chessService.getOpenGames().stream()
            .map(ChessGameDto::from)
            .toList();
    }

    @GetMapping("/games/{uuid}")
    public ChessGameDto getGame(@PathVariable UUID uuid) {
        return chessService.getGame(uuid);
    }

    @GetMapping("/games/history")
    public Page<ChessGameDto> getHistory(@RequestAttribute("gatewayUser") GatewayUser auth, Pageable pageable) {
        return chessService.getHistory(auth.userId(), pageable)
            .map(ChessGameDto::from);
    }

    @PostMapping("/games/{uuid}/join")
    public ChessGameDto joinGame(@PathVariable UUID uuid, @RequestAttribute("gatewayUser") GatewayUser auth) {
        return ChessGameDto.from(chessService.joinGame(uuid, auth.userId()));
    }

    @PostMapping("/games/{uuid}/move")
    public ChessGameDto makeMove(
            @PathVariable UUID uuid,
            @RequestBody Map<String, String> body,
            @RequestAttribute("gatewayUser") GatewayUser auth) {
        String move = body.get("move");
        if (move == null || move.isBlank()) {
            throw new IllegalArgumentException("Move is required");
        }
        return ChessGameDto.from(chessService.makeMove(uuid, move.trim(), auth.userId()));
    }

    @PostMapping("/games/{uuid}/ai-move")
    public ChessGameDto makeAiMove(
            @PathVariable UUID uuid,
            @RequestBody Map<String, String> body,
            @RequestAttribute("gatewayUser") GatewayUser auth) {
        String move = body.get("move");
        if (move == null || move.isBlank()) {
            throw new IllegalArgumentException("Move is required");
        }
        return ChessGameDto.from(chessService.makeAiMove(uuid, move.trim(), auth.userId()));
    }

    @PostMapping("/games/{uuid}/resign")
    public ChessGameDto resign(@PathVariable UUID uuid, @RequestAttribute("gatewayUser") GatewayUser auth) {
        return ChessGameDto.from(chessService.resign(uuid, auth.userId()));
    }

    @PostMapping("/games/{uuid}/draw")
    public ChessGameDto offerDraw(@PathVariable UUID uuid, @RequestAttribute("gatewayUser") GatewayUser auth) {
        return ChessGameDto.from(chessService.offerDraw(uuid, auth.userId()));
    }

    @PostMapping("/games/{uuid}/draw/accept")
    public ChessGameDto acceptDraw(@PathVariable UUID uuid, @RequestAttribute("gatewayUser") GatewayUser auth) {
        return ChessGameDto.from(chessService.acceptDraw(uuid, auth.userId()));
    }

    @PostMapping("/games/{uuid}/draw/decline")
    public ChessGameDto declineDraw(@PathVariable UUID uuid, @RequestAttribute("gatewayUser") GatewayUser auth) {
        return ChessGameDto.from(chessService.declineDraw(uuid, auth.userId()));
    }

    @DeleteMapping("/games/{uuid}")
    public ResponseEntity<Void> abandon(@PathVariable UUID uuid, @RequestAttribute("gatewayUser") GatewayUser auth) {
        chessService.abandon(uuid, auth.userId());
        return ResponseEntity.noContent().build();
    }
}
