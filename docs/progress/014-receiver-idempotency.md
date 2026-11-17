# 014 — 수신자 DB 멱등 처리

기록일: 2026-11-17. 사용자 승인: FlowHook의 재전송이 수신자의 업무를 중복 실행하지 않도록 구현한다.

## 변경과 이유

- 기존 Java21 HttpServer를 유지하고 ReceiverStore(JDBC)를 추가했다. 본 서버의 JPA는 그대로다. 조건부 INSERT와 같은 트랜잭션의 업무 기록을 직접 관찰하도록 수신 데모에서만 JDBC를 사용한다.
- 별도 receiver source set과 JDBC/Flyway 런타임, Gradle receiver/receiverCheck 작업을 추가했다. scripts/receiver.sh는 이 작업을 실행한다. Spring 서버를 하나 더 생성한 것이 아니다.
- ReceiverDatabase가 기존 프로젝트 .env/환경변수의 DB 설정을 런타임에서 읽는다. 설정 내용/인증 값은 출력하거나 복사하지 않는다. 독립 테스트 DB는 RECEIVER_DB_URL/USER/PASSWORD로 지정할 수 있다.
- 수신측 Flyway V1은 demo_receiver 스키마의 별도 이력과 receipts/business_effects를 만든다. FlowHook public V5 및 기존 데이터는 변경하지 않는다.
- delivery_id 기본키 INSERT ON CONFLICT로 신규 처리자를 정한다. 새로운 수신 기록과 업무 장부 INSERT를 같은 트랜잭션으로 커밋한다. 실패 시 둘 다 롤백한다.
- 중복은 JSONB로 내용을 비교해 같은 내용이면 receipt_count만 증가하고200/duplicate=true/businessApplied=false, 다르면409다. 큰 숫자 정밀도/JSON 타입/키 순서 비교를 DB에 맡긴다.
- 업무 효과는 합성 이벤트를 업무 장부에 한 건 저장하는 것이다. 외부 결제나 주문 API를 호출하지 않는다.
- 필수 전달 ID 헤더/중복 헤더/64KiB/UTF-8/JSON 객체를 검증한다. DB statement/lock timeout2초, 연결2초/socket5초로 제한하고 저장 실패는503을 반환한다. 예외 상세/DB 인증 정보는 응답/로그에 넣지 않는다.
- failure는 업무 전에500, delay는 새 업무를 커밋한 다음 응답만 지연한다. 이미 커밋한 중복은 지연 없이200으로 응답하므로 응답 유실→재시도 실습이 가능하다.
- 기존 no-DB HTTP 자체 검사 경로는 유지하지만 실제 main 실행은 항상 DB 기반이다.
- CI backend test는 DB 통합 테스트를 포함하고 receiver-test는 Gradle receiverCheck로 의존성을 포함해 기존 HTTP 자체 검사를 실행하도록 갱신했다.

## 실제 검증

기존 수신기가 전달 ID 없는 요청을200으로 받는 실패 테스트를 먼저 확인했다. 헤더/저장 경계를 추가한 뒤 기존 자체 검사를 통과했다.
`./scripts/backend.sh test receiverCheck assemble` 성공. 총118개 JUnit, 실패·오류·스킵0개: 기존103개 + ReceiverIdempotencyTest14개 + FlowHookReceiverIntegrationTest1개. 기존 ReceiverTest 실제 HTTP 자체 검사도 통과했다.

- 동일 전달2회200/업무1, 동시20회 수신/업무1.
- 수신기/Store 재생성 후 중복 판정 유지, 같은 ID 다른 내용409, 숫자/JSON 비교 정확도.
- 신규 업무 커밋 후 클라이언트 타임아웃, 중복 응답은 지연 없이 성공.
- 다른 전달 ID는 같은 이벤트여도 별도 업무, 업무 INSERT 실패 시 수신 기록도 롤백되고 같은 ID 재시도 성공.
- 잘못된 JSON/UTF-8/중복 ID 헤더 거절, failure 모드는 수신/업무 기록 없음.
- 실제 FlowHook 자동 worker가 demo.idempotent/지연 수신기로 전송: TIMEOUT→자동 SUCCESS, attempt_count2, receipt_count2, business_effect1. 전송 API는 직접 호출하지 않았다.
- scripts/receiver.sh를 임시 PostgreSQL 환경에서 실행해 실제 수신 JVM을 종료한 뒤 새 JVM으로 재시작했다. 두 요청의 duplicate false→true, receipt_count2/business_effect1을 확인했다. 스크립트의 클래스패스/설정/수신측 Flyway도 이 과정에서 검증했다. 임시 JVM/DB는 정리했다.
- 별도 코드 검토에서 중요한 결함은 발견되지 않았다. 고정4개 HTTP 스레드가 모두 지연 중이면 중복 요청도 대기할 수 있다는 제한을 문서화했다.
- 사용자 DB 작업을 임의로 전송하거나 초기화하지 않았다. 새 demo_receiver 스키마는 사용자가 수신기를 실행할 때 생성된다.

## 학습과 작은 실습

멱등 처리는 중복 HTTP를 없애는 것이 아니라 같은 전달이 만드는 업무 효과를 중복 반영하지 않는 것이다.
흔한 SELECT EXISTS→INSERT 순서는 동시 요청에 취약하다. DB 기본키/ON CONFLICT로 한 요청만 신규 처리자가 되게 하고 수신 표시와 업무 효과를 같은 트랜잭션으로 커밋한다.
읽을 순서: 수신기 Flyway V1 → ReceiverStore.accept → Receiver.receive의 커밋 후 지연.
작은 실습: 같은 전달 ID/같은 본문을 두 번 보내 receipt_count2/business_count1을 확인하고, 본문을 바꿔409가 되며 장부가 늘지 않는지 확인한다.
전체 Swagger/DB 절차는 [실습 안내](../SEND_ONCE.md)의 8번을 따른다.

## 제한과 다음 작업

로컬 합성 데이터·단일 FlowHook 환경의 전달 ID를 가정한다. 서로 다른 발행자나 DB 초기화 뒤 재사용 ID를 구분하는 namespace 설계는 후속이다. 기존 수신 이력을 삭제하면 중복 방지도 사라진다.
receipt_count는 커밋된 처리/중복 수신 횟수다. 거절·failure·롤백을 포함한 전체 HTTP 횟수는 아니다.
업무가 이 PostgreSQL 트랜잭션 밖의 외부 API라면 같은 원자성을 주장할 수 없다. HTTP 자체의 중복과 스레드 대기는 계속 가능하다.
서명·인증·부하용 대기열 제한·실제 주문 시스템 연동·429/두 번 실패 모드는 이번 범위에 추가하지 않았다.
다음 후보는 웹훅 서명 검증으로 위조/변조 방지를 학습하는 것이다. 커밋·푸시·원격 CI는 실행하지 않았다.
