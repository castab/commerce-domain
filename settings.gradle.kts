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

rootProject.name = "booking-lifecycle"
