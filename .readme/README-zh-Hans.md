<!--suppress HtmlDeprecatedAttribute, HttpUrlsUsage -->

<div align="center">
  <h1>AutoJs6-Plugin-R8-Compiler</h1>

  <p>AutoJs6 独立 R8 编译插件. 在隔离进程中以完整 release 配置 (shrink + optimize + obfuscate) 将脚本 JAR 编译为 DEX</p>

  <p><sub>当前阶段: 私有预发布 (源码与安装包尚未公开)</sub></p>
</div>

******

### 语言

******

当前 README.md 支持以下语言:

- 简体中文 [zh-Hans] # 当前
- [繁體中文 (香港) [zh-Hant-HK]](https://github.com/SuperMonster003/AutoJs6-Plugin-R8-Compiler/blob/master/.readme/README-zh-Hant-HK.md)
- [繁體中文 (台灣) [zh-Hant-TW]](https://github.com/SuperMonster003/AutoJs6-Plugin-R8-Compiler/blob/master/.readme/README-zh-Hant-TW.md)
- [English [en]](https://github.com/SuperMonster003/AutoJs6-Plugin-R8-Compiler/blob/master/.readme/README-en.md)
- [Français [fr]](https://github.com/SuperMonster003/AutoJs6-Plugin-R8-Compiler/blob/master/.readme/README-fr.md)
- [Español [es]](https://github.com/SuperMonster003/AutoJs6-Plugin-R8-Compiler/blob/master/.readme/README-es.md)
- [日本語 [ja]](https://github.com/SuperMonster003/AutoJs6-Plugin-R8-Compiler/blob/master/.readme/README-ja.md)
- [한국어 [ko]](https://github.com/SuperMonster003/AutoJs6-Plugin-R8-Compiler/blob/master/.readme/README-ko.md)
- [Русский [ru]](https://github.com/SuperMonster003/AutoJs6-Plugin-R8-Compiler/blob/master/.readme/README-ru.md)
- [العربية [ar]](https://github.com/SuperMonster003/AutoJs6-Plugin-R8-Compiler/blob/master/.readme/README-ar.md)

******

### 简介

******

AutoJs6 的脚本可以通过 `runtime.loadJar()` 加载 JAR 并调用其中的 Java 类. 这条默认路径使用 D8 完成 JAR 到 DEX 的纯编译, 不做压缩与混淆. 当你希望产物经过完整的 release 处理, 即移除未使用代码 (shrinking), 字节码优化 (optimization) 与标识符混淆 (obfuscation), 就需要 R8.

本插件是一个独立安装的应用, 在自己的隔离进程中运行固定版本的 Google R8 编译器, 为 AutoJs6 提供显式的完整 release 编译服务. 脚本通过专门的 `runtime.loadJarWithR8()` 入口发起编译并必须同时提供 keep 规则; AutoJs6 取回 DEX 与 mapping 等五件产物后逐一校验, 缓存, 并只加载验证过的 DEX.

与内置编译路径最大的不同在于: R8 入口是显式且无回退的. 编译失败时不会悄悄改用 D8/dx, 而是把错误如实抛给脚本. 这保证了一条简单的语义: 只要加载成功, 产物必然经过完整 R8 处理.

适合使用本插件的场景: 需要缩减 JAR 产物体积或对其混淆; 需要 mapping 文件用于日后还原混淆堆栈; 或希望编译语义完全确定 (要么完整 R8, 要么明确失败).

******

### 工作原理

******

启用插件后, 一次 `runtime.loadJarWithR8()` 调用大致经历以下步骤:

```text
1. script     calls runtime.loadJarWithR8(program, keepRules[, classpath[, consumerRules, ordinals]])
2. AutoJs6    snapshots program, classpath and rule files into one canonical path-free input bundle
3. plugin     re-verifies the bundle, then runs pinned R8 (full release profile) in its private ":r8" process
4. plugin     streams back one bundle with five artifacts: DEX ZIP, mapping, seeds, usage, retrace metadata
5. AutoJs6    re-validates every artifact, commits an R8-only cache generation, and loads the verified DEX ZIP
*  no fallback: any failure terminates the call as an R8 error; D8/dx is never used silently
```

插件只负责第 3 和第 4 步, 即 "编译" 本身; 输入的快照与冻结, 产物的校验, 缓存与最终加载始终由 AutoJs6 完成. 双方通过 Binder 只传递文件描述符, 线上数据不含任何文件路径, 插件不会读取脚本目录. 编译结果按输入内容与编译参数缓存在独立的 R8 缓存域中, 相同输入的重复加载会直接命中缓存; 缓存数据一旦损坏会被自动清除并重新编译, 每个从缓存打开的产物都会重新哈希校验.

******

### 功能特性

******

- 完整 release 编译: shrinking (移除未使用代码), optimization (字节码优化) 与 obfuscation (标识符混淆) 始终全部启用, 由固定版本 R8 8.13.17 完成.
- 显式语义, 无静默回退: 只有 `runtime.loadJarWithR8()` 会使用本插件; 任何失败都以 R8 错误结束, 绝不悄悄改用 D8/dx. `runtime.loadJar()` 与 `runtime.loadJarWithClasspath()` 的行为完全不变.
- 五件产物一次返回: DEX ZIP, mapping (混淆映射), seeds (被保留项清单), usage (被移除项清单) 与 retrace 元数据, 全部逐项绑定 SHA-256 并由宿主独立复验.
- 编译运行在插件自己的 `:r8` 独立进程与私有工作区中, 与 AutoJs6 相互隔离; 输入在编译前会被再次完整校验.
- 支持有序的编译期 classpath 与归属到具体 classpath JAR 的 consumer 规则; keep 规则必须显式提供, 不从归档内部隐式发现规则.
- 内置经字节校验的 Android 36 平台库作为编译库, 不依赖设备 boot classpath 中可能残缺的 JAR; 编译参数 minApi 24 至 36 全部经真实 R8 语料验证.
- 支持 Android 7.0 (API 24) 及以上; 已在 API 25/28/37 的真机与模拟器 (含 16 KiB 页大小设备) 上完成 ART 运行, JNI 调用与 Retrace 还原验证.
- 仅与同签名的 AutoJs6 通信 (受 `org.autojs.permission.PLUGIN` 权限保护), 不申请网络与存储权限.

******

### 与 DEX Compiler 插件的关系

******

AutoJs6 生态中存在两个独立的编译插件, 二者互补而不重叠, 可以同时安装:

- [AutoJs6-Plugin-DEX-Compiler](https://github.com/SuperMonster003/AutoJs6-Plugin-DEX-Compiler) 服务于默认路径 `runtime.loadJar()` / `runtime.loadJarWithClasspath()`: 用较新版本的 D8 做纯编译, 不压缩不混淆; 插件失败时宿主自动回退内置编译器.
- 本插件 (R8) 只服务于显式路径 `runtime.loadJarWithR8()`: 完整 release 编译, 必须提供 keep 规则; 失败即失败, 没有回退.

两者使用彼此独立的协议 (`dex-compiler-api` 与 `r8-compiler-api`), 服务 action, 开发者选项条目与缓存域, 互不依赖也互不感知. DEX 协议中的 `RELEASE` 只是 D8 的 release 编译模式, 与 R8 无关. 安装或卸载其中任何一个都不影响另一个的功能.

******

### 安装与使用

******

启用插件共三步: 安装包含 R8 集成的配对 AutoJs6, 安装本插件 APK, 然后在 AutoJs6 开发者选项中手动选择本插件. 有两点需要提前了解:

- 插件默认不生效. 仅安装不会改变 AutoJs6 的任何行为; 未启用时调用 `runtime.loadJarWithR8()` 会直接失败 (fail-closed), 而不是改用其他编译器.
- 随时可以撤销. 在开发者选项中停用该入口即可恢复原状, 无需卸载任何应用.

#### 安装前提

- 包含 `runtime.loadJarWithR8()` 集成的 AutoJs6 (代表性验证版本为 AutoJs6 6.8.0 (build 5276)); 旧版宿主没有该入口与对应的开发者选项条目.
- 宿主与插件必须来自同一可信来源且签名一致; 签名不一致时插件无法被选中, 请改用成对发布的安装包或成对自行构建.
- 本插件当前处于私有预发布阶段, 安装包来自私有 GitHub prerelease 或本地构建, 请与配对宿主一同获取.
- 自行构建时保持下方固定的包名与服务组件不变.

相关标识如下:

```text
host package: org.autojs.autojs6
plugin package: io.github.supermonster003.autojs6.plugin.r8compiler
paired host: AutoJs6 6.8.0 (build 5276)
exact component: io.github.supermonster003.autojs6.plugin.r8compiler/io.github.supermonster003.autojs6.plugin.r8compiler.R8CompilerService
```

#### 安装并启用

1. 安装或升级到包含 R8 集成的配对 AutoJs6.
2. 安装本插件 APK.
3. 打开 AutoJs6, 进入 设置 > 关于应用和开发者, 长按应用图标进入开发者选项.
4. 进入 R8 compiler > Explicit full-release R8 provider.
5. 选中本插件的服务组件 (即上方 exact component) 并确认.

再次强调: 仅安装插件不会自动启用, AutoJs6 也不会自动选择它发现的任何 provider; 未完成选择时 `runtime.loadJarWithR8()` 始终以失败结束.

#### 确认已生效

回到开发者选项页面, 当 Explicit full-release R8 provider 的摘要显示 `runtime.loadJarWithR8` 使用本插件组件时, 表示启用成功; 摘要含 disabled 或 fails closed 字样时, 表示入口仍处于关闭状态.

如果列表中找不到本插件, 请依次检查: 宿主是否为包含 R8 集成的配对构建; 宿主与插件的包名是否与上方一致; 插件应用是否被系统禁用; 两者签名是否一致.

与 DEX 插件不同, 本入口没有 "实际由谁编译" 的歧义: 只要 `runtime.loadJarWithR8()` 成功返回, 产物必然经过完整 R8 处理 (来自本次编译或先前已验证的缓存).

#### 脚本示例

把包含 JVM `.class` 文件的 JAR 与一份 keep 规则文件放到脚本目录, 然后调用重载家族中的任意一个入口. keep 规则不可省略: R8 会移除并混淆所有未被规则保留的符号, 没有规则的编译几乎必然产出无法按原名访问的类.

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

当 program JAR 引用了不在其内部的编译期类 (例如 API stub) 时, 使用三参数重载传入有序 classpath:

```javascript
runtime.loadJarWithR8(
    files.path("./lib/program.jar"),
    [files.path("./lib/keep-rules.pro")],
    [files.path("./lib/compile-api-stubs.jar")],
);
```

五参数重载额外接受 consumer 规则文件及其归属序号, 每份 consumer 规则通过序号绑定到 classpath 中对应的 JAR:

```javascript
runtime.loadJarWithR8(program, keepRuleFiles, classpathJars, consumerRuleFiles, ownerOrdinals);
```

要点:

- classpath JAR 只在编译期用于解析引用, 不会进入输出, 也不会被自动加载; 声明顺序有意义并参与缓存标识.
- keep 与 consumer 规则均为严格 UTF-8 文本; 含文件系统访问, include, 输入/输出重定向, 字典等危险指令的规则会被直接拒绝 (fail-closed).
- mapping 等产物目前由宿主验证后存入私有缓存, 暂无脚本可直接读取的导出入口 (见 ROADMAP).
- 编译不等于安全审查, 只加载你信任的 JAR.

#### keep 规则入门

关于 `Packages` 访问、反射、JNI、序列化与公共 API 面的最小可用配方，请阅读 [keep 规则实用指南](https://github.com/SuperMonster003/AutoJs6-Plugin-R8-Compiler/blob/master/docs/keep-rules-guide-zh-Hans.md).

#### 编译失败时会发生什么

本入口的失败语义非常简单: 要么拿到完整 R8 产物, 要么抛出错误, 没有中间态.

- 未选择提供者, 参数无效, 提供者不可用或繁忙 (BUSY, 同一时刻只允许一个编译会话), 编译失败, 超时 (默认 120 秒, 上限 300 秒), 产物校验失败, 缓存或加载失败: 以上情况全部以 R8 错误结束脚本调用, 绝不改用 D8/dx.
- 你主动取消 (例如停止脚本) 会立即终止本次调用, 同样不产生任何回退.
- 缓存命中不改变语义: 命中的产物在写入时已通过完整验证, 读取时还会再次重新哈希校验.

如果你希望 "失败时退回普通编译", 请在脚本层自行捕获错误并显式调用 `runtime.loadJar()`; 宿主不会替你做这个决定.

#### 排查问题与反馈

排查时常见原因依次为: 未在开发者选项中选择提供者; 规则被拒绝 (含被禁止的指令或非 UTF-8 编码); 输入超出资源上限; 类名书写错误或未被 keep 规则保留 (R8 已将未保留的符号混淆或移除). 反馈问题时请尽量附上以下信息:

- AutoJs6 版本与 build, 插件版本, 以及开发者选项摘要中的完整组件名.
- 设备型号, Android 版本 (API) 与 CPU 架构 (ABI).
- 触发问题的 program JAR 与全部规则文件 (或其字节数与 SHA-256), 完整脚本异常信息与复现步骤.

如果会使用 ADB, 以下命令可采集相关日志 (`<serial>` 替换为你的设备序列号; 分享前请删去日志中的私有路径与敏感内容):

```powershell
adb -s <serial> shell dumpsys package org.autojs.autojs6
adb -s <serial> shell dumpsys package io.github.supermonster003.autojs6.plugin.r8compiler
adb -s <serial> logcat -d -v threadtime AndroidClassLoader:D AndroidRuntime:E *:S
```

#### 关闭, 回滚与卸载

- 临时关闭: 在开发者选项的 Explicit full-release R8 provider 中停用该入口并确认. 此后 `runtime.loadJarWithR8()` 恢复为直接失败, 其他脚本行为不受影响; 已保留的组件选择可随时重新启用.
- 卸载插件: 先停用入口, 再停止 AutoJs6 并卸载插件 APK. 卸载会清除插件自己的全部数据与临时文件.
- 重新安装或更新插件后, 宿主会重新核验组件身份 (包括 UID 与签名), 需要在开发者选项中重新确认选择.

******

### 常见问题

******

**问: 为什么必须提供 keep 规则?**

答: R8 的 full-release 配置会移除并混淆一切未被显式保留的符号. 脚本通过 `Packages.xxx` 以反射方式访问类, R8 无法自动推断哪些符号必须保留, 因此协议将 keep 规则设为显式必填, 避免 "编译成功但类找不到" 的静默陷阱.

**问: 它比内置编译器或 DEX 插件更快吗?**

答: 不是, 通常更慢. R8 做全程序分析 (shrink/optimize/obfuscate), 本身就比纯 D8 编译昂贵; 换来的是更小, 更难逆向的产物与 mapping 文件. 已编译结果会被缓存, 相同输入的后续加载很快.

**问: 它能替代 DEX Compiler 插件吗 (或者反过来)?**

答: 不能. 两者服务于不同的脚本入口, 协议与缓存彼此独立, 详见 "与 DEX Compiler 插件的关系" 章节.

**问: 为什么失败不自动回退到 D8?**

答: 这是有意设计. 调用 R8 入口即声明 "我需要完整 release 产物"; 静默回退会让你在不知情的情况下拿到未混淆产物. 需要回退语义时, 请在脚本层捕获错误后自行调用 `runtime.loadJar()`.

**问: 混淆后的异常堆栈如何还原?**

答: 每次编译都会产出 mapping 与 retrace 元数据并被宿主缓存; 当前版本尚未提供从脚本或界面直接取用 mapping 的入口, retrace RPC 也在路线图中. 自行构建产物时, 可用 R8 retrace 工具配合你保存的 mapping 还原堆栈.

**问: 插件会联网或读取我的文件吗?**

答: 不会. 插件没有网络与存储权限, 只能通过 AutoJs6 递来的文件描述符读取待编译内容, 线上数据不含文件路径, 临时文件全部位于自己的私有目录.

**问: 启动器中的插件界面有什么作用?**

答: 插件的只读界面显示插件版本、固定 R8 版本、服务组件可用状态与内置更新日志. 它不会启用 provider; 选择和启用仍只能在 AutoJs6 开发者选项中完成.

******

### 能力边界

******

为避免误解, 以下事项明确不属于本插件的功能范围:

- 只服务 `runtime.loadJarWithR8()`; 不改变 `runtime.loadJar()` 与 `runtime.loadJarWithClasspath()` 的行为, 也不会被它们隐式选中.
- 没有 D8 模式, 没有 debug 编译, 也没有可单独关闭的 shrink/optimize/obfuscate 开关: 编译配置固定为 FULL_RELEASE.
- 不从 JAR 内部读取规则 (如 META-INF 中的 proguard 文件); keep 与 consumer 规则必须显式随请求提供.
- 拒绝含文件系统访问, include, 输入/输出重定向, mapping 导入, print, 字典与全局 profile 控制指令的规则 (协议 1.0 之外, fail-closed).
- 不提供 retrace RPC; retrace 元数据仅作映射溯源, mapping 暂无脚本导出入口 (两者均见 ROADMAP).
- 不下载或解析依赖 (没有 Maven/Gradle 集成), 不进行网络编译.
- 不处理 `.aar`, 已编译的 `.dex` 与 `defineClass()` 动态字节码, 它们始终走 AutoJs6 内置路径.
- 当前仅私有发布: 源码与安装包位于私有 GitHub 仓库, 公开发布是路线图中的独立事项.

******

### 技术参考

******

以下内容面向需要精确边界的开发者与集成方; 仅使用插件时通常无需阅读.

#### 输入与输出

协议 1.0 通过只读输入描述符接收一个规范化输入包, 通过只写输出描述符返回一个规范化产物包; 全程无文件路径, 输入整体与每件产物均绑定 SHA-256:

```text
input: 1 program JAR + ordered classpath JARs + explicit keep rules + optional consumer rules
output: DEX_ZIP + MAPPING_TEXT + SEEDS_TEXT + USAGE_TEXT + RETRACE_METADATA
compiler: R8 8.13.17
profile: FULL_RELEASE (shrink + optimize + obfuscate)
```

#### 插件发现标识

宿主通过以下标识发现并调用插件:

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

插件声明 R8 8.13.17, 协议 1.0, 固定 FULL_RELEASE 编译配置, minApi 24 至 36, multi-dex 输出与五产物能力集. 编译库为内置且经字节校验的 Android 36 平台库; runtime library 指纹仍绑定观测到的设备 boot classpath 文件.

插件不含 native library, 以一个纯 JVM universal APK 覆盖所有设备 ABI; 已验证的 JNI 调用针对的是被编译 JAR 中的 native 方法, 而非插件自身.

#### 安全模型

插件不申请网络与存储权限. 编译服务受 `org.autojs.permission.PLUGIN` 权限保护并运行在独立的 `:r8` 进程; 每次调用双向核验包名, 调用方 UID 与双方签名, 只接受同签名的 AutoJs6 宿主. 输入输出均通过文件描述符传递, 访问模式异常或输入/输出别名同一端点的描述符会被拒绝; 临时文件仅位于插件私有工作区, 陈旧工作区会被自动回收. 宿主侧同样独立复验每件产物, 类加载器只接受再次哈希校验后的只读 DEX 副本.

#### 资源上限

为防御恶意或异常输入, 协议对各环节设置了硬性上限, 超限请求会被直接拒绝:

- program JAR: 最大 128 MiB; classpath: 最多 32 个 JAR, 单个最大 64 MiB, 总量最大 128 MiB.
- 规则文件: keep 最多 16 个, consumer 最多 32 个; 单文件最大 256 KiB, 规则总量最大 2 MiB, 单行最大 16 KiB.
- 输入包整体最大 260 MiB; 归档展开: 单 JAR 最多 20000 个 entry, 总计最多 60000 个, 解压总量最大 512 MiB; class 数据单个最大 8 MiB, 总量最大 256 MiB.
- 输出包最大 256 MiB: DEX ZIP 最大 192 MiB, mapping 最大 32 MiB, seeds 与 usage 各最大 16 MiB, retrace 元数据最大 256 KiB.
- 并发: 同一时刻只处理一个编译会话, 其余请求收到可重试的 BUSY; 超时默认 120 秒, 上限 300 秒.
- 诊断数据最多 64 KiB, 且经过路径脱敏.

#### 注意事项

- minApi 是编译参数而非设备运行声明; 产物无法在低于其 minApi 的设备上加载.
- R8 版本被固定 (当前 R8 8.13.17); 缓存标识包含编译器与运行时指纹, 升级编译器不会误用旧缓存.
- 取消会立即阻止结果发布, 但 R8 内部的 CPU 计算可能在隔离进程中继续到本次编译返回; 会话槽在清理完成前保持 BUSY.
- 同一输入在同一编译器版本下可复现 (发布 Gate 验证过同环境字节级一致), 但不承诺跨 R8 版本的字节级一致.
- 宿主构建横幅中显示的 AGP 自带 R8 版本属于 APK 打包工具链, 与本插件的编译器版本无关.

******

### 开发路线图

******

开发按可核验的阶段 (Gate) 推进: G1 契约冻结, G2 提供者实现, G3 宿主集成, G4 兼容性语料, G5 本地签名发布, G6 设备验收, G7 ART/JNI/Retrace 闭环与 G8 私有远程发布均已完成, 每个阶段都留有绑定 SHA-256 的可复核证据. 后续计划 (公开发布, retrace 能力开放, 文档与体验, 引擎升级维护) 及各条目的完成定义见:

- [查看可勾选的 ROADMAP.md](https://github.com/SuperMonster003/AutoJs6-Plugin-R8-Compiler/blob/master/ROADMAP.md)

******

### 版本历史

******

# v0.2.0

###### 2026/09/11

* `优化` 构建阶段阻止意外引入原生依赖, 并输出 JSON 校验报告

# v0.1.0-provider-dev-private.1 (local.5)

###### 2026/08/25

* `提示` 当前版本. 通过私有 GitHub prerelease 发布 (含签名 APK, 两个冻结契约 AAR, 发布清单与 SHA256SUMS 共 5 项资产, 全部经重新下载与逐字节校验), 尚未公开发布
* `提示` 安装后默认不生效, 需在 AutoJs6 开发者选项中手动启用; 详细步骤见 README 的 "安装与使用" 章节
* `新增` 内置经字节校验的 Android 36 平台库作为 R8 编译库, 不再依赖设备 boot classpath; 修复部分设备上 boot JAR 为资源壳导致的编译失败
* `新增` 添加有界且路径脱敏的 R8 诊断信息收集, 覆盖提供者启动与引擎导入失败场景
* `优化` 在 API 25/28/37 (含 16 KiB 页大小模拟器) 的 ART 上完成优化产物运行验证: 反射, 动态类名, 序列化, 脚本入口, 移除诱饵检查, arm64/x86/x86_64 JNI 调用, 以及 mapping 哈希校验后的 R8 Retrace 堆栈还原

# v0.1.0-provider-dev (local.4)

###### 2026/08/25

* `修复` 以零字节的公开 `Os.read`/`Os.write` 内核探针替换 `/proc/self/fdinfo` 访问模式检查, 解决部分设备 (如 Sony API 28) 的 procfs 访问限制; 继续保留 `Os.fstat` 别名拒绝
* `优化` 在 1 台真机与 2 台模拟器 (API 25/28) 上完成跨 APK Binder/PFD 设备验收: 快乐路径, 生命周期, 恶意输入与进程死亡共 9/9 用例通过

##### 更多版本

* [CHANGELOG-zh-Hans.md](https://github.com/SuperMonster003/AutoJs6-Plugin-R8-Compiler/blob/master/app/src/main/assets/doc/CHANGELOG-zh-Hans.md)

******

### 构建

******

```powershell
.\gradlew.bat :app:assembleDebug
```

发布构建:

```powershell
.\gradlew.bat :app:assembleRelease
```

构建要求 JDK 17 或更高 (建议 21) 与 Android SDK Platform 36: 构建脚本会按字节校验 `platforms/android-36/android.jar` 并将其内置为编译库资产, 校验失败即终止构建. 当前 minSdk 为 24, targetSdk 为 36.

协议 ABI 由仓库内冻结的 0.1.0 契约 AAR 提供 (位于 `plugin-api/r8-compiler-api/releases/0.1.0/`), 应用消费的是这些 AAR 字节而非其源码工程:

```text
protocol-wire-api-0.1.0.aar
r8-compiler-api-0.1.0.aar
```

编译器通过 Maven 引入固定版本 R8 8.13.17. 正式发布使用 `scripts/` 目录下的发布与验证脚本 (append-only 本地发布目录, 双快照可复现构建与逐 Gate 校验); 日常调试直接使用上方 Gradle 命令即可.

******

### 许可证

******

项目源码使用 MPL-2.0. R8 和其他第三方组件继续适用各自的许可证.

******

### 资源布局

******

```text
.readme/lang_*.json
.changelog/lang_*.json
.python/generate_markdown.py
app/src/main/assets/doc/CHANGELOG-*.md
```

`.python/generate_markdown.py` 从 JSON 源生成全部 10 种语言的 README 与 CHANGELOG (含仓库根目录的 README.md 与 CHANGELOG.md); 修改文档请编辑 JSON 源而非生成的 Markdown.

如需在不修改工作区的情况下验证全部生成 Markdown 与源文件一致，请运行:

```powershell
python .python/generate_markdown.py --check
```

******

### 链接

******

- AutoJs6 文档: https://docs.autojs6.com
- R8 项目: https://r8.googlesource.com/r8
- DEX Compiler 插件 (D8): https://github.com/SuperMonster003/AutoJs6-Plugin-DEX-Compiler
- 私有发布页 (需要访问权限): https://github.com/SuperMonster003/AutoJs6-Plugin-R8-Compiler/releases/tag/v0.1.0-provider-dev-private.1


[16 KB page alignment and build verification](https://github.com/SuperMonster003/AutoJs6-Plugin-R8-Compiler/blob/master/docs/16kb.md)
