package io.schnappy.chess;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;
import java.util.UUID;

/**
 * Internal endpoints consumed by sibling services over mTLS. Auth is
 * enforced by Istio mesh-level DENY policies (see schnappy-mesh chart);
 * Spring Security explicitly bypasses /internal/** so the cluster
 * principal — not a Keycloak JWT — is the authority.
 *
 * Membership lookup: returns 200 if the caller-supplied user is one of
 * the two players in the given game, 404 otherwise. Used by the admin
 * service when minting Centrifugo channel-subscription tokens.
 */
@RestController
@RequestMapping("/internal")
public class InternalController {

    private final ChessGameRepository gameRepository;

    public InternalController(ChessGameRepository gameRepository) {
        this.gameRepository = gameRepository;
    }

    /**
     * @param user    Keycloak user UUID
     * @param channel Centrifugo channel string, expected shape {@code chess:game:<gameUuid>}
     */
    @GetMapping("/membership")
    public ResponseEntity<Void> membership(@RequestParam("user") String user,
                                            @RequestParam("channel") String channel) {
        UUID userUuid;
        try {
            userUuid = UUID.fromString(user);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }

        // Channel must be `chess:game:<gameUuid>`. Anything else → not a member.
        String[] parts = channel.split(":");
        if (parts.length != 3 || !"chess".equals(parts[0]) || !"game".equals(parts[1])) {
            return ResponseEntity.notFound().build();
        }
        UUID gameUuid;
        try {
            gameUuid = UUID.fromString(parts[2]);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }

        Optional<ChessGame> game = gameRepository.findByUuid(gameUuid);
        if (game.isPresent() && game.get().isPlayerInGame(userUuid)) {
            return ResponseEntity.ok().build();
        }
        return ResponseEntity.notFound().build();
    }
}
