# 데모 웹훅 수신 서버

FlowHook의 전송·실패·타임아웃을 실험할 로컬 상대 서버다. Java 21의 기본 HTTP 서버만 사용하며 DB와 외부 의존성이 없다.
FlowHook local 프로필의 수동 1회 송신과 연결할 수 있다. [Swagger 전송 실습](../../docs/SEND_ONCE.md)을 따른다. 자동 worker·재시도는 아직 없다.

## 실행

프로젝트 루트에서:

```bash
./scripts/receiver.sh
```

기본 주소는 `http://127.0.0.1:9090/webhooks`다. 종료는 Ctrl+C.
IntelliJ에서는 `Receiver.java`의 main을 Java 21로 직접 실행해도 된다.
이 폴더는 별도 Gradle 프로젝트가 아니며 Gradle 모듈 가져오기는 필요 없다.
스크립트는 macOS에서 Java 21을 선택한 뒤 Java의 단일 소스 파일 실행 기능을 사용한다.
다른 OS에서는 Java 21 JDK가 PATH에 있어야 한다.

포트·지연 시간을 바꾸려면 위치 인자를 전달한다:

```bash
./scripts/receiver.sh 9091 1000
```

첫 값은 포트, 두 번째는 지연 모드의 밀리초다. 지연은 0~30000ms, 기본 8000ms다.

## 응답 모드

| 요청 | 결과 |
| --- | --- |
| POST /webhooks | 즉시 200 |
| POST /webhooks?mode=success | 즉시 200 |
| POST /webhooks?mode=failure | 즉시 500 |
| POST /webhooks?mode=delay | 설정한 지연 후 200 |

mode는 요청 URL에 속하므로 서로 다른 요청의 모드를 덮어쓰지 않는다.
지원하는 쿼리는 표에 있는 형태뿐이다. 알 수 없거나 중복된 mode는 400이다.
기본 8초 지연은 다음 송신 실습에서 5초 응답 제한을 확인하는 데 사용할 수 있다.

```bash
curl -i http://127.0.0.1:9090/webhooks \
  -H 'Content-Type: application/json' \
  -H 'X-FlowHook-Delivery-Id: demo-1' \
  -d '{"eventId":"order-1001","type":"order.created","data":{"orderId":1001}}'

curl -i 'http://127.0.0.1:9090/webhooks?mode=failure' \
  -H 'Content-Type: application/json' -d '{"orderId":1001}'

curl -i 'http://127.0.0.1:9090/webhooks?mode=delay' \
  -H 'Content-Type: application/json' -d '{"orderId":1001}'
```

서버 터미널에는 모드·본문 크기·Content-Type·전달 ID·본문 일부가 출력된다.
Authorization 등 다른 헤더는 출력하지 않는다. 로그 본문은 4096자까지만 출력하고 줄바꿈을 이스케이프한다.
학습용 합성 데이터로 사용한다.

## 제한과 다음 연결

- 루프백 127.0.0.1에만 바인딩한다. 공개 배포용 서버가 아니다.
- application/json만 받으며 본문은 최대 65536바이트다. 본문은 전달받은 UTF-8 텍스트로 로그에 표시하고 JSON 문법을 별도로 파싱하지 않는다.
- 잘못된 경로 404, 메서드 405, Content-Type 415, 크기 초과 413이다.
- 고정 4개 작업 스레드의 로컬 소량 실습용이다. 부하 실험 전 유한 대기열·거절 정책을 적용해야 한다.
- 수신 내용은 영속 저장하지 않고 서명·업무 중복 처리는 아직 하지 않는다.
- FlowHook은 기본 HTTPS 등록 정책을 유지하며 local 프로필에서만 정확한 http://127.0.0.1:9090/webhooks 및 표의 mode를 예외 허용한다. localhost 표기는 허용하지 않는다.

## 검증

```bash
mkdir -p demo/receiver/build
javac --release 21 -d demo/receiver/build demo/receiver/Receiver.java demo/receiver/ReceiverTest.java
java -cp demo/receiver/build ReceiverTest
```

Java 21 JDK를 사용한다. 테스트는 임시 포트에서 실제 HTTP로 모드·지연·병렬 요청·입력 제한·로그 헤더 제외를 확인한 뒤 서버를 종료한다.
같은 검증이 GitHub CI의 receiver-test 작업에서도 실행된다. PostgreSQL은 필요 없다.
