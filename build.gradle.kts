import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

plugins {
    id("com.android.application") version "9.1.0" apply false
    id("com.android.library") version "9.1.0" apply false
}

tasks.register<Delete>("clean") {
    delete(layout.buildDirectory)
}

val g2ProviderGate = layout.buildDirectory.file("reports/r42-g2/provider-gate.json")
val prepareG2ProviderGate = tasks.register("prepareG2ProviderGate") {
    outputs.upToDateWhen { false }
    doLast {
        val output = g2ProviderGate.get().asFile
        check(output.parentFile.mkdirs() || output.parentFile.isDirectory)
        val temporary = output.parentFile.resolve(".${output.name}.${UUID.randomUUID()}.tmp")
        try {
            temporary.writeText(
                """
                {
                  "schemaVersion": "autojs6.r8.g2.provider-gate/v2",
                  "passed": false,
                  "evidenceBoundary": "LOCAL_PROVIDER_JVM_AND_ANDROID_BUILD",
                  "summary": "G2 provider gate was invalidated before prerequisites"
                }
                """.trimIndent() + "\n",
                Charsets.UTF_8,
            )
            try {
                Files.move(
                    temporary.toPath(),
                    output.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary.toPath())
        }
    }
}

val g2Prerequisites = listOf(
    ":app:testDebugUnitTest",
    ":app:lintDebug",
    ":app:assembleDebug",
    ":app:assembleRelease",
)

gradle.projectsEvaluated {
    g2Prerequisites.forEach { path -> tasks.getByPath(path).mustRunAfter(prepareG2ProviderGate) }
}

tasks.register<Exec>("verifyG2Provider") {
    dependsOn(prepareG2ProviderGate)
    dependsOn(g2Prerequisites)
    outputs.upToDateWhen { false }
    commandLine(
        "powershell.exe",
        "-NoLogo",
        "-NoProfile",
        "-NonInteractive",
        "-ExecutionPolicy",
        "Bypass",
        "-File",
        layout.projectDirectory.file("scripts/verify-g2-provider.ps1").asFile.absolutePath,
    )
}

tasks.register<Exec>("verifyG3HostControlPlane") {
    outputs.upToDateWhen { false }
    commandLine(
        "powershell.exe",
        "-NoLogo",
        "-NoProfile",
        "-NonInteractive",
        "-ExecutionPolicy",
        "Bypass",
        "-File",
        layout.projectDirectory.file("scripts/verify-g3-host-control-plane.ps1").asFile.absolutePath,
    )
}

tasks.register<Exec>("verifyG4CompatibilityCorpus") {
    outputs.upToDateWhen { false }
    commandLine(
        "powershell.exe",
        "-NoLogo",
        "-NoProfile",
        "-NonInteractive",
        "-ExecutionPolicy",
        "Bypass",
        "-File",
        layout.projectDirectory.file("scripts/verify-g4-compatibility-corpus.ps1").asFile.absolutePath,
    )
}

tasks.register<Exec>("publishG5LocalRelease") {
    outputs.upToDateWhen { false }
    commandLine(
        "powershell.exe",
        "-NoLogo",
        "-NoProfile",
        "-NonInteractive",
        "-ExecutionPolicy",
        "Bypass",
        "-File",
        layout.projectDirectory.file("scripts/publish-g5-local-release.ps1").asFile.absolutePath,
    )
}
