# 데모 웹훅 수신 서버

FlowHook의 로컬 상대 서버다. Java21 HttpServer를 유지하고, PostgreSQL의 `demo_receiver` 스키마에 수신 기록과 합성 업무 장부를 저장한다.
동일한 전달 ID와 내용은 여러 번 받아도 업무 장부에는 한 건만 반영한다. 수신기 JVM을 다시 시작해도 DB 기록이 유지된다.

## 실행

Docker Desktop/PostgreSQL을 켜고 프로젝트 루트에서 실행한다. 이전 데모 수신기가 실행 중이면 먼저 종료한다:

```bash
./scripts/receiver.sh
```

기본 주소는 `http://127.0.0.1:9090/webhooks`, 종료는 Ctrl+C다. FlowHook은 기존 local 설정으로 실행한다.
스크립트는 백엔드 Gradle의 receiver 작업으로 JDBC/Flyway 의존성을 포함한다. 새 Spring 앱을 만드는 것은 아니며 별도 Gradle 프로젝트도 아니다.
IntelliJ에서는 backend 프로젝트의 Gradle `receiver` 작업을 실행한다. 예전 `java Receiver.java` 단독 실행 방식은 이제 사용하지 않는다.

기존 프로젝트 `.env`의 PostgreSQL 설정을 수신기 런타임이 읽는다. 내용을 출력하거나 별도 파일에 복사하지 않는다.
기본 URL은 localhost/POSTGRES_PORT(기본5433)/flowhook, 사용자명은 flowhook이다.
SPRING_DATASOURCE_URL/USERNAME/PASSWORD와 POSTGRES_PASSWORD 환경변수/속성도 지원한다.
독립된 DB를 지정할 때는 RECEIVER_DB_URL/USER/PASSWORD를 사용한다. 각 설정은 환경변수→프로젝트 `.env`→기본값 순서다.
`.env`는 Spring에서 사용하던 Java properties 형식이며 shell 명령이나 dotenv 확장 문법을 실행하지 않는다.

첫 실행 때 수신기용 Flyway V1이 별도 스키마/이력을 생성한다. FlowHook의 public 스키마/V5 이력은 변경하지 않는다.
DB를 초기화하거나 수동으로 테이블을 만들 필요가 없다. DB를 사용할 수 없으면 수신기는 시작을 거절한다.

```bash
./scripts/receiver.sh 9091 1000
```

포트와 지연시간(ms)이다. 지연0~30000, 기본8000이다. 포트를 바꾸면 FlowHook의 local-receiver-port도 맞춘다.

## 요청과 멱등 처리

POST `/webhooks`, Content-Type application/json, 최대65536바이트 UTF-8 JSON 객체가 필요하다.
전달 ID·시각·HMAC 서명 헤더가 필수다. [서명 규약과 키 설정](../../docs/WEBHOOK_SIGNATURE.md)을 따른다.
`X-FlowHook-Delivery-Id` 헤더는 하나만 지정한다. 1~128자의 영문/숫자로 시작하고 이후 영문/숫자/점/밑줄/콜론/하이픈을 허용한다.
FlowHook은 이 헤더를 자동으로 보낸다. 같은 이벤트라도 전달 ID가 다르면 별도의 업무로 처리한다.

```bash
curl -i http://127.0.0.1:9090/webhooks \
  -H 'Content-Type: application/json' \
  -H 'X-FlowHook-Delivery-Id: demo-once-001' \
  -d '{"eventId":"order-1001","type":"order.created","data":{"orderId":1001}}'
```

위 curl은 서명을 생략했으므로401로 거절된다. 정상 실습은 FlowHook에서 이벤트를 접수해 자동 서명된 요청을 보낸다.
유효한 서명으로 같은 전달을 다시 보내면200/duplicate=true/businessApplied=false/receiptCount=2다.
최초 요청은 duplicate=false/businessApplied=true/receiptCount=1이다. 같은 ID에 다른 JSON 내용은409다.
키 순서/동등한 숫자 표기는 같은 내용으로 비교하고 큰 정수도 정확하게 구분한다.

수신 측 처리:

1. delivery_id 기본키로 수신 기록 INSERT ON CONFLICT DO NOTHING.
2. 새 전달이면 업무 장부에 INSERT한다. 중복이면 기존 payload와 비교하고 수신 횟수만 증가시킨다.
3. 수신 기록과 업무 장부를 같은 DB 트랜잭션으로 커밋한 뒤 성공 응답을 보낸다.

업무 장부 한 건 추가가 이번 데모의 합성 업무다. 실제 주문 시스템이나 외부 결제를 실행하지 않는다.
DB/업무 저장 실패는503이며 수신 기록도 함께 롤백하므로 다음 요청에서 다시 처리할 수 있다.

## 응답 모드

| 요청 | 결과 |
| --- | --- |
| POST /webhooks 또는 ?mode=success | DB 처리 후200 |
| POST /webhooks?mode=failure | 처리 전에500, 수신/업무 기록 없음 |
| POST /webhooks?mode=delay | 신규 전달의 업무를 커밋한 뒤 응답만 지연; 이미 처리한 중복은 지연 없이200 |

mode는 요청별이다. 알 수 없거나 중복된 mode는400이다.
기본8초 지연에서 FlowHook은5초에 TIMEOUT을 기록하지만 업무는 이미 반영됐다. 이후 자동 재시도는 중복으로 판정돼 성공한다.
소량의 정상 DB 환경에서 확인하는 실습이며, HTTP 작업 스레드4개가 모두 지연 중이면 중복 요청도 큐에서 기다릴 수 있다.

## DBeaver로 확인

수신기를 한 번 실행한 뒤 스키마 목록을 새로고침하면 demo_receiver가 보인다.

```sql
SELECT r.delivery_id, r.receipt_count,
       (SELECT count(*) FROM demo_receiver.business_effects b
        WHERE b.delivery_id=r.delivery_id) AS business_count,
       r.payload
FROM demo_receiver.receipts r
ORDER BY r.received_at DESC;
```

응답을 잃고 재시도한 전달은 receipt_count=2, business_count=1이다.
receipt_count는 커밋된 처리/중복 수신 횟수이며, validation 거절·failure 모드·롤백된 요청을 포함한 전체 HTTP 통계는 아니다.

## 검증

```bash
./scripts/backend.sh test receiverCheck assemble
```

test는 별도 PostgreSQL에서 동시 중복·충돌·롤백·영속성·FlowHook 실제 자동 재시도를 검증한다.
receiverCheck는 DB 없이 임시 HTTP 서버의 모드/헤더/크기/로그 검증을 수행하는 기존 자체 검사다. 이 stateless 테스트 경로를 실제 실행에는 사용하지 않는다.
GitHub test-and-build는 DB 기반 통합 테스트를, receiver-test는 receiverCheck를 실행하도록 구성했다.

## 제한

루프백 전용 합성 데이터 서버다. 로그에 전달 ID/모드/본문 일부를 남기며 Authorization 같은 다른 헤더는 출력하지 않는다.
HMAC 서명을 검증하지만 사용자 로그인/API키 인증과 endpoint별 키 발급·교체는 아직 없다. 전달 ID는 단일 FlowHook 환경에서 유일하다는 가정이며, 서로 다른 발행자/DB 초기화 이후 재사용 ID의 충돌 방지는 후속이다.
수신 이력을 삭제하면 중복 방지도 사라진다. 업무가 같은 PostgreSQL 트랜잭션 밖의 외부 API라면 이 방식만으로 한 번 처리를 보장하지 못한다.
고정4스레드와 무한 요청 대기열은 로컬 소량 실습용이다. 부하 실험 전 별도 제한이 필요하다.

서명 기능을 적용할 때는 FlowHook과 수신기를 모두 새 버전으로 재시작한다. 별도 설정 없이 첫 실행에서 로컬 비밀키 파일을 만들며 Git에는 포함하지 않는다. 미인가 요청은 본문 로그·DB 처리 전에401로 거절한다.
