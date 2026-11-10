# PRD 작성 도구 검토와 적용 기록

검토일: 2026-09-30 · 범위: GitHub 공개 저장소의 README와 공식 템플릿

## 1. 비교 결과

| 후보 | 확인한 기능·방식 | FlowHook에서의 판단 |
|---|---|---|
| [GitHub Spec Kit](https://github.com/github/spec-kit) | 명세·계획·작업으로 이어지는 구조, 우선순위가 있는 사용자 시나리오, 기능 요구사항, 성공 기준, 요구사항 품질 체크리스트 | 기능 명세서의 기본 구조로 채택 |
| [BMAD Method](https://github.com/bmad-code-org/BMAD-METHOD) | PRD 템플릿의 목적·사용자 여정·기능·범위·성공 지표·가정·미결정 항목 연결 | PRD의 내용 구조로 채택 |
| [OpenSpec](https://github.com/Fission-AI/OpenSpec) | proposal·specs·design·tasks와 변경 이력 관리 | 이후 기능 변경이 반복될 때 재검토; 이번 문서 세트의 기본 도구로는 미채택 |

판단은 현재의 1인 학습 프로젝트와 문서 작성 요청에 대한 것이다. 도구 전체의 성능·품질 우열을 평가하거나 실제 실행 성능을 비교한 것은 아니다.

## 2. 실제 사용 방식

공식 템플릿 원문과 라이선스를 로컬에 보관하고, 그 구조를 FlowHook에 맞게 적용해 문서를 직접 작성·검토했다. CLI 설치나 도구의 자동 생성·다중 에이전트 워크플로를 실행한 것은 아니다. 문서 내 예시 명령과 지시는 참고 자료로 읽었다.

외부 모델·서비스에 프로젝트 파일을 업로드하지 않았다. 다운로드한 것은 공개된 Markdown 템플릿과 라이선스다.

| 템플릿 요소 | 반영 위치 |
|---|---|
| BMAD 목적·비전·사용자 | PRD 1~2절 |
| BMAD 사용자 여정·요구사항 연결 | PRD UJ-01~04, FR 목록 |
| BMAD 범위·가정·성공·균형 지표 | PRD 7~11절 |
| Spec Kit 우선순위·독립 확인 | PRD 사용자 여정 |
| Spec Kit 기능 요구사항·핵심 개념 | PRD FR/NFR와 용어 |
| Spec Kit Given/When/Then | 기능 명세서 AC-01~33 |
| Spec Kit 측정 가능한 성공 기준 | PRD SC-001~006 |
| Spec Kit 요구사항 품질 체크리스트 | 아래 자체 검토 |

템플릿의 예시 문구나 미완성 자리표시자를 그대로 제품 요구사항으로 사용하지 않았다. 독립 검증은 공통 기반과 필요한 초기 데이터가 준비된 상태라는 의미로 한정했다.

## 3. 소스 버전과 라이선스

확인 시점의 저장소 트리 SHA에 고정했다. 최신 릴리스 번호나 안정 버전이라고 주장하지 않는다.

- Spec Kit: `2c0a57abe1e7383a864c7d5e4dfa2457d7537734`
- BMAD: `1cbcfa272fe65787c06a1fa164a901f46117cca7`
- OpenSpec 비교 대상: `c879d13d5f5d045c532a08523316d2d74f2db99a`

적용 원문:

- [Spec Kit spec-template](https://github.com/github/spec-kit/blob/2c0a57abe1e7383a864c7d5e4dfa2457d7537734/templates/spec-template.md) · [로컬 사본](references/spec-kit/spec-template.md)
- [Spec Kit checklist-template](https://github.com/github/spec-kit/blob/2c0a57abe1e7383a864c7d5e4dfa2457d7537734/templates/checklist-template.md) · [로컬 사본](references/spec-kit/checklist-template.md)
- [BMAD prd-template](https://github.com/bmad-code-org/BMAD-METHOD/blob/1cbcfa272fe65787c06a1fa164a901f46117cca7/skills/bmad-prd/assets/prd-template.md) · [로컬 사본](references/bmad/prd-template.md)
- [OpenSpec proposal 비교 원문](https://github.com/Fission-AI/OpenSpec/blob/c879d13d5f5d045c532a08523316d2d74f2db99a/schemas/spec-driven/templates/proposal.md)

선정한 두 저장소는 확인한 소스 기준 MIT 라이선스다. [Spec Kit 라이선스](references/spec-kit/LICENSE)와 [BMAD 라이선스](references/bmad/LICENSE)를 함께 보관했다. 다운로드 파일별 경로·원본 URL·SHA-256은 [출처 목록](references/sources.json)에 기록했다.

## 4. 기존 초안 대비 보완

- 기획서와 PRD를 구분하고 기능 규칙은 기능 명세서로 모았다.
- 4개 사용자 여정과 19개 기능 요구사항, 8개 공통 품질 요구사항을 연결했다.
- 14개 기본 정책과 33개 Given/When/Then 인수조건을 정의했다.
- 중복 접수 응답, JSON 내용 비교, 용량 한도에서의 재요청 처리 기준을 추가했다.
- 목적지 변경과 기존 전달의 관계, 중지 중 신규 이벤트의 처리 기준을 명시했다.
- 재처리 요청 키와 expectedVersion을 함께 정의해 오래된 화면의 재요청까지 다뤘다.
- 결과 불명 시도의 보존과 예산 소진, 늦은 worker 결과의 처리 기준을 추가했다.
- 최초 목표와 측정 결과를 구분하고 가정·남은 결정을 명시했다.

## 5. 작성자 자체 검토 — 문서 품질

아래 체크는 문서 내용의 검토 결과다. 구현 테스트 통과나 사용자 승인, 독립된 외부 검토를 뜻하지 않는다.

- [x] CHK-001 사용자와 해결할 문제, 개발자의 학습 목표가 기술돼 있다.
- [x] CHK-002 V1과 후속 기능을 구별했다.
- [x] CHK-003 각 기능 요구사항에 인수조건을 연결했다.
- [x] CHK-004 성공 기준에 관찰 방법이 있고 미측정 수치를 실적으로 표현하지 않았다.
- [x] CHK-005 중복·권한·중지·한도·재처리 경쟁·재시작의 예외 동작을 정의했다.
- [x] CHK-006 접수·HTTP 성공·업무 처리 성공의 의미를 구별했다.
- [x] CHK-007 정책 수치와 개인 일정 가정을 제안으로 표시했다.
- [x] CHK-008 서명 규약·배포 범위·버전 선택의 결정 시점을 명시했다.
- [x] CHK-009 문서 간 링크·ID 연결·참고 파일의 무결성을 점검했다.
- [ ] CHK-010 제품 초안에 대한 사용자의 검토와 정책 확정은 남아 있다.
- [ ] CHK-011 서명 프로토콜 상세와 배포 환경은 해당 구현 전에 결정해야 한다.

현재 결론: 제품 범위와 동작을 검토할 수 있는 초안이다. 기본 API·데이터 설계의 출발점으로 사용할 수 있으며, 미결정 항목이 관련 작업에 도달하면 갱신한다.
