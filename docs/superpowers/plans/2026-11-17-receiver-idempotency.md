# 수신자 멱등 처리

사용자 승인: 같은 전달이 재전송돼도 수신자의 업무를 한 번만 반영하고 재시작 이후에도 중복 판정을 유지한다.

- 기존 Java21 HttpServer 유지. ReceiverStore는 JDBC로 PostgreSQL 별도 demo_receiver 스키마를 사용한다. FlowHook의 JPA/마이그레이션과 분리한다.
- 수신측 Flyway V1: receipts(delivery_id PK, JSONB payload, receipt_count), business_effects(delivery_id PK/FK, applied_at). 업무 효과는 합성 이벤트를 업무 장부에 추가하는 것으로 한정한다.
- INSERT ON CONFLICT + JSONB 내용 비교. 새로운 전달만 같은 트랜잭션에서 업무 장부에 INSERT한다. 동일 ID/동일 내용은 수신 횟수만 늘리고200/duplicate=true, 다른 내용은409다.
- 처리 가능한 POST에 전달 ID 헤더/JSON/크기 검증. failure는 처리 전에500, delay는 신규 업무 커밋 후 응답을 지연해 응답 유실을 재현한다. 이미 커밋한 중복은 지연 없이200이라 FlowHook의 재시도가 성공한다.
- 실행 스크립트는 Gradle receiver source set으로 JDBC/Flyway 의존성/리소스를 포함한다. 프로젝트 .env는 수신기 런타임에서 기존 DB 설정을 읽되 출력/복사하지 않는다. 환경변수로 테스트 DB를 지정할 수 있다.
- 테스트: 실제 HTTP+별도 PG의 중복/동시성/응답 유실/수신기 재생성/충돌/업무 실패 롤백/입력 검증, 기존 전송 테스트 유지. 전체 backend test와 기존 ReceiverTest 자체 검증도 실행한다. CI receiver-test를 Gradle receiverCheck로 갱신한다.
- 상태/진행/실습 안내를 갱신한다. 사용자 DB 작업을 임의 전송하거나 DB를 초기화하지 않는다. 자동 커밋/푸시 없음.

수신자 서명·인증·실제 주문 시스템 연동·다중 발행자/DB 초기화 이후 ID 충돌 방지는 범위 밖이다. delivery ID가 다른 대상은 별도 업무다. HTTP 중복 자체는 계속 허용된다.

완료: 구현/실행 스크립트/CI 구성/문서 반영, 전체118개 JUnit+receiverCheck+assemble 성공, 실제 worker TIMEOUT→재시도 수신2/업무1 확인. 임시 DB와 scripts/receiver.sh의 두 독립 JVM 재시작 중복 판정 검증 통과. 코드 검토 완료. 사용자 DB 변경/커밋/푸시/원격 CI는 하지 않았다.
