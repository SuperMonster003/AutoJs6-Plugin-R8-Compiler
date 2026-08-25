# AutoJs6 R8 Compiler Roadmap

Updated: 2026-08-25

## G1: Independent contract AAR

- [x] Freeze the independent namespace, action, engine, wire schemas, AIDL descriptors, and Java-visible JVM binary ABI.
- [x] Validate bounded canonical input and artifact bundles, ordered identities, and SHA-256 binding.
- [x] Validate explicit rules and output semantics, including fail-closed dangerous directives.
- [x] Produce an immutable local `0.1.0` AAR distribution that co-records a source snapshot,
  scanned artifacts, and explicit intra-distribution/external dependency boundaries.
- [x] Prove a detached consumer compiles against the staged AAR without source-project fallback.
- [x] Persist a fail-closed report with evidence level `CONTRACT_AAR_ONLY` and all runtime claims false.

G1 local evidence (2026-08-14): 126 JVM contract tests across 13 suites passed (14 protocol-wire
and 112 R8 contract), including 1,024 fixed-seed mutation variants. Both debug lint tasks completed
with 0 errors; the R8 API report has 0 warnings and the protocol module has one wrapper-version
advisory. Both release AARs assembled. The source-boundary self-test is 43/43 and the distribution
self-test is 31/31. The strict classfile golden covers 97 class entries, 87 Java-visible classes,
774 visible members, 10 AIDL interface method descriptors, three Binder `DESCRIPTOR` constants,
and ten transaction constants; its SHA-256 is
`b628e1e2edccf0510b7acd31157fb9184947f1d8ccfe61826d0076e7350c96bf`.

The append-only distribution was created and then independently re-read as `IDENTICAL`. Its
manifest SHA-256 is `40c307e1280fa011064f4e7f06215ec17364bfe88cc74bfff5ae0a5d2827b16a`;
the protocol-wire and R8 API AAR SHA-256 values are
`1d97a5b44b2c20e85aa12b263fca604a32d6d89275d47a19076861cd20c29a36` and
`e9df49b7e49992615a15bc0af2372a4525f02b4a2a915a560ddab3128bb2f066`.
The detached Java consumer used an empty `-sourcepath` and only Android 36 plus the two extracted
AAR `classes.jar` files. The persisted local report SHA-256 is
`28425adc67ced736b434524c1708f35267d1fe2a9feeff9be8cb2f9e616815fd`.
The co-recorded source fingerprint is
`a85d40e9e8eebbc347703588fef20adb0ee93a2d992425baca076635d79a3dc8`, and the
distribution verifier SHA-256 is
`3b12ecdd28187c577bcb3a80fd3d2c1e79988ad33dc5ddee1d93a674c0e3bf33`.
It remains `CONTRACT_AAR_ONLY`, `published=false`, and every runtime claim false.

G1 does not create an app, provider service, compiler engine, host route, fallback, APK, or device
evidence. Those are later gates and must not be inferred from an AAR build.

## G2: Provider implementation

- [x] Add a separately identified application and isolated provider service.
- [x] Implement bounded R8 execution and atomic multi-artifact publication.
- [x] Verify Binder/PFD lifecycle, cancellation, ownership, hostile inputs, and process death.

G2 local provider evidence (2026-08-24): the repository now contains an installable `:app` with
application ID `io.github.supermonster003.autojs6.plugin.r8compiler` and one exported,
permission-protected `R8CompilerService` in process `:r8`. The service consumes the byte-frozen
0.1.0 protocol AARs rather than their source projects, authenticates the exact same-signature
AutoJs6 caller, rejects descriptor access-mode/alias violations before asynchronous work, owns one
process-wide session, recovers stale private workspaces, and enforces explicit cancellation and
deadline terminal states. No production source contains a D8/dx route.

The provider materializes the canonical input bundle only into isolated staging, revalidates JAR
framing/entries/class budgets and extracted rule content, executes fixed R8 8.13.17 with shrinking,
optimization and obfuscation enabled, validates indexed DEX output, and finalizes exactly
`DEX_ZIP`, `MAPPING_TEXT`, `SEEDS_TEXT`, `USAGE_TEXT`, and `RETRACE_METADATA` into one local
canonical artifact bundle before claiming the output descriptor. The API 24/25 CLI seam is locked
to `--release` plus provider-owned report controls; API 26+ uses `R8Command` and its cooperative
cancellation checker. Failure remains an R8 terminal and has no semantic fallback.

The local gate is now 6 suites / 42 tests with no failure, error, or skip. The 16 non-corpus tests
still cover a real JVM R8 compile of a generated Java JAR, contract-side consumption of all five
artifacts, aggregate output budget rejection, an API 24 CLI boundary, hostile rules/input checks,
workspace recovery, exact identity/AAR checks, terminal state ownership, and the API 24 provider
floor's pinned core-library/NIO desugaring configuration. The additional 26
parameterized cells are the later G4 Java/Kotlin compatibility corpus; including them in the full
G2 prerequisite set does not extend G2 into ART, Binder, or device evidence. Android lint is 0
errors / 2 version-boundary warnings; Debug and unsigned Release APKs assemble successfully.
`verifyG2Provider` invalidates its fixed report before prerequisites; an unrelated `--tests`
producer failure was exercised and left `passed=false` before the positive gate was restored.

The final v2 gate invocation is `e8d210c8-0022-47be-9846-4a17b11c6e52`; the gate SHA-256 is
`c7913af510bc24ab2a24c989886722f351e4d72f307f7fc0bff4aba6e296bfae`, and its self-recorded
verifier SHA-256 is `99b28d8df8e3fe3452903eb683f05d361b05e7585c4cbc8b383f2299c689f166`.
The Debug and unsigned Release APK SHA-256 values are
`164714cbeae84b09b57afbc46532983ff8b52a67480270d434007a2bfe6ef33e` and
`ae5ba2c507d680352b5cea61d4cd02f3e2365c7c54bf44e4190602ffb08d364b`; the report contains only
repository-relative paths. Its source-boundary test locks the API 24 desugaring configuration and
the procfs-free zero-byte `Os.read`/`Os.write` descriptor capability probes while retaining
`Os.fstat` alias rejection.

This report is `LOCAL_PROVIDER_JVM_AND_ANDROID_BUILD`, not Binder or device acceptance. Its
historical claims remain bounded to G2. The later authorized G6 Gate below independently closes the
third implementation checkbox without rewriting G2. The G2 report records
`laterHostIntegrationPresent=true` and `extendsThisG2EvidenceBoundary=false`; its own
`hostIntegrated` claim remains false. The Release APK is unsigned, no APK/API was remotely
published, and the G2 invocation itself ran no ADB or device operation.

## G3: Host integration

- [x] Add default-off, explicit exact-component selection with signer and protocol pinning.
- [x] Prove that every post-dispatch failure remains an R8 failure and never falls back to D8/dx.
- [x] Isolate R8 cache identity from the existing D8 semantic cache.

G3 local host-integration evidence (2026-08-25): sibling `AutoJs6` at final gate base commit
`e826803c18e1aecd16f687945a04c02eff6d398a` consumes the exact frozen R8 API AAR bytes and an
explicit protocol-wire source whose SHA-256 matches the G1 snapshot. A dedicated Developer-options
preference remains default-off, discovers only the reserved R8 action, and persists one
user-selected exact same-signature component. Selection pins component, UID, version, update time
and the complete signer set; binding uses an explicit `ComponentName`, identity is re-inspected
before negotiation, and callbacks must carry the pinned package UID.

The production entry is the three-overload `runtime.loadJarWithR8(...)` method family. It requires
one or more explicit keep-rule files and optionally accepts ordered classpath JARs plus consumer
rules with parallel classpath-owner ordinals. Rhino conversion of script arrays into `String[]` and
`int[]` is covered. `runtime.loadJar(...)` and `runtime.loadJarWithClasspath(...)` are unchanged and
never select R8 implicitly. Missing selection, invalid arguments, provider/transport failure,
cancellation, corrupt output, cache failure, and load failure all terminate the explicit R8 call;
none authorizes D8/dx fallback.

The process-wide dispatcher owns the complete R8-only transaction. It snapshots bounded program,
classpath, keep-rule and consumer-rule streams into a private canonical input bundle, constructs
only protocol 1.0 / `R8_EXPLICIT` / `FULL_RELEASE` / fallback `NONE`, opens distinct read-only and
write-only descriptors, enforces the callback/session law, and adopts no output until the result,
canonical bundle, five artifact digests, bounded text/retrace metadata, contiguous DEX ZIP names,
DEX header/signature/checksum, and negotiated budgets all pass. Timeout, cancellation, Binder-death
injection, remote failure, malformed or duplicate terminal callbacks, corrupt bundle/DEX, and
descriptor/session cleanup failures all end as R8 failures with semantic fallback disabled.

Verified output is copied into an R8-only `partial-<uuid>` generation and becomes visible only by a
same-directory rename to `entry-<semantic-sha>-<uuid>`. Lookup rebinds the new request ID, repeats
bundle/artifact validation, and hashes each opened descriptor again; corrupt cache data is evicted
and redispatched to the same selected R8 provider. Only the verified `DEX_ZIP` then crosses a
separate class-loader seam, which rechecks size/SHA-256 and atomically adopts a read-only
`r8_verified_` copy without calling a local compiler. The transaction domain remains
`autojs6:r8-compiler:v1`, the directory remains `r8-compiler-cache-v1`, and all 25 production R8
sources remain free of DEX host transport, `dex-compiler-api`, and D8/dx fallback routes.

The invocation-bound v4 gate atomically invalidates its fixed report and invokes the focused host
Test task with `--no-daemon --rerun-tasks`. After the host advanced to the recorded commit, final
positive invocation `49f8c6cb-e547-4fea-9bc1-c53be48fff75` executed all 537 host tasks and passed
11 suites / 49 tests
with no failure, error, or skip. The report hashes all 25 production sources, 13 test sources and 15
external integration/resource files, contains neither workspace absolute path, and has SHA-256
`b4c8e257e31c151fd069cac26a3d33470ec8f4dfacc6d7760b163b542f57b53a`; its self-recorded verifier
SHA-256 is `579cc569791205e89ad7acba3d30bdcee5a1e9bce45aa7e6bd56bc7f34158d79`.

An additional non-gate host packaging check completed `:app:assembleAppDebug`. The current mixed
workspace universal APK is 43,991,748 bytes with SHA-256
`3fb6642c88e62a99411bd7c266d537f793c54d3339896792b6b22fbb1fee9dc6`; `apkanalyzer dex packages`
found the host R8 package, frozen R8 API namespace, and exactly the intended two-, three-, and
five-argument `loadJarWithR8` signatures. This APK is not a frozen Gate artifact, published release,
or installed artifact. The host build banner's bundled R8 8.13.19 remains AGP's APK/D8 packaging
toolchain, not the provider compiler identity, which remains fixed R8 8.13.17.

Online generated documentation, the non-versioned Offline Docs asset copy, the TypeScript
declaration repository, and the Ace bundled declaration copy now describe the same three overloads;
documentation generation/check and `tsc --noEmit` pass. This is local synchronization, not remote
publication.

This closes all three local G3 implementation checkboxes. At its historical evidence boundary the
v4 report accurately sets
`hostIntegrated=true`, `publicOrScriptEntry=true`, `runtimeDispatchImplemented=true`,
`postDispatchRunnerVerified=true`, artifact adoption and persistent R8 cache true, while keeping
`binderVerified=false`, `deviceVerified=false`, and `published=false`. G5 later supplies local
signing and G6 supplies cross-APK Binder/PFD, device execution, and real Android process-death
evidence without rewriting the G3 report. Retrace execution and remote publication remain open.

## G4: Compatibility and release

- [x] Run compatibility corpora across API 24-36 and supported Java/Kotlin inputs.
- [x] Publish append-only independent local signed APK/API history and same-environment reproducible release evidence.
- [ ] Publish independent remote APK/API history (explicitly deferred by the owner; local-only for now).
- [x] Complete authorized device acceptance without reusing unrelated device evidence.

G4 local compatibility evidence (2026-08-24): the dedicated corpus contains 26 real-R8 cells,
formed by generated Java `--release 8` and project-compiled Kotlin inputs at every compiler
`minApi` from 24 through 36. Each cell carries reflection, a runtime-composed class name, JNI
native methods, Java serialization hooks, and an AutoJs6 script-facing public API. An unkept
`RemovedDecoy` must disappear from DEX and appear in usage, so the corpus does not pass by globally
disabling shrinking. `minApi` remains a compiler parameter rather than a device API execution
claim.

Before R8, an isolated JVM loader executes the reflection, dynamic-name, serialization, and public
API controls and inspects the JNI native modifiers. The same input then traverses the production
canonical materializer, fixed R8 8.13.17, DEX packager, and five-artifact codec. A structural DEX
parser verifies actual class definitions, encoded fields/methods and native access flags; mapping,
seeds, usage, exact artifact roles, hashes, and canonical `classes*.dex` topology are also checked.
Each cell atomically writes a path-free receipt with ART execution, JNI linking, and device claims
fixed false. The host side separately forces all 8 existing Rhino/runtime route tests.

The invocation-bound verifier has a fail-closed Test producer and atomically replaces its fixed
report. Final positive invocation `0c7b701f-918d-4b45-9da4-e731b27cfe63` passed provider 26/26 and
host 8/8; the host child actually executed 537/537 tasks. All 26 path-free receipts are individually
bound into the final report.

The final G4 report SHA-256 is
`879d4682bdf485e71bd883308d5a59eebaeb393b93973f131cbd14cbdf9214f1`; its self-recorded verifier
SHA-256 is `ea92853a859982dfc2d528585088a1f6452a578a1602f2453e1df3b2d6bf2640`. It binds the current G2
report `c7913af510bc24ab2a24c989886722f351e4d72f307f7fc0bff4aba6e296bfae`, G3 report
`b4c8e257e31c151fd069cac26a3d33470ec8f4dfacc6d7760b163b542f57b53a`, exact corpus sources,
host route sources, design, verifier, and all receipts; an independent rehash found zero mismatch.
The G4 corpus report itself closes only the local compiler/artifact compatibility checkbox and
correctly keeps its historical publication claims false. Optimized DEX was not run on ART, JNI was
not linked, and no APK was installed. The separate G5 evidence below closes local signing,
same-environment reproducibility, and append-only local history without rewriting the G4 report;
G6 closes Binder/device acceptance. Remote publication remains open.

G5 local signed-release evidence (2026-08-25): two different temporary source snapshots each run
exactly `:app:assembleRelease` offline with daemon/build/configuration caches disabled and all 48
tasks forced to rerun. Their 7,656,013-byte unsigned APKs are byte-identical with SHA-256
`f12235ce6922ce4b1cb04c5b31a73e72be5a3d60110a2c707824ffafc0b0c125`. Independent signing with
the authorized external AutoJs6 configuration produces identical 7,662,017-byte APKs with SHA-256
`ecf88bdf4800d04b7002cefc8ef7605a8e1feb69e1003cd56d6b604e50bc7a30`.

`apksigner` verifies exactly one certificate, SHA-256
`31a681fcfffb3e428420cae280ded89292b12a3b0f59e19b7a73e32a8ae4c213`, across API 24-36 with
v2/v3 true and v1/v3.1/v3.2/v4 false. The manifest binds 48 release-input files under source
fingerprint `75e2269ae8c3f5490cd72f4569cade2e61d3f097ee2bcceefd459eb28f919ef5`, both frozen G1 AARs,
the current G2/G3/G4 reports, publisher, design, and toolchain. The authoritative append-only
generation is `releases/provider/0.1.0-provider-dev/local.4/`; its manifest SHA-256 is
`20c1c80d431c9c08c8f9aa8ad23070f25386a4290f38446384770c5eb76f4b81`.

`local.1` remains the bootstrap generation. `local.2` removed a PowerShell module-autoload hash
dependency but device acceptance exposed missing Java 11 core-library desugaring on API 24-28.
`local.3` added pinned desugaring; AVD acceptance passed, then Sony API 28 denied
`/proc/self/fdinfo`. `local.4` replaces procfs parsing with zero-byte public `Os.read`/`Os.write`
kernel capability probes and retains `Os.fstat` alias rejection. No generation was overwritten or
deleted.

Before publication, negative invocation `c78a89fc-6f6a-4044-a74e-f61ab24caf00` supplied an
unrelated expected source fingerprint, exited before build/signing, wrote `passed=false` with Gate
SHA-256 `efbc4706541894b0e44ee469c718b4aae24971a72b9b2f1d95487dde9878ab61`, created no `local.4`,
and left the first three generation tree digests unchanged. First positive invocation
`1d057b71-15c6-4edb-a118-62a7c0ebda23` created `local.4`; final invocation
`a85ba7ca-0177-4575-8f20-c6d35d58e1ea` rebuilt both snapshots and returned `IDENTICAL`. Final G5
Gate SHA-256 is `994a9ba471e94aef78423a4bb313dea2be84a4a77c7b731665e1cfd28ab0826f`; publisher SHA-256 is
`086debf76deca7570e6039bd1b572ab45e59bbad9db248efb087b9e2ae7d0087`. G5 itself records
`localPublished=true`, `remotePublished=false`, and the correct historical pre-device
`deviceVerified=false`.

G6 authorized device evidence (2026-08-25): the standalone verifier requires an explicit consent
switch and three distinct non-protected serials. It does not install or uninstall packages. It
pulls the already installed host, instrumentation, and provider base APKs, requires the single
authorized signer, and requires every installed provider to exactly equal the official `local.4`
length and SHA-256. Its fixed matrix is Sony G8441 API 28 arm64, API 28 x86_64 AVD, and API 25 x86
AVD.

Each target ran one happy-path test and two lifecycle/process-death tests: 9/9 tests and exactly 9
validated receipts. The happy receipts prove real same-signer cross-APK Binder/PFD, fresh R8
8.13.17, a verified cache hit, all five artifacts, and production R8-only `answer42`. Lifecycle
receipts prove blocked-pipe BUSY behavior, a single terminal, idempotent cancel/close, callee PFD
ownership and EOF, gate recovery, and hostile `INVALID_BUNDLE` / `INPUT_VALIDATION`. Process-death
receipts prove exact-provider force-stop, Binder death, zero provider terminal callbacks, EOF,
identity revalidation, authenticated rebind, and recovery.

Final G6 invocation `1d978a79-4d08-41d8-a443-0115fb91cb59` has Gate SHA-256
`24fc3e2b09182859e4405ab1d125efd2fefdced843f0f89bc106c70e60e32970`; verifier SHA-256 is
`c4abd0d0e9faf41a72d2efbc500496e1d949acd15099eb4ca519de0586b809fd`. An independent rehash of
all prerequisite reports, androidTest sources, current identity, design, and verifier found zero
mismatch; the report contains no local absolute path, signing-material name, secret, or protected
serial. Current claims now set Binder/PFD lifecycle/process-death/physical-device/device verification
true while keeping retrace, JNI linking, and remote publication false. No Git push, remote release,
or remote Maven upload occurred; `QV710AF65F` and `968e9f18` were not touched by G6.

After the final Gate and independent rehash, the exact host, instrumentation, and provider packages
installed by this campaign were removed from `BH900ASK9E`, `emulator-5560`, and `emulator-5556`.
The campaign-owned API 28 AVD was then stopped; Sony and the API 25 AVD were rechecked clean. This
post-evidence cleanup removed only campaign app data and does not mutate the frozen G6 report or any
local release generation.

## G7: ART, JNI, and Retrace closure

- [x] Replace device boot-classpath resource shells as compiler input with a byte-pinned Android
  platform library while retaining a device-runtime fingerprint.
- [x] Execute the optimized fixture through the explicit host/provider route on API 25, API 28,
  and API 37 ART, including a 16 KiB page-size AVD.
- [x] Link ABI-specific JNI libraries and verify real instance and static native calls.
- [x] Verify the mapping hash and execute pinned R8 Retrace against an obfuscated ART stack.
- [x] Publish an append-only signed `local.5` generation locally; keep remote publication deferred.

G7 provider correction evidence (2026-08-25): the first real ART campaign exposed that the
device-reported Android boot-classpath JARs on the selected devices were resource shells without
the class definitions R8 requires. The provider now packages a byte-pinned Android 36 platform
library, materializes it atomically inside private storage, verifies it before use, and gives R8
that coherent compiler library instead of the stripped device shells. Runtime-library identity
still binds the observed device boot files together with the compiler library. The embedded
27,768,026-byte platform library has SHA-256
`d9eb9da824d9e247a352f570f01e1169e725b2954bca9e283a71786c59b59f9a`.

The same correction also adds bounded, path-redacted R8 diagnostics that survive provider startup
and import failures without leaking absolute paths or preserving a stale PASS. Provider verification
now passes 8 suites / 50 tests, including dedicated platform-materialization and diagnostic
collector tests. Android lint remains 0 errors / 2 version advisories, and both Debug and Release
assemble successfully.

The append-only `releases/provider/0.1.0-provider-dev/local.5/` generation preserves every earlier
local release. Two isolated offline snapshots, with all tasks rerun, produced the same
33,150,783-byte unsigned APK with SHA-256
`9335c3142ce48975741bcd5e0a5937c3340f1a1c574dfd20238ec0bc3528100b`; independent signing
produced the same 33,155,599-byte APK with SHA-256
`84447972cb0e4e020e5a696d0d2dabeb273e2990067a62f2d1b7590add628265`. It has exactly the
authorized certificate SHA-256
`31a681fcfffb3e428420cae280ded89292b12a3b0f59e19b7a73e32a8ae4c213` and verifies with v2/v3.
The release manifest SHA-256 is
`d97ed12a150a991b30e2697258ae0e381afa80667aed5e8272a5a5ebd4ec1583`, under source
fingerprint `4155d375952d6946a12034b91a4ce97e676ae58f33f42382430cced042b0a164`.
Final local-release invocation `2efce169-b337-47e5-8814-b2aa152232a4` returned `IDENTICAL`; its
Gate SHA-256 is `fe3df1fdce2b6ff675b41cad8d2da4720a6554da86230f11d1440f1d5f66953d`.

The authorization-bound G7 runtime matrix then installed the exact `local.5` bytes only on Sony
G8441 API 28 arm64, an API 25 x86 AVD, and an API 37 x86_64 16 KiB AVD. All 3/3 focused tests and
3/3 structured receipts passed. Each target executed optimized DEX on ART and verified reflection,
a runtime-composed class name, Java serialization, the script-facing entry, removed-decoy
shrinking, obfuscated failure capture, and real JNI calls returning `42` and `g7-jni-static`.

For every receipt, the verifier separately checked the embedded mapping hash and ran real Retrace
from the byte-pinned 18,279,079-byte R8 8.13.17 JAR, SHA-256
`d31fd0dc751d48740009cdd9a485126acb1d0d14c59b9f05579479940f4adf74`, on JDK 21. All three
retraced stacks restore `G7ObfuscatedCrash.explode`, source line 8, and
`G7RuntimeFixture.crashForRetrace`, while removing the raw obfuscated frame.

Final G7 invocation `36e7e2ff-b734-4034-96ab-cce5a0a037f5` has Gate SHA-256
`263a80a840b93d73de31e727ce9a76a824e44f326f3ae99b22a6f64850a466ff`; verifier SHA-256 is
`d4a76471ea2b1d8862027a9a8ad94b4825d6910ab5ba91a6aa282f1e395fd2e5`. Its prerequisite and
source bindings independently rehash without mismatch, and it records local publication, ART,
reflection, dynamic-name lookup, serialization, script entry, JNI, mapping verification, Retrace,
API 25/28/37, physical-device, and 16 KiB claims true while keeping `remotePublished=false`.

The verifier itself installed or removed nothing and performed no force-stop. After the final Gate,
campaign packages were removed from the API 25 AVD and physical target; the API 37 AVD's pre-existing
host and instrumentation APKs were restored to their exact original bytes and the provider was
removed. Raw mapping/stack material and temporary APK backups were deleted after path validation.
No protected device, Git remote, remote release, or remote Maven repository was touched. G4's
remote-publication checkbox therefore remains the only deliberately open release item.
