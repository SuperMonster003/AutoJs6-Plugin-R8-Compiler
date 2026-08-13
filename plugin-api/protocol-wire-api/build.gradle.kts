plugins {
    id("com.android.library")
}

android {
    namespace = "org.autojs.plugin.protocol.wire"

    compileSdk = 36

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    lint {
        targetSdk = 36
        abortOnError = true
        // Protocol 0.1 is intentionally frozen to the API 24-36 compatibility range.
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
