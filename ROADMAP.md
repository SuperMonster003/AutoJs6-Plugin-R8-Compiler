# AutoJs6 R8 Compiler Roadmap

Updated: 2026-08-14

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

- [ ] Add a separately identified application and isolated provider service.
- [ ] Implement bounded R8 execution and atomic multi-artifact publication.
- [ ] Verify Binder/PFD lifecycle, cancellation, ownership, hostile inputs, and process death.

## G3: Host integration

- [ ] Add default-off, explicit exact-component selection with signer and protocol pinning.
- [ ] Prove that every post-dispatch failure remains an R8 failure and never falls back to D8/dx.
- [ ] Isolate R8 cache identity from the existing D8 semantic cache.

## G4: Compatibility and release

- [ ] Run compatibility corpora across API 24-36 and supported Java/Kotlin inputs.
- [ ] Publish independent APK/API history and reproducible release evidence.
- [ ] Complete authorized device acceptance without reusing unrelated device evidence.
