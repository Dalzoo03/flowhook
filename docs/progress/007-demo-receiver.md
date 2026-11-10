# 007 — 데모 수신 서버

기록일: 2026-11-10.

요청: FlowHook의 실제 송신을 실습할 상대 서버부터 준비한다.

## 변경과 이유

독립 `demo/receiver/Receiver.java`를 Java 21 기본 HTTP 서버로 구현했다. 단순 수신 실습에 새 Spring 애플리케이션·DB·Gradle 설정을 중복하지 않았다.
루프백 9090에서 POST /webhooks를 받으며 요청 URL에 따라 success=200, failure=500, delay=8초 뒤 200을 반환한다.
포트·지연은 실행 인자로 조정 가능하다. 실행 스크립트는 Java 21로 소스 파일을 실행한다.
본문 크기·Content-Type·메서드·경로·mode를 확인하고 본문 일부와 제한된 헤더를 로그에 남긴다.
CI 경로 필터에 receiver와 스크립트를 추가하고 receiver-test 작업으로 실제 HTTP 검증을 연결했다.

## 검증

서버 골격에서 성공 경로 테스트가 실패하는 것을 확인한 뒤 구현했다.
Java 21 컴파일 및 ReceiverTest 성공. 실제 루프백 HTTP로 200·500·지연 시간·병렬 모드 독립·65536바이트 경계·잘못된 경로/메서드/형식·Authorization 로그 제외를 확인했다.
실행 스크립트에서도 포트 0·짧은 지연으로 시작해 세 모드를 확인하고 임시 서버를 종료했다.
테스트용 DB나 기존 PostgreSQL 데이터는 사용·변경하지 않았다.
커밋 `848c7b4`의 [GitHub CI](https://github.com/Dalzoo03/flowhook/actions/runs/42774000883)에서 receiver-test와 기존 PostgreSQL test-and-build가 모두 성공했다.

## 범위와 학습

이번에는 수신 서버만 구현했다. worker·실제 전송·attempt 기록·재시도는 다음 단계다.
FlowHook의 HTTPS 전용 등록 정책은 그대로다. 다음에 localhost HTTP 수신기를 연결할 때 개발 전용 허용 규칙을 별도로 만든다.
현재 수신기는 합성 데이터·소량 로컬 실습용이며 수신 영속화·서명·중복 업무 방지는 아직 없다.

직접 해볼 것: 같은 본문을 success·failure·delay URL에 보내고 응답 코드·시간과 서버 로그를 비교한다.
다음 학습은 FlowHook HTTP 클라이언트의 연결/응답 시간 제한과 DB 트랜잭션 밖의 통신이다.
