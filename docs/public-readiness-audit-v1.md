# G9 public-readiness baseline audit v1

## Result

This invocation completed the authorized **read-only** pre-publication audit, but it does not
authorize or claim a Private-to-Public visibility change.

```text
invocationId: 30243e1f-0564-4ac3-8025-13e23e529c4f
completedAtUtc: 2026-08-27T06:17:26.3774144Z
auditCompleted: true
readyForVisibilityChange: false
remoteMutationPerformed: false
publicPublished: false
```

The content, identity, object-database, release-byte, Actions, path, and privacy scans found no
actionable credential or personal-path disclosure. Publication remains blocked by source/remote
divergence and release-metadata drift. Because G10 will intentionally add more commits after this
baseline, the eventual public candidate must be pushed and this complete audit must be rerun over
the final remote state before visibility changes.

## Authorization and operation boundary

The user authorized a G9 **read-only public-preparation audit**, followed by G10. This invocation:

- queried GitHub repository, branch, tag, release, security-feature, Actions, issue/PR, deployment,
  environment, secret/variable-count, cache, artifact, and deploy-key metadata;
- downloaded the existing five release assets into invocation-specific system-temporary
  directories, hashed and inspected them, and deleted those directories after exact path-boundary
  checks;
- downloaded the official Gitleaks v8.30.1 Windows archive into an invocation-specific temporary
  directory, verified its published checksum, ran it with 100% secret redaction, and deleted it;
- inspected the complete local Git object database, reachable history, refs, reflogs, tracked paths,
  commit/tag identities, current worktree, frozen local release, APK signature, and archive contents;
- did **not** push, fetch, edit GitHub metadata, change visibility, edit a release, upload/delete an
  asset, run a workflow, access a device, read signing material, sign an APK, or publish Maven data.

## Audited snapshots

| Boundary | Object | Value |
| --- | --- | --- |
| Local source candidate | commit | `1559a6ca1819093f4e99bba0c6e9298f1bd8d589` |
| Local source candidate | tree | `2b34fc3de918e8a81264bb8c72b98a20352a4b86` |
| GitHub default branch | commit | `277ce8a05faa9566abcf474fcb0d3e6f928737ff` |
| GitHub default branch | tree | `5eddb99350057f20f8db9dcd7eb779a2e9c0c901` |
| Annotated tag object | tag | `fdce4dc42e6b1f65dae8677099d0f4b77fafec4a` |
| Annotated tag target | commit | `29abdf6a2742e3f327b17eb6ca1f50684bd5f72b` |
| Frozen G8 Gate | report SHA-256 | `ead4d551ae7eb13e319bc5ffed3639edc1ab96c6a85b9088ed7ca070f0a3e000` |
| Frozen G8 Gate | invocation | `ab010b7d-800f-43d9-acc9-27efb087efa2` |

At the audit boundary, local `master` was two commits ahead and zero commits behind GitHub
`master`. The worktree was clean. GitHub exposed one branch (`master`), one annotated tag, and one
release.

## Publication blockers

### B1: the audited local source is not the GitHub default-branch source

The local candidate contains two reviewed but unpushed commits:

1. `3ba7304d2e565775d8cb072e388cabfa2c9a10d7` — multilingual user documentation;
2. `1559a6ca1819093f4e99bba0c6e9298f1bd8d589` — completed G11 documentation and app experience.

Changing visibility now would expose only remote commit
`277ce8a05faa9566abcf474fcb0d3e6f928737ff`, not the audited local candidate. No push was performed
because it is outside this read-only audit.

### B2: release metadata no longer matches the frozen private-prerelease claim

GitHub release `v0.1.0-provider-dev-private.1` is still non-draft and its five asset bytes are
unchanged, but the live API returned `prerelease=false`. Its metadata was updated at
`2026-08-27T05:06:02Z`. G8, `docs/identity-reservation.json`, and the generated README sources still
describe it as a **Private prerelease**.

This is metadata drift, not asset-byte drift. Before a public Gate, the intended public release/tag
strategy and all identity/README claims must be made consistent and then independently verified.
The audit did not mutate the release to resolve this.

### B3: this is a baseline, not the final visibility-change snapshot

This report is added after the audited local commit, and the authorized next phase (G10) will add
protocol, provider, host, test, and documentation changes. Therefore this invocation cannot be
reused as authorization for a later visibility change. A final G9 audit must cover the exact remote
branch head, all refs and assets after those changes are pushed.

## Complete local Git object-database audit

`git cat-file --batch-all-objects`, `git fsck --full --no-reflogs --unreachable`, ref enumeration,
and reflog checks produced the following inventory:

| Object type | Count | Uncompressed bytes |
| --- | ---: | ---: |
| blob | 218 | 3,168,171 |
| commit | 6 | 1,948 |
| tag | 1 | 248 |
| tree | 145 | 22,352 |
| **total** | **370** | **3,192,719** |

- unreachable objects: `0`;
- loose/pack garbage objects: `0`;
- reachable commits: `6`;
- current and historical unique tracked paths: `181`;
- symlinks, submodules, replace refs, notes refs, and stash refs: `0` each;
- legacy identity-bearing predecessor objects
  `2a1fb3b70cfe6f4678bd0118a87c905a3fe52bbd` and
  `372db374e95e5536d3a6556131c0b95e9bfdc744`: absent from the object database and reflogs;
- author/committer identity mismatches: `0`; all six reachable commits use
  `SuperMonster003 <30370009+SuperMonster003@users.noreply.github.com>`;
- the annotated tagger uses the same authorized noreply identity.

The only local refs were the `master` branch, its `origin/master` remote-tracking ref, and the single
annotated release tag. The origin URL identifies exactly
`SuperMonster003/AutoJs6-Plugin-R8-Compiler` and carries no embedded credential.

## Tracked-path, secret, and privacy scans

### Path and deterministic pattern scan

All reachable revisions were scanned, not just the current checkout.

- secret/key path candidates (`.env`, credentials/secrets files, `id_rsa`, `id_ed25519`, signing
  properties, local properties, JKS/keystore/PKCS/private-key extensions): `0`;
- strong secret candidates (private-key headers, GitHub tokens, AWS access IDs, Google API keys,
  Slack tokens, OpenAI keys, Stripe live keys, credential-bearing URLs, and literal generic
  credential assignments): `0`;
- unknown email identities: `0` after classifying the authorized GitHub noreply address, the
  `git@github.com` SSH transport URL, and Kotlin annotation text that is syntactically email-like;
- absolute-path candidates: `2`, both reviewed false positives:
  - a deliberately hostile `/data`, `C:\\private`, and `file:/storage` test fixture in
    `R8DiagnosticCollectorTest.kt`, whose assertion proves redaction to `<path>`;
  - the `/home/`, `/Users/`, and `/tmp/` literals in the G1 verifier's path-sanitization regex.

No actual user-home path, device serial, recovery-bundle location, former email address, credential,
or signing-material path was found.

### Independent Gitleaks scan

The audit used official Gitleaks `8.30.1`, published 2026-03-21. The downloaded Windows x64 archive
matched SHA-256 `d29144deff3a68aa93ced33dddf84b7fdc26070add4aa0f4513094c8332afc4e`.
The complete Git-history scan used `--redact=100`.

Gitleaks returned two `generic-api-key` candidates. Both were reviewed as non-secret command-line
placeholders:

- `docs/art-jni-retrace-acceptance-v1.md`: `<api37-x86_64-serial>`;
- `docs/device-acceptance-v1.md`: `<api28-avd-serial>`.

Actionable Gitleaks findings after review: `0`. No matching value was retained in this report.

## GitHub repository and public metadata audit

At the invocation boundary:

```text
repositoryId: 1345668157
visibility: PRIVATE
defaultBranch: master
archived: false
disabled: false
branches: 1
tags: 1
pullRequests: 0
issuesIncludingPullRequests: 0
deployments: 0
deployKeys: 0
GitHub Pages: not configured
license detected by GitHub: MPL-2.0
```

Repository description, homepage, release name/body, and other public-facing text fields produced
zero strong secret, credential URL, user-home path, or personal-email candidates. The repository
has no homepage or topics. `LICENSE`, `README.md`, `CHANGELOG.md`, `ROADMAP.md`, and `.gitignore` are
tracked. `SECURITY.md`, `CONTRIBUTING.md`, and `CODE_OF_CONDUCT.md` are not present; these are public
project quality recommendations rather than credential/privacy blockers.

The remote default branch contains exactly four normalized-history commits, all with the authorized
noreply author/committer identity. Neither legacy predecessor commit is remotely reachable through
the branch or tag.

## GitHub Actions audit

GitHub returned:

| Surface | Count |
| --- | ---: |
| workflows | 0 |
| workflow runs | 0 |
| workflow logs | 0 (no runs exist) |
| workflow artifacts | 0 |
| caches | 0 |
| repository Actions secrets | 0 |
| repository Actions variables | 0 |
| environments | 0 |

There is therefore no Actions history, log, artifact, cache, workflow path, reusable-workflow name,
secret name, variable, or environment metadata to expose in a visibility conversion. GitHub's
visibility documentation explicitly warns that Actions history and logs become visible to everyone
when a private repository becomes public; this zero-state must be queried again immediately before
conversion: <https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/managing-repository-settings/setting-repository-visibility>.

GitHub Secret Scanning is not enabled/available for this user-owned Private repository; the alerts
API returned HTTP 404. This audit compensated with full-history deterministic and Gitleaks scans.
GitHub documents that secret scanning runs automatically for public repositories, but the eventual
Gate must independently verify its post-conversion state and any generated alerts:
<https://docs.github.com/en/code-security/how-tos/secure-your-secrets/detect-secret-leaks/enable-secret-scanning>.

## Release and asset-byte audit

All five GitHub assets were independently downloaded into a fresh temporary directory. The name
set, byte lengths, and hashes exactly match the frozen positive G8 report and local source records:

| Asset | Bytes | SHA-256 |
| --- | ---: | --- |
| `autojs6-r8-compiler-provider-0.1.0-provider-dev-signed.apk` | 33,155,599 | `84447972cb0e4e020e5a696d0d2dabeb273e2990067a62f2d1b7590add628265` |
| `protocol-wire-api-0.1.0.aar` | 29,387 | `1d97a5b44b2c20e85aa12b263fca604a32d6d89275d47a19076861cd20c29a36` |
| `r8-compiler-api-0.1.0.aar` | 179,855 | `e9df49b7e49992615a15bc0af2372a4525f02b4a2a915a560ddab3128bb2f066` |
| `release-manifest.json` | 990 | `d97ed12a150a991b30e2697258ae0e381afa80667aed5e8272a5a5ebd4ec1583` |
| `autojs6-r8-compiler-provider-0.1.0-provider-dev-private.1-SHA256SUMS.txt` | 399 | `061e760ca9283f48be1a0d974c502fb087059cb14163bb715ecfdfe9b676e3cb` |

Archive inspection covered 149 top-level and nested entries (46,930,983 expanded bytes excluding
recursive expansion of the pinned platform library). It found zero traversal/absolute entry names,
secret/key filenames, user-home paths, or strong secret patterns. The APK's embedded Android 36
platform JAR is exactly 27,768,026 bytes with SHA-256
`d9eb9da824d9e247a352f570f01e1169e725b2954bca9e283a71786c59b59f9a`.

One email-shaped byte sequence in `classes3.dex` was reviewed as an R8 keep-annotation protobuf
descriptor fragment, not an email. The APK v2/v3 signature verifies; its certificate SHA-256 is
`31a681fcfffb3e428420cae280ded89292b12a3b0f59e19b7a73e32a8ae4c213`, and its subject DN contains
no email address. Signing material itself was never opened.

All invocation-specific download/extraction directories were removed after verified system-temp
path checks.

## Required next audit

This report closes the requested **baseline read-only review**, not the G9 publication Gate. Before
any visibility mutation, a new invocation must:

1. identify one exact final candidate commit after G10 and all public documentation changes;
2. push only with separate authorization, then prove local HEAD equals GitHub `master`;
3. rerun full Git object/ref/path/identity, Gitleaks, public metadata, Actions, and asset scans;
4. resolve or intentionally supersede the `prerelease=false` metadata drift and synchronize
   `docs/identity-reservation.json`, README sources, release/tag naming, and public claims;
5. require zero actionable findings and record the final remote commit/tree plus every asset hash;
6. only then request separate authorization for the Private-to-Public API mutation;
7. after conversion, independently verify visibility, anonymous source/asset downloads, release
   bytes, Actions exposure, and GitHub Secret Scanning results.

Until those conditions are met, `publicPublished=false` remains the only valid claim.
