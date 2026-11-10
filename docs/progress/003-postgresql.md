# PostgreSQL 연결

## 변경과 이유

사용자가 DBeaver에서 만든 테이블과 데이터를 유지하면서 API의 메모리 저장소를 JDBC 저장소로 교체했다.
Spring JDBC starter, PostgreSQL JDBC 드라이버, 테스트용 Testcontainers를 추가했다. 버전은 기존 Boot 의존성 관리에 따른다.

`JdbcEndpointRepository`의 SQL은 이름 있는 매개변수를 바인딩한다. 문자열에 사용자 입력을 이어 붙이지 않는다.
`INSERT ... RETURNING`으로 DB가 생성한 ID·기본값을 포함한 행을 받아 `Endpoint` record로 매핑한다.
목록 조회는 `project_id`로 제한하고 `id` 순서로 정렬한다.

Spring 설정에 `.env` import와 DataSource 연결 정보를 추가했다. 실제 비밀번호 파일 내용은 읽어 출력하거나 복사하지 않았다.
로컬 앱은 localhost:5433, Compose 앱은 postgres:5432로 연결한다.
SQL 자동 초기화는 껐다. `db/schema.sql`은 이미 존재하는 로컬 테이블에 자동 적용하지 않는다.

## 트랜잭션과 동시성

`EndpointService.create()`의 `@Transactional`이 프로젝트 잠금, 개수 확인, INSERT를 같은 DB 트랜잭션으로 묶는다.
동일 프로젝트 ID의 `pg_advisory_xact_lock`을 획득한 요청만 등록 절차를 진행한다. 잠금은 commit/rollback 때 풀린다.
READ COMMITTED를 명시해 앞선 요청의 커밋 결과가 다음 개수 조회에 반영되게 했다.
HikariCP가 커넥션 풀을 관리하고 Spring이 현재 트랜잭션의 연결을 JDBC 작업에 제공한다.

이는 DB CHECK 제약이 아니다. 모든 등록 경로가 같은 잠금 규칙을 지켜야 한다.
DBeaver의 직접 INSERT는 이 프로토콜을 따르지 않으므로 10개 제한을 우회할 수 있다.
프로젝트 테이블 도입 시 프로젝트 행 잠금으로 바꾸는 방안을 비교할 수 있다.

## 검증

DB에 직접 넣은 행을 API가 조회하는 테스트를 먼저 실행해 메모리 저장소에서 실패를 확인했다.
교체 후 `./scripts/backend.sh test assemble` 성공, 테스트 14개 실패·오류·스킵 0개. 격리된 PostgreSQL 17에서 기존 입력·개수 제한과 DB 저장·조회, 동시 등록을 검증했다.
테스트 초기화는 Testcontainers 컨테이너 안에서만 수행한다.
패키징한 JAR도 임시 포트로 실행해 실제 로컬 DB의 기존 ID 1을 목록 API에서 HTTP 200으로 조회했다. 데이터 변경 없이 조회만 했고 임시 앱은 종료했다.

## 학습과 다음 단계

읽을 순서: application.yaml → JdbcEndpointRepository.save() → EndpointService.create().
생각해볼 문제: 트랜잭션만 있고 프로젝트 잠금이 없다면, 동시에 온 두 요청이 모두 개수 9를 읽을 때 무슨 일이 생기는가?
다음 단계는 Flyway로 스키마 변경을 기록하는 것이다. 기존 수동 생성 DB의 도입 절차를 별도로 다룬다.
인증, 실제 전송, 운영 배포는 이번 작업 범위 밖이다.

참고: [Spring Testcontainers](https://docs.spring.io/spring-boot/reference/testing/testcontainers.html), [PostgreSQL advisory lock 함수](https://www.postgresql.org/docs/17/functions-admin.html#FUNCTIONS-ADVISORY-LOCKS).
