# AutoJs6-Plugin-R8-Compiler engineering rules

These repository-specific rules apply the AutoJs6 new plugin repository reference dated 2026-09-13. Preserve deeper AGENTS.md constraints when working on vendored code.

- Start with git status, branch, recent commits and relevant diffs. Existing uncommitted work belongs to the user; never reset or silently include it in your commits.
- Inspect, validate and commit each complete change with Conventional Commits unless the user requests otherwise. Before each commit set VERSION_BUILD to the reachable HEAD commit count plus one. Verify equality after committing. Do not auto-increment it during assembly.
- VERSION_NAME follows semantic versioning. Update every current-version changelog JSON and generated document when changing behavior. Never claim an unexecuted device test or an unpublished candidate is released.
- Resolve the platform and native alignment plugins 1.8.3 from public repositories in root settings. The platform plugin must run before build-logic. Do not use Maven local, consumer gradle/data overrides or sibling build substitutions.
- Read Android/Kotlin plugin versions from platform system properties. Keep SDK and application versions in version.properties and preserve signing logic. Java/Kotlin source encoding is UTF-8.
- Builds must be self-contained. Keep local API artifacts with their source and checksum, or use controlled source modules. Do not read sibling project JAR/AAR files at build time.
- sign.properties, local.properties, keystores and migration backups are ignored local files. Never log or commit their secrets. appendDigestToReleasedFiles must assemble and validate the exact signed output set, actual versions and CRC32 before collecting a release.
- Preserve protected Wake metadata, NoDisplay activity, WAKE action and DEFAULT category. Activation does no model loading or networking. Test the Manifest contract and actual service discovery/Binder paths.
- PluginInfo uses installed package versions, localized description, stable identity and explicit true ABI capabilities. Preserve published AIDL order and negotiate additions. Bound inputs, resource ownership and cancellation remain part of the contract.
- Application titles stay English and nontranslatable. Keep all ten locales plus explicit English, sort strings by name, put plurals/arrays in separate files, and use ASCII punctuation and escaped Android quotes.
- Maintain the existing purpose-specific PNG at app/src/main/res/mipmap/ic_launcher.png. README references must resolve to actual assets and the correct repository.
- Edit .readme/.changelog JSON and templates, then run the generator and its true read-only --check mode. Root README is Simplified Chinese. Generated changelogs belong under app/src/main/assets/doc, not .changelog.
- Preserve settings/local release-history behavior, fallback languages, accessibility and failure recovery. Keep networking and data permissions accurate in user documentation.
- Native capabilities require ELF/ZIP alignment checks and real 16 KB execution evidence. Static checks do not substitute for device tests. Record unavailable OEM activation and device matrix coverage explicitly.

- The 0.2.1-provider-dev line remains a development provider. The common INFO service supplements, and must not change, the frozen R8 compiler wire protocol. Keep shrinking disabled for the compiler payload.

- The app has no ABI-specific native implementation. Its universal APK and explicit empty PluginInfo ABI list support every host ABI; redundant byte-identical ABI splits are not required.

## Validation

```powershell
py .python/generate_markdown.py
py .python/generate_markdown.py --check
py .python/check_repository.py --pending-commit
.\gradlew.bat --no-daemon '-Djava.vendor=Eclipse Adoptium' '-Djava.vendor.version=Temurin-21.0.12.1+1' :app:assembleDebug :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebugAndroidTest :app:lintDebug
.\gradlew.bat :app:appendDigestToReleasedFiles
git diff --check
```

Run the relevant custom Python regression suites after changing their logic. After committing, run check_repository.py without --pending-commit and review git status. Install/activate/upgrade and Binder smoke tests on the exact signed release remain necessary evidence for an actual release.

## Selectable launcher icons

- Expose adaptive light, adaptive dark, adaptive automatic (default), and transparent modes in one settings row. Explain automatic/transparent launcher caching and background limitations.
- Keep all four `launcher.*IconAlias` component names stable. Keep MainActivity enabled for existing explicit intents. Enable the new alias before disabling the old one with DONT_KILL_APP, migrate mutable shortcut ownership, and restore previous states if switching fails.
- Regenerate the separate launcher resources with `py .python/generate_launcher_icons.py`; run its read-only `--check`. The source vector is preserved in `.python/icons/launcher-foreground.xml`. Original purpose-specific PNG/README/application resources remain unchanged.
- Fixed dark uses glyph #D8D8D8/background #212121; fixed light uses glyph #272727/background #FAFAFA. Auto must have an independent resource ID: PackageManager eagerly resolves values aliases when parsing activity icons. Supply default dark and notnight light legacy XML plus matching default-v26 and notnight-v26 adaptive XML. Never put a legacy night PNG ahead of an adaptive v26 resource with the same name.
- Run LauncherIconResourceTest and LauncherIconSelectionTest on API 24 and a modern API. Tests restore exact component states and remove only their own temporary shortcuts; do not clear user or launcher data.

## Standalone settings standard (2026-09-29)

Read `../AUTOJS6_PLUGIN_STANDALONE_SETTINGS_AGENTS.md` for the maintainer-approved common style. All four appearance settings are implemented here: language, night mode, theme color, launcher icon. Use neutral surfaces, 16/14 sp text, 72 dp minimum two-line rows, 24 dp horizontal padding and matching outline icons. Choices and HEX/RGB preview remain drafts until OK; Cancel must have no persistence side effect. Default appearance follows AutoJs6 with system/default fallback, and the launcher default is Auto. The update-only receiver normalizes component state without changing explicit choices or starting business work.

Appearance snapshots are read asynchronously through the local pinned official common API and validated before use. Never bypass host signing/enable checks; unavailable snapshots use an honest fallback. Theme coverage includes disabled states, radio/check/switch, input cursor/selection, buttons, sliders, menus and dynamically created rows. Preserve content-specific colors such as sampled pixels. Run AppearancePolicyTest, ThemeColorValueTest, SettingsAppearanceTest and LauncherIconSelectionTest, including cancel/confirm, contrast and old-default migration.

## Optical icon standard (2026-10-03)

- Follow `../AUTOJS6_PLUGIN_BLACK_N_WHITE_ADAPTIVE_ICON_AGENTS.md` for every standalone plugin, including the Plugin Center. `.python/icon_geometry.py` v1 is a self-contained copy of the common geometry algorithm; keep its implementation identical across the standalone plugins. Never read sibling checkouts during a build.
- Derive size from the equal-weight combination of visible bounding-box area (alpha >= 16) and alpha-weighted ink area. Target visible size is 0.52 of the canvas, with only documented optical corrections in 0.94-1.06. The adaptive ratio is always the UI ratio multiplied by 72/108. This supersedes older hardcoded UI/adaptive widths in historical notes. Preserve aspect ratio, optical placement and final nonzero-alpha safety checks.
- Current derived widths: UI 0.5971, adaptive 0.3981 (rounded documentation values, not generation constants). Optical scale=1.00 and zero offsets.
- Generate `mipmap/ic_plugin_center.png` and its night counterpart from the same geometry as the transparent UI/launcher mode. They are transparent neutral artwork for installed and catalog entries, independent of the active launcher alias. Keep them through `raw/keep_plugin_center_icon.xml`. Existing separate brand assets retain their original purpose.
- The default glyph colors are #272727 / #D8D8D8. Stamp Mail is the maintainer-approved grayscale exception: preserve the envelope folds, use neutral R=G=B values, and retain identical day/night alpha. Do not introduce a filled background into the Plugin Center assets.
- Run the icon generator and its read-only `--check`, `.python/tests/test_icon_geometry.py`, existing icon regressions, and review the full set at 36/48/64 px in both themes and in launcher masks. `.github/workflows/icons.yml` verifies Windows/Linux reproducibility. Synthetic previews do not replace actual launcher verification.
