# 로컬 수신기로 전송 한 번 실습

현재 단계는 자동 worker가 아니라 Swagger에서 전달 ID를 골라 한 번 전송하는 실습이다.
기존에 저장된 외부 주소를 자동 호출하지 않는다. 대상은 정확한 루프백 주소로 제한한다.

## 1. 실행

수신 서버:

```bash
./scripts/receiver.sh
```

FlowHook은 IntelliJ 실행 설정의 **Program arguments**에 `--spring.profiles.active=local`을 넣고 재시작한다.
터미널을 사용한다면:

```bash
SPRING_PROFILES_ACTIVE=local ./scripts/backend.sh
```

Docker Desktop과 PostgreSQL도 켜져 있어야 한다. 이번 실습은 호스트 JVM에서 앱과 수신기를 실행한다.
Docker 컨테이너 안의 127.0.0.1은 호스트 수신기를 가리키지 않는다. 컨테이너 간 송신은 후속 구성이다.

기본 프로필에서는 로컬 HTTP 등록 예외와 전송 실습 API가 모두 꺼져 있다.
local 프로필은 `http://127.0.0.1:9090/webhooks`만 송신 허용한다. `localhost`, 다른 경로·포트·외부 HTTPS는 송신하지 않는다.
포트를 바꿔 실습한다면 앱 실행 인자 `--flowhook.delivery.local-receiver-port=9091`과 수신기 포트를 함께 바꾼다.

## 2. Swagger에서 이벤트 만들기

http://localhost:8080/swagger-ui/index.html 을 열고 `전달 대상`의 등록 API를 실행한다. projectId는 1이다.

```json
{
  "name": "로컬 정상 수신기",
  "url": "http://127.0.0.1:9090/webhooks",
  "eventType": "demo.success"
}
```

`이벤트`의 POST /v1/events:

```json
{
  "eventId": "send-demo-001",
  "type": "demo.success",
  "data": {"orderId": 1001}
}
```

기존 order.created 실습 대상과 섞이지 않도록 별도 유형을 사용한다.

## 3. 선택한 전달 전송

1. `전송 실습(로컬)` → GET `/api/projects/1/deliveries`로 최근 최대 100건을 조회한다.
2. 원하는 로컬 targetUrl·PENDING 행의 **id**를 확인한다. 이벤트 접수 응답의 id는 이벤트 ID이며 전달 ID와 다르다.
3. POST `/api/projects/1/deliveries/{deliveryId}/send`에 해당 전달 ID를 넣어 Execute 한다.
4. 수신기 터미널의 POST 본문·X-FlowHook-Delivery-Id를 보고, API 결과의 status/outcome/httpStatus를 확인한다.

정상 결과 예:

```json
{
  "deliveryId": 1,
  "attemptId": 1,
  "status": "SUCCEEDED",
  "outcome": "SUCCESS",
  "httpStatus": 200,
  "durationMs": 10
}
```

숫자는 실제 데이터에 따라 다르다. 같은 전달을 다시 실행하면 409이며 재전송하지 않는다.
API HTTP 200은 '한 번의 시도 처리 완료'다. 상대가 500을 보내면 API는 200과 DEAD/HTTP_ERROR/httpStatus=500을 반환한다.

## 4. 실패와 지연

별도 대상과 새 이벤트를 만들어 실습한다.

| 실험 | 대상 URL | eventType/type | 결과 |
| --- | --- | --- | --- |
| 정상 | http://127.0.0.1:9090/webhooks | demo.success | SUCCEEDED / SUCCESS |
| 상대 500 | http://127.0.0.1:9090/webhooks?mode=failure | demo.failure | DEAD / HTTP_ERROR / 500 |
| 8초 지연 | http://127.0.0.1:9090/webhooks?mode=delay | demo.delay | DEAD / TIMEOUT |

새 시나리오에는 새 eventId를 쓴다. 기존 eventId를 그대로 쓰면 중복 접수 결과가 반환되며 신규 작업은 생기지 않는다.
수신기를 끈 상태에서 전송하면 NETWORK_ERROR로 기록된다.

## 5. DB 이력

```sql
SELECT id, status, target_url FROM public.deliveries ORDER BY id;

SELECT id, delivery_id, started_at, finished_at, outcome, http_status,
       duration_ms, octet_length(response_body), response_truncated
FROM public.delivery_attempts ORDER BY id;
```

응답 본문은 최대 4096바이트의 BYTEA이며 바이너리·NUL도 그대로 저장한다. 초과 수신은 구독을 취소하고 잘림을 기록한다.
연결 제한 2초, 응답 대기 5초, 본문을 포함한 HTTP 작업 전체는 최대 10초다. 전체 API 시간에는 앞뒤 DB 처리 시간도 포함된다.
redirect와 시스템 proxy는 사용하지 않는다. 고정 숫자 IP만 사용해 목적지 DNS 해석도 필요 없다.

## 6. 트랜잭션과 장애 경계

```text
짧은 DB 트랜잭션: PENDING → PROCESSING + UNKNOWN 시도 저장 → 커밋
HTTP 호출: DB 트랜잭션 없이 POST
짧은 DB 트랜잭션: 응답·결과 저장 + SUCCEEDED/DEAD → 커밋
```

동일 ID의 동시 요청은 조건부 UPDATE로 한 요청만 선점한다. 다른 요청은 409다.
현재 attempts의 UNIQUE(delivery_id)는 '전달당 한 번' 실습 범위를 DB에서도 제한한다.
한 번 실패하면 DEAD로 끝내며 자동 재시도 정책은 아직 적용하지 않았다.

**전송 중 프로세스가 죽거나 결과 DB 저장이 실패하면 PROCESSING과 미완료 UNKNOWN 시도가 남을 수 있다.**
상대가 이미 받았을 수도 있으므로 DB를 수동으로 PENDING으로 바꾸거나 무작정 재전송하지 않는다.
다음 단계에서 lease·회수·중복 수신 방어·재시도를 구현한다. 이번 기능이 장애 복구를 완성한 것은 아니다.
이 API는 인증·서명이 없는 로컬 실습용이다. 외부 HTTPS URL 등록이 가능해도 실제 송신은 이 단계에서 차단한다.
