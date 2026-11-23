# Task 보관·복구 계약 v1

- 날짜: 2026-11-23
- 상태: V11/API/최소 권한 도구 구현 및 격리 PostgreSQL 검증 완료. 실제 로컬 DB/권한 적용은 별도 작업
- 결정: [ADR-069](decisions.md#adr-069-task-보관-복구-정책-2026-11-23)
- 기준: [요구사항](requirements.md), [아키텍처](architecture.md), [Task 생성/조회](task-model-and-api-v1.md), [Task 수정](task-update-api-v1.md), [Task 상태](task-status-api-v1.md), [Project 보관](project-archive-api-v1.md)

## 확정 정책

| 항목 | 결정 |
|---|---|
| 보관 방식 | Task row를 삭제하지 않는 복구 가능한 soft archive |
| 보존 | status, 제목/설명, 우선순위, 담당자, 마감일, Task 상태 이력을 그대로 유지 |
| 목록 | 기본 Task 목록은 활성 Task만 반환. 모든 현재 Workspace 멤버는 `includeArchived=true`로 보관 Task를 포함해 조회 |
| 읽기 | 보관 Task 상세와 상태 이력은 계속 조회 가능. 미소속/미존재/URL 부모 불일치는 기존과 같은 404 |
| 보관/복구 권한 | 현재 Workspace OWNER·ADMIN. 올바른 Task를 가진 MEMBER·VIEWER는 403 |
| 동시 변경 | 기존 Task `version` 사용. stale 요청은 409. 현재 상태의 동일 요청은 version을 바꾸지 않는 no-op |
| 쓰기 차단 | archived Task의 일반 수정과 상태 변경은 409 `TASK_ARCHIVED`. 자원/역할 인가 확인 뒤 오류를 반환 |
| 부모 Project | Project가 archived면 Task 보관/복구는 409 `PROJECT_ARCHIVED`; Project 복구 뒤 수행 |
| Activity | 상태 Activity에는 보관/복구를 기록하지 않는다. status history는 상태 변화에만 사용하며 별도 archive audit trail도 이번 범위에서 만들지 않는다 |
| 후속 제외 | 영구 삭제, cascade, 댓글, 칸반, 필터, 알림, 별도 Activity 종류, 실제 인증 |

Task 생성은 계속 Project가 활성일 때 OWNER·ADMIN·MEMBER에게 허용된다. Project 보관은 기존 계약대로 Project 아래 Task 생성을 포함한 모든 Task 변경을 차단한다. Task 자체를 보관해도 status, due date, assignee 또는 기존 history는 수정하지 않는다.

## 모델·마이그레이션

V11은 `flowdesk.tasks.archived_at TIMESTAMPTZ NULL`만 추가한다. null은 활성, 값은 UTC 보관 시각이다. 별도 `is_archived`, archive table, audit/activity table은 추가하지 않는다. Task Entity/상세 DTO는 `archivedAt`, 목록/상세의 공개 응답은 `archived` 상태를 제공한다.

보관과 복구는 Task 자신의 `version`을 사용한다. 상태가 바뀌면 `archived_at`, `updated_at`, `@Version`이 한 트랜잭션에서 변경된다. 같은 목표 상태를 현재 version으로 요청하면 no-op이고 version은 증가하지 않는다. stale version이면 상태가 이미 목표와 같더라도 409다.

```http
GET /api/workspaces/3/projects/21/tasks?includeArchived=true&page=0&size=20
```

query는 정확한 단일 boolean `true`/`false`만 받는다. 생략/false는 활성만, true는 전체를 ID 오름차순으로 반환한다. 중복/알 수 없는 query는 400이다. 기존 0-based page/size 규칙을 유지한다.

## 보관·복구 HTTP 계약

```http
PATCH /api/workspaces/3/projects/21/tasks/8/archive
Content-Type: application/json
Origin: http://127.0.0.1:5173

{"archived":true,"version":"4"}
```

- JSON은 `archived` 필수 boolean과 `version` 필수 정규 10진 문자열만 허용한다. 누락/null/다른 타입/알 수 없는 필드/중복 키/query는 400이다.
- 성공은 200과 업데이트된 Task 상세 DTO. no-op은 현재 Task 상태와 version이 맞을 때 200이다.
- stale Task version은 409, 보관 중 update/status는 409 `TASK_ARCHIVED`, 보관 Project 부모는 409 `PROJECT_ARCHIVED`다.
- Project/Task 경로 부모가 맞지 않거나 미소속/미존재면 동일 404, 현재 Workspace MEMBER·VIEWER의 archive/restore는 403이다.
- 기존 dev-actor·서버 지정 actor·loopback·허용 Origin·JSON·Workspace-write 경계를 재사용한다. archive API는 `flowdesk.dev.task-archive-enabled=true`로 별도 켠다. 기본 프로필과 쓰기 off는 비활성으로 유지한다.

## 트랜잭션·동시성·최소 권한

Task archive command는 현재 Workspace membership, Project 부모, Task 부모, 역할, Project 상태와 Task version을 Service transaction에서 확인한다. Archive, Task update, status change, Project archive가 기존 Workspace row lock을 공유해 같은 공간의 Task/Project 변경을 직렬화한다. Project가 archived면 그 자식 Task의 archive/restore도 거부한다. Entity `@Version`은 Task 상세를 읽은 뒤 발생한 stale 변경을 검출한다.

현재 Task SELECT는 12개 매핑 컬럼으로 제한된다. V11 이후 JPA가 새 필드를 읽을 수 있도록 기존 `TASK_ASSIGNEE_DUE_DATE` 권한 정책에 `archived_at` column SELECT를 더한다. 별도 `TASK_ARCHIVE` 권한 도구는 기존 Task UPDATE 권한을 보존하고 `archived_at` column UPDATE만 추가한다. table-wide UPDATE, DELETE, TRUNCATE, sequence, Activity/DDL 권한은 부여하지 않는다. 새 archive mode 앱은 SELECT/UPDATE column privilege를 확인하지만 GRANT하지 않는다.

## 구현·격리 수용 기준

V10+Task 데이터가 있는 고유 PostgreSQL에서 V11 1회/Validate/반복 0건, 기존 Task 필드·version·activity·ACL·시퀀스 보존을 확인한다. 격리 API는 OWNER/ADMIN 보관·복구/no-op/stale 409, MEMBER/VIEWER 403, 부모/미소속 404, 기본 목록 제외/`includeArchived` 포함, 상세·history GET 허용, archived Task update/status 409, archived Project parent 거부, 동시 Task mutation 직렬화, 정확한 column UPDATE ACL과 모든 이전 grant 도구 보존을 검사한다. 구현 뒤 Vue 목록 filter/badge, archive confirmation, read-only detail, restore와 충돌/결과 불명 복구를 연결한다.

실행 결과와 격리 테스트는 [Task 보관 구현 기록](task-archive-implementation.md)에 남긴다. 실제 로컬 DB migration/GRANT, Task 보관 UI는 별도 단계다.
