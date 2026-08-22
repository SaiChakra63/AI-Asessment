# URL Shortener Assessment Deliverables

## 1. Executive summary

This submission is a runnable URL-shortening service developed incrementally in
three engineering scenarios:

1. **Greenfield:** build the core URL-shortening product from an empty project.
2. **Brownfield:** improve the running product without replacing the stable
   PostgreSQL foundation.
3. **Ambiguous:** evaluate requirements whose correct implementation depends on
   scale, trust boundaries, and operational evidence rather than adding every
   proposed component automatically.

The result runs end-to-end with Spring Boot, PostgreSQL, Redis, Prometheus, and
Grafana through Docker Compose. It supports URL creation, public redirection,
analytics, deactivation, API-key authorization, rate limiting, monitoring, and
database backup/restore-drill tooling.

## 2. Deliverable status

| Required deliverable | Evidence in this submission |
|---|---|
| Working prototype | `docker compose up -d --build` starts the complete stack; the create, redirect, analytics, delete, health, metrics, and dashboard flows are runnable locally. |
| Architecture overview | Sections 3-5 below describe components, tools, execution approach, control flow, and key decisions. |
| Three scenarios | Sections 6-8 show the decomposition, execution, and validation for greenfield, brownfield, and ambiguous work. |
| Setup instructions | Section 9 provides the local startup and verification procedure. The root `README.md` contains the detailed commands. |
| Testing, limitations, trade-offs | Sections 10-12 record automated evidence, known boundaries, and consciously accepted trade-offs. |

## 3. Architecture overview

### 3.1 Runtime components

| Component | Responsibility | Main technology |
|---|---|---|
| API layer | Request validation, HTTP contracts, redirects, stable error responses | Spring Boot MVC |
| Security layer | Stateless API-key authentication, roles, client ownership, fail-closed protected APIs | Spring Security |
| Rate-limit layer | Per-client/IP fixed-window request control before authentication | Redis Lua script + Spring filter |
| Service layer | URL lifecycle, cache-aside flow, analytics, authorization-aware operations | Java services + Spring transactions |
| Persistent store | Authoritative URL mappings, statistics, analytics, API clients, ownership | PostgreSQL 15 |
| Cache | Redirect and statistics acceleration; shared rate-limit counters | Redis 7 |
| Schema management | Repeatable, versioned database creation and brownfield migration | Flyway |
| Metrics collection | Scrape application and JVM metrics | Actuator, Micrometer, Prometheus |
| Visualization | Provisioned operational dashboard | Grafana |
| Packaging | Repeatable local environment and application image | Dockerfile + Docker Compose |
| Recovery tooling | Logical backup and isolated restore drill | PowerShell + `pg_dump`/`pg_restore` |

### 3.2 High-level flow

```text
Postman / browser
       |
       v
RateLimitFilter
       |
       v
API-key authentication and role authorization
       |
       v
Controllers
  |          |             |
  v          v             v
URL service  Analytics     Health
  |          service       checks
  |             |
  +---- Redis cache -------+
  |             |
  +---- PostgreSQL --------+
       (source of truth)

Spring Actuator -> Prometheus -> Grafana dashboard
```

### 3.3 Repository structure

```text
src/main/java/com/shortener/
  config/       Security, rate-limit filter, OpenAPI configuration
  controller/   URL, analytics, and dependency-health endpoints
  security/     API-key hashing, authentication, bootstrap, principals
  service/      URL, cache, rate limit, analytics, request metadata
  repository/   JPA persistence interfaces
  model/        PostgreSQL entities
  dto/          Public request/response contracts
  exception/    Stable API error mapping

src/main/resources/db/migration/   Flyway migrations V001-V003
monitoring/                         Prometheus and Grafana provisioning
scripts/                            Benchmark, backup, and restore tools
```

## 4. Tools and execution approach

### 4.1 Tools

- Java 17 and Spring Boot 3.3.5
- Maven for dependency management, build, test, and JaCoCo reporting
- PostgreSQL for authoritative relational data
- Redis for cache-aside reads and atomic rate-limit counters
- Flyway for forward database migrations
- Spring Security for API authentication and role authorization
- Testcontainers for real PostgreSQL/Redis integration tests
- Docker Compose for the local end-to-end environment
- Prometheus and Grafana for operational metrics and visualization
- Swagger/OpenAPI and Postman for API discovery/manual validation
- Git for phased change history

### 4.2 Phased execution

The implementation was not developed as one large change:

1. Establish a working Phase 1 baseline.
2. Identify redirect-path and operational weaknesses in the baseline.
3. Add Phase 2 optimizations behind stable API contracts.
4. Add Phase 3 security, ownership, and recovery controls.
5. Run unit, integration, migration, security, and end-to-end checks.
6. Keep production-only scale mechanisms out until measurements justify them.

This preserves a working system after every phase and makes changes explainable
as engineering decisions rather than a collection of unrelated features.

## 5. Important control flows and decisions

### 5.1 Create a short URL

```text
POST /api/v1/urls/shorten
 -> rate-limit evaluation
 -> API-key authentication
 -> URL_WRITE/ADMIN authorization
 -> URL and optional custom-code validation
 -> PostgreSQL transaction saves mapping + initial statistics
 -> transaction commits
 -> after-commit callback stores redirect value in Redis
 -> HTTP 201 response
```

The cache write occurs after the database commit. Redis must not expose a URL
whose PostgreSQL transaction later rolls back.

### 5.2 Public redirect

```text
GET /api/v1/urls/{shortCode}
 -> public endpoint (no API key required)
 -> Redis lookup
 -> cache hit: use cached destination
 -> cache miss: read active mapping from PostgreSQL and fill Redis
 -> atomically update click/unique-visitor analytics in PostgreSQL
 -> invalidate cached statistics after commit
 -> HTTP 302 Location: original URL
```

Public redirection is intentional: a recipient of a short link should not need
an account. Management and analytics operations remain protected.

### 5.3 API-key validation

The raw key is supplied in `X-API-Key`. The server combines it with a secret
pepper and calculates an HMAC-SHA-256 digest. PostgreSQL stores only the digest,
client ID, authorities, and active status. Authentication succeeds only when an
active database record has a constant-time digest match. API keys are intended
for calling applications/clients; this prototype does not implement end-user
login or JWT refresh-token flows.

### 5.4 Authorization and ownership

| Operation | Access rule |
|---|---|
| Public redirect | No API key |
| Create URL | `URL_WRITE` or `ADMIN` |
| Deactivate URL | Owner with `URL_WRITE`, or `ADMIN` |
| Read analytics | Owner with `ANALYTICS_READ`, or `ADMIN` |
| Dependency health | `OPS_READ` or `ADMIN` |
| Actuator health/Prometheus and Swagger | Public on the loopback-bound local stack |

Cross-client management returns `404` rather than revealing that another
client's short code exists.

### 5.5 Cache and database decision

PostgreSQL remains the source of truth. Redis is an optimization and never the
only copy of URL data. The service uses cache-aside reads, bounded TTL values,
and after-commit invalidation. Runtime Redis failures fall back to PostgreSQL;
rate limiting deliberately fails open while recording a failure metric.

### 5.6 Monitoring decision

Micrometer publishes application counters and latency metrics through
`/actuator/prometheus`. Prometheus scrapes and stores the time series. Grafana
queries Prometheus and renders the provisioned dashboard. The monitoring files
contain configuration only; application data is sent through HTTP metric
scraping, not written into the `monitoring/` directory.

## 6. Scenario 1: Greenfield development

### 6.1 Problem

Build a URL-shortening service from an empty project that can create, resolve,
track, and deactivate short links.

### 6.2 Decomposition

1. Define API request/response contracts.
2. Model mappings, statistics, and analytics.
3. Create versioned PostgreSQL schema migration.
4. Implement validation and short-code generation.
5. Implement service transactions and repositories.
6. Add controllers, error handling, Swagger, Docker packaging, and tests.

### 6.3 Execution

- Built a layered Spring Boot application.
- Added generated/custom Base62-style alphanumeric short codes and database
  uniqueness constraints.
- Used PostgreSQL transactions for mapping and statistics consistency.
- Added public `302` redirects, soft deletion, analytics, and stable JSON errors.
- Added Flyway V001 and a Dockerized PostgreSQL environment.

### 6.4 Validation

- Unit-tested validation, generation, services, and error responses.
- Verified create -> redirect -> analytics -> deactivate manually.
- Verified PostgreSQL persistence rather than relying on in-memory state.
- Confirmed repeatable startup from Flyway migrations.

## 7. Scenario 2: Brownfield improvement

### 7.1 Problem

Improve the existing working service's redirect latency, abuse protection,
analytics, and observability without rewriting the Phase 1 application.

### 7.2 Decomposition

1. Preserve PostgreSQL as the durable authority.
2. Isolate the redirect read path as the cache candidate.
3. Define cache consistency behavior and TTLs.
4. Add shared rate limiting suitable for multiple app instances.
5. Improve analytics writes and unique-visitor counting.
6. Add metrics, dashboards, integration tests, and benchmark tooling.

### 7.3 Execution

- Added Redis cache-aside URL and statistics lookups.
- Added after-commit cache updates/invalidation.
- Added an atomic Redis Lua fixed-window rate limiter.
- Hashed rate-limit identifiers before using them as Redis keys.
- Added unique-visitor conflict handling and atomic statistics increments.
- Added V002 schema evolution rather than recreating Phase 1 tables.
- Added Prometheus, Grafana, cache metrics, rate-limit metrics, and redirect
  latency measurement.

### 7.4 Validation

- Exercised cache hit, miss, invalidation, and Redis-failure behavior.
- Used PostgreSQL and Redis Testcontainers for realistic integration coverage.
- Verified rate-limit responses and headers.
- Verified Prometheus targets and Grafana provisioning.
- Retained the benchmark script as repeatable performance-test tooling; local
  benchmark output is not presented as production capacity evidence.

## 8. Scenario 3: Ambiguous requirements

### 8.1 Problem

Some requested enterprise features were underspecified. In particular, database
sharding was proposed even though the prototype had one PostgreSQL instance and
no measured evidence of database saturation. Security requirements also did not
state whether the consumers were end users or calling applications.

### 8.2 Decomposition

1. Separate caching from sharding: caching reduces repeated reads; sharding
   distributes durable data and writes across physical databases.
2. Inspect actual access patterns: redirects still write statistics and
   analytics even when URL resolution hits Redis.
3. Identify the consumer model: the assessment API is called by applications,
   while redirect recipients are anonymous users.
4. Define what can be proved locally and what requires production infrastructure.
5. Prefer reversible choices and document non-implemented scale mechanisms.

### 8.3 Execution

- Kept one PostgreSQL source of truth because physical sharding was not justified
  by measured storage/write saturation.
- Removed preliminary sharding-readiness code so the submission does not imply
  that HTTP/JPA traffic is physically sharded when it is not.
- Implemented API-key authentication for application clients rather than adding
  an unrelated end-user JWT login flow.
- Kept redirects public but protected creation, deletion, analytics, and
  operational health with roles and per-client ownership.
- Added V003 as a brownfield ownership migration and added backup/isolated
  restore tooling without claiming production failover or achieved RPO/RTO.

### 8.4 Validation

- Confirmed the runtime contains one configured PostgreSQL data source and no
  false shard router.
- Verified a protected POST returns `401` without a key and `201` with a valid
  bootstrap key.
- Tested invalid/inactive keys, role denials, ownership isolation, and bootstrap
  update behavior.
- Tested V003 by migrating a database with pre-existing Phase 2 rows and checking
  preservation plus new constraints.
- Treated sharding, multi-region failover, and achieved recovery targets as
  future work requiring production measurements and infrastructure.

## 9. Setup instructions

### 9.1 Prerequisites

- Windows with PowerShell
- Docker Desktop with Docker Compose
- Java 17 and Maven 3.9+ for running tests outside the application image

### 9.2 Configure local secrets

Create an uncommitted `.env` in the project root. It must contain:

```dotenv
POSTGRES_DB=urlshortener
POSTGRES_USER=postgres
POSTGRES_PASSWORD=<strong-local-password>
VISITOR_HASH_SALT=<at-least-32-random-bytes>
API_KEY_PEPPER=<different-at-least-32-random-bytes>
APP_BOOTSTRAP_API_KEY=<different-at-least-32-random-bytes>
APP_API_CLIENT_ID=local-assessment-client
APP_API_CLIENT_DISPLAY_NAME=Local assessment client
APP_API_CLIENT_AUTHORITIES=URL_WRITE,ANALYTICS_READ,OPS_READ
APP_API_CLIENT_UPDATE_EXISTING=false
TRUST_FORWARDED_HEADERS=false
TRUSTED_PROXY_ADDRESSES=
TRUST_GEO_HEADERS=false
RATE_LIMIT_ENABLED=true
RATE_LIMIT_CAPACITY=100
RATE_LIMIT_WINDOW=1m
GRAFANA_ADMIN_USER=admin
GRAFANA_ADMIN_PASSWORD=<strong-local-password>
```

The real `.env` is ignored by Git. Docker Compose reads it and passes the same
PostgreSQL username/password to the database and Spring Boot containers. Spring
Boot does not automatically load `.env` when launched directly from VS Code.

### 9.3 Start the complete stack

```powershell
cd "C:\path\to\url-shortener"
docker compose up -d --build
docker compose ps
docker compose logs -f url-shortener
```

### 9.4 Service URLs

| Service | URL |
|---|---|
| Application | `http://localhost:8080` |
| Swagger UI | `http://localhost:8080/swagger-ui.html` |
| Actuator health | `http://localhost:8080/actuator/health` |
| Prometheus targets | `http://localhost:9090/targets` |
| Grafana | `http://localhost:3000` |

All published development ports are bound to `127.0.0.1` in Compose.

### 9.5 End-to-end smoke test

Use the local `APP_BOOTSTRAP_API_KEY` as the Postman `X-API-Key` header.

```http
POST /api/v1/urls/shorten
Content-Type: application/json
X-API-Key: <APP_BOOTSTRAP_API_KEY>

{
  "originalUrl": "https://example.com/assessment",
  "customCode": "manager01"
}
```

Then open `http://localhost:8080/api/v1/urls/manager01` without an API key and
confirm a `302` redirect. Read protected statistics at
`GET /api/v1/analytics/urls/manager01/stats` with the same API key, and finally
deactivate it with `DELETE /api/v1/urls/manager01`.

## 10. Testing approach and evidence

### 10.1 Automated layers

- **Unit tests:** validation, short-code generation, services, transaction
  callbacks, request metadata, API-key hashing/bootstrap/filtering, and error
  mapping.
- **Integration tests:** real PostgreSQL and Redis through Testcontainers,
  complete HTTP security and URL flows, cache behavior, ownership isolation,
  and rate limiting.
- **Migration test:** start at V002 with existing data, apply V003, then verify
  preserved rows plus ownership constraints.
- **Coverage report:** JaCoCo report generated during `mvn clean test`.

### 10.2 Latest local evidence

The final external test run completed:

- 41 tests
- 0 failures
- 0 errors
- 0 skipped
- 85.63% line coverage
- 87.14% instruction coverage
- 65.89% branch coverage

Run the suite with:

```powershell
mvn clean test
```

Docker must be running to execute the Testcontainers tests. A CI environment
should treat unavailable Docker/integration tests as a failed quality gate rather
than accepting a partial unit-only run.

### 10.3 Manual checks

- Protected request without API key -> `401`
- Valid API key with required role -> successful response
- Public redirect without key -> `302`
- Cross-client analytics/deactivation -> non-disclosing `404`
- Redis cache hit and miss counters visible in Prometheus/Grafana
- PostgreSQL rows visible through `docker compose exec postgres psql ...`
- Backup/restore PowerShell scripts parse successfully; a live backup followed
  by a full isolated restore remains an operator-controlled validation step

## 11. Limitations

1. **Single PostgreSQL node:** physical sharding, replicas, automated failover,
   and multi-region routing are not implemented.
2. **Local API-key provisioning:** suitable for application clients in this
   prototype, but there is no self-service key issuance/rotation API or external
   identity provider.
3. **Redis startup dependency:** runtime cache failures have a database fallback,
   but the current Compose startup waits for Redis health.
4. **Rate-limit availability policy:** Redis failures fail open, favoring service
   availability over strict abuse prevention.
5. **Synchronous redirect analytics:** a redirect performs PostgreSQL analytics
   writes; very hot links may require an asynchronous event pipeline.
6. **Cache consistency window:** an invalidation failure can leave stale redirect
   data until its bounded TTL expires.
7. **Collision handling:** database uniqueness prevents duplicate codes, but a
   generated-code insert race should be retried more explicitly at very high
   concurrency.
8. **Performance evidence:** local benchmark results are useful for regression
   comparison, not proof of Internet-scale throughput or latency SLOs.
9. **Recovery scope:** scripts provide logical backup and isolated restore
   mechanics; production needs encryption, immutable off-host storage,
   continuous recovery objectives, and regularly measured drills.
10. **Legacy schema columns:** nullable Phase 1 IP/user-agent columns remain in
    the schema for migration compatibility, although the current analytics write
    path does not populate them.

## 12. Trade-offs

| Decision | Benefit | Cost / accepted risk |
|---|---|---|
| PostgreSQL remains authoritative | Simple consistency and recovery model | Redirect analytics still create database write load |
| Cache-aside Redis with TTL | Faster repeated redirects and safe DB fallback | Stale data is possible until invalidation/TTL |
| After-commit cache actions | Cache never publishes rolled-back DB data | Cache update is not atomic with PostgreSQL commit |
| API keys for application clients | Stateless and operationally simple | Less suitable than OAuth/OIDC for human user identity |
| Public redirects | Links work naturally in browsers/messages | Redirect path must rely on rate limiting and validation |
| Rate limiter fails open | Redis outage does not take down the API | Abuse protection weakens during the outage |
| HMAC visitor identifier | Repeat counting without using raw value as identity | Secret rotation changes continuity of unique-visitor counts |
| No premature sharding | Honest, maintainable prototype | Scale-out database design remains future work |
| Synchronous analytics | Immediate, transactionally visible counts | Added redirect latency and hot-row contention risk |

## 13. Completion statement

The prototype is runnable end-to-end and the three scenarios demonstrate how
work was decomposed, implemented, and validated. Brownfield improvements were
added without replacing the stable greenfield core. Ambiguous requirements were
resolved through evidence and explicit trade-offs: security and recovery controls
were implemented where they were meaningful, while unproven physical sharding
and production recovery claims were deliberately excluded.
