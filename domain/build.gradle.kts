// commerce-domain: pure commerce vocabulary and invariants.
//
// The published runtime dependency surface of this module is kotlin-stdlib only.
// It must never depend on :service or on any HTTP, persistence, serialization,
// configuration, or logging library. Shared toolchain, test, lint, and publishing
// conventions come from the root build.gradle.kts.
plugins {
    `java-library`
    `maven-publish`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
}

base {
    archivesName = "commerce-domain"
}

dependencies {
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.mockk)
}

// Protects the module boundary: an adapter that depends only on commerce-domain must
// never acquire service infrastructure (http4k, Jetty, JDBI, HikariCP, PostgreSQL,
// Flyway, Hoplite, logging, serialization) or :service transitively. `check` fails if
// the resolved runtime classpath contains anything beyond kotlin-stdlib and its own
// annotations dependency.
val verifyRuntimeDependencies =
    tasks.register("verifyRuntimeDependencies") {
        group = "verification"
        description = "Verifies that commerce-domain's runtime dependencies are kotlin-stdlib only."
        val allowed = setOf("org.jetbrains.kotlin:kotlin-stdlib", "org.jetbrains:annotations")
        val root = configurations.runtimeClasspath.flatMap { it.incoming.resolutionResult.rootComponent }
        doLast {
            val found = mutableSetOf<String>()
            val pending = ArrayDeque(listOf(root.get()))
            while (pending.isNotEmpty()) {
                pending
                    .removeFirst()
                    .dependencies
                    .filterIsInstance<ResolvedDependencyResult>()
                    .forEach { dependency ->
                        val key =
                            when (val id = dependency.selected.id) {
                                is ModuleComponentIdentifier -> "${id.group}:${id.module}"
                                is ProjectComponentIdentifier -> "project ${id.projectPath}"
                                else -> id.displayName
                            }
                        if (found.add(key)) pending.add(dependency.selected)
                    }
            }
            val unexpected = found - allowed
            if (unexpected.isNotEmpty()) {
                throw GradleException(
                    "commerce-domain must depend on kotlin-stdlib only, but its runtime classpath contains: " +
                        unexpected.sorted().joinToString(),
                )
            }
        }
    }

tasks.named("check") {
    dependsOn(verifyRuntimeDependencies)
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            // The Gradle project is :domain; the published identity is unchanged from the
            // single-module build: io.github.castab:commerce-domain.
            artifactId = "commerce-domain"
            from(components["java"])

            pom {
                name = "commerce-domain"
                description = "Immutable, persistence-agnostic commerce domain models and " +
                    "lifecycle APIs for Kotlin/JVM: a type-level booking lifecycle protocol, " +
                    "versioned financial documents (estimates, quotes, and invoices), and " +
                    "payment reconciliation (payments, allocations, reversals, and refunds), " +
                    "a provider-neutral payment adapter contract, and human and service " +
                    "identity with role-based authorization."
            }
        }
    }
}
