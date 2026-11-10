# 004 — GitHub CI와 Flyway

기록일: 2026-11-10. 이 문서는 이미 수행한 작업과 대화에서 다룬 개념을 정리한다. 개념을 다뤘다는 기록은 사용자 숙련도 검증을 뜻하지 않는다.

## GitHub와 CI

- `bbc4774`: 기존 구현을 최초 기준점으로 저장하고 비공개 GitHub 저장소에 push. 이전 작업의 가상 커밋 이력을 만들지 않았다.
- `676fbb4`: 빌드만 하던 CI에 PostgreSQL 테스트 추가. `test assemble` 실행.
- 테스트 DB는 Testcontainers가 생성하며 로컬 DB·비밀번호와 분리한다.
- CI 실행 성공을 확인했다. [CI 테스트 연결 결과](https://github.com/Dalzoo03/flowhook/actions/runs/42760045203).
- CD는 미구현. 자동 배포 대상도 아직 정하지 않았다.

## Flyway

- `df31b9e`: 기존 schema.sql을 `V1__create_endpoints.sql`로 이동하고 Spring Boot Flyway starter와 PostgreSQL 모듈을 추가했다.
- 빈 DB는 앱 시작 시 V1을 실제 실행한다. 기존 로컬 DB는 구조 확인 후 V1 baseline을 한 번 적용했다.
- 기존 데이터 전체를 적용 전후 비교해 변경이 없음을 확인했다.
- 자동 baseline은 기본 false, clean은 비활성화. 정상 옵션으로 다시 실행해 조회 성공을 확인했다.
- API 테스트 14개와 마이그레이션 테스트 2개, 총 16개 통과. `clean test assemble` 성공.
- [Flyway 변경의 GitHub CI](https://github.com/Dalzoo03/flowhook/actions/runs/42765182313) 성공.
- V2 변경은 아직 없다. 자세한 원리·설정은 [Flyway 안내](../FLYWAY.md).

## 대화에서 다룬 내용

- Docker 포트 연결: 로컬 5433 → 컨테이너 5432.
- `.env`의 비밀번호는 PostgreSQL 최초 초기화에 사용된다. 이후 파일만 바꿔도 기존 계정 비밀번호가 바뀌지는 않는다.
- PostgreSQL의 `public`은 테이블 등을 묶는 이름 공간이다. `public.endpoints`는 스키마명.테이블명이다.
- identity는 ID 자동 생성, RETURNING은 변경한 행을 같은 SQL에서 반환한다.
- DBeaver 실행 시 SQL 일부만 실행되어 생긴 오류를 해결했다. 테이블 생성문 전체 실행이 필요했다.
- 동일 INSERT를 여러 번 실행하면 별개 ID로 저장된다. 요청에 따라 중복 ID 2만 삭제하고 ID 1을 보존했다.
- Spring은 명시한 config import로 `.env`를 읽고 DataSource에 값을 전달한다.
- CI는 자동 검사·빌드, CD는 배포 준비·배포 자동화다.
- Flyway는 직접 작성한 DB 변경 SQL의 적용 순서와 이력을 관리한다. 기존 DB baseline은 구조를 확인했다는 기준 선언이다.

## 사용자 학습 방식

사용자는 Java·Spring 기본 CRUD와 SQL 배경이 있으므로 기초 반복 설명은 줄인다.
단순 등록·조회 코드는 에이전트가 구현하고, PostgreSQL 특성·트랜잭션·동시성·신뢰성 처리는 실제 코드를 보며 설명한다.
사용자는 진행 내용을 계속 기록하기를 요청했다. 작업 후 STATUS와 해당 작업 기록을 갱신한다.

## 다음

이벤트 접수 기능을 위한 event·delivery·attempt 역할을 먼저 구분한다.
명세 F-04의 이벤트·전달 대기 원자적 저장과 중복 접수 정책을 작은 작업으로 나눠 구현한다.
현재 인증·이벤트 접수·실제 전송·재시도·CD는 미구현이다.
