# AutoJs6 R8 Compiler

This repository owns the independent R8 compiler protocol for AutoJs6.

The byte-frozen `0.1.0` contract distribution contains the typed API, TaggedWire codecs, canonical
streaming bundle formats, validation, and generated Binder interfaces from three source-frozen
AIDL descriptors. The repository has now advanced through authorized G8 private remote publication:
`:app` builds a separately identified Android application and dedicated `:r8` service that
consumes those frozen AAR bytes and executes fixed R8 8.13.17. A sibling AutoJs6 working tree now
contains a default-off Developer-options selector, the explicit `runtime.loadJarWithR8(...)`
production entry, a process-wide host transaction/adoption dispatcher, and an isolated persistent
R8 cache. A 26-cell Java/Kotlin compatibility corpus covers every compiler `minApi` from 24 through
36. G5 adds an append-only, host-signed, repository-local provider release and two-snapshot
same-environment reproducibility evidence. G6 binds those exact release bytes to real cross-APK
Binder/PFD execution, lifecycle, hostile-input, and process-death receipts on one physical device
and two AVDs. G7 adds the corrected `local.5` provider, executes optimized output and JNI on API
25/28/37 ART (including a 16 KiB AVD), and runs mapping-hash-verified R8 Retrace over each captured
obfuscated stack. G8 privacy-normalizes the complete Git history, publishes the exact `local.5`
APK/API bytes in a private GitHub prerelease, and independently downloads and rehashes every asset.
There is no semantic fallback. Public publication remains false.

## Frozen boundaries

- API namespace: `org.autojs.plugin.r8compiler.api`
- Reserved discovery action: `org.autojs.plugin.R8_COMPILER`
- Engine identity: `r8-compiler`
- Compiler family/intent/fallback: `R8` / `R8_EXPLICIT` / `NONE`
- Compilation profile: `FULL_RELEASE` (shrink + optimize + obfuscate)
- Protocol: `1.0`
- Distribution: `org.autojs.plugin.r8compiler:r8-compiler-api:0.1.0`

The AIDL surface reserves one compile session. Compilation streams one canonical input bundle to
one canonical multi-artifact output bundle. `RETRACE_METADATA` binds mapping provenance for a later
retrace contract; this milestone intentionally exposes no retrace RPC.

The R8 API does not depend on, extend, or alias `dex-compiler-api`. In particular, the existing
DEX wire `RELEASE` mode remains a D8 compilation mode and is not an R8 request.

## Provider boundary

- Application ID: `io.github.supermonster003.autojs6.plugin.r8compiler`
- Component: `io.github.supermonster003.autojs6.plugin.r8compiler/.R8CompilerService`
- Dedicated process: `:r8`
- Input: one canonical program/classpath/rules bundle through a read-only PFD
- Output: one canonical five-artifact bundle through a write-only, non-aliasing PFD
- Compiler: fixed R8 `8.13.17`, full release profile, no D8/dx fallback

The service uses private per-session staging and does not expose caller or provider paths on the
wire. It admits and hashes the complete input before R8 execution, then creates and verifies all
five output artifacts before writing the caller-owned output transaction. The same-signature exact
AutoJs6 host is the only accepted Binder caller. A verified, byte-pinned Android 36 platform
library supplies coherent compiler definitions even when device boot-classpath JARs are resource
shells; the runtime-library fingerprint still binds the observed device boot files. G6 verifies the
installed same-signer APK boundary on Android API 25 and 28, and G7 executes the optimized result on
API 25, 28, and 37 ART.

## Host control-plane boundary

The sibling AutoJs6 host consumes the byte-frozen R8 API AAR through a local wrapper and keeps the
protocol source byte-identical to G1. Its dedicated Developer-options preference is default-off,
discovers only the reserved R8 action, and persists one user-selected exact same-signature
component. The host pins package and signer identity, binds explicitly, re-inspects identity after
connection, negotiates protocol 1.0 and the exact full-release five-artifact capability set, and
rejects callback UIDs other than the pinned provider UID.

R8 preference keys, transport types, transaction workspace, failure state and semantic cache
identity are separate from the DEX host path. The three `runtime.loadJarWithR8(...)` overloads
require explicit keep-rule paths and optionally carry ordered classpath JARs plus consumer-rule
owner ordinals. They do not alter `runtime.loadJar(...)` or `runtime.loadJarWithClasspath(...)`.
The process-wide dispatcher snapshots bounded inputs, constructs only explicit
full-release/no-fallback requests, owns distinct input/output descriptors, validates the callback
law, adopts all five verified artifacts, and commits an R8-only cache generation. The cache domain
is `autojs6:r8-compiler:v1`, the persistent directory is `r8-compiler-cache-v1`, and every opened
cache artifact is rehashed.

Only the already verified `DEX_ZIP` is handed to `AndroidClassLoader`. The loader independently
checks its size and SHA-256, atomically adopts it into a read-only `r8_verified_` cache file, and
cannot call a local D8/dx compiler through this seam. Missing selection, invalid arguments,
provider/transport failure, cancellation, corrupt output, cache failure, and class-loader failure
all terminate the explicit R8 call without D8/dx fallback.

## Evidence boundary

G1's immutable report remains `CONTRACT_AAR_ONLY` at commit
`2ce4d296a69fc78ff373a39630a1b3796bae9fe7`; this is the tree-identical,
privacy-normalized successor of the original local G1 commit, and later functional Gates do not
rewrite its evidence boundary.

The invocation-bound G2 v2 Gate proves `LOCAL_PROVIDER_JVM_AND_ANDROID_BUILD`: fixed R8 8.13.17,
the canonical five-artifact round-trip, provider JVM/security boundaries, pinned
`desugar_jdk_libs_nio` 2.1.5 for the API 24 runtime floor, 6 suites / 42 tests, lint 0 errors / 2
warnings, and Debug/unsigned Release APK assembly. Final invocation
`e8d210c8-0022-47be-9846-4a17b11c6e52` has Gate SHA-256
`c7913af510bc24ab2a24c989886722f351e4d72f307f7fc0bff4aba6e296bfae`; verifier SHA-256 is
`99b28d8df8e3fe3452903eb683f05d361b05e7585c4cbc8b383f2299c689f166`. The unsigned Release APK
SHA-256 is `ae5ba2c507d680352b5cea61d4cd02f3e2365c7c54bf44e4190602ffb08d364b`.

The invocation-bound G3 v4 Gate proves
`HOST_R8_EXPLICIT_SCRIPT_INTEGRATION_JVM_AND_ANDROID_COMPILE`: 11 focused suites / 49 tests, the
exact 25-file production R8 package, 13-file test package, 15 integration/resource files,
default-off exact selection, three public script overloads, canonical host transactions, strict
artifact adoption, read-only R8 DEX loading, no-fallback behavior, and persistent cache
publication/hit/corruption handling. It binds AutoJs6 commit
`e826803c18e1aecd16f687945a04c02eff6d398a`. Final invocation
`49f8c6cb-e547-4fea-9bc1-c53be48fff75` has Gate SHA-256
`b4c8e257e31c151fd069cac26a3d33470ec8f4dfacc6d7760b163b542f57b53a`; verifier SHA-256 is
`579cc569791205e89ad7acba3d30bdcee5a1e9bce45aa7e6bd56bc7f34158d79`.

The invocation-bound G4 v1 Gate proves
`R8_COMPATIBILITY_CORPUS_JVM_ARTIFACT_AND_SCRIPT_ROUTE`: 26 Java/Kotlin × `minApi` 24-36 real-R8
cells plus 8 host Rhino/runtime-route tests. Reflection, runtime-composed names, JNI descriptors,
serialization hooks, script-facing APIs, an actually removed decoy, DEX class data, and all five
artifacts are checked. Final invocation `0c7b701f-918d-4b45-9da4-e731b27cfe63` has Gate SHA-256
`879d4682bdf485e71bd883308d5a59eebaeb393b93973f131cbd14cbdf9214f1`; verifier SHA-256 is
`ea92853a859982dfc2d528585088a1f6452a578a1602f2453e1df3b2d6bf2640`. This historical Gate keeps
ART, Binder, device, and publication claims false; G6 supplies those incremental runtime claims.

The invocation-bound G5 v1 Gate proves
`LOCAL_SIGNED_PROVIDER_RELEASE_SAME_ENVIRONMENT_REPRODUCIBILITY`. Two isolated offline snapshots
each execute 48 Release tasks and produce the same 7,656,013-byte unsigned APK, SHA-256
`f12235ce6922ce4b1cb04c5b31a73e72be5a3d60110a2c707824ffafc0b0c125`. Independent signing
produces the same 7,662,017-byte API 24-36 v2/v3 APK, SHA-256
`ecf88bdf4800d04b7002cefc8ef7605a8e1feb69e1003cd56d6b604e50bc7a30`, with the single
authorized certificate SHA-256
`31a681fcfffb3e428420cae280ded89292b12a3b0f59e19b7a73e32a8ae4c213`. The authoritative
append-only directory is `releases/provider/0.1.0-provider-dev/local.4/`; manifest SHA-256 is
`20c1c80d431c9c08c8f9aa8ad23070f25386a4290f38446384770c5eb76f4b81`. Final invocation
`a85ba7ca-0177-4575-8f20-c6d35d58e1ea` re-created the candidate and returned `IDENTICAL`; Gate
SHA-256 is `994a9ba471e94aef78423a4bb313dea2be84a4a77c7b731665e1cfd28ab0826f`.

`local.1` through `local.3` remain byte-for-byte superseded history. `local.2` exposed missing
core-library desugaring on API 24-28; `local.3` fixed that and then exposed a Sony API 28 procfs
restriction. `local.4` replaces `/proc/self/fdinfo` access-mode inspection with zero-byte public
`Os.read`/`Os.write` kernel probes while retaining `fstat` alias rejection. G5 passes secrets only
through process-local environment variables and records `localPublished=true`,
`remotePublished=false`, and its historical pre-device `deviceVerified=false` claim.

The invocation-bound G6 v1 Gate proves
`AUTHORIZED_CROSS_APK_BINDER_PFD_DEVICE_ACCEPTANCE` for the exact installed `local.4` bytes. Its
matrix is Sony G8441 API 28 arm64, API 28 x86_64 AVD, and API 25 x86 AVD. Each device passes one
happy-path test plus two lifecycle/process-death tests: 9/9 tests and 9 validated JSON receipts in
total. The receipts prove same-signer cross-APK Binder, canonical PFD streaming, fresh R8 and cache
hit, five artifacts, production R8-only `answer42`, BUSY/single-terminal/cancel/close discipline,
callee PFD ownership and EOF, hostile `INVALID_BUNDLE` rejection, exact-package force-stop, Binder
death with zero terminal callbacks, identity revalidation, authenticated rebind, and recovery.
Final invocation `1d978a79-4d08-41d8-a443-0115fb91cb59` has Gate SHA-256
`24fc3e2b09182859e4405ab1d125efd2fefdced843f0f89bc106c70e60e32970`; verifier SHA-256 is
`c4abd0d0e9faf41a72d2efbc500496e1d949acd15099eb4ca519de0586b809fd`.

G6 was explicitly authorized and did not install or uninstall packages itself; the selected test
targets already contained the host, instrumentation, and official provider. It force-stopped only
the exact provider package for process-death coverage. The protected devices `QV710AF65F` and
`968e9f18` were not touched by G6. No Git push, remote release, or remote Maven publication was
performed at that historical G6 boundary; remote publication was still deliberately deferred. After
the final report was frozen,
the three campaign-owned packages were removed from the Sony and both AVDs, and the campaign-owned
API 28 AVD was stopped; this post-evidence cleanup does not alter the G6 report.

The invocation-bound G7 local-release Gate proves
`APPEND_ONLY_LOCAL5_PLATFORM_LIBRARY_FIX_RELEASE`. The provider packages a verified Android 36
platform library, 27,768,026 bytes with SHA-256
`d9eb9da824d9e247a352f570f01e1169e725b2954bca9e283a71786c59b59f9a`, instead of attempting
to compile against stripped device boot-classpath resource shells. Two isolated offline rerun
builds and signatures are byte-identical. The append-only signed `local.5` APK is 33,155,599 bytes,
SHA-256 `84447972cb0e4e020e5a696d0d2dabeb273e2990067a62f2d1b7590add628265`, with the same single
authorized v2/v3 certificate as `local.4`; its manifest SHA-256 is
`d97ed12a150a991b30e2697258ae0e381afa80667aed5e8272a5a5ebd4ec1583`. Final invocation
`2efce169-b337-47e5-8814-b2aa152232a4` returned `IDENTICAL`; Gate SHA-256 is
`fe3df1fdce2b6ff675b41cad8d2da4720a6554da86230f11d1440f1d5f66953d`.

The authorization-bound G7 runtime Gate proves `AUTHORIZED_LOCAL5_ART_JNI_AND_PINNED_RETRACE` for
the exact `local.5` APK on Sony G8441 API 28 arm64, API 25 x86 AVD, and API 37 x86_64 16 KiB AVD.
All 3 tests, 3 structured receipts, and 3 real Retrace executions pass. Optimized DEX runs on ART;
reflection, runtime-composed lookup, serialization, the public script entry, removed-decoy
shrinking, and arm64/x86/x86_64 JNI calls are verified. A byte-pinned R8 8.13.17 Retrace invocation
first verifies each mapping hash, then restores the original crash class, method, and source line
from the ART stack. Final invocation `36e7e2ff-b734-4034-96ab-cce5a0a037f5` has Gate SHA-256
`263a80a840b93d73de31e727ce9a76a824e44f326f3ae99b22a6f64850a466ff`; verifier SHA-256 is
`d4a76471ea2b1d8862027a9a8ad94b4825d6910ab5ba91a6aa282f1e395fd2e5`.

After the frozen G7 report, campaign packages were removed from the physical target and API 25 AVD;
the API 37 AVD's pre-existing host/instrumentation APKs were restored byte-for-byte and its provider
was removed. Temporary raw mapping, stack, and APK-backup material was deleted. No protected device,
Git push, remote release, or remote Maven publication was involved in that historical G7 boundary.

The invocation-bound G8 Gate proves `PRIVACY_NORMALIZED_PRIVATE_GITHUB_RELEASE`. Before the first
remote push, both local commits had their author and committer identities changed to the verified
GitHub ID-based noreply identity. Commit messages, author/committer dates, topology, and both trees
remained identical; the normalized G1/G7 commits are `2ce4d296a69fc78ff373a39630a1b3796bae9fe7`
and `884fe5be362f3ec2089514421cd4108b54349bf8`. The predecessor refs, reflogs, and objects were
removed from the repository and retained only in an external local recovery bundle that was never
pushed.

The source branch and annotated tag `v0.1.0-provider-dev-private.1` are hosted in the verified
Private repository
[`SuperMonster003/AutoJs6-Plugin-R8-Compiler`](https://github.com/SuperMonster003/AutoJs6-Plugin-R8-Compiler).
Its published prerelease contains exactly the signed APK, both frozen AARs, the `local.5` manifest,
and a canonical SHA256SUMS asset. All 5/5 assets were downloaded again and matched their local byte
lengths and SHA-256 digests. Final G8 invocation `ab010b7d-800f-43d9-acc9-27efb087efa2` has Gate
SHA-256 `ead4d551ae7eb13e319bc5ffed3639edc1ab96c6a85b9088ed7ca070f0a3e000`; verifier SHA-256 is
`644c8fce1097073b73abc1fef27a9aeaccd06c2db8b8c791c710e64f04b51d75`. Current claims are
`remotePublished=true`, `remoteVisibility=PRIVATE`, and `publicPublished=false`. No remote Maven
publication occurred; changing repository visibility is a separate future authorization and Gate.
The Gate's three-commit branch snapshot is an invocation-time fact; the later noreply evidence commit
that records this result advances `master` without moving or rewriting the release tag or assets.

The verified local `0.1.0` contract distribution is append-only under
`plugin-api/r8-compiler-api/releases/0.1.0/`. Its manifest co-records the current production-source
snapshot and separately scanned AAR digests; this is not a cryptographic or reproducible-build
proof that the binaries were derived from that source. It also records the three AIDL source
descriptors and generated Binder class boundary, exact interface method descriptors and Binder
constants, and a strict classfile golden for the Java-visible JVM binary ABI. The golden does not
claim to freeze Kotlin source or metadata compatibility. The manifest also records the exact
intra-distribution `r8-compiler-api -> protocol-wire-api` edge, observed external Kotlin runtime
requirements whose versions are unverified and whose artifacts are not staged, and a detached
Java compile. It is a local contract artifact, not a remote publication or a self-contained runtime
distribution. G2 now separately proves that the provider consumes those exact AAR bytes; it does
not rewrite the historical G1 report.
