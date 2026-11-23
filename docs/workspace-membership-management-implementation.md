# Workspace 멤버십 관리 구현 기록

- 날짜: 2026-11-14
- 계약: [Workspace 멤버십 관리 v1](workspace-membership-management-v1.md)
- 결정: [ADR-065 정책](decisions.md#adr-065-workspace-멤버-초대역할제거-정책-2026-11-13), [ADR-066 구현](decisions.md#adr-066-workspace-멤버십-관리-apiv9최소-권한-구현-2026-11-14)

## 구현 범위

- V9 `workspace_invitations`: 기존 User/Workspace FK, ADMIN/MEMBER/VIEWER 역할, digest 형식/유일성, 상태와 만료·수락 제약, pending target 부분 UNIQUE.
- 개발 전용 HTTP: 초대 발급/취소, 대상 actor 수락, 역할 변경, 멤버 제거, OWNER 이전. 코드 원문은 발급 201 응답에만 포함한다.
- 서비스는 Workspace row를 먼저 잠그고 트랜잭션 안에서 행위자·현재 membership·대상·상태를 재검사한다. task 생성·수정·상태 변경은 공유 row lock을 써 제거와 재할당 경합을 직렬화한다.
- 멤버 제거는 membership row만 삭제한다. User/Task/상태 이력을 cascade하지 않으며 existing assignee ID를 유지한다.
- `provision-workspace-membership-management.ps1` → 전용 Gradle task → `AppWorkspaceMembershipManagementAccessRunner` 경로를 추가했다. membership DELETE와 role/updated_at UPDATE, invitation 매핑 열의 SELECT/INSERT/UPDATE만 더한다. 앱 startup 검증은 기존 쓰기 권한과 신규 관리 권한 각각의 유효 범위를 확인한다.

## 검증

- `backend`에서 Gradle `compileJava compileTestJava`: 성공.
- `backend`에서 `test --tests com.flowdesk.migration.V9MigrationTest --tests com.flowdesk.persistence.WorkspaceMembershipManagementAccessPolicyTest --tests com.flowdesk.workspace.WorkspaceMembershipManagementApiTest`: 6 tests 통과. HTTP 초대/재발급/취소/만료·대상 actor 검사/재사용 거부/역할 변경/제거/단일·동시 OWNER 이전/Origin·role 경계, 제거 뒤 Task assignee와 status actor 보존, ACL 최초·반복·부분상태 거부, V9 constraints를 확인했다.
- `backend`에서 Workspace/Task API 회귀 221 tests: 통과. 별도로 V6→현재 migration, seed, Project/runtime ACL 및 Repository read API도 확인했다.
- `pwsh -NoProfile -File infra/verify-migration.ps1 -IncludeWorkspaceMembershipManagement`: **153 checks 통과**. 고유 Compose 프로젝트에서 V1~V9, seed, 이전 권한 도구의 신규 ACL 보존, 신규 최소 권한/부분상태 거부, 자격증명·환경변수 보존을 검증했다. 임시 Compose 자원은 정리됐다.
- PowerShell parser: `migration-common.ps1`, `verify-migration.ps1`, 신규 provision/run wrapper 4개 문법 유효.
- 전체 `gradle test`는 모든 테스트 실행 완료까지 확인하지 않았다. 관련 API/migration/ACL 범위는 위 targeted 테스트와 153개 통합 검사를 실행했다.

## 로컬 적용 상태

로컬 적용 결과는 아래 2026-11-19 실행 기록을 따른다. 운영 로그인/메일/외부 초대는 미구현이며 API는 명시적으로 활성화하는 로컬 dev-actor 모드에 한정된다.

## Vue 연결 — 2026-11-19

- `workspace-membership.ts`가 발급/수락/취소/역할/제거/소유권 API를 엄격한 status·DTO·Location 검사로 연결한다. 실패한 쓰기는 재전송하지 않으며, 5xx/통신/잘못된 성공 응답은 결과 불명으로 잠근다.
- `WorkspaceMembershipManager.vue`는 멤버 페이지를 사용자가 열 때 조회하고 OWNER/ADMIN 관리 동작을 제공한다. `WorkspaceInvitationAcceptForm.vue`는 Workspace 선택과 무관하게 코드를 수락하고 성공 뒤 해당 공간 목록/상세를 다시 연다.
- 초대 코드는 유효한 단일 201 응답에서만 화면에 표시하며 발급 화면을 벗어나면 복구할 수 없다. 대기 목록 API는 백엔드에 추가됐지만 Vue에서 아직 호출하지 않는다. API 응답도 코드를 반환하지 않는다. 대상 계정으로 서버 dev-actor를 실행해야 하며 UI actor switch/실제 로그인/메일은 추가하지 않았다.
- `npm.cmd test`: 24 files/505 tests 통과. `npm.cmd run build`: vue-tsc와 Vite 성공. 2026-11-19 Chrome/Vite/로컬 PostgreSQL에서 초대 취소·재발급·1회 수락·만료 코드 거부, 구 코드·취소·재사용 코드 거부, Alice→Bob→Alice 소유권 이전, 390px 화면을 확인했다. 임시 행과 membership timestamp를 복구하고 전체 snapshot 비교가 통과했다. [대기 목록 API](workspace-invitation-list-implementation.md)는 2026-11-20 추가했고 격리 PostgreSQL 5개 테스트가 통과했다. 다음은 Vue 목록 조회/취소 연결이다.
