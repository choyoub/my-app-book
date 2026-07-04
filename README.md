# 두루마리

개인 소설/문서 폴더를 등록해서 읽는 Android 전자책 리더 앱입니다. 폴더 단위로 문서를 스캔하고, 읽던 위치와 책갈피를 저장해 이어 읽을 수 있습니다.

## 앱 정보

- 앱 이름: 두루마리
- 패키지명: `com.netice.myapp.durumari`
- 현재 버전: `0.1.2` (`versionCode` 3)
- 최소 지원 버전: Android 8.0, API 26
- 타깃 SDK: API 36
- 개발 언어: Kotlin
- UI 방식: Android View 기반 네이티브 UI

## 주요 기능

- 폴더 등록: Android 문서 접근 권한으로 로컬 폴더를 등록합니다.
- 문서 목록: 등록된 폴더의 문서를 목록에서 검색하고 정렬합니다.
- 열람록: 읽은 문서의 마지막 페이지와 진행률을 저장합니다.
- 책갈피: 현재 페이지를 책갈피로 저장하고 목록에서 바로 이동합니다.
- 뷰어: 터치, 스와이프, 볼륨키로 페이지를 넘길 수 있습니다.
- 페이지 이동: 페이지 번호 입력과 슬라이더로 원하는 위치로 이동합니다.
- 인코딩 선택: UTF-8, EUC-KR, CP949, UTF-16 LE/BE를 선택할 수 있습니다.
- 읽기 설정: 글꼴, 글자 크기, 줄 간격, 자간, 굵기, 여백을 조정합니다.
- 테마: 한지, 화이트, 다크, 칠판 테마를 지원합니다.
- 피드백: 페이지 넘김 효과, 진동, 효과음을 설정할 수 있습니다.

## 지원 문서

폴더 스캔 대상:

- `.txt`
- `.epub`
- `.zip`
- `.gz`

ZIP 내부에서는 `.txt`, `.epub`, `.gz` 항목을 읽습니다. EPUB은 내부 HTML/XHTML 본문을 텍스트로 추출해 표시합니다.

## 화면 구성

- `목록`: 현재 폴더의 문서를 보여주며 제목, 날짜, 상태 기준으로 정렬합니다.
- `열람록`: 읽은 문서를 보여주며 제목, 날짜, 진행률 기준으로 정렬합니다.
- `책갈피`: 저장한 책갈피를 보여주며 제목, 날짜, 페이지 기준으로 정렬합니다.

각 화면의 검색/정렬 영역은 고정되어 있고, 실제 목록 영역만 스크롤됩니다.

## 개발 빌드

디버그 APK는 프로젝트 루트에서 아래 명령으로 생성합니다.

```powershell
.\gradlew.bat assembleDebug
```

출력 위치:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## 릴리즈 빌드

릴리즈 APK는 프로젝트 루트에서 아래 스크립트로 생성합니다.

```powershell
.\build-release.ps1
```

이 스크립트는 `app/build.gradle.kts`의 `versionName`을 읽어서 APK 파일명을 만들고, Gradle 릴리즈 빌드 결과물을 `apk` 폴더로 복사합니다.

예시 출력:

```text
apk/durumari-v0.1.2-release.apk
```

`.\gradlew.bat assembleRelease`를 직접 실행하면 Gradle 기본 산출물인 `app/build/outputs/apk/release/app-release.apk`만 생성됩니다. 배포용으로 보관할 파일은 `build-release.ps1`로 생성된 `apk/durumari-v<versionName>-release.apk`를 사용합니다.

현재 릴리즈 빌드는 `debug` 서명 설정을 사용하며, 난독화와 리소스 축소는 꺼져 있습니다.
