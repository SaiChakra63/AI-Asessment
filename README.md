# URL Shortener — Phase 2 Brownfield

This directory contains the runnable Phase 1 foundation plus the Phase 2
brownfield performance, reliability, and analytics improvements. Phase 3
enterprise features remain intentionally out of scope.

## Phase 1 capabilities

- Create a generated or custom short code.
- Redirect with HTTP `302 Found`.
- Soft-delete (deactivate) a short URL.
- Record total clicks, device type, referrer, client IP, and user agent.
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

## Prerequisites

- Java 17
- Maven 3.9+
- Docker Desktop (for PostgreSQL, Redis, and integration tests)

## Run with Docker Compose

```bash
cp .env.example .env
# Replace POSTGRES_PASSWORD in .env with a local development password.
docker compose up --build
```

Docker Compose reads `.env` from this directory. The file is ignored by Git;
`.env.example` documents the required names without storing production secrets.

The application starts at `http://localhost:8080`.

- Swagger UI: `http://localhost:8080/swagger-ui.html`
- OpenAPI JSON: `http://localhost:8080/api-docs`
- Health: `http://localhost:8080/actuator/health`
- Phase 2 dependency health: `http://localhost:8080/api/v1/health`
- Prometheus metrics: `http://localhost:8080/actuator/prometheus`

## Run locally

On this Windows machine, set Maven to use JDK 17 in the current PowerShell
session before building:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17'
mvn --version
```

The reported Java version must be 17 or newer.

Start PostgreSQL and Redis before running Spring Boot locally:

```bash
docker compose up -d postgres redis
mvn spring-boot:run
```

Run tests:

```bash
mvn clean test
```

If Docker is unavailable, Testcontainers integration tests are skipped while
unit tests still run.

## API examples

Create:

```bash
curl -X POST http://localhost:8080/api/v1/urls/shorten \
  -H "Content-Type: application/json" \
  -d '{"originalUrl":"https://example.com/assessment","customCode":"demo01"}'
```

Redirect:

```bash
curl -i http://localhost:8080/api/v1/urls/demo01
```

Statistics:

```bash
curl http://localhost:8080/api/v1/analytics/urls/demo01/stats
```

Deactivate:

```bash
curl -i -X DELETE http://localhost:8080/api/v1/urls/demo01
```

## Assessment evidence

- [Section 5 deliverables index](docs/ASSESSMENT_DELIVERABLES.md)
- [Phase 1 engineering record](docs/PHASE1_ENGINEERING_RECORD.md)
- [Phase 1 baseline for the Phase 2 comparison](docs/PHASE1_BASELINE.md)
- [Phase 2 engineering record and deliverables](docs/PHASE2_ENGINEERING_RECORD.md)
- [Preserved Phase 1 to Phase 2 changeset](docs/PHASE1_TO_PHASE2_CHANGESET.md)
- [Phase 2 architecture](docs/PHASE2_ARCHITECTURE.md)
- [Phase 2 testing and benchmark guide](docs/PHASE2_TESTING_AND_BENCHMARKS.md)

The numbered `.txt` assessment artifacts provide the broader phase plan. The
files in `docs/` record what this runnable implementation actually does and the
evidence required before making optimization or scale claims.
