# G6 authorized device acceptance v1

## Evidence boundary

G6 proves the installed, append-only `0.1.0-provider-dev-local.4` APK across a deliberately small
Android matrix. It is an incremental device Gate over the historical G2 provider, G3 host, G4
compatibility, and G5 local-release reports. Those earlier reports keep the claims that were true at
their own boundaries; G6 is the first report allowed to set Binder, PFD lifecycle, process-death,
physical-device, and device claims true.

The fixed matrix is one physical API 28 `arm64-v8a` device, one API 28 `x86_64` AVD, and one API 25
`x86` AVD. The physical serial and both emulator transport serials are explicit command arguments,
must be distinct, and are rejected if either protected physical serial is supplied. The verifier
independently derives the stable device identity passed into instrumentation and checks physical/AVD,
SDK, and ABI properties before touching package state.

## Authorized operations

The verifier requires the explicit `-AuthorizeDeviceAcceptance` switch. It does not install,
replace, enable, disable, clear, or uninstall any package. It pulls the already installed base APKs
for the canonical host, its instrumentation package, and the R8 provider into an invocation-specific
system temporary directory. Every pulled APK must have exactly one signer and the authorized AutoJs6
certificate. The provider bytes and length must exactly equal the signed APK recorded by the final
positive G5 `local.4` report.

Instrumentation is allowed to bind the provider and to force-stop exactly
`io.github.supermonster003.autojs6.plugin.r8compiler` for the process-death test. The verifier never
force-stops the host, test package, another plugin, or an arbitrary caller-supplied package. It
rechecks all three package paths after testing and safely removes only its validated system-temp
directory. It performs no Git push, remote release, Maven upload, or other remote publication.

## Runtime receipts

Each device executes two isolated AndroidJUnitRunner class invocations and three tests:

1. The happy path selects the exact same-signer component, crosses the real Binder/PFD boundary,
   performs a fresh fixed-R8 8.13.17 compile, validates the canonical five-artifact bundle, proves a
   verified persistent-cache hit, and uses the production R8-only loader to execute `answer42`.
2. The lifecycle test holds a real input pipe open, proves the single-session BUSY terminal,
   idempotent cancel/close, callee descriptor ownership and output EOF, recovered admission, and
   fail-closed hostile-bundle rejection as `INVALID_BUNDLE` during `INPUT_VALIDATION`.
3. The process-death test force-stops only the exact provider package, observes Binder death and
   output EOF with zero provider terminal callbacks, revalidates the pinned identity, performs an
   authenticated rebind, and proves the provider can accept work again.

AndroidJUnitRunner success text is necessary but insufficient. The verifier parses exactly one
`G6-REAL-R8-HAPPY`, one `G6-REAL-R8-LIFECYCLE`, and one `G6-REAL-R8-PROCESS-DEATH` JSON receipt per
device and validates every identity, version, signer, artifact, lifecycle, and recovery field. The
fixed report is invalidated to `passed=false` before authorization or ADB resolution and becomes
positive only after all three devices pass. Reports contain no local absolute path or signing
secret.

Run from the provider repository root only after the three intended devices already contain the
authorized host, instrumentation, and exact signed `local.4` provider:

```powershell
powershell.exe -NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass `
  -File .\scripts\verify-g6-device-acceptance.ps1 `
  -AuthorizeDeviceAcceptance `
  -PhysicalSerial <physical-serial> `
  -Api28AvdSerial <api28-avd-serial> `
  -Api25AvdSerial <api25-avd-serial>
```

The report is written atomically to
`build/reports/r42-g6/device-acceptance-gate.json`. G6 does not claim remote publication, hermetic
cross-machine reproducibility, retrace execution, or JNI linking.
