# Chess Service

Backend for the chess feature on pmon.dev. PvP and human-vs-AI games over REST. Server is the source of truth for board state — every move is replayed and validated with `chesslib` before being persisted, then a Kafka envelope is published for Centrifugo to fan out to subscribers.

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
  InternalController.java        /internal/membership for admin's sub-token mint
  ChessService.java              core game logic, chesslib Board replay, AI move
  ChessGame*.java                JPA entity + repository + DTO
  ChessGameCacheService.java     Valkey cache for active games
  ChessUser*.java                local mirror of admin-service users
  CreateGameRequest, GameEvent, GameResult, GameResultReason, GameStatus, GameType
  config/
    SecurityConfig.java          Spring Security — trusts Istio-propagated JWT, permits /internal/**
    KafkaConfig.java             producer/consumer factories
    GlobalExceptionHandler.java  catch-all
  kafka/
    ChessKafkaProducer.java      writes to chess.moves (legacy; no consumer)
    EventEnvelope.java           publication envelope record
    EventEnvelopeProducer.java   writes to events.chess.moves (Centrifugo fan-out)
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

## Internal endpoint (admin-only, mTLS-fronted)

```
GET /internal/membership?user=<uuid>&channel=chess:game:<uuid>
  → 200 if user is white or black, 404 otherwise
```

`/internal/**` is `permitAll` in Spring Security; mesh-level Istio
AuthorizationPolicy DENY rejects every source SA except admin.

## Real-time fan-out (Centrifugo)

This service does NOT terminate WebSocket connections. After every state change (move, resign, draw outcome), `EventEnvelopeProducer` publishes a publication envelope to `events.chess.moves` with header `x-centrifugo-channels: chess:game:<uuid>`. Centrifugo's Kafka async-consumer (different workload) reads it and pushes the new game state to every subscriber.

Browsers subscribe to `chess:game:<uuid>` via centrifuge-js; admin (`POST /api/realtime/sub-token`) mints the per-channel subscription token after checking the `/internal/membership` endpoint above.

## DB

PostgreSQL `chess` (production) / `monitor_chess` (local dev default in `application.yml`). Per-service DB on the shared CNPG cluster. JPA entities + Liquibase migrations under `src/main/resources/db/changelog/`.

## Kafka topics

- **events.chess.moves** — produced by `EventEnvelopeProducer`, consumed by Centrifugo + ClickHouse. Primary realtime channel.
- **chess.moves** — legacy; produced by `ChessKafkaProducer` from `ChessService` but has no consumer left after the STOMP fan-out was removed. Pending cleanup (rip out producer + drop topic).
- **user.events** — consumer only, mirrors admin-service users into the local `chess_user` table.

## Valkey (Redis)

`ChessGameCacheService` caches active-game state. Persistence still goes to PostgreSQL — the cache is for hot-path reads, not source of truth.

## Tracing / metrics

OpenTelemetry starter is on the classpath; traces flow to Tempo, metrics to Prometheus → Mimir.

## Conventions specific to this service

- AI move flow: client (browser Stockfish worker) computes → submits via `/ai-move` → server validates with chesslib and publishes the envelope. Server **never** runs an engine.
- Game UUIDs are the user-facing identifier — internal DB IDs are not exposed.
- All state changes (move, resign, draw outcome) write to PostgreSQL + publish the envelope before responding 200, so the realtime fanout is consistent with what the API returned.

## Full Infrastructure Docs

See `schnappy/ops` repo `CLAUDE.md`.
