# FlowHook

이벤트를 외부 서버에 전달하고, 실패를 재시도·조회·복구하는 백엔드를 만드는 학습 프로젝트.

**현재 단계: PostgreSQL 기반 전달 대상 등록·조회와 이벤트 접수 API.** 이벤트와 대상별 전달 대기를 원자적으로 저장한다. 실제 전송과 인증은 이후 학습 단계다.

## 처음에는 이것부터

1. [설정 설명서](docs/GETTING_STARTED.md)를 읽는다. 파일별 역할, 실행 방법, 학습 순서를 설명한다.
2. 아래 명령으로 서버를 실행한다.
3. 브라우저에서 `http://localhost:8080/api/health`를 연다. 코드상 기대 응답은 `{"status":"UP"}`이다.

```bash
cd /Users/dalzoo/Desktop/flowhook
docker compose --profile db up -d postgres
./scripts/backend.sh
```

종료: 터미널에서 `Ctrl+C`. 첫 실행에서는 Gradle과 라이브러리를 내려받을 수 있다.

## 현재 준비된 것

| 구성 | 상태 |
| --- | --- |
| Java 21 / Spring Boot 4.1.1 / Gradle 9.7.1 | 기본 프로젝트 작성, 로컬 assemble 성공 |
| `GET /api/health` | 코드 작성, HTTP 실행 확인 미실행 |
| 전달 대상 등록·조회 | 프로젝트 1, PostgreSQL 저장, 입력 검증·10개 제한 |
| 이벤트 접수 | POST /v1/events, 원자적 저장·대상 고정·중복 처리 |
| 테스트 | 별도 PostgreSQL 컨테이너에서 48개 통과, API·DB·동시성·Flyway·Swagger 검증 |
| Swagger UI | 앱 실행 후 http://localhost:8080/swagger-ui/index.html |
| DB 구조 관리 | Flyway V1·V2, 기존 데이터 보존 |
| Docker / PostgreSQL | 로컬 PostgreSQL 사용, 앱+DB Compose 설정 작성 |
| GitHub Actions | PostgreSQL 테스트·빌드 CI 구성, 실행 결과는 Actions에서 확인 |
| Git | 현재까지의 구현을 첫 커밋으로 기록 |
| pi | 기존 계정 설정 유지, 프로젝트 확장 작성, 실제 로딩 미확인 |

## 문서

- [현재 진행 현황](docs/STATUS.md)
- [Flyway 설명과 사용법](docs/FLYWAY.md)
- [설정 설명과 첫 학습](docs/GETTING_STARTED.md)
- [백엔드 실행 방법](backend/README.md)
- [pi 사용법](docs/PI_HARNESS.md)
- [이번 작업 기록](docs/progress/001-health-api.md)
- [기획서·PRD·로드맵](docs/README.md)

CI는 Testcontainers의 별도 PostgreSQL에서 테스트하고 컴파일·패키징한다. 테스트 실패 시 CI도 실패한다. 배포 자동화(CD)는 아직 구성하지 않았다.
