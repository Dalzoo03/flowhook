# 011 — 로컬 자동 전송 worker

기록일: 2026-11-16. 사용자가 이벤트 접수 이후 자동 전송·예약 재시도를 실행하는 worker 구현을 승인했다. lease 회수·장애 복구는 이번 범위 밖이다.

## 구현

- DeliveryWorkerConfiguration은 local 프로필과 worker-enabled=true/local-receiver-enabled=true가 모두 맞을 때만 worker와 scheduling을 활성화한다. application-local.yaml에 worker-enabled=true를 추가했다.
- DeliveryWorker.runOnce는 최초1초 뒤 시작하고 이전 배치 완료1초 뒤 다시 실행한다. 최대10 ID를 한 번에 조회해 순차 처리하며 기존 DeliveryService.sendOnce를 재사용한다.
- 후보 SQL은 프로젝트1·활성 endpoint·잔여 횟수·PENDING/due RETRY_WAIT·정규 로컬 URL4종을 LIMIT 전에 필터링한다. 금지 URL이 앞에 많이 있어도 정상 작업을 굶기지 않는다.
- 후보 조회는 소유권이 아니다. 각 작업은 HTTP 직전에 기존 claim으로 선점한다. 조회 트랜잭션은 이미 종료됐고 HTTP는 기존처럼 트랜잭션 밖이다.
- 수동 실행 또는 다른 worker와 경쟁한404/409는 건너뛴다. 개별 처리 실패는 다음 작업을 막지 않고, 조회 실패는 다음 tick에서 재조회한다. 상태·횟수를 임의로 초기화하지 않는다.
- 경고에는 작업 ID/예외 종류만 기록하며 payload·URL·DB 예외 상세는 넣지 않는다.
- 기존 수동 API는 유지한다. 수동 실습을 계속하려면 --flowhook.delivery.worker-enabled=false로 worker만 끈다.
- local 재시작 후에는 기존 데모 목적지의 실행 가능한 작업도 자동 처리한다. DEAD/SUCCEEDED/PROCESSING은 되살리지 않는다. 외부 송신 대상은 자동 실행하지 않는다.

## 검증

worker 구현 전 실제 스케줄러·별도 PostgreSQL·실제 HTTP 수신기 테스트가 PENDING에서 바뀌지 않아 실패하는 것을 확인했다. 구현 후 자동 성공이 확인됐다.
확장된 worker 테스트는 아래 동작을 확인한다:

- 수신처 등록 API와 이벤트 접수 API만 호출해 SUCCEEDED/시도1/실제 HTTP1회에 도달한다. send API는 호출하지 않는다.
- 미래 예약·중지 대상·다른 프로젝트·DEAD/SUCCEEDED/PROCESSING 제외. 금지 URL12개 뒤의 정상 작업도 실행한다.
- 첫 실패 예약 → 미래 예약 유지 → due 변경 후 자동 성공, 이전 실패/성공 이력과 시도 번호 보존.
- 기존 DB에 저장된 due 재시도(2회 소진)에서 시작해 횟수3으로 성공한다. 이 테스트는 실제 프로세스 재시작 검증은 아니다.
- 지속 실패5회→DEAD, 다음 tick에서 다른 작업을 보내도 횟수5 유지.
- HTTP 후 저장 실패 시 PROCESSING/UNKNOWN 유지, 같은 배치의 다음 작업 성공.
- local 밖, worker flag 없음, local 수신기 비활성 조건에서 worker bean이 생성되지 않는다.

최종 `./scripts/backend.sh test assemble` 성공. 총93개, 실패·오류·스킵0개: 기존84개 + worker 통합6개 + 활성화 조건3개.
확장 테스트의 NULL 조회는 JdbcClient.single()이 NULL을 허용하지 않아 처음 실패했다. SQL의 IS NULL 결과를 직접 검증하도록 테스트 조회를 수정했으며 성공 조건은 그대로 유지했다. 테스트는 격리된 PostgreSQL/수신기를 사용하며 사용자의 실제 DB 작업은 실행하지 않았다.
별도 읽기 코드 검토에서 중요한 결함이 발견되지 않았다. 직접적인 batch 상한/순차성, 조회 장애 회복, worker 대 수동 실행 경쟁의 별도 테스트는 없고 공통 claim 경쟁은 기존 테스트로 검증한다.

## 학습

@Scheduled(fixedDelay=1000)은 앞선 실행이 끝난 뒤1초 대기한다는 의미다. 정확히 매초 완료나 전송을 보장하는 의미가 아니다.
worker는 DB의 대기열을 polling한다. 별도 Kafka/Redis나 @Async 실행 풀을 이번 단계에 추가하지 않았다.
후보 조회와 선점은 다르며, 실제 실행 권한은 조건부 UPDATE가 확보한다.
작은 실습: Swagger에서 새 이벤트를 접수하고 send를 누르지 않은 채 GET deliveries만 실행해 attemptCount와 상태가 변하는지 확인한다.

## 제한·다음 작업

서버가 죽거나 결과 저장에 실패한 PROCESSING/UNKNOWN의 자동 회수는 미구현이다. 타임아웃 뒤 상대가 이미 받았을 가능성과 중복 HTTP 가능성도 남아 있다.
순차 최대10건이므로 느린 수신기가 뒤 작업을 지연시킬 수 있다. 다중 인스턴스 분산 처리/공정성/처리량 튜닝은 아직 다루지 않았다.
기본 프로필에서는 worker가 없으며 정규 로컬 URL만 지원한다. 인증·서명·외부 송신도 후속이다.
다음은 사용자 자동 전송 실습 후 lease/중단 복구를 작은 범위로 설계하는 단계다. 커밋·푸시·원격 CI는 실행하지 않았다.
