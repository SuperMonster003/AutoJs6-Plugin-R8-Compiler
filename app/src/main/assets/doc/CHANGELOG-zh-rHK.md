******

### 版本歷史

******

# v0.2.2

###### 2026/09/19

* `修復` AGP 9.1 構建時的 SDK XML v4 解析警告及 JVM 單元測試組裝任務誤觸發 APK 原生程式庫對齊檢查的問題 (共用構建外掛 1.8.3)
* `優化` 將 compileSdk 與 targetSdk 提升到 37 (Android 17), 插件行為不受新目標版本影響

# v0.2.1

###### 2026/09/13

* `優化` 統一多語言資源, 明確插件啟用契約並驗證發佈產物

# v0.2.0

###### 2026/09/13

* `優化` 建置階段阻止意外引入原生相依套件, 並輸出 JSON 校驗報告
* `優化` 統一多語言資源, 明確插件啟用契約並驗證發佈產物

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

# v0.1.0-provider-dev (local.3)

###### 2026/08/25

* `修復` 為 API 24 至 28 固定 core-library/NIO desugaring (desugar_jdk_libs_nio 2.1.5), 修復低版本裝置缺失 Java 11 核心庫能力導致的執行失敗

# v0.1.0-provider-dev (local.2)

###### 2026/08/25

* `修復` 移除發佈腳本對 PowerShell 模組自動載入雜湊的依賴, 保證與發佈環境無關的可重現簽名流程

# v0.1.0-provider-dev (local.1)

###### 2026/08/25

* `提示` 首個本地簽名發佈 (bootstrap 代次); 雙離線快照位元組級一致的可重現構建, append-only 本地發佈目錄自此建立
* `新增` 作為 AutoJs6 的顯式 R8 編譯插件: 腳本透過 `runtime.loadJarWithR8()` 請求完整 release 編譯 (shrinking + optimization + obfuscation)
* `新增` 一次編譯傳回五件產物: DEX ZIP, mapping, seeds, usage 與 retrace 元資料, 逐項綁定 SHA-256 並由主程式獨立複驗
* `新增` 無回退語義: 任何失敗都以 R8 錯誤結束, 絕不靜默改用 D8/dx; 主程式側使用獨立的 R8 快取域 `autojs6:r8-compiler:v1`
* `新增` 編譯執行於插件獨立 `:r8` 進程的私人沙箱中, 僅接受同簽名 AutoJs6 呼叫 (受 `org.autojs.permission.PLUGIN` 權限保護), 無網絡與儲存權限
* `新增` 嚴格核驗輸入: 規範化無路徑輸入包, 規則嚴格 UTF-8 且危險指令 fail-closed, 歸檔/類別資料/輸出各環節均有硬性上限
* `新增` 編譯參數 minApi 24 至 36 全覆蓋: 26 格 Java/Kotlin 真實 R8 相容語料, 含反射, 動態類別名稱, JNI, 序列化與移除誘餌驗證
* `新增` 純 JVM 實現, 單個 universal APK 覆蓋所有裝置架構
* `依賴` 內置 Google R8 8.13.17 (Maven `com.android.tools:r8`)

# v0.1.0 (contract)

###### 2026/08/14

* `提示` 協議契約凍結, 不含應用程式與執行時行為; 本條目記錄介面邊界的建立
* `新增` 凍結獨立 R8 編譯協議 1.0: API 命名空間 `org.autojs.plugin.r8compiler.api`, 發現 action `org.autojs.plugin.R8_COMPILER`, 引擎標識 `r8-compiler`
* `新增` 凍結規範化輸入/產物串流包格式, 三份 AIDL 描述符與 Java 可見 JVM ABI; 以 append-only 方式發佈 0.1.0 契約 AAR (protocol-wire-api 與 r8-compiler-api)
