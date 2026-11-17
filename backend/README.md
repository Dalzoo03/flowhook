# FlowHook 백엔드

Java 21, Spring Boot 4.1.1, Spring MVC, Gradle Wrapper 9.7.1을 사용한다.
현재 API는 상태 확인, 전달 대상 등록·조회, 이벤트 접수이며 PostgreSQL에 저장한다.

실행 전에 Docker Desktop을 켜고 루트에서 `docker compose --profile db up -d postgres`로 DB를 실행한다.
`.env`의 비밀번호는 기존 DB 사용자 비밀번호와 일치해야 한다.
빈 DB에서는 앱 시작 시 Flyway가 V1(전달 대상), V2(이벤트·전달 대기), V3(전송 시도), V4(시도 번호·횟수·재시도 예약), V5(처리 기한·소유권·복구 이유)를 적용한다. 수동으로 테이블을 만들 필요가 없다.
수동 실습으로 이미 테이블을 만든 DB는 구조 확인 후 한 번의 baseline이 필요하다. 현재 로컬 DB에는 도입 작업에서 이미 적용했다.
다른 기존 DB에 도입할 때는 [Flyway 안내](../docs/FLYWAY.md)를 읽고 구조부터 확인한다.

## 실행

프로젝트 최상위 디렉터리에서:

```bash
./scripts/backend.sh
```

macOS에서는 이 스크립트가 설치된 Java 21을 해당 프로세스에만 지정한다.
다른 운영체제에서는 Java 21을 설치하고 `JAVA_HOME` 또는 `PATH`를 지정한다.

Gradle을 직접 사용하려면 `backend/`에서 실행한다:

```bash
./gradlew --no-daemon bootRun
```

기본 주소: `http://localhost:8080/api/health`.
코드상 기대 응답: HTTP 200, `{"status":"UP"}`. `/`는 구현하지 않아 404가 정상이다.
종료는 `Ctrl+C`.

## 빌드와 JAR

프로젝트 최상위에서:

```bash
./scripts/backend.sh assemble
```

실행 파일은 `backend/build/libs/flowhook-0.0.1-SNAPSHOT.jar`다.
`-plain.jar`는 의존성을 포함한 서버 실행 파일이 아니다.
실행 JAR는 Java 21로 `java -jar` 명령을 사용한다.

## 설정 변경 예

```bash
SERVER_PORT=8081 ./scripts/backend.sh
```

`application.yaml`의 `${SERVER_PORT:8080}`은 환경변수가 있으면 그 값, 없으면 8080이라는 뜻이다.
Spring이 원래 `.env`를 자동으로 읽는 것은 아니다. 이번 설정에서는 `spring.config.import`로 현재 작업 폴더와 그 부모의 `.env`를 properties 형식으로 명시적으로 읽도록 했다. 프로젝트 루트 또는 `backend/`를 작업 폴더로 사용한다.
파일에는 `POSTGRES_PASSWORD`, `POSTGRES_PORT`를 지정한다. 실행 환경변수의 `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`로 연결 정보를 덮어쓸 수도 있다.

## 전달 대상 등록·조회

- `POST /api/projects/1/endpoints`: 등록, HTTP 201과 생성 객체 반환.
- `GET /api/projects/1/endpoints`: 등록 순서 목록, HTTP 200 반환.
- 프로젝트는 실습용 1만 지원한다. 나머지 ID는 404다.
- 이름은 앞뒤 공백 제거 후 1~80자, 이벤트 유형은 필수이며 입력한 대소문자를 유지한다.
- 기본 URL은 HTTPS이며 사용자 정보·fragment·잘못된 포트를 거절한다. local 프로필에서만 숫자 루프백 수신 주소를 예외 허용한다.
- 중복 URL도 별도 대상으로 등록한다. 10개를 초과하면 409로 거절한다.
- 사용자 인증, 외부 목적지 소유 확인·허용 목록, endpoint별 서명키 발급은 아직 없다. 로컬 공유키 서명은 지원한다. 실제 전송은 local 프로필의 데모 수신기로만 가능하다.
- PostgreSQL에 저장하므로 앱 재시작 후에도 데이터가 남는다. Docker의 DB 볼륨 삭제는 데이터를 지운다.
- 등록은 트랜잭션 안에서 프로젝트별 advisory lock → 개수 확인 → INSERT 순서다. SQL을 직접 실행하는 외부 쓰기에는 이 제한이 강제되지 않는다.

```bash
curl -i http://localhost:8080/api/projects/1/endpoints \
  -H 'Content-Type: application/json' \
  -d '{"name":"배송 서비스","url":"https://partner.example.com/webhooks","eventType":"order.created"}'

curl http://localhost:8080/api/projects/1/endpoints
```

## 테스트

Docker Desktop이 켜진 상태에서 루트의 `./scripts/backend.sh test`를 실행한다.
Testcontainers가 별도 PostgreSQL 17 컨테이너를 띄운다. 로컬 flowhook DB와 포트·계정·저장 공간이 분리된다.
각 테스트의 초기화는 테스트 컨테이너에만 적용한다. 테스트를 위해 로컬 DB를 비우지 않는다.
Spring과 MockMvc로 기존 API 동작, DB 데이터 조회·저장, 동시 등록 20건에서 성공 10건·거절 10건을 검증한다.
테스트 DB의 테이블도 수동 init script 대신 앱의 Flyway가 생성한다.
Flyway 테스트는 빈 DB의 V1→V5 적용·재실행, 기존 DB의 baseline→V5 및 V3→V5·V4→V5 데이터 보존을 검증한다. 실제 전송 테스트를 포함해 전체 147개다.

## 이벤트 접수 — 로컬 실습 단계

### Swagger UI에서 실행

Gradle 변경 사항을 새로고침하고 앱을 재시작한 뒤 http://localhost:8080/swagger-ui/index.html 을 연다.
`이벤트` → `POST /v1/events` → `Try it out` → 예시 JSON 확인 → `Execute`로 요청한다.
동일 입력을 두 번 보내면 202→200, 같은 eventId에서 data만 바꾸면 409가 된다.
`전달 대상`에서 등록·목록 조회도 가능하다. projectId에는 1을 사용한다.
`Execute`는 실제 API 요청이므로 등록·접수 데이터를 DB에 저장한다.
OpenAPI JSON은 `/v3/api-docs`에서 확인한다.

### 터미널에서 실행

앱을 재시작하면 미적용 마이그레이션이 적용된다. 아래 요청은 실제로 이벤트와 전달 대기를 추가한다.

```bash
curl -i http://localhost:8080/v1/events \
  -H 'Content-Type: application/json' \
  -d '{"eventId":"order-1001","type":"order.created","data":{"orderId":1001}}'
```

신규 요청은 202와 `id`, `eventId`, `matchedDeliveryCount`, `duplicate=false`를 반환한다.
같은 요청을 다시 보내면 200과 같은 내부 ID·최초 대상 수, `duplicate=true`가 반환된다.
동일 eventId의 type/data가 다르면 409 `EVENT_ID_CONFLICT`다. 저장 실패는 503이며 이벤트와 전달 작업 모두 롤백된다.

- 최상위 필드는 eventId/type/data만 받는다. ID·유형은 공백만 있는 값을 제외한 1~128자 문자열, data는 객체다.
- 본문 한도 65536바이트. 초과하면 413. 잘못된 JSON, 중복 키, 미정의 필드, 비표준 숫자는 400이다.
- 숫자는 BigInteger/BigDecimal로 파싱한다. JSONB 비교로 객체 키 순서와 1/1.0 차이는 무시하되 배열 순서·자료형·큰 정수 값은 구분한다.
- PostgreSQL JSONB가 저장할 수 없는 NUL·잘못된 Unicode·숫자 범위는 입력 단계에서 거절한다. 숫자·문자열·객체 키 토큰 한도는 본문 한도에 맞췄으며 Jackson의 기본 파싱 깊이 제약은 적용된다.
- 신규 접수 당시 프로젝트의 활성 endpoint 중 event_type이 정확히 일치하는 대상에만 PENDING 전달 작업을 만든다. URL과 전체 송신 payload를 고정한다.
- 대상이 없으면 202, matchedDeliveryCount=0으로 이벤트만 저장한다. 중복 접수로 나중에 추가된 대상에 재매칭하지 않는다.
- 프로젝트당 이벤트 10000개. 같은 내용의 중복 조회는 한도 검사보다 먼저 처리한다.
- 오류 응답은 code/message/traceId를 포함한다. 저장 장애 로그는 같은 traceId를 기록하며 요청 본문·SQL 내부 오류는 응답에 넣지 않는다.

현재는 인증 없이 프로젝트 1로 고정된 학습 API다. 공개 서비스용 인증 계약을 완성한 상태가 아니다.
프로젝트별 잠금으로 접수 경쟁과 한도를 제어하므로 같은 프로젝트의 접수는 직렬 처리된다. 처리량 개선은 측정 후 후속으로 다룬다.
local 프로필에서 자동 worker·시도 기록·재시도 예약과 전달 목록을 제공한다. [Swagger 전송 실습](../docs/SEND_ONCE.md)을 따른다.
재시도 가능 실패는 RETRY_WAIT로 예약한다. 예약 시각 이후 worker가 자동 실행하며 최초 포함 최대 5회다. worker는 한 배치 최대10건 순차 처리 후 1초 쉬고 다시 조회한다. 30초 lease가 만료된 PROCESSING을 UNKNOWN으로 닫고 남은 횟수에 따라 재예약/DEAD 처리한다. 이벤트 조회 API는 아직 없다. 저장 결과는 DBeaver에서도 확인한다:

```sql
SELECT id, project_id, event_id, type, data, matched_delivery_count, received_at
FROM public.events ORDER BY id;

SELECT id, event_id, endpoint_id, target_url, payload, status, attempt_count, next_attempt_at
FROM public.deliveries ORDER BY id;
```

전체 설명: [설정 설명서](../docs/GETTING_STARTED.md).

## JPA와 이벤트 요청 DTO

전달 대상·이벤트의 일반 저장/조회는 `EndpointEntity`, `EventEntity`와 Spring Data `JpaRepository`를 사용한다.
JSONB의 정확한 동등 비교·전달 일괄 생성은 EventRepository의 native query다.
조건부 선점·시도 결과 저장·최신 결과 조회는 DeliveryTransactions의 JPA/Hibernate native SQL로 유지한다.
`spring.jpa.hibernate.ddl-auto=validate`로 기존 Flyway 스키마를 확인하며 JPA가 테이블을 생성·변경하지 않는다. `open-in-view=false`다.
외부 HTTP는 이전처럼 트랜잭션 밖에서 실행한다.

EventController는 `@Valid @RequestBody EventRequest`로 요청 JSON을 DTO로 받는다.
EventRequest는 eventId/type/data 필드와 Bean Validation 어노테이션만 가진다.
64KiB 크기 제한은 EventBodyLimit, 중복 키·정확한 숫자·엄격한 JSON 설정은 JsonConfiguration,
JSONB에 저장할 수 없는 값 검사는 EventJsonValidation으로 분리했다. JsonConfiguration은 앱 전체 JSON 입력에 적용된다.
기존 성공·오류 응답 형식과 기존 테스트 계약은 유지한다.
학습·검증 기록: [JPA 전환](../docs/progress/010-jpa-request.md).

## 로컬 자동 worker

`application-local.yaml`은 `flowhook.delivery.worker-enabled=true`다. local 프로필과 local-receiver-enabled=true가 함께 있어야 동작한다.
worker가 프로젝트1/활성 대상/PENDING 또는 due RETRY_WAIT/잔여 횟수/정규 로컬 URL을 먼저 필터링한 뒤 최대10 ID를 조회한다.
후보 조회가 끝난 뒤 `DeliveryService.sendOnce`를 한 건씩 호출하므로 HTTP 전송은 DB 트랜잭션 밖이다.
중복 선점 경쟁의404/409는 건너뛰고, 개별 처리나 DB 조회 실패는 경고를 남기고 다음 작업/tick으로 진행한다.
로그에는 payload·URL·DB 예외 상세를 넣지 않는다. 저장 실패로 남은 PROCESSING은 lease 만료 후 별도 scheduler 작업이 회수한다.
수동 실습은 실행 인자 `--flowhook.delivery.worker-enabled=false`로 자동 worker만 끈다. 기본 프로필에서는 worker가 없다.
local 재시작 시 기존 데모 목적지의 실행 가능한 작업도 처리한다. [자동 전송 실습](../docs/SEND_ONCE.md), [구현 기록](../docs/progress/011-local-worker.md).

## 처리 기한과 중단 복구

선점 직전에 DB 시각 기준30초 lease와 UUID를 저장한다. 결과 저장은 현재 UUID/회차/유효 기한을 검사하며 이전 worker의 늦은 응답은409로 거절한다.
별도 복구 tick이 만료 PROCESSING을 UNKNOWN/LEASE_EXPIRED로 닫고 같은 시도 예산으로 재예약하거나5회에서 DEAD로 끝낸다.
스케줄러 풀은2개이며 정상 송신은 여전히 배치 내 순차 실행이다. 중지 수신처는 상태 정리만 하고 전송을 보류한다.
목록에 leaseExpiresAt/recoveryReason을 제공한다. V5 적용 전 구버전 앱을 모두 중지하며, 상태/이력/횟수는 유지한다.
테스트103개 외에도 임시 DB와 패키징 앱에서 실제 SIGKILL→재시작→30초 만료→두 번째 시도 성공을 확인했다.
중복 HTTP는 여전히 가능하다. heartbeat/수신자 멱등 처리/구버전 혼합 운영은 지원하지 않는다. [복구 기록](../docs/progress/012-lease-recovery.md).

## 수신자 중복 업무 방지

demo/receiver는 receiver source set으로 컴파일한다. `./scripts/receiver.sh`는 Gradle receiver JavaExec 작업으로 실행하며 JDBC/Flyway/리소스를 포함한다.
수신기 DB는 별도 demo_receiver 스키마/수신측 Flyway V1을 사용하며 FlowHook public V5에는 변경이 없다.
`test`의 수신자19개·서명20개·키4개·실제 worker 통합1개 등을 포함한 전체147개와 `receiverCheck` 자체 검증을 함께 실행하려면 `./scripts/backend.sh test receiverCheck assemble`을 사용한다.
기존 지연 모드는 신규 업무 커밋 후 응답을 지연하며, 중복 요청은 바로 성공한다. TIMEOUT→자동 재시도 SUCCESS와 수신2/업무1이 확인된다.
[수신기 실행/DB 안내](../demo/receiver/README.md), [학습 기록](../docs/progress/014-receiver-idempotency.md).

## 로컬 웹훅 서명

LocalWebhookSender가 실제 UTF-8 body/전달ID/전송 Unix초에 HMAC-SHA256을 계산한다. Receiver는 업무/로그 전에 유효한 서명과 ±300초를 확인한다.
기본 키는 첫 실행에 생성하는 프로젝트 .local/webhook-signing.key이며 Git에서 제외한다. 로컬 기능이 꺼진 앱은 생성하지 않는다. 명시한 FLOWHOOK_SIGNING_KEY 또는 FLOWHOOK_SIGNING_KEY_FILE도 지원한다.
송신/수신 모두 새 버전으로 재시작한다. 테스트는 합성 키/임시 파일만 사용하며 전체147개와 receiverCheck 검증을 통과했다. DB 마이그레이션 변경은 없다.
[프로토콜/설정](../docs/WEBHOOK_SIGNATURE.md), [검증·학습 기록](../docs/progress/015-webhook-signature.md).
