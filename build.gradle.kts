import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
}

group = "io.github.castab"
version = "0.1.0-SNAPSHOT"

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
