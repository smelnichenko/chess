# Chess Service

Chess game service for pmon.dev supporting PvP and AI games with real-time moves.

## Architecture

Receives authenticated requests via the API gateway. Uses WebSocket for real-time move delivery, Kafka for event fan-out, Redis for game state caching, and PostgreSQL for persistent game storage.

```
API Gateway --> Chess (this service) --> PostgreSQL (monitor_chess DB)
                                     --> Redis (game cache)
                                     --> Kafka (event fan-out)
            <-- WebSocket (real-time moves)
```

## Tech Stack

- Java 25, Spring Boot 4.0, Gradle 9.3
- PostgreSQL 17 (game state persistence)
- Redis (active game caching)
- Kafka (game event fan-out)
- WebSocket (real-time move delivery)
- Liquibase (schema migrations)
- SpringDoc OpenAPI

## Development

```bash
# From the ops repo
task dev

# Or start infra only, then run from IDE
task dev:infra
./gradlew bootRun

# Run tests
./gradlew test
```

## Deployment

Deployed to k3s via Flux CD GitOps:

1. Push to master triggers Woodpecker CD pipeline
2. `./gradlew test` runs, then Kaniko builds the container image
3. Image pushed to Forgejo registry at `git.pmon.dev`
4. Woodpecker commits new image tag to the `schnappy/infra` repo
5. Flux detects the change and reconciles the HelmRelease

Production at `https://pmon.dev/api/chess/*` in the `monitor` namespace.
