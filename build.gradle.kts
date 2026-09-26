import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jlleitschuh.gradle.ktlint.KtlintExtension
import org.jlleitschuh.gradle.ktlint.reporter.ReporterType

// The root project coordinates the `commerce` build. It has no sources and publishes
// nothing. It holds only the configuration both modules must share; everything
// module-specific (dependencies, artifactId, POM description) lives in the module.
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    // Applied here as well so the root build scripts are lint-checked.
    alias(libs.plugins.ktlint)
}

// "owner/repository" on GitHub. GitHub Actions provides GITHUB_REPOSITORY; the
// default applies to local builds. Used for the GitHub Packages URL and POM links.
// This is the repository that hosts the packages, not the artifact identity: each
// module sets its own artifactId, so renaming the GitHub repository does not change
// the published coordinates.
val githubRepository =
    providers
        .environmentVariable("GITHUB_REPOSITORY")
        .getOrElse("castab/commerce")

// ---------------------------------------------------------------------------
// Java 25 is an intentional, hard requirement of every module.
//
// Compilation, bytecode target, and test execution are all pinned to Java 25.
// Do not lower this to 17/21 for "broader compatibility". If no Java 25 toolchain
// is installed, the build fails (auto-download is disabled in gradle.properties).
// ---------------------------------------------------------------------------
val requiredJavaVersion = 25

allprojects {
    plugins.withId("org.jlleitschuh.gradle.ktlint") {
        configure<KtlintExtension> {
            outputToConsole.set(true)
            coloredOutput.set(true)
            baseline.set(layout.projectDirectory.file("config/ktlint/baseline.xml"))
            reporters {
                reporter(ReporterType.PLAIN)
                reporter(ReporterType.HTML)
            }
        }
        // Keep compilation of main sources on the formatter's output.
        tasks.named { it == "compileKotlin" }.configureEach {
            dependsOn("ktlintMainSourceSetFormat")
        }
        // When building, report style violations before compilation can format main sources.
        tasks.named { it == "ktlintMainSourceSetFormat" }.configureEach {
            mustRunAfter("ktlintMainSourceSetCheck")
        }
    }
}

subprojects {
    group = "io.github.castab"

    // Release versions are never edited into build files. The Publish workflow derives
    // the version from the GitHub Release tag (v1.2.3 -> 1.2.3) and passes it as the
    // `version` project property. Both modules always share one version. Local builds
    // use the development default.
    version = providers.gradleProperty("version").getOrElse("0.0.0-SNAPSHOT")

    plugins.withId("org.jetbrains.kotlin.jvm") {
        configure<JavaPluginExtension> {
            toolchain {
                languageVersion = JavaLanguageVersion.of(requiredJavaVersion)
            }
            withSourcesJar()
            // The sources are Kotlin-only, so this jar holds no generated API docs. It is
            // published for Maven convention (and future Maven Central) compatibility; the
            // API documentation lives in KDoc, readable via the sources jar in IDEs.
            withJavadocJar()
        }

        configure<KotlinJvmProjectExtension> {
            jvmToolchain(requiredJavaVersion)
            compilerOptions {
                jvmTarget = JvmTarget.JVM_25
            }
        }

        tasks.withType<JavaCompile>().configureEach {
            options.release = requiredJavaVersion
        }

        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
            // Test JVM comes from the Java 25 toolchain configured above.
            testLogging {
                events("passed", "skipped", "failed")
            }
        }
    }

    plugins.withId("maven-publish") {
        configure<PublishingExtension> {
            // Each module creates its own publication and sets its artifactId, name, and
            // description. The metadata below is identical for every commerce artifact.
            publications.withType<MavenPublication>().configureEach {
                pom {
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
    }
}
