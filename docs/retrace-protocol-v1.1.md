# R8 retrace protocol 1.1

Status: frozen for G10. The canonical byte distribution, Java-visible ABI baseline, source
fingerprint, normalized AIDL hashes, Binder transaction IDs, and detached-consumer result are
recorded under `plugin-api/r8-compiler-api/releases/0.2.0/` and
`plugin-api/r8-compiler-api/abi/0.2.0-java-visible-jvm-abi.txt`.

Protocol 1.1 is an append-only extension of the frozen 1.0 compile contract. It turns the
previously provenance-only `RETRACE_METADATA` artifact into an explicit, bounded, fail-closed
retrace operation. It does not change compilation semantics, cache-key semantics, the five compile
artifacts, or any existing Binder transaction ID.

## Compatibility boundary

`IR8CompilerProvider` keeps the 1.0 methods in their original order:

1. `getCompilerInfo()`
2. `getCapabilities()`
3. `openSession(...)`

Protocol 1.1 appends:

4. `getRetraceCapabilities()`
5. `openRetraceSession(...)`

An upgraded provider advertises `protocolMin=1.0` and `protocolMax=1.1`. Compile requests continue
to encode protocol 1.0 and continue to use schemas `0x52380001` through `0x52380015`. A 1.0 host
therefore sees a compatible provider and never calls the appended methods. A retrace host requires
1.1 to lie within the advertised range before it queries or dispatches retrace.

The existing `IR8CompilerSession` and `IR8CompilerCallback` lifecycle surfaces are reused because
their values are opaque byte arrays and their ownership law already fits retrace. Retrace callback
payloads always use retrace-specific schemas; compile and retrace schemas are never mixed within a
session.

## Selection and failure law

Retrace is available only through the same default-off, exact-component, same-signer provider
selection as compilation. No selected provider means failure. Binding failure, negotiation
failure, malformed input, provenance mismatch, timeout, cancellation, and retrace-engine failure
are terminal. None authorizes a local retrace implementation, another provider, D8/dx, or a retry.

The provider admits at most one process-wide compile-or-retrace session. A second session receives
the retrace `BUSY` or compile `BUSY` terminal result, as appropriate. `cancel()` and `close()` are
idempotent. At most one started event precedes strictly increasing progress events and exactly one
terminal event; no callback is legal after terminal.

## Path-free transport

The host sends only:

- tagged metadata bytes;
- a read-only descriptor for one canonical input bundle; and
- a distinct write-only descriptor for the raw retraced stack output.

No wire field contains a path, URI, cache directory, export directory, Android `Context`, Java
object, or filesystem capability. The caller retains and closes its local descriptor instances
after the Binder call. The provider exclusively owns its received duplicates until terminal
cleanup. Input and output descriptors must not alias.

## Retrace capability document

Schema `0x52380020` contains these required fields:

| Tag | Type | Meaning |
| ---: | --- | --- |
| 1 | int32 | compiler family (`R8=1`) |
| 2 | string | exact embedded R8 version |
| 3 | string | mapping format ID (`com.android.tools.r8.mapping`) |
| 4 | string | mapping format version (`1`) |
| 5 | int32 | input layout (`MAPPING_METADATA_AND_STACK_BUNDLE_V1=1`) |
| 6 | int32 | output layout (`UTF8_LF_STACK_TRACE_V1=1`) |
| 7 | document | retrace resource limits (`0x52380021`) |
| 8 | bytes | retrace capability fingerprint (32-byte SHA-256) |

The fingerprint is SHA-256 over the domain
`AutoJs6:R8RetraceCapabilityFingerprint:v1\0` followed by every semantic capability field in the
order above, excluding the fingerprint itself. Strings are UTF-8 with a big-endian 32-bit length;
numbers are big-endian. A zero placeholder is used while computing the final capability object.

The provider candidate limits are:

| Resource | Provider limit | Contract ceiling |
| --- | ---: | ---: |
| mapping | 16 MiB | 32 MiB |
| retrace metadata | 256 KiB | 256 KiB |
| obfuscated stack | 1 MiB | 1 MiB |
| complete input bundle | 18,088,096 bytes | 34,865,312 bytes |
| retraced stack | 4 MiB | 4 MiB |
| diagnostics | 64 KiB | 64 KiB |
| concurrent compile/retrace sessions | 1 | 1 |
| default timeout | 30 seconds | 120 seconds maximum |

The complete input limit includes the fixed 160-byte bundle table overhead.

## Canonical input bundle

The bundle is a byte-exact, big-endian structure:

```text
8 bytes  magic = ASCII "AJ6R8T01"
4 bytes  version = 1
4 bytes  count = 3
3 records, each:
    4 bytes role code
    4 bytes ordinal = 0
    8 bytes payload size
   32 bytes payload SHA-256
payloads concatenated in record order
EOF
```

The three roles and their only legal order are:

1. `MAPPING_TEXT`
2. `RETRACE_METADATA`
3. `OBFUSCATED_STACK_TRACE`

Mapping and stack payloads must be non-empty strict UTF-8, contain no BOM, NUL, or CR byte, use LF
only, and end in LF. `RETRACE_METADATA` is the existing schema `0x52380008`. The table, declared
bundle size, full-bundle SHA-256, every entry size, every entry SHA-256, exact EOF, text
canonicalization, and metadata decode must all pass before R8 sees any input.

## Mapping provenance binding

The request carries a 32-byte `mappingProvenanceId`:

```text
SHA-256(
    UTF-8 "AutoJs6:R8MappingProvenanceId:v1\0" ||
    mapping payload SHA-256 ||
    RETRACE_METADATA payload SHA-256
)
```

The provider additionally requires all of the following:

- the decoded metadata `mappingSha256` equals the mapping entry SHA-256;
- metadata `formatId` and `formatVersion` equal the retrace capability;
- metadata `compilerVersion` equals the exact embedded retrace compiler version; and
- the request provenance ID recomputes from the canonical table.

This binds the mapping bytes not only to their digest but also to the full compile provenance
(compile capability fingerprint, runtime-library fingerprint, input-set fingerprint, min API, and
profile) already present in `RETRACE_METADATA`. The provider does not trust a filename, mutable
cache key, or caller-supplied label as mapping identity.

## Request document

Schema `0x52380023` contains:

| Tag | Type | Meaning |
| ---: | --- | --- |
| 1 | bytes | 16-byte request UUID |
| 2, 3 | int32 | protocol major and minor; must be `1,1` |
| 4 | int32 | input layout |
| 5 | repeated document | three input identities (`0x52380022`) |
| 6 | int64 | exact bundle byte length |
| 7 | bytes | full bundle SHA-256 |
| 8 | bytes | mapping provenance ID |
| 9 | string | exact expected compiler version |
| 10 | bytes | exact expected retrace capability fingerprint |
| 11 | int32 | output layout |
| 12 | int64 | output byte limit |
| 13 | int32 | diagnostic byte limit |
| 14 | int64 | timeout in milliseconds |

Each input identity has required role, ordinal, size, and SHA-256 fields. All fields that define
admission or identity are required. Unknown required fields, duplicate singular fields, missing
fields, unknown enum codes, reversed/overflowed sizes, and values above either request or provider
limits fail closed. Unknown optional fields may be ignored under the shared tagged-wire 1.x law.

## Callback and result documents

Retrace uses dedicated schemas:

| Schema | Payload |
| --- | --- |
| `0x52380024` | started: request ID, sequence, protocol, compiler version, retrace capability fingerprint, mapping provenance ID, queue elapsed |
| `0x52380025` | progress: request ID, sequence, stage |
| `0x52380026` | result: all identities above plus obfuscated-stack SHA-256, output layout, output size/SHA-256, elapsed time, diagnostics |
| `0x52380027` | error: request ID, error code, failure phase, bounded sanitized message, elapsed time, diagnostics |
| `0x52380028` | cancellation: request ID, reason, current failure phase, elapsed time |

Progress stages are `VALIDATING`, `RETRACING`, and `WRITING`. The output descriptor contains only
the non-empty retraced stack in strict UTF-8/LF form. The host accepts it only after callback
identity validation, exact declared-size read, SHA-256 recomputation, text validation, and EOF.

## Errors and phases

Retrace error codes are:

- `INVALID_REQUEST`
- `UNSUPPORTED_PROTOCOL`
- `UNSUPPORTED_CAPABILITY`
- `INPUT_TOO_LARGE`
- `INVALID_BUNDLE`
- `MAPPING_MISMATCH`
- `METADATA_MISMATCH`
- `INVALID_STACK_TRACE`
- `BUSY`
- `RETRACE_FAILED`
- `OUTPUT_TOO_LARGE`
- `INTERNAL`
- `TIMEOUT`

Failure phases are `NEGOTIATION`, `INPUT_VALIDATION`, `PROVENANCE_VALIDATION`, `RETRACING`,
`OUTPUT_VALIDATION`, `OUTPUT_WRITE`, and `CLEANUP`. Error messages and R8 diagnostics are bounded,
single-line, and path-redacted before serialization. The host exposes a terminal script exception
without changing its semantic route.

## Artifact export boundary

The host may copy the already verified cached `MAPPING_TEXT`, `SEEDS_TEXT`, `USAGE_TEXT`, and
`RETRACE_METADATA` artifacts into a user-selected export directory. Export is outside the Binder
wire contract. Every cache read rechecks the stored size and SHA-256, every staged export copy
rehashes while writing, and the four-file directory is published by a same-parent rename only
after all checks pass. Export never mutates or substitutes the compile cache and never exports DEX
implicitly.

The script retrace entry accepts the stack text plus exported mapping and metadata paths. Paths are
resolved and read only inside the host; only the canonical, identity-bound bytes cross Binder.

## Freeze evidence required

The 1.1 freeze requires, at minimum:

- normalized AIDL hashes and an assertion that the 1.0 methods remain the first three methods;
- golden tagged-wire and bundle hashes;
- codec round trips, unknown-optional acceptance, and unknown-required rejection;
- boundary, truncation, trailing-data, digest, provenance, compiler-version, timeout, diagnostic,
  callback-order, and output-limit negative tests;
- a JVM retrace test using a real R8-produced mapping and obfuscated stack;
- a byte-frozen append-only API AAR release consumed identically by provider and host; and
- host export, selection, no-provider, output-rehash, and script-surface tests.

Device acceptance is a separate evidence boundary. It must use the authorized API 25/28/37 matrix
and a real obfuscated crash sample; this document and JVM evidence do not claim device execution.
