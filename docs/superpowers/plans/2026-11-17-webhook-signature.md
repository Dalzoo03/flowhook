# 로컬 웹훅 서명 검증

승인 범위: FlowHook에서 본문/전달 ID/매 시도 시각을 HMAC-SHA256으로 서명하고 수신자가 업무 처리 전에 검증한다. 기존 자동 재시도/중단 복구/멱등 처리는 유지한다.

- 서명 입력: UTF-8 `v1\n<unix초>\n<deliveryId>\n` + 실제 HTTP body 바이트. 헤더 X-FlowHook-Timestamp, X-FlowHook-Signature(v1=소문자64hex). 기존 X-FlowHook-Delivery-Id 유지.
- 수신 허용 시각 ±300초(경계 포함). 중복/누락/형식 오류 헤더, 변조, 다른 키, 과거/미래 요청은401이며 DB/본문 로그/업무 처리 전에 거절한다. 비교는 MessageDigest.isEqual 사용.
- pure-JDK WebhookSignature와 LocalWebhookKey를 송신/수신에서 공유한다. 매 HTTP 시도 직전에 현재 시각과 HMAC을 새로 계산한다.
- 명시한 FLOWHOOK_SIGNING_KEY(Base64 32바이트) 또는 첫 실행에서 생성한 .local/webhook-signing.key를 사용한다. .local은 Git 제외한다. 런타임만 키를 읽고 출력하지 않는다. 프로젝트 루트 탐색으로 backend/root 작업 디렉터리 차이를 흡수한다. 파일 잠금으로 동시 첫 실행도 같은 키를 사용한다.
- 테스트는 전용 합성 키/임시 디렉터리로 격리한다. 기본 비활성 모드는 로컬 키를 만들지 않는다. 키 오류는 로컬 앱/수신기 시작 실패로 명시하며 무서명 우회는 없다.
- 기존 receiver 자체 검사/DB 통합/FlowHook worker 통합을 유효한 서명으로 갱신한다. 변조·오래된 시각·잘못된 키·중복 헤더·기존 전달 재생 공격을 테스트한다. 독립 HMAC 벡터도 검증한다.
- 문서/STATUS/progress 갱신. 키 교체 UI/endpoint별 키 암호화 저장/외부 배포/사용자 로그인·API키 인증은 이번 범위 밖이다. 커밋/푸시 없음.

완료: 구현/문서/검토 보강 반영, 전체147개 JUnit+receiverCheck+assemble 성공. 임시 생성 키를 실제 수신기와 패키징 FlowHook이 공유해 서명 전송/무서명401/수신기 재시작 검증 성공. 사용자 DB·키 파일은 건드리지 않았다. 커밋/푸시/원격 CI 미실행.
