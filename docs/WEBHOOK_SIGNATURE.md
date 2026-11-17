# 로컬 웹훅 서명

FlowHook과 데모 수신기가 공유키로 요청을 검증한다. 서명 검증이 성공해야 수신기의 본문 로그·실패 모드·수신 기록·업무 처리가 실행된다.
기존 멱등 처리는 그대로 유지한다. HTTP 재전송은 허용하며 같은 전달의 업무만 중복 반영하지 않는다.

## 실행과 키

기존 FlowHook과 수신기를 모두 종료하고 새 코드로 실행한다:

```bash
./scripts/receiver.sh
```

FlowHook은 기존 IntelliJ local 설정이나 `SPRING_PROFILES_ACTIVE=local ./scripts/backend.sh`로 실행한다.
별도 키 설정이 없으면 첫 프로세스가 프로젝트 루트의 `.local/webhook-signing.key`를 생성하고 두 프로세스가 같은 파일을 사용한다.
32바이트 난수의 Base64이며 macOS/Linux에서 소유자만 읽고 쓸 수 있는600 권한으로 생성한다. 파일 잠금으로 동시 첫 실행도 같은 키를 얻는다.
루트/backend 작업 디렉터리 차이는 프로젝트 루트 탐색으로 해결한다. `.local/`은 Git에서 제외한다.
키 내용은 출력하거나 문서/소스에 붙여 넣지 않는다. 기존 파일을 임의로 삭제/교체하면 실행 중인 상대의 키와 달라질 수 있다.

명시적 설정이 필요하면 두 프로세스에 같은 `FLOWHOOK_SIGNING_KEY`(32바이트의 Base64)를 환경변수 또는 프로젝트 .env 속성으로 제공한다.
`FLOWHOOK_SIGNING_KEY_FILE` 환경변수로 다른 파일 경로를 지정할 수도 있다. 명시적 키가 우선이며 파일 경로는 절대 경로가 권장된다.
잘못된 키/파일 권한은 로컬 기능이 켜진 앱 또는 수신기 시작 실패로 처리하며, 무서명으로 자동 우회하지 않는다.
이 경로/키 설정은 프로젝트 로컬 기능용이다. 계정 로그인/API 인증 설정을 변경하지 않는다.

## 프로토콜 v1

| 헤더 | 형식 |
| --- | --- |
| X-FlowHook-Delivery-Id | 기존 전달 ID. 재시도에도 동일 |
| X-FlowHook-Timestamp | 전송 시각의 Unix 초. 음수/소수/공백/앞0 없음 |
| X-FlowHook-Signature | `v1=` 다음 소문자16진수64자리 |

각 헤더는 정확히 한 개여야 한다. 전달 ID는1~128자이며 영문/숫자로 시작하고 이후 영문/숫자/점/밑줄/콜론/하이픈을 허용한다.
서명 입력은 아래 접두사의 UTF-8 바이트와 **실제 HTTP 본문 바이트**를 이어 붙인 값이다. 각 줄바꿈은 LF 한 바이트다:

```text
v1\n<timestamp>\n<deliveryId>\n<raw body bytes>
```

송신기는 매 HTTP 시도 직전에 현재 시각으로 HMAC-SHA256을 다시 계산한다. 같은 초에 보낸 같은 전달은 같은 서명이 될 수 있다.
JSON을 재파싱하거나 공백을 정규화한 뒤 서명하지 않는다. 수신기도 파싱/DB 처리 전에 같은 원본 바이트로 검증한다.
서명은 암호화가 아니므로 본문을 숨기지 않는다.

수신 허용 시각은 수신기 현재 시각 기준 ±300초(양쪽 경계 포함)다. 과거/미래 초과·형식 오류·변조·다른 키·중복/누락 인증 헤더는401/invalid_signature다.
본문 크기413, Content-Type415, 경로404/메서드405 같은 HTTP 입력 제한은 별도로 적용한다. 서명이 유효해도 JSON 객체/UTF-8 오류는400이다.
MAC은32바이트로 검증하며 MessageDigest.isEqual로 비교한다. 키나 서명 값은 로그에 넣지 않고 실패 응답도 구체적인 키 정보를 노출하지 않는다.

## 확인

기존 [자동 전송/중복 처리 실습](SEND_ONCE.md)의 이벤트를 새 eventId로 접수하면 FlowHook이 헤더를 자동으로 붙인다. Swagger에서 서명키나 헤더를 직접 입력할 필요가 없다.
직접 curl로 서명 없이 수신기에 보내면401이 정상이다. 이때 수신 기록/업무 장부/본문 로그가 늘지 않는다.
서명 변조·오래된 요청·잘못된 키는 아래 테스트에서 확인한다:

```bash
./scripts/backend.sh test receiverCheck assemble
```

JUnit은 독립 Python HMAC 벡터,5분 경계,본문/ID/시각/키 변조,overflow/형식/중복 헤더,미인가 요청의 DB/로그 차단을 검증한다.
기존 자동 재시도/수신자 멱등 처리도 서명을 붙여 함께 검증한다. 테스트는 합성 키와 임시 경로만 사용한다.

## 범위

공유키는 로컬 데모 전체에 하나다. endpoint별 키 발급/암호화 저장/교체 UI,사용자 로그인/API 키 인증,외부 공개 서비스는 아직 아니다.
시계 오차가5분을 넘으면 정상 요청도 거절되므로 호스트 시각이 맞아야 한다.
5분 이내 유효한 요청의 재전송은 허용하며 수신자 멱등 처리가 중복 업무를 막는다. 전송 ID 이력 삭제 후의 재생이나 키 유출까지 방어하는 기능은 아니다.
현재 서명 범위는 전달 ID/시각/본문이며 테스트용 URL mode나 HTTP 메서드/경로는 서명에 넣지 않는다. 허용 URL/POST 정책은 별도 경계에서 제한한다.

참고: [Java21 Mac/HmacSHA256](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/javax/crypto/Mac.html), [MessageDigest.isEqual](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/security/MessageDigest.html#isEqual(byte%5B%5D,byte%5B%5D)). 프로토콜 v1은 이 프로젝트의 로컬 규약이다.
