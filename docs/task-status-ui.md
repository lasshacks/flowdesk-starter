# Vue Task 상태 변경·공유 버전 복구 — 2026-11-10

## 목표와 범위

Task 상세에 OWNER/ADMIN/MEMBER용 상태 선택·저장을 연결했다. [상태 API 계약](task-status-api-v1.md)의 모든 전이·현재 버전 동일 상태 no-op·공유 Task version/409를 따른다. VIEWER는 조회만 제공하며 최종 인가는 서버가 수행한다. 백엔드·V7/권한·의존성은 변경하지 않았다.

일반 편집 폼과 상태 폼은 각자의 시작 Task 스냅샷과 입력을 보존한다. 한 폼의 성공으로 다른 폼의 입력/버전을 자동 갱신하지 않는다. 하나로 합친 폼이나 공통 변경 프레임워크 대신 기존 명시적 복구 패턴을 사용했다.

## 화면부터 서버와 다시 화면까지

1. TaskBrowser가 현재 Task ID에 연결된 TaskStatusForm을 표시한다. 할 일/진행 중/완료를 선택하며 완료 취소·재개도 가능하다. 진행 중 중복 제출과 언마운트 후 늦은 응답을 막는다.
2. changeTaskStatus는 부모 하위 `/tasks/{taskId}/status`에 정확히 status와 문자열 version만 PATCH한다. 사용자/역할·부모/일반 필드·이력 입력은 보내지 않는다. 자격증명 제외·캐시 금지·리다이렉트 거부·10초 제한/취소를 유지한다.
3. 기존 Controller → Service의 서버 행위자/부모/역할·공유 버전 → 상태/이력 동일 트랜잭션 → TaskView 200을 사용한다. 프론트는 대상 ID/두 부모·요청 상태·변경 시 다음 버전/동일 상태 시 같은 버전·일반 필드 불변을 검증한다. 버전은 문자열로 보존하고 비교/계산에만 BigInt를 사용한다.
4. 확인한 DTO로 현재 상세와 같은 ID 목록만 갱신한다. 추가 GET이나 다른 Task/Project 갱신은 없다. TaskBrowser는 이전 버전의 늦은 갱신을 버린다. 두 폼은 현재 입력/시작 기준을 각각 유지한다.
5. 409는 선택/입력 보존·저장 잠금이다. 최신 Task GET과 Workspace 역할 GET이 모두 성공해야 비교를 표시한다. 상태 폼은 시작/서버/선택 상태와 서버 일반 필드를, 일반 폼은 세 필드와 시작/서버 상태를 보여 준다. 명시적 기준 갱신은 입력/선택을 유지하며 PATCH를 보내지 않는다. 별도 수동 저장에서만 새 버전을 보낸다.
6. 복구 GET이 시작 기준 또는 이미 받은 props보다 오래된 버전이면 비교/기준 갱신을 허용하지 않는다. GET 실패·VIEWER 전환도 잠금을 유지한다. 재확인 실패는 이전 비교 결과를 지운다.
7. 네트워크/시간 초과/5xx/잘못된 200은 결과 불명이다. 이미 commit됐을 수 있어 자동 재전송하지 않는다. GET에 같은 상태가 보여도 이전 요청의 성공으로 처리하거나 잠금을 풀지 않는다. 명시적 비교·기준 갱신·별도 수동 저장을 유지한다. 400/403/404/405/413/415 확정 거부는 안전한 메시지와 입력 보존으로 처리한다.

## 변경 파일

- `frontend/src/api/tasks.ts`: 상태 PATCH·응답 확정/결과 불명 분류.
- `frontend/src/components/TaskStatusForm.vue`: 선택·독립 기준·복구·권한/취소.
- `frontend/src/components/TaskBrowser.vue`: 상태 폼 연결·표시 버전 역행 방지.
- `frontend/src/components/TaskUpdateForm.vue`: 상태 비교·오래된 복구 응답 거부.
- `frontend/src/style.css`: 기존 폼/select 스타일 재사용.
- 상태 API/폼 신규 테스트와 일반 폼 회귀 테스트. 새 패키지/라우터/전역 상태 계층은 없다.

## 실행한 검증

```powershell
# frontend/
npm.cmd test
npm.cmd run build

# 저장소 루트, Git 제외 검증기
pwsh -NoProfile -File backend/build/task-status-browser-verification/verify.ps1
```

- 최초 477개/21파일과 타입 검사·빌드가 통과했다. 오래된 복구 GET·재확인 실패 검증을 보강한 최종 결과는 **479개/21파일**, vue-tsc/Vite build 통과다. 테스트 실패는 없었다.
- 실제 Chrome/Vite/고유 PostgreSQL에서 MEMBER 상태 저장/중복 제출 방지·목록/상세, 일반→상태 및 상태→일반 충돌과 입력/기준 보존, 상태끼리 409, 비교·명시적 기준·수동 저장을 확인했다.
- commit 뒤 상태 PATCH의 200 응답만 CDP에서 끊었다. 결과 불명 잠금·선택 유지/미재전송, 같은 내용 GET을 성공 영수증으로 보지 않는 동작을 확인했다. VIEWER 폼 숨김·쓰기 off 405와 1280/390px 배치도 확인했다.
- 브라우저 상태 PATCH 6회/일반 PATCH 2회, 다른 HTTP 클라이언트 2회다. 격리 Task는 version 6, 상태 이력은 버전 1/4/5/6의 4건이며 일반 변경 버전 2/3에는 이력이 없다. 부모 Project/Workspace는 변하지 않았다.
- 실제 로컬 V7·5/3/6/1/1 + 이력 1건·Task IN_PROGRESS/version 2·Project version 1의 전체 스냅샷은 같다. 백엔드/infra/자격증명/JAR/package 등 167개 해시도 같다. 고유 Compose 자원·임시 자격증명 3개와 소유 앱/Vite/Chrome을 정리했다.

영수증은 Git 제외 `backend/build/task-status-ui-verification/receipt.json`의 VERIFIED다. 실제 명령·로그/스크린샷 경로는 [실행 기록](local-setup.md)에 남겼다. 백엔드가 변경되지 않아 이전 Java 전체 회귀는 반복하지 않았다.

[상태 이력 조회 계약](task-status-history-read-api-v1.md)을 확정했다. 다음 한 작업은 **Task 상태 이력 조회 API·최소 SELECT 권한 도구 구현과 격리 PostgreSQL 검증**이다. 현재 화면에는 이력 피드를 추가하지 않았다.
