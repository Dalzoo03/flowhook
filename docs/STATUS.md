# FlowHook 진행 현황

최종 갱신: 2026-11-17. 현재 구현 상태는 이 문서에, 작업별 근거와 다룬 개념은 docs/progress/에 기록한다.

## 완료한 작업

- [x] Java 21 / Spring Boot / Gradle 기본 구성
- [x] 상태 확인 API, 전달 대상 등록·목록 조회 API
- [x] 입력 검증, 프로젝트 1 제한, 전달 대상 10개 제한
- [x] PostgreSQL JPA 저장·조회 (이전 JDBC에서 전환)
- [x] 등록 트랜잭션과 프로젝트별 DB 잠금
- [x] 별도 PostgreSQL 컨테이너 기반 테스트 147개
- [x] 로컬 JAR에서 실제 DB 데이터 조회 확인
- [x] Docker·Compose 설정 파일, GitHub PostgreSQL 테스트·빌드 CI 파일
- [x] GitHub에서 PostgreSQL 테스트·빌드 CI 실행 성공 확인
- [x] Flyway V1 및 기존 DB baseline 도입, 기존 데이터 보존 확인
- [x] Flyway V2: events·deliveries 테이블
- [x] 로컬 프로젝트 1 이벤트 접수·대상별 전달 대기 원자적 저장
- [x] 중복·충돌 판정, 대상 URL·payload 고정, JSON·크기·한도 검증
- [x] Swagger UI: 이벤트 예시·응답 코드·전달 대상 API 실행
- [x] 독립 Java 21 데모 수신 서버: 루프백 9090, 정상·실패·지연 모드
- [x] 수신 서버의 실제 HTTP 자체 검증 및 CI receiver-test 구성
- [x] Flyway V3 전송 시도, local 프로필의 수동 1회 송신·목록 API
- [x] 선점/HTTP/결과 저장 분리, 타임아웃·응답 제한·기본 모드 차단
- [x] 사용자 Swagger 실습: 전달 ID 1의 SUCCESS·HTTP 200 응답 확인
- [x] 사용자 Swagger 실습: HTTP 500과 5006ms TIMEOUT 응답 확인

- [x] Flyway V4: 시도 번호·누적 횟수·재시도 예약 시각, 기존 상태 유지
- [x] 실패 분류·최대 5회·backoff/jitter·429 Retry-After와 예약 시각 이후 수동 실행
- [x] 사용자 Swagger 실습: 재시도 횟수 증가와 5회 실패 후 DEAD 확인
- [x] Spring Data JPA Entity/Repository 전환, 조건부 선점은 JPA native SQL 유지
- [x] 이벤트 요청을 @Valid @RequestBody DTO로 정리, 기존 입력 검사 유지
- [x] local 전용 자동 worker: PENDING/due RETRY_WAIT 자동 전송·대상 제외·최대5회 종료
- [x] Flyway V5: 30초 lease·UUID 소유권·만료 UNKNOWN 회수·예산에 따른 재예약/DEAD
- [x] 수신기 DB 멱등 처리: 같은 전달의 동시/재시작 중복에도 업무 장부 한 건
- [x] 커밋 후 응답 지연→FlowHook TIMEOUT→자동 재시도 SUCCESS, 수신2/업무1 검증
- [x] 로컬 HMAC-SHA256 서명: 본문/ID/시각 검증·5분 범위·미인가 요청 DB/로그 차단
- [x] 비공개 로컬 공유키 자동 생성·동시 첫 실행/수신기 재시작 검증

## 남은 작업

- [ ] 앱 Docker 이미지 빌드·실행 확인
- [ ] 프로젝트 생성·사용자 인증·인가
- [ ] API 키 인증을 포함한 전체 이벤트 접수 명세 완성
- [ ] endpoint별 키 발급·암호화 저장·키 교체·외부 송신 확대·외부 업무 효과의 중복 방지

현재는 이벤트와 대상별 PENDING 전달을 한 트랜잭션에 저장한다. 동일 입력 재접수는 200, 충돌은 409다.
실습 시 대상 수 0으로 최초 접수한 이벤트는 대상 등록 후 재접수해도 추가 전달을 만들지 않는다. 새 eventId로 새 이벤트를 접수해야 한다.
local 프로필에서 worker가 PENDING 또는 예약 시각이 지난 RETRY_WAIT를 정규 127.0.0.1 데모 수신기로 자동 전송한다. 일반 외부 URL은 송신하지 않는다.
기존 대기/예약 작업도 local 재시작 이후 처리한다. 수동 실습은 worker-enabled=false로 끈다.
자동 worker가 한 배치 최대10건을 순차 처리하고 배치 완료1초 뒤 다시 조회한다. 프로세스 crash·결과 저장 실패의 PROCESSING은30초 기한 이후 UNKNOWN으로 닫고 남은 횟수에 따라 재예약/DEAD 처리한다.

## 검증 근거

로컬 서명 검증 추가 후 `./scripts/backend.sh test receiverCheck assemble` 성공. JUnit147개, 실패·오류·스킵0개와 기존 수신기 자체 검증 통과.
독립 HMAC 벡터·5분 경계·헤더/본문 변조·다른 키·미인가 요청 로그/DB 차단·두 JVM 동시 최초 키 생성을 확인했다. 임시 환경의 실제 수신기/패키징 FlowHook이 생성 키를 공유해 전송 성공했고 수신기 재시작 후 검증도 유지됐다.
실제 FlowHook 자동 worker→DB 수신기 지연 응답에서 TIMEOUT 후 재시도 SUCCESS, 수신2/업무1을 확인했다. 실행 스크립트의 별도 JVM 재시작 후에도 중복 판정이 유지됐다. 사용자 DB의 demo_receiver 스키마는 다음 수신기 실행 때 생성된다.
격리된 실제 JVM을 HTTP 도중 SIGKILL(-9) 후 재시작해 30초 만료 회수→UNKNOWN/LEASE_EXPIRED→동일 전달의 시도2 SUCCESS를 확인했다. 사용자 DB의 V5 적용은 다음 앱 재시작 때 수행한다.
이전 단계에서 local 프로필 JAR의 목록·Swagger를 확인했다. 당시 로컬 Flyway V4 성공 및 기존 DEAD 3건·SUCCEEDED 1건·PENDING 1건을 확인했다. V3→V4 데이터 보존은 별도 PostgreSQL 마이그레이션 테스트에서도 검증했다. 사용자 데이터의 전달을 임의로 실행하지 않았다.
패키징한 앱에서 기존 DB의 ID 1을 GET API로 조회했다. 상세 근거는 [DB 연결 기록](progress/003-postgresql.md)에 있다.
GitHub CI는 `test assemble`을 실행한다. Testcontainers가 테스트용 PostgreSQL을 생성하므로 로컬 DB나 GitHub의 DB 비밀번호 설정을 사용하지 않는다. 실행 결과는 Actions 탭에서 확인한다.
Flyway 변경 커밋 `df31b9e`의 [CI 성공 결과](https://github.com/Dalzoo03/flowhook/actions/runs/42765182313)를 확인했다.
이벤트 접수 커밋 `183b873`의 [GitHub CI](https://github.com/Dalzoo03/flowhook/actions/runs/42768671610)도 성공했다.
Swagger 커밋 `761ac99`의 [GitHub CI](https://github.com/Dalzoo03/flowhook/actions/runs/42770860265)도 성공했다.
수신 서버 커밋 `848c7b4`의 [GitHub CI](https://github.com/Dalzoo03/flowhook/actions/runs/42774000883)에서 receiver-test와 기존 백엔드 테스트·빌드가 모두 성공했다.
JPA 전환 패키징 앱의 기존 DB 스키마 검증과 health/endpoints/deliveries/OpenAPI 조회 성공, 사용자 테이블 4개의 변경 없음도 확인했다.
실제 송신 커밋 `9afe4d5`의 [GitHub CI](https://github.com/Dalzoo03/flowhook/actions/runs/43610025906)도 두 작업 모두 성공했다.

## 작업 기록

- [001 기본 골격](progress/001-health-api.md)
- [002 전달 대상 등록·조회](progress/002-endpoints.md)
- [003 PostgreSQL 연결](progress/003-postgresql.md)
- [004 CI·Flyway 도입과 학습 기록](progress/004-ci-flyway.md)
- [005 이벤트 접수·원자적 저장·중복 처리](progress/005-event-ingestion.md)
- [006 Swagger UI](progress/006-swagger.md)
- [007 데모 수신 서버](progress/007-demo-receiver.md)
- [008 로컬 실제 전송 한 번](progress/008-single-delivery.md)
- [009 재시도 예약](progress/009-retry-reservation.md)
- [010 JPA와 요청 DTO 정리](progress/010-jpa-request.md)
- [011 로컬 자동 worker](progress/011-local-worker.md)
- [012 처리 기한·중단 복구](progress/012-lease-recovery.md)
- [013 오늘 작업 Git 반영](progress/013-git-sync.md)
- [014 수신자 멱등 처리](progress/014-receiver-idempotency.md)
- [015 로컬 웹훅 서명](progress/015-webhook-signature.md)
- [016 수신·서명 Git 반영](progress/016-signature-git-sync.md)

## 변경 이력 확인

- **Commits:** 최초 기준점 이후 CI·Flyway 등 실제 작업 단위로 남긴 변경 이력. 최초 기준점에는 JDBC까지 포함되어 있다.
- **Actions:** 자동 빌드 실행·성공·실패 기록.
- **이 문서:** 기능별 완료 상태. 작업을 끝낼 때 근거와 함께 갱신한다.
- **docs/progress/:** 각 작업의 변경 이유·검증·제한 사항.

이후 작업은 브랜치와 커밋·PR로 기록한다. 로컬 수정은 커밋하고 push하기 전까지 GitHub에 보이지 않는다.

## 제한

프로젝트1/로컬 공유키 서명만 지원한다. 사용자 로그인/API키 인증·일반 외부 송신은 없다. 동일 URL 등록은 허용한다.
재시도 가능 실패는 RETRY_WAIT로 예약하고 최초 포함 5회 실패하면 DEAD로 종료한다. local worker가 자동 실행하며 만료된 PROCESSING은 회수한다. 기존 DEAD/SUCCEEDED는 되살리지 않는다.
V5 적용 전 구버전 FlowHook을 모두 중지한다. 중복 HTTP 가능성은 남지만 데모 수신기의 DB 업무 장부는 전달 ID별 한 번만 저장한다. 외부 API 업무 효과는 별도 설계가 필요하다.
이전 lease 단계는 로컬 테스트103개와 빌드·격리된 실제 중단 복구 검증을 완료했다. 2026-11-16 사용자 요청으로 오늘 변경을 main에 커밋·푸시했다. 기능 커밋 `1dda9de`의 [GitHub CI](https://github.com/Dalzoo03/flowhook/actions/runs/43890510044)에서 백엔드 테스트·빌드와 수신기 검증 모두 성공했다. 상세는 [Git 반영 기록](progress/013-git-sync.md)에 있다.
2026-11-17 사용자 요청으로 수신기 멱등 처리/실행 스크립트/CI 구성/로컬 서명 변경을 GitHub에 반영한다. 로컬147개 테스트·수신기 자체 검사·빌드와 실제 실행 검증을 완료했으며 원격 결과는 [서명 Git 반영 기록](progress/016-signature-git-sync.md)에 기록한다.
10개 제한은 API의 잠금 규칙에 의존하므로 수동 INSERT로 우회할 수 있다.
비밀번호 파일 `.env`, 빌드 산출물, pi 실행 기록은 Git 추적에서 제외한다.

서명 적용 때 기존 FlowHook과 수신기를 모두 중지하고 새 버전으로 시작한다. 첫 실행의 .local 공유키 파일은 Git에서 제외하며 내용은 출력하지 않는다.
