# 001 — Spring Boot 초기 프로젝트와 상태 확인 API

상태: 기본 골격과 상태 확인 API 초안 작성 완료. 테스트 작성·실행 및 HTTP 동작 확인은 미실행이다. 사용자가 `/fh-task 001`을 실행하면 기존 파일을 검토하고 아래 남은 테스트·검증 작업을 진행한다. 프로젝트를 새로 덮어쓰지 않는다.

## 목표

Java 21과 Spring Boot로 서버를 실행하고 `GET /api/health`에 HTTP 200과 `{"status":"UP"}`을 반환한다.

## 구현 범위

- `backend/`에 Gradle Wrapper를 포함한 Java 프로젝트를 만든다.
- Gradle Groovy DSL인 `build.gradle`, `settings.gradle`을 사용한다.
- Spring Boot와 Gradle의 Java 21 호환 버전은 생성 시 공식 정보를 확인하고 고정한다.
- Spring MVC 상태 확인 API와 의미 있는 HTTP 동작 테스트를 만든다.
- 테스트는 URL, 상태 코드, JSON 응답을 실제 Spring 요청 처리 경로에서 확인한다.
- DB·로그인·웹훅·프론트엔드는 다음 작업이다. 이 단계는 DB 없이 기동한다.
- `backend/README.md`에 실행·테스트 방법을 적는다.
- `docs/progress/001-health-api.md`에 변경 이유, 검증, 설명과 직접 해볼 과제를 적는다.

## 완료 조건

1. Java 21로 `./gradlew --no-daemon clean assemble` 성공.
2. `./gradlew --no-daemon test --rerun-tasks` 성공, 실행된 JUnit 테스트가 1개 이상이고 실패·오류가 0개.
3. 만들어진 실행 JAR로 로컬 서버 기동, `/api/health`의 200·JSON `status=UP` 응답 확인.
4. 작업 범위 밖의 파일 변경 없음. 검증 뒤 코드가 바뀌면 다시 검증.

## 작업 방식

- 먼저 요구사항과 현재 파일을 확인하고 짧은 계획을 설명한다.
- Spring의 요청 처리와 의존성 주입을 이해할 수 있게 최소 구성으로 만든다.
- 최종 보고 전 `flowhook_verify`를 호출한다. 최초 검증 후 수정·재검증은 최대 2회다.
- 검증 실패를 숨기거나 테스트·작업 정의·검증기를 바꾸지 않는다.
- 사용자가 중단하거나 질문으로 방향을 바꾸면 새 구현보다 그 요청을 우선한다.

## 이해 확인

설명할 것: main 클래스와 controller의 역할, `/api/health`까지의 요청 흐름, 테스트가 확인하는 범위.

직접 해볼 과제: 응답에 애플리케이션 이름 필드를 추가하고 관련 테스트를 수정해보기.
