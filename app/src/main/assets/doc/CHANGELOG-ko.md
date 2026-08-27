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

# v0.1.0-provider-dev (local.2)

###### 2026/08/25

* `수정` 게시 스크립트의 PowerShell 모듈 자동 로드 해시 의존성을 제거하여, 환경과 무관한 재현 가능 서명 흐름을 보장

# v0.1.0-provider-dev (local.1)

###### 2026/08/25

* `힌트` 첫 로컬 서명 릴리스 (bootstrap 세대); 오프라인 스냅샷 두 개가 바이트 단위로 일치하는 재현 빌드로 append-only 로컬 릴리스 디렉터리를 확립
* `기능` AutoJs6용 명시적 R8 컴파일러 플러그인: 스크립트가 `runtime.loadJarWithR8()`로 완전한 release 컴파일 (shrinking + optimization + obfuscation)을 요청
* `기능` 한 번의 컴파일로 다섯 산출물 반환: DEX ZIP, mapping, seeds, usage, retrace 메타데이터. 각각 SHA-256에 바인딩되며 호스트가 독립적으로 재검증
* `기능` 무폴백 의미론: 모든 실패는 R8 오류로 끝나며 결코 조용히 D8/dx로 전환되지 않음; 호스트는 전용 R8 캐시 도메인 `autojs6:r8-compiler:v1`을 사용
* `기능` 컴파일은 플러그인 자체 `:r8` 프로세스의 사설 샌드박스에서 실행되며, 같은 서명의 AutoJs6 호출자만 수용 (`org.autojs.permission.PLUGIN` 권한으로 보호), 네트워크와 저장소 권한 없음
* `기능` 엄격한 입력 검증: 경로 없는 정규 입력 번들, 위험 지시어를 fail-closed로 거부하는 엄격 UTF-8 규칙, 아카이브·클래스 데이터·출력 각 단계의 강제 상한
* `기능` 컴파일러 minApi 24~36 전체 커버: 리플렉션, 런타임 합성 이름, JNI, 직렬화, 제거 미끼 검증을 포함한 26셀 Java/Kotlin 실 R8 호환성 코퍼스
* `기능` 순수 JVM 구현; universal APK 하나로 모든 기기 아키텍처 지원
* `의존성` Google R8 8.13.17 내장 (Maven `com.android.tools:r8`)

# v0.1.0 (contract)

###### 2026/08/14

* `힌트` 프로토콜 계약 동결로, 앱이나 런타임 동작은 포함하지 않습니다; 이 항목은 인터페이스 경계의 확립을 기록합니다
* `기능` 독립 R8 컴파일 프로토콜 1.0 동결: API 네임스페이스 `org.autojs.plugin.r8compiler.api`, 발견 action `org.autojs.plugin.R8_COMPILER`, 엔진 식별자 `r8-compiler`
* `기능` 정규 입력/산출물 스트림 번들 형식, AIDL 기술자 3종, Java 가시 JVM ABI를 동결; 0.1.0 계약 AAR (protocol-wire-api와 r8-compiler-api)를 append-only로 게시
