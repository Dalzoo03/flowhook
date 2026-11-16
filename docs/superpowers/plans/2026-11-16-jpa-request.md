# JPA와 요청 DTO 정리

사용자 승인 범위: JDBC 저장 계층을 JPA로 변경하고 EventRequest를 @Valid @RequestBody DTO로 단순화한다. 자동 worker는 이번 범위 밖이다.

- Endpoint/Event는 Entity와 Spring Data JpaRepository로 저장·조회한다.
- JSONB 동등 비교·일괄 전달 생성·조건부 선점은 JPA native SQL로 유지한다. 외부 HTTP는 트랜잭션 밖에서 실행한다.
- EventRequest의 직접 파싱을 제거한다. 기존 64KiB·정확한 숫자·잘못된 JSON 거절은 입력 경계의 설정/검증으로 유지한다.
- Flyway가 스키마를 관리하고 Hibernate는 validate만 한다. 기존 DB를 초기화하지 않는다.
- 기존 API·롤백·동시성·재시도 테스트를 유지하고 전체 test assemble로 검증한다.
- 사용자 실습: 최초 포함 5회 실패 후 DEAD 확인. 이후 후보는 자동 worker이나 별도 합의 후 구현한다.

완료: JPA/DTO 전환, 기존 84개 테스트와 빌드, 기존 로컬 DB read-only 확인, 별도 코드 검토 및 회귀 수정. 문서 반영 완료. 커밋·푸시·원격 CI는 미실행.
