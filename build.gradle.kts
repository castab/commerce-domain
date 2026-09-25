import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `java-library`
    `maven-publish`
    alias(libs.plugins.kotlin.jvm)
}

group = "io.github.castab"

// Release versions are never edited into this file. The Publish workflow derives
// the version from the GitHub Release tag (v1.2.3 -> 1.2.3) and passes it as the
// `version` project property. Local builds use the development default.
version = providers.gradleProperty("version").getOrElse("0.0.0-SNAPSHOT")

// "owner/repository" on GitHub. GitHub Actions provides GITHUB_REPOSITORY; the
// default applies to local builds. Used for the GitHub Packages URL and POM links.
val githubRepository = providers.environmentVariable("GITHUB_REPOSITORY")
    .getOrElse("castab/booking-lifecycle")

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
    explicitApi()
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
                name = "booking-lifecycle"
                description = "A type-level booking lifecycle protocol for Kotlin/JVM: " +
                    "application-owned domain types implement lifecycle phases, and the " +
                    "legal transitions between phases are expressed by their interfaces."
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
                // No <licenses> section: the repository does not declare a license yet.
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
