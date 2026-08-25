# G7 local.5 ART, JNI, and Retrace acceptance v1

## Why local.5 exists

G6 proved the cross-APK Binder/PFD path with a deliberately small Java fixture, but it did not
execute the G4 reflection/JNI/serialization corpus on ART and did not execute Retrace. The first G7
runtime probe exposed a real provider defect: Android 8 and similar devices publish readable boot
classpath JAR paths, but those JARs are often resource shells whose class definitions live only in
ART boot images. Passing the shells to R8 therefore left even `java.lang.String` and
`java.io.Serializable` unresolved. The earlier small fixture did not exercise enough library
surface to reveal that boundary.

The local.5 provider keeps the frozen protocol and its device-runtime fingerprint. It hashes the
readable device boot-classpath files as before, then adds the exact Android 36 SDK class-stub JAR to
the advertised runtime identity list. R8 receives only that byte-pinned class-stub JAR as its
compile library; it never mistakes stripped device shells for class-file libraries. The build must
find an `android.jar` of exactly 27,768,026 bytes and SHA-256
`d9eb9da824d9e247a352f570f01e1169e725b2954bca9e283a71786c59b59f9a`. The APK embeds those exact
bytes. The provider atomically materializes them under its private no-backup directory, verifies
length and digest before and after publication, and exposes neither the device path nor the build
SDK path over Binder.

R8 diagnostics now preserve its actionable semantic message while remaining bounded and
path-redacted. Diagnostic origin and position objects are never serialized. Absolute Windows,
Unix, and `file:` paths are replaced by `<path>`, controls are folded to one line, each message is
limited to 2 KiB of valid UTF-8, and the aggregate protocol budget remains authoritative.

## Release and evidence boundary

`publish-g7-local-release.ps1` creates the append-only
`0.1.0-provider-dev-local.5` generation. Two separate source snapshots each execute an offline,
no-build-cache, all-tasks-rerun Release build. Their unsigned APKs and two authorized signing passes
must be byte-identical. The publisher verifies the single AutoJs6 signer, v2/v3 signatures, APK
identity, embedded platform-library bytes, frozen 0.1.0 AARs, and source closure. It may create
local.5 once or return `IDENTICAL`; it never rewrites local.4 and never pushes or publishes
remotely.

The runtime Gate is incremental over positive G6 and the final `IDENTICAL` local.5 release Gate. It
accepts only the exact local.5 provider APK installed on every target. The fixed matrix is:

| Role | Runtime | Compiler path | Native ABI |
|---|---|---|---|
| Physical | API 28 Sony arm64 | API 26+ `R8Command` | `arm64-v8a` |
| Modern AVD | API 37, 16 KiB page, compiler `minApi=36` | API 26+ `R8Command` | `x86_64` |
| Legacy AVD | API 25 | API 24/25 R8 CLI | `x86` |

Each device performs one invocation-bound test through the production host dispatcher and exact
same-signer provider selection. The Gate UUID is passed into instrumentation, included in the keep
rules (forcing a fresh semantic input), and echoed in the sole structured receipt. A receipt from
any previous run is therefore rejected.

The optimized DEX must execute all of the following on ART:

- kept public script entry;
- reflective construction and invocation;
- runtime-composed class-name loading;
- Java serialization hooks and round trip;
- removal of an unkept decoy;
- an obfuscated exception frame produced by ART;
- ABI-matched JNI load, a numeric native call returning 42, and a static native string call.

The test consumes the production five-artifact bundle and production R8-only DEX loader. It sends
mapping and obfuscated stack bytes only inside its invocation-private status receipt. The verifier
checks their recorded SHA-256 values, strict UTF-8, canonical Base64, bounds, mapping hash metadata,
and the absence of the original crash class in the raw ART stack.

## Real Retrace execution

The host-side verifier runs `com.android.tools.r8.retrace.Retrace` from the exact pinned R8 8.13.17
JAR: 18,279,079 bytes, SHA-256
`d31fd0dc751d48740009cdd9a485126acb1d0d14c59b9f05579479940f4adf74`. It uses JDK 21 and
`--verify-mapping-file-hash`. A positive result must restore
`G7ObfuscatedCrash.explode`, `G7ObfuscatedCrash.java:8`, and the kept
`G7RuntimeFixture.crashForRetrace` caller while preserving the exception marker and removing the
raw `a.a.a` frame. The fixed Gate records only byte lengths and hashes of mapping, raw stack, and
retraced output; it never persists their Base64 bodies or an absolute temporary path.

## Authorized operations

The verifier requires `-AuthorizeDeviceRuntime`. It does not install, replace, uninstall,
force-stop, clear, enable, or disable a package. It pulls each installed base APK into an
invocation-specific system-temporary directory, checks the authorized signer, and requires the
provider bytes to equal local.5 exactly. It rechecks all installed package paths after each test and
removes only its validated temporary directory.

The protected physical serials `QV710AF65F` and `968e9f18` are rejected. The verifier performs no
Git push, remote release, Maven upload, or other remote publication.

After the intended packages are installed on the three selected targets, run from the provider
repository root:

```powershell
powershell.exe -NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass `
  -File .\scripts\verify-g7-art-jni-retrace.ps1 `
  -AuthorizeDeviceRuntime `
  -PhysicalSerial <api28-arm64-serial> `
  -ModernAvdSerial <api37-x86_64-serial> `
  -Api25AvdSerial <api25-x86-serial>
```

The invocation-bound report is atomically written to
`build/reports/r42-g7/art-jni-retrace-gate.json`. A failed or interrupted invocation leaves that
fixed Gate negative. This stage proves local ART/JNI/Retrace behavior only; official/remote
publication remains false.
