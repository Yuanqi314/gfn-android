pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "gfn-android"

include(
    ":app",
    ":core-model",
    ":core-network",
    ":gfn-auth",
    ":gfn-account",
    ":gfn-games",
    ":gfn-cloudmatch",
    ":gfn-identity",
    ":gfn-session",
    ":diagnostics",
    ":stream-core",
    ":stream-input",
    ":stream-signaling",
    ":stream-webrtc",
    ":protocol-cli",
)
