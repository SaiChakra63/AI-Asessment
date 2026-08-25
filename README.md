# URL Shortener - Phase 3 Security and Resilience

This directory contains the runnable Phase 1 foundation, the Phase 2 brownfield
performance improvements, and the implemented Phase 3 security and
disaster-recovery work.

## Phase 1 capabilities

- Create a generated or custom short code.
- Redirect with HTTP `302 Found`.
- Soft-delete (deactivate) a short URL.
- Record clicks, unique visitors, and bounded device/referrer/location metadata.
- Use an HMAC visitor identifier for repeat-visitor counting. The current write
  path does not populate the nullable legacy IP/user-agent analytics columns;
  production deployments must still apply an explicit retention/privacy policy.
- Retrieve per-URL analytics.
- Validate requests and return stable JSON errors.
- Manage PostgreSQL schema through Flyway.
- Publish OpenAPI/Swagger documentation and health endpoints.

## Phase 2 additions

- Redis cache-aside lookup for the redirect critical path, with bounded TTLs.
- Safe database fallback if Redis becomes unavailable after application startup.
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

## Spring profiles

Configuration is separated by environment:

| File | Purpose |
|---|---|
| `application.properties` | Settings shared by every environment |
| `application-dev.properties` | Local development and Docker Compose defaults |
| `application-prod.properties` | Production settings with no database or secret defaults |
| `src/test/resources/application-test.properties` | Test-only settings; not packaged in the production application |

No profile is selected in the shared file. Docker Compose explicitly sets
`SPRING_PROFILES_ACTIVE=dev`, integration tests use `test`, and a production
deployment must explicitly set `SPRING_PROFILES_ACTIVE=prod`. This prevents a
production deployment from silently inheriting local-development values.

## Run with Docker Compose

For the first startup, create a local `.env` file before starting the stack.
The following PowerShell commands generate different random values for every
secret and write the configuration expected by `docker-compose.yml`:

```powershell
function New-LocalSecret {
    (New-Guid).ToString('N') + (New-Guid).ToString('N')
}

$dbPassword = New-LocalSecret
$visitorSalt = New-LocalSecret
$apiKeyPepper = New-LocalSecret
$bootstrapApiKey = New-LocalSecret
$grafanaPassword = New-LocalSecret

@"
POSTGRES_DB=urlshortener
POSTGRES_USER=postgres
POSTGRES_PASSWORD=$dbPassword
TRUST_FORWARDED_HEADERS=false
TRUSTED_PROXY_ADDRESSES=
TRUST_GEO_HEADERS=false
VISITOR_HASH_SALT=$visitorSalt
RATE_LIMIT_ENABLED=true
RATE_LIMIT_CAPACITY=100
RATE_LIMIT_WINDOW=1m
API_KEY_PEPPER=$apiKeyPepper
APP_BOOTSTRAP_API_KEY=$bootstrapApiKey
APP_API_CLIENT_ID=local-assessment-client
APP_API_CLIENT_DISPLAY_NAME=Local assessment client
APP_API_CLIENT_AUTHORITIES=URL_WRITE,ANALYTICS_READ,OPS_READ
APP_API_CLIENT_UPDATE_EXISTING=false
GRAFANA_ADMIN_USER=admin
GRAFANA_ADMIN_PASSWORD=$grafanaPassword
"@ | Set-Content -Encoding ascii .env

docker compose up -d --build
```

Docker Compose automatically reads `.env` from this project directory. The file
is ignored by Git and must never be committed. Compose passes `POSTGRES_DB`,
`POSTGRES_USER`, and `POSTGRES_PASSWORD` to both PostgreSQL and the Spring Boot
container, so the application uses the same credentials as the database.
`POSTGRES_PASSWORD`, `VISITOR_HASH_SALT`, and `GRAFANA_ADMIN_PASSWORD` are
required; Compose stops with a clear error if any of them is missing.

Create this file only once for an existing local stack. The PostgreSQL image
uses `POSTGRES_PASSWORD` when it initializes a new `postgres_data` volume; merely
changing that value later does not change the password inside an existing
database. Rotate an existing database password with PostgreSQL administration,
then update `.env` to the same value.

The generated `$bootstrapApiKey` is the value to use as `X-API-Key` in Postman.
You can retrieve it later from the local `.env` file. Never paste the
`API_KEY_PEPPER` into an API request; it is a server-side secret used only when
hashing API keys.

On the first startup, the bootstrap runner stores the API-key digest and client
authorities in PostgreSQL. On normal restarts,
`APP_API_CLIENT_UPDATE_EXISTING=false` prevents an existing client from being
silently reactivated or overwritten. For an intentional key rotation, set it to
`true` for one controlled application restart, verify the new key, then return
it to `false`.

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
**Dashboards -> URL Shortener -> URL Shortener - Phase 2 Overview**. Prometheus
scrapes Spring Boot metrics from `/actuator/prometheus`; Grafana queries the
provisioned Prometheus data source and renders the dashboard JSON stored under
`monitoring/grafana/dashboards/`.

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
$env:SPRING_PROFILES_ACTIVE = 'dev'
mvn spring-boot:run
```

When Spring Boot runs outside Compose, it does not automatically read `.env`.
Set the datasource credentials and application secrets in that PowerShell
session, using the same values contained in the local `.env`:

```powershell
$env:SPRING_DATASOURCE_URL = 'jdbc:postgresql://localhost:5432/urlshortener'
$env:SPRING_DATASOURCE_USERNAME = 'postgres'
$env:SPRING_DATASOURCE_PASSWORD = '<your POSTGRES_PASSWORD value>'
$env:VISITOR_HASH_SALT = '<at least 32 random bytes>'
$env:API_KEY_PEPPER = '<at least 32 random bytes>'
$env:APP_BOOTSTRAP_API_KEY = '<a different value of at least 32 random bytes>'
mvn spring-boot:run
```

The three `SPRING_DATASOURCE_*` variables are the Spring equivalents of the
Compose `POSTGRES_*` values. They are passed automatically only when the
application is launched by Docker Compose.

Run tests:

```powershell
mvn clean test
```

For production, inject all required values through the deployment platform's
secret/configuration mechanism and activate the profile explicitly:

```powershell
$env:SPRING_PROFILES_ACTIVE = 'prod'
java -jar target/url-shortener-1.0.0.jar
```

The production profile intentionally fails startup when required database,
Redis, public-base-URL, visitor-salt, or API-key-pepper values are absent.

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

The restore script targets a separately named database and never performs an
automatic live cutover. Backups written to the local `backups/` directory are
ignored by Git. For production, store encrypted backups off-host and validate
recovery through scheduled restore drills.
