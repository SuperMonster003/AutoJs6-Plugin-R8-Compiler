# AutoJs6 R8 Compiler Contract

This repository owns the independent R8 compiler protocol for AutoJs6.

The current `0.1.0` milestone is contract-only. It builds an Android library AAR containing the
typed API, TaggedWire codecs, canonical streaming bundle formats, validation, and generated Binder
interfaces from three source-frozen AIDL descriptors. It deliberately contains no installable
application, discoverable Android service,
R8 engine dependency, host integration, semantic fallback, or device evidence.

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

## Evidence boundary

Passing source, JVM, lint, and AAR gates proves only `CONTRACT_AAR_ONLY`. Until later roadmap
stages provide an independently installable provider and explicit host integration, all of the
following remain false: provider implemented, manifest discoverable, host integrated, Binder/PFD
verified, R8 executed, retrace executed, device verified, remotely published, and consumed by the
host.

No ADB, connected-device, install, or protected-device task is part of this milestone.

The verified local `0.1.0` contract distribution is append-only under
`plugin-api/r8-compiler-api/releases/0.1.0/`. Its manifest co-records the current production-source
snapshot and separately scanned AAR digests; this is not a cryptographic or reproducible-build
proof that the binaries were derived from that source. It also records the three AIDL source
descriptors and generated Binder class boundary, exact interface method descriptors and Binder
constants, and a strict classfile golden for the Java-visible JVM binary ABI. The golden does not
claim to freeze Kotlin source or metadata compatibility. The manifest also records the exact
intra-distribution `r8-compiler-api -> protocol-wire-api` edge, observed external Kotlin runtime
requirements whose versions are unverified and whose artifacts are not staged, and a detached
Java compile. It is a local contract artifact, not a remote publication, a self-contained runtime
distribution, or evidence that a provider consumed the protocol.
