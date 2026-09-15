import java.util.Properties

plugins {
    id("com.android.library")
}

group = "org.autojs.plugin.r8compiler"
version = "0.2.0"

// The SDK levels follow the repository's version.properties, like the app module.
val repositoryVersions = Properties().apply {
    rootProject.file("version.properties").inputStream().use { load(it) }
}

android {
    namespace = "org.autojs.plugin.r8compiler.api"
    compileSdk = repositoryVersions.getProperty("COMPILE_SDK_VERSION").toInt()

    defaultConfig {
        minSdk = repositoryVersions.getProperty("MIN_SDK_VERSION").toInt()
        consumerProguardFiles("consumer-rules.pro")
    }

    lint {
        targetSdk = repositoryVersions.getProperty("TARGET_SDK_VERSION").toInt()
        abortOnError = true
        // Protocol 1.x is intentionally frozen; its compatibility range starts at the minSdk above.
        disable += "GradleDependency"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        aidl = true
        buildConfig = false
    }
}

dependencies {
    api(project(":plugin-api:protocol-wire-api"))
    testImplementation(libs.junit)
}

tasks.withType<Test>().configureEach {
    systemProperty("r8.aidl.root", layout.projectDirectory.dir("src/main/aidl").asFile.absolutePath)
}
