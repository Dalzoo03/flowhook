# Flyway: DB 구조 변경 이력 관리

Git이 SQL 파일의 변경 이력을 관리한다면, Flyway는 각 DB에 어떤 버전의 SQL이 적용됐는지 기록한다.
이번에는 기존 테이블 생성 SQL을 `backend/src/main/resources/db/migration/V1__create_endpoints.sql`로 옮겼다.

## 앱 시작 시 하는 일

1. 설정된 PostgreSQL에 연결한다.
2. `public.flyway_schema_history`에서 적용 이력을 읽는다.
3. 마이그레이션 파일과 기록을 검사한다.
4. 아직 적용하지 않은 버전의 SQL을 순서대로 실행하고 결과를 기록한다.

`V1__create_endpoints.sql`에서 V1은 버전 1, 밑줄 두 개 뒤는 설명이다.
다음 구조 변경은 V1을 고치는 대신 V2 파일로 추가한다. 예를 들어 실제로 생성 시각이 필요해질 때 `V2__add_endpoint_created_at.sql`을 만든다. 이번에는 V2를 추가하지 않았다.

적용한 SQL 파일은 바꾸지 않는다. 실제 SQL 마이그레이션에는 checksum이 기록되어 이후 수정되면 검증이 실패한다.
오류를 감추려고 history를 수동 수정하거나 무작정 repair하지 말고, 변경 원인을 먼저 확인한다.

## 새 DB와 기존 DB의 차이

| DB 상태 | 도입 방식 | history의 V1 타입 |
| --- | --- | --- |
| 빈 DB | V1 SQL을 실제 실행해 테이블·인덱스 생성 | SQL |
| 이미 동일한 테이블이 있는 DB | 현재 구조를 확인한 뒤 V1 기준점을 기록 | BASELINE |

baseline은 "현재 DB가 V1 수준이라고 확인했다"는 선언이다. 테이블을 다시 만들거나 기존 데이터를 지우지 않는다.
baseline 자체가 실제 구조와 V1 SQL의 일치를 자동 증명하는 것은 아니다. 기준 이하 SQL을 실행·검증한 것으로 착각하면 안 된다.
이번 로컬 DB는 컬럼·타입·identity·기본값·PK·CHECK·인덱스를 먼저 확인했고 V1과 일치했다.

## 이번 로컬 DB 도입

기존 데이터가 있는 DB에 자동 baseline을 상시 허용하면 잘못 연결한 DB까지 기준 처리할 수 있다.
그래서 `application.yaml`의 `spring.flyway.baseline-on-migrate`는 false다.
이번에는 확인한 로컬 DB에만 앱 실행 인자로 true와 baseline-version=1을 한 번 전달했다.
이후 해당 인자 없이 정상 시작되는지 다시 확인한다. 이 인자를 IntelliJ 실행 설정이나 CI에 계속 남기지 않는다.

현재 로컬 DB는 이미 기준 처리했으므로 다음부터 평소처럼 앱을 실행하면 된다.
DBeaver에서 이력은 다음으로 확인한다:

```sql
SELECT installed_rank, version, description, type, script, checksum, success
FROM public.flyway_schema_history
ORDER BY installed_rank;
```

로컬 DB에는 version=1, type=BASELINE, success=true가 나타나는 것이 맞다. baseline 행에 SQL checksum이 없는 것도 정상이다.
테스트용 빈 DB에는 V1이 실제 실행되므로 type=SQL로 기록된다.

## 코드 설정

- `spring-boot-starter-flyway`: 앱 시작 시 Flyway 실행을 연결한다.
- `flyway-database-postgresql`: PostgreSQL 지원 모듈이다.
- `spring.sql.init.mode=never`: 일반 schema.sql 자동 실행과 중복하지 않는다.
- `default-schema=public`: 이력과 현재 테이블을 public에 관리한다.
- `clean-disabled=true`: Flyway clean을 통한 스키마 삭제를 막는다. Docker 볼륨 삭제까지 막는 설정은 아니다.
- 테스트도 같은 V1을 사용한다. 테스트 컨테이너의 별도 init script는 제거했다.

## 학습 확인

생각해볼 것: 동료가 새 DB에서 앱을 실행하면 V1은 SQL로 기록되는데, 내 로컬 DB에서는 왜 BASELINE인가?
향후 실제 구조 변경을 V2로 추가하면 두 환경 모두 V2를 실행한다.
현재는 수동 DDL 대신 새 마이그레이션 파일을 추가하는 방식으로 변경을 기록한다.

참고: [Spring DB 초기화](https://docs.spring.io/spring-boot/how-to/data-initialization.html), [Flyway baseline](https://documentation.red-gate.com/flyway/reference/commands/baseline), [자동 baseline 설정](https://documentation.red-gate.com/flyway/reference/configuration/flyway-namespace/flyway-baseline-on-migrate-setting).
