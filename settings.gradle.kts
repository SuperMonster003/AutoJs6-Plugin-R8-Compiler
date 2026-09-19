pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        id("io.github.supermonster003.autojs6-platform-versions") version "1.8.3"
        id("io.github.supermonster003.autojs6-native-alignment") version "1.8.3"
    }
}

plugins {
    id("io.github.supermonster003.autojs6-platform-versions")
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
