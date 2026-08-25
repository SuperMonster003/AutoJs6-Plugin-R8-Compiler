# G8 privacy-normalized private remote release v1

## Evidence boundary

G8 closes the Roadmap's independent remote APK/API-history item without claiming public
publication. The authorized destination is the personal GitHub repository
`SuperMonster003/AutoJs6-Plugin-R8-Compiler`, which must remain `PRIVATE`. The release tag is
`v0.1.0-provider-dev-private.1`; it must identify a published prerelease rather than a draft or a
public production release. No Maven repository is in this boundary.

The historical G5 and G7 reports remain immutable and correctly retain
`remotePublished=false`. G8 adds a later invocation-bound report with
`remotePublished=true`, `remoteVisibility=PRIVATE`, and `publicPublished=false`; it never rewrites
an earlier claim.

## Commit-identity privacy migration

The repository had never had a Git remote when its two local commits were privacy-normalized. Both
author and committer identities were changed to the GitHub ID-based noreply identity while the
commit messages, author dates, committer dates, topology, and file trees remained identical:

| Local predecessor | Privacy-normalized successor | Identical tree |
| --- | --- | --- |
| `2a1fb3b70cfe6f4678bd0118a87c905a3fe52bbd` | `2ce4d296a69fc78ff373a39630a1b3796bae9fe7` | `892db0f4fb8a8ed618970144e80c32fbcdc381f9` |
| `372db374e95e5536d3a6556131c0b95e9bfdc744` | `884fe5be362f3ec2089514421cd4108b54349bf8` | `d1010affa348ee1587ed31d010b04146f6c0b94f` |

A recovery bundle containing the predecessor history exists only outside the repository. Its path,
contents, and former email identities are deliberately excluded from every tracked file, Gate,
tag, remote ref, and release asset. Before the first push, the in-repository predecessor ref,
reflogs, and unreachable predecessor objects are removed. The first remote history must contain
only the two normalized commits plus the G8 preparation commit, and every reachable author,
committer, and annotated-tag tagger email must be
`30370009+SuperMonster003@users.noreply.github.com`.

## Frozen payload

The remote prerelease republishes the exact append-only `local.5` bytes; it does not rebuild,
re-sign, rename, or mutate that local generation. The five release assets are exactly:

1. `autojs6-r8-compiler-provider-0.1.0-provider-dev-signed.apk`
2. `protocol-wire-api-0.1.0.aar`
3. `r8-compiler-api-0.1.0.aar`
4. `release-manifest.json`
5. `autojs6-r8-compiler-provider-0.1.0-provider-dev-private.1-SHA256SUMS.txt`

The checksum asset is generated outside `local.5` from the other four frozen files. A positive G8
invocation independently downloads all five assets into a newly created system-temporary
directory, rejects any missing or extra name, and verifies every byte length and SHA-256 digest
against the local source records. The temporary directory is removed only after its resolved path
is proven to remain under the system temporary root and to carry the invocation-specific prefix.
A positive Gate is written only after that invocation directory has been successfully removed.

## Fail-closed remote verification

`scripts/verify-g8-private-remote-release.ps1` is read-only with respect to GitHub. It requires the
explicit `-AuthorizePrivateRemoteVerification` switch and the exact tagged commit. Before checking
authorization, GitHub authentication, repository state, Git history, tag, release, or assets, it
atomically invalidates the fixed report at
`build/reports/r42-g8/private-remote-release-gate.json` to `passed=false`. Any missing CLI, malformed
JSON, authentication mismatch, non-private visibility, branch/tag drift, legacy commit reachability,
identity mismatch, draft/non-prerelease state, asset-set drift, download failure, or digest mismatch
leaves the fixed Gate negative.

A positive invocation binds the current identity document, this design, the verifier, both frozen
G7 Gates, the normalized commit/tree mapping, the annotated tag, the exact remote branch history,
and the downloaded release bytes. The report contains no credential, signing-material path,
keystore name, device serial, former email address, local absolute path, or recovery-bundle detail.

## Public-release boundary

Private remote publication is not public publication. Changing repository visibility to `PUBLIC`
is a separate future authorization and requires a new full-history, Actions-log, release-asset,
secret, path, and privacy audit before the visibility change. G8 must continue to report
`publicPublished=false` until such a later Gate actually completes.
