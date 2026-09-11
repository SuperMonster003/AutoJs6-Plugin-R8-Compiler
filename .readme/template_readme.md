<!--suppress HtmlDeprecatedAttribute, HttpUrlsUsage -->

<div align="center">
  <h1>AutoJs6-Plugin-R8-Compiler</h1>

  <p>{{ text_plugin_synopsis }}</p>

  <p><sub>{{ text_release_stage }}</sub></p>
</div>

******

### {{ h3_languages_with_ascii }}

******

{{ p_languages_all_supported_for_readme }}:

{{ placeholder_ul_languages_all_supported }}

******

### {{ h3_introduction }}

******

{{ p_introduction }}

******

### {{ h3_how_it_works }}

******

{{ p_how_it_works }}:

```text
1. script     calls runtime.loadJarWithR8(program, keepRules[, classpath[, consumerRules, ordinals]])
2. AutoJs6    snapshots program, classpath and rule files into one canonical path-free input bundle
3. plugin     re-verifies the bundle, then runs pinned R8 (full release profile) in its private ":r8" process
4. plugin     streams back one bundle with five artifacts: DEX ZIP, mapping, seeds, usage, retrace metadata
5. AutoJs6    re-validates every artifact, commits an R8-only cache generation, and loads the verified DEX ZIP
*  no fallback: any failure terminates the call as an R8 error; D8/dx is never used silently
```

{{ p_how_it_works_note }}

******

### {{ h3_functions }}

******

{{ placeholder_features }}

******

### {{ h3_dex_relation }}

******

{{ p_dex_relation }}

******

### {{ h3_user_guide }}

******

{{ p_user_guide_overview }}

#### {{ h4_user_guide_prerequisites }}

{{ p_user_guide_prerequisites }}

```text
host package: {{ host_package }}
plugin package: {{ plugin_package }}
paired host: {{ paired_host_build }}
exact component: {{ exact_service_component }}
```

#### {{ h4_user_guide_install_enable }}

{{ p_user_guide_install_enable }}

#### {{ h4_user_guide_status }}

{{ p_user_guide_status }}

#### {{ h4_user_guide_example }}

{{ p_user_guide_example }}

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

{{ p_user_guide_example_note }}

#### {{ h4_keep_rules_guide }}

{{ p_keep_rules_guide }}

#### {{ h4_user_guide_failure }}

{{ p_user_guide_failure }}

#### {{ h4_user_guide_troubleshooting }}

{{ p_user_guide_troubleshooting }}

```powershell
adb -s <serial> shell dumpsys package {{ host_package }}
adb -s <serial> shell dumpsys package {{ plugin_package }}
adb -s <serial> logcat -d -v threadtime AndroidClassLoader:D AndroidRuntime:E *:S
```

#### {{ h4_user_guide_disable_uninstall }}

{{ p_user_guide_disable_uninstall }}

******

### {{ h3_faq }}

******

{{ p_faq }}

******

### {{ h3_boundaries }}

******

{{ p_boundaries_intro }}:

{{ placeholder_boundaries }}

******

### {{ h3_reference }}

******

{{ p_reference_intro }}

#### {{ h4_reference_formats }}

{{ p_formats }}:

```text
input: 1 program JAR + ordered classpath JARs + explicit keep rules + optional consumer rules
output: DEX_ZIP + MAPPING_TEXT + SEEDS_TEXT + USAGE_TEXT + RETRACE_METADATA
compiler: {{ compiler_dependency }}
profile: {{ compiler_profile }}
```

#### {{ h4_reference_interface }}

{{ p_plugin_interface }}:

```text
service action: {{ plugin_action }}
plugin id: {{ plugin_id }}
protocol provider id: {{ protocol_provider_id }}
engine: {{ plugin_engine }}
variant: {{ plugin_variant }}
protocol: {{ protocol_version }}
api namespace: {{ api_namespace }}
distribution: {{ distribution_coordinate }}
cache domain: {{ cache_domain }}
```

{{ p_plugin_scope }}

{{ p_plugin_packaging }}

#### {{ h4_reference_security }}

{{ p_security }}

#### {{ h4_reference_limits }}

{{ p_reference_limits_intro }}:

{{ placeholder_security_limits }}

#### {{ h4_reference_caveats }}

{{ placeholder_caveats }}

******

### {{ h3_roadmap }}

******

{{ p_roadmap_status }}

- [{{ text_open_roadmap }}]({{ repo_url }}/blob/master/ROADMAP.md)

******

### {{ h3_release_history }}

******

{{ placeholder_latest_release_history }}

##### {{ h5_for_more_release_history }}

* {{ placeholder_read_more_in_changelog_md }}

******

### {{ h3_build }}

******

```powershell
.\gradlew.bat :app:assembleDebug
```

{{ text_release_build }}:

```powershell
.\gradlew.bat :app:assembleRelease
```

{{ p_build_params }}.

{{ p_local_aars }}:

```text
{{ local_aars }}
```

{{ p_build_architecture }}

******

### {{ h3_license }}

******

{{ p_license }}

******

### {{ h3_resource_layout }}

******

```text
.readme/lang_*.json
.changelog/lang_*.json
.python/generate_markdown.py
app/src/main/assets/doc/CHANGELOG-*.md
```

{{ p_resource_layout }}.

{{ text_generated_docs_check }}:

```powershell
python .python/generate_markdown.py --check
```

******

### {{ h3_links }}

******

- {{ text_link_autojs6_docs }}: {{ docs_autojs6_url }}
- {{ text_link_upstream }}: {{ upstream_url }}
- {{ text_link_dex_plugin }}: {{ dex_plugin_repo_url }}
- {{ text_link_release_page }}: {{ release_url }}


[16 KB page alignment and build verification](https://github.com/SuperMonster003/AutoJs6-Plugin-R8-Compiler/blob/master/docs/16kb.md)
