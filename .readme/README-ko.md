<!--suppress HtmlDeprecatedAttribute, HttpUrlsUsage -->

<div align="center">
  <h1>AutoJs6-Plugin-R8-Compiler</h1>

  <p>AutoJs6용 독립 R8 컴파일러 플러그인. 격리된 프로세스에서 완전한 release 프로필 (shrink + optimize + obfuscate)로 스크립트 JAR를 DEX로 컴파일합니다</p>

  <p><sub>현재 단계: 비공개 프리릴리스 (소스 코드와 설치 파일은 아직 공개되지 않았습니다)</sub></p>
</div>

******

### 언어

******

README.md는 현재 다음 언어로 제공됩니다:

- [简体中文 [zh-Hans]](https://github.com/SuperMonster003/AutoJs6-Plugin-R8-Compiler/blob/master/.readme/README-zh-Hans.md)
- [繁體中文 (香港) [zh-Hant-HK]](https://github.com/SuperMonster003/AutoJs6-Plugin-R8-Compiler/blob/master/.readme/README-zh-Hant-HK.md)
- [繁體中文 (台灣) [zh-Hant-TW]](https://github.com/SuperMonster003/AutoJs6-Plugin-R8-Compiler/blob/master/.readme/README-zh-Hant-TW.md)
- [English [en]](https://github.com/SuperMonster003/AutoJs6-Plugin-R8-Compiler/blob/master/.readme/README-en.md)
- [Français [fr]](https://github.com/SuperMonster003/AutoJs6-Plugin-R8-Compiler/blob/master/.readme/README-fr.md)
- [Español [es]](https://github.com/SuperMonster003/AutoJs6-Plugin-R8-Compiler/blob/master/.readme/README-es.md)
- [日本語 [ja]](https://github.com/SuperMonster003/AutoJs6-Plugin-R8-Compiler/blob/master/.readme/README-ja.md)
- 한국어 [ko] # 현재
- [Русский [ru]](https://github.com/SuperMonster003/AutoJs6-Plugin-R8-Compiler/blob/master/.readme/README-ru.md)
- [العربية [ar]](https://github.com/SuperMonster003/AutoJs6-Plugin-R8-Compiler/blob/master/.readme/README-ar.md)

******

### 소개

******

AutoJs6 스크립트는 `runtime.loadJar()`로 JAR를 로드하고 그 안의 Java 클래스를 호출할 수 있습니다. 이 기본 경로는 D8로 JAR를 DEX로 단순 컴파일하며 축소나 난독화는 하지 않습니다. 결과물이 완전한 release 처리 -- 미사용 코드 제거 (shrinking), 바이트코드 최적화 (optimization), 식별자 난독화 (obfuscation) -- 를 거치길 원한다면 R8이 필요합니다.

이 플러그인은 별도로 설치하는 앱으로, 고정된 버전의 Google R8 컴파일러를 자체 격리 프로세스에서 실행하여 AutoJs6에 명시적인 완전 release 컴파일 서비스를 제공합니다. 스크립트는 전용 진입점 `runtime.loadJarWithR8()`로 컴파일을 시작하며 반드시 keep 규칙을 함께 제공해야 합니다; AutoJs6는 DEX와 mapping 등 다섯 가지 산출물을 돌려받아 각각 검증하고 캐시한 뒤, 검증된 DEX만 로드합니다.

내장 경로와의 가장 큰 차이: R8 진입점은 명시적이며 폴백이 없습니다. 컴파일이 실패해도 조용히 D8/dx로 전환되지 않고, 오류가 그대로 스크립트에 전달됩니다. 이로써 "로드에 성공했다면 산출물은 반드시 완전한 R8 처리를 거쳤다"는 단순한 불변식이 보장됩니다.

이 플러그인이 적합한 경우: JAR 산출물의 크기를 줄이거나 난독화해야 할 때; 나중에 스택 트레이스를 복원하기 위한 mapping 파일이 필요할 때; 또는 컴파일 의미론이 완전히 결정적이길 원할 때 (완전한 R8이거나, 명확한 실패이거나).

******

### 동작 원리

******

플러그인이 활성화된 상태에서 한 번의 `runtime.loadJarWithR8()` 호출은 대략 다음 단계를 거칩니다:

```text
1. script     calls runtime.loadJarWithR8(program, keepRules[, classpath[, consumerRules, ordinals]])
2. AutoJs6    snapshots program, classpath and rule files into one canonical path-free input bundle
3. plugin     re-verifies the bundle, then runs pinned R8 (full release profile) in its private ":r8" process
4. plugin     streams back one bundle with five artifacts: DEX ZIP, mapping, seeds, usage, retrace metadata
5. AutoJs6    re-validates every artifact, commits an R8-only cache generation, and loads the verified DEX ZIP
*  no fallback: any failure terminates the call as an R8 error; D8/dx is never used silently
```

플러그인은 3단계와 4단계, 즉 "컴파일" 자체만 담당합니다; 입력의 스냅샷과 고정, 산출물 검증, 캐시, 최종 클래스 로딩은 항상 AutoJs6가 수행합니다. 양측은 Binder로 파일 디스크립터만 주고받으며, 파일 경로는 결코 전송되지 않고, 플러그인은 스크립트 디렉터리를 읽을 수 없습니다. 결과는 입력 내용과 컴파일 매개변수를 기준으로 전용 R8 캐시 도메인에 캐시됩니다; 같은 입력의 반복 로드는 캐시에 바로 적중하고, 손상된 캐시 데이터는 자동으로 제거되어 다시 컴파일되며, 캐시에서 여는 모든 산출물은 먼저 해시를 재검증합니다.

******

### 기능

******

- 완전한 release 컴파일: shrinking (미사용 코드 제거), optimization (최적화), obfuscation (난독화)이 항상 모두 활성화되며, 고정된 R8 8.13.17가 수행합니다.
- 명시적 의미론과 무폴백: 이 플러그인을 사용하는 것은 `runtime.loadJarWithR8()`뿐입니다; 모든 실패는 R8 오류로 끝나며 결코 조용히 D8/dx로 전환되지 않습니다. `runtime.loadJar()`와 `runtime.loadJarWithClasspath()`는 전혀 변경되지 않습니다.
- 다섯 가지 산출물을 한 번에 반환: DEX ZIP, mapping (난독화 맵), seeds (보존 항목 목록), usage (제거 항목 목록), retrace 메타데이터. 각각 SHA-256에 바인딩되며 호스트가 독립적으로 재검증합니다.
- 컴파일은 플러그인 자체의 `:r8` 프로세스와 사설 작업 공간에서 실행되어 AutoJs6와 격리됩니다; 입력은 R8 실행 전에 완전히 재검증됩니다.
- 순서 있는 컴파일 타임 classpath와, 소유 classpath JAR에 바인딩되는 consumer 규칙을 지원합니다; keep 규칙은 명시적으로 제공해야 하며, 아카이브 내부에서 규칙을 암묵적으로 발견하지 않습니다.
- 바이트 검증된 Android 36 플랫폼 라이브러리를 컴파일 라이브러리로 내장하여, 훼손되었을 수 있는 기기 boot classpath JAR에 의존하지 않습니다; 컴파일러 minApi 24~36 전체를 실제 R8 코퍼스로 검증했습니다.
- Android 7.0 (API 24) 이상 지원; API 25/28/37 실기기와 에뮬레이터 (16 KiB 페이지 크기 기기 포함)에서 실제 ART 실행, JNI 호출, Retrace 복원을 검증했습니다.
- 같은 서명의 AutoJs6와만 통신하며 (`org.autojs.permission.PLUGIN` 권한으로 보호), 네트워크나 저장소 권한을 요구하지 않습니다.

******

### DEX Compiler 플러그인과의 관계

******

AutoJs6 생태계에는 두 개의 독립적인 컴파일러 플러그인이 있습니다. 서로 보완적이고 겹치지 않으며 나란히 설치할 수 있습니다:

- [AutoJs6-Plugin-DEX-Compiler](https://github.com/SuperMonster003/AutoJs6-Plugin-DEX-Compiler)는 기본 경로 `runtime.loadJar()` / `runtime.loadJarWithClasspath()`를 담당합니다: 더 새로운 D8로 단순 컴파일하며 축소나 난독화는 하지 않습니다; 플러그인이 실패하면 호스트가 자동으로 내장 컴파일러로 폴백합니다.
- 이 플러그인 (R8)은 명시적 경로 `runtime.loadJarWithR8()`만 담당합니다: keep 규칙이 필수인 완전 release 컴파일이며, 실패는 실패로 남고 폴백이 없습니다.

둘은 완전히 독립적인 프로토콜 (`dex-compiler-api` vs `r8-compiler-api`), 서비스 action, 개발자 옵션 항목, 캐시 도메인을 사용하며 서로 의존하지도, 인지하지도 않습니다. DEX 프로토콜의 `RELEASE`는 D8의 release 컴파일 모드일 뿐 R8과 무관합니다. 어느 한쪽의 설치나 제거가 다른 쪽에 영향을 주지 않습니다.

******

### 설치 및 사용

******

플러그인 활성화는 세 단계입니다: R8 통합이 포함된 페어 AutoJs6를 설치하고, 이 플러그인 APK를 설치한 뒤, AutoJs6 개발자 옵션에서 플러그인을 수동으로 선택합니다. 미리 알아둘 두 가지:

- 플러그인은 기본적으로 비활성 상태입니다. 설치만으로는 AutoJs6에 아무 변화가 없습니다; 활성화되기 전까지 `runtime.loadJarWithR8()`는 다른 컴파일러를 쓰는 대신 그냥 안전하게 실패합니다 (fail-closed).
- 언제든 되돌릴 수 있습니다. 개발자 옵션에서 진입점을 비활성화하면 이전 상태로 돌아가며, 아무것도 제거할 필요가 없습니다.

#### 사전 조건

- `runtime.loadJarWithR8()` 통합이 포함된 AutoJs6 (대표 검증 빌드: AutoJs6 6.8.0 (build 5276)); 이전 호스트에는 이 진입점도, 해당 개발자 옵션 항목도 없습니다.
- 호스트와 플러그인은 같은 신뢰할 수 있는 출처에서 나와야 하며 서명이 같아야 합니다; 서명이 다르면 플러그인을 선택할 수 없습니다 -- 쌍으로 릴리스 (또는 빌드)된 설치 패키지를 사용하세요.
- 이 플러그인은 현재 비공개 프리릴리스 단계이며, 설치 파일은 비공개 GitHub 프리릴리스나 로컬 빌드에서 얻습니다. 페어 호스트와 함께 받으세요.
- 직접 빌드할 때는 아래의 고정 패키지 이름과 서비스 컴포넌트를 변경하지 마세요.

관련 식별자는 다음과 같습니다:

```text
host package: org.autojs.autojs6
plugin package: io.github.supermonster003.autojs6.plugin.r8compiler
paired host: AutoJs6 6.8.0 (build 5276)
exact component: io.github.supermonster003.autojs6.plugin.r8compiler/io.github.supermonster003.autojs6.plugin.r8compiler.R8CompilerService
```

#### 설치 및 활성화

1. R8 통합이 포함된 페어 AutoJs6를 설치하거나 업그레이드합니다.
2. 이 플러그인 APK를 설치합니다.
3. AutoJs6를 열고 설정 > 앱 및 개발자 정보로 이동한 뒤, 앱 아이콘을 길게 눌러 개발자 옵션에 들어갑니다.
4. R8 compiler > Explicit full-release R8 provider로 이동합니다.
5. 이 플러그인의 서비스 컴포넌트 (위의 exact component)를 선택하고 확인합니다.

다시 강조하면: 설치만으로는 플러그인이 활성화되지 않으며, AutoJs6는 발견된 provider를 자동 선택하지 않습니다; 선택이 완료되기 전까지 `runtime.loadJarWithR8()`는 항상 실패로 끝납니다.

#### 활성화 확인

개발자 옵션 페이지로 돌아가서, Explicit full-release R8 provider의 요약에 `runtime.loadJarWithR8`가 이 플러그인의 컴포넌트를 사용한다고 표시되면 설정이 성공한 것입니다; 요약에 disabled나 fails closed 문구가 있으면 진입점이 아직 꺼져 있는 상태입니다.

플러그인이 목록에 나타나지 않으면 순서대로 확인하세요: 호스트가 R8 통합이 포함된 페어 빌드인지; 호스트와 플러그인의 패키지 이름이 위 식별자와 일치하는지; 플러그인 앱이 시스템에 의해 비활성화되지 않았는지; 양쪽 서명이 일치하는지.

DEX 플러그인과 달리 이 진입점에는 "실제로 누가 컴파일했는가"의 모호함이 없습니다: `runtime.loadJarWithR8()`가 성공적으로 반환되면, 산출물은 반드시 완전한 R8 처리 (이번 호출 또는 이미 검증된 캐시 세대)를 거친 것입니다.

#### 스크립트 예제

JVM `.class` 파일이 담긴 JAR와 keep 규칙 파일을 스크립트 디렉터리에 두고, 진입점 오버로드 중 아무거나 호출하세요. keep 규칙은 선택 사항이 아닙니다: R8은 규칙으로 보존되지 않은 모든 심벌을 제거하고 난독화하므로, 규칙 없는 컴파일은 거의 확실히 원래 이름으로 접근할 수 없는 클래스를 만들어 냅니다.

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

program JAR가 자신 안에 없는 컴파일 타임 클래스 (예: API 스텁)를 참조한다면, 3인자 오버로드에 순서 있는 classpath를 전달하세요:

```javascript
runtime.loadJarWithR8(
    files.path("./lib/program.jar"),
    [files.path("./lib/keep-rules.pro")],
    [files.path("./lib/compile-api-stubs.jar")],
);
```

5인자 오버로드는 추가로 consumer 규칙 파일과 그 소유 서수를 받습니다; 각 consumer 규칙 파일은 서수를 통해 해당 classpath JAR에 바인딩됩니다:

```javascript
runtime.loadJarWithR8(program, keepRuleFiles, classpathJars, consumerRuleFiles, ownerOrdinals);
```

핵심 사항:

- classpath JAR는 컴파일 타임 참조 해석에만 쓰이며, 출력에 포함되지도 자동 로드되지도 않습니다. 순서가 의미를 가지며 캐시 정체성의 일부입니다.
- keep과 consumer 규칙은 엄격한 UTF-8 텍스트여야 합니다; 파일 시스템 접근, include, 입출력 리디렉션, 사전 등 위험한 지시어를 포함한 규칙은 즉시 거부됩니다 (fail-closed).
- mapping 등 산출물은 현재 호스트가 검증 후 사설 캐시에 저장하며, 스크립트에서 직접 가져올 수 있는 내보내기 진입점은 아직 없습니다 (ROADMAP 참조).
- 컴파일은 보안 검토가 아닙니다. 신뢰하는 JAR만 로드하세요.

#### keep 규칙 가이드

`Packages` 접근, 리플렉션, JNI, 직렬화 및 공개 API 표면에 대한 최소 레시피는 [실전 keep 규칙 가이드](https://github.com/SuperMonster003/AutoJs6-Plugin-R8-Compiler/blob/master/docs/keep-rules-guide.md)를 참조하세요.

#### 실패하면 어떻게 되나요

이 진입점의 실패 의미론은 의도적으로 단순합니다: 완전한 R8 산출물을 얻거나, 오류가 나거나 -- 중간 상태는 없습니다.

- provider 미선택, 잘못된 인자, provider 사용 불가 또는 사용 중 (BUSY, 동시에 하나의 컴파일 세션만 허용), 컴파일 실패, 시간 초과 (기본 120초, 최대 300초), 산출물 검증 실패, 캐시 또는 로드 실패 -- 모두 스크립트 호출을 R8 오류로 끝내며, 결코 D8/dx로 전환하지 않습니다.
- 사용자가 직접 취소하면 (예: 스크립트 중지) 호출이 즉시 종료되며 마찬가지로 폴백이 발생하지 않습니다.
- 캐시 적중은 의미론을 바꾸지 않습니다: 캐시된 산출물은 기록 시점에 완전히 검증되었고, 읽을 때마다 다시 해시 검증됩니다.

"실패 시 일반 컴파일로 되돌아가기"를 원한다면 스크립트에서 오류를 잡아 명시적으로 `runtime.loadJar()`를 호출하세요; 호스트가 그 결정을 대신하지 않습니다.

#### 문제 해결 및 제보

흔한 원인은 순서대로: 개발자 옵션에서 provider 미선택; 규칙 거부 (금지된 지시어나 UTF-8이 아닌 인코딩); 리소스 상한을 초과한 입력; 클래스 이름 오타 또는 keep 규칙에 포함되지 않음 (R8이 보존되지 않은 심벌을 난독화하거나 제거함). 문제를 제보할 때 가능한 한 다음을 첨부해 주세요:

- AutoJs6 버전과 빌드, 플러그인 버전, 개발자 옵션 요약의 전체 컴포넌트 이름.
- 기기 모델, Android 버전 (API), CPU 아키텍처 (ABI).
- 문제를 일으킨 program JAR와 모든 규칙 파일 (또는 바이트 크기와 SHA-256), 전체 스크립트 예외 정보와 재현 단계.

ADB를 사용할 수 있다면, 다음 명령으로 관련 로그를 수집할 수 있습니다 (`<serial>`을 기기 시리얼 번호로 바꾸세요; 공유 전에 로그의 사적 경로와 민감한 내용을 제거하세요):

```powershell
adb -s <serial> shell dumpsys package org.autojs.autojs6
adb -s <serial> shell dumpsys package io.github.supermonster003.autojs6.plugin.r8compiler
adb -s <serial> logcat -d -v threadtime AndroidClassLoader:D AndroidRuntime:E *:S
```

#### 비활성화, 롤백, 제거

- 일시적 비활성화: 개발자 옵션의 Explicit full-release R8 provider에서 진입점을 끄고 확인합니다. 이후 `runtime.loadJarWithR8()`는 다시 안전한 실패로 돌아가고, 다른 것은 변하지 않으며, 보존된 컴포넌트 선택은 언제든 다시 활성화할 수 있습니다.
- 플러그인 제거: 먼저 진입점을 비활성화한 뒤 AutoJs6를 중지하고 플러그인 APK를 제거합니다. 제거하면 플러그인 자체의 모든 데이터와 임시 파일이 삭제됩니다.
- 재설치나 업데이트 후에는 호스트가 컴포넌트 신원 (UID와 서명 포함)을 재검증하므로, 개발자 옵션에서 선택을 다시 확인해야 합니다.

******

### 자주 묻는 질문

******

**Q: 왜 keep 규칙이 필수인가요?**

A: R8의 full-release 프로필은 명시적으로 보존되지 않은 모든 심벌을 제거하고 난독화합니다. 스크립트는 `Packages.xxx`를 통한 리플렉션으로 클래스에 접근하므로 R8은 어떤 심벌이 살아남아야 하는지 추론할 수 없습니다; 그래서 프로토콜은 keep 규칙을 명시적 필수로 만들어 "컴파일은 성공했는데 클래스를 찾을 수 없는" 조용한 함정을 피합니다.

**Q: 내장 컴파일러나 DEX 플러그인보다 빠른가요?**

A: 아니요 -- 보통 더 느립니다. R8은 전체 프로그램 분석 (shrink/optimize/obfuscate)을 수행하므로 단순 D8 컴파일보다 본질적으로 비쌉니다; 그 대가로 더 작고 역공학이 어려운 산출물과 mapping 파일을 얻습니다. 결과는 캐시되므로 같은 입력의 이후 로드는 빠릅니다.

**Q: DEX Compiler 플러그인을 대체할 수 있나요 (또는 그 반대)?**

A: 아니요. 둘은 서로 다른 스크립트 진입점을 담당하며 프로토콜과 캐시가 독립적입니다; "DEX Compiler 플러그인과의 관계"를 참조하세요.

**Q: 왜 실패 시 D8로 자동 폴백하지 않나요?**

A: 의도된 설계입니다. R8 진입점 호출은 "완전한 release 산출물이 필요하다"는 선언입니다; 조용한 폴백은 모르는 사이에 난독화되지 않은 산출물을 건네게 됩니다. 폴백 의미론이 필요하면 스크립트에서 오류를 잡아 직접 `runtime.loadJar()`를 호출하세요.

**Q: 스택 트레이스는 어떻게 복원하나요?**

A: 모든 컴파일은 mapping과 retrace 메타데이터를 생성하며 호스트가 검증하고 캐시합니다; 현재 버전에는 mapping을 가져올 스크립트/UI 진입점이 아직 없고, retrace RPC는 로드맵에 있습니다. 산출물을 직접 빌드할 때는 보관해 둔 mapping과 R8 retrace 도구로 스택을 복원할 수 있습니다.

**Q: 플러그인이 네트워크에 접근하거나 내 파일을 읽나요?**

A: 아니요. 네트워크와 저장소 권한이 없으며, 컴파일 입력은 AutoJs6가 건네주는 파일 디스크립터에서만 읽고, 전송 경로에서 파일 경로를 보지 못하며, 임시 파일은 자체 사설 디렉터리 안에만 둡니다.

**Q: 런처 화면에서는 무엇을 할 수 있나요?**

A: 플러그인의 읽기 전용 화면은 플러그인 버전, 고정된 R8 버전, 서비스 컴포넌트 가용 상태와 내장 변경 기록을 보여 줍니다. 이 화면은 provider를 활성화하지 않으며, 선택과 활성화는 계속 AutoJs6 개발자 옵션에서만 수행합니다.

******

### 범위의 경계

******

오해를 피하기 위해, 다음은 이 플러그인의 범위에 명시적으로 포함되지 않습니다:

- `runtime.loadJarWithR8()`만 담당합니다; `runtime.loadJar()`와 `runtime.loadJarWithClasspath()`의 동작을 바꾸지 않으며, 그 진입점들이 이 플러그인을 암묵적으로 선택할 수도 없습니다.
- D8 모드도, debug 컴파일도, shrink/optimize/obfuscate 개별 스위치도 없습니다: 프로필은 FULL_RELEASE로 고정입니다.
- JAR 내부의 규칙 (예: META-INF 아래 proguard 파일)을 암묵적으로 발견하지 않습니다; keep과 consumer 규칙은 요청과 함께 명시적으로 제공해야 합니다.
- 파일 시스템 접근, include, 입출력 리디렉션, mapping 가져오기, print, 사전, 전역 profile 제어 지시어를 포함한 규칙은 거부됩니다 (프로토콜 1.0 범위 밖, fail-closed).
- retrace RPC가 없습니다; retrace 메타데이터는 mapping 출처 정보일 뿐이며, mapping의 스크립트 내보내기도 아직 없습니다 (둘 다 ROADMAP에 있음).
- 의존성 다운로드나 해석을 하지 않습니다 (Maven/Gradle 통합 없음). 네트워크 컴파일도 없습니다.
- `.aar`, 미리 컴파일된 `.dex`, `defineClass()` 동적 바이트코드는 처리하지 않습니다; 이들은 항상 AutoJs6의 내장 경로를 사용합니다.
- 현재는 비공개 배포뿐입니다: 소스 코드와 설치 파일은 비공개 GitHub 저장소에 있으며, 공개 릴리스는 로드맵의 별도 항목입니다.

******

### 기술 레퍼런스

******

다음 내용은 정확한 경계가 필요한 개발자와 통합 담당자를 위한 것입니다; 플러그인을 쓰기만 한다면 대개 건너뛰어도 됩니다.

#### 입력과 출력

프로토콜 1.0은 읽기 전용 디스크립터로 하나의 정규 입력 번들을 받고, 쓰기 전용 디스크립터로 하나의 정규 산출물 번들을 반환합니다; 전 과정에서 파일 경로는 전송되지 않으며, 입력 전체와 각 산출물이 SHA-256에 바인딩됩니다:

```text
input: 1 program JAR + ordered classpath JARs + explicit keep rules + optional consumer rules
output: DEX_ZIP + MAPPING_TEXT + SEEDS_TEXT + USAGE_TEXT + RETRACE_METADATA
compiler: R8 8.13.17
profile: FULL_RELEASE (shrink + optimize + obfuscate)
```

#### 플러그인 발견 식별자

호스트는 다음 식별자로 플러그인을 발견하고 호출합니다:

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

플러그인은 R8 8.13.17, 프로토콜 1.0, 고정 FULL_RELEASE 프로필, minApi 24~36, multi-dex 출력, 다섯 산출물 능력 집합을 선언합니다. 컴파일 라이브러리는 내장된 바이트 검증 Android 36 플랫폼 라이브러리입니다; runtime library 지문은 여전히 관측된 기기 boot classpath 파일에 바인딩됩니다.

플러그인은 native library를 포함하지 않으며 순수 JVM universal APK 하나로 모든 기기 ABI를 지원합니다; 검증된 JNI 호출은 컴파일된 JAR 안의 native 메서드를 대상으로 하며 플러그인 자체의 것이 아닙니다.

#### 보안 모델

플러그인은 네트워크와 저장소 권한을 요구하지 않습니다. 컴파일 서비스는 `org.autojs.permission.PLUGIN` 권한으로 보호되며 전용 `:r8` 프로세스에서 실행됩니다; 매 호출마다 패키지 이름, 호출자 UID, 양측 서명을 양방향으로 검증하며 같은 서명의 AutoJs6 호스트만 받아들입니다. 입출력은 오직 파일 디스크립터로만 전달되며, 접근 모드가 잘못되었거나 입력/출력이 같은 엔드포인트를 가리키는 디스크립터는 거부됩니다. 임시 파일은 플러그인의 사설 작업 공간 안에만 있고, 오래된 작업 공간은 자동으로 회수됩니다. 호스트 측도 각 산출물을 독립적으로 재검증하며, 클래스 로더는 다시 해시 검증된 읽기 전용 DEX 사본만 받아들입니다.

#### 리소스 상한

악의적이거나 비정상적인 입력을 방어하기 위해 프로토콜은 모든 단계에 강제 상한을 둡니다; 상한을 넘는 요청은 즉시 거부됩니다:

- program JAR: 최대 128 MiB; classpath: 최대 32개 JAR, 각 64 MiB, 총 128 MiB.
- 규칙 파일: keep 최대 16개, consumer 최대 32개; 파일당 최대 256 KiB, 규칙 총량 최대 2 MiB, 한 줄 최대 16 KiB.
- 입력 번들 전체 최대 260 MiB; 아카이브 확장: JAR당 최대 20000개 항목, 총 60000개, 압축 해제 최대 512 MiB; class 데이터는 클래스당 최대 8 MiB, 총 256 MiB.
- 출력 번들 최대 256 MiB: DEX ZIP 최대 192 MiB, mapping 최대 32 MiB, seeds와 usage 각 최대 16 MiB, retrace 메타데이터 최대 256 KiB.
- 동시성: 한 번에 정확히 하나의 컴파일 세션만 처리하며, 다른 요청은 재시도 가능한 BUSY를 받습니다. 시간 제한은 기본 120초, 상한 300초.
- 진단 데이터는 최대 64 KiB이며 경로가 마스킹됩니다.

#### 주의 사항

- minApi는 컴파일 매개변수이지 기기 실행 선언이 아닙니다; 산출물은 자신의 minApi보다 낮은 기기에서 로드할 수 없습니다.
- R8 버전은 고정되어 있습니다 (현재 R8 8.13.17); 캐시 정체성에 컴파일러와 런타임 지문이 포함되므로 컴파일러 업그레이드가 오래된 결과를 잘못 재사용하는 일은 없습니다.
- 취소는 결과 게시를 즉시 막지만, R8 내부의 CPU 작업은 격리 프로세스 안에서 해당 컴파일이 반환될 때까지 계속될 수 있습니다; 세션 슬롯은 정리가 끝날 때까지 BUSY로 유지됩니다.
- 같은 입력은 같은 컴파일러 버전에서 바이트 단위로 재현됩니다 (릴리스 게이트가 동일 환경 재현성을 검증); 다만 R8 버전 간의 바이트 수준 일치는 약속되지 않습니다.
- 호스트 빌드 배너에 표시되는 AGP 번들 R8 버전은 APK 패키징 도구 체인의 것으로, 이 플러그인의 컴파일러 버전과 무관합니다.

******

### 개발 로드맵

******

개발은 검증 가능한 게이트 단위로 진행됩니다: G1 계약 동결, G2 provider 구현, G3 호스트 통합, G4 호환성 코퍼스, G5 로컬 서명 릴리스, G6 기기 수용, G7 ART/JNI/Retrace 마무리, G8 비공개 원격 릴리스가 모두 완료되었으며, 각 단계마다 SHA-256에 바인딩된 검토 가능한 증거가 있습니다. 향후 계획 (공개 릴리스, retrace 개방, 문서와 UX, 엔진 업그레이드 유지 보수)과 각 항목의 완료 정의는 다음에 있습니다:

- [체크 가능한 ROADMAP.md 열기](https://github.com/SuperMonster003/AutoJs6-Plugin-R8-Compiler/blob/master/ROADMAP.md)

******

### 릴리스 기록

******

# v0.1.0-provider-dev-private.1 (local.5)

###### 2026/08/25

* `힌트` 현재 버전. 비공개 GitHub 프리릴리스로 게시 (서명된 APK, 동결된 계약 AAR 2개, 릴리스 매니페스트, SHA256SUMS 등 5개 에셋, 전부 다시 다운로드하여 바이트 단위로 검증); 아직 공개 릴리스는 아닙니다
* `힌트` 설치 후 기본적으로 비활성 상태입니다. AutoJs6 개발자 옵션에서 수동으로 활성화해야 합니다 -- README의 "설치 및 사용" 절을 참조하세요
* `기능` 바이트 검증된 Android 36 플랫폼 라이브러리를 R8 컴파일 라이브러리로 내장하여 기기 boot classpath 의존을 제거; boot JAR가 리소스 껍데기뿐인 기기에서의 컴파일 실패를 수정
* `기능` provider 시작과 엔진 가져오기 실패까지 다루는, 상한이 있고 경로가 마스킹된 R8 진단 수집 추가
* `개선` API 25/28/37 (16 KiB 페이지 크기 에뮬레이터 포함)의 ART에서 최적화된 출력 검증: 리플렉션, 런타임 합성 클래스 이름, 직렬화, 스크립트 진입점, 제거 미끼 확인, arm64/x86/x86_64 JNI 호출, mapping 해시 검증 후 R8 Retrace 스택 복원

# v0.1.0-provider-dev (local.4)

###### 2026/08/25

* `수정` `/proc/self/fdinfo` 접근 모드 검사를 0바이트 공개 `Os.read`/`Os.write` 커널 프로브로 교체하여 일부 기기 (예: Sony API 28)의 procfs 제한을 해결; `Os.fstat` 별칭 거부는 유지
* `개선` 실기기 1대와 에뮬레이터 2대 (API 25/28)에서 APK 간 Binder/PFD 기기 수용 완료: 정상 경로, 수명 주기, 악의적 입력, 프로세스 종료 -- 9/9 테스트 통과

# v0.1.0-provider-dev (local.3)

###### 2026/08/25

* `수정` API 24~28용 core-library/NIO desugaring (desugar_jdk_libs_nio 2.1.5)을 고정하여, 구형 기기에서 Java 11 코어 라이브러리 기능 부재로 인한 실행 실패를 수정

##### 추가 릴리스

* [CHANGELOG-ko.md](https://github.com/SuperMonster003/AutoJs6-Plugin-R8-Compiler/blob/master/app/src/main/assets/doc/CHANGELOG-ko.md)

******

### 빌드

******

```powershell
.\gradlew.bat :app:assembleDebug
```

릴리스 빌드:

```powershell
.\gradlew.bat :app:assembleRelease
```

빌드에는 JDK 17 이상 (21 권장)과 Android SDK Platform 36이 필요합니다: 빌드 스크립트가 `platforms/android-36/android.jar`를 바이트 단위로 검증해 컴파일 라이브러리 에셋으로 내장하며, 불일치 시 중단합니다. 현재 minSdk는 24, targetSdk는 36입니다.

프로토콜 ABI는 저장소 안의 동결된 0.1.0 계약 AAR (`plugin-api/r8-compiler-api/releases/0.1.0/` 아래)가 제공합니다; 앱은 소스 프로젝트가 아니라 이 AAR 바이트를 소비합니다:

```text
protocol-wire-api-0.1.0.aar
r8-compiler-api-0.1.0.aar
```

컴파일러는 Maven에서 고정된 R8 8.13.17로 가져옵니다. 공식 릴리스는 `scripts/` 아래의 게시·검증 스크립트 (append-only 로컬 릴리스 디렉터리, 두 스냅샷 재현 빌드, 게이트별 검증)를 사용합니다; 일상적인 디버깅에는 위의 Gradle 명령이면 충분합니다.

******

### 라이선스

******

프로젝트 소스 코드는 MPL-2.0 라이선스를 따릅니다. R8과 기타 서드파티 컴포넌트에는 각자의 라이선스가 계속 적용됩니다.

******

### 리소스 구성

******

```text
.readme/lang_*.json
.changelog/lang_*.json
.python/generate_markdown.py
app/src/main/assets/doc/CHANGELOG-*.md
```

`.python/generate_markdown.py`가 JSON 소스로부터 10개 언어 전체의 README와 CHANGELOG (저장소 루트의 README.md와 CHANGELOG.md 포함)를 생성합니다; 문서를 수정할 때는 생성된 Markdown이 아니라 JSON 소스를 편집하세요.

생성된 모든 Markdown이 소스와 일치하는지 작업 트리를 수정하지 않고 확인하려면 다음을 실행하세요:

```powershell
python .python/generate_markdown.py --check
```

******

### 링크

******

- AutoJs6 문서: https://docs.autojs6.com
- R8 프로젝트: https://r8.googlesource.com/r8
- DEX Compiler 플러그인 (D8): https://github.com/SuperMonster003/AutoJs6-Plugin-DEX-Compiler
- 비공개 릴리스 페이지 (접근 권한 필요): https://github.com/SuperMonster003/AutoJs6-Plugin-R8-Compiler/releases/tag/v0.1.0-provider-dev-private.1
