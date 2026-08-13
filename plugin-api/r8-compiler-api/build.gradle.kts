plugins {
    id("com.android.library")
}

group = "org.autojs.plugin.r8compiler"
version = "0.1.0"

android {
    namespace = "org.autojs.plugin.r8compiler.api"
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
