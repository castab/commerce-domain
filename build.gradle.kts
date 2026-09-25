import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jlleitschuh.gradle.ktlint.reporter.ReporterType

plugins {
    `java-library`
    `maven-publish`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
}

ktlint {
    outputToConsole.set(true)
    coloredOutput.set(true)
    baseline.set(layout.projectDirectory.file("config/ktlint/baseline.xml"))
    reporters {
        reporter(ReporterType.PLAIN)
        reporter(ReporterType.HTML)
    }
}

// Keep compilation of main sources on the formatter's output.
tasks.named("compileKotlin") {
    dependsOn("ktlintMainSourceSetFormat")
}

// When building, report style violations before compilation can format main sources.
tasks.named("ktlintMainSourceSetFormat") {
    mustRunAfter("ktlintMainSourceSetCheck")
}

group = "io.github.castab"

// Release versions are never edited into this file. The Publish workflow derives
// the version from the GitHub Release tag (v1.2.3 -> 1.2.3) and passes it as the
// `version` project property. Local builds use the development default.
version = providers.gradleProperty("version").getOrElse("0.0.0-SNAPSHOT")

// "owner/repository" on GitHub. GitHub Actions provides GITHUB_REPOSITORY; the
// default applies to local builds. Used for the GitHub Packages URL and POM links.
// This is the repository that hosts the package, not the artifact identity: the
// artifactId comes from rootProject.name (settings.gradle.kts), so renaming the
// GitHub repository does not change the published coordinates.
val githubRepository =
    providers
        .environmentVariable("GITHUB_REPOSITORY")
        .getOrElse("castab/commerce-domain")

// ---------------------------------------------------------------------------
// Java 25 is an intentional, hard requirement of this library.
//
// Compilation, bytecode target, and test execution are all pinned to Java 25.
// Do not lower this to 17/21 for "broader compatibility": the artifact is
// meant to require a Java 25 runtime. If no Java 25 toolchain is installed,
// the build fails (auto-download is disabled in gradle.properties).
// ---------------------------------------------------------------------------
val requiredJavaVersion = 25

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(requiredJavaVersion)
    }
    withSourcesJar()
    // The sources are Kotlin-only, so this jar holds no generated API docs. It is
    // published for Maven convention (and future Maven Central) compatibility; the
    // API documentation lives in KDoc, readable via the sources jar in IDEs.
    withJavadocJar()
}

kotlin {
    jvmToolchain(requiredJavaVersion)
    compilerOptions {
        jvmTarget = JvmTarget.JVM_25
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = requiredJavaVersion
}

dependencies {
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.mockk)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // Test JVM comes from the Java 25 toolchain configured above.
    testLogging {
        events("passed", "skipped", "failed")
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])

            pom {
                name = "commerce-domain"
                description = "Immutable, persistence-agnostic commerce domain models and " +
                    "lifecycle APIs for Kotlin/JVM: a type-level booking lifecycle protocol, " +
                    "versioned financial documents (estimates, quotes, and invoices), and " +
                    "payment reconciliation (payments, allocations, reversals, and refunds), " +
                    "and a provider-neutral payment adapter contract."
                url = "https://github.com/$githubRepository"
                developers {
                    developer {
                        id = "castab"
                        name = "Brayan Castaneda"
                        url = "https://github.com/castab"
                    }
                }
                scm {
                    url = "https://github.com/$githubRepository"
                    connection = "scm:git:https://github.com/$githubRepository.git"
                    developerConnection = "scm:git:ssh://git@github.com/$githubRepository.git"
                }
                licenses {
                    license {
                        name = "Apache-2.0"
                        url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                        distribution = "repo"
                    }
                }
            }
        }
    }

    repositories {
        maven {
            name = "GitHubPackages"
            // GitHub Packages requires a lowercase owner in the registry URL.
            url = uri("https://maven.pkg.github.com/${githubRepository.lowercase()}")
            // Resolved lazily from the GitHubPackagesUsername / GitHubPackagesPassword
            // properties (in CI: ORG_GRADLE_PROJECT_GitHubPackages{Username,Password}).
            // They are required only when a task publishes to this repository, so
            // build, test, and publishToMavenLocal never need GitHub credentials.
            credentials(PasswordCredentials::class)
        }
    }
}
