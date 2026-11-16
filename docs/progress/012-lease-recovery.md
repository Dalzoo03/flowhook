# 012 — 처리 기한과 중단 복구

기록일: 2026-11-16. 사용자 승인: 전송 중 FlowHook이 중단돼 PROCESSING으로 남은 작업을 기한 이후 복구한다.

## 구현과 이유

- Flyway V5가 delivery의 lease_token(UUID)/lease_expires_at, attempt의 recovery_reason을 추가한다. 기존 상태·횟수·시각·이력은 유지한다. 구버전 PROCESSING에는 마이그레이션 시점부터30초의 기한을 부여한다.
- 실제 선점 시 DB 시각 기준30초 lease와 매번 새로운 UUID를 저장한다. 이때 이미 시도 횟수가 소진되므로 crash도 횟수에 포함된다.
- finish는 delivery→attempt 순서로 잠근다. PROCESSING·시도 번호·UUID·유효 기한을 검사한 뒤 결과와 상태를 원자적으로 저장한다. 완료 시 lease를 비우며 만료/이전 소유자는409로 거절하고 이력도 변경하지 않는다.
- recoverExpired는 프로젝트1/정규 로컬 URL의 만료 PROCESSING 최대10건을 FOR UPDATE SKIP LOCKED로 잠근다. 현재 미완료 시도를 UNKNOWN/finished_at/recovery_reason=LEASE_EXPIRED로 닫는다. 기존 소진 횟수를 유지해 backoff RETRY_WAIT 또는5회 DEAD로 끝낸다.
- 중지 endpoint도 상태 정리는 한다. 실제 재전송은 기존 활성 조건에 따라 재개 전까지 보류한다. 외부 URL·다른 프로젝트는 자동 회수하지 않는다.
- 정상 완료와 복구 모두 같은 잠금 순서를 사용한다. 복구 실패 시 attempt 종료와 delivery 변경은 함께 롤백된다.
- worker에1초 fixedDelay 복구 작업을 추가했다. 스케줄러 풀2개로 순차 HTTP 배치와 복구 작업을 분리해 느린 전송 배치가 회수를 막지 않는다.
- 목록에 leaseExpiresAt/recoveryReason을 추가했다. UUID는 내부 처리 권한으로 API에 노출하지 않는다.

## 검증

V5 컬럼 추가 후 기존 finish가 만료된 성공 결과를 받아들이는 실패 테스트를 확인하고, 소유권·기한 검사를 구현했다.
`./scripts/backend.sh test assemble` 성공. 총103개, 실패·오류·스킵0개: 기존93개 + 전송lease7개 + scheduler복구2개 + V4→V5 migration1개.

- 선점 시30초 기한, 미만료 회수 없음, 만료된 결과409·UNKNOWN 보존.
- 중지 대상의 회수·backoff 예약, 재개 후 새 소유권, 이전 소유자 결과 거절, 성공 완료 시 lease 해제.
- 다섯 번째 불명 시도 후DEAD·예산 보존, 두 회수자의 경쟁에서 한 번만 종료.
- 현재 회차라도 잘못된 token은 거절, 예약 저장 실패 시 attempt 종료도 롤백.
- 실제 scheduler가 만료 UNKNOWN을 회수하고 자동 재전송해 성공.
- HTTP 전송을 latch로 막은 상태에서 다른 만료 작업이3초 내 RETRY_WAIT로 회수됨을 확인하고, 전송을 해제한 뒤 두 작업의 성공까지 확인했다. 한 스레드 scheduler로 회귀하면 이 테스트가 실패한다.
- V4→V5에서 기존 PROCESSING에30초 유예, terminal/RETRY_WAIT/예약 시각/횟수/UNKNOWN 이력 보존.
- 별도 읽기 코드 검토에서 중요한 결함은 발견되지 않았다. 검토에서 제안한 느린 배치 중 복구 테스트도 추가했다.

실제 프로세스 강제 종료/재시작 검증도 성공했다. 격리된 PostgreSQL 컨테이너/임시 HTTP 수신기와 패키징 JAR을 사용했다.
1. 수신기에서 첫 POST를 받은 순간 해당 테스트 JVM에 SIGKILL을 보내 종료 코드-9를 확인했다.
2. DB에 PROCESSING/시도1/아직 유효한 lease가 저장돼 있는지 확인했다.
3. 같은 DB로 새로운 JVM을 시작하고 실제30초 기한 만료를 기다렸다. 테스트에서 시각을 앞당기지 않았다.
4. 첫 시도는 UNKNOWN/LEASE_EXPIRED/finished_at 기록, 두 번째 시도는 SUCCESS, delivery는 SUCCEEDED/attemptCount2가 됐다.
5. 두 HTTP의 delivery ID와 payload가 같음을 확인했다. 임시 프로세스·컨테이너·수신기를 모두 정리했다.
사용자의 실제 서버를 종료하거나 실제 DB 작업을 실행하지 않았다. 사용자 DB의 V5 적용은 다음 앱 재시작 때 이루어진다.

## 학습과 실습

lease는 처리 권한의 유효 시간이고, UUID는 현재 소유자 확인 값이다. 기한은 DB 시각으로 계산한다.
소유자 확인은 DB의 늦은 결과 덮어쓰기를 막으며, 이미 보낸 HTTP를 취소하거나 상대의 중복 수신까지 막지는 못한다.
읽을 순서: claim의 lease 부여 → finish의 유효 권한 검사 → recoverExpired의 UNKNOWN 종료와 재예약.
직접 실습은 [전송 안내](../SEND_ONCE.md)의 중단 복구 절차를 따른다. 작은 확인: 만료 복구된 attempt의 outcome/recovery_reason/attempt_no를 조회해 소진 횟수가 초기화되지 않는지 확인한다.

## 제한·다음 작업

local/정규 데모 URL만 지원한다. 처리 기한30초, 여유 자원이 있는 정상 worker/DB에서 만료 후15초 내 정리하는 명세는 확인 목표이며 장애·부하 중의 SLA가 아니다.
heartbeat나 기한 자동 연장은 없다. 과부하·일시 정지로 살아 있는 worker의 기한도 만료될 수 있고, 상대가 이미 수신한 이벤트가 중복 전송될 수 있다.
동시 구버전/신버전 앱 운영은 지원하지 않는다. V5 적용 전 구버전 FlowHook 프로세스를 모두 중지한다. 기존 DEAD/SUCCEEDED를 되살리거나 DB를 초기화하지 않는다.
자동 회수는 worker가 켜진 local에서만 동작한다. 손으로 만든 불일치 데이터(현재 시도 없음/횟수0 PROCESSING 등)의 복구는 지원하지 않는다.
다음 후보는 수신자 측 멱등 처리로 중복 업무 효과를 막는 학습이다. 서명/인증/일반 외부 송신은 아직 없다. 커밋·푸시·원격 CI는 실행하지 않았다.
