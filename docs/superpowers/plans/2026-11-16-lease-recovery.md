# 로컬 lease와 중단 복구

사용자 승인: 처리 도중 서버가 중단돼 PROCESSING으로 남은 전송을 기한 이후 복구한다. 현재 local worker/최대5회/DB 밖 HTTP 구조를 유지한다.

- V5: deliveries.lease_token UUID/lease_expires_at, delivery_attempts.recovery_reason. 기존 PROCESSING에는 전환 시점+30초의 lease를 부여하고 기존 상태/이력/소진 횟수는 보존한다. 이전 버전 앱을 모두 중지한 뒤 업그레이드한다.
- claim: 실제 한 건 처리 직전 UUID 소유권과 DB 시각 기준30초 lease를 저장하고 시도 횟수를 소진한다.
- finish: delivery 행을 먼저 잠그고 token/시도/PROCESSING/기한 내 여부를 확인한다. 이후 attempt와 delivery를 같은 트랜잭션으로 갱신하며 lease를 비운다. 만료/이전 소유자는409, 이력 변경 없음.
- recoverExpired: project1/정규 로컬 대상의 만료 PROCESSING 최대10건을 FOR UPDATE SKIP LOCKED로 잠근다. 중지된 endpoint도 상태 정리는 한다. 미완료 현재 attempt는 UNKNOWN/finished_at/recovery_reason=LEASE_EXPIRED로 닫는다. 이미 소진된 횟수를 유지하고 기존 backoff로 RETRY_WAIT 또는 횟수5에서 DEAD; lease를 비운다.
- worker: 복구 tick을 전송 tick과 분리한다. scheduler2개 스레드로 순차 송신이 길어져도1초 복구 tick이 실행되게 한다. 조건은 기존local+두flags를 유지한다.
- tests: 기한 전 보류/만료 복구/5회 종료/이전 결과 거절/중지 목적지 상태 정리/복구 경쟁/롤백/V4→V5 보존, 실제 scheduler, 임시 DB의 실제 앱 kill/restart를 검증한다.
- docs: STATUS/progress와 실습 안내 갱신, 중복 HTTP 가능성과 구버전 혼용 금지·복구 시간 목표의 한계를 명시한다.

검증은 격리된 테스트 DB에서 수행한다. 사용자 서버를 강제 종료하거나 사용자 전송 데이터를 직접 실행하지 않는다. 커밋/푸시는 하지 않는다. heartbeat/외부 송신/수신자 멱등 처리는 범위 밖이다.

완료: V5/lease/소유권 검사/회수/독립 스케줄러/목록 필드/문서 반영. 전체103개 테스트와 assemble 성공. 별도 코드 검토 및 지적된 전송 지연 중 회수 테스트 완료. 임시 DB와 실제 패키징 JVM을 전송 도중 SIGKILL한 뒤 재시작해30초 lease 만료 UNKNOWN 종료→시도2 SUCCESS 확인. 사용자 DB·서버는 수정/종료하지 않았다. 커밋·푸시·원격 CI 미실행.
