# 로컬 자동 전송과 재시도 실습

local 프로필에서는 worker가 PENDING과 실행 시각이 지난 RETRY_WAIT를 자동 전송한다.
기존 데모 목적지의 실행 가능한 작업도 다음 재시작부터 처리한다. DEAD·SUCCEEDED는 되살리지 않는다. PROCESSING은 처리 기한이 지난 뒤 회수한다.

## 1. 수신기와 FlowHook 실행

먼저 수신기를 켠다:

```bash
./scripts/receiver.sh
```

Docker Desktop/PostgreSQL이 켜져 있는 상태에서 FlowHook을 기존 IntelliJ local 프로필로 재시작한다.
Active profiles에 local 또는 Program arguments에 `--spring.profiles.active=local`을 사용한다.
터미널 실행은 다음과 같다:

```bash
SPRING_PROFILES_ACTIVE=local ./scripts/backend.sh
```

`application-local.yaml`에서 local-receiver-enabled와 worker-enabled가 true다. 다른 프로필에서는 자동 worker가 생성되지 않는다.
대상은 프로젝트 1의 활성 수신처이며, 고정된 `http://127.0.0.1:9090/webhooks` 및 `?mode=success|failure|delay` 주소만 자동 전송한다.
일반 외부 URL은 자동 송신하지 않으며, 중지된 대상도 건너뛴다. 비표준 표기의 동등 URL은 worker 대상이 아니다.
포트를 바꾸면 앱의 `--flowhook.delivery.local-receiver-port=9091`과 수신기 포트를 함께 바꾼다.
현재는 호스트 JVM 실습이다. Docker 안의 127.0.0.1은 호스트 수신기가 아니다.

## 2. 전달 대상 확인

Swagger: http://localhost:8080/swagger-ui/index.html

GET `/api/projects/1/endpoints`로 기존 대상을 확인한다. 같은 type과 URL의 대상이 있으면 다시 등록하지 않는다.
정상 대상을 새로 만들 때만 POST `/api/projects/1/endpoints`:

```json
{"name":"로컬 정상 수신기","url":"http://127.0.0.1:9090/webhooks","eventType":"demo.success"}
```

같은 type의 활성 대상이 여러 개면 각 대상으로 전달이 생성된다. 각각 별도 작업이므로 수신 로그도 여러 번 나온다.

## 3. 이벤트만 접수하고 자동 전송 확인

POST `/v1/events`:

```json
{"eventId":"auto-success-001","type":"demo.success","data":{"orderId":5001}}
```

새 이벤트는 202로 접수된다. 같은 eventId와 내용을 반복하면 200/duplicate=true이며 새로운 작업은 만들지 않는다.
GET `/api/projects/1/deliveries`를 실행해 이 이벤트의 전달을 찾는다. 잠시 후 다시 조회하면 SUCCEEDED/SUCCESS/httpStatus=200, attemptCount=1이다.
수동 POST send를 누를 필요가 없다. 대상이 0개면 작업도 없으므로 먼저 수신처를 등록하고 새로운 eventId로 접수한다.

worker는 배치를 끝낸 뒤 1초 쉬고 다시 확인한다. 한 배치 최대10건을 순서대로 처리하므로 backlog나 느린 수신기가 있으면 더 기다릴 수 있다.

## 4. 실패 후 자동 재시도 확인

기존 demo.failure 수신처가 있으면 재등록하지 않는다. 없을 때만 아래 설정을 등록한다:

```json
{"name":"로컬 실패 수신기","url":"http://127.0.0.1:9090/webhooks?mode=failure","eventType":"demo.failure"}
```

POST `/v1/events`:

```json
{"eventId":"auto-failure-001","type":"demo.failure","data":{"orderId":5002}}
```

이후에는 GET deliveries만 실행한다.

- 첫 실패: RETRY_WAIT / HTTP_ERROR / httpStatus=500 / attemptCount=1 / nextAttemptAt 저장.
- 시각이 지나면 worker가 같은 전달 ID를 자동 재전송한다. attemptId와 attemptCount가 증가한다.
- 최초 포함 5회 실패하면 DEAD, nextAttemptAt=null이며 더 전송하지 않는다.

backoff는 5/10/20/40초 ±20%다. 대기만 대략 60~90초이며 HTTP 시간·배치 처리·poll 지연이 추가된다.
데모 failure는 계속500을 반환한다. DB 기반 수신기의 delay는 신규 업무 커밋 후8초 지연해 첫 요청을 TIMEOUT으로 만들고, 중복 재시도는 지연 없이200으로 성공시킨다.
408/429/5xx/네트워크/타임아웃은 남은 횟수 안에서 예약한다. 다른 3xx/4xx, URL_BLOCKED, 중단 UNKNOWN은 종료한다.
429의 Retry-After는 초 또는 HTTP 날짜를 최대10분 반영하고 backoff보다 이른 예약을 만들지 않는다.

## 5. 이전 수동 실습 방법

수동 단계별 확인을 하려면 Program arguments에 다음을 추가하고 재시작한다:

```text
--flowhook.delivery.worker-enabled=false
```

local 수신기 API는 유지된다. GET deliveries의 전달 **id**를 골라 POST `/api/projects/1/deliveries/{deliveryId}/send`로 실행한다.
PENDING은 첫 시도, RETRY_WAIT는 예약 시각 이후 재시도다. 이른 실행·종료 상태·이미 PROCESSING인 작업은409다.
worker가 켜져 있으면 수동 실행과 경쟁할 수 있다. 이를 없애려고 상태나 횟수를 DB에서 초기화하지 않는다.
API의 HTTP200은 시도 처리 완료이며, 실제 상대 응답은 본문의 httpStatus다.

## 6. DB와 장애 경계

```sql
SELECT id,event_id,status,attempt_count,next_attempt_at FROM public.deliveries ORDER BY id;
SELECT id,delivery_id,attempt_no,started_at,finished_at,outcome,http_status,duration_ms
FROM public.delivery_attempts ORDER BY id;
```

후보 조회는 소유권이 아니다. 각 작업의 실제 HTTP 직전에 원자적 claim으로 선점해 중복 실행을 막는다.
선점+UNKNOWN 시도 저장 커밋 → DB 트랜잭션 밖의 HTTP → 결과/예약을 새 트랜잭션으로 저장한다.
한 작업의 저장 실패는 나머지 작업을 막지 않는다. 조회 실패는 다음 tick에 재조회하며 상태를 임의로 초기화하지 않는다. 만료된 PROCESSING은 별도 복구 tick이 UNKNOWN으로 정리하고 재예약/DEAD 처리한다.

응답 본문은 최대4096바이트 BYTEA다. 연결 제한2초, 응답 대기5초, 전체 HTTP 상한10초이며 앞뒤 DB 시간은 별도다.
redirect/proxy는 사용하지 않는다. payload와 목적지는 접수 시 고정하고 목록에는 최신 시도를 표시한다.

30초 lease와 소유권 검사를 사용한다. 결과 저장 실패·프로세스 종료 후 기한이 지나면 UNKNOWN/LEASE_EXPIRED로 기록하고 남은 횟수에 따라 재예약하거나 DEAD로 끝낸다.
타임아웃은 상대의 미수신을 증명하지 않으므로 중복 HTTP 가능성도 남아 있다. 서명·인증·외부 목적지 송신은 후속 단계다.

## 7. 전송 중 중단·재시작 실습

V5 업그레이드 전 기존 FlowHook을 모두 중지하고 새 버전만 local로 실행한다. Flyway가 V5를 적용하며 기존 데이터를 초기화할 필요는 없다.
목록의 leaseExpiresAt은 PROCESSING의 처리 기한이고 recoveryReason은 최신 시도가 회수됐을 때 LEASE_EXPIRED다.

실제 중단 실습은 합성 데이터로만 진행한다. 느린 수신기(mode=delay)와 demo.delay 대상이 있는 상태에서 새 eventId의 이벤트를 접수한다.
수신기 로그에 POST가 도착하고 목록이 PROCESSING인 동안 **해당 FlowHook JVM만** 강제 종료한다. 보통의 정상 종료는 요청이 완료될 수 있어 crash와 다르다.
다시 local로 시작하고 GET deliveries만 조회한다. 기존 lease가 만료될 때까지 기다린 뒤 RETRY_WAIT/UNKNOWN/recoveryReason=LEASE_EXPIRED, 다음 예약 이후 attemptCount 증가를 확인한다.
DB 기반 delay 수신기는 첫 업무를 이미 커밋했으므로 중복 재전송을 지연 없이200으로 받는다. 복구 후에도 업무 장부는 같은 전달당 한 건만 남는다.
이전 UNKNOWN 이력은 다음 성공/실패 시도 뒤에도 DB에 남는다. 중지 endpoint는 회수만 하고 활성화될 때까지 전송을 보류한다.
lease30초 + 재시도 backoff + polling/배치 시간이 필요하며 정확히30초 뒤 전송한다는 뜻은 아니다. 서버가 꺼져 있는 동안에는 회수할 worker도 없다.

## 8. HTTP 두 번·업무 한 번 확인

수신기를 새 코드로 재시작한다(`./scripts/receiver.sh`). PostgreSQL도 실행 중이어야 한다. FlowHook의 기존 local worker는 그대로 사용한다.
GET endpoints에서 기존 `demo.delay` 대상의 URL이 `http://127.0.0.1:9090/webhooks?mode=delay`인지 확인한다. 이미 있으면 다시 등록하지 않는다.
없을 때만 이 URL과 eventType=demo.delay로 수신처 하나를 등록한다.

POST `/v1/events`:

```json
{"eventId":"receiver-once-001","type":"demo.delay","data":{"orderId":7001}}
```

GET deliveries를 조회한다. 첫 시도는 TIMEOUT/RETRY_WAIT가 되고, 예약 이후 두 번째 시도는 SUCCESS/SUCCEEDED가 된다.
소량의 정상 환경에서 대략10~15초가 걸리며 배치/부하에 따라 더 기다릴 수 있다. send API를 직접 누를 필요는 없다.

DBeaver:

```sql
SELECT r.delivery_id,r.receipt_count,
       (SELECT count(*) FROM demo_receiver.business_effects b
        WHERE b.delivery_id=r.delivery_id) AS business_count
FROM demo_receiver.receipts r
WHERE r.payload->>'eventId'='receiver-once-001';
```

각 전달의 receipt_count=2/business_count=1을 확인한다. 같은 type의 수신처가 여러 개면 전달 ID마다 별도 행이 생기는 것이 정상이다.
기존 성공한 eventId를 반복 접수하면 새 전달이 생기지 않으므로 새 실습마다 eventId를 바꾼다.
업무 효과는 합성 업무 장부 한 건 저장이다. 실제 주문/결제 API를 호출하는 데까지 한 번 처리를 보장하는 것은 아니다.

## 9. 서명 검증

FlowHook과 수신기를 모두 새 코드로 재시작한다. 첫 실행에서 프로젝트 .local의 공유키를 만들고 두 프로세스가 함께 사용한다.
Swagger의 이벤트 접수 방식은 그대로이며 송신기가 매 시도 직전에 시각/서명 헤더를 붙인다.
본문·전달 ID·시각이 바뀌거나 요청이5분 범위를 벗어나면 수신기는401을 반환하고 수신/업무 기록을 남기지 않는다.
직접 서명 없는 curl은 이제401이 정상이다. 인증 헤더 오류도401이며, 유효한 서명 뒤 JSON 오류는400으로 구분한다.
서명 값/키를 복사하거나 로그에서 확인할 필요가 없다. 기존 delay 실습의 TIMEOUT→재시도 SUCCESS/수신2·업무1도 서명을 검증한 뒤 유지된다.
규약·설정·검증 테스트는 [서명 안내](WEBHOOK_SIGNATURE.md)를 따른다.
