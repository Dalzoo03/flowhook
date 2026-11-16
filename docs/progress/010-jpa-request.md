# 010 — JPA와 이벤트 요청 DTO 정리

기록일: 2026-11-16. 사용자 요청: JDBC를 JPA로 전환하고 EventRequest를 익숙한 @Valid @RequestBody 방식으로 단순화한다. 사용자가 5회 전송 실패 후 DEAD를 확인했다.

## 구현과 이유

- EndpointEntity/EventEntity 및 Spring Data JpaRepository로 일반 저장·조회·count를 전환했다. 엔티티와 응답 DTO는 분리한다.
- EventService가 save로 이벤트를 저장하고, 관리 중인 EventEntity의 matchedDeliveryCount 변경은 dirty checking으로 반영한다.
- JSONB 동등 비교와 일괄 전달 생성은 native query로 유지한다. 정확한 숫자와 PostgreSQL JSON 비교 의미를 바꾸지 않는다.
- 프로젝트별 advisory transaction lock과 전송의 조건부 UPDATE RETURNING은 JPA native query로 유지한다. 단순 find→save로 바꿔 중복 선점 가능성을 만들지 않는다.
- DeliveryTransactions는 EntityManager/Hibernate native query를 사용한다. @Repository로 DB 예외를 Spring DataAccessException으로 변환한다.
- 외부 HTTP는 DeliveryService에서 DB 트랜잭션 밖에 둔다. 선점과 결과 저장은 각각 REQUIRES_NEW다.
- EventRequest는 입력 필드·@NotBlank/@NotNull만 가진 record다. Controller는 @Valid @RequestBody를 받으며 Spring이 역직렬화한다. 별도의 문서용 EventInput 중복 모델을 없앴다.
- 직접 파싱을 제거하되 64KiB 제한은 RequestBodyAdvice, 정확한 숫자·중복 키·타입 강제 변환/추가 필드 거절은 Jackson 설정, JSONB 값 한도는 별도 검증으로 유지한다. JSON 설정은 다른 API에도 적용된다.
- Flyway SQL은 변경하지 않았다. Hibernate ddl-auto=validate, open-in-view=false로 기존 데이터/스키마를 유지한다.

## 실제 검증

JPA/Validation 의존성 추가 후 기존 이벤트·대상 테스트 기준 동작을 확인했다. 전환 후 전체 테스트에서 날짜 결과 매핑과 예외 변환의 두 회귀를 확인했다.

- 원인: Hibernate native SQL이 TIMESTAMPTZ를 Instant로 반환하지만 결과 record는 OffsetDateTime을 요구했다. 변경: 결과 모델을 Instant로 통일했다. API는 동일한 UTC ISO 시각을 반환한다.
- 원인: 직접 Hibernate 호출의 예외가 기존 DataAccessException 처리에 들어가지 않았다. 변경: DB 경계인 DeliveryTransactions에 @Repository를 적용해 예외를 변환한다.
- 수정 후 `./scripts/backend.sh test assemble` 성공. 총 84개, 실패·오류·스킵 0개: endpoint15, event32, delivery20, policy14, migration3.
- 기존 동시 100회 이벤트 접수의 중복 방지, 결과 저장 실패 시 PROCESSING/UNKNOWN 유지, 선점 경쟁, DB 롤백, 5회 종료, JSON·숫자·본문 경계 테스트를 완화하지 않고 통과했다.
- 별도 읽기 검토에서 동일한 두 문제를 확인했으며 변경 후 전체 테스트로 수정 결과를 확인했다.
- 패키징 JAR을 임시 포트/local 프로필로 실행해 기존 로컬 DB의 Hibernate 스키마 검증과 health/endpoints/deliveries/OpenAPI GET 200을 확인했다. 확인 전후 endpoints/events/deliveries/delivery_attempts 전체 행의 해시가 일치했다. 사용자 전달은 실행하지 않았다.

## 학습과 작은 과제

읽을 순서: EventRequest → EventController.accept → EventService.accept → EventRepository/EventEntity.
DTO는 외부 입력 형식, Entity는 DB 저장 모델이다. @Valid는 검증, @RequestBody는 JSON→자바 객체 변환을 요청한다.
save 이후 같은 트랜잭션 안에서 관리 중인 Entity를 바꾸면 dirty checking이 UPDATE를 실행한다.
작은 실습: Swagger에서 type을 빈 문자열로 바꿔 400을 확인하고, @NotBlank가 어느 필드에 붙어 있는지 찾아본다. DB를 직접 수정할 필요는 없다.

## 다음 단계와 제한

이번 범위는 저장 계층/입력 정리다. 자동 worker·lease 회수·장애 복구는 구현하지 않았다.
다음 후보는 예약 시각이 지난 작업을 찾아 기존 선점→HTTP→결과 저장 흐름을 호출하는 작은 worker다. 별도 합의 후 구현한다.
기존 중복 수신 가능성, 인증·서명·외부 송신 제한은 유지한다. 테스트는 별도 PostgreSQL 컨테이너를 사용한다.
커밋·푸시·원격 CI 실행은 하지 않았다.
