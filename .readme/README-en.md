<!--suppress HtmlDeprecatedAttribute, HttpUrlsUsage -->

<div align="center">
  <h1>AutoJs6-Plugin-R8-Compiler</h1>

  <p>Standalone R8 compiler plugin for AutoJs6. Compiles script JARs into DEX with the full release profile (shrink + optimize + obfuscate) in an isolated process</p>

  <p><sub>Current stage: private prerelease (source and installers are not public yet)</sub></p>
</div>

******

### Languages

******

The README.md is currently available in the following languages:

- [简体中文 [zh-Hans]](https://github.com/SuperMonster003/AutoJs6-Plugin-R8-Compiler/blob/master/.readme/README-zh-Hans.md)
- [繁體中文 (香港) [zh-Hant-HK]](https://github.com/SuperMonster003/AutoJs6-Plugin-R8-Compiler/blob/master/.readme/README-zh-Hant-HK.md)
- [繁體中文 (台灣) [zh-Hant-TW]](https://github.com/SuperMonster003/AutoJs6-Plugin-R8-Compiler/blob/master/.readme/README-zh-Hant-TW.md)
- English [en] # current
- [Français [fr]](https://github.com/SuperMonster003/AutoJs6-Plugin-R8-Compiler/blob/master/.readme/README-fr.md)
- [Español [es]](https://github.com/SuperMonster003/AutoJs6-Plugin-R8-Compiler/blob/master/.readme/README-es.md)
- [日本語 [ja]](https://github.com/SuperMonster003/AutoJs6-Plugin-R8-Compiler/blob/master/.readme/README-ja.md)
- [한국어 [ko]](https://github.com/SuperMonster003/AutoJs6-Plugin-R8-Compiler/blob/master/.readme/README-ko.md)
- [Русский [ru]](https://github.com/SuperMonster003/AutoJs6-Plugin-R8-Compiler/blob/master/.readme/README-ru.md)
- [العربية [ar]](https://github.com/SuperMonster003/AutoJs6-Plugin-R8-Compiler/blob/master/.readme/README-ar.md)

******

### Introduction

******

AutoJs6 scripts can load a JAR with `runtime.loadJar()` and call the Java classes inside. That default path uses D8 for a plain JAR-to-DEX compilation with no shrinking or obfuscation. When you want the output to go through a full release treatment -- dead-code removal (shrinking), bytecode optimization, and identifier obfuscation -- you need R8.

This plugin is a separately installed app that runs a pinned version of the Google R8 compiler in its own isolated process, providing AutoJs6 with an explicit full-release compilation service. Scripts start a compilation through the dedicated `runtime.loadJarWithR8()` entry and must supply keep rules; AutoJs6 takes back five artifacts (DEX, mapping, and more), verifies each one, caches them, and loads only the verified DEX.

The biggest difference from the built-in path: the R8 entry is explicit and has no fallback. A failed compilation never silently switches to D8/dx; the error is thrown back to the script as-is. This guarantees one simple invariant: if the load succeeds, the artifact went through full R8 processing.

Good reasons to install this plugin: you need to shrink or obfuscate a JAR artifact; you need mapping files to de-obfuscate stack traces later; or you want fully deterministic compile semantics (either full R8, or a clear failure).

******

### How it works

******

With the plugin enabled, one `runtime.loadJarWithR8()` call roughly goes through the following steps:

```text
1. script     calls runtime.loadJarWithR8(program, keepRules[, classpath[, consumerRules, ordinals]])
2. AutoJs6    snapshots program, classpath and rule files into one canonical path-free input bundle
3. plugin     re-verifies the bundle, then runs pinned R8 (full release profile) in its private ":r8" process
4. plugin     streams back one bundle with five artifacts: DEX ZIP, mapping, seeds, usage, retrace metadata
5. AutoJs6    re-validates every artifact, commits an R8-only cache generation, and loads the verified DEX ZIP
*  no fallback: any failure terminates the call as an R8 error; D8/dx is never used silently
```

The plugin is responsible only for steps 3 and 4 -- the compilation itself; input snapshotting and freezing, artifact validation, caching, and the final class loading are always done by AutoJs6. The two sides exchange only file descriptors over Binder, no file path ever crosses the wire, and the plugin cannot read your script directory. Results are cached by input content and compile parameters in a dedicated R8 cache domain; repeated loads of the same input hit the cache directly, corrupt cache data is evicted and recompiled automatically, and every artifact opened from the cache is re-hashed first.

******

### Features

******

- Full release compilation: shrinking (dead-code removal), optimization, and obfuscation are always all enabled, performed by pinned R8 8.13.17.
- Explicit semantics with no silent fallback: only `runtime.loadJarWithR8()` uses this plugin; every failure ends as an R8 error and never silently switches to D8/dx. `runtime.loadJar()` and `runtime.loadJarWithClasspath()` are completely unchanged.
- Five artifacts in one round-trip: DEX ZIP, mapping (obfuscation map), seeds (kept items), usage (removed items), and retrace metadata, each bound to a SHA-256 and independently re-verified by the host.
- Compilation runs in the plugin's own `:r8` process and private workspace, isolated from AutoJs6; the input is fully re-validated before R8 runs.
- Supports ordered compile-time classpath JARs and consumer rules bound to their owning classpath JAR; keep rules must be provided explicitly, and rules are never discovered implicitly inside archives.
- Ships a byte-verified Android 36 platform library as the compiler library instead of relying on possibly stripped device boot-classpath JARs; compiler `minApi` 24 through 36 is fully covered by a real R8 corpus.
- Supports Android 7.0 (API 24) and above; verified on API 25/28/37 physical devices and emulators (including a 16 KiB page-size device) with real ART execution, JNI calls, and Retrace restoration.
- Talks only to a same-signature AutoJs6 (protected by the `org.autojs.permission.PLUGIN` permission) and requests no network or storage permission.

******

### Relationship with the DEX Compiler plugin

******

The AutoJs6 ecosystem has two independent compiler plugins. They complement each other, do not overlap, and can be installed side by side:

- [AutoJs6-Plugin-DEX-Compiler](https://github.com/SuperMonster003/AutoJs6-Plugin-DEX-Compiler) serves the default path `runtime.loadJar()` / `runtime.loadJarWithClasspath()`: plain compilation with a newer D8, no shrinking or obfuscation; when the plugin fails, the host automatically falls back to its built-in compiler.
- This plugin (R8) serves only the explicit path `runtime.loadJarWithR8()`: full release compilation with mandatory keep rules; a failure is a failure, with no fallback.

The two use fully independent protocols (`dex-compiler-api` vs `r8-compiler-api`), service actions, developer-options entries, and cache domains; neither depends on nor is aware of the other. The `RELEASE` mode in the DEX protocol is just D8's release compilation mode and has nothing to do with R8. Installing or uninstalling either one never affects the other.

******

### Installation and usage

******

Enabling the plugin takes three steps: install a paired AutoJs6 build that contains the R8 integration, install this plugin APK, then manually select the plugin in AutoJs6 developer options. Two things to know up front:

- The plugin is inert by default. Merely installing it changes nothing in AutoJs6; while it is not enabled, `runtime.loadJarWithR8()` simply fails closed instead of using another compiler.
- It is always reversible. Disable the entry in developer options to restore the previous state; nothing needs to be uninstalled.

#### Prerequisites

- An AutoJs6 build that contains the `runtime.loadJarWithR8()` integration (representative verified build: AutoJs6 6.8.0 (build 5276)); older hosts have neither the entry nor the corresponding developer-options item.
- Host and plugin must come from the same trusted source and carry the same signature; with mismatched signatures the plugin cannot be selected -- use install packages released (or built) as a pair.
- The plugin is currently in a private prerelease stage; installers come from the private GitHub prerelease or a local build. Obtain them together with the paired host.
- When building yourself, keep the fixed package and service component below unchanged.

The relevant identifiers are:

```text
host package: org.autojs.autojs6
plugin package: io.github.supermonster003.autojs6.plugin.r8compiler
paired host: AutoJs6 6.8.0 (build 5276)
exact component: io.github.supermonster003.autojs6.plugin.r8compiler/io.github.supermonster003.autojs6.plugin.r8compiler.R8CompilerService
```

#### Install and enable

1. Install or upgrade to a paired AutoJs6 build containing the R8 integration.
2. Install this plugin APK.
3. Open AutoJs6, go to Settings > About app and developer, and long-press the app icon to enter developer options.
4. Open R8 compiler > Explicit full-release R8 provider.
5. Select this plugin's service component (the exact component above) and confirm.

To repeat: installing alone never enables the plugin, and AutoJs6 never auto-selects any provider it discovers; until the selection is made, `runtime.loadJarWithR8()` always ends in failure.

#### Verify it is active

Back on the developer options page, the setup succeeded when the Explicit full-release R8 provider summary says `runtime.loadJarWithR8` uses this plugin's component; a summary containing "disabled" or "fails closed" means the entry is still off.

If the plugin does not appear in the list, check in order: the host is a paired build containing the R8 integration; host and plugin package names match the identifiers above; the plugin app is not disabled by the system; both signatures match.

Unlike the DEX plugin, this entry has no "who actually compiled it" ambiguity: whenever `runtime.loadJarWithR8()` returns successfully, the artifact necessarily went through full R8 processing (either in this call or in a previously verified cache generation).

#### Script example

Put a JAR containing JVM `.class` files and a keep-rules file into your script directory, then call any overload of the entry family. Keep rules are not optional: R8 removes and obfuscates every symbol not kept by a rule, so a compilation without rules almost certainly produces classes that can no longer be accessed by their original names.

```javascript
"use strict";

const program = files.path("./lib/example.jar");
const keepRules = files.path("./lib/keep-rules.pro");

// keep-rules.pro (UTF-8), e.g.:
//   -keep class com.example.autojs6.R8PluginExample { public *; }

runtime.loadJarWithR8(program, [keepRules]);

// Replace this with a public class that actually exists in example.jar
// and is kept by your keep rules.
const Example = Packages.com.example.autojs6.R8PluginExample;
console.log("R8 compiler example: " + Example.answer());
```

When the program JAR references compile-time classes that are not inside it (API stubs, for example), use the three-argument overload with an ordered classpath:

```javascript
runtime.loadJarWithR8(
    files.path("./lib/program.jar"),
    [files.path("./lib/keep-rules.pro")],
    [files.path("./lib/compile-api-stubs.jar")],
);
```

The five-argument overload additionally accepts consumer-rule files and their owner ordinals; each consumer-rule file is bound by ordinal to the corresponding classpath JAR:

```javascript
runtime.loadJarWithR8(program, keepRuleFiles, classpathJars, consumerRuleFiles, ownerOrdinals);
```

Key points:

- Classpath JARs are used only to resolve references at compile time; they are neither packaged into the output nor loaded automatically. Their order matters and is part of the cache identity.
- Keep and consumer rules must be strict UTF-8 text; rules containing filesystem access, include, input/output redirection, dictionary, and similar dangerous directives are rejected outright (fail-closed).
- Mapping and the other artifacts are currently verified and stored in the host's private cache; there is no script-facing export entry yet (see ROADMAP).
- Compilation is not a security review; only load JARs you trust.

#### Keep-rule guide

For minimal recipes covering `Packages` access, reflection, JNI, serialization, and a public API surface, see the [practical keep-rule guide](https://github.com/SuperMonster003/AutoJs6-Plugin-R8-Compiler/blob/master/docs/keep-rules-guide.md).

#### What happens on failure

The failure semantics of this entry are deliberately simple: you either get full R8 artifacts or an error -- nothing in between.

- No provider selected, invalid arguments, provider unavailable or busy (BUSY; only one compile session at a time), compilation failure, timeout (120 s by default, 300 s at most), artifact verification failure, cache or load failure -- all of them end the script call as an R8 error, never by switching to D8/dx.
- Your own cancellation (stopping the script, for example) terminates the call immediately and likewise triggers no fallback.
- A cache hit does not change the semantics: cached artifacts were fully verified when written and are re-hashed again on every read.

If you do want "fall back to a plain compile on failure", catch the error in your script and call `runtime.loadJar()` explicitly; the host will not make that decision for you.

#### Troubleshooting and feedback

The most common causes, in order: no provider selected in developer options; rules rejected (forbidden directives or non-UTF-8 encoding); input exceeding a resource limit; a class name misspelled or not covered by the keep rules (R8 has obfuscated or removed unkept symbols). When reporting an issue, please attach as much of the following as possible:

- AutoJs6 version and build, plugin version, and the full component name from the developer-options summary.
- Device model, Android version (API), and CPU architecture (ABI).
- The triggering program JAR and all rule files (or their byte sizes and SHA-256 digests), the complete script exception, and reproduction steps.

If you can use ADB, the following commands collect the relevant logs (replace `<serial>` with your device serial; strip private paths and sensitive content before sharing):

```powershell
adb -s <serial> shell dumpsys package org.autojs.autojs6
adb -s <serial> shell dumpsys package io.github.supermonster003.autojs6.plugin.r8compiler
adb -s <serial> logcat -d -v threadtime AndroidClassLoader:D AndroidRuntime:E *:S
```

#### Disable, roll back, and uninstall

- Temporarily disable: turn the entry off under Explicit full-release R8 provider in developer options and confirm. `runtime.loadJarWithR8()` goes back to failing closed, nothing else changes, and the retained component selection can be re-enabled at any time.
- Uninstall the plugin: disable the entry first, then stop AutoJs6 and uninstall the plugin APK. Uninstalling removes all of the plugin's own data and temporary files.
- After a reinstall or update, the host re-validates the component identity (including UID and signature), so the selection must be confirmed again in developer options.

******

### FAQ

******

**Q: Why are keep rules mandatory?**

A: R8's full-release profile removes and obfuscates every symbol that is not explicitly kept. Scripts access classes reflectively through `Packages.xxx`, so R8 cannot infer which symbols must survive; the protocol therefore makes keep rules explicitly required, avoiding the silent trap of "compiled fine, class not found".

**Q: Is it faster than the built-in compiler or the DEX plugin?**

A: No -- usually slower. R8 performs whole-program analysis (shrink/optimize/obfuscate), which is inherently more expensive than a plain D8 compile; what you get in return is a smaller, harder-to-reverse artifact plus a mapping file. Results are cached, so subsequent loads of the same input are fast.

**Q: Can it replace the DEX Compiler plugin (or vice versa)?**

A: No. They serve different script entries with independent protocols and caches; see "Relationship with the DEX Compiler plugin".

**Q: Why is there no automatic fallback to D8 on failure?**

A: By design. Calling the R8 entry declares "I need a full release artifact"; a silent fallback would hand you an un-obfuscated artifact without you knowing. If you want fallback semantics, catch the error in your script and call `runtime.loadJar()` yourself.

**Q: How do I de-obfuscate a stack trace?**

A: Every compilation produces mapping and retrace metadata, which the host verifies and caches; the current version has no script- or UI-facing entry to fetch the mapping yet, and a retrace RPC is on the roadmap. When you build the artifact yourself, use the R8 retrace tool with the mapping you kept to restore stacks.

**Q: Does the plugin access the network or read my files?**

A: No. It has no network or storage permission, reads compile input only from file descriptors handed over by AutoJs6, never sees file paths on the wire, and keeps temporary files strictly inside its own private directory.

**Q: What does the launcher UI do?**

A: The plugin's read-only screen shows the plugin version, pinned R8 version, service-component availability, and the bundled changelog. It does not enable the provider; selection and activation remain exclusively in AutoJs6 developer options.

******

### Scope boundaries

******

To avoid misunderstandings, the following are explicitly outside this plugin's scope:

- Serves only `runtime.loadJarWithR8()`; it never changes `runtime.loadJar()` or `runtime.loadJarWithClasspath()`, nor can those entries select it implicitly.
- No D8 mode, no debug compilation, and no individual switches for shrink/optimize/obfuscate: the profile is fixed to FULL_RELEASE.
- No implicit rule discovery inside JARs (such as proguard files under META-INF); keep and consumer rules must be provided explicitly with the request.
- Rules containing filesystem access, include, input/output redirection, mapping import, print, dictionary, or global profile-control directives are rejected (outside protocol 1.0, fail-closed).
- No retrace RPC; retrace metadata is mapping provenance only, and mapping has no script-facing export yet (both on the ROADMAP).
- No dependency downloading or resolution (no Maven/Gradle integration), no network compilation.
- No handling of `.aar`, precompiled `.dex`, or `defineClass()` dynamic bytecode; those always take AutoJs6's built-in paths.
- Private distribution only for now: source and installers live in a private GitHub repository; public release is a separate roadmap item.

******

### Technical reference

******

The following targets developers and integrators who need exact boundaries; regular plugin users can usually skip it.

#### Input and output

Protocol 1.0 receives one canonical input bundle through a read-only descriptor and returns one canonical artifact bundle through a write-only descriptor; no file path ever crosses the wire, and the whole input plus every artifact is SHA-256 bound:

```text
input: 1 program JAR + ordered classpath JARs + explicit keep rules + optional consumer rules
output: DEX_ZIP + MAPPING_TEXT + SEEDS_TEXT + USAGE_TEXT + RETRACE_METADATA
compiler: R8 8.13.17
profile: FULL_RELEASE (shrink + optimize + obfuscate)
```

#### Plugin discovery identifiers

The host discovers and calls the plugin through the following identifiers:

```text
service action: org.autojs.plugin.R8_COMPILER
plugin id: r8-compiler
protocol provider id: autojs6-r8
engine: r8-compiler
variant: r8
protocol: 1.0
api namespace: org.autojs.plugin.r8compiler.api
distribution: org.autojs.plugin.r8compiler:r8-compiler-api:0.1.0
cache domain: autojs6:r8-compiler:v1
```

The plugin declares R8 8.13.17, protocol 1.0, the fixed FULL_RELEASE profile, `minApi` 24 through 36, multi-dex output, and the five-artifact capability set. The compiler library is a bundled, byte-verified Android 36 platform library; the runtime-library fingerprint still binds the observed device boot-classpath files.

The plugin contains no native library and covers all device ABIs with one pure-JVM universal APK; the verified JNI calls target native methods inside compiled JARs, not the plugin itself.

#### Security model

The plugin requests no network or storage permission. The compile service is protected by the `org.autojs.permission.PLUGIN` permission and runs in the dedicated `:r8` process; every call verifies package name, caller UID, and both signatures in both directions, accepting only the same-signature AutoJs6 host. Input and output travel exclusively as file descriptors; descriptors with wrong access modes or with input/output aliasing the same endpoint are rejected. Temporary files stay inside the plugin's private workspace, and stale workspaces are recovered automatically. The host independently re-verifies every artifact as well, and the class loader accepts only a re-hashed, read-only DEX copy.

#### Resource limits

To defend against malicious or abnormal input, the protocol sets hard limits on every stage; over-limit requests are rejected outright:

- Program JAR: up to 128 MiB; classpath: up to 32 JARs, 64 MiB each, 128 MiB total.
- Rule files: up to 16 keep and 32 consumer files; 256 KiB per file, 2 MiB of rules total, 16 KiB per line.
- Whole input bundle up to 260 MiB; archive expansion: up to 20000 entries per JAR and 60000 in total, 512 MiB uncompressed; class data up to 8 MiB per class and 256 MiB in total.
- Output bundle up to 256 MiB: DEX ZIP up to 192 MiB, mapping up to 32 MiB, seeds and usage up to 16 MiB each, retrace metadata up to 256 KiB.
- Concurrency: exactly one compile session at a time; other requests receive a retryable BUSY. Timeout defaults to 120 s with a 300 s ceiling.
- Diagnostics are capped at 64 KiB and path-redacted.

#### Caveats

- `minApi` is a compiler parameter, not a device execution claim; an artifact cannot be loaded on devices below its `minApi`.
- The R8 version is pinned (currently R8 8.13.17); the cache identity includes compiler and runtime fingerprints, so a compiler upgrade never reuses stale results.
- Cancellation immediately prevents result publication, but R8's internal CPU work may continue inside the isolated process until the compilation returns; the session slot stays BUSY until cleanup completes.
- The same input reproduces byte-identically under the same compiler version (release gates verified same-environment reproducibility), but byte-level identity across R8 versions is not promised.
- The AGP-bundled R8 version shown in the host build banner belongs to the APK packaging toolchain and is unrelated to this plugin's compiler version.

******

### Roadmap

******

Development proceeds through verifiable gates: G1 contract freeze, G2 provider implementation, G3 host integration, G4 compatibility corpus, G5 local signed release, G6 device acceptance, G7 ART/JNI/Retrace closure, and G8 private remote release are all complete, each with SHA-256-bound reviewable evidence. Upcoming plans (public release, retrace availability, docs and UX, engine upgrade maintenance) and the definition of done for each item live in:

- [Open the checkable ROADMAP.md](https://github.com/SuperMonster003/AutoJs6-Plugin-R8-Compiler/blob/master/ROADMAP.md)

******

### Release history

******

# v0.1.0-provider-dev-private.1 (local.5)

###### 2026/08/25

* `Hint` Current release. Published as a private GitHub prerelease (5 assets: signed APK, two frozen contract AARs, release manifest, and SHA256SUMS, all re-downloaded and byte-verified); not publicly released yet
* `Hint` Inert after installation by default; it must be enabled manually in AutoJs6 developer options -- see the "Installation and usage" section of the README
* `Feature` Bundle a byte-verified Android 36 platform library as the R8 compiler library instead of relying on the device boot classpath; fixes compile failures on devices whose boot JARs are resource-only shells
* `Feature` Add bounded, path-redacted R8 diagnostics collection covering provider startup and engine import failures
* `Improvement` Verify optimized output on ART at API 25/28/37 (including a 16 KiB page-size emulator): reflection, runtime-composed class names, serialization, the script-facing entry, removed-decoy checks, arm64/x86/x86_64 JNI calls, and R8 Retrace stack restoration after mapping-hash verification

# v0.1.0-provider-dev (local.4)

###### 2026/08/25

* `Fix` Replace `/proc/self/fdinfo` access-mode inspection with zero-byte public `Os.read`/`Os.write` kernel probes, resolving procfs restrictions on some devices (such as Sony API 28) while retaining `Os.fstat` alias rejection
* `Improvement` Complete cross-APK Binder/PFD device acceptance on 1 physical device and 2 emulators (API 25/28): happy path, lifecycle, hostile input, and process death -- 9/9 tests passing

# v0.1.0-provider-dev (local.3)

###### 2026/08/25

* `Fix` Pin core-library/NIO desugaring (desugar_jdk_libs_nio 2.1.5) for API 24 through 28, fixing runtime failures caused by missing Java 11 core-library capabilities on older devices

##### More releases

* [CHANGELOG-en.md](https://github.com/SuperMonster003/AutoJs6-Plugin-R8-Compiler/blob/master/app/src/main/assets/doc/CHANGELOG-en.md)

******

### Build

******

```powershell
.\gradlew.bat :app:assembleDebug
```

Release build:

```powershell
.\gradlew.bat :app:assembleRelease
```

Building requires JDK 17 or later (21 recommended) and Android SDK Platform 36: the build script byte-verifies `platforms/android-36/android.jar` and bundles it as the compiler-library asset, aborting on any mismatch. Current minSdk is 24 and targetSdk is 36.

The protocol ABI comes from the frozen 0.1.0 contract AARs inside the repository (under `plugin-api/r8-compiler-api/releases/0.1.0/`); the app consumes those AAR bytes rather than their source projects:

```text
protocol-wire-api-0.1.0.aar
r8-compiler-api-0.1.0.aar
```

The compiler is pulled from Maven as pinned R8 8.13.17. Official releases use the publishing and verification scripts under `scripts/` (append-only local release directory, two-snapshot reproducible builds, and per-gate verification); for day-to-day debugging the Gradle commands above are all you need.

******

### License

******

The project source is licensed under MPL-2.0. R8 and other third-party components remain under their own licenses.

******

### Resource layout

******

```text
.readme/lang_*.json
.changelog/lang_*.json
.python/generate_markdown.py
app/src/main/assets/doc/CHANGELOG-*.md
```

`.python/generate_markdown.py` generates the README and CHANGELOG in all 10 languages (including the repository-root README.md and CHANGELOG.md) from JSON sources; to change the documentation, edit the JSON sources rather than the generated Markdown.

To verify that every generated Markdown file matches its source without modifying the working tree, run:

```powershell
python .python/generate_markdown.py --check
```

******

### Links

******

- AutoJs6 documentation: https://docs.autojs6.com
- R8 project: https://r8.googlesource.com/r8
- DEX Compiler plugin (D8): https://github.com/SuperMonster003/AutoJs6-Plugin-DEX-Compiler
- Private release page (access required): https://github.com/SuperMonster003/AutoJs6-Plugin-R8-Compiler/releases/tag/v0.1.0-provider-dev-private.1
