# G5 local signed release v1

## Scope

G5 creates an append-only release inside this repository's ignored `releases/` directory. It does
not push Git refs, create a remote release, upload a Maven artifact, install an APK, invoke ADB, or
claim Binder/device acceptance. The mutable identity field `published=false` continues to mean
that no official or remote publication has occurred; G5 records the narrower and non-conflicting
claims `localPublished=true` and `remotePublished=false`.

The authoritative G5 release identity is `0.1.0-provider-dev-local.4`, matching the APK manifest version
`0.1.0-provider-dev`. A local development build is deliberately not relabelled as the frozen API
version `0.1.0` or as a remote production release.

The bootstrap `local.1` directory remains immutable. It proved the two-build/signing pipeline when
the publisher was launched directly, then its Gradle wrapper entry exposed a Windows PowerShell
module-autoload dependency for `Get-FileHash`. `local.2` replaces that dependency with the .NET
SHA-256 primitive used by the earlier Gates and remains the first formal publisher release.

Authorized device acceptance of `local.2` then exposed a provider-runtime compatibility defect:
fixed R8 8.13.17 calls the Java 11 `List.toArray(IntFunction)` API, which does not exist on the
provider's Android API 24-28 runtime floor. An isolated diagnostic build demonstrated that Android
core-library desugaring with pinned `desugar_jdk_libs_nio` 2.1.5 is the minimum build-time remedy.
`local.3` published that corrected provider for formal device acceptance. Android API 24/25/28 AVD
acceptance then passed, but a Sony API 28 physical device denied the provider access to
`/proc/self/fdinfo`, exposing a second runtime portability defect in descriptor-mode validation.
`local.4` removes that procfs dependency: public API-24 `Os.read` and `Os.write` zero-byte syscalls
require the exact read-only/write-only capability matrix without consuming or emitting payload,
while `Os.fstat` continues to reject aliases. It supersedes `local.3` without overwriting, deleting,
or promoting any byte from `local.1`, `local.2`, or `local.3`.

## Build and reproducibility boundary

The publisher enumerates the current Git worktree, including staged and untracked non-ignored
files, and copies it into two different temporary directories. Each snapshot runs exactly
`:app:assembleRelease` with Gradle daemon, build cache, configuration cache, and network access
disabled, and with every task forced to rerun. The release inputs are hashed before either build
and rehashed after both builds.

G5 requires the two unsigned APKs to be byte-identical. It then signs each APK independently with
the same externally authorized host signing configuration and requires the signed APKs to be
byte-identical too. This proves repeatability across two clean source directories under the same
offline machine/toolchain. It is not a claim of a hermetic build across arbitrary operating
systems, JDKs, Android SDK installations, or dependency mirrors.

The APK manifest is inspected after signing. The package, version, SDK boundary, protected
permission, exact provider service, exported state, isolated `:r8` process, and explicit R8 action
must all match the reserved identity. `apksigner` must validate one signer and APK Signature
Schemes v2 and v3 across API 24-36; v1 and v4 are deliberately disabled.

## Signing-secret boundary

The publisher reads the authorized host `sign.properties` and keystore only after source and prior
gate validation. Passwords are passed to `apksigner` through process-local environment variables
and cleared in `finally`; they never appear in the command line, release manifest, Gate report, or
console output. Neither external path is persisted. The only signing identity published into the
evidence is the public signer-certificate SHA-256 digest.

## Append-only publication

The fixed local directory is:

`releases/provider/0.1.0-provider-dev/local.4/`

It contains exactly one signed provider APK, the two byte-frozen `0.1.0` API AARs, and one
deterministic manifest. A first publication is staged under a sibling `.partial-<uuid>` directory
and exposed by a same-volume directory rename. A later invocation may only return `IDENTICAL`
after exact file-name, length, and SHA-256 verification. It never overwrites a published byte.

The fixed invocation report is written atomically to
`build/reports/r42-g5/local-release-gate.json`. It binds the current positive G2, G3, and G4
reports, release inputs, frozen G1 distribution, design, publisher, Android/JDK tool identities,
both isolated builds, signature verification, release manifest, and final released artifacts.

Run the local-only publication from the repository root with:

```powershell
.\gradlew.bat publishG5LocalRelease
```
