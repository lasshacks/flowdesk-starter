# Project 보관·복구 API 계약 v1

- 날짜: 2026-11-23
- 상태: API·V10·권한 도구 구현/격리 검증, 실제 로컬 적용, Vue UI 연결/브라우저 검증 완료. 결과는 [구현 기록](project-archive-implementation.md)과 [로컬 실행](local-setup.md)을 따른다.
- 관련 결정: [ADR-068](decisions.md#adr-068-project-보관-복구-정책-2026-11-23)
- 선행 계약: [Project 생성/조회](project-model-and-api-v1.md), [Project 수정](project-update-api-v1.md), [Task 생성/조회](task-model-and-api-v1.md), [Task 수정](task-update-api-v1.md)

## 확정 정책

| 항목 | 결정 |
|---|---|
| 보관 방식 | 복구 가능한 soft archive. Project/Task 행을 삭제하지 않는다 |
| 연결된 Task | 상태·담당자·마감일·이력은 유지한다. 보관 중 Task 조회/상태 이력 조회는 허용하고 생성·수정·상태 변경은 막는다 |
| Project 편집 | 보관 중 이름/설명 수정은 막는다. 복구만 허용한다 |
| 조회 | 기본 Project 목록은 활성 항목만 반환한다. 모든 현재 Workspace 멤버는 `includeArchived=true`로 보관 항목을 포함해 조회할 수 있고, archived Project 상세와 Task/이력도 읽을 수 있다 |
| 보관/복구 권한 | 현재 Workspace OWNER·ADMIN만 가능하다. MEMBER·VIEWER는 403, 미소속/미존재/부모 불일치는 기존 동일 404 규칙을 유지한다 |
| 동시 변경 | 기존 Project `version`을 사용한다. 오래된 버전은 409, 현재 상태와 같은 요청은 version을 바꾸지 않는 no-op이다 |
| 기록 | `archived_at`에 UTC instant를 저장하고 복구 시 null로 지운다. Task 상태나 Task version을 바꾸거나 Task status Activity를 만들지 않는다 |

## 모델·마이그레이션

V10은 `flowdesk.projects.archived_at TIMESTAMPTZ NULL` 컬럼만 추가한다. null은 활성, 값이 있으면 보관 상태다. 현재 Project version 컬럼을 재사용하며 별도 `is_archived`, Task별 보관 필드, cascade delete, 새 Activity 테이블/이력은 추가하지 않는다. 보관은 `archived_at`을 설정/해제하며 `updated_at`과 Project version을 증가시킨다. 현재 상태와 보관 시각 외의 별도 감사 이력은 이 범위에 포함하지 않는다.

Project 목록/상세 DTO는 `archived`를 반환하고 상세 DTO는 `archivedAt`과 기존 문자열 `version`도 반환한다. Project 목록 query는 `includeArchived`를 추가한다. 생략/`false`는 활성만, 엄격한 boolean 문자열 `true`는 활성+보관 항목을 ID 오름차순으로 반환한다. 알 수 없거나 중복된 query는 400이다. Workspace 소속 확인과 기존 0-based page/size slice 규칙은 유지한다.

```http
GET /api/workspaces/3/projects?includeArchived=true&page=0&size=20
```

## 상태 변경 HTTP 계약

보관과 복구는 명시적 목표 상태를 전송해 재시도 의도를 모호하게 하지 않는다.

```http
PATCH /api/workspaces/3/projects/21/archive
Content-Type: application/json
Origin: http://127.0.0.1:5173

{"archived":true,"version":"3"}
```

- `archived`는 필수 boolean, `version`은 정규 10진 문자열이다. 누락/null/잘못된 타입·알 수 없는 필드·중복 JSON 키·query는 400이다.
- 보관/복구 성공은 200과 업데이트된 Project 상세 DTO를 반환한다. 같은 목표 상태이고 version이 현재 값이면 200 no-op이다. 요청 version이 오래되면 상태가 우연히 같아도 409이며 자동 재전송하지 않는다.
- 미소속/미존재/부모 경로 불일치는 동일 404, 실제 Workspace 멤버지만 OWNER/ADMIN이 아니면 403, stale Project version은 409다.
- 쓰기는 기존 `dev-actor`, 명시적 Workspace write 활성화, loopback 및 허용 Origin/JSON 경계를 따른다. 기본 프로필과 쓰기 off는 계속 비활성이다. 실제 인증은 Phase 2다.

## Project·Task 쓰기 경계

보관 중 Project rename은 409 `PROJECT_ARCHIVED`로 거부한다. Task 생성·제목/설명/우선순위/담당자/마감일 수정·상태 변경도 409 `PROJECT_ARCHIVED`다. 해당 Workspace의 OWNER·ADMIN·MEMBER 역할 검사는 유지하고, `PROJECT_ARCHIVED`는 자원·역할 검증을 통과한 쓰기 요청에만 돌려 상태 정보가 인가를 우회하지 않게 한다. Task 조회와 상태 변경 이력 조회는 모든 현재 Workspace 멤버에게 계속 허용한다. 복구 후 별도 상태 초기화 없이 기존 Project/Task 쓰기가 다시 가능하다.

## 계층 흐름과 최소 권한

`PATCH` strict JSON/ID parsing → 서버 actor와 현재 Workspace membership → URL 부모와 Project 확인 → OWNER/ADMIN → 요청 version → 상태 변경 → `updated_at`/`@Version` flush·commit → DTO/200 흐름이다. Task write service들은 기존 Workspace 잠금 규약 아래 Project 상태를 확인하고, active 여부를 Task mutation 직전에도 검사한다. Archive command도 같은 Workspace row lock을 먼저 획득해 Task command와 직렬화한다. Project rename과 archive는 공유 Project version으로 서로의 선행 변경을 stale 409로 검출한다.

`archived_at` read는 기존 `projects` table-level SELECT에 포함되므로 추가 SELECT grant는 없다. 별도 `PROJECT_ARCHIVE` 권한 runner는 기존 `PROJECT_UPDATE` 상태를 보존하면서 `archived_at` column UPDATE만 추가한다. `updated_at`/`version`은 Project 수정 capability가 이미 부여한 정확한 열이다. table-wide UPDATE, DELETE/TRUNCATE, sequence/history/DDL 권한은 추가하지 않는다. Archive API는 기존 명시적 workspace-write 설정과 별도로 `flowdesk.dev.project-archive-enabled=true`일 때만 노출한다. 이 모드의 앱 부팅 검사는 `archived_at` UPDATE가 있는지 확인하며 앱 스스로 GRANT하지 않는다.

Task DB schema/ACL에는 새 컬럼을 추가하지 않는다. Task Service는 Project의 `archived_at`을 읽어 mutation을 막으므로 해당 Project SELECT 권한은 읽기 전용 모드에도 필요하다. V10과 Project 보관 ACL 적용 뒤 새 앱을 기동한다.

## 격리 수용 기준 및 범위

V9+Project 데이터가 있는 고유 PostgreSQL에서 V10 1회/Validate/반복 0회, 기존 행·버전·ACL·시퀀스 보존, 컬럼·현재 Project `@Version`을 확인한다. OWNER/ADMIN 보관·복구와 no-op, stale 409, MEMBER/VIEWER 403, 미소속/부모 불일치 404, 기본 목록 제외/`includeArchived` 포함/상세 조회, Task 생성·수정·상태 변경 409와 Task/이력 GET 보존, 동시 archive 대 Task write, 정확한 column ACL과 모든 이전 권한 실행기의 보존을 검증한다. Vue 보관 토글/필터·복구 확인·실패/409 UI는 API 구현 후 별도 소비자 작업이다.

이번 결정은 정책·계약 문서만 확정한다. 코드, migration, 권한, 실제 로컬 DB/화면은 변경하지 않았다.
