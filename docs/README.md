# FlowHook 문서 안내

현재 제품 문서는 **v0.2 검토용 최종 초안**이다. 구현·성능 검증·사용자 승인을 의미하지 않는다.

## 먼저 읽을 문서

처음 실행한다면 [기초 세팅 설명서](GETTING_STARTED.md)와 [작업 기록](progress/001-health-api.md)부터 읽는다.

1. [프로젝트 기획서](PROJECT_PLAN.md) — 왜 만드는지, 목표·범위·일정·위험.
2. [PRD](PRD.md) — 사용자 여정, 기능·품질 요구사항, 성공 기준.
3. [기능 명세서](FUNCTIONAL_SPEC.md) — 기본 정책, 기능 동작, 상태·오류, 인수조건.

## 실행과 학습 계획

- [pi 하네스 사용법](PI_HARNESS.md) — 설정 내역, 첫 작업 시작, 검증·학습 명령.
- [첫 구현 작업](tasks/001-health-api.md) — Spring Boot와 상태 확인 API.
- [프로젝트 로드맵](FLOWHOOK_PROJECT_ROADMAP.md) — 구현·실험 순서와 기술 방향.
- [개인 백엔드 학습 로드맵](BACKEND_LEARNING_ROADMAP.md) — 현재 수준, 강의·실습·지원 준비.

## 작성 근거

- [GitHub 도구 검토·적용 기록](TOOLING_REVIEW.md) — 비교 후보, 선택 이유, 문서 자체 검토.
- [참고 소스 목록](references/sources.json) — 고정한 소스와 파일 해시.

V1의 사용자 동작·정책은 PRD와 기능 명세서를 기준으로 읽는다. 로드맵은 구현 순서를 다루며, 인수인계 ZIP은 초기 참고 자료다.
