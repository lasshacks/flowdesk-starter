# Vue Task 상태 이력 화면 — 2026-11-10

## 목표와 계약

Task 상세에 현재 Task의 상태 변경 이력을 연결한다. 백엔드는 기존 GET `/api/workspaces/{workspaceId}/projects/{projectId}/tasks/{taskId}/status-activities`를 제공하며, 네 역할 모두 읽을 수 있다. 응답의 행위자 ID/현재 표시 이름 중 개인정보가 덜 필요한 표시 이름만 사용하고 이메일은 요청·표시하지 않는다. 기록은 서버 순서대로 최신 버전부터 보이고, 기본 20건을 읽은 뒤 `nextBeforeVersion`으로 더 이전 기록을 덧붙인다. 번호 페이지로 재해석하거나 새 버전을 임의의 목록 경계로 만들지 않는다.

## 화면 흐름

Task 상세의 “상태 변경 이력 보기” 버튼을 누르면 첫 페이지를 불러온다. 상세를 선택할 때마다 자동 요청하지 않아 읽기 호출을 줄이고 기존 상세 조회 상호작용도 유지한다. 접힌 동안 Task 상태가 바뀌었다면 다시 열 때 최신 페이지를 새로 읽는다. 펼친 상태의 새 상태 저장/복구 이벤트는 표시 중인 이력을 최신 페이지부터 갱신한다.

초기 로딩·빈 이력·요청 오류는 서로 다른 상태로 보인다. 처음 읽기가 실패하면 이력 없음으로 오인하지 않게 재시도를 제공한다. 더 보기 요청이 실패하면 이미 받은 이력과 커서를 유지해 같은 경계에서 재시도한다. Task 전환/화면 해제/새로고침은 기존 fetch를 취소하고 세대 번호로 늦은 응답 반영을 막는다.

각 기록은 행위자 현재 표시 이름, 이전→이후 상태, UTC 시각을 한국어 날짜 문자열로 보여 준다. 원래 UTC ISO 시각은 `<time datetime>`에 유지하고 버전도 함께 표시한다. Vue 텍스트 보간으로 문자열을 HTML로 실행하지 않는다.

## 코드 흐름

`TaskBrowser` → `TaskStatusHistory` 펼침 → `getTaskStatusHistory` → 기존 fetch/AbortController 패턴 → strict 계약 파싱 → version cursor/size 20 → 최신순 이력 목록이 흐름이다. API client는 부모/Task ID, 응답 필드 수, 상태 전이·actor·version·UTC 형식, 요청 커서에 대한 배타 경계, 페이지 정렬/hasNext/nextBeforeVersion 정합성을 확인한다. 서버 행위자·인가/이력 내용은 클라이언트가 지정하지 않는다.

## 검증

`frontend/`에서 `npm.cmd run build`를 실행했다. `vue-tsc --build`와 Vite production build가 성공했다. 최초 빌드에서 컴파일 대상 라이브러리에 없는 `Array.at()` 호출이 확인되어 기존 target과 맞는 배열 인덱스로 변경한 뒤 빌드가 통과했다. Vitest와 브라우저 수동 검증은 실행하지 않았다.

새 이력 조회는 API/컴포넌트 경로를 기준으로 기존 fetch 규칙(credentials omit, cache no-store, redirect error, timeout, abort)을 따른다. 실제 API/로컬 Postgres는 이전 단계에서 검증되어 이 UI 작업에서는 다시 실행하지 않았다. 실제 로그인/저장된 사용자 세션이 없는 개발 행위자 환경이며, 화면은 현재 사용자의 권한을 최종 결정하지 않는다.

요청한 UI 연결을 완료했다. 전체 Task MVP/Phase 1과 실제 인증은 별도 범위다.
다음 한 작업은 **Task 담당자·마감일 필드의 모델·권한 범위 확정**이다.
