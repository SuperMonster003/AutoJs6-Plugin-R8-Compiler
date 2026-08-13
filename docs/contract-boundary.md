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
