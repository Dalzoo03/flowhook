# 001 — 기초 세팅 작업 기록

## 요청과 범위

사용자 요청: 학습을 시작할 수 있도록 프로젝트 기본 초안과 설정 설명을 준비한다.
이번 작업은 pi 자동 실행이 아닌 현재 세션에서 수행했다.

## 작성한 내용

- Spring Initializr에서 Java 21 / Boot 4.1.1 / Gradle 프로젝트 골격과 공식 Wrapper를 가져왔다.
- 상태 확인 Controller, YAML 설정, Java 21 실행 스크립트를 작성했다.
- Dockerfile, 선택 실행 가능한 Compose 앱·PostgreSQL 설정, 환경변수 예시를 작성했다.
- 빌드 CI, Git·에디터 규칙, 초보자 설명서를 작성했다.
- 로컬 Git main 브랜치를 초기화했다. 커밋·원격 연결은 하지 않았다.
- 기존 pi 계정·자동 모델 전환 설정은 변경하지 않았다.

## 실제 수행 결과

프로젝트 루트에서 `./scripts/backend.sh assemble` 실행:

```text
compileJava → processResources → classes → resolveMainClassName → bootJar → jar → assemble
BUILD SUCCESSFUL
5 actionable tasks: 5 executed
```

테스트 코드 작성·실행, 서버 HTTP 확인, 컨테이너 실행, GitHub CI 실행, pi 확장 로딩은 미실행이다.
기존 작업 001의 전체 완료 조건을 충족한 것으로 표시하지 않는다. pi PASS 기록도 만들지 않았다.
컨테이너·서버는 실행해두지 않았다.

## 다음 단계

1. [설정 설명서](../GETTING_STARTED.md)의 실습을 직접 해본다.
2. 테스트 작성까지 진행할 때 `/fh-task 001`로 기존 API의 남은 검증 작업을 진행한다.
3. 요청·응답 흐름을 이해한 뒤 endpoint 등록 API를 설계한다.

직접 해볼 과제: 상태 응답에 앱 이름을 추가하고 서버 재시작 후 JSON이 어떻게 바뀌는지 확인하기.
