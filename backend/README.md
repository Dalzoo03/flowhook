# FlowHook 백엔드

Java 21, Spring Boot 4.1.1, Spring MVC, Gradle Wrapper 9.7.1을 사용한다.
현재 API는 `GET /api/health`와 전달 대상 등록·조회이며 PostgreSQL에 저장한다.

실행 전에 Docker Desktop을 켜고 루트에서 `docker compose --profile db up -d postgres`로 DB를 실행한다.
`.env`의 비밀번호는 기존 DB 사용자 비밀번호와 일치해야 한다. DB를 처음 준비한다면 DBeaver에서 `src/main/resources/db/schema.sql`을 실행한다.
이미 수동 실습으로 테이블을 만들었다면 다시 실행하지 않는다. 앱이 테이블을 생성하거나 초기화하지 않는다.

## 실행

프로젝트 최상위 디렉터리에서:

```bash
./scripts/backend.sh
```

macOS에서는 이 스크립트가 설치된 Java 21을 해당 프로세스에만 지정한다.
다른 운영체제에서는 Java 21을 설치하고 `JAVA_HOME` 또는 `PATH`를 지정한다.

Gradle을 직접 사용하려면 `backend/`에서 실행한다:

```bash
./gradlew --no-daemon bootRun
```

기본 주소: `http://localhost:8080/api/health`.
코드상 기대 응답: HTTP 200, `{"status":"UP"}`. `/`는 구현하지 않아 404가 정상이다.
종료는 `Ctrl+C`.

## 빌드와 JAR

프로젝트 최상위에서:

```bash
./scripts/backend.sh assemble
```

실행 파일은 `backend/build/libs/flowhook-0.0.1-SNAPSHOT.jar`다.
`-plain.jar`는 의존성을 포함한 서버 실행 파일이 아니다.
실행 JAR는 Java 21로 `java -jar` 명령을 사용한다.

## 설정 변경 예

```bash
SERVER_PORT=8081 ./scripts/backend.sh
```

`application.yaml`의 `${SERVER_PORT:8080}`은 환경변수가 있으면 그 값, 없으면 8080이라는 뜻이다.
Spring이 원래 `.env`를 자동으로 읽는 것은 아니다. 이번 설정에서는 `spring.config.import`로 현재 작업 폴더와 그 부모의 `.env`를 properties 형식으로 명시적으로 읽도록 했다. 프로젝트 루트 또는 `backend/`를 작업 폴더로 사용한다.
파일에는 `POSTGRES_PASSWORD`, `POSTGRES_PORT`를 지정한다. 실행 환경변수의 `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`로 연결 정보를 덮어쓸 수도 있다.

## 전달 대상 등록·조회

- `POST /api/projects/1/endpoints`: 등록, HTTP 201과 생성 객체 반환.
- `GET /api/projects/1/endpoints`: 등록 순서 목록, HTTP 200 반환.
- 프로젝트는 실습용 1만 지원한다. 나머지 ID는 404다.
- 이름은 앞뒤 공백 제거 후 1~80자, 이벤트 유형은 필수이며 입력한 대소문자를 유지한다.
- URL은 호스트가 있는 HTTPS 형식만 받으며 사용자 정보·fragment·잘못된 포트를 거절한다.
- 중복 URL도 별도 대상으로 등록한다. 10개를 초과하면 409로 거절한다.
- 인증, 목적지 소유 확인·허용 목록, 서명키 생성, 실제 전송은 아직 구현하지 않았다. 공개 서비스용 URL 안전 정책을 완성한 상태가 아니다.
- PostgreSQL에 저장하므로 앱 재시작 후에도 데이터가 남는다. Docker의 DB 볼륨 삭제는 데이터를 지운다.
- 등록은 트랜잭션 안에서 프로젝트별 advisory lock → 개수 확인 → INSERT 순서다. SQL을 직접 실행하는 외부 쓰기에는 이 제한이 강제되지 않는다.

```bash
curl -i http://localhost:8080/api/projects/1/endpoints \
  -H 'Content-Type: application/json' \
  -d '{"name":"배송 서비스","url":"https://partner.example.com/webhooks","eventType":"order.created"}'

curl http://localhost:8080/api/projects/1/endpoints
```

## 테스트

Docker Desktop이 켜진 상태에서 루트의 `./scripts/backend.sh test`를 실행한다.
Testcontainers가 별도 PostgreSQL 17 컨테이너를 띄운다. 로컬 flowhook DB와 포트·계정·저장 공간이 분리된다.
각 테스트의 초기화는 테스트 컨테이너에만 적용한다. 테스트를 위해 로컬 DB를 비우지 않는다.
Spring과 MockMvc로 기존 12개 시나리오에 더해 DB 데이터 조회·저장 및 동시 등록 20건에서 성공 10건·거절 10건을 검증한다.
Flyway 도입은 다음 단계다. 현재 `db/schema.sql`은 수동 생성 및 격리 테스트 초기화용이며 버전별 마이그레이션은 아니다.

전체 설명: [설정 설명서](../docs/GETTING_STARTED.md).
