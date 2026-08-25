# R8 compiler 0.1 contract boundary

The `0.1` protocol represents one operation only: an explicit, full R8 compilation with no
semantic fallback. The profile always enables shrinking, optimization, and obfuscation. There is
no D8 mode, debug mode, automatic provider choice, or per-transform switch in this API.

The caller supplies one path-free canonical input bundle through a read-only file descriptor. The
bundle contains one program JAR, ordered classpath JARs, one or more explicit keep-rule documents,
and optional consumer-rule documents bound to the owning classpath JAR ordinal and digest. A
provider must not discover implicit rules inside archives. Rule admission is strict UTF-8 and
fail-closed; filesystem, include, input/output redirection, mapping import, print, dictionary, and
global profile-control directives are outside protocol version 1.

The caller closes its local PFD instances after Binder dispatch; the provider owns only its
received duplicates and must close them on every setup-failure or terminal path. This ownership
law is frozen here. Input and output descriptors must not alias the same endpoint; access-mode,
alias, and cross-process ownership enforcement remain G2 conformance gates.

The provider writes one path-free canonical artifact bundle through the caller-owned output file
descriptor. It contains `DEX_ZIP`, `MAPPING_TEXT`, `SEEDS_TEXT`, `USAGE_TEXT`, and
`RETRACE_METADATA` identities in exact canonical order. Mapping metadata binds the mapping digest,
compiler/capability/runtime fingerprints, input-set fingerprint, min API, and profile. It is
provenance for a future retrace contract, not evidence that retrace was executed.

Every request pins the runtime and capability fingerprints, selects `R8_EXPLICIT`, and carries
`fallbackPolicy=NONE`. After dispatch, unavailable, busy, cancelled, incompatible, and failed
outcomes remain R8 outcomes; this contract defines no retry into D8, dx, or another provider.

The AIDL callback contract permits at most one started event and strictly increasing progress
sequence values. When started exists, every progress sequence is greater than its sequence.
Exactly one terminal callback follows. `cancel()` and `close()` are idempotent. These are
contract requirements only in G1; Binder/PFD lifecycle compliance requires a later provider and
host integration test.

## Frozen protocol budgets

Provider capabilities may advertise values no larger than the following V1 ceilings, and a
request may select only values no larger than those capabilities:

| Boundary | V1 ceiling |
|---|---:|
| Android API / `minApi` | 24 through 36, inclusive |
| Program JAR | 128 MiB |
| Classpath JARs | 32 files, 64 MiB each, 128 MiB total |
| Keep / consumer rule files | 16 / 32 files |
| Rules | 256 KiB per file, 2 MiB total, 4,096 lines/file, 16,384 lines total, 16 KiB/line |
| Input bundle | 260 MiB |
| Archive expansion | 20,000 entries/JAR, 60,000 total; 512 MiB uncompressed inputs |
| Class bytes | 8 MiB/class, 256 MiB total |
| Output bundle | 256 MiB |
| DEX ZIP / mapping | 192 MiB / 32 MiB |
| Seeds / usage / retrace metadata | 16 MiB / 16 MiB / 256 KiB |
| Diagnostics / concurrent sessions | 64 KiB / 1 |
| Default / maximum timeout | 120 s / 300 s |

A provider-enforced request deadline terminates with `R8ErrorCode.TIMEOUT` and the active
`R8FailurePhase`; it is a failure, not a caller cancellation. The provider must not map a timeout
to `INTERNAL`, `COMPILATION_FAILED`, or either cancellation reason.

The contract layer checks metadata, framing, rule admission, descriptor digests, and stream EOF.
Streaming entry callbacks receive untrusted bytes before the enclosing entry and bundle digests are
final. Callers must write only to isolated staging, discard all staged state on any exception, and
must not compile, load, publish, or otherwise consume entries until the complete reader returns
successfully.
Archive-entry, expanded-byte, class-file, and DEX-entry counts are provider responsibilities in G2;
G1 freezes their capability fields and upper bounds but does not claim that an R8 engine enforced
them.

## G2 implementation status

The current local provider implements those G2 responsibilities without changing protocol 1.0.
It consumes the immutable 0.1.0 AARs, materializes inputs into a private recoverable workspace,
enforces archive/class/rule budgets, runs fixed R8 8.13.17, validates indexed DEX output, and
atomically finalizes the five-artifact bundle in local staging before the output PFD is claimed.
The API 26+ runner supplies an R8 cancellation checker; the API 24/25 CLI runner is release-only
and injects only provider-owned report destinations. Neither runner contains a D8/dx fallback.

This status is backed by 6 suites / 42 tests, including real JVM compiler round-trips, the pinned
core-library/NIO desugaring boundary required by the API 24 provider floor, and the later
compatibility cells, plus Android lint/APK builds. Folding G4 tests into the full provider
prerequisite set does not extend the G2 evidence boundary. Its historical report therefore keeps
Binder/PFD lifecycle, cross-process cancellation, process death, installed-manifest discovery,
cross-APK host consumption, and device claims false. The later G6 report verifies those behaviors
incrementally without rewriting G2.

## G3 host integration status

The sibling host now exposes the independent compiler only through the explicit
`runtime.loadJarWithR8(...)` method family and a default-off Developer-options exact-component
selector. The existing D8/dx-backed load methods retain their behavior. The process-wide R8
dispatcher consumes the frozen contract, snapshots the canonical input set, owns the
request/descriptors/session, validates the callback law, adopts the complete five-artifact bundle,
and publishes only a verified R8 cache generation.

Only the verified `DEX_ZIP` crosses the final execution seam. The class loader independently
rehashes it, adopts a read-only `r8_verified_` copy, and has no local D8/dx compiler path from that
entry. The invocation-bound G3 v4 JVM matrix covers the public overload shapes, Rhino array
conversion, explicit rules/classpath ownership, success, missing selection, provider failure,
cancellation, corrupt transactions, and cache behavior. It proves local host integration and that
every failure remains terminal R8. Its historical report does not claim cross-APK Binder/PFD,
installation, device execution, Android process death, signing, publication, or retrace execution;
G5 later supplies local signing and G6 supplies the device/runtime claims.

## G4 local compatibility status

The compatibility corpus executes the production materializer, fixed R8 8.13.17 engine, DEX
packager, and five-artifact codec for generated Java and project-compiled Kotlin inputs at each
compiler `minApi` from 24 through 36. Every one of the 26 cells carries reflective access, a
runtime-composed class name, native methods, Java serialization hooks, a script-facing API, and an
unkept removal decoy. An indexed-DEX parser verifies class definitions, encoded members and native
flags rather than relying on raw substring presence. Mapping, seeds, usage, DEX topology, artifact
roles, sizes, and digests are also checked.

The original JAR controls execute in an isolated JVM loader, and the host's eight Rhino/runtime
route tests are forced separately. The optimized DEX is not executed on ART, JNI is not linked,
and no Android serialization or script invocation of the optimized class is claimed. The G4
evidence boundary is therefore `R8_COMPATIBILITY_CORPUS_JVM_ARTIFACT_AND_SCRIPT_ROUTE`, with
Binder, device, installation, signing, publication, reproducibility, and retrace execution false
inside that historical G4 report.

## G5 local release status

G5 separately signs the provider with the externally authorized AutoJs6 signing configuration and
publishes only to the repository-ignored append-only local release directory. Two independent
offline source snapshots, with Gradle caches disabled and every task rerun, must produce the same
unsigned APK. Two independent signing passes must then produce the same signed APK. This is a
same-machine/toolchain repeatability boundary, not hermetic cross-environment reproducibility.

The publisher validates exactly one signer, API 24-36 v2/v3 signatures, and the complete reserved
APK manifest identity. Passwords cross only process-local environment variables; no password,
actual alias, external signing path, keystore name, or properties-file name enters the manifest or
Gate. The local distribution contains exactly the signed APK, both byte-frozen 0.1.0 AARs, and one
deterministic manifest. Existing bytes may only be re-read as `IDENTICAL`.

At its historical G5 boundary this closes local signing and append-only local APK/API history with
`localPublished=true`. It does not alter the then-current identity's official/remote
`published=false` meaning, and records `remotePublished=false`, `installed=false`,
`binderVerified=false`, and `deviceVerified=false`.

## G6 authorized device status

G6 consumes the final positive G2-G5 reports and requires the installed provider base APK on every
target to exactly equal the append-only `local.4` signed APK. One physical API 28 arm64 device, one
API 28 x86_64 AVD, and one API 25 x86 AVD each execute the dedicated happy-path and lifecycle test
classes. The verifier validates 9/9 tests and exactly nine structured receipts rather than trusting
AndroidJUnitRunner success text alone.

The happy receipts prove exact same-signer cross-APK Binder, canonical input/output PFD streaming,
fresh fixed-R8 execution, five-artifact adoption, a verified cache hit, and production R8-only DEX
execution. Lifecycle receipts prove the BUSY/single-terminal law, idempotent cancel/close, callee
descriptor ownership, EOF, recovered admission, and hostile input rejection. Process-death receipts
prove exact-provider force-stop, Binder death, zero provider terminal callbacks, EOF, identity
revalidation, authenticated rebind, and recovery.

G6 is explicitly authorized and does not itself install or uninstall packages. It force-stops only
the exact provider package and blocks the protected physical serials. Its historical incremental claims
set Binder, PFD lifecycle, process death, physical-device, and device verification true while keeping
remote publication, retrace execution, and JNI linking false. See `device-acceptance-v1.md` for the
exact matrix and fail-closed verifier boundary.

## G7 ART, JNI, and Retrace status

G7 creates the append-only signed `local.5` generation after correcting the provider's compiler
library input to a byte-pinned Android 36 platform JAR. Its authorized three-target runtime matrix
executes optimized output on API 25/28/37 ART, links arm64/x86/x86_64 JNI, verifies mapping hashes,
and runs pinned R8 8.13.17 Retrace against each captured obfuscated stack. Both G7 Gates are local
historical boundaries and correctly retain `remotePublished=false`.

## G8 private remote publication status

G8 changes no compiler, protocol, APK, AAR, signer, device, or G7 result. Before any Git remote
exists, it privacy-normalizes both author and committer identities while preserving each commit's
message, dates, topology, and tree. It then creates an empty GitHub Private repository, verifies that
visibility before uploading objects, and pushes only the normalized source branch and noreply
annotated tag.

The Private prerelease republishes the exact four `local.5` files plus a canonical SHA256SUMS asset.
Two separate post-upload campaigns download all five assets and revalidate names, lengths, and
SHA-256 digests. The invocation-bound G8 Gate writes PASS only after its downloaded temporary tree
is safely removed. Current publication claims are `remotePublished=true`,
`remoteVisibility=PRIVATE`, and `publicPublished=false`; no remote Maven or public-visibility claim
is included. See `private-remote-release-v1.md` for the privacy, remote, and fail-closed boundaries.
