# 자고 일어나서 보는 FlowHook 기초 세팅 안내

최신 상태: 전달 대상 등록·조회를 PostgreSQL JDBC 저장소로 교체했다. 사용법은 [백엔드 README](../backend/README.md), 변경 내역은 [DB 연결 기록](progress/003-postgresql.md)을 따른다. 아래 기초 세팅 당시의 DB 미연결·메모리 저장·테스트 미작성 설명은 과거 상태다.

## 1. 이번에 어디까지 만들었나

처음 시작할 수 있는 Java 서버 골격을 만들었다. 로컬 빌드는 성공했다.
서버 실행, HTTP 응답 확인, 테스트 작성·실행, Docker 실행, GitHub CI 실행은 아직 진행하지 않았다.
실행 중인 서버나 컨테이너를 남겨두지 않았다.

오늘 처음 배울 내용은 **Java 프로그램이 어떻게 HTTP 요청을 받아 JSON으로 답하는가**다.
웹훅의 재시도와 DB 모델링은 이 흐름을 이해한 다음에 붙인다.

## 2. 선택한 기술은 무엇인가

| 이름 | 쉬운 설명 | 이번 설정 |
| --- | --- | --- |
| Java / JDK | 코드를 작성하는 언어 / Java를 컴파일하고 실행하는 도구 | 설치된 Java 21 사용 |
| Spring Boot | Spring 서버의 시작과 기본 설정을 도와주는 도구 | 4.1.1 |
| Spring MVC | URL 요청을 Java 메서드에 연결하는 웹 프레임워크 | Web MVC starter |
| Gradle | 라이브러리 다운로드, 컴파일, 패키징을 수행하는 도구 | Wrapper 9.7.1 |
| JAR | Java 코드와 필요한 실행 구성물을 담는 파일 | bootJar로 서버 실행 파일 생성 |
| Docker | 애플리케이션 실행 환경을 이미지로 구성하는 도구 | Dockerfile 초안 |
| Compose | 여러 컨테이너의 실행 설정을 파일로 관리하는 도구 | 앱·PostgreSQL을 선택 실행 |
| PostgreSQL | 데이터를 테이블에 저장하는 관계형 DB | 17 계열 실습용 구성만 준비 |
| Git | 파일 변경 이력을 관리하는 도구 | 로컬 main 브랜치 초기화 |
| GitHub Actions | GitHub에서 정해진 작업을 자동 실행하는 기능 | 빌드 CI 파일만 준비 |
| pi 하네스 | AI에 작업 범위와 완료 확인 절차를 제공하는 개발 보조 설정 | 이전 프로젝트 확장 유지 |

Spring 공식 생성기에서 안정 버전인 Boot 4.1.1을 선택했다. Java 21과 Gradle 9.x는 공식 호환 범위 안에 있다.
강의의 Boot 2·3 예제와는 starter 이름, 테스트 관련 import 등이 다를 수 있다.
복사한 예제가 맞지 않을 때는 현재 버전에 맞춰 확인한다.
[Spring 공식 호환 조건](https://docs.spring.io/spring-boot/4.1/system-requirements.html), [공식 생성기](https://start.spring.io/).

## 3. 파일 지도

```text
flowhook/
├── README.md                         처음 보는 안내
├── AGENTS.md                         AI가 참고할 프로젝트 개발 지침
├── .editorconfig                     들여쓰기·줄바꿈 규칙
├── .gitattributes                    Git 줄바꿈·바이너리 파일 규칙
├── .gitignore                        비밀번호·빌드 결과 등 추적 제외
├── .env.example                      Compose 환경변수 예시
├── compose.yaml                      앱·DB 컨테이너 실행 구성
├── scripts/backend.sh                Java 21로 Gradle 실행
├── .github/workflows/backend-build.yml GitHub 빌드 CI 초안
├── .pi/                              AI 개발 하네스 설정
├── backend/
│   ├── build.gradle                  플러그인·Java·라이브러리 설정
│   ├── settings.gradle               Gradle 프로젝트 이름
│   ├── gradlew / gradlew.bat         지정된 Gradle 실행기
│   ├── gradle/wrapper/               Gradle 버전·다운로드 설정
│   ├── Dockerfile                    앱 컨테이너 이미지 만드는 순서
│   ├── .dockerignore                 이미지 빌드에 보내지 않을 파일
│   ├── src/main/java/dev/flowhook/
│   │   ├── FlowhookApplication.java  서버 시작점
│   │   └── health/HealthController.java 상태 확인 API
│   ├── src/main/resources/application.yaml 서버 설정
│   └── build/                        자동 생성되는 빌드 결과
└── docs/                             기획·명세·학습·작업 기록
```

`build/`와 `.gradle/`은 도구가 만드는 결과·캐시다. 직접 코드를 작성하는 곳은 `src/`다.
Git 저장소는 이 폴더 안에만 만들었고, 아직 커밋이나 GitHub 원격 연결은 없다.

## 4. Java 파일 두 개부터 읽자

### FlowhookApplication.java

Java에서 배운 `main()`이 그대로 출발점이다.
`SpringApplication.run(...)`이 Spring 애플리케이션과 내장 웹 서버를 시작한다.
별도 Tomcat 설치는 필요 없다.

`@SpringBootApplication`에는 자동 설정과 컴포넌트 탐색 등의 기능이 묶여 있다.
현재 main 클래스가 `dev.flowhook`에 있어서 그 아래의 `health` 패키지도 탐색 대상이다.
나중에 클래스 위치를 전혀 다른 최상위 패키지로 옮기면 탐색에서 빠질 수 있다.

### HealthController.java

- `@RestController`: 이 클래스가 HTTP 요청을 받아 응답 본문을 돌려주는 컨트롤러임을 표시한다.
- `@GetMapping("/api/health")`: 해당 URL의 GET 요청을 `health()`에 연결한다.
- `new HealthResponse("UP")`: 응답 데이터를 담는 Java 객체를 만든다.
- `record`: 간단한 데이터 묶음을 표현한다. 여기서는 문자열 `status` 하나를 담는다.

Spring MVC가 반환 객체를 JSON으로 직렬화한다. 코드에서 JSON 문자열을 수작업으로 조립하지 않는다.

요청 흐름:

```text
브라우저 GET /api/health
  → 내장 웹 서버
  → Spring MVC의 요청 분배
  → HealthController.health()
  → HealthResponse 객체
  → JSON 응답 {"status":"UP"}
```

현재 이 API는 HTTP 응답 가능 여부만 나타낸다. DB나 외부 서비스의 건강 상태까지 검사하지 않는다.
저장할 데이터나 별도 업무 규칙이 없어서 Service·Repository 클래스는 아직 만들지 않았다.

## 5. build.gradle은 어떻게 읽나

- `plugins`: Java 컴파일, Spring Boot 실행 파일 생성, 의존성 버전 관리를 사용한다.
- `group`: 프로젝트의 식별용 이름 공간인 `dev.flowhook`이다. 도메인 소유나 DNS 설정을 뜻하지 않는다.
- `version`: 우리 앱의 개발 버전인 `0.0.1-SNAPSHOT`이다. Spring Boot 자체는 정식 버전이다.
- `java.toolchain`: 코드 컴파일에 Java 21을 사용하게 한다.
- `repositories`: Maven Central에서 라이브러리를 가져온다.
- `implementation`: 서버가 실행할 때 필요한 Spring MVC 라이브러리 묶음이다.
- `testImplementation`·`testRuntimeOnly`: 이후 테스트 작성에 사용할 도구다. 의존성만 준비되어 있다.
- `useJUnitPlatform()`: 나중에 테스트를 실행할 때 사용할 엔진 체계를 지정한다.

`gradlew`는 Gradle을 따로 설치하지 않아도 지정한 버전으로 빌드하도록 돕는다.
Wrapper 설정에 배포 파일 SHA-256도 기록했다. 다운로드된 Gradle 배포 파일이 기대한 파일과 일치하는지 확인하는 값이다.

## 6. 직접 서버 실행하기

터미널에서:

```bash
cd /Users/dalzoo/Desktop/flowhook
./scripts/backend.sh
```

이 스크립트는 Mac에서 설치된 Java 21을 찾아 해당 프로세스에만 적용하고 `bootRun`을 실행한다.
시스템의 기본 Java 설정이나 셸 설정 파일은 바꾸지 않는다.

브라우저에서 `http://localhost:8080/api/health`를 연다.
기대 응답은 `{"status":"UP"}`이다. `/`는 아직 만든 경로가 아니므로 404가 나올 수 있다.
서버는 실행 중인 터미널에서 `Ctrl+C`로 종료한다.

포트가 이미 사용 중이라면:

```bash
SERVER_PORT=8081 ./scripts/backend.sh
```

그때는 브라우저 주소도 8081로 바꾼다.
IntelliJ를 사용한다면 `backend/build.gradle`을 Gradle 프로젝트로 열고 Project SDK와 Gradle JVM을 Java 21로 지정한다.

## 7. application.yaml 읽기

- `spring.application.name`: 로그나 다른 도구에서 사용할 앱 이름이다.
- `server.address`: 기본은 `127.0.0.1`, 내 컴퓨터에서 접근하는 로컬 주소다.
- `server.port`: 기본 8080. 환경변수 `SERVER_PORT`로 바꿀 수 있다.
- `server.shutdown: graceful`: 종료할 때 처리 중인 요청을 정리할 기회를 준다.
- `logging.level.root: INFO`: 일반 운영 정보 수준의 로그를 출력한다.

`${변수명:기본값}`은 환경변수가 있으면 사용하고 없으면 기본값을 쓰는 표현이다.
현재는 설정이 작아서 local/dev/prod 파일을 여러 개로 나누지 않았다.

## 8. Docker는 SQL을 배울 때 켜도 된다

먼저 Docker Desktop이 실행되어 있어야 한다.
최상위 폴더에서 처음 한 번 `.env.example`을 `.env`로 복사하고 비밀번호를 로컬용으로 변경한다.
이미 `.env`가 있다면 덮어쓰지 않는다.

```bash
cp -n .env.example .env
```

Compose는 `.env`의 값을 읽는다. Spring 애플리케이션이 이 파일을 자동으로 읽는 것은 아니다.
현재 Compose는 DB와 앱 중 하나만 실행해도 환경변수 해석을 위해 비밀번호 설정이 필요하다.

DB만 실행:

```bash
docker compose --profile db up -d postgres
```

접속 정보는 호스트 `localhost`, 포트 `5433`, DB·사용자 `flowhook`, 비밀번호는 `.env`에 정한 값이다.
기존 로컬 PostgreSQL의 기본 포트 5432와 겹칠 가능성을 줄이려고 외부 포트를 5433으로 정했다.
컨테이너 안의 PostgreSQL 포트는 그대로 5432다.

SQL 터미널 열기:

```bash
docker compose exec postgres psql -U flowhook -d flowhook
```

SQL 창 종료는 `\q`다.
앱과 DB는 아직 연결하지 않았다. JPA·JDBC 드라이버·Flyway와 테이블 생성은 DB 학습 단계에서 추가한다.

앱을 Docker로 실행해보고 싶을 때:

```bash
docker compose --profile app up --build -d app
docker compose logs -f app
```

터미널에서 직접 실행한 앱과 같은 8080 포트를 쓰므로 먼저 로컬 서버를 종료한다.
Dockerfile은 Java 코드를 빌드하는 단계와 JAR만 실행하는 단계를 나누었고, 앱은 일반 사용자 권한으로 실행한다.
Docker 환경에서는 컨테이너 밖에서 연결되도록 서버 주소를 `0.0.0.0`으로 설정한다.
호스트 공개 포트는 `127.0.0.1`로 제한했다.

종료:

```bash
docker compose --profile db --profile app down
```

기본 `down`은 이름 있는 볼륨의 DB 데이터를 유지한다. `down -v`는 데이터를 지우므로 초기화가 필요할 때만 사용한다.
DB 초기화 이후 `.env`의 비밀번호만 바꿔도 기존 DB 계정 비밀번호가 자동 변경되지는 않는다.
[PostgreSQL 공식 이미지 안내](https://hub.docker.com/_/postgres).

## 9. CI와 CD는 현재 어디까지인가

CI는 변경된 코드가 함께 빌드되는지 자동으로 확인하는 과정이다.
작성한 GitHub Actions 파일은 관련 파일의 push·PR 때 Java 21을 준비하고 Gradle `assemble`을 실행한다.
사용하는 Actions는 버전 태그가 가리킨 커밋으로 고정했다.

현재는 **컴파일·패키징 CI 초안**이다. GitHub 저장소 연결이 없으므로 아직 실행 이력은 없다.
테스트 코드를 작성한 뒤 테스트 단계와 결과 보관을 추가할 수 있다.
CD는 빌드한 앱을 서버에 배포하는 자동화다. 배포 대상과 운영 방식을 정할 때 구성한다.
[Java 설정 Action](https://github.com/actions/setup-java), [Gradle Action](https://github.com/gradle/actions).

## 10. pi는 지금 어떻게 쓰면 되나

우선 프로젝트 폴더에서 pi를 열고 다음을 입력해 설명부터 받아도 된다:

```text
/fh-learn main 클래스와 Controller부터 설명해줘
```

기존에 열려 있던 pi는 `/reload`가 필요하다. 실제 확장 로딩은 아직 확인하지 않았다.
계정·모델·자동 전환 설정은 이번 작업에서 변경하지 않았다.

`/fh-task 001`은 이제 처음부터 프로젝트를 생성하는 대신 **현재 초안의 테스트 작성과 검증을 마무리하는 작업**으로 안내한다.
기존 하네스는 실행된 테스트가 하나도 없으면 PASS를 주지 않는다. 따라서 현 상태를 `/fh-check`만으로 완료 처리할 수 없다.
사용자가 그 다음 단계까지 진행하고 싶을 때 작업을 시작한다.

## 11. 다음 공부 시간: 45~60분

1. 10분: `FlowhookApplication.java`와 `HealthController.java`를 읽고 각 어노테이션의 역할을 말해본다.
2. 10분: 서버를 켜고 브라우저에서 응답을 본다. `/`와 `/api/health`의 차이도 본다.
3. 15분: 응답 객체에 `application` 필드를 추가해서 `flowhook`을 함께 반환해본다.
4. 10분: 서버를 재시작해서 바뀐 응답을 확인한다. DevTools는 설치하지 않아 코드 변경 후 자동 재시작되지 않는다.
5. 10분: 요청이 들어와 JSON이 나가기까지의 흐름을 본인 말로 기록한다.

이후 테스트 학습을 시작하고, 그 다음에 endpoint 등록 API의 입력·출력부터 함께 설계하면 된다.
지금 만든 설정을 모두 외우기보다 **수정한 코드 → 서버 재실행 → 응답 변화**를 한 번 직접 경험하는 것이 첫 목표다.
