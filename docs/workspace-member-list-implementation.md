# Workspace 현재 멤버 목록 API 구현 기록

- 날짜: 2026-11-12
- 범위: 읽기 전용 Workspace 멤버 endpoint, DTO projection, 정확한 현재 mapped-column SELECT ACL 전환 도구, 격리 PostgreSQL 검증
- 계약: [workspace-member-list-api-v1.md](workspace-member-list-api-v1.md), [ADR-062](decisions.md)

## 요청에서 DB까지

`GET /api/workspaces/{workspaceId}/members?page=0&size=20`는 dev-actor 프로파일에서만 등록된다. Controller는 workspace/page/size와 중복·알 수 없는 query key를 strict parsing한다. Service는 서버 actor의 현재 membership부터 확인하고 미소속/미존재를 같은 404로 돌려준다. 이후 native projection query가 같은 Workspace의 현재 membership과 User를 조인해 `id`, `display_name`, `role`만 사용자 ID 오름차순 Slice로 읽는다. DTO는 ID를 십진 문자열로 반환하며 이메일·Entity는 노출하지 않는다. 네 역할 모두 API를 사용할 수 있다. Task 저장 Service의 별도 membership 재검증은 그대로 유지한다.

## 최소 SELECT 정책

기존 앱 역할은 `users`, `workspaces`, `workspace_memberships` 테이블 단위 SELECT를 사용했다. 그 상태에서 신규 컬럼 권한을 추가해도 좁은 정책이 되지 않으므로 전용 관리자 실행기가 현재 JPA 동작에 필요한 컬럼만 한 트랜잭션에서 grants로 바꾼다.

- users: `id,email,display_name,created_at,updated_at` (5)
- workspaces: `id,name,created_at,updated_at,version` (5)
- workspace_memberships: `id,user_id,workspace_id,role,created_at,updated_at` (6)

총 16개 컬럼이며 세 테이블의 table-wide SELECT는 제거한다. 미래 컬럼이 자동으로 읽히지 않는다. runner는 대상 URL·기존 app 비밀번호·Flyway 이력·현재 ACL을 먼저 확인하고, 부분/과도한 권한은 자동 수선하지 않는다. 관리자 도구는 앱 시작 시 실행되지 않는다.

## 변경 파일

- Workspace Controller/QueryService/Repository에 strict GET, 현재 membership 인가, Slice 및 projection을 추가했다.
- `WorkspaceMemberList`는 응답 필드와 문자열 ID를 고정한다.
- `AppReadPolicy`는 기존 broad state 또는 승인된 exact 16-column state만 받아들이고 혼합/부분 권한을 거부한다.
- migration JavaExec, 전용 SQL policy, PowerShell provision wrapper를 추가했다. `infra/verify-migration.ps1 -IncludeWorkspaceMemberListRead`는 전체 선행 권한 단계 뒤 이 도구를 격리 Compose DB에서 검사한다.

## 격리 검증

2026-11-12, 사전 seed 데이터가 있는 격리 PostgreSQL에서 기존 ACL 누적 상태를 exact columns로 바꾸고 반복 Apply가 idempotent임을 확인했다. table-wide SELECT 3개가 사라지고 exact 16 column SELECT만 남았다. 기존 최소 SELECT 회귀에서 부분 컬럼/과도/PUBLIC/다른 role/default grant 거부가 유지됐다. 멤버 API는 OWNER/ADMIN/MEMBER/VIEWER 각각 본인 공간에서 200, 미소속/미존재 동일 404, strict page query, ID 순서·페이지 envelope·email 제외를 검사했다. 신규 권한 상태에서 기존 Workspace API 앱 기동 및 테스트 조회도 통과했다.

- Gradle targeted run: 3 test classes, 76 tests passed, 0 failed/skipped.
- PowerShell parser: 신규/수정 wrapper 4개 파일 통과.
- Vue 연결 (2026-11-13): TaskAssigneeSelect가 page API를 포커스 시 로드하고 이름·역할·ID를 보여 준다. 생성/수정에서 선택 ID를 기존 Task API에 연결하고 오류 재시도/abort와 과거 할당 유지 option을 처리했다. Frontend 전체 496개 테스트와 production build 통과.
- Former-member assignment: Task update는 기존 ID를 그대로 유지할 때 membership을 재검사하지 않고, assignee 변경은 현재 membership을 검사한다. `TaskUpdateApiTest` 40개 통과; 기존 데이터/멤버십 snapshot 복원 확인.
- 실제 로컬 적용은 아래 2026-11-13 기록을 참조한다.

## 실제 로컬 exact-column 권한 적용 및 멤버 목록 HTTP — 2026-11-13

사용자 요청으로 `pwsh -NoProfile -File infra/provision-workspace-member-list-read.ps1`을 실행했다. 전용 runner가 기존 앱 자격증명으로 인증하고 Flyway 이력을 검증한 뒤 단일 트랜잭션에서 세 테이블의 table-wide SELECT를 제거하고 승인된 16개 컬럼 SELECT 정책을 적용했다. 실행 결과는 `Application workspace-member-list-read access: TASK_ASSIGNEE_DUE_DATE; existing password preserved.`였다. `TASK_ASSIGNEE_DUE_DATE`는 이 정책 적용 후 현재 누적 앱 권한 상태를 뜻하며, 비밀번호 회전이나 스키마 migration은 없었다.

최신 소스가 든 JAR로 로컬 PostgreSQL에 연결할 수 있도록 Gradle `bootJar`를 실행했다. Wrapper는 네트워크 제한으로 배포판을 받지 못해 실패했으나, 이미 설치된 Gradle 8.14.3으로 `bootJar --no-daemon --console=plain`을 실행해 성공했다(컴파일 최신, `BUILD SUCCESSFUL`). 별도 `dev-actor` 앱을 loopback `127.0.0.1:18081`에서 짧게 실행했다. Actuator `/actuator/health`는 200/UP, DB 연결 풀 및 JPA 초기화가 성공했고, `/api/workspaces/1/members?page=0&size=20`은 200으로 Alice(OWNER), Bob(ADMIN), Carol(MEMBER), Dave(VIEWER) 네 명을 반환했다. 응답 필드는 ID·표시 이름·역할뿐이고 이메일은 없었다. 검증은 GET만 수행했다.

최초 검증기 실행에서 PowerShell의 byte[] 응답을 JSON으로 직접 읽어 실패했지만, 이는 HTTP 서버 오류가 아니었다. UTF-8 디코딩을 명시한 뒤 검증을 다시 실행해 통과했다. 임시 앱 프로세스를 종료하고 18081 listener가 없는 것을 확인했다. 기존 8080 앱은 종료하지 않았으며 실제 사용자 데이터에 쓰는 요청은 보내지 않았다. 영수증과 비밀정보를 제거한 앱 로그는 Git 제외 `backend/build/local-workspace-member-list-verification/` 아래 있다.

실행한 검증: dedicated ACL apply 성공, `bootJar` 성공, 새 앱 Actuator 200/UP 및 실제 멤버 목록 HTTP 200/email 미노출 확인. 이번 로컬 검증에서는 JUnit/Vue 테스트를 다시 실행하지 않았다. 정책은 [멤버십 관리 계약](workspace-membership-management-v1.md)과 ADR-065에 확정했다. 다음 한 작업: 멤버십 관리 API·초대 V9·최소 권한 도구 구현 및 격리 PostgreSQL 검증이다.

## 다음 한 작업

멤버십 관리 API·초대 V9·최소 권한 도구를 구현하고 격리 PostgreSQL에서 검증한다.
