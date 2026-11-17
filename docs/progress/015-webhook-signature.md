# 015 — 로컬 웹훅 HMAC 서명

기록일: 2026-11-17. 사용자 승인: FlowHook이 본문/전달 ID/시각에 서명하고 수신기가 업무 처리 전에 검증한다.

## 구현과 이유

- pure-JDK WebhookSignature가 HMAC-SHA256을 생성/검증한다. v1 LF 접두사/Unix초/전달ID/실제body바이트를 묶으며 검증 비교는 MessageDigest.isEqual을 사용한다.
- LocalWebhookSender는 매 HTTP 시도 직전에 시각과 서명을 계산해 X-FlowHook-Timestamp/Signature를 붙인다. HMAC과 전송에 같은 UTF-8 byte[]를 사용해 직렬화 차이를 막는다.
- 수신기는 최대64KiB 원본 바이트를 읽고, 중복/누락/형식 오류 헤더·잘못된 MAC·±300초 초과를401로 거절한다. 검증 전에는 payload 로그/실패 모드/DB/멱등 처리도 수행하지 않는다.
- 서명은JSON 의미가 아니라 원본바이트를 보호한다. 유효한 서명의 JSON 객체는 이전과 같은 JSONB 멱등 처리로 이어진다.
- LocalWebhookKey가 명시한 Base64 32바이트 키 또는 프로젝트 루트 .local/webhook-signing.key를 사용한다. 키가 없으면 SecureRandom으로 생성한다. OS 파일 잠금과 JVM 내 직렬화, NOFOLLOW_LINKS, macOS/Linux600 권한으로 동시 첫 실행/심볼릭 링크 읽기를 다룬다.
- backend/root 작업 디렉터리에서도 같은 루트 키를 찾고, receiver 클래스패스에 main의 순수JDK 공통 코드를 포함한다. 별도 암호화 라이브러리 의존성은 추가하지 않았다.
- 키는 런타임에만 읽고 코드/문서/로그에 출력하지 않는다. .local은 Git 제외다. 로컬 기능이 꺼진 앱은 키를 만들지 않고, 키가 잘못되면 로컬 기능이 켜진 앱/수신기 시작을 거절한다. 무서명 우회 설정은 없다.
- DB 스키마 변경은 없다. FlowHook public V5와 수신자 Flyway V1/기존 데이터는 유지한다.

## 실제 검증

서명 없는 요청이 업무를 반영하고200을 반환하는 실패 테스트를 먼저 확인한 뒤 검증을 추가했다.
최종 `./scripts/backend.sh test receiverCheck assemble` 성공. 총147개 JUnit, 실패·오류·스킵0개: 기존118개 + 서명20개 + 키4개 + 수신 인증5개. ReceiverTest 실제 HTTP 자체 검사도 통과했다.

- 별도 Python hmac/hashlib가 계산한 고정 벡터와 UTF-8 한글 본문 결과를 비교했다. 송수신이 같은 구현이라 함께 틀리는 문제를 피한다.
- ±300초 경계 허용/±301초 거절, 본문/공백/ID/시각/키 변조,잘못된 형식/overflow/누락 검증.
- 이미 처리한 전달·failure 모드에서도 인증 실패는 DB와 로그를 바꾸지 않는다. 중복 timestamp/signature/ID 헤더를 거절한다.
- 임시 경로의 비공개 키가 유지되고,심볼릭 링크는 읽지 않으며,별도 JVM2개가 키 없는 상태에서 동시에 출발해 같은 키 fingerprint를 얻는지 검증했다. 실제 키 값은 출력하지 않는다.
- 서명한 기존 FlowHook→지연 수신기 통합: TIMEOUT→자동SUCCESS,수신2/업무1 유지. 재시도 서명 계산은 새 시각에서 별도로 확인했다.
- 격리된 DB와 scripts/receiver.sh/패키징 JAR을 실행했다. 임시 키 파일을 자동 생성(600)하고 두 프로세스가 공유해 정상 이벤트를 전송했으며,무서명 요청401/업무 증가 없음,수신기 재시작 후 검증 유지까지 확인했다. 키 내용은 열어보지 않았고 임시 프로세스/DB/키 경로를 정리했다.
- 별도 읽기 검토에서 인증 헤더400/401 일관성,미인가 로그 차단 검증,프로세스 간 동시 최초 생성 테스트를 지적받았다. 모두 반영하고 검토에서 확인됐다.
- 사용자 DB/키/기존 인증 파일은 읽어 출력하거나 변경하지 않았다. 실사용 키는 다음 로컬 앱/수신기 첫 실행에서 생성한다. 커밋·푸시·원격 CI는 하지 않았다.

## 학습과 실습

읽을 순서: WebhookSignature.sign/verify → LocalWebhookSender.send → Receiver.receive의 검증 위치.
HMAC은 비밀키를 아는 쪽이 만든 요청인지/본문이 바뀌었는지 확인한다. 암호화가 아니므로 본문을 숨기지는 않는다.
시각 범위만으로 창 안의 모든 재전송을 막는 것은 아니다. 정상 재시도를 허용하고 중복 업무는 전달ID 멱등 처리가 막는다.
작은 실습: 새 버전 둘 다 실행한 뒤 Swagger의 이벤트 접수는 성공하는지,서명 없는 직접 수신 요청은401이고 DB/본문 로그가 늘지 않는지 확인한다.
설정/헤더/실습은 [서명 안내](../WEBHOOK_SIGNATURE.md)를 따른다. 키/서명을 화면에 복사할 필요는 없다.

## 제한·다음 작업

현재는 로컬 데모 전체에 공유키 하나다. endpoint별 생성·암호화 저장·교체 UI·로그인/API키 인증·외부 공개 송신은 미구현이다.
키는 프로세스 시작 시 읽으며 자동 교체하지 않는다. 키를 임의로 삭제/교체하면 구버전/기존 프로세스와 달라질 수 있다.
URL mode/HTTP 경로·메서드는 서명 범위 밖이며 고정된 로컬 URL/POST 정책이 별도로 제한한다. 공개 배포용 범용 인증 규약으로 주장하지 않는다.
다음 후보는 API 접수 인증 또는 프로젝트 소유권이다. 제품 전체 보안 검증/침투 테스트를 수행한 상태는 아니다.

참고: [Java21 Mac](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/javax/crypto/Mac.html), [MessageDigest.isEqual](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/security/MessageDigest.html#isEqual(byte%5B%5D,byte%5B%5D)).
