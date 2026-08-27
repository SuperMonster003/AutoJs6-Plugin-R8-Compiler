# G10 script-visible Retrace device acceptance v1

## Evidence objective

G10 turns the protocol 1.1 Retrace extension into a user-visible host capability. The device Gate
therefore starts above the JVM and contract tests: a production AutoJs6 build must call
`runtime.retraceR8Stack(...)` from Rhino, cross the real Binder/PFD boundary into the separately
installed provider, retrace a stack emitted by ART, and return the restored text to the script.
The same public route must reject a mapping whose bytes no longer match the exported Retrace
metadata. No D8/dx or local fallback is admitted after the explicit R8 route starts.

The invocation-bound Android test also calls `loadJarWithR8(...)` with a report directory. It must
export only `mapping.txt`, `seeds.txt`, `usage.txt`, and `retrace-metadata.bin`; DEX remains in the
private semantic cache and is not copied into the report directory. Every exported artifact is
re-read and checked against the provider result before publication.

## Fixed debug matrix

The final G10 Gate uses three AVDs and one fresh UUID shared by all three runs:

| Gate role | AVD | API | ABI | Required compiler minApi | Page size |
|---|---|---:|---|---:|---:|
| `AVD_API25_X86` | `R1_API25_Play` | 25 | `x86` | 25 | not claimed |
| `AVD_API28_X86_64` | `DEX_R1_API28_X64` | 28 | `x86_64` | 28 | not claimed |
| `AVD_API37_X86_64_16K` | `AVD_API_37` | 37 | `x86_64` | 36 | 16,384 bytes |

This is a debug-only integration boundary. The host, host instrumentation, and provider APKs must
all have the standard local debug certificate SHA-256
`2e64822e13a6c80c12e1c4b47e8fb32d1e9334526289da75777b7a79145de4b8`. The Gate compares each
installed base APK byte-for-byte with an explicit local APK path; it neither uses nor requests the
official release signing material.

The operator explicitly authorized use of all connected devices and dormant AVDs on 2026-08-27.
Setup used `adb install -r -t` to replace only the three task packages on these AVDs. It did not
uninstall a package, clear application data, or touch any physical device. The verifier itself is
read-only apart from the expected instrumentation lifecycle and its local report/temp files: it
does not install, replace, uninstall, clear, enable, disable, start an AVD, or issue an explicit
force-stop command.

## Acceptance assertions

Each target must emit exactly one `autojs.r8Compiler.g10.result` JSON receipt. The verifier admits
an exact receipt schema and rejects extra fields, so raw mapping, metadata, obfuscated stack, and
retraced stack bytes cannot enter the Gate report. Only bounded byte lengths and lowercase SHA-256
digests are retained.

The receipt proves all of the following:

- a fresh cross-APK R8 compile of the byte-pinned G7 crash fixture;
- a real `IllegalStateException` thrown by the optimized DEX on ART;
- execution through a production Rhino engine and the public `runtime.retraceR8Stack(...)` entry;
- provider-side R8 8.13.17 Retrace with mapping/metadata hash verification;
- restoration of `G7ObfuscatedCrash.explode`, `G7ObfuscatedCrash.java:8`, and the kept caller;
- preservation of the invocation marker and removal of the target obfuscated frame;
- fail-closed rejection of a deliberately modified mapping, with no output and no fallback;
- verified four-report export and explicit absence of exported DEX.

The verifier additionally pulls the three installed base APKs to an invocation-private temporary
directory, validates package path, bytes, and signer, then confirms that package paths did not
change while the test ran. The API 37 target must independently report a 16 KiB page size.

## Low-API defects found by the matrix

The device matrix caught three issues that JVM/API 37 testing could not expose:

1. R8 8.13.17 writes mapping format version `2.2`; advertising bundle schema version `1` caused
   provenance negotiation to fail closed. Provider capabilities now advertise the emitted mapping
   header version, and an integration test binds real compiler metadata back into an input bundle.
2. The host report exporter used `File.toPath()` while the supported runtime floor is API 24. API
   24/25 now publish a same-parent staging directory with checked `File.renameTo`; API 26+ retains
   the atomic NIO move path.
3. R8 8.13.17's built-in string parser calls `Matcher.start(String)`, which Android added in API
   26. API 24/25 now use a numbered-capture parser feeding R8's public proxy Retrace engine; API
   26+ keeps the native `RetraceCommand` path. A provider Android test directly exercises the
   engine on API 25 so the unwrapped compatibility boundary remains reproducible.

## Running the fixed Gate

Build the host and instrumentation APKs from a clean AutoJs6 worktree containing the G10 commits,
build the provider debug APK, install those exact bytes on the three AVDs, then run:

```powershell
powershell.exe -NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass `
  -File .\scripts\verify-g10-device-retrace.ps1 `
  -AuthorizeDeviceRuntime `
  -Api25Serial emulator-5566 `
  -Api28Serial emulator-5564 `
  -Api37Serial emulator-5560 `
  -Api25HostApkPath <clean-host-x86-apk> `
  -Api28HostApkPath <clean-host-x86_64-apk> `
  -Api37HostApkPath <clean-host-x86_64-apk> `
  -HostTestApkPath <clean-host-androidTest-apk> `
  -ProviderApkPath <provider-debug-apk> `
  -HostSourceRoot <clean-AutoJs6-worktree>
```

The report is atomically written to
`build/reports/r42-g10/device-retrace-gate.json`. A failed or interrupted run overwrites the fixed
Gate with a negative report. The Gate performs no Git push, visibility change, release mutation,
Maven upload, or other remote action; it cannot complete any G9 publication item.
