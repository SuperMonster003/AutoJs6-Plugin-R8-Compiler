<!--suppress HtmlDeprecatedAttribute, HttpUrlsUsage -->

<div align="center">
  <h1>AutoJs6-Plugin-R8-Compiler</h1>

  <p>AutoJs6 獨立 R8 編譯插件. 在隔離進程中以完整 release 配置 (shrink + optimize + obfuscate) 將腳本 JAR 編譯為 DEX</p>

  <p><sub>目前階段: 私有預發佈 (原始碼與安裝包尚未公開)</sub></p>
</div>

******

### 語言

******

目前 README.md 支援以下語言:

- [简体中文 [zh-Hans]](https://github.com/SuperMonster003/AutoJs6-Plugin-R8-Compiler/blob/master/.readme/README-zh-Hans.md)
- 繁體中文 (香港) [zh-Hant-HK] # 目前
- [繁體中文 (台灣) [zh-Hant-TW]](https://github.com/SuperMonster003/AutoJs6-Plugin-R8-Compiler/blob/master/.readme/README-zh-Hant-TW.md)
- [English [en]](https://github.com/SuperMonster003/AutoJs6-Plugin-R8-Compiler/blob/master/.readme/README-en.md)
- [Français [fr]](https://github.com/SuperMonster003/AutoJs6-Plugin-R8-Compiler/blob/master/.readme/README-fr.md)
- [Español [es]](https://github.com/SuperMonster003/AutoJs6-Plugin-R8-Compiler/blob/master/.readme/README-es.md)
- [日本語 [ja]](https://github.com/SuperMonster003/AutoJs6-Plugin-R8-Compiler/blob/master/.readme/README-ja.md)
- [한국어 [ko]](https://github.com/SuperMonster003/AutoJs6-Plugin-R8-Compiler/blob/master/.readme/README-ko.md)
- [Русский [ru]](https://github.com/SuperMonster003/AutoJs6-Plugin-R8-Compiler/blob/master/.readme/README-ru.md)
- [العربية [ar]](https://github.com/SuperMonster003/AutoJs6-Plugin-R8-Compiler/blob/master/.readme/README-ar.md)

******

### 簡介

******

AutoJs6 的腳本可以透過 `runtime.loadJar()` 載入 JAR 並呼叫其中的 Java 類別. 這條預設路徑使用 D8 完成 JAR 到 DEX 的純編譯, 不做壓縮與混淆. 當你希望產物經過完整的 release 處理, 即移除未使用程式碼 (shrinking), 位元組碼優化 (optimization) 與標識符混淆 (obfuscation), 就需要 R8.

本插件是一個獨立安裝的應用程式, 在自己的隔離進程中執行固定版本的 Google R8 編譯器, 為 AutoJs6 提供顯式的完整 release 編譯服務. 腳本透過專門的 `runtime.loadJarWithR8()` 入口發起編譯並必須同時提供 keep 規則; AutoJs6 取回 DEX 與 mapping 等五件產物後逐一核驗, 快取, 並只載入驗證過的 DEX.

與內置編譯路徑最大的不同在於: R8 入口是顯式且無回退的. 編譯失敗時不會悄悄改用 D8/dx, 而是把錯誤如實拋給腳本. 這保證了一條簡單的語義: 只要載入成功, 產物必然經過完整 R8 處理.

適合使用本插件的情況: 需要縮減 JAR 產物體積或對其混淆; 需要 mapping 檔案用於日後還原混淆堆疊; 或希望編譯語義完全確定 (要麼完整 R8, 要麼明確失敗).

******

### 運作原理

******

啟用插件後, 一次 `runtime.loadJarWithR8()` 呼叫大致經歷以下步驟:

```text
1. script     calls runtime.loadJarWithR8(program, keepRules[, classpath[, consumerRules, ordinals]])
2. AutoJs6    snapshots program, classpath and rule files into one canonical path-free input bundle
3. plugin     re-verifies the bundle, then runs pinned R8 (full release profile) in its private ":r8" process
4. plugin     streams back one bundle with five artifacts: DEX ZIP, mapping, seeds, usage, retrace metadata
5. AutoJs6    re-validates every artifact, commits an R8-only cache generation, and loads the verified DEX ZIP
*  no fallback: any failure terminates the call as an R8 error; D8/dx is never used silently
```

插件只負責第 3 和第 4 步, 即 "編譯" 本身; 輸入的快照與固定, 產物的核驗, 快取與最終載入始終由 AutoJs6 完成. 雙方透過 Binder 只傳遞檔案描述符, 線上資料不含任何檔案路徑, 插件不會讀取腳本目錄. 編譯結果按輸入內容與編譯參數快取在獨立的 R8 快取域中, 相同輸入的重複載入會直接命中快取; 快取資料一旦損壞會被自動清除並重新編譯, 每個從快取開啟的產物都會重新雜湊核驗.

******

### 功能特性

******

- 完整 release 編譯: shrinking (移除未使用程式碼), optimization (位元組碼優化) 與 obfuscation (標識符混淆) 始終全部啟用, 由固定版本 R8 8.13.17 完成.
- 顯式語義, 無靜默回退: 只有 `runtime.loadJarWithR8()` 會使用本插件; 任何失敗都以 R8 錯誤結束, 絕不悄悄改用 D8/dx. `runtime.loadJar()` 與 `runtime.loadJarWithClasspath()` 的行為完全不變.
- 五件產物一次傳回: DEX ZIP, mapping (混淆映射), seeds (被保留項清單), usage (被移除項清單) 與 retrace 元資料, 全部逐項綁定 SHA-256 並由主程式獨立複驗.
- 編譯執行於插件自己的 `:r8` 獨立進程與私人工作區中, 與 AutoJs6 相互隔離; 輸入在編譯前會被再次完整核驗.
- 支援有序的編譯期 classpath 與歸屬到具體 classpath JAR 的 consumer 規則; keep 規則必須顯式提供, 不從歸檔內部隱式發現規則.
- 內置經位元組核驗的 Android 36 平台庫作為編譯庫, 不依賴裝置 boot classpath 中可能殘缺的 JAR; 編譯參數 minApi 24 至 36 全部經真實 R8 語料驗證.
- 支援 Android 7.0 (API 24) 及以上; 已在 API 25/28/37 的真機與模擬器 (含 16 KiB 頁大小裝置) 上完成 ART 執行, JNI 呼叫與 Retrace 還原驗證.
- 僅與同簽名的 AutoJs6 通訊 (受 `org.autojs.permission.PLUGIN` 權限保護), 不要求網絡與儲存權限.

******

### 與 DEX Compiler 插件的關係

******

AutoJs6 生態中存在兩個獨立的編譯插件, 二者互補而不重疊, 可以同時安裝:

- [AutoJs6-Plugin-DEX-Compiler](https://github.com/SuperMonster003/AutoJs6-Plugin-DEX-Compiler) 服務於預設路徑 `runtime.loadJar()` / `runtime.loadJarWithClasspath()`: 用較新版本的 D8 做純編譯, 不壓縮不混淆; 插件失敗時主程式自動回退內置編譯器.
- 本插件 (R8) 只服務於顯式路徑 `runtime.loadJarWithR8()`: 完整 release 編譯, 必須提供 keep 規則; 失敗即失敗, 沒有回退.

兩者使用彼此獨立的協議 (`dex-compiler-api` 與 `r8-compiler-api`), 服務 action, 開發者選項條目與快取域, 互不依賴亦互不感知. DEX 協議中的 `RELEASE` 只是 D8 的 release 編譯模式, 與 R8 無關. 安裝或解除安裝其中任何一個都不影響另一個的功能.

******

### 安裝及使用

******

啟用插件共三步: 安裝包含 R8 整合的配對 AutoJs6, 安裝本插件 APK, 然後在 AutoJs6 開發者選項中手動選擇本插件. 有兩點需要提前了解:

- 插件預設不生效. 僅安裝不會改變 AutoJs6 的任何行為; 未啟用時呼叫 `runtime.loadJarWithR8()` 會直接失敗 (fail-closed), 而不是改用其他編譯器.
- 隨時可以撤銷. 在開發者選項中停用該入口即可回復原狀, 毋須解除安裝任何應用程式.

#### 安裝前提

- 包含 `runtime.loadJarWithR8()` 整合的 AutoJs6 (代表性驗證版本為 AutoJs6 6.8.0 (build 5276)); 舊版主程式沒有該入口與對應的開發者選項條目.
- 主程式與插件必須來自同一可信來源且簽名一致; 簽名不一致時插件無法被選中, 請改用成對發佈的安裝包或成對自行構建.
- 本插件目前處於私有預發佈階段, 安裝包來自私有 GitHub prerelease 或本地構建, 請與配對主程式一同取得.
- 自行構建時保持下方固定的套件名稱與服務組件不變.

相關標識如下:

```text
host package: org.autojs.autojs6
plugin package: io.github.supermonster003.autojs6.plugin.r8compiler
paired host: AutoJs6 6.8.0 (build 5276)
exact component: io.github.supermonster003.autojs6.plugin.r8compiler/io.github.supermonster003.autojs6.plugin.r8compiler.R8CompilerService
```

#### 安裝並啟用

1. 安裝或升級至包含 R8 整合的配對 AutoJs6.
2. 安裝本插件 APK.
3. 開啟 AutoJs6, 進入 設定 > 關於應用程式及開發者, 長按應用程式圖示進入開發者選項.
4. 開啟 R8 compiler > Explicit full-release R8 provider.
5. 選取本插件的服務組件 (即上方 exact component) 並確認.

再次強調: 僅安裝插件不會自動啟用, AutoJs6 亦不會自動選擇它發現的任何 provider; 未完成選擇時 `runtime.loadJarWithR8()` 始終以失敗結束.

#### 確認已生效

回到開發者選項頁面, 當 Explicit full-release R8 provider 的摘要顯示 `runtime.loadJarWithR8` 使用本插件組件時, 表示啟用成功; 摘要含 disabled 或 fails closed 字樣時, 表示入口仍處於關閉狀態.

如果清單中找不到本插件, 請依次檢查: 主程式是否為包含 R8 整合的配對構建; 主程式與插件的套件名稱是否與上方一致; 插件應用程式是否被系統停用; 兩者簽名是否一致.

與 DEX 插件不同, 本入口沒有 "實際由誰編譯" 的歧義: 只要 `runtime.loadJarWithR8()` 成功傳回, 產物必然經過完整 R8 處理 (來自本次編譯或先前已驗證的快取).

#### 腳本範例

把包含 JVM `.class` 檔案的 JAR 與一份 keep 規則檔案放到腳本目錄, 然後呼叫重載家族中的任意一個入口. keep 規則不可省略: R8 會移除並混淆所有未被規則保留的符號, 沒有規則的編譯幾乎必然產出無法按原名存取的類別.

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

當 program JAR 引用了不在其內部的編譯期類別 (例如 API stub) 時, 使用三參數重載傳入有序 classpath:

```javascript
runtime.loadJarWithR8(
    files.path("./lib/program.jar"),
    [files.path("./lib/keep-rules.pro")],
    [files.path("./lib/compile-api-stubs.jar")],
);
```

五參數重載額外接受 consumer 規則檔案及其歸屬序號, 每份 consumer 規則透過序號綁定到 classpath 中對應的 JAR:

```javascript
runtime.loadJarWithR8(program, keepRuleFiles, classpathJars, consumerRuleFiles, ownerOrdinals);
```

要點:

- classpath JAR 只在編譯期用於解析引用, 不會進入輸出, 亦不會被自動載入; 宣告順序有意義並參與快取標識.
- keep 與 consumer 規則均為嚴格 UTF-8 文字; 含檔案系統存取, include, 輸入/輸出重定向, 字典等危險指令的規則會被直接拒絕 (fail-closed).
- mapping 等產物目前由主程式驗證後存入私人快取, 暫無腳本可直接讀取的匯出入口 (見 ROADMAP).
- 編譯不等於安全審查, 只載入你信任的 JAR.

#### keep 規則入門

關於 `Packages` 存取、反射、JNI、序列化與公開 API 介面的最小可用配方，請閱讀 [keep 規則實用指南](https://github.com/SuperMonster003/AutoJs6-Plugin-R8-Compiler/blob/master/docs/keep-rules-guide.md).

#### 編譯失敗時會發生甚麼

本入口的失敗語義非常簡單: 要麼取得完整 R8 產物, 要麼拋出錯誤, 沒有中間態.

- 未選擇提供者, 參數無效, 提供者不可用或繁忙 (BUSY, 同一時刻只允許一個編譯工作階段), 編譯失敗, 逾時 (預設 120 秒, 上限 300 秒), 產物核驗失敗, 快取或載入失敗: 以上情況全部以 R8 錯誤結束腳本呼叫, 絕不改用 D8/dx.
- 你主動取消 (例如停止腳本) 會立即終止本次呼叫, 同樣不產生任何回退.
- 快取命中不改變語義: 命中的產物在寫入時已通過完整驗證, 讀取時還會再次重新雜湊核驗.

如果你希望 "失敗時退回普通編譯", 請在腳本層自行捕獲錯誤並顯式呼叫 `runtime.loadJar()`; 主程式不會替你做這個決定.

#### 排查問題及回報

排查時常見原因依次為: 未在開發者選項中選擇提供者; 規則被拒絕 (含被禁止的指令或非 UTF-8 編碼); 輸入超出資源上限; 類別名稱書寫錯誤或未被 keep 規則保留 (R8 已將未保留的符號混淆或移除). 回報問題時請盡量附上以下資訊:

- AutoJs6 版本與 build, 插件版本, 以及開發者選項摘要中的完整組件名稱.
- 裝置型號, Android 版本 (API) 與 CPU 架構 (ABI).
- 觸發問題的 program JAR 與全部規則檔案 (或其位元組數與 SHA-256), 完整腳本異常資訊與重現步驟.

如果會使用 ADB, 以下命令可收集相關記錄 (`<serial>` 替換為你的裝置序號; 分享前請刪去記錄中的私人路徑與敏感內容):

```powershell
adb -s <serial> shell dumpsys package org.autojs.autojs6
adb -s <serial> shell dumpsys package io.github.supermonster003.autojs6.plugin.r8compiler
adb -s <serial> logcat -d -v threadtime AndroidClassLoader:D AndroidRuntime:E *:S
```

#### 停用, 回復及解除安裝

- 暫時停用: 在開發者選項的 Explicit full-release R8 provider 中停用該入口並確認. 此後 `runtime.loadJarWithR8()` 回復為直接失敗, 其他腳本行為不受影響; 已保留的組件選擇可隨時重新啟用.
- 解除安裝插件: 先停用入口, 再停止 AutoJs6 並解除安裝插件 APK. 解除安裝會清除插件自己的全部資料與暫存檔案.
- 重新安裝或更新插件後, 主程式會重新核驗組件身份 (包括 UID 與簽名), 需要在開發者選項中重新確認選擇.

******

### 常見問題

******

**問: 為甚麼必須提供 keep 規則?**

答: R8 的 full-release 配置會移除並混淆一切未被顯式保留的符號. 腳本透過 `Packages.xxx` 以反射方式存取類別, R8 無法自動推斷哪些符號必須保留, 因此協議將 keep 規則設為顯式必填, 避免 "編譯成功但類別找不到" 的靜默陷阱.

**問: 它比內置編譯器或 DEX 插件更快嗎?**

答: 不是, 通常更慢. R8 做全程式分析 (shrink/optimize/obfuscate), 本身就比純 D8 編譯昂貴; 換來的是更小, 更難逆向的產物與 mapping 檔案. 已編譯結果會被快取, 相同輸入的後續載入很快.

**問: 它能取代 DEX Compiler 插件嗎 (或者反過來)?**

答: 不能. 兩者服務於不同的腳本入口, 協議與快取彼此獨立, 詳見 "與 DEX Compiler 插件的關係" 章節.

**問: 為甚麼失敗不自動回退到 D8?**

答: 這是有意設計. 呼叫 R8 入口即聲明 "我需要完整 release 產物"; 靜默回退會讓你在不知情的情況下取得未混淆產物. 需要回退語義時, 請在腳本層捕獲錯誤後自行呼叫 `runtime.loadJar()`.

**問: 混淆後的異常堆疊如何還原?**

答: 每次編譯都會產出 mapping 與 retrace 元資料並被主程式快取; 目前版本尚未提供從腳本或介面直接取用 mapping 的入口, retrace RPC 亦在路線圖中. 自行構建產物時, 可用 R8 retrace 工具配合你保存的 mapping 還原堆疊.

**問: 插件會連接網絡或讀取我的檔案嗎?**

答: 不會. 插件沒有網絡與儲存權限, 只能透過 AutoJs6 遞來的檔案描述符讀取待編譯內容, 線上資料不含檔案路徑, 暫存檔案全部位於自己的私人目錄.

**問: 啟動器中的插件介面有甚麼作用?**

答: 插件的唯讀介面顯示插件版本、固定 R8 版本、服務元件可用狀態與內置更新日誌. 它不會啟用 provider; 選擇與啟用仍只可在 AutoJs6 開發者選項中完成.

******

### 能力邊界

******

為避免誤解, 以下事項明確不屬於本插件的功能範圍:

- 只服務 `runtime.loadJarWithR8()`; 不改變 `runtime.loadJar()` 與 `runtime.loadJarWithClasspath()` 的行為, 亦不會被它們隱式選中.
- 沒有 D8 模式, 沒有 debug 編譯, 亦沒有可單獨關閉的 shrink/optimize/obfuscate 開關: 編譯配置固定為 FULL_RELEASE.
- 不從 JAR 內部讀取規則 (如 META-INF 中的 proguard 檔案); keep 與 consumer 規則必須顯式隨請求提供.
- 拒絕含檔案系統存取, include, 輸入/輸出重定向, mapping 匯入, print, 字典與全域 profile 控制指令的規則 (協議 1.0 之外, fail-closed).
- 不提供 retrace RPC; retrace 元資料僅作映射溯源, mapping 暫無腳本匯出入口 (兩者均見 ROADMAP).
- 不下載或解析依賴 (沒有 Maven/Gradle 整合), 不進行網絡編譯.
- 不處理 `.aar`, 已編譯的 `.dex` 與 `defineClass()` 動態位元組碼, 它們始終走 AutoJs6 內置路徑.
- 目前僅私有發佈: 原始碼與安裝包位於私有 GitHub 儲存庫, 公開發佈是路線圖中的獨立事項.

******

### 技術參考

******

以下內容面向需要精確邊界的開發者與整合方; 僅使用插件時通常毋須閱讀.

#### 輸入與輸出

協議 1.0 透過唯讀輸入描述符接收一個規範化輸入包, 透過唯寫輸出描述符傳回一個規範化產物包; 全程無檔案路徑, 輸入整體與每件產物均綁定 SHA-256:

```text
input: 1 program JAR + ordered classpath JARs + explicit keep rules + optional consumer rules
output: DEX_ZIP + MAPPING_TEXT + SEEDS_TEXT + USAGE_TEXT + RETRACE_METADATA
compiler: R8 8.13.17
profile: FULL_RELEASE (shrink + optimize + obfuscate)
```

#### 插件發現標識

主程式透過以下標識發現並呼叫插件:

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

插件聲明 R8 8.13.17, 協議 1.0, 固定 FULL_RELEASE 編譯配置, minApi 24 至 36, multi-dex 輸出與五產物能力集. 編譯庫為內置且經位元組核驗的 Android 36 平台庫; runtime library 指紋仍綁定觀測到的裝置 boot classpath 檔案.

插件不含 native library, 以一個純 JVM universal APK 覆蓋所有裝置 ABI; 已驗證的 JNI 呼叫針對的是被編譯 JAR 中的 native 方法, 而非插件自身.

#### 安全模型

插件不要求網絡與儲存權限. 編譯服務受 `org.autojs.permission.PLUGIN` 權限保護並執行於獨立的 `:r8` 進程; 每次呼叫雙向核驗套件名稱, 呼叫方 UID 與雙方簽名, 只接受同簽名的 AutoJs6 主程式. 輸入輸出均透過檔案描述符傳遞, 存取模式異常或輸入/輸出別名同一端點的描述符會被拒絕; 暫存檔案僅位於插件私人工作區, 陳舊工作區會被自動回收. 主程式側同樣獨立複驗每件產物, 類別載入器只接受再次雜湊核驗後的唯讀 DEX 副本.

#### 資源上限

為防禦惡意或異常輸入, 協議對各環節設置了硬性上限, 超限請求會被直接拒絕:

- program JAR: 最大 128 MiB; classpath: 最多 32 個 JAR, 單個最大 64 MiB, 總量最大 128 MiB.
- 規則檔案: keep 最多 16 個, consumer 最多 32 個; 單檔案最大 256 KiB, 規則總量最大 2 MiB, 單行最大 16 KiB.
- 輸入包整體最大 260 MiB; 歸檔展開: 單 JAR 最多 20000 個 entry, 總計最多 60000 個, 解壓總量最大 512 MiB; class 資料單個最大 8 MiB, 總量最大 256 MiB.
- 輸出包最大 256 MiB: DEX ZIP 最大 192 MiB, mapping 最大 32 MiB, seeds 與 usage 各最大 16 MiB, retrace 元資料最大 256 KiB.
- 並發: 同一時刻只處理一個編譯工作階段, 其餘請求收到可重試的 BUSY; 逾時預設 120 秒, 上限 300 秒.
- 診斷資料最多 64 KiB, 且經過路徑脫敏.

#### 注意事項

- minApi 是編譯參數而非裝置執行聲明; 產物無法在低於其 minApi 的裝置上載入.
- R8 版本被固定 (目前 R8 8.13.17); 快取標識包含編譯器與執行時指紋, 升級編譯器不會誤用舊快取.
- 取消會立即阻止結果發佈, 但 R8 內部的 CPU 計算可能在隔離進程中繼續至本次編譯返回; 工作階段槽在清理完成前保持 BUSY.
- 同一輸入在同一編譯器版本下可重現 (發佈 Gate 驗證過同環境位元組級一致), 但不承諾跨 R8 版本的位元組級一致.
- 主程式構建橫幅中顯示的 AGP 自帶 R8 版本屬於 APK 打包工具鏈, 與本插件的編譯器版本無關.

******

### 開發路線圖

******

開發按可核驗的階段 (Gate) 推進: G1 契約凍結, G2 提供者實現, G3 主程式整合, G4 相容性語料, G5 本地簽名發佈, G6 裝置驗收, G7 ART/JNI/Retrace 閉環與 G8 私有遠端發佈均已完成, 每個階段都留有綁定 SHA-256 的可複核證據. 後續計劃 (公開發佈, retrace 能力開放, 文件與體驗, 引擎升級維護) 及各條目的完成定義見:

- [查看可勾選的 ROADMAP.md](https://github.com/SuperMonster003/AutoJs6-Plugin-R8-Compiler/blob/master/ROADMAP.md)

******

### 版本歷史

******

# v0.2.0

###### 2026/09/11

* `優化` 建置階段阻止意外引入原生相依套件, 並輸出 JSON 校驗報告

# v0.1.0-provider-dev-private.1 (local.5)

###### 2026/08/25

* `提示` 目前版本. 透過私有 GitHub prerelease 發佈 (含簽名 APK, 兩個凍結契約 AAR, 發佈清單與 SHA256SUMS 共 5 項資產, 全部經重新下載與逐位元組核驗), 尚未公開發佈
* `提示` 安裝後預設不生效, 需在 AutoJs6 開發者選項中手動啟用; 詳細步驟見 README 的 "安裝及使用" 章節
* `新增` 內置經位元組核驗的 Android 36 平台庫作為 R8 編譯庫, 不再依賴裝置 boot classpath; 修復部分裝置上 boot JAR 為資源殼導致的編譯失敗
* `新增` 新增有界且路徑脫敏的 R8 診斷資訊收集, 覆蓋提供者啟動與引擎匯入失敗情況
* `優化` 在 API 25/28/37 (含 16 KiB 頁大小模擬器) 的 ART 上完成優化產物執行驗證: 反射, 動態類別名稱, 序列化, 腳本入口, 移除誘餌檢查, arm64/x86/x86_64 JNI 呼叫, 以及 mapping 雜湊核驗後的 R8 Retrace 堆疊還原

# v0.1.0-provider-dev (local.4)

###### 2026/08/25

* `修復` 以零位元組的公開 `Os.read`/`Os.write` 內核探針替換 `/proc/self/fdinfo` 存取模式檢查, 解決部分裝置 (如 Sony API 28) 的 procfs 存取限制; 繼續保留 `Os.fstat` 別名拒絕
* `優化` 在 1 部真機與 2 部模擬器 (API 25/28) 上完成跨 APK Binder/PFD 裝置驗收: 快樂路徑, 生命週期, 惡意輸入與進程死亡共 9/9 案例通過

##### 更多版本

* [CHANGELOG-zh-Hant-HK.md](https://github.com/SuperMonster003/AutoJs6-Plugin-R8-Compiler/blob/master/app/src/main/assets/doc/CHANGELOG-zh-Hant-HK.md)

******

### 構建

******

```powershell
.\gradlew.bat :app:assembleDebug
```

發佈構建:

```powershell
.\gradlew.bat :app:assembleRelease
```

構建要求 JDK 17 或更高 (建議 21) 與 Android SDK Platform 36: 構建腳本會按位元組核驗 `platforms/android-36/android.jar` 並將其內置為編譯庫資產, 核驗失敗即終止構建. 目前 minSdk 為 24, targetSdk 為 36.

協議 ABI 由儲存庫內凍結的 0.1.0 契約 AAR 提供 (位於 `plugin-api/r8-compiler-api/releases/0.1.0/`), 應用程式消費的是這些 AAR 位元組而非其原始碼工程:

```text
protocol-wire-api-0.1.0.aar
r8-compiler-api-0.1.0.aar
```

編譯器透過 Maven 引入固定版本 R8 8.13.17. 正式發佈使用 `scripts/` 目錄下的發佈與驗證腳本 (append-only 本地發佈目錄, 雙快照可重現構建與逐 Gate 核驗); 日常偵錯直接使用上方 Gradle 命令即可.

******

### 授權條款

******

專案原始碼使用 MPL-2.0. R8 和其他第三方組件繼續適用各自的授權條款.

******

### 資源佈局

******

```text
.readme/lang_*.json
.changelog/lang_*.json
.python/generate_markdown.py
app/src/main/assets/doc/CHANGELOG-*.md
```

`.python/generate_markdown.py` 從 JSON 來源產生全部 10 種語言的 README 與 CHANGELOG (含儲存庫根目錄的 README.md 與 CHANGELOG.md); 修改文件請編輯 JSON 來源而非產生的 Markdown.

如需在不修改工作區的情況下驗證全部已產生 Markdown 與來源檔案一致，請執行:

```powershell
python .python/generate_markdown.py --check
```

******

### 連結

******

- AutoJs6 文件: https://docs.autojs6.com
- R8 專案: https://r8.googlesource.com/r8
- DEX Compiler 插件 (D8): https://github.com/SuperMonster003/AutoJs6-Plugin-DEX-Compiler
- 私有發佈頁 (需要存取權限): https://github.com/SuperMonster003/AutoJs6-Plugin-R8-Compiler/releases/tag/v0.1.0-provider-dev-private.1


[16 KB page alignment and build verification](https://github.com/SuperMonster003/AutoJs6-Plugin-R8-Compiler/blob/master/docs/16kb.md)
