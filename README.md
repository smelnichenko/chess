# Chess Service

Chess backend for pmon.dev — PvP and human-vs-AI games with server-side move validation and real-time updates.

## Architecture

Behind the Istio ingress gateway. Istio terminates TLS, validates the Keycloak JWT (including on the WebSocket upgrade), and forwards to the sidecar. Every move is replayed on the server with `chesslib` before being persisted, cached in Valkey, and fanned out via Kafka → STOMP. Human-vs-AI games run Stockfish in a Web Worker on the client and submit the result through a dedicated endpoint; the server still validates the position regardless.

```
Istio ingress --> sidecar --> Chess --> PostgreSQL  (chess DB, persistent state)
                                    --> Valkey      (active-game cache)
                                    --> Kafka       (chess.moves: fan-out)
              <-- WebSocket (STOMP/SockJS, /topic/chess.{uuid})
              <-- Kafka <-- Admin       (user.events sync)
```

## Tech Stack

- Java 25, Spring Boot 4.0, Gradle 9.3
- PostgreSQL 17 — game state + result history
- Valkey — active-game cache (`spring-boot-starter-data-redis`)
- Kafka — `chess.moves` fan-out, `user.events` consumer
- Spring WebSocket — STOMP simple broker over SockJS for real-time moves
- chesslib (`com.github.bhlangonijr:chesslib`) — server-side board replay and validation
- Liquibase — schema migrations
- OpenTelemetry — traces to Tempo, metrics to Prometheus → Mimir
- ArchUnit — architectural test rules
- SpringDoc OpenAPI

## REST API

All endpoints sit under `/api/chess/games` and require the `PLAY` permission:

| Method | Path | Purpose |
|--------|------|---------|
| `POST` | `/` | Create game (PvP or AI) |
| `GET` | `/` | List the caller's games |
| `GET` | `/open` | List open games waiting for an opponent |
| `GET` | `/{uuid}` | Get game state |
| `GET` | `/history` | Completed games |
| `POST` | `/{uuid}/join` | Join a PvP game |
| `POST` | `/{uuid}/move` | Submit a move |
| `POST` | `/{uuid}/ai-move` | Submit a client-computed AI move |
| `POST` | `/{uuid}/resign` | Resign |
| `POST` | `/{uuid}/draw` / `…/accept` / `…/decline` | Draw offer flow |
| `DELETE` | `/{uuid}` | Abort/cleanup |

## Real-time

Clients subscribe to `/topic/chess.{gameUuid}` after STOMP `CONNECT`. `WebSocketAuthInterceptor` validates the JWT on connect and `SubscriptionGuard` rejects subscriptions from non-participants.

## Development

```bash
# From the ops repo (starts PostgreSQL, Valkey, Kafka, all services)
task dev

# Or start infra only, then run from IDE
task dev:infra
./gradlew bootRun

# Tests
./gradlew test
```

## Deployment

Deployed to kubeadm via Argo CD GitOps:

1. Push to `main` triggers Woodpecker CD
2. `./gradlew clean check` then Kaniko builds the image
3. Image pushed to Forgejo registry at `git.pmon.dev`
4. Woodpecker commits the new tag to `schnappy/infra`
5. Argo CD syncs the Application

Production at `https://pmon.dev/api/chess/*` in the `schnappy-production-apps` namespace.
