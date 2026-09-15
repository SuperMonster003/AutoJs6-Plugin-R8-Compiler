import java.util.Properties

plugins {
    id("com.android.library")
}

// The SDK levels follow the repository's version.properties, like the app module.
val repositoryVersions = Properties().apply {
    rootProject.file("version.properties").inputStream().use { load(it) }
}

android {
    namespace = "org.autojs.plugin.protocol.wire"

    compileSdk = repositoryVersions.getProperty("COMPILE_SDK_VERSION").toInt()

    defaultConfig {
        minSdk = repositoryVersions.getProperty("MIN_SDK_VERSION").toInt()
        consumerProguardFiles("consumer-rules.pro")
    }

    lint {
        targetSdk = repositoryVersions.getProperty("TARGET_SDK_VERSION").toInt()
        abortOnError = true
        // Protocol 0.1 is intentionally frozen; its compatibility range starts at the minSdk above.
        disable += "GradleDependency"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = false
    }
}

dependencies {
    testImplementation(libs.junit)
}
