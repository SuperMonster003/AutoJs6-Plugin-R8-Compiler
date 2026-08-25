# R8 compatibility corpus v1

This corpus is the local compiler/artifact gate for compatibility-sensitive R8 inputs. It does
not execute optimized DEX on ART and does not replace cross-APK, JNI-link, installation, or device
acceptance.

## Matrix

The matrix contains 26 cells:

- input language: generated Java `--release 8` and project-compiled Kotlin;
- R8 `minApi`: every integer from 24 through 36;
- compiler: the provider's fixed R8 8.13.17 command path;
- profile: `FULL_RELEASE`, with shrinking, optimization, and obfuscation enabled;
- fallback: `NONE`;
- output: the canonical five-artifact bundle.

`minApi` is an R8 compiler parameter. A passing cell is not evidence that Android API 24-36
devices executed the output.

Each language program carries all five compatibility surfaces:

1. a constant reflective class lookup and reflective member invocation;
2. a class name assembled from a runtime method argument;
3. JNI native methods whose class, member names, and descriptor classes must survive;
4. a `Serializable` state class with `serialVersionUID`, `writeObject`, `readObject`, and
   `readResolve` hooks;
5. a public constructor/method/static-method surface intended for later AutoJs6 script access.

An unreferenced `RemovedDecoy` is deliberately omitted from the rules. Every cell must show that
the seven protected classes remain defined while that decoy is absent from DEX and present in the
R8 usage report. This ensures the corpus does not accidentally disable shrinking globally.

## Per-cell verification

Before R8, the original JAR is loaded in an isolated JVM class loader. Reflection, runtime class
name construction, Java serialization round-trip, and the public script-style API are executed;
the JNI methods are reflected and required to carry the JVM native modifier without being called.

The same JAR, explicit rules, and (for Kotlin) bounded Kotlin runtime classpath JARs are then sent
through the provider's production canonical input materializer, R8 engine, DEX packager, and
canonical artifact-bundle codec. Every result must contain exactly:

- `DEX_ZIP`;
- `MAPPING_TEXT`;
- `SEEDS_TEXT`;
- `USAGE_TEXT`;
- `RETRACE_METADATA`.

The test parses each standard indexed DEX header, string/type/field/method/class table and encoded
class-data list. It verifies the protected class descriptors, reflective/dynamic target members,
native access flags and names, serialization field/hooks, and script-visible method names. It also
checks mapping, seeds, and usage observations. The test does not treat a raw byte substring as a
class-definition proof.

After a cell passes, it atomically writes one repository-local JSON receipt. Receipts contain no
absolute paths, make no determinism claim, and explicitly record:

- `realR8Executed=true`;
- `canonicalFiveArtifactBundleConsumed=true`;
- `postR8DexRuntimeExecuted=false`;
- `jniLinked=false`;
- `deviceVerified=false`.

## Host script boundary

The G4 verifier separately forces the existing host
`R8CompilerExplicitRuntimeRouteTest` suite. Its eight tests cover the exact three-overload public
shape, Rhino array conversion into `String[]`/`int[]`, canonical argument resolution, verified DEX
publication, disabled/no-selection behavior, provider failure, and cancellation without D8/dx
fallback.

The combination proves a local script route and R8 artifact compatibility corpus. It still does
not prove that an AutoJs6 script loaded and invoked the optimized DEX on an Android device.

## Invocation-bound gate

`verifyG4CompatibilityCorpus` atomically writes a fixed `passed=false` report before it launches
either Gradle child. It forces both provider and host tests with `--no-daemon --rerun-tasks`,
requires the exact 2 x 13 receipt set, binds the current G2 and G3 report hashes, and records its
own verifier and corpus-source hashes. A bad test filter, compilation failure, missing receipt,
receipt drift, or host-route failure leaves the fixed report negative.
