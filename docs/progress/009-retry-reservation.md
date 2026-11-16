# 009 — 실패 재시도 예약

기록일: 2026-11-16. 사용자 요청: V4와 finish() 변경으로 실패를 DB에 재시도로 예약한다. 자동 worker는 범위 밖이다.

## 변경과 이유

- V4가 deliveries.attempt_count/next_attempt_at, attempts.attempt_no를 추가한다. 기존 시도 수를 backfill하며 상태·이력은 보존한다.
- 기존 UNIQUE(delivery_id)를 UNIQUE(delivery_id,attempt_no)로 바꿔 한 전달의 여러 시도를 저장한다.
- claim은 PENDING 또는 실행 시각이 지난 RETRY_WAIT만 원자적으로 선점하며, 횟수 증가와 새 시도 저장을 같은 트랜잭션에서 처리한다.
- RetryPolicy는 최초 포함 최대 5회, 408/429/5xx/네트워크/타임아웃 재시도, 5/10/20/40초 ±20% jitter를 결정한다.
- 알려진 영구 HTTP 오류·URL_BLOCKED·중단 UNKNOWN은 종료한다. 기존 DEAD는 자동으로 되살리지 않는다.
- 429의 Retry-After 초/RFC1123 날짜를 최대 10분 반영하며 backoff보다 이르게 예약하지 않는다. 날짜 기준은 DB의 실제 finished_at이다.
- finish는 시도 결과와 RETRY_WAIT/다음 시각을 원자적으로 기록하고, 성공·최종 실패에서는 다음 시각을 비운다.
- 목록은 LATERAL로 최신 시도만 조인해 재시도 횟수만큼 전달이 중복 표시되지 않게 했다.
- claim의 시도 번호를 결과 저장에서도 검사해 이전 시도가 최신 시도를 덮어쓰지 못하게 한다.

## 검증

기존 코드에서 재시도 API 테스트가 DEAD로 응답해 실패하는 것을 확인했다.
수정 후 `test assemble`과 총 84개 테스트 통과: 이벤트32, endpoint15, 전송20, 정책14, 마이그레이션3.

- 조기 재시도409/횟수 유지, 2회 실패 뒤 3회 성공, 모든 시도 보존, 목록 한 행·최신 결과.
- 지속 실패5회→DEAD, 6차 실행 거절, 성공·종료 시 다음 시각 없음.
- 같은 due 작업의 경쟁 요청에서 단일 선점, 중지 대상 거절, 이전 완료의 재적용 거절.
- backoff/jitter 범위, 429 초·날짜·잘못된 값·최대 대기·숫자 overflow.
- V3→V4에서 DEAD/SUCCEEDED/PROCESSING/PENDING 상태와 UNKNOWN 이력 보존 및 횟수 backfill, 중복 attempt_no 거절.
- HTTP 후 저장 실패에서는 기존처럼 PROCESSING/UNKNOWN을 유지하며 자동 재송신하지 않는다.

별도 검토에서 429 헤더를 받은 뒤 본문이 끊기면 Retry-After가 유실되는 문제를 발견했다.
실제 HTTP429+Retry-After120+중간에 끊긴 본문 테스트가 실패하는 것을 먼저 확인했다.
sender가 ResponseInfo를 보존하도록 수정해 본문 실패·타임아웃에도 이미 받은 코드와 헤더를 유지한다. 수정 후 120초 예약을 확인했고 검토에서도 확인됐다.
알려진 영구 3xx/4xx는 전송 오류가 동반돼도 재시도하지 않는다.

## 학습

읽을 순서: V4 SQL → RetryPolicy.decide → DeliveryTransactions.finish → claim의 due 조건.
RETRY_WAIT는 Java가 기다리고 있다는 뜻이 아니라 DB에 예약된 상태다. worker가 아직 없으므로 사용자가 실행 시각 이후 수동 호출한다.
직접 실습은 [전송 안내](../SEND_ONCE.md). 기존 실패 대상을 재사용하고 새 이벤트로 전달을 생성한다.

## 아직 하지 않은 것

worker·lease 회수·프로세스 crash 복구·서명·인증·외부 목적지 송신은 미구현이다.
미완료 PROCESSING/UNKNOWN을 수동으로 되돌려 재전송하지 않는다. 타임아웃/네트워크 오류도 상대의 미수신을 증명하지 않으므로 중복 수신 방어는 여전히 필요하다.
기존 1회 실습 API 경로는 유지하되 이제 실행 시각이 지난 RETRY_WAIT도 허용한다.

참고: [RFC9110 Retry-After](https://www.rfc-editor.org/rfc/rfc9110.html#name-retry-after).

## 로컬 적용·기록 상태

로컬 DB flyway_schema_history의 V4 success=true를 확인했다. 기존 DEAD 3건·SUCCEEDED 1건·PENDING 1건이 남아 있다. 사용자 전달을 실행하거나 기존 DEAD를 되살리지 않았다.
기존 상태·이력 보존과 횟수 backfill은 V3→V4 통합 테스트에서도 검증했다.
코드와 문서는 로컬 변경 상태다. AGENTS.md에 따라 별도 요청 없이 커밋·푸시하지 않았고 이번 변경의 원격 CI는 미실행이다. 다음은 사용자 Swagger 확인 후 자동 worker의 작은 범위를 합의하는 단계다.

## 사용자 실습 확인 — 2026-11-16

사용자가 같은 전달의 attemptCount 증가를 확인했고, 최초 포함 5회 실패 후 DEAD를 확인했다고 보고했다. 6차 409의 사용자 수동 확인은 아직 보고되지 않았으며 자동 테스트에서 검증된 상태다.
