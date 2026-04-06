# Chess Service

Chess game service with real-time moves via Kafka.

## Quick Start

```bash
cd ../ops && task dev        # Starts all infra + services
cd ../ops && task dev:infra  # Infra only, run ChessApplication from IDE
```

## Key Classes

| File | Purpose |
|------|---------|
| `SecurityConfig.java` | Spring Security config — trusts gateway headers |
| `GatewayAuthFilter.java` | Reads JWT payload, populates SecurityContext |
| `PermissionInterceptor.java` | AOP aspect enforcing `@RequirePermission` (PLAY) |
| `ChessController.java` | Chess game REST API |

## DB Schema (monitor_chess)

Separate PostgreSQL database on shared instance.

## Full Infrastructure Docs

See `schnappy/ops` repo `CLAUDE.md` for complete infrastructure documentation. 
