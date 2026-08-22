# URL Shortener - Phase 3 Security and Resilience

This directory contains the runnable Phase 1 foundation, the Phase 2 brownfield
performance improvements, and the implemented Phase 3 security and
disaster-recovery work.

## Phase 1 capabilities

- Create a generated or custom short code.
- Redirect with HTTP `302 Found`.
- Soft-delete (deactivate) a short URL.
- Record clicks and bounded device/referrer/location metadata; use an HMAC
  visitor identifier instead of persisting raw IP or user-agent values.
- Retrieve per-URL analytics.
- Validate requests and return stable JSON errors.
- Manage PostgreSQL schema through Flyway.
- Publish OpenAPI/Swagger documentation and health endpoints.

## Phase 2 additions

- Redis cache-aside lookup for the redirect critical path, with bounded TTLs.
- Safe database fallback when Redis is unavailable.
- Atomic Redis-backed per-client rate limiting across application instances.
- Unique-visitor and device/location/referrer analytics.
- Cache invalidation after committed writes.
- Prometheus-compatible cache, rate-limit, and redirect-latency metrics.
- PostgreSQL and Redis Testcontainers integration coverage.

## Phase 3 additions

- Stateless API-key authentication for management endpoints.
- Role authorization for URL writes, analytics, and operational health.
- Per-client URL ownership; cross-client management returns `404` without
  leaking whether another client's code exists.
- HMAC-SHA-256 API-key digests in PostgreSQL; raw keys and the server-side
  pepper are never stored in the database.
- PostgreSQL backup and restore-drill PowerShell scripts with archive-TOC,
  size, and SHA-256 checks; only a completed restore proves recoverability.

## Prerequisites

- Java 17
- Maven 3.9+
- Docker Desktop (for PostgreSQL, Redis, Prometheus, Grafana, and integration tests)

## Run with Docker Compose

```powershell
Copy-Item .env.example .env
# Replace every placeholder and fill the intentionally blank API_KEY_PEPPER
# and APP_BOOTSTRAP_API_KEY with two different high-entropy values.
docker compose up --build
```

Docker Compose automatically reads `.env` from this project directory. The file
is ignored by Git; `.env.example` documents names without storing real secrets.

After changing API-key settings, rebuild/restart the application:

```powershell
docker compose up -d --build url-shortener
docker compose logs -f url-shortener
```

The application starts at `http://localhost:8080`.

- Swagger UI: `http://localhost:8080/swagger-ui.html`
- OpenAPI JSON: `http://localhost:8080/api-docs`
- Actuator health: `http://localhost:8080/actuator/health`
- Protected dependency health: `http://localhost:8080/api/v1/health`
- Prometheus metrics: `http://localhost:8080/actuator/prometheus`
- Prometheus targets: `http://localhost:9090/targets`
- Grafana: `http://localhost:3000` (credentials come from `.env`)

In Postman, add the request header `X-API-Key` with exactly the value assigned
to `APP_BOOTSTRAP_API_KEY` for protected endpoints. The redirect endpoint stays
public so a visitor can open a short link without an account.

Prometheus and Grafana are provisioned from `monitoring/`. In Grafana, open
**Dashboards -> URL Shortener -> URL Shortener - Phase 2 Overview**. See the
[monitoring dashboard beginner guide](docs/PHASE2_MONITORING_DASHBOARD_BEGINNER_GUIDE.md)
for the data flow and troubleshooting steps.

## Run locally

On this Windows machine, set Maven to use JDK 17 in the current PowerShell
session before building:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17'
mvn --version
```

The reported Java version must be 17 or newer. Start PostgreSQL and Redis before
running Spring Boot locally:

```powershell
docker compose up -d postgres redis
mvn spring-boot:run
```

When Spring Boot is outside Compose, set the datasource credentials and API
secrets in that PowerShell session. Use the same database values you placed in
your local `.env`:

```powershell
$env:SPRING_DATASOURCE_URL = 'jdbc:postgresql://localhost:5432/urlshortener'
$env:SPRING_DATASOURCE_USERNAME = 'postgres'
$env:SPRING_DATASOURCE_PASSWORD = '<your POSTGRES_PASSWORD value>'
$env:VISITOR_HASH_SALT = '<at least 32 random bytes>'
$env:API_KEY_PEPPER = '<at least 32 random bytes>'
$env:APP_BOOTSTRAP_API_KEY = '<a different value of at least 32 random bytes>'
mvn spring-boot:run
```

Values in `.env` are read by Docker Compose; Spring Boot itself does not
automatically read `.env`. This is why the variables must be set again when the
Java process runs directly from VS Code or PowerShell.

Run tests:

```powershell
mvn clean test
```

If Docker is unavailable, Testcontainers integration tests are skipped while
unit tests still run.

## API examples

For Postman:

1. `POST http://localhost:8080/api/v1/urls/shorten`
2. Header: `Content-Type: application/json`
3. Header: `X-API-Key: <your APP_BOOTSTRAP_API_KEY value>`
4. Body -> raw -> JSON:

```json
{
  "originalUrl": "https://example.com/assessment",
  "customCode": "demo01"
}
```

Public redirect (no API key required):

```powershell
curl.exe -i http://localhost:8080/api/v1/urls/demo01
```

Protected statistics:

```powershell
$apiKey = '<same value placed in your local .env>'
curl.exe -H "X-API-Key: $apiKey" http://localhost:8080/api/v1/analytics/urls/demo01/stats
```

Protected deactivation:

```powershell
curl.exe -i -X DELETE -H "X-API-Key: $apiKey" http://localhost:8080/api/v1/urls/demo01
```

## Backup and isolated restore drill

Create an archive-checked PostgreSQL backup on Windows:

```powershell
.\scripts\backup-postgres.ps1
```

Restore a selected backup only into a new database (never over the source):

```powershell
.\scripts\restore-postgres.ps1 `
  -BackupPath .\backups\postgres\urlshortener-<timestamp>.dump `
  -TargetDatabase urlshortener_restore_test
```

Read [the disaster-recovery runbook](docs/PHASE3_DISASTER_RECOVERY.md) before
running a restore. The script never performs automatic live cutover.

## Assessment evidence

- [Section 5 deliverables index](docs/ASSESSMENT_DELIVERABLES.md)
- [Phase 1 engineering record](docs/PHASE1_ENGINEERING_RECORD.md)
- [Phase 1 baseline](docs/PHASE1_BASELINE.md)
- [Phase 2 engineering record](docs/PHASE2_ENGINEERING_RECORD.md)
- [Phase 1 to Phase 2 changeset](docs/PHASE1_TO_PHASE2_CHANGESET.md)
- [Phase 2 architecture](docs/PHASE2_ARCHITECTURE.md)
- [Phase 2 testing and benchmarks](docs/PHASE2_TESTING_AND_BENCHMARKS.md)
- [Phase 3 security and authorization](docs/PHASE3_SECURITY_AND_AUTHORIZATION.md)
- [Phase 3 disaster-recovery runbook](docs/PHASE3_DISASTER_RECOVERY.md)
- [Phase 2 to Phase 3 changeset](docs/PHASE2_TO_PHASE3_CHANGESET.md)
- [Phase 3 engineering record](docs/PHASE3_ENGINEERING_RECORD.md)
- [Final submission peer review](docs/FINAL_SUBMISSION_PEER_REVIEW.md)

The numbered `.txt` assessment artifacts provide the broader plan. The files in
`docs/` record what the runnable implementation actually does and the evidence
required before making optimization, scaling, or recovery claims.
