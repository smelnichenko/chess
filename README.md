# Chess Service

Chess game service for pmon.dev supporting PvP and AI games with real-time moves.

## Architecture

Behind Istio ingress; Istio validates Keycloak JWTs and forwards to the sidecar. Uses WebSocket for real-time move delivery, Kafka for event fan-out, Valkey for active-game state caching, and PostgreSQL for persistent game storage.

```
Istio ingress --> sidecar --> Chess --> PostgreSQL (chess DB)
                                    --> Valkey    (active game cache)
                                    --> Kafka     (event fan-out)
              <-- WebSocket (real-time moves)
```

## Tech Stack

- Java 25, Spring Boot 4.0, Gradle 9.3
- PostgreSQL 17 — game state persistence
- Valkey — active-game cache
- Kafka — game event fan-out
- Spring WebSocket — real-time move delivery
- Liquibase — schema migrations
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

Deployed to kubeadm via Argo CD GitOps:

1. Push to master triggers Woodpecker CD
2. `./gradlew clean check` then Kaniko builds the image
3. Image pushed to Forgejo registry at `git.pmon.dev`
4. Woodpecker commits the new tag to `schnappy/infra`
5. Argo CD syncs the Application

Production at `https://pmon.dev/api/chess/*` in the `schnappy-production-apps` namespace.
