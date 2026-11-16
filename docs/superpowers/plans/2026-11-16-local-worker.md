# 로컬 자동 전송 worker

사용자가 승인한 기능: 이벤트 접수 후 PENDING과 실행 시각이 지난 RETRY_WAIT를 자동 전송한다. 이전 재시도 정책과 전송 코드를 재사용한다.

- DeliveryWorkerConfiguration: local 프로필, worker-enabled=true와 local-receiver-enabled=true에서만 scheduling 활성화/worker 생성.
- DeliveryWorker: fixedDelay 1초(앞선 배치 완료 후), 최대 10건 순차 처리. 기존 sendOnce 호출. 경쟁 404/409는 건너뛰고 저장 실패는 기록하며 다음 작업을 처리한다. DB 조회 실패도 다음 tick에 다시 조회한다.
- DeliveryTransactions.findReady: 프로젝트1·활성 endpoint·잔여 시도·PENDING/due RETRY_WAIT·정확한 로컬 대상 URL 4종 필터 후 최대10 ID. 후보 조회 트랜잭션 종료 후 각 작업을 기존 원자적 claim으로 선점한다. 후보 전체를 선점해 HTTP 대기를 쌓지 않는다.
- LocalReceiverPolicy: worker가 조회에 쓰는 정규 URL 목록 제공. 송신 시 기존 guard도 유지한다.
- application-local.yaml: worker 활성화. 다른 프로필 기본 꺼짐. 기존 local DB의 실행 가능한 데모 작업은 다음 재시작부터 자동 실행될 수 있다.
- 테스트: 실제 스케줄러+HTTP 수신기+별도 PG에서 자동 성공/재시도, 미래 예약·중지·다른 프로젝트·금지 URL·종료/불명 상태 제외, 5회 종료, 일부 저장 실패 뒤 다음 작업 처리, 활성화 조건을 검증한다. 기존 전송 경쟁 테스트도 유지한다.
- 문서: 상태/진행 기록, Swagger·실습 가이드에 자동 실행과 수동 모드 선택 방법 반영.

회수·lease·프로세스 종료 복구·외부 송신·인증은 범위 밖이다. 사용자 DB 작업을 직접 실행해 검증하지 않고 테스트 전용 DB에서 검증한다. 커밋/푸시 없음.

완료: 위 구현/문서, 실제 스케줄러·HTTP·별도 PG의 worker6개/조건3개와 기존84개 총93개 및 assemble 성공. 별도 읽기 코드 검토 완료. 사용자 DB의 작업은 실행하지 않았으며 사용자 Swagger 실습은 다음 단계다. 커밋/푸시/원격 CI 미실행.
