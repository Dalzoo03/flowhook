# 016 — 수신자 멱등 처리·서명 Git 반영

기록일: 2026-11-17. 사용자 요청: 완성한 변경을 GitHub에도 올린다.

## 반영 범위

수신자의 영속 멱등 처리/업무 장부,로컬 HMAC-SHA256 서명과 키 파일 처리,실행 스크립트/receiver source set/CI 설정,관련 테스트·실습·진행 문서를 반영한다.
사용자 설정/인증 값/로컬 키 파일은 포함하지 않는다. 이전014·015 기록은 각 기능 구현 당시 상태로 보존한다.

## 검증

코드 변경 뒤 전체147개 JUnit과 receiverCheck/assemble 성공,실패·오류·스킵0을 확인했다.
실제 worker의 TIMEOUT→자동 재시도/수신2·업무1,독립 JVM 재시작과 서명키 공유·무서명401도 격리 환경에서 검증했다.
업로드 때문에 같은 로컬 테스트를 반복하지 않고 GitHub의 동일 커밋 test-and-build/receiver-test 결과를 확인한다.
기능 커밋 `e481e6e983c78a238220413211cba3b5caefccd7`를 main에 푸시했다.
[GitHub CI](https://github.com/Dalzoo03/flowhook/actions/runs/44028755332) 성공을 확인했다. 동일 기능 커밋에서 test-and-build(2분13초)와 receiver-test(31초)가 모두 성공했다.
결과 링크와 최종 상태를 문서 커밋으로 추가한다. docs만 변경하므로 workflow paths 조건에 따라 이 문서 커밋은 CI를 새로 실행하지 않는다.

## 제한·다음

배포/계정 인증 설정 변경/사용자 DB 실행은 하지 않는다. 새 버전 적용은 기존 FlowHook과 수신기를 모두 중지한 뒤 재시작한다.
현재는 로컬 공유키 방식이며 endpoint별 키 관리·사용자 로그인/API키 인증·일반 외부 송신은 후속이다.
