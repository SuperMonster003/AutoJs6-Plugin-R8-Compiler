******

### Release history

******

# v0.2.2

###### 2026/09/15

* `Improvement` Raise compileSdk and targetSdk to 37 (Android 17); the plugin's behavior does not depend on the new target

# v0.2.1

###### 2026/09/13

* `Improvement` Consistent localized resources, explicit plugin activation and validated release preparation

# v0.2.0

###### 2026/09/13

* `Improvement` Build verification rejects accidental native dependencies and produces a JSON report
* `Improvement` Consistent localized resources, explicit plugin activation and validated release preparation

# v0.1.0-provider-dev-private.1 (local.5)

###### 2026/08/25

* `Hint` Current release. Published as a private GitHub prerelease (5 assets: signed APK, two frozen contract AARs, release manifest, and SHA256SUMS, all re-downloaded and byte-verified); not publicly released yet
* `Hint` Inert after installation by default; it must be enabled manually in AutoJs6 developer options -- see the "Installation and usage" section of the README
* `Feature` Bundle a byte-verified Android 36 platform library as the R8 compiler library instead of relying on the device boot classpath; fixes compile failures on devices whose boot JARs are resource-only shells
* `Feature` Add bounded, path-redacted R8 diagnostics collection covering provider startup and engine import failures
* `Improvement` Verify optimized output on ART at API 25/28/37 (including a 16 KiB page-size emulator): reflection, runtime-composed class names, serialization, the script-facing entry, removed-decoy checks, arm64/x86/x86_64 JNI calls, and R8 Retrace stack restoration after mapping-hash verification

# v0.1.0-provider-dev (local.4)

###### 2026/08/25

* `Fix` Replace `/proc/self/fdinfo` access-mode inspection with zero-byte public `Os.read`/`Os.write` kernel probes, resolving procfs restrictions on some devices (such as Sony API 28) while retaining `Os.fstat` alias rejection
* `Improvement` Complete cross-APK Binder/PFD device acceptance on 1 physical device and 2 emulators (API 25/28): happy path, lifecycle, hostile input, and process death -- 9/9 tests passing

# v0.1.0-provider-dev (local.3)

###### 2026/08/25

* `Fix` Pin core-library/NIO desugaring (desugar_jdk_libs_nio 2.1.5) for API 24 through 28, fixing runtime failures caused by missing Java 11 core-library capabilities on older devices

# v0.1.0-provider-dev (local.2)

###### 2026/08/25

* `Fix` Remove the publishing script's dependency on PowerShell module auto-load hashing, ensuring an environment-independent reproducible signing flow

# v0.1.0-provider-dev (local.1)

###### 2026/08/25

* `Hint` First local signed release (bootstrap generation); reproducible build with two byte-identical offline snapshots, establishing the append-only local release directory
* `Feature` Explicit R8 compiler plugin for AutoJs6: scripts request full release compilation (shrinking + optimization + obfuscation) through `runtime.loadJarWithR8()`
* `Feature` One compilation returns five artifacts: DEX ZIP, mapping, seeds, usage, and retrace metadata, each SHA-256 bound and independently re-verified by the host
* `Feature` No-fallback semantics: every failure ends as an R8 error and never silently switches to D8/dx; the host uses the dedicated R8 cache domain `autojs6:r8-compiler:v1`
* `Feature` Compilation runs in the plugin's own `:r8` process inside a private sandbox, accepts only same-signature AutoJs6 callers (protected by the `org.autojs.permission.PLUGIN` permission), and requests no network or storage permission
* `Feature` Strict input validation: canonical path-free input bundle, strict UTF-8 rules with fail-closed dangerous directives, and hard limits on archives, class data, and outputs
* `Feature` Full compiler `minApi` 24-36 coverage: a 26-cell Java/Kotlin real-R8 compatibility corpus with reflection, runtime-composed names, JNI, serialization, and removed-decoy checks
* `Feature` Pure JVM implementation; one universal APK covers all device architectures
* `Dependency` Bundle Google R8 8.13.17 (Maven `com.android.tools:r8`)

# v0.1.0 (contract)

###### 2026/08/14

* `Hint` Protocol contract freeze with no app or runtime behavior; this entry records the establishment of the interface boundary
* `Feature` Freeze the independent R8 compile protocol 1.0: API namespace `org.autojs.plugin.r8compiler.api`, discovery action `org.autojs.plugin.R8_COMPILER`, engine identity `r8-compiler`
* `Feature` Freeze the canonical input/artifact streaming bundle formats, three AIDL descriptors, and the Java-visible JVM ABI; publish the 0.1.0 contract AARs (protocol-wire-api and r8-compiler-api) append-only
