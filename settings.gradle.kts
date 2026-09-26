pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

// No toolchain resolver plugin (e.g. foojay) is applied on purpose: if a local
// Java 25 installation cannot be found, the build fails instead of silently
// provisioning or falling back to another JDK.

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
    }
}

// The root project is the `commerce` toolkit. It contains no sources of its own.
rootProject.name = "commerce"

// Gradle project names are not artifact identities. Each module sets its published
// artifactId explicitly: :domain -> io.github.castab:commerce-domain and
// :service -> io.github.castab:commerce-service.
include("domain")
include("service")
