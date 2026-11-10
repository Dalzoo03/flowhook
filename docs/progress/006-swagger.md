# 006 — Swagger UI

사용자 요청: curl 대신 Swagger에서 API를 확인하고 실행한다.

## 변경

Boot 4.1 호환 범위의 springdoc-openapi-starter-webmvc-ui 3.2.0을 추가했다.
이벤트 API는 HttpServletRequest에서 제한된 크기만 읽기 때문에 자동 문서 추론만으로는 요청 본문이 나오지 않는다.
문서용 EventInput 모델과 예시를 명시하고, 기존 엄격한 입력 파싱·크기 제한은 그대로 유지했다.
이벤트 200/202/400/409/413/503 응답 모델과 전달 대상 API 설명·예시를 추가했다.

## 사용법

Gradle 새로고침·앱 재시작 후 http://localhost:8080/swagger-ui/index.html 에서 Try it out → Execute.
Swagger Execute는 실제 요청이므로 저장 데이터가 생긴다. 로컬 프로젝트 1만 지원하고 인증·외부 전송은 아직 없다.

## 검증

UI가 없는 상태에서 테스트 실패를 확인했다. 추가 후 전체 48개 테스트와 assemble 통과.
새 테스트는 UI HTML, OpenAPI 이벤트 본문·필수 필드·미정의 필드 제한·주요 응답 코드·전달 대상 경로를 검증한다.
패키징한 JAR를 임시 포트로 실행해 Swagger HTML·JS 번들·UI 설정·OpenAPI JSON의 HTTP 200을 확인했다. 이벤트 요청 모델과 응답 코드도 확인했다. DB 변경 없이 조회만 수행했고 임시 앱은 종료했다.
실제 브라우저에서 Execute 클릭은 사용자가 앱을 재시작한 뒤 진행한다.
커밋 `761ac99`의 [GitHub CI 테스트·빌드](https://github.com/Dalzoo03/flowhook/actions/runs/42770860265)도 성공했다.

참고: [springdoc 공식 호환 표](https://springdoc.org/#what-is-the-compatibility-matrix-of-springdoc-openapi-with-spring-boot).
