# AGENTS.md

The architectural contract for contributors and coding agents working in this repository.
Read this before changing anything in `src/main`, and before adding a dependency, a
module, or a lifecycle concept.

[`README.md`](README.md) is the adopter-facing introduction. This file explains the rules
and why seemingly reasonable changes can be architecturally wrong.

## Repository mission

`commerce-domain` is a reusable library of immutable commerce domain models and lifecycle
APIs, shared by multiple applications. Each domain lives in its own package beneath
`io.github.castab.commerce` and is independent of the others:

| Domain | Package | Style |
|---|---|---|
| Booking lifecycle | `io.github.castab.commerce.booking.lifecycle` | A type-level protocol. Adopters' own types implement the phases. The library owns no booking data. |
| Financial documents | `io.github.castab.commerce.financial` | Concrete, library-owned immutable value types (`Estimate`, `Quote`, `Invoice`) whose invariants the library enforces. |

The two styles are deliberate and not interchangeable. Read the rules for the domain you
are changing: [Booking lifecycle domain](#booking-lifecycle-domain) and
[Financial document domain](#financial-document-domain). The build, dependency,
toolchain, and publication rules apply to the whole repository.

Domains must not depend on each other. Neither package imports the other. Applications
compose them (for example, a booking `Quote` phase model that holds a
`FinancialDocument.Quote`). The library does not.

For the booking lifecycle:

> The library defines what may legally follow a lifecycle phase. The adopting application
> defines whether, when, and how that transition occurs.

If a proposed change describes data, policy, or activity *around* a booking rather than the
booking lifecycle itself, it probably does not belong in the booking lifecycle API.

## Repository layout

| Path | Contents |
|---|---|
| `src/main/kotlin/io/github/castab/commerce/booking/lifecycle/BookingLifecycle.kt` | The entire booking lifecycle API. |
| `src/main/kotlin/io/github/castab/commerce/financial/` | The financial document API: `FinancialDocument.kt` (the sealed class, its three stages, and change application), `Version.kt`, `Money.kt`, `LineItem.kt`, `ChangeOrder.kt`, `FinancialDocumentReference.kt`, and `FinancialDocumentHistory.kt` (the history SPI and its lookup extensions). |
| `src/test/kotlin/io/github/castab/commerce/booking/lifecycle/BookingLifecycleSpec.kt` | Kotest `FunSpec` for the booking lifecycle contract. |
| `src/test/kotlin/io/github/castab/commerce/booking/lifecycle/fixtures/TestBookingModels.kt` | Test-only "application-owned" booking models. |
| `src/test/kotlin/io/github/castab/commerce/financial/*Spec.kt` | Kotest specs for the financial domain: `FinancialDocumentSpec`, `ChangeOrderSpec`, `FinancialDocumentHistorySpec`, `LineItemSpec`, `MoneySpec`, `VersionSpec`. |
| `src/test/kotlin/io/github/castab/commerce/financial/fixtures/TestFinancialModels.kt` | Test-only money and line item helpers and an in-memory `FinancialDocumentHistory`. |
| `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties` | Single-module build with the Java 25 toolchain and the Maven publication. |
| `gradle/libs.versions.toml` | Version catalog. |
| `.github/workflows/ci.yml` | CI: build and test on Java 25 for pull requests and pushes to `main`. |
| `.github/workflows/publish.yml` | Publish to GitHub Packages when a GitHub Release is published. |
| `README.md`, `AGENTS.md` | Documentation. Keep both in sync with the code. |

Maven coordinates: `io.github.castab:commerce-domain` (the artifactId is `rootProject.name`
in `settings.gradle.kts`), published to the GitHub Packages registry of the repository that
runs the Publish workflow (currently `https://maven.pkg.github.com/castab/booking-lifecycle`).
The artifact was previously published as `io.github.castab:booking-lifecycle`, with the
booking lifecycle in package `io.github.castab.bookinglifecycle`. No aliases are kept for
the old package.

# Booking lifecycle domain

The sections from here through [Anti-patterns](#anti-patterns) govern
`io.github.castab.commerce.booking.lifecycle`.

## Architectural invariants

These are non-negotiable without an explicit decision from the maintainer.

1. **The library owns the lifecycle type hierarchy.**
2. **Consuming applications own the concrete data types inhabiting that hierarchy.**
3. **Application models implement lifecycle phase interfaces directly.**
4. **Transition methods represent legal lifecycle edges.**
5. **Transition implementations belong to the adopting application.**
6. **Business prerequisites for transitions belong to the adopting application.**
7. **Invalid lifecycle edges should generally be absent from the type API rather than
   rejected at runtime.**
8. **Terminal lifecycle outcomes are immutable historical facts.**

The current topology, which code, KDoc, README, and tests must all agree on:

```text
InitialRequest ─ toQuote() ──→ Quote        InitialRequest ─ cancel() → Cancelled
Quote ────────── toBooking() → Booked       Quote ────────── cancel() → Cancelled
Booked ───────── complete() ─→ Completed    Booked ───────── cancel() → Cancelled
Cancelled, Completed: no transitions
Entry points: InitialRequest, Quote
```

## Sealed vs open interfaces

The shape is deliberate:

```text
BookingLifecycle          sealed interface
├── Active                sealed interface
│   ├── InitialRequest    interface (open)
│   ├── Quote             interface (open)
│   └── Booked            interface (open)
└── Terminal              sealed interface
    ├── Cancelled         interface (open)
    └── Completed         interface (open)
```

- **The sealed hierarchy controls the broad lifecycle taxonomy.** Only this module can
  declare direct subtypes of `BookingLifecycle`, `Active`, and `Terminal`, so no adopter
  can invent a sixth phase. `when` over a `BookingLifecycle` or an `Active` is exhaustive
  without `else`, and the tests rely on that (`classify` and `describe` in
  `BookingLifecycleSpec`). Do not remove sealing from these three without understanding
  and replacing what it provides.
- **The concrete phase interfaces stay open so that external application models can
  implement them.** Kotlin restricts only *direct* subtypes of a sealed type to the
  declaring module. Do not make `InitialRequest`, `Quote`, `Booked`, `Cancelled`, or
  `Completed` sealed, or otherwise prevent adopters in other modules from implementing
  them. That would destroy the library's purpose.

Known limit: the type system does not stop one class from implementing two phases (for
example `Quote` and `Booked`). Phases are intended to be mutually exclusive, and
documentation tells adopters to implement exactly one. Don't add runtime checks for this.
If enforcement is ever wanted, treat it as an architectural decision.

## Transition design

**Transition methods are the state machine.** The legal graph is encoded entirely by
which abstract functions each phase interface declares.

- Do not introduce a generic `StateMachine`, `TransitionEngine`, `TransitionValidator`,
  `transition(...)`, `canTransitionTo(...)`, or `allowedTransitions(...)` that merely
  restates what the interface graph already encodes. Add a runtime transition mechanism
  only if a future requirement gives it value beyond the compile-time topology, and only
  after an architectural decision.
- Every transition function stays **abstract**. Never add a default implementation. The
  library knows `Quote → Booked` is legal. It cannot know how an adopter's quote becomes
  that adopter's booking.
- Transition return types are the protocol types (`Booked`, `Terminal.Cancelled`, ...).
  Adopters narrow them with **covariant overrides** (`override fun toBooking():
  CateringBooking`). Preserve this. Do not add generics, self-type parameters, or wrapper
  return types (`Result<Booked>`, `Transition<Booked>`) that break or complicate it.
- Every active phase declares its own `cancel()`. There is intentionally no shared
  `cancel()` on `Active`. See [Open questions](#open-questions).

## Transition policy boundary

Distinguish a **legal transition** from a **business-authorized transition**.

- `Quote → Booked` is *legal* according to this library.
- Whether a particular quote may become booked, because a deposit was paid, a contract
  was signed, or a manager approved, is *business authorization*, and the adopter owns it.

Do not add generic payment, approval, acceptance, availability, actor, or timestamp
concepts to the core library to solve an application-specific requirement. Do not add
`TransitionContext`, `TransitionPolicy`, `TransitionCondition`, or `TransitionGuard`.
Adopters express policy inside their own transition implementations, or before calling
them.

## Application data boundary

Booking lifecycle interfaces declare transitions only, with no properties and no data.

Avoid introducing types or fields such as these into the booking lifecycle:

```text
Customer  Money  Invoice  QuoteData  Payment  Deposit  Actor  Event  Selection
Complaint  Refund  CancellationReason  bookingId  createdAt  version
```

Add one only if a future architectural decision proves it is a lifecycle concept rather
than adopter data. `Money` and `Invoice` now exist in the financial package. That does not
make them booking lifecycle concepts: never reference financial types from
`BookingLifecycle`. Booking identity in particular (how a quote and its booking are known
to be the same booking) is currently application-owned. See [Open questions](#open-questions).

## New lifecycle phase checklist

Before adding a lifecycle phase or changing an edge, answer all of these:

1. Does this represent a genuinely distinct phase of the booking lifecycle?
2. Is it mutually exclusive with existing lifecycle phases?
3. Does it alter the legal transition graph?
4. Would unrelated booking applications recognize this phase?
5. Can a booking meaningfully *remain* in this phase?
6. Is this actually a payment, support, accounting, fulfillment, quote-revision, or
   invoice concern?
7. Could this concept instead be modeled by an application-owned type or an orthogonal
   state machine?

If question 6 or 7 suggests another domain owns the concept, do **not** add it to
`BookingLifecycle`. `DepositPaid`, `InvoiceSent`, `QuoteRevised`, `Refunded`, and
`Disputed` all fail this checklist.

## Terminal phase rules

`Cancelled` and `Completed` are terminal historical outcomes. `Cancelled` means the
lifecycle ended without fulfillment. `Completed` means the booked service or event was
fulfilled.

- Do not add outgoing lifecycle transitions (`reopen()`, `refund()`, `revert()`, ...) to
  terminal interfaces without an explicit redesign of lifecycle semantics.
- Do not add dummy members to terminal interfaces for symmetry. The "protocol shape"
  tests assert that terminal interfaces declare no methods.
- Post-completion activity such as a refund, complaint, chargeback, credit, or
  corrective service does not reopen or rewrite the booking lifecycle. It belongs to
  application-owned processes that *reference* the booking. `Completed` plus a refund and
  `Cancelled` plus a refund are different historical facts, and both remain expressible
  without new lifecycle phases.

## Anti-patterns

**State-property replacement.** Do not replace the type-level protocol with:

```kotlin
enum class BookingState { INITIAL_REQUEST, QUOTE, BOOKED, CANCELLED, COMPLETED }

data class Booking(val state: BookingState /* ... */)
```

**Mutable lifecycle state.** Do not model progression as `var state: BookingState`. The
intended model transforms one lifecycle-typed application model into another.

**Universal transition engine.** Do not make `transition(from, to)` the primary lifecycle
API when interface methods already express legal transitions.

**Application data in lifecycle interfaces.**

```kotlin
interface Quote : Active {
    val customer: Customer   // wrong: adopter data
    val total: Money         // wrong: adopter data
}
```

**Financial or support states as booking phases.** `Refunded`, `PartiallyRefunded`,
`DepositPaid`, `Chargeback`, `Disputed`, `ComplaintOpened`, `CompletedRefunded`.

**Revisions as phases.** `QuoteRevised`, `InvoiceSent`, `InvoiceRevised`.

**Runtime emulation of illegal edges.** Do not add `fun complete(): Nothing = throw ...`
to `InitialRequest`, or similar. An illegal edge must have no method at all.

# Financial document domain

These rules govern `io.github.castab.commerce.financial`. A `FinancialDocument` is one
immutable snapshot of a commercial document. The domain describes what is charged and how
that description evolves. It does not describe settlement.

## Financial invariants

These are non-negotiable without an explicit decision from the maintainer.

1. **The stage is the sealed subtype.** `FinancialDocument` is a sealed class with exactly
   `Estimate`, `Quote`, and `Invoice`. There is no stage enum or mutable stage/type
   property driving behavior. `when` over a document is exhaustive.
2. **Snapshots are immutable.** Nothing modifies a snapshot. Every change order and every
   transition returns a new snapshot.
3. **A lineage keeps one `UUID`.** Successors share the source's `id`, are at
   `version.next()`, and have `previousVersion == source.version`. A lineage starts at
   `Version.INITIAL` with `previousVersion == null`. Consequently `previousVersion` is
   always the version immediately preceding `version`, and the base class checks this.
4. **Lineages may start at any stage.** `Estimate.create`, `Quote.create`, and
   `Invoice.create` are all first-class entry points. Direct invoice creation (point of
   sale) is not a workaround.
5. **Transitions move one stage forward.** `Estimate.toQuote()` and `Quote.toInvoice()`
   only. No `Estimate.toInvoice()`, no reverse transitions, and nothing leaves `Invoice`
   except its own change orders. Illegal transitions have no method.
6. **A change order never changes the stage.** Each stage's `changeOrder` returns its own
   type.
7. **Change orders are ordered and atomic.** Changes apply in list order to a working copy.
   The successor is constructed only after every change succeeds, so a failure yields no
   snapshot at all.
8. **Totals are derived.** `subtotal`, `taxAmount`, and `total` are calculated from line
   items. No API accepts them.
9. **One currency per document.** `Money` never converts or rounds. Mixed currencies are
   rejected in `Money` arithmetic, within a `LineItem`, and within a document.
10. **History is referenced, never embedded.** A snapshot holds no other snapshot and no
    reference object to one. History is reached only through `FinancialDocumentHistory`,
    one explicit lookup at a time.

The topology, which code, KDoc, README, and tests must all agree on:

```text
Estimate ─ changeOrder() → Estimate     Estimate ─ toQuote() ──→ Quote
Quote ──── changeOrder() → Quote        Quote ──── toInvoice() → Invoice
Invoice ── changeOrder() → Invoice
Entry points: Estimate.create, Quote.create, Invoice.create
```

## Construction and forgery

- Stage constructors are **private**. Do not make them `internal`, `protected`, or public:
  `internal` constructors are public in bytecode and callable from Java.
- Cross-stage successors are built through `@JvmSynthetic internal` companion functions
  (`Quote.successorOf`, `Invoice.successorOf`), invisible to Java and to other modules.
- The stages are **not** data classes. A `copy()` would let callers forge versions,
  previous-version links, or stages. Equality, `hashCode`, and `toString` are implemented
  once, finally, on `FinancialDocument`.
- `restore(id, version, lineItems)` exists on each stage only so persistence adapters and
  `FinancialDocumentHistory` implementations can rebuild stored snapshots. It derives
  `previousVersion` from `version` and cannot express any other link. Do not add
  parameters that let callers choose `previousVersion`, totals, or anything else derived.
- `Version` has a private constructor. `Version.of(n)` rejects `n < 1`. Don't add public
  arithmetic beyond `next()`.

## Values and collections

- Every identifier is a `java.util.UUID`. Do not add id wrapper types (`FinancialDocumentId`,
  `LineItemId`, ...). Do not add an id to `ChangeOrder` for symmetry. `Version` is a domain
  value, not an identifier.
- Money is `BigDecimal` plus `java.util.Currency`. Never `Double` or `Float`. Don't add
  rounding, scale normalization, currency conversion, or exchange rates.
- `LineItem.quantity == null` means flat-priced (subtotal = price). Otherwise
  subtotal = price × quantity. `price` excludes tax, and `taxAmount` is the final tax for
  the line. Do not rename it to `taxableAmount`, and do not turn it into a rate.
- `LineItem`, `Money`, `FinancialDocumentReference`, and the `ChangeOrder.Change` types are
  data classes, because `copy()` on them cannot break an invariant: every copy re-runs
  validation. Keep validation in `init` blocks so this stays true.
- Collections received from callers are copied into unmodifiable lists
  (`toImmutableList()`), so neither the caller's list nor a cast to `MutableList` can change
  a snapshot or a change order.
- Change orders replace whole line items. Do not add field-level patch semantics or
  nullable "unchanged" markers.

## Persistence and concurrency boundary

- `FinancialDocumentHistory` is the only history access point. It is an SPI implemented by
  applications. The library must never ship an implementation tied to a database, and
  must never load previous versions implicitly or recursively.
- `retrievePreviousVersion` performs zero lookups for version 1 and exactly one lookup
  otherwise. `retrieveVersion` performs exactly one lookup. `retrieveLatestVersion`
  delegates by `id`. Keep these guarantees, and the tests that verify them with MockK.
- Do not add locks, transactions, global registries, caches, or static mutable state.
  Concurrent successors of the same snapshot are resolved by the persistence layer's
  uniqueness or optimistic-concurrency check on `(id, version)`.

## Out of scope for financial documents

Never add payment or settlement concepts to any financial type: `amountPaid`, `balance`,
`balanceDue`, `remainingBalance`, `paymentStatus`, `paymentMethod`, `paymentIntent(Id)`,
`transactionId`, `refundAmount`, `paidAt`, `partiallyPaid`, `overdue`, payment history,
payment processors (Stripe, Square, PayPal), or accounting ledgers. Payments are another
bounded context that references a document by `FinancialDocumentReference`.

Also out of scope: pricing rules, tax calculation, discount engines, customer or
counterparty models, dates and due dates, document numbering, and serialization
annotations. These are application data unless an architectural decision says otherwise.

## Financial anti-patterns

```kotlin
enum class FinancialDocumentType { ESTIMATE, QUOTE, INVOICE }   // wrong: the subtype is the stage
data class FinancialDocument(val type: FinancialDocumentType, ...)

class Invoice(...) { var version: Version }                     // wrong: snapshots are immutable
class Quote(val previous: Quote?)                               // wrong: embeds history recursively
fun Estimate.toInvoice(): Invoice                               // wrong: an illegal edge
Invoice.create(id, lineItems, total = ...)                      // wrong: totals are derived
val balanceDue: Money                                           // wrong: payments are out of scope
```

# Repository-wide rules

These sections apply to every domain and to the build.


## Persistence boundary

There is no persistence in this repository. `FinancialDocumentHistory` is an SPI that
applications implement, not a persistence layer. Do not add persistence incidentally while
solving unrelated tasks.

When persistence eventually arrives:

- keep it in a separate module, so the core stays persistence-agnostic;
- do not add SQL, document, or ORM concerns (annotations, surrogate keys, column names,
  optimistic-lock columns) to domain types. The financial `UUID` id and `Version` are
  domain concepts, not persistence concerns, and stay as they are;
- do not force adopter business models into library-owned persistence models;
- do not require an ORM, and keep low-level adapters such as JDBI possible;
- let applications own transaction boundaries where appropriate.

## Dependency policy

The published `main` dependency surface is `kotlin-stdlib` only. Keep it that way.

Do not add these to core:

```text
Spring  Ktor  Hibernate  JPA  JDBI  MongoDB drivers  Jackson  kotlinx.serialization
NATS  Kafka
```

Put optional integrations in separate modules if and when they are justified. Test-only
dependencies (Kotest, MockK) belong in `testImplementation` and must never leak into the
runtime or API dependencies. Check with:

```bash
./gradlew dependencies --configuration runtimeClasspath
```

No preview, EAP, milestone, RC, snapshot, or nightly dependencies. Never add a runtime
dependency just to support CI or publishing.

## Toolchain rules

- **Java 25 is a hard requirement.** The Java toolchain, Kotlin `jvmTarget`, and
  `JavaCompile.release` are all 25, and tests run on the Java 25 toolchain. Toolchain
  auto-download is disabled (`gradle.properties`), and no foojay resolver is applied, so a
  missing Java 25 fails the build. Do not lower any of these to accommodate a tool or a
  consumer.
- Versions: Kotlin 2.4.20, Kotest 6.2.5, MockK 1.14.11 (`gradle/libs.versions.toml`).
  The Gradle wrapper is 9.7.0, the newest Gradle that Kotlin 2.4.20 declares full
  compatibility with, and its distribution checksum is pinned. Do not bump Gradle beyond
  what the Kotlin Gradle plugin officially supports.
- MockK 1.14.11 currently resolves Byte Buddy 1.18.2 and runs on Java 25 without an
  override. If a future upgrade breaks MockK on Java 25 because of Byte Buddy, add a
  **test-scoped** constraint for `net.bytebuddy:byte-buddy` and
  `net.bytebuddy:byte-buddy-agent`, with a comment in the build file explaining why. Do not
  replace MockK and do not lower Java.

## CI and publication

- **Java 25 in CI.** Both workflows use Temurin 25 through `actions/setup-java`. Do not
  add a matrix with older JDKs or lower the version to get CI green.
- **The Gradle Wrapper is authoritative.** Workflows run `./gradlew`, and
  `gradle/actions/setup-gradle` provides caching and wrapper validation. Do not install
  another Gradle, and do not add competing cache steps.
- **CI must pass before publication.** `ci.yml` runs `./gradlew clean build
  --no-build-cache` with `contents: read` only. It can never publish. `publish.yml` runs
  the same verification, and publishes only if it succeeds. Never add
  `continue-on-error`, skip tests, or reorder these steps.
- **Releases go to GitHub Packages, triggered only by a published GitHub Release.** Do not
  publish from pushes, pull requests, or schedules. `publish.yml` is the only workflow
  with `packages: write`. Do not grant `contents: write`, `id-token: write`, or other
  scopes unless a new requirement truly needs them.
- **Maven versions derive from release tags.** A tag `vX.Y.Z[-prerelease]` becomes Maven
  version `X.Y.Z[-prerelease]`, passed to Gradle as the `version` project property
  (`ORG_GRADLE_PROJECT_version`). The build script's default, `0.0.0-SNAPSHOT`, is for
  local builds. Never write release versions into `build.gradle.kts`,
  `gradle.properties`, or the workflow files.
- **Published versions are immutable.** Never design for overwriting a released version.
  A fix is released as the next version.
- **Credentials are never committed.** Publishing uses the workflow's `GITHUB_TOKEN`,
  passed as the `GitHubPackagesUsername` and `GitHubPackagesPassword` Gradle properties
  through `credentials(PasswordCredentials::class)`. Local `build`, `test`, and
  `publishToMavenLocal` must keep working without any GitHub credentials. Never put
  tokens in repository files. Consumers keep theirs in `~/.gradle/gradle.properties`.
- **Published artifacts:** the main jar, a sources jar, a javadoc jar (empty for now,
  because the sources are Kotlin-only and Dokka is not used), the POM, and Gradle module
  metadata. The POM declares the repository's license (Apache-2.0, see `LICENSE`). Keep
  the two in sync.
- **Test-only dependencies must not leak into the published library.** Check the
  generated POM or `runtimeClasspath` after dependency changes.
- **Routine feature work should not modify publication behavior.** Keep
  `publishing { }` and the workflows unchanged unless the task is about them.
- **Maven Central, if added later, is an additional publishing target.** Add a second
  repository or workflow step. Do not replace or break GitHub Packages for existing
  consumers, and do not add PGP signing for GitHub Packages alone.

## Kotlin design rules

- Booking lifecycle phases are **interfaces**, not classes, enums, or sealed data carriers.
- In the booking lifecycle, seal the taxonomy (`BookingLifecycle`, `Active`, `Terminal`)
  and never seal the phases. Keep transition functions abstract, with covariant-friendly
  return types, and keep terminal interfaces transition-free.
- Financial document stages are final classes of a sealed class, with private
  constructors, because the library owns their invariants.
- Prefer compile-time topology over runtime string or enum state validation.
- Keep the public API small. Each domain should be readable in minutes. Don't add
  abstraction layers, reflection, classpath scanning, service locators, dependency
  injection, or coroutines to the main source set.
- Keep Java callers in mind: `@JvmStatic` on companion factories, `@JvmField` on
  constants, `@JvmSynthetic` on internal helpers that must not be callable from Java.
- The build enables **`explicitApi()`**. Every public declaration needs an explicit
  visibility modifier (`public`) and, in practice, KDoc.
- KDoc describes semantics: what a phase means, whether it is active or terminal, which
  edges are legal, and that implementations are application-owned. It does not describe
  implementation trivia or business policy. Never write something like "called after a
  deposit is paid".
- Invalid arguments fail with `require` (`IllegalArgumentException`). A broken SPI
  contract, such as a history returning the wrong snapshot, fails with `check`
  (`IllegalStateException`).
- Code style: `kotlin.code.style=official`.

## Testing expectations

- Stack: Kotest 6.2.5 `FunSpec` with Kotest assertions on the JUnit Platform, plus MockK
  1.14.11. Do not use JUnit assertion APIs.
- Use concrete, test-owned fixtures in `fixtures/TestBookingModels.kt` to show lifecycle
  semantics. Mocks are for showing that the protocol is mockable. Keep at least one real
  MockK test that creates, stubs, calls, and verifies a mock. Don't rely on mocks alone.
- Changes to lifecycle semantics must come with tests showing adopter-owned concrete
  implementations, legal transition paths, cancellation paths, phase classification
  (including exhaustive `when`), covariant transition returns, terminal behavior, and
  mocking compatibility where relevant.
- The "protocol shape" tests use reflection to assert the exact set of transitions on each
  phase, that every transition is abstract, and that terminal interfaces declare nothing.
  Update them deliberately when the topology changes. Never loosen them to make a change pass.
- Do not add a compile-testing library to prove that illegal calls fail to compile.
  Illegal calls are documented in the comment at the top of `BookingLifecycleSpec.kt` and
  in the README.
- Financial tests use real `Money`, `LineItem`, and `ChangeOrder` values. Never mock
  value objects. MockK is for collaborators, chiefly `FinancialDocumentHistory`, where
  the tests verify exactly which lookups happen. Changes to financial semantics must
  come with tests covering creation at every stage, versioning, lifecycle typing,
  immutability (including defensive copies), line and document calculations, currency
  rejection, change-order success and atomic failure, and history lookups.
- `FinancialDocumentSpec` uses reflection to assert each stage's exact public operations,
  that no stage has a callable constructor, and that snapshots hold no reference to other
  snapshots. Update these deliberately. Never loosen them to make a change pass.
- Tests must run on Java 25. Never lower the test runtime to get tests passing.

Run:

```bash
./gradlew clean test
```

```powershell
.\gradlew.bat clean test
```

The build cache is on. Add `--no-build-cache` to force the tests to actually run.

## Documentation synchronization

Any change to lifecycle topology or semantics must update, in the same change:

- KDoc in `BookingLifecycle.kt` or in the financial sources;
- the README: the relevant Mermaid diagram, transition tree, phase or stage table, API
  listing, examples, and compile-error list;
- this file: the topology blocks, invariants, and rules;
- the tests.

Code and documentation must never disagree about legal lifecycle edges or Maven
coordinates. In the booking lifecycle, use the term **phase**. In the financial domain,
use **stage** (`Estimate`, `Quote`, `Invoice`) and **snapshot** (one immutable version).
Reserve "state" for the rejected state-property design and for the phrase "the transition
methods are the state machine".

## Scope discipline

When solving a focused issue, do not opportunistically add persistence, serialization,
payment logic, customer models, workflow engines, generic transition contexts, event
buses, new domains, or new modules unless the requested work requires them. Do not couple
the booking lifecycle and the financial documents. Prefer narrow architectural evolution.
Do not split the project into `commerce-domain-persistence`, `commerce-domain-jdbi`, and
similar modules until that work is requested.

## Decision heuristics

Before changing the core, ask:

- Does this describe the booking lifecycle itself, or activity around a booking?
- Is this a lifecycle phase or application data?
- Is this a legal transition edge, or a business rule controlling that edge?
- Can an adopter own this concern without weakening the shared lifecycle protocol?
- Would multiple unrelated booking systems reasonably agree on this concept?
- Can the type system express this naturally without introducing a runtime framework?

If the answers point away from the lifecycle itself, the change belongs in adopter code
or in a future, separate module.

## Open questions

These are intentionally unresolved. Do not settle them incidentally.

- **Booking identity.** The protocol does not say how an `InitialRequest`, its `Quote`, and
  its `Booked` model are known to be the same booking. Adopters currently carry their own
  identifier. A future persistence module will likely need an answer. Decide it
  explicitly, and don't bolt an `id` property onto the phase interfaces.
- **Phase exclusivity enforcement.** One class can currently implement several phases.
  Whether to enforce exclusivity at the type level is undecided.
- **Booking and financial coupling.** The two domains are deliberately independent.
  Whether the library should ever offer a bridge between booking phases and financial
  documents is undecided. Don't add one incidentally.
- **Financial document numbering, dates, and counterparties.** Human-facing document
  numbers, issue and due dates, and customer references are application data today.
- **A shared `Active.cancel()`.** All active phases can be cancelled, but `cancel()` is
  declared per phase. Code holding only an `Active` must use `when` to cancel. Hoisting
  `cancel()` to `Active` would change the public API shape, so leave that for a deliberate
  decision.
