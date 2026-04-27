# Chess Service

Backend for the chess feature on pmon.dev. PvP and human-vs-AI games over REST + STOMP/WebSocket. Server is the source of truth for board state — every move is replayed and validated with `chesslib` before being persisted and fanned out.

## Quick Start

```bash
cd ../ops && task dev        # Starts all infra + services
cd ../ops && task dev:infra  # Infra only, run ChessApplication from IDE
./gradlew test               # JUnit + Spring Boot test + ArchUnit
```

## Layout

```
src/main/java/io/schnappy/chess/
  ChessApplication.java          Spring entry point
  ChessController.java           REST endpoints under /chess/games/**
  ChessService.java              core game logic, chesslib Board replay, AI move
  ChessGame*.java                JPA entity + repository + DTO
  ChessGameCacheService.java     Valkey cache for active games
  ChessUser*.java                local mirror of admin-service users
  CreateGameRequest, GameEvent, GameResult, GameResultReason, GameStatus, GameType
  config/
    SecurityConfig.java          Spring Security — trusts gateway-propagated JWT
    KafkaConfig.java             producer/consumer factories
    WebSocketConfig.java         STOMP simple broker on /topic, /app prefix, SockJS
    WebSocketAuthInterceptor.java auth check on STOMP CONNECT (Keycloak JWT)
    SubscriptionGuard.java       enforces /topic/chess.{gameUuid} membership
    GlobalExceptionHandler.java  catch-all
  kafka/
    ChessKafkaProducer.java      writes to chess.moves
    ChessEventConsumer.java      fans out chess.moves to STOMP /topic/chess.{uuid}
    UserEventConsumer.java       consumes user.events to mirror users locally
  security/
    GatewayAuthFilter.java       reads Keycloak JWT, populates SecurityContext
    GatewayUser.java             user identity record
    PermissionInterceptor.java   AOP for @RequirePermission
    RequirePermission, Permission
    UserProvisioner.java         on-demand local-user upsert from JWT claims
```

## REST endpoints (auth: Keycloak JWT, permission: PLAY)

All under `/chess/games`:

```
POST   /                       create game (PvP or AI)
GET    /                       list user's games
GET    /open                   list open games to join
GET    /{uuid}                 game state
GET    /history                completed games
POST   /{uuid}/join            join an open PvP game
POST   /{uuid}/move            submit a move (validated server-side)
POST   /{uuid}/ai-move         submit an AI move (Stockfish-in-browser pre-computes)
POST   /{uuid}/resign
POST   /{uuid}/draw            offer draw
POST   /{uuid}/draw/accept
POST   /{uuid}/draw/decline
DELETE /{uuid}                 abort/cleanup
```

Server validates *every* move with `chesslib` regardless of which endpoint it came in on — `/ai-move` is just a different flow trigger, not a trust boundary.

## Real-time

STOMP simple broker (in-memory, single-pod-friendly). Destinations:

- Subscribe: `/topic/chess.{gameUuid}` — receive `GameEvent`s for that game
- `SubscriptionGuard` enforces the subscriber is actually in the game

## DB

PostgreSQL `chess` (production) / `monitor_chess` (local dev default in `application.yml`). Per-service DB on the shared CNPG cluster (Plan 056). JPA entities + Liquibase migrations under `src/main/resources/db/changelog/`.

## Kafka topics

- **chess.moves** — produced by this service, consumed by this service for fan-out, also consumable by other services that want to react to game events
- **user.events** — consumer only, mirrors admin-service users into the local `chess_user` table

## Valkey (Redis)

`ChessGameCacheService` caches active-game state. Persistence still goes to PostgreSQL — the cache is for hot-path reads, not source of truth.

## Tracing / metrics

OpenTelemetry starter is on the classpath; traces flow to Tempo, metrics to Prometheus → Mimir.

## Conventions specific to this service

- AI move flow: client (browser Stockfish worker) computes → submits via `/ai-move` → server validates with chesslib and broadcasts. Server **never** runs an engine.
- Game UUIDs are the user-facing identifier — internal DB IDs are not exposed.
- All state changes (move, resign, draw outcome) emit a `GameEvent` to Kafka before responding 200, so the WS broadcast is consistent with what the API returned.

## Full Infrastructure Docs

See `schnappy/ops` repo `CLAUDE.md`.
