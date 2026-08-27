<!--suppress HtmlDeprecatedAttribute, HttpUrlsUsage -->

<div align="center">
  <h1>AutoJs6-Plugin-R8-Compiler</h1>

  <p>AutoJs6 向けの独立した R8 コンパイラプラグイン. 隔離プロセス内で完全な release プロファイル (shrink + optimize + obfuscate) によりスクリプトの JAR を DEX へコンパイルします</p>

  <p><sub>現在の段階: プライベートプレリリース (ソースコードとインストーラーはまだ非公開です)</sub></p>
</div>

******

### 言語

******

README.md は現在以下の言語で利用できます:

- [简体中文 [zh-Hans]](https://github.com/SuperMonster003/AutoJs6-Plugin-R8-Compiler/blob/master/.readme/README-zh-Hans.md)
- [繁體中文 (香港) [zh-Hant-HK]](https://github.com/SuperMonster003/AutoJs6-Plugin-R8-Compiler/blob/master/.readme/README-zh-Hant-HK.md)
- [繁體中文 (台灣) [zh-Hant-TW]](https://github.com/SuperMonster003/AutoJs6-Plugin-R8-Compiler/blob/master/.readme/README-zh-Hant-TW.md)
- [English [en]](https://github.com/SuperMonster003/AutoJs6-Plugin-R8-Compiler/blob/master/.readme/README-en.md)
- [Français [fr]](https://github.com/SuperMonster003/AutoJs6-Plugin-R8-Compiler/blob/master/.readme/README-fr.md)
- [Español [es]](https://github.com/SuperMonster003/AutoJs6-Plugin-R8-Compiler/blob/master/.readme/README-es.md)
- 日本語 [ja] # 現在
- [한국어 [ko]](https://github.com/SuperMonster003/AutoJs6-Plugin-R8-Compiler/blob/master/.readme/README-ko.md)
- [Русский [ru]](https://github.com/SuperMonster003/AutoJs6-Plugin-R8-Compiler/blob/master/.readme/README-ru.md)
- [العربية [ar]](https://github.com/SuperMonster003/AutoJs6-Plugin-R8-Compiler/blob/master/.readme/README-ar.md)

******

### 概要

******

AutoJs6 のスクリプトは `runtime.loadJar()` で JAR を読み込み, 中の Java クラスを呼び出せます. この既定経路は D8 による単純な JAR から DEX へのコンパイルで, 縮小や難読化は行いません. 成果物に完全な release 処理 -- 不要コードの除去 (shrinking), バイトコード最適化 (optimization), 識別子の難読化 (obfuscation) -- を施したい場合に必要になるのが R8 です.

本プラグインは別途インストールするアプリで, 固定バージョンの Google R8 コンパイラを自身の隔離プロセス内で実行し, AutoJs6 に明示的な完全 release コンパイルサービスを提供します. スクリプトは専用の `runtime.loadJarWithR8()` 入口からコンパイルを開始し, 必ず keep ルールを併せて渡します; AutoJs6 は DEX や mapping など 5 つの成果物を受け取り, それぞれを検証してキャッシュし, 検証済みの DEX だけを読み込みます.

内蔵経路との最大の違いは, R8 入口が明示的でフォールバックを持たない点です. コンパイルが失敗しても黙って D8/dx に切り替わることはなく, エラーはそのままスクリプトへ返されます. これにより「読み込みが成功したなら, 成果物は必ず完全な R8 処理を経ている」という単純な不変条件が保証されます.

本プラグインが適する場面: JAR 成果物の縮小や難読化が必要な場合; 後でスタックトレースを復元するための mapping ファイルが必要な場合; あるいはコンパイルの意味論を完全に決定的にしたい場合 (完全な R8 か, 明確な失敗のどちらか).

******

### 動作の仕組み

******

プラグインを有効化すると, 一度の `runtime.loadJarWithR8()` 呼び出しはおおよそ以下の手順をたどります:

```text
1. script     calls runtime.loadJarWithR8(program, keepRules[, classpath[, consumerRules, ordinals]])
2. AutoJs6    snapshots program, classpath and rule files into one canonical path-free input bundle
3. plugin     re-verifies the bundle, then runs pinned R8 (full release profile) in its private ":r8" process
4. plugin     streams back one bundle with five artifacts: DEX ZIP, mapping, seeds, usage, retrace metadata
5. AutoJs6    re-validates every artifact, commits an R8-only cache generation, and loads the verified DEX ZIP
*  no fallback: any failure terminates the call as an R8 error; D8/dx is never used silently
```

プラグインが担当するのは手順 3 と 4, つまり「コンパイル」そのものだけです; 入力のスナップショットと固定, 成果物の検証, キャッシュ, 最終的なクラス読み込みは常に AutoJs6 が行います. 両者は Binder 経由でファイル記述子のみをやり取りし, ファイルパスが通信路を渡ることはなく, プラグインはスクリプトディレクトリを読めません. 結果は入力内容とコンパイルパラメータをキーに専用の R8 キャッシュドメインへ保存され, 同じ入力の再読み込みは直接キャッシュにヒットします; 破損したキャッシュは自動的に破棄されて再コンパイルされ, キャッシュから開かれる成果物は毎回先にハッシュを再検証します.

******

### 機能

******

- 完全な release コンパイル: shrinking (不要コード除去), optimization (最適化), obfuscation (難読化) は常にすべて有効で, 固定された R8 8.13.17 が実行します.
- 明示的な意味論と無サイレントフォールバック: 本プラグインを使うのは `runtime.loadJarWithR8()` だけです; あらゆる失敗は R8 エラーとして終わり, 黙って D8/dx に切り替わることはありません. `runtime.loadJar()` と `runtime.loadJarWithClasspath()` は完全に無変更です.
- 5 つの成果物を一往復で返却: DEX ZIP, mapping (難読化マップ), seeds (保持項目一覧), usage (除去項目一覧), retrace メタデータ. それぞれ SHA-256 に紐付き, ホストが独立して再検証します.
- コンパイルはプラグイン自身の `:r8` プロセスと私有ワークスペースで実行され, AutoJs6 から隔離されます; 入力は R8 実行前に完全に再検証されます.
- 順序付きコンパイル時 classpath と, 所属する classpath JAR に紐付く consumer ルールをサポート; keep ルールは明示的に渡す必要があり, アーカイブ内部からルールを暗黙に発見することはありません.
- バイト検証済みの Android 36 プラットフォームライブラリをコンパイルライブラリとして同梱し, 欠損している可能性のあるデバイスの boot classpath JAR に依存しません; コンパイラの minApi 24 から 36 を実際の R8 コーパスで全数検証済みです.
- Android 7.0 (API 24) 以上をサポート; API 25/28/37 の実機とエミュレーター (16 KiB ページサイズのデバイスを含む) で実際の ART 実行, JNI 呼び出し, Retrace 復元を検証済みです.
- 同一署名の AutoJs6 とのみ通信し (`org.autojs.permission.PLUGIN` 権限で保護), ネットワークとストレージの権限を要求しません.

******

### DEX Compiler プラグインとの関係

******

AutoJs6 エコシステムには独立した 2 つのコンパイラプラグインがあります. 両者は補完関係にあり, 重複せず, 同時にインストールできます:

- [AutoJs6-Plugin-DEX-Compiler](https://github.com/SuperMonster003/AutoJs6-Plugin-DEX-Compiler) は既定経路 `runtime.loadJar()` / `runtime.loadJarWithClasspath()` を担当します: より新しい D8 による単純コンパイルで, 縮小も難読化もしません; プラグインが失敗するとホストは自動的に内蔵コンパイラへフォールバックします.
- 本プラグイン (R8) は明示経路 `runtime.loadJarWithR8()` のみを担当します: keep ルール必須の完全 release コンパイルで, 失敗は失敗のまま, フォールバックはありません.

両者は完全に独立したプロトコル (`dex-compiler-api` と `r8-compiler-api`), サービス action, 開発者オプション項目, キャッシュドメインを使い, 互いに依存も認識もしません. DEX プロトコルの `RELEASE` は D8 の release コンパイルモードにすぎず, R8 とは無関係です. どちらか一方のインストールやアンインストールがもう一方へ影響することはありません.

******

### インストールと使い方

******

プラグインの有効化は 3 段階です: R8 統合を含むペアの AutoJs6 をインストールし, 本プラグインの APK をインストールし, AutoJs6 の開発者オプションで本プラグインを手動選択します. あらかじめ知っておくべき点が 2 つあります:

- プラグインは既定で不活性です. インストールしただけでは AutoJs6 の挙動は何も変わりません; 有効化されるまで `runtime.loadJarWithR8()` は別のコンパイラを使うのではなく, 単に fail-closed で失敗します.
- いつでも元に戻せます. 開発者オプションで入口を無効化すれば以前の状態に戻り, 何もアンインストールする必要はありません.

#### 前提条件

- `runtime.loadJarWithR8()` 統合を含む AutoJs6 (代表的な検証済みビルド: AutoJs6 6.8.0 (build 5276)); 古いホストにはこの入口も対応する開発者オプション項目もありません.
- ホストとプラグインは同じ信頼できる提供元に由来し, 同一署名である必要があります; 署名が一致しない場合プラグインは選択できません -- ペアでリリース (またはビルド) されたインストールパッケージを使ってください.
- 本プラグインは現在プライベートプレリリース段階で, インストーラーはプライベート GitHub プレリリースまたはローカルビルドから取得します. ペアのホストと併せて入手してください.
- 自分でビルドする場合は, 下記の固定パッケージ名とサービスコンポーネントを変更しないでください.

関連する識別子は次のとおりです:

```text
host package: org.autojs.autojs6
plugin package: io.github.supermonster003.autojs6.plugin.r8compiler
paired host: AutoJs6 6.8.0 (build 5276)
exact component: io.github.supermonster003.autojs6.plugin.r8compiler/io.github.supermonster003.autojs6.plugin.r8compiler.R8CompilerService
```

#### インストールと有効化

1. R8 統合を含むペアの AutoJs6 をインストールまたはアップグレードします.
2. 本プラグインの APK をインストールします.
3. AutoJs6 を開き, 設定 > アプリと開発者について に進み, アプリアイコンを長押しして開発者オプションに入ります.
4. R8 compiler > Explicit full-release R8 provider に進みます.
5. 本プラグインのサービスコンポーネント (上記の exact component) を選択して確定します.

重ねて強調します: インストールだけではプラグインは有効になりません. AutoJs6 が発見済み provider を自動選択することもなく, 選択が完了するまで `runtime.loadJarWithR8()` は常に失敗で終わります.

#### 有効化の確認

開発者オプションのページに戻り, Explicit full-release R8 provider の概要に `runtime.loadJarWithR8` が本プラグインのコンポーネントを使用する旨が表示されていれば設定成功です; 概要に disabled や fails closed の文言がある場合, 入口はまだ無効のままです.

プラグインが一覧に現れない場合は順に確認してください: ホストが R8 統合を含むペアビルドであること; ホストとプラグインのパッケージ名が上記の識別子と一致していること; プラグインアプリがシステムに無効化されていないこと; 両者の署名が一致していること.

DEX プラグインと異なり, この入口には「実際に誰がコンパイルしたか」の曖昧さがありません: `runtime.loadJarWithR8()` が成功を返した時点で, 成果物は必ず完全な R8 処理 (今回のコンパイル, または検証済みのキャッシュ世代) を経ています.

#### スクリプト例

JVM の `.class` ファイルを含む JAR と keep ルールファイルをスクリプトディレクトリに置き, オーバーロード群のいずれかを呼び出します. keep ルールは省略できません: R8 はルールで保持されないすべてのシンボルを除去・難読化するため, ルールなしのコンパイルはほぼ確実に元の名前でアクセスできないクラスを生み出します.

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

program JAR がその内部に存在しないコンパイル時クラス (API スタブなど) を参照する場合は, 3 引数オーバーロードで順序付き classpath を渡します:

```javascript
runtime.loadJarWithR8(
    files.path("./lib/program.jar"),
    [files.path("./lib/keep-rules.pro")],
    [files.path("./lib/compile-api-stubs.jar")],
);
```

5 引数オーバーロードはさらに consumer ルールファイルとその所属序数を受け取ります; 各 consumer ルールファイルは序数によって対応する classpath JAR に紐付きます:

```javascript
runtime.loadJarWithR8(program, keepRuleFiles, classpathJars, consumerRuleFiles, ownerOrdinals);
```

要点:

- classpath JAR はコンパイル時の参照解決のみに使われ, 出力に含まれることも自動的に読み込まれることもありません. 並び順には意味があり, キャッシュ識別子の一部になります.
- keep と consumer のルールは厳密な UTF-8 テキストでなければなりません; ファイルシステムアクセス, include, 入出力リダイレクト, 辞書などの危険な指令を含むルールは即座に拒否されます (fail-closed).
- mapping などの成果物は現在ホストが検証のうえ私有キャッシュに保存しており, スクリプトから直接取得できるエクスポート入口はまだありません (ROADMAP を参照).
- コンパイルはセキュリティ審査ではありません. 信頼できる JAR だけを読み込んでください.

#### コンパイル失敗時の挙動

この入口の失敗の意味論は意図的に単純です: 完全な R8 成果物を得るか, エラーになるかのどちらかで, 中間状態はありません.

- provider 未選択, 引数不正, provider 利用不可または多忙 (BUSY, 同時に許可されるコンパイルセッションは 1 つのみ), コンパイル失敗, タイムアウト (既定 120 秒, 上限 300 秒), 成果物検証失敗, キャッシュまたは読み込み失敗 -- これらはすべて R8 エラーとしてスクリプト呼び出しを終わらせ, D8/dx へ切り替わることは決してありません.
- 自発的なキャンセル (スクリプトの停止など) は呼び出しを即座に終了させ, 同様にフォールバックを起こしません.
- キャッシュヒットは意味論を変えません: キャッシュ済み成果物は書き込み時に完全検証されており, 読み出しのたびに再度ハッシュ検証されます.

「失敗したら通常コンパイルへ戻す」動作が必要なら, スクリプト側でエラーを捕捉して明示的に `runtime.loadJar()` を呼んでください; ホストがその判断を代行することはありません.

#### トラブルシューティングと報告

よくある原因は順に: 開発者オプションで provider が未選択; ルールの拒否 (禁止指令や UTF-8 以外のエンコーディング); リソース上限を超える入力; クラス名の誤記または keep ルールで保持されていない (R8 が保持されないシンボルを難読化・除去済み). 問題を報告する際は, できるだけ以下を添えてください:

- AutoJs6 のバージョンとビルド, プラグインのバージョン, 開発者オプション概要の完全なコンポーネント名.
- デバイスの型番, Android バージョン (API), CPU アーキテクチャ (ABI).
- 問題を引き起こした program JAR とすべてのルールファイル (またはそのバイト数と SHA-256), スクリプトの完全な例外情報と再現手順.

ADB を使える場合, 以下のコマンドで関連ログを収集できます (`<serial>` はデバイスのシリアル番号に置き換えてください; 共有前にログ内の私的パスや機微情報を削除してください):

```powershell
adb -s <serial> shell dumpsys package org.autojs.autojs6
adb -s <serial> shell dumpsys package io.github.supermonster003.autojs6.plugin.r8compiler
adb -s <serial> logcat -d -v threadtime AndroidClassLoader:D AndroidRuntime:E *:S
```

#### 無効化, ロールバック, アンインストール

- 一時的な無効化: 開発者オプションの Explicit full-release R8 provider で入口を無効化して確定します. 以後 `runtime.loadJarWithR8()` は fail-closed の失敗に戻り, 他には何も変わりません; 保持されたコンポーネント選択はいつでも再有効化できます.
- プラグインのアンインストール: まず入口を無効化し, AutoJs6 を停止してからプラグイン APK をアンインストールします. アンインストールでプラグイン自身のデータと一時ファイルはすべて削除されます.
- 再インストールや更新の後, ホストはコンポーネントの身元 (UID と署名を含む) を再検証するため, 開発者オプションで選択を再確認する必要があります.

******

### よくある質問

******

**Q: なぜ keep ルールが必須なのですか?**

A: R8 の full-release プロファイルは明示的に保持されないすべてのシンボルを除去・難読化します. スクリプトは `Packages.xxx` によるリフレクションでクラスへアクセスするため, R8 はどのシンボルを残すべきか推論できません; そこでプロトコルは keep ルールを明示必須とし,「コンパイルは成功したのにクラスが見つからない」というサイレントな罠を避けています.

**Q: 内蔵コンパイラや DEX プラグインより速いですか?**

A: いいえ -- 通常はより遅いです. R8 は全プログラム解析 (shrink/optimize/obfuscate) を行うため, 単純な D8 コンパイルより本質的に高コストです; その代わりに得られるのは, より小さくリバースエンジニアリングされにくい成果物と mapping ファイルです. 結果はキャッシュされるため, 同じ入力の 2 回目以降の読み込みは高速です.

**Q: DEX Compiler プラグインの代わりになりますか (またはその逆は)?**

A: なりません. 両者は異なるスクリプト入口を担当し, プロトコルとキャッシュも独立しています.「DEX Compiler プラグインとの関係」を参照してください.

**Q: なぜ失敗時に D8 へ自動フォールバックしないのですか?**

A: 意図的な設計です. R8 入口の呼び出しは「完全な release 成果物が必要だ」という宣言です; サイレントなフォールバックは, 知らないうちに難読化されていない成果物を渡すことになります. フォールバックの意味論が必要なら, スクリプトでエラーを捕捉して自分で `runtime.loadJar()` を呼んでください.

**Q: スタックトレースはどうやって復元しますか?**

A: すべてのコンパイルで mapping と retrace メタデータが生成され, ホストが検証してキャッシュします; 現バージョンには mapping を取得するスクリプト用・UI 用の入口がまだなく, retrace RPC はロードマップにあります. 成果物を自分でビルドする場合は, 保存しておいた mapping と R8 の retrace ツールでスタックを復元できます.

**Q: プラグインはネットワークにアクセスしたり私のファイルを読んだりしますか?**

A: しません. ネットワークとストレージの権限を持たず, コンパイル入力は AutoJs6 から渡されるファイル記述子のみから読み取り, 通信路上でファイルパスを見ることはなく, 一時ファイルは自身の私有ディレクトリ内に限定されます.

**Q: ランチャーにアイコンや画面がないのはなぜですか?**

A: プラグインにはユーザーインターフェースがなく, AutoJs6 がバインドするコンパイルサービスだけを含みます. これは正常です.

******

### 対応範囲の境界

******

誤解を避けるため, 以下は本プラグインの範囲外であることを明示します:

- 担当するのは `runtime.loadJarWithR8()` のみです; `runtime.loadJar()` と `runtime.loadJarWithClasspath()` の挙動を変えることはなく, それらの入口が本プラグインを暗黙に選択することもありません.
- D8 モードも debug コンパイルもなく, shrink/optimize/obfuscate の個別スイッチもありません: プロファイルは FULL_RELEASE に固定されています.
- JAR 内部からのルールの暗黙発見はありません (META-INF 配下の proguard ファイルなど); keep と consumer のルールはリクエストと共に明示的に渡す必要があります.
- ファイルシステムアクセス, include, 入出力リダイレクト, mapping インポート, print, 辞書, グローバル profile 制御の指令を含むルールは拒否されます (プロトコル 1.0 の範囲外, fail-closed).
- retrace RPC はありません; retrace メタデータは mapping の来歴情報のみで, mapping のスクリプト向けエクスポートもまだありません (いずれも ROADMAP に記載).
- 依存関係のダウンロードや解決は行いません (Maven/Gradle 統合なし). ネットワークコンパイルもありません.
- `.aar`, コンパイル済み `.dex`, `defineClass()` の動的バイトコードは扱いません; これらは常に AutoJs6 の内蔵経路を通ります.
- 現時点ではプライベート配布のみです: ソースコードとインストーラーはプライベート GitHub リポジトリにあり, 公開リリースはロードマップ上の独立した項目です.

******

### 技術リファレンス

******

以下は正確な境界を必要とする開発者や統合者向けの内容です; プラグインを使うだけなら通常は読む必要はありません.

#### 入力と出力

プロトコル 1.0 は読み取り専用記述子で 1 つの正準入力バンドルを受け取り, 書き込み専用記述子で 1 つの正準成果物バンドルを返します; 全経路でファイルパスは一切現れず, 入力全体と各成果物は SHA-256 に紐付きます:

```text
input: 1 program JAR + ordered classpath JARs + explicit keep rules + optional consumer rules
output: DEX_ZIP + MAPPING_TEXT + SEEDS_TEXT + USAGE_TEXT + RETRACE_METADATA
compiler: R8 8.13.17
profile: FULL_RELEASE (shrink + optimize + obfuscate)
```

#### プラグイン発見識別子

ホストは以下の識別子でプラグインを発見して呼び出します:

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

プラグインは R8 8.13.17, プロトコル 1.0, 固定の FULL_RELEASE プロファイル, minApi 24 から 36, multi-dex 出力, 5 成果物の能力セットを宣言します. コンパイルライブラリは同梱のバイト検証済み Android 36 プラットフォームライブラリです; runtime library フィンガープリントは引き続き観測されたデバイスの boot classpath ファイルに紐付きます.

プラグインは native library を含まず, 純粋な JVM のユニバーサル APK 1 つで全デバイス ABI をカバーします; 検証済みの JNI 呼び出しはコンパイルされた JAR 内の native メソッドを対象とし, プラグイン自身のものではありません.

#### セキュリティモデル

プラグインはネットワークとストレージの権限を要求しません. コンパイルサービスは `org.autojs.permission.PLUGIN` 権限で保護され, 専用の `:r8` プロセスで動作します; 毎回の呼び出しでパッケージ名, 呼び出し元 UID, 双方の署名を双方向に検証し, 同一署名の AutoJs6 ホストのみを受け入れます. 入出力はファイル記述子としてのみ受け渡しされ, アクセスモードが不正な記述子や入出力が同一エンドポイントを指す記述子は拒否されます. 一時ファイルはプラグインの私有ワークスペース内に留まり, 古いワークスペースは自動回収されます. ホスト側も各成果物を独立に再検証し, クラスローダーは再ハッシュ済みの読み取り専用 DEX コピーのみを受け入れます.

#### リソース上限

悪意ある入力や異常入力から守るため, プロトコルは各段階に強制上限を設けています. 上限を超えるリクエストは即座に拒否されます:

- program JAR: 最大 128 MiB; classpath: 最大 32 個の JAR, 各 64 MiB, 合計 128 MiB.
- ルールファイル: keep 最大 16 個, consumer 最大 32 個; 1 ファイル最大 256 KiB, ルール合計最大 2 MiB, 1 行最大 16 KiB.
- 入力バンドル全体は最大 260 MiB; アーカイブ展開: 1 JAR あたり最大 20000 エントリ, 合計最大 60000 エントリ, 展開後最大 512 MiB; class データは 1 クラス最大 8 MiB, 合計最大 256 MiB.
- 出力バンドルは最大 256 MiB: DEX ZIP 最大 192 MiB, mapping 最大 32 MiB, seeds と usage は各最大 16 MiB, retrace メタデータ最大 256 KiB.
- 並行性: 同時に処理されるコンパイルセッションは 1 つのみで, 他のリクエストは再試行可能な BUSY を受け取ります. タイムアウトは既定 120 秒, 上限 300 秒.
- 診断データは最大 64 KiB で, パスは秘匿化されます.

#### 注意事項

- minApi はコンパイルパラメータであり, デバイス実行の宣言ではありません; 成果物はその minApi 未満のデバイスでは読み込めません.
- R8 のバージョンは固定されています (現在 R8 8.13.17); キャッシュ識別子はコンパイラとランタイムのフィンガープリントを含むため, コンパイラ更新後に古い結果が誤用されることはありません.
- キャンセルは結果の公開を即座に阻止しますが, R8 内部の CPU 処理は隔離プロセス内で当該コンパイルの終了まで続く可能性があります; セッションスロットはクリーンアップ完了まで BUSY のままです.
- 同一入力は同一コンパイラバージョンでバイト単位に再現します (リリース Gate で同一環境の再現性を検証済み). ただし R8 バージョンをまたぐバイト単位の一致は保証されません.
- ホストのビルドバナーに表示される AGP 同梱の R8 バージョンは APK パッケージングツールチェーンのもので, 本プラグインのコンパイラバージョンとは無関係です.

******

### 開発ロードマップ

******

開発は検証可能な Gate 単位で進みます: G1 契約凍結, G2 provider 実装, G3 ホスト統合, G4 互換性コーパス, G5 ローカル署名リリース, G6 デバイス受け入れ, G7 ART/JNI/Retrace クロージャ, G8 プライベートリモートリリースがすべて完了しており, 各段階に SHA-256 で紐付いた検証可能な証跡があります. 今後の計画 (公開リリース, retrace 機能の開放, ドキュメントと UX, エンジン更新の保守) と各項目の完了定義は次にあります:

- [チェック可能な ROADMAP.md を開く](https://github.com/SuperMonster003/AutoJs6-Plugin-R8-Compiler/blob/master/ROADMAP.md)

******

### リリース履歴

******

# v0.1.0-provider-dev-private.1 (local.5)

###### 2026/08/25

* `ヒント` 現行バージョン. プライベート GitHub プレリリースとして公開 (署名済み APK, 凍結済み契約 AAR 2 点, リリースマニフェスト, SHA256SUMS の計 5 アセット, すべて再ダウンロードしてバイト単位で検証済み); 一般公開はまだ行われていません
* `ヒント` インストール後は既定で不活性です. AutoJs6 の開発者オプションで手動有効化が必要です -- README の「インストールと使い方」を参照してください
* `機能` バイト検証済みの Android 36 プラットフォームライブラリを R8 コンパイルライブラリとして同梱し, デバイスの boot classpath への依存を排除; boot JAR がリソースだけの殻であるデバイスでのコンパイル失敗を修正
* `機能` provider 起動やエンジンインポートの失敗まで対象とする, 上限付きでパス秘匿化された R8 診断収集を追加
* `改善` API 25/28/37 (16 KiB ページサイズのエミュレーターを含む) の ART 上で最適化済み出力を検証: リフレクション, 実行時合成クラス名, シリアライズ, スクリプト向け入口, 除去デコイの確認, arm64/x86/x86_64 の JNI 呼び出し, mapping ハッシュ検証後の R8 Retrace によるスタック復元

# v0.1.0-provider-dev (local.4)

###### 2026/08/25

* `修正` `/proc/self/fdinfo` によるアクセスモード検査をゼロバイトの公開 `Os.read`/`Os.write` カーネルプローブへ置き換え, 一部デバイス (Sony API 28 など) の procfs 制限を解消; `Os.fstat` によるエイリアス拒否は維持
* `改善` 実機 1 台とエミュレーター 2 台 (API 25/28) で APK 間 Binder/PFD デバイス受け入れを完了: ハッピーパス, ライフサイクル, 敵対的入力, プロセス死 -- 9/9 テスト合格

# v0.1.0-provider-dev (local.3)

###### 2026/08/25

* `修正` API 24 から 28 向けに core-library/NIO desugaring (desugar_jdk_libs_nio 2.1.5) を固定し, 旧デバイスで Java 11 コアライブラリ機能の欠如による実行失敗を修正

##### その他のリリース

* [CHANGELOG-ja.md](https://github.com/SuperMonster003/AutoJs6-Plugin-R8-Compiler/blob/master/app/src/main/assets/doc/CHANGELOG-ja.md)

******

### ビルド

******

```powershell
.\gradlew.bat :app:assembleDebug
```

リリースビルド:

```powershell
.\gradlew.bat :app:assembleRelease
```

ビルドには JDK 17 以降 (21 推奨) と Android SDK Platform 36 が必要です: ビルドスクリプトが `platforms/android-36/android.jar` をバイト検証してコンパイルライブラリ資産として同梱し, 不一致があれば中断します. 現在の minSdk は 24, targetSdk は 36 です.

プロトコル ABI はリポジトリ内の凍結済み 0.1.0 契約 AAR (`plugin-api/r8-compiler-api/releases/0.1.0/` 配下) が提供します; アプリはソースプロジェクトではなくこれらの AAR バイトを消費します:

```text
protocol-wire-api-0.1.0.aar
r8-compiler-api-0.1.0.aar
```

コンパイラは Maven から固定された R8 8.13.17 として取得します. 正式リリースは `scripts/` 配下の公開・検証スクリプト (append-only なローカルリリースディレクトリ, 2 スナップショットの再現ビルド, Gate ごとの検証) を使います; 日常のデバッグには上記の Gradle コマンドで十分です.

******

### ライセンス

******

プロジェクトのソースコードは MPL-2.0 でライセンスされます. R8 とその他のサードパーティコンポーネントには, それぞれのライセンスが引き続き適用されます.

******

### リソース構成

******

```text
.readme/lang_*.json
.changelog/lang_*.json
.python/generate_markdown.py
app/src/main/assets/doc/CHANGELOG-*.md
```

`.python/generate_markdown.py` が JSON ソースから全 10 言語の README と CHANGELOG (リポジトリルートの README.md と CHANGELOG.md を含む) を生成します; ドキュメントを変更するときは生成された Markdown ではなく JSON ソースを編集してください.

******

### リンク

******

- AutoJs6 ドキュメント: https://docs.autojs6.com
- R8 プロジェクト: https://r8.googlesource.com/r8
- DEX Compiler プラグイン (D8): https://github.com/SuperMonster003/AutoJs6-Plugin-DEX-Compiler
- プライベートリリースページ (アクセス権が必要): https://github.com/SuperMonster003/AutoJs6-Plugin-R8-Compiler/releases/tag/v0.1.0-provider-dev-private.1
