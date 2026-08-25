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

rootProject.name = "autojs6-plugin-r8-compiler"

include(
    ":app",
    ":plugin-api:protocol-wire-api",
    ":plugin-api:r8-compiler-api",
)
