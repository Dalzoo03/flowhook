# FlowHook 진행 현황

최종 갱신: 2026-11-10. 현재 구현 상태는 이 문서에, 작업별 근거와 다룬 개념은 docs/progress/에 기록한다.

## 완료한 작업

- [x] Java 21 / Spring Boot / Gradle 기본 구성
- [x] 상태 확인 API, 전달 대상 등록·목록 조회 API
- [x] 입력 검증, 프로젝트 1 제한, 전달 대상 10개 제한
- [x] PostgreSQL JDBC 저장·조회
- [x] 등록 트랜잭션과 프로젝트별 DB 잠금
- [x] 별도 PostgreSQL 컨테이너 기반 테스트 16개
- [x] 로컬 JAR에서 실제 DB 데이터 조회 확인
- [x] Docker·Compose 설정 파일, GitHub PostgreSQL 테스트·빌드 CI 파일
- [x] GitHub에서 PostgreSQL 테스트·빌드 CI 실행 성공 확인
- [x] Flyway V1 및 기존 DB baseline 도입, 기존 데이터 보존 확인

## 남은 작업

- [ ] 앱 Docker 이미지 빌드·실행 확인
- [ ] 프로젝트 생성·사용자 인증·인가
- [ ] 이벤트 접수·전달·재시도·복구

현재는 Flyway로 빈 DB의 구조를 생성하고 기존 로컬 DB의 기준 버전을 기록했다.
다음은 기능 명세 F-04를 바탕으로 이벤트 접수 기능을 설계한다. event(발생한 사실), delivery(대상별 전달 작업), attempt(실제 전송 시도)를 구분한다.
이벤트와 대상별 전달 대기를 같은 트랜잭션에 저장하고, 동일 이벤트 재접수를 처리하는 순서로 진행한다. API 키 인증은 아직 없으므로 실습 단계와 최종 명세의 차이를 명시한다. 이번 기록 작업에서 이벤트 기능을 구현하지는 않았다.

## 검증 근거

Flyway 도입 후 `./scripts/backend.sh clean test assemble` 성공. 테스트 16개, 실패·오류·스킵 0개.
패키징한 앱에서 기존 DB의 ID 1을 GET API로 조회했다. 상세 근거는 [DB 연결 기록](progress/003-postgresql.md)에 있다.
GitHub CI는 `test assemble`을 실행한다. Testcontainers가 테스트용 PostgreSQL을 생성하므로 로컬 DB나 GitHub의 DB 비밀번호 설정을 사용하지 않는다. 실행 결과는 Actions 탭에서 확인한다.
Flyway 변경 커밋 `df31b9e`의 [CI 성공 결과](https://github.com/Dalzoo03/flowhook/actions/runs/42765182313)를 확인했다.

## 작업 기록

- [001 기본 골격](progress/001-health-api.md)
- [002 전달 대상 등록·조회](progress/002-endpoints.md)
- [003 PostgreSQL 연결](progress/003-postgresql.md)
- [004 CI·Flyway 도입과 학습 기록](progress/004-ci-flyway.md)

## 변경 이력 확인

- **Commits:** 최초 기준점 이후 CI·Flyway 등 실제 작업 단위로 남긴 변경 이력. 최초 기준점에는 JDBC까지 포함되어 있다.
- **Actions:** 자동 빌드 실행·성공·실패 기록.
- **이 문서:** 기능별 완료 상태. 작업을 끝낼 때 근거와 함께 갱신한다.
- **docs/progress/:** 각 작업의 변경 이유·검증·제한 사항.

이후 작업은 브랜치와 커밋·PR로 기록한다. 로컬 수정은 커밋하고 push하기 전까지 GitHub에 보이지 않는다.

## 제한

프로젝트 1만 지원하고 인증·실제 전송은 없다. 동일 URL 등록은 허용한다.
10개 제한은 API의 잠금 규칙에 의존하므로 수동 INSERT로 우회할 수 있다.
비밀번호 파일 `.env`, 빌드 산출물, pi 실행 기록은 Git 추적에서 제외한다.
