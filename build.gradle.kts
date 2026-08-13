plugins {
    id("com.android.library") version "9.2.1" apply false
}

tasks.register<Delete>("clean") {
    delete(layout.buildDirectory)
}
