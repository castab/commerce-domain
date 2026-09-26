# commerce

[![CI](https://github.com/castab/commerce/actions/workflows/ci.yml/badge.svg)](https://github.com/castab/commerce/actions/workflows/ci.yml)

A Kotlin/JVM commerce toolkit for building service businesses: catering, mobile auto
detailing, computer repair, pet and service appointments, general point of sale, and the
next ones. It has two independently consumable modules:

```text
commerce
│
├── commerce-domain        (Gradle project :domain)
│   Pure Kotlin commerce vocabulary and invariants.
│   Depends on kotlin-stdlib only. Can be consumed independently.
│
└── commerce-service       (Gradle project :service)
    Opinionated reusable commerce application/runtime layer:
    http4k on Jetty, PostgreSQL through HikariCP, JDBI, and Flyway,
    kotlinx.serialization, Hoplite configuration, explicit composition.
    Depends on commerce-domain.
```

| Artifact | Coordinates | Documentation |
|---|---|---|
| commerce-domain | `io.github.castab:commerce-domain:<version>` | [domain/README.md](domain/README.md) |
| commerce-service | `io.github.castab:commerce-service:<version>` | [service/README.md](service/README.md) |

> **Requires Java 25.** Both artifacts are compiled to Java 25 bytecode, tested on Java 25,
> and require a Java 25 or newer runtime.

## Why two modules

The two modules keep durable commerce concepts apart from the machinery that deploys
them:

1. **Domain.** `commerce-domain` holds the concepts and their invariants: customers,
   bookings and the booking lifecycle, estimates, quotes, and invoices, payments,
   allocations, refunds, reconciliation, the provider-neutral payment adapter contract,
   and principals, roles, and permissions. It knows nothing about HTTP, databases,
   serialization, or frameworks.
2. **Service.** `commerce-service` is reusable application and runtime machinery that
   composes those concepts into deployable commerce systems: application operations,
   transactions, PostgreSQL persistence, HTTP conventions, errors, health, and
   configuration.

```text
                         commerce
                            │
              ┌─────────────┴─────────────┐
              │                           │
              ▼                           ▼
           domain                       service
              │                           │
     commerce concepts            application/runtime
     and invariants                  orchestration
              │                           │
              │                    HTTP + persistence
              │                           │
              └───────────────◄───────────┘
                        service depends
                          on domain
```

The dependency points one way: `:service` → `:domain`, never the reverse. The build
enforces it. `:domain`'s `check` fails if its runtime classpath ever contains anything
beyond `kotlin-stdlib`.

## Intended usage

```text
Payment adapter (e.g. a future stripe-adapter)
  └── commerce-domain

Point-of-sale application
  └── commerce-service
       └── commerce-domain

Catering application
  └── commerce-service
       ├── commerce-domain
       └── application-specific booking extension (owned by the application)

Detailing application
  └── commerce-service
       ├── commerce-domain
       └── application-specific booking extension (owned by the application)
```

- **An adapter** that only needs the shared vocabulary, such as the provider-neutral
  payment contract, depends on `commerce-domain` alone. It never picks up http4k, Jetty,
  JDBI, HikariCP, PostgreSQL, Flyway, or Hoplite.
- **An application** depends on `commerce-service`. It writes a small `main` that loads
  configuration and calls `commerceService(configuration, contributions)`, adding its own
  routes and migrations. It does not fork or copy the service.
- **Booking is optional.** Booking is one commerce capability, not the root of commerce. A
  point-of-sale application uses customers, invoices, payments, allocations, refunds, and
  reconciliation without ever creating a booking.

`commerce-service` does **not** define `CateringBooking`, `DetailingBooking`,
`RepairBooking`, `GroomingBooking`, or any other business-specific booking model. Neither
module will. Those types belong to the applications that need them. The next design step
is a strongly typed, compile-time **booking extension seam** that lets each application
supply its own booking details while reusing the generic machinery. See
[service/README.md](service/README.md#booking-extension-direction).

## Installation

Releases are published to **GitHub Packages**:

| | |
|---|---|
| Repository | `https://maven.pkg.github.com/castab/commerce` |
| Versions | [GitHub Releases](https://github.com/castab/commerce/releases). Both artifacts share one version: a release tagged `v0.1.0` publishes `commerce-domain:0.1.0` and `commerce-service:0.1.0`. |

```kotlin
repositories {
    mavenCentral()
    maven {
        url = uri("https://maven.pkg.github.com/castab/commerce")
        credentials {
            username = providers.gradleProperty("gpr.user").orNull ?: System.getenv("GITHUB_ACTOR")
            password = providers.gradleProperty("gpr.key").orNull ?: System.getenv("GITHUB_TOKEN")
        }
        content {
            includeGroup("io.github.castab")
        }
    }
}

dependencies {
    // Either the domain alone...
    implementation("io.github.castab:commerce-domain:0.1.0")
    // ...or the service, which brings the same version of commerce-domain with it.
    implementation("io.github.castab:commerce-service:0.1.0")
}
```

GitHub Packages requires authentication even to download public packages. See
[Authentication](domain/README.md#authentication-is-required-even-for-public-packages).

To build against an unreleased checkout, include it as a composite build. Gradle matches
included projects by project name (`domain`, `service`), not by artifactId, so map the
coordinates explicitly:

```kotlin
// settings.gradle.kts of the consuming build
includeBuild("../commerce") {
    dependencySubstitution {
        substitute(module("io.github.castab:commerce-domain")).using(project(":domain"))
        substitute(module("io.github.castab:commerce-service")).using(project(":service"))
    }
}
```

## Repository layout

```text
commerce/
├── settings.gradle.kts       rootProject "commerce"; include("domain", "service")
├── build.gradle.kts          shared conventions: Java 25, Kotlin, ktlint, tests, publishing
├── gradle.properties
├── gradle/libs.versions.toml all versions, for both modules
├── domain/                   commerce-domain
│   ├── build.gradle.kts
│   ├── README.md
│   └── src/{main,test}/kotlin/io/github/castab/commerce/...
└── service/                  commerce-service
    ├── build.gradle.kts
    ├── README.md
    └── src/{main,test}/{kotlin,resources}
```

Package names are stable across the restructuring. The domain stays in
`io.github.castab.commerce.*` and the service lives in `io.github.castab.commerce.service.*`.

## Requirements

| | Version | Notes |
|---|---|---|
| Java | **25** | Hard requirement for building, testing, and running both modules. Toolchain auto-download is disabled, so a missing JDK 25 fails the build. |
| Kotlin | 2.4.20 | |
| Gradle | 9.7.0 | Pinned through the wrapper (with checksum): the newest Gradle that Kotlin 2.4.20 declares full support for. |
| Docker | any recent | Only for `:service` tests, which start a throwaway PostgreSQL 18 container. Not needed to build or test `:domain`. |

Versions are declared in [`gradle/libs.versions.toml`](gradle/libs.versions.toml).

## Building and testing

```bash
./gradlew clean build
```

On Windows:

```powershell
.\gradlew.bat clean build
```

`build` compiles both modules, runs every test suite and the ktlint checks, verifies the
domain's dependency boundary, and assembles the main, sources, and javadoc jars of both
artifacts. It publishes nothing and needs no GitHub credentials. Local builds use the
version `0.0.0-SNAPSHOT`.

Module-specific commands:

| Command | What it does |
|---|---|
| `./gradlew :domain:build` | Builds and tests `commerce-domain` alone. No Docker, no database. |
| `./gradlew :service:test` | Runs the service specs against real PostgreSQL (see below). |
| `./gradlew ktlintCheck` | Checks Kotlin sources and Gradle Kotlin scripts of every project. |
| `./gradlew ktlintFormat` | Formats them. |
| `./gradlew :domain:dependencies --configuration runtimeClasspath` | Shows that the domain resolves `kotlin-stdlib` only. |

**Service tests and PostgreSQL.** The first `:service` test run starts a
`postgres:18-alpine` container through the plain Docker CLI, then removes it when the build
ends, even if tests fail. Each database spec creates its own database and applies the real
Flyway migrations. There is no H2, no Testcontainers, and no separate test schema. To use
an existing PostgreSQL server instead, set `TEST_DATABASE_JDBC_URL`,
`TEST_DATABASE_USERNAME`, and `TEST_DATABASE_PASSWORD` (the user must be allowed to
`CREATE DATABASE`).

**Formatting.** One formatter covers the whole repository: the
[ktlint-gradle](https://github.com/JLLeitschuh/ktlint-gradle) plugin 14.2.0 with the root
`.editorconfig` (ktlint official style, four-space indentation). `compileKotlin` formats
main sources first; in `build`, lint checks run before that formatting.

The build cache is enabled. To force tests to run again, add `--no-build-cache` (or use
`--rerun`).

The [CI workflow](.github/workflows/ci.yml) runs on Java 25 (Temurin) for every pull
request and every push to `main`. Its steps are ktlint, domain tests, service tests, and
then the full build.

## Releasing

A GitHub Release is the only point where versions are published, and one release
publishes both artifacts at the same version.

1. Merge the desired changes to `main` and confirm CI is green.
2. Create a GitHub Release with a new tag of the form `vMAJOR.MINOR.PATCH`, for example
   `v0.1.0`. Prerelease suffixes such as `v0.2.0-alpha.1` are also accepted.
3. Publishing the release triggers the [Publish workflow](.github/workflows/publish.yml).
   It validates the tag and runs `./gradlew clean build` on Java 25.
4. If every check passes, the workflow publishes `commerce-domain` and `commerce-service`
   at the version without the `v` to GitHub Packages. The published `commerce-service`
   POM depends on `commerce-domain` at that same version. If the tag is malformed or any
   check fails, nothing is published.

The workflow rejects tags that don't match the format: `0.0.1` (no `v`), `v0.1`,
`v01.0.0`, build metadata such as `v1.0.0+build.5`, and `SNAPSHOT` versions. No release
version is ever written into source-controlled files.

**Published versions are immutable.** Never try to overwrite a published version. If
`0.1.0` has a problem, fix it and release `0.1.1`. If a Publish run fails before uploading
anything, use **Re-run jobs** on that run.

## Contributing

The architectural rules are in [`AGENTS.md`](AGENTS.md): module boundaries, domain
invariants, service conventions, and build and publication rules. Read it before changing
either module.

## License

Licensed under the [Apache License, Version 2.0](LICENSE).
