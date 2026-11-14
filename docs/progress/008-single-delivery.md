# 008 — 실제 HTTP 전송 한 번

기록일: 2026-11-15. 요청: FlowHook에서 준비된 데모 수신기로 실제 전송을 연결한다.

## 구현한 범위

- local 프로필에서만 목록·선택 ID의 수동 전송 API를 노출한다. 기존 외부 주소를 자동 전송하는 scheduler는 넣지 않았다.
- LocalReceiverPolicy는 기본 false이며 정확한 127.0.0.1·신뢰된 설정 포트·/webhooks·허용된 mode만 통과한다. 등록 예외와 송신 때 같은 정책을 검사한다.
- 기본 HTTPS 등록 규칙은 유지되지만 현재 실제 송신은 로컬 수신기만 허용한다. redirect·proxy를 사용하지 않는다.
- V3 delivery_attempts에 UNKNOWN 초기 이력, 종료 시각·결과·HTTP 코드·시간·최대 4 KiB 응답 bytes를 기록한다.
- DeliveryTransactions.claim의 조건부 UPDATE와 최초 시도 저장을 커밋한 후 HTTP를 호출한다.
- DeliveryService는 NEVER로 외부 트랜잭션을 거절한다. claim/finish는 별도 빈의 REQUIRES_NEW이므로 self-invocation 함정을 피한다.
- Java HttpClient의 연결 2초·요청 5초, 전체 future 10초 한도를 적용했다. 응답이 4 KiB를 넘으면 구독을 취소한다.
- 수신 결과와 전달 상태는 하나의 finish 트랜잭션에서 함께 저장한다.

## 검증

처음에는 Docker가 꺼져 통합 테스트 초기화에 실패했다. Docker를 켠 뒤 미구현 API에서 404 실패를 확인하고 구현했다.
전체 62개 테스트와 `test assemble` 성공. 전송 테스트 13개, 기존 이벤트 32개, endpoint 15개, Flyway 2개다.

검증한 항목:

- 실제 HTTP POST·고정 payload·전달 ID 헤더, 선점 커밋이 수신기 측 DB 연결에서 보이는지 확인.
- 200/500, 지연 헤더 타임아웃, 응답 헤더 이후 멈춘 본문에도 전체 시간 제한 적용.
- 바이너리 응답 10000바이트에서 4096바이트만 저장하고 잘림 표시.
- 같은 전달 ID 동시 요청에서 실제 HTTP 호출 1번, 재호출·중지 대상 거절.
- 외부/localhost/잘못된 경로·query 차단, redirect 미추적, 다른 프로젝트·없는 작업 거절.
- 기본 모드 HTTP 등록·전송 API 차단, 외부 트랜잭션에서 선점·HTTP 이전 거절.
- 연결 종료 오류와 결과 저장 실패 주입. 저장 실패 시 PROCESSING·UNKNOWN이 남고 자동 재송신하지 않음.

로컬 PostgreSQL을 기존 볼륨으로 켜고 V3를 적용했다. endpoints/events/deliveries 전체를 전후 비교해 보존을 확인했다.
local 프로필 실행 JAR의 목록 GET과 Swagger 전송 경로를 확인했다. 사용자 DB에는 시도나 송신을 만들지 않았고 임시 앱을 종료했다.
별도 읽기 전용 코드 검토에서 중요한 수정 사항은 없었다.
구현 커밋 `9afe4d5`의 [GitHub CI](https://github.com/Dalzoo03/flowhook/actions/runs/43610025906)에서 PostgreSQL 테스트·빌드와 receiver-test가 모두 성공했다.

## 학습과 다음 단계

중심 코드: DeliveryService.sendOnce → DeliveryTransactions.claim → LocalWebhookSender → DeliveryTransactions.finish.
직접 실습은 [전송 안내](../SEND_ONCE.md)의 정상·실패·지연 세 경우를 비교한다.
응답 API의 200과 상대 서버의 httpStatus는 다른 값이다. 로그·DB 결과를 함께 확인한다.

## 아직 구현하지 않은 것

worker scheduler·자동 재시도·lease 회수·서명·인증·외부 목적지 허용 정책은 후속이다.
프로세스 crash 또는 HTTP 후 DB 저장 실패는 PROCESSING과 UNKNOWN을 남길 수 있다.
현재의 한 번 실패 시 DEAD와 시도 UNIQUE 제약은 학습 단계의 임시 계약이며 최종 V1의 5회 재시도 정책이 아니다.
수동으로 상태를 되돌려 재전송하지 말고 다음 단계에서 복구 프로토콜을 설계한다.

## 사용자 실습 확인 — 2026-11-15

Swagger 전달 목록이 HTTP 200과 빈 배열을 반환해 DB를 읽기 전용으로 확인했다.
send-demo-001은 matched_delivery_count=0으로 최초 접수되어 있고 deliveries는 0건이다.
현재는 demo.success 활성 대상이 ID 3·4로 중복 등록되어 있다. 기존 이벤트 재접수는 대상에 재매칭하지 않으므로 새 eventId로 접수해야 전달이 생성된다.
정확한 최초 등록 순서는 테이블 기록만으로 확인하지 못했다. 데이터 수정·삭제·전송은 수행하지 않았다.
현재 대상 그대로 신규 이벤트를 보내면 전달 2건이 생성된다. 단일 수신 확인은 그중 한 건만 전송하며, 중복 대상 정리는 별도 작업이다.

이후 사용자가 제공한 Swagger 캡처로 send-demo-002의 신규 202·대상 수 2·duplicate=false를 확인했다.
전달 목록에 ID 1·2가 PENDING으로 나타났고, 사용자가 ID 1을 수동 전송했다.
전송 응답은 deliveryId=1, attemptId=1, status=SUCCEEDED, outcome=SUCCESS, httpStatus=200, durationMs=94였다.
이는 사용자 화면에 표시된 정상 전송 결과의 확인이며 수신기 터미널 로그를 별도로 관찰한 것은 아니다. ID 2의 이후 상태는 재조회하지 않았다.
