# Task 보관·복구 구현

- 날짜: 2026-11-23
- 범위: V11, Task archive/restore API, 목록 계약, 최소 DB 권한 실행기, 격리 PostgreSQL 검증
- 로컬 DB migration/GRANT 및 Vue 관리 화면은 범위에 포함하지 않는다.
- 정책 기준: [Task archive 계약](task-archive-api-v1.md), [ADR-069](decisions.md#adr-069-task-보관-복구-정책-2026-11-23)

## 구현

- V11은 `flowdesk.tasks.archived_at TIMESTAMPTZ NULL`만 추가한다. 기존 row의 값은 null이며 Task status/history는 건드리지 않는다.
- `Task`는 archive timestamp를 보관하고 `@Version`으로 동시 변경을 검사한다. 새 Task INSERT에서는 컬럼을 쓰지 않고 DB NULL 기본값을 사용한다. `@DynamicUpdate`로 archive PATCH가 허용된 변경 컬럼만 갱신하게 했다.
- 목록은 기본적으로 활성 Task만 조회한다. `includeArchived=true`는 활성/보관 모두 반환한다. Task summary/detail에는 `archived`, detail에는 `archivedAt`이 추가됐다.
- `PATCH /api/workspaces/{workspaceId}/projects/{projectId}/tasks/{taskId}/archive`는 `{ "archived": boolean, "version": string }`만 허용한다. OWNER/ADMIN 검사, 부모 Project 활성 검사, version 확인, timestamp/version 갱신은 Service transaction에서 수행한다. 같은 목표 상태+현재 version은 no-op이다.
- archived Task의 일반 수정/상태 변경은 409 `TASK_ARCHIVED`다. 상세/상태 history 조회는 유지한다. 보관/복구는 별도 Activity를 기록하지 않는다.
- `TASK_ARCHIVE` 실행기는 다른 전용 관리자 도구처럼 명시적으로만 실행한다. 최신 Task 권한 상태에 `UPDATE(archived_at)`만 추가하고 기존 컬럼 UPDATE/SELECT/INSERT, Project archive, Workspace membership 관리 ACL을 보존한다. JPA hydrate를 위한 `SELECT(archived_at)`은 최신 Task SELECT 권한 흐름에서 추가한다. 새 기능 설정은 `flowdesk.dev.task-archive-enabled=true`이며 앱 시작 때 SELECT/UPDATE 권한을 확인할 뿐 GRANT하지 않는다.
- Vue Task API 파서는 새 응답 필드를 검증하고 `TASK_ARCHIVED` 오류 코드를 인식한다. Archive 버튼/필터 UI는 다음 단계다.

## 검증

격리 PostgreSQL Testcontainers에서 다음을 확인했다.

- V11은 기존 Task row를 보존하며 nullable archive timestamp를 추가한다. Validate 및 반복 migration은 각각 성공/0건이다.
- 권한 실행기의 최초/반복 실행과 이전 `TASK_ASSIGNEE_DUE_DATE` 도구 재실행 뒤 Task archive 권한이 유지된다. table-level Task UPDATE는 없고 총 9개 UPDATE 컬럼 중 `archived_at` 하나만 추가된다.
- OWNER 보관/복구, version 증가/no-op/stale 409, 기본 목록 제외와 `includeArchived=true`, archived Task 상세/history 읽기, MEMBER 403, 엄격한 입력, archived Task 수정/상태 409, archived Project 아래 archive 차단을 확인한다.
- 이전 migration/access 테스트에서 발견된 V11 호환 이슈를 수정했다. 구버전 스키마에서 아직 없는 `archived_at` 권한을 조회하면 PostgreSQL이 `undefined_column`을 반환하므로 verifier가 `pg_attribute` 존재 확인을 먼저 하도록 했다. JPA INSERT에 archive 컬럼이 실려 permission denied가 나던 문제는 `insertable=false`와 `@DynamicUpdate`로 해결했다.

## 최종 결과

- `gradle test --tests com.flowdesk.task.TaskArchiveApiTest --tests com.flowdesk.task.TaskApiTest --tests com.flowdesk.task.TaskUpdateApiTest --tests com.flowdesk.task.TaskStatusApiTest`: 146 tests passed, 0 failures. Testcontainers PostgreSQL에서 실제 앱 DB 계정으로 HTTP 경계를 검사했다.
- 별도 집중 실행에서 `TaskAssigneeDueDateAccessPolicyTest`, `V11MigrationTest`, `V7MigrationTest`, `V9MigrationTest`도 통과했다. V11 신규 컬럼 때문에 Task SELECT ACL이 늘어나는 경로, 기존 migration up-to-latest, 구버전 privilege preflight를 확인했다.
- 최종 권한 layering 회귀에서 `TaskArchiveApiTest` 5개, `TaskAssigneeDueDateAccessPolicyTest` 2개, `V11MigrationTest` 1개가 모두 통과했다. OWNER·ADMIN 및 MEMBER/비소속 경계, 과도한 `project_id` UPDATE ACL의 자동 거부/무수선, membership-management ACL을 포함한 기존 도구 재실행을 확인했다.
- `npm.cmd test -- --reporter=dot`: 26 files, 522 tests passed.
- `npm.cmd run build`: vue-tsc와 Vite production build passed.
- 신규/변경 PowerShell 실행기와 `verify-migration.ps1` 등 4개 파일의 PowerShell parser 검사 통과. Compose 기반 `verify-migration.ps1 -IncludeTaskArchive` 전체 스크립트는 실행하지 않았다.

실제 로컬 DB에는 V11 migration이나 GRANT를 적용하지 않았다. 다음 범위는 Vue archive 목록 필터·상태 표시·OWNER/ADMIN 보관/복구 화면이다.
