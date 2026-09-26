# commerce-service

```text
io.github.castab:commerce-service:<version>
```

The opinionated, reusable application and runtime layer of the [`commerce`](../README.md)
project. It turns the vocabulary and invariants of
[`commerce-domain`](../domain/README.md) into deployable commerce services: application
operations, explicit transactions, PostgreSQL persistence, HTTP conventions, one error
contract, health checks, configuration, and an explicit composition root.

It is **not** a specific business's backend. Catering, mobile detailing, computer repair,
pet and service appointments, and point of sale should all be able to use it. Anything
that makes sense for only one of them belongs to that application.

> **Status: foundation.** This first iteration establishes the architecture and the
> infrastructure, and shows the full request path with one capability (customers). It
> does not yet orchestrate bookings, financial documents, or payments. See
> [Current limitations](#current-limitations).

## Relationship to commerce-domain

- `commerce-service` depends on `commerce-domain` (as an `api` dependency, at the same
  version); `commerce-domain` never depends on `commerce-service`.
- The service reuses domain types directly. It never duplicates them, and it never
  annotates them for serialization or persistence.
- The service owns relationships that only coordinate independently meaningful domain
  concepts, for example which financial documents belong to a booking. The domain stays
  free of them:

  > A relationship belongs in `commerce-domain` when one domain concept cannot meaningfully
  > express its semantics or invariants without the other concept. Relationships that
  > coordinate otherwise independently meaningful concepts belong to the consuming
  > application/service layer.

- Where the domain already defines a boundary contract (for example the
  `FinancialDocumentHistory` SPI or the staff resolver ports), the service implements that
  contract rather than wrapping it.

## Technology

The stack follows the reference backend (`castab/fionas-ui` `apps/backend`), with versions
declared in [`gradle/libs.versions.toml`](../gradle/libs.versions.toml):

| Concern | Choice |
|---|---|
| Language and runtime | Kotlin 2.4.20 on Java 25 |
| HTTP | http4k 6.58 on Jetty (`JettyLoom`, virtual threads; Jetty keeps Server-Sent Events possible) |
| Serialization | kotlinx.serialization 1.11 through `http4k-format-kotlinx-serialization` |
| Database | PostgreSQL (driver 42.7) through HikariCP 7.1 and JDBI 3.54 |
| Migrations | Flyway 13.3 |
| Configuration | Hoplite 2.9 with HOCON, plus explicit environment overrides |
| Logging | Kotlin Logging 8 on Logback 1.6 |
| Tests | Kotest 6.2, real PostgreSQL through the Docker CLI |
| Formatting | ktlint (the repository's single formatter) |

There is no Spring, Spring Boot, Hibernate, JPA, Micronaut, Quarkus, Ktor, or dependency
injection framework. Everything is wired by ordinary Kotlin in one visible composition
root.

## Architecture

```text
HTTP route            translate: request DTO -> domain values -> operation -> response DTO
  │
  ▼
application operation  orchestration and policy; opens the transaction
  │
  ├── commerce-domain  invariants and legal transitions
  ├── repositories     SQL, inside the caller's transaction
  └── Transactor       one explicit PostgreSQL transaction boundary
```

Routes contain no SQL and no orchestration. Operations contain no HTTP. Repositories
never open their own transactions.

### Packages

| Package | Contents |
|---|---|
| `service.config` | `CommerceServiceConfiguration`: HOCON loading, environment overrides, and validation. |
| `service.persistence` | `createDataSource` (HikariCP), `DatabaseMigrations` (Flyway), `Transactor` and `Transaction`, and PostgreSQL error helpers. |
| `service.application` | `CommerceFailure`, the expected failures of operations, and `validating`. |
| `service.http` | `CommerceJson`, `jsonBody`, the error contract and `CommerceErrorHandling` filter, and health routes. |
| `service.customer` | The representative capability: `CustomerRepository`, `CreateCustomer` / `GetCustomer`, and the customer routes and DTOs. |
| `service.runtime` | The composition root `commerceService(...)`, `ApplicationContributions`, `CommerceRuntime`, and a development `main`. |

Packages for booking, financial, and payment orchestration will appear when they contain
real code, not before.

### Composition root

`commerceService(configuration, contributions)` in
[`CommerceService.kt`](src/main/kotlin/io/github/castab/commerce/service/runtime/CommerceService.kt)
builds, in order:

```text
configuration → DataSource (HikariCP) → Flyway → Jdbi → Transactor + repositories
             → application operations → HTTP routes → error handling → http4k → Jetty
```

An application's own entry point is intended to look like this:

```kotlin
fun main() {
    val service = commerceService(
        configuration = CommerceServiceConfiguration.load(),
        application = ApplicationContributions(
            migrationLocations = listOf("classpath:db/migration"),
            routes = { runtime -> listOf(myApplicationRoutes(runtime.transactor, runtime.customers)) },
        ),
    ).start()
    Runtime.getRuntime().addShutdownHook(Thread { service.close() })
    Thread.currentThread().join()
}
```

`ApplicationContributions` is intentionally small and not booking-specific. An application
contributes its own Flyway locations and its own routes, and those routes receive the
shared `CommerceRuntime` (the `Transactor` and the commerce repositories). This lets an
application's writes and commerce writes share one transaction. The service still owns
the error handling around every route.

## Runtime conventions

### Configuration

`CommerceServiceConfiguration.load()` reads the classpath resource `application.conf` (the
service ships one with every default; an application may ship its own ahead of it) and
then applies exactly these environment variables:

| Setting | Environment variable | Default |
|---|---|---|
| `server.port` | `PORT` | `8080` |
| `database.jdbcUrl` | `DATABASE_JDBC_URL` | required |
| `database.username` | `DATABASE_USERNAME` | required |
| `database.password` | `DATABASE_PASSWORD` | required |
| `database.maximumPoolSize` | `DATABASE_MAXIMUM_POOL_SIZE` | `4` |
| `database.minimumIdle` | `DATABASE_MINIMUM_IDLE` | `1` |
| `database.connectionTimeoutMs` | `DATABASE_CONNECTION_TIMEOUT_MS` | `500` |
| `database.validationTimeoutMs` | `DATABASE_VALIDATION_TIMEOUT_MS` | `1000` |
| `flyway.enabled` | `FLYWAY_ENABLED` | `false` |

Invalid values fail at startup with a message naming the variable. Secrets come only
from the environment. The database password is redacted from the configuration's
`toString`.

### Database and migrations

- Commerce-owned tables live in the PostgreSQL schema **`commerce`**, migrated from
  `classpath:db/commerce` and tracked in `commerce.flyway_schema_history`.
- Application migrations (from `ApplicationContributions.migrationLocations`) run
  afterwards, in their own schema (`public` by default) with their own history table. The
  two sets evolve independently. Application migrations may reference commerce tables but
  must not change them.
- Every SQL statement names the `commerce` schema explicitly. PostgreSQL's default
  `search_path` starts with `"$user"`, so a role named `commerce` would otherwise resolve
  unqualified names into the commerce schema.
- With `FLYWAY_ENABLED=true` the service migrates at startup. Otherwise migrations are run
  separately.

Current commerce tables: `commerce.customers` (id, name, email).

### Transactions

`Transactor.inTransaction { transaction -> ... }` commits when the block returns and rolls
back when it throws, rethrowing the original exception. Repository methods take the
`Transaction` as their first parameter. An operation that coordinates several persistent
concepts does everything inside one `inTransaction` call. For example: payment recorded,
allocation recorded, reconciliation derived, booking policy evaluated, booking and
document transitioned. A nested `inTransaction` call opens a separate transaction, so pass
the existing `Transaction` down instead.

### HTTP and errors

Bodies are JSON (`CommerceJson`): unknown request fields are ignored, defaults are
written, and `null` optionals are omitted. Transport DTOs are `@Serializable` classes in
the service. Routes translate between DTOs and domain values explicitly.

Every error has one shape:

```json
{"code": "validation_failed", "message": "Customer name must not be blank"}
```

| Category | Status | `code` | Raised by |
|---|---|---|---|
| Malformed request | 400 | `malformed_request` | unreadable JSON, missing fields, unparsable path values (http4k `LensFailure`) |
| Validation failure | 422 | `validation_failed` | `CommerceFailure.ValidationFailed`, typically a domain `require` inside `validating { }` |
| Not found | 404 | `not_found` | `CommerceFailure.NotFound`, or no matching route |
| Conflict | 409 | `conflict` | `CommerceFailure.Conflict`, e.g. a unique-key violation |
| Illegal transition | 409 | `illegal_transition` | `CommerceFailure.IllegalTransition` |
| Invariant violation | 422 | `invariant_violated` | `CommerceFailure.InvariantViolated` |
| Internal failure | 500 | `internal_failure` | anything else; logged, never described |

Messages come only from `CommerceFailure`, whose messages are written for callers. SQL
text, stack traces, and exception details of unexpected failures never reach a response.

### Health

- `GET /health`: liveness. Always `200 {"status":"ok"}` while HTTP is served, and
  checks no dependency.
- `GET /ready`: readiness. `200 {"status":"ready"}` when a database connection
  validates, and `503 {"status":"unavailable"}` otherwise.

## Current capabilities

| Endpoint | Behavior |
|---|---|
| `POST /customers` `{"name", "email"}` | Creates a customer. `201` with the customer and `Location`. Invalid values are `422`. |
| `GET /customers/{customerId}` | `200` with the customer, or `404`. |
| `GET /health`, `GET /ready` | See [Health](#health). |

Customers are the representative capability because every known consumer, with or
without bookings, has them. They exercise every layer: route, DTO, domain translation,
operation, transaction, repository, migration, and error mapping.

### Running locally

```bash
docker run --rm -d --name commerce-dev -e POSTGRES_USER=dev -e POSTGRES_PASSWORD=dev -e POSTGRES_DB=commerce -p 5432:5432 postgres:18-alpine
```

```bash
DATABASE_JDBC_URL=jdbc:postgresql://localhost:5432/commerce DATABASE_USERNAME=dev DATABASE_PASSWORD=dev FLYWAY_ENABLED=true ./gradlew :service:run
```

The development entry point (`runtime/DevelopmentServer.kt`) runs the generic service
with no application contributions. It is a verification aid, not a deployment.

## Booking extension direction

Applications must be able to own strongly typed booking details (`CateringBooking`,
`DetailingBooking`, `RepairBooking`, `GroomingBooking`, ...) that are known at compile
time, while reusing the generic booking machinery. That extension seam is deliberately
**not** designed yet. It will be derived from the concrete requirements of the known
consumers. What this module already guarantees:

- No concrete business booking type exists in `commerce-service` or `commerce-domain`,
  and none will.
- Booking details will never be modeled as opaque JSON (`type: String, details: JsonObject`).
  JSONB may later be a *persistence representation* of a strongly typed application
  model. That is a separate decision.
- Booking stays optional. Nothing about customers, financial documents, payments, or
  their HTTP and persistence requires a booking. Point of sale enables no booking
  functionality at all.
- A booking type parameter, if one is introduced, stays confined to booking APIs. It must
  not spread into customer, financial, or payment APIs.

Responsibilities this foundation has identified for the future extension:

1. **Detail schema.** Its own tables, contributed through the existing
   `ApplicationContributions.migrationLocations`, keyed by the domain's `Booking.Id`.
2. **Transactional persistence.** A repository for its details that takes the service's
   `Transaction`, so detail writes commit atomically with the booking identity and
   lifecycle writes. `Transaction` already allows this.
3. **Transport.** A `KSerializer` for its request and response details, since the
   service's routes are generic over the booking but must stay strongly typed.
4. **Validation.** Translation from transport to its detail type, reported through
   `CommerceFailure.ValidationFailed`.
5. **Phase rehydration.** The domain's lifecycle phases are adopter-implemented
   interfaces whose transitions are adopter-owned. The service therefore cannot build
   phase models itself. The extension must turn a stored booking (identity, phase, and
   details) into its phase type, and persist what its transitions return.
6. **Transition policy.** The business authorization of legal edges (for example, whether
   a quote may become booked once a deposit is reconciled). It is evaluated inside the
   same transaction as the facts it depends on.

The booking-to-financial-document association is not an extension concern. The generic
service will own it as an application-level relationship.

## Current limitations

- **No authentication or authorization over HTTP.** The staff principals, roles, and
  permissions exist in `commerce-domain`. Wiring them into requests is a separate
  iteration. Operations are plain classes with explicit inputs, so a principal can be
  added to their commands and checked with `PrincipalId.can` without restructuring. Until
  then, deploy the service only behind a trusted boundary.
- No booking, financial document, payment, refund, or reconciliation orchestration or
  persistence yet. In particular, a JDBI implementation of the domain's
  `FinancialDocumentHistory` SPI is the natural next persistence step.
- No idempotency keys, outbox or events, Server-Sent Events, or scheduled jobs yet.
- No payment provider integration. Providers (e.g. a future `stripe-adapter`) sit
  behind the provider-neutral contract in `commerce-domain`. This module will never
  depend on a provider SDK.
- No deployable packaging. Applications build their own runnable jar (for example with
  the Shadow plugin) and image. This library publishes a plain jar.
- `ApplicationContributions` covers routes and migrations only. Other contribution
  points will be added when a concrete consumer needs them.

## Tests

```bash
./gradlew :service:test
```

The specs cover configuration loading and validation, the error contract, health and
readiness, DTO serialization, Flyway discovery for commerce and application migrations,
transaction commit and rollback across commerce and application tables, the customer
repository, and the complete composition served by Jetty over real HTTP. Database specs
run against a real PostgreSQL 18 that the build starts through the Docker CLI. See
[Building and testing](../README.md#building-and-testing).
