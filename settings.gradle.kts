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

// The project name is the published Maven artifactId: io.github.castab:commerce-domain.
// It is independent of the GitHub repository name and of the checkout directory name.
rootProject.name = "commerce-domain"
