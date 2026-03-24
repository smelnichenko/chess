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
        UUID playerUuid = auth.uuid();
        ChessGame game;
        if (request.type() == GameType.AI) {
            game = chessService.createAiGame(playerUuid, request.difficulty() != null ? request.difficulty() : 10);
        } else {
            game = chessService.createPvpGame(playerUuid);
        }
        return ResponseEntity.ok(chessService.toDto(game));
    }

    @GetMapping("/games")
    public List<ChessGameDto> getActiveGames(@RequestAttribute("gatewayUser") GatewayUser auth) {
        return chessService.getActiveGames(auth.uuid()).stream()
            .map(chessService::toDto)
            .toList();
    }

    @GetMapping("/games/open")
    public List<ChessGameDto> getOpenGames() {
        return chessService.getOpenGames().stream()
            .map(chessService::toDto)
            .toList();
    }

    @GetMapping("/games/{uuid}")
    public ChessGameDto getGame(@PathVariable UUID uuid) {
        return chessService.getGame(uuid);
    }

    @GetMapping("/games/history")
    public Page<ChessGameDto> getHistory(@RequestAttribute("gatewayUser") GatewayUser auth, Pageable pageable) {
        return chessService.getHistory(auth.uuid(), pageable)
            .map(chessService::toDto);
    }

    @PostMapping("/games/{uuid}/join")
    public ChessGameDto joinGame(@PathVariable UUID uuid, @RequestAttribute("gatewayUser") GatewayUser auth) {
        return chessService.toDto(chessService.joinGame(uuid, auth.uuid()));
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
        return chessService.toDto(chessService.makeMove(uuid, move.trim(), auth.uuid()));
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
        return chessService.toDto(chessService.makeAiMove(uuid, move.trim(), auth.uuid()));
    }

    @PostMapping("/games/{uuid}/resign")
    public ChessGameDto resign(@PathVariable UUID uuid, @RequestAttribute("gatewayUser") GatewayUser auth) {
        return chessService.toDto(chessService.resign(uuid, auth.uuid()));
    }

    @PostMapping("/games/{uuid}/draw")
    public ChessGameDto offerDraw(@PathVariable UUID uuid, @RequestAttribute("gatewayUser") GatewayUser auth) {
        return chessService.toDto(chessService.offerDraw(uuid, auth.uuid()));
    }

    @PostMapping("/games/{uuid}/draw/accept")
    public ChessGameDto acceptDraw(@PathVariable UUID uuid, @RequestAttribute("gatewayUser") GatewayUser auth) {
        return chessService.toDto(chessService.acceptDraw(uuid, auth.uuid()));
    }

    @PostMapping("/games/{uuid}/draw/decline")
    public ChessGameDto declineDraw(@PathVariable UUID uuid, @RequestAttribute("gatewayUser") GatewayUser auth) {
        return chessService.toDto(chessService.declineDraw(uuid, auth.uuid()));
    }

    @DeleteMapping("/games/{uuid}")
    public ResponseEntity<Void> abandon(@PathVariable UUID uuid, @RequestAttribute("gatewayUser") GatewayUser auth) {
        chessService.abandon(uuid, auth.uuid());
        return ResponseEntity.noContent().build();
    }
}
