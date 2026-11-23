# Task 상태 변경·최소 Activity 계약

- 날짜: 2026-11-08
- 상태: 2026-11-08 정책 확정 후 구현/격리 검증·실제 로컬 V7/최소 권한·상태/이력 확인을 완료했다. 2026-11-10 [Vue 상태 UI·공유 버전/409 복구](task-status-ui.md)도 연결하고 검증했다. [구현 기록](task-status-implementation.md)과 [실행 기록](local-setup.md)을 따른다. Activity 조회는 후속이다.
- 기준: [요구사항](requirements.md), [아키텍처](architecture.md), [기술 스택](tech-stack.md), [로드맵](roadmap.md), [Task 생성/조회](task-model-and-api-v1.md), [일반 수정 계약](task-update-api-v1.md).
- 현재 구현/로컬: V7/TASK_STATUS_CHANGE·5/3/6/1/1 + 상태 이력 1건, Task 1 version 2/HIGH/IN_PROGRESS와 Project 1 version 1. Carol의 TODO→IN_PROGRESS 이력만 추가했고 일반 필드/부모는 유지했다. 검증 앱은 종료했고 DB는 실행 중이다.
- 계약 완료 기준: 전이/역할·HTTP·공유 버전/no-op·이력 원자성·V7/최소 권한을 확정한다. 구현/격리·실제 로컬과 Vue 연결 결과는 각 기록에 구분한다.

## 사용자와 확정한 정책

| 항목 | 선택 |
|---|---|
| 상태 | 기존 TODO / IN_PROGRESS / DONE 세 값 유지 |
| 허용 전이 | 서로 다른 세 상태 사이 모든 전이 허용. 바로 완료·완료 취소/재개도 가능 |
| 권한 | 해당 Workspace OWNER / ADMIN / MEMBER가 공간 내 모든 Task 상태 변경 가능 |
| 접근 거부 | VIEWER는 403. 미소속·미존재·URL 부모 불일치는 같은 404 |
| 최소 이력 | 실제 상태 변경마다 서버 행위자 ID·이전/이후 상태·시간·새 Task 버전을 같은 트랜잭션으로 기록 |
| 원자성 | 이력 저장 실패 시 상태 변경도 rollback. 상태만 성공한 응답 금지 |
| 후속 | Activity 조회 API/UI, 일반 필드 수정/생성 이력, 담당자/마감일·댓글/보관, 알림/실시간 |

다른 공간의 역할은 현재 부모에 적용하지 않는다. 작성자/담당자 기반 제한·Project/Task 별도 membership은 추가하지 않는다. MEMBER의 Project 생성/수정 권한은 여전히 없고 네 역할의 Task 조회 권한은 유지한다.

## 전이 행렬

행은 현재 DB 상태, 열은 요청 목표 상태다. 서버가 읽은 현재 상태와 버전을 사용한다.

| 현재 → 목표 | TODO | IN_PROGRESS | DONE |
|---|---|---|---|
| TODO | no-op | 허용 | 허용 |
| IN_PROGRESS | 허용 | no-op | 허용 |
| DONE | 허용 | 허용 | no-op |

추가 승인/완료 잠금/이유 입력은 요구하지 않는다. 같은 상태는 현재 버전일 때만 no-op이며 stale 버전이면 409다. 지원하지 않는 상태는 전이 오류가 아니라 입력 400이다. 이번 행렬에는 형식상 유효하지만 금지된 서로 다른 상태 전이가 없다.

## HTTP 입력·응답

```http
PATCH /api/workspaces/1/projects/1/tasks/1/status
Content-Type: application/json
Accept: application/json
Origin: http://127.0.0.1:5173

{"status":"IN_PROGRESS","version":"1"}
```

- 구현 기본값은 status/version 두 필드 모두 필수인 전용 PATCH다. status는 정확한 대문자 TODO/IN_PROGRESS/DONE만 허용한다. 누락/null/숫자/빈 값/앞뒤 공백/소문자/미지원 값은 400이며 자동 정규화하지 않는다.
- version은 기존 상세 GET의 **Task 버전**이다. 0~Long.MAX_VALUE 정규 십진 문자열, 숫자/null/음수/부호/선행 0/범위 초과는 400이다. 별도 상태 버전·부모 버전은 도입하지 않는다.
- 세 경로 ID는 기존 양의 정규 십진 문자열/Long 범위다. fromStatus/actorUserId/userId/role/부모 ID·Task ID/title/description/priority/시간·이력 정보와 모든 미지원 필드, 중복 키/후행 JSON/잘못된 UTF-8·JSON/쿼리 파라미터는 400이다. 이전 상태/행위자/새 버전/시각은 서버만 정한다.
- 기존 Task 쓰기의 실제/chunked 본문 32 KiB/413·UTF-8 application/json/Content-Encoding 정책/415, dev-actor·서버 지정 사용자·loopback·명시적 Origin/쓰기 flag를 재사용한다. 기본 프로필 업무 경로 404·dev-actor 쓰기 off 405를 유지한다. `/status`에는 GET 대응 경로가 없어 쓰기 off 전용 거부 Controller로 405를 명시한다. 실제 인증은 Phase 2다.
- 현재 개발 쓰기 필터는 `/api/workspaces/` 하위 PATCH를 이미 검사한다. 깊은 `/status` 경로에서도 실제 HTTP/Vite로 검사되는지 확인한다.

```http
HTTP/1.1 200 OK
Content-Type: application/json
Cache-Control: no-store

{"id":"1","workspaceId":"1","projectId":"1","title":"작업","description":null,"status":"IN_PROGRESS","priority":"HIGH","version":"2"}
```

응답은 기존 TaskView 여덟 필드다. 생성은 계속 TODO/version "0"이고 일반 수정 PATCH는 status를 계속 거부한다. 이력 ID/행위자·시간은 이번 응답에 추가하지 않는다. 목록 요약 여섯 필드·경로/페이지 계약도 유지한다. commit 성공 후만 200을 반환한다.

## 공유 버전·no-op·오류 순서

- 상태와 일반 필드 수정은 같은 Task version을 공유한다. 같은 Task/version에서 제목 수정과 상태 변경이 경합하면 한 건만 commit되고 다른 건은 **409/TASK_VERSION_CONFLICT**다. 서로 다른 Task는 독립적이며 부모 Project/Workspace 버전·시각은 바꾸지 않는다.
- 실제 상태 전환은 status/updated_at/version만 바꾸고 version을 1 증가시킨다. title/description/priority/id/project_id/created_at과 부모 데이터는 유지한다. 서버가 한 번 정한 시각을 Task updated_at과 이력 occurred_at에 사용한다.
- 현재 버전·같은 상태 요청은 200/no-op, 상태/버전/시각 그대로·이력 0건이다. 트랜잭션 종료 시 낙관적 검증은 유지한다. stale 요청은 이미 목표 상태가 되어 있어도 409다.
- 순서는 개발 접속/쓰기·Origin → HTTP 입력 → 서버 행위자 → Workspace membership → Project id+workspaceId → Task id+projectId → 역할 → 요청 버전 → 상태/no-op → 저장/DB 버전 검사다. VIEWER의 올바른 Task는 stale여도 403/TASK_FORBIDDEN이고 없는 Task/잘못된 부모는 404/TASK_NOT_FOUND다. 양쪽 공간 소속 사용자도 잘못된 부모는 404다.
- 기존 400/403/404/405/409/413/415/503/500·안전한 오류/민감정보 비노출을 재사용한다. SQL 권한/Activity 제약 오류를 무조건 버전 409로 바꾸지 않는다. 이력 저장 실패는 전체 rollback 뒤 기존 안전한 500/503 분류를 따른다.
- 자동 병합/재전송하지 않는다. 200 유실 뒤 같은 이전 version을 보내면 409가 될 수 있다. 버전/이력 UNIQUE는 idempotency key나 특정 요청의 성공 영수증이 아니다.

## 최소 Activity 데이터 모델·V7

전용 테이블 `flowdesk.task_status_activities`를 사용한다. 이는 첫 Activity의 **상태 전이만** 저장하며 범용 이벤트 JSON/모든 수정 감사 기능은 도입하지 않는다.

| 컬럼 | 형식/제약 | 책임 |
|---|---|---|
| id | bigint GENERATED ALWAYS AS IDENTITY, PK | DB 생성, 이번 API에서 반환하지 않음 |
| task_id | bigint NOT NULL, tasks(id) FK / ON DELETE RESTRICT | 대상 Task |
| actor_user_id | bigint NOT NULL, users(id) FK / ON DELETE RESTRICT | 서버가 식별한 기존 행위자 |
| from_status | varchar(16) NOT NULL, 기존 세 상태 CHECK | 전환 전 DB 상태 |
| to_status | varchar(16) NOT NULL, 기존 세 상태 CHECK | 전환 후 상태 |
| task_version | bigint NOT NULL, CHECK > 0 | 전환 후 Task 버전 |
| occurred_at | timestamptz NOT NULL | 서버 변경 시각 |

- CHECK from_status <> to_status, UNIQUE(task_id, task_version)를 둔다. UNIQUE 인덱스가 Task별 버전 조회 기반도 제공하므로 중복 인덱스를 미리 추가하지 않는다. actor/시간별 검색 인덱스는 조회 요구 때 정한다.
- Task version은 일반 필드 수정에도 증가하므로 이력 버전은 연속일 필요가 없다. 현재 tasks.version과 FK로 묶지 않는다. 원래 Task가 다음 버전으로 바뀌어도 과거 이력은 남아야 한다.
- 이메일/표시 이름·제목/설명·자유 입력 사유·workspace_id/project_id는 복제하지 않는다. 작성 당시 역할 스냅샷/사용자 삭제·탈퇴·데이터 보존 정책은 이번에 추가하지 않으며 향후 해당 API 도입 시 재검토한다.
- V7 예정 파일은 `backend/src/main/resources/db/migration/V7__create_task_status_activities.sql`이다. 새 테이블/FK/CHECK/UNIQUE만 만들고 V1~V6 SQL/기존 행·상태/시간·Task version·이력/ACL·소유권/시퀀스는 보존한다. tasks의 기존 상태 CHECK는 세 값이 이미 있어 바꾸지 않는다.
- 이력은 빈 상태로 시작한다. 기존 Task 상태·생성/수정을 허구의 행위자/시간으로 backfill하지 않는다. 상태 no-op/Task 생성/일반 수정/거부·충돌/rollback에는 상태 이력을 남기지 않는다.
- 앱에 이력 UPDATE/DELETE 권한을 주지 않는 범위의 append-only다. DB 관리자까지 변경을 못 하는 변조 방지 저장소라고 주장하지 않는다. 알림/WebSocket/Kafka/Outbox·Activity 조회 API/UI는 별도다.

## 상태 변경·이력의 단일 트랜잭션 설계

상태 전용 Service의 readOnly=false/READ_COMMITTED/예외 rollback 트랜잭션에서 소속/두 부모/역할·버전을 확인한다. 실제 전환은 현재 상태/버전을 캡처→조건부 상태 UPDATE→새 Task 버전 확인→이력 INSERT→트랜잭션 프록시 commit→DTO/200 순서다. 이력은 afterCommit/비동기 별도 저장으로 분리하지 않는다.

구현 기본안은 기존 `Task.status`의 `updatable=false`를 유지하고 전용 mandatory 어댑터에서 EntityManager native SQL의 상태 UPDATE를 명시적으로 수행하는 방식이다.

- `UPDATE ... SET status=?,updated_at=?,version=version+1 WHERE id=? AND version=?`의 영향 행 수가 1이어야 한다. 0이면 TaskVersionConflict로 rollback/409다. JPA native SQL에는 @Version 자동 증가/검증이 적용된다고 가정하지 않고 **직접 버전 조건/증가·행 수 검사**를 구현한다.
- 상태 전용 경로에서는 관리 Entity의 다른 필드를 먼저 변경하지 않는다. native SQL 전후의 자동 flush로 버전이 이중 증가하지 않게 하고, 성공 후 refresh로 관리 Entity의 status/version/updated_at을 실제 행에 맞춘다. 같은 트랜잭션의 UPDATE 잠금 아래에서 읽은 결과로 이력/DTO를 만든다.
- 이력 INSERT는 고정 여섯 컬럼에 바인딩하며 `RETURNING`/generated keys/후속 SELECT 없이 수행한다. 이번 API는 이력 ID가 필요 없으므로 이력 SELECT·직접 시퀀스 권한을 요구하지 않도록 한다. flush/native INSERT/최종 commit 실패는 예외를 삼키지 않고 상태·이력 모두 rollback한다.
- no-op은 상태 UPDATE/이력 INSERT를 하지 않고 기존 OPTIMISTIC 트랜잭션 종료 검증을 적용한다. 일반 수정은 기존 @Version 경로를 유지하고 status는 SQL UPDATE 대상에서 계속 제외된다. 두 경로는 같은 DB version 조건으로 서로 경합한다.
- 대안은 status를 수정 가능한 JPA 매핑으로 바꾸는 방식이다. 기본 정적 dirty-check UPDATE가 일반 수정 때도 status를 포함할 수 있어, 컬럼 범위를 지킬 별도 대책과 검증이 필요하다. 이번 기본안은 수동 조건부 SQL의 책임을 명시하고 일반 수정 매핑을 유지한다. 구현에서 수동 SQL/refresh·커밋 경합을 격리 테스트로 검증해야 한다.

## 최소 권한·관리자 도구·현재 이력 전환

- 새 상태명은 예정 `TASK_STATUS_CHANGE`다. 기존 TASK_UPDATE 전체를 유지하며 **tasks UPDATE(status)** 한 컬럼과 **task_status_activities INSERT(task_id,actor_user_id,from_status,to_status,task_version,occurred_at)** 여섯 컬럼만 추가한다.
- 합쳐진 Task UPDATE는 기존 다섯 컬럼 + status의 정확한 여섯 컬럼이다. 여전히 Task 테이블 전체 UPDATE·id/project_id/created_at·DELETE/TRUNCATE는 금지다. 새 이력 테이블에는 SELECT/테이블 전체 INSERT/id INSERT·모든 UPDATE/DELETE/TRUNCATE·REFERENCES/TRIGGER 등의 추가 권한·시퀀스/DDL/history 권한을 주지 않는다. 이력 조회는 향후 별도 권한/인가 계약으로 다룬다.
- 예정 `infra/provision-task-status-change.ps1` → `runAppTaskStatusChangeAccess`/`AppTaskStatusChangeAccessRunner`/정책 SQL은 정확한 TASK_UPDATE→TASK_STATUS_CHANGE 또는 완전 상태의 반복 보존만 허용한다. 두 종류 권한 중 일부만 있으면 자동 보충하지 않는다. PUBLIC/GRANT OPTION/과도 권한도 자동 수선·철회하지 않는다. 자격증명은 유지한다.
- AppReadPolicy는 기존 테이블 UPDATE 원시 ACL뿐 아니라 새 이력 **컬럼 INSERT**의 원시 ACL/유효 집합을 별도로 검사해야 한다. 이전 승인 상태는 보존하되 task status UPDATE만 있거나 이력 INSERT 일부/SELECT·id 권한 등은 거부한다. 기존 모든 도구도 완전 TASK_STATUS_CHANGE를 인정/보존한다.
- DevWorkspaceWriteSettings의 Task UPDATE 다섯 컬럼 검사를 여섯 컬럼 + 이력 여섯 INSERT/금지 권한 검사로 전환한다. 이력 테이블을 기존 업무 테이블의 SELECT 필수 루프에 무조건 넣지 않는다. 실제 쓰기 기동은 V7/새 정책이 필요하며 앱이 자동 생성/GRANT하지 않는다. 조회 전용 Task 모델은 V6 매핑을 유지하고, 신규 상태 기능의 쓰기 기동에서 이력 누락/권한 오류를 거부한다.
- MigrationHistory/권한·seed/current fixture는 정확한 1~7로 전환하고 과거 V1~V6 마이그레이션은 명시적 target을 유지한다. V6MigrationTest의 latest 가정과 seed의 미래 V7 fixture는 과거 target 6/미래 V8로 분리한다. 누락/미래/체크섬 검사를 약화하지 않는다.
- 관리자 runner/seed의 테이블 잠금과 예제 일치 검사에 새 이력을 반영한다. seed는 기존 사용자/공간 예제만 유지하고 projects/tasks/task_status_activities가 비어 있어야 한다. 이력 예제 삽입·실제 업무 데이터 초기화/보충은 하지 않는다.
- 앱 Flyway 자동 실행 비활성·JPA validate/OSIV=false와 관리자 클래스패스/환경 분리를 유지한다. 후속 실제 적용은 앱 중지→전용 migrator V7/Validate→상태 변경 최소 권한 도구→새 쓰기 앱 순서며, 구현/격리 검증 뒤 별도 요청으로 실행한다.

## Vue 연결·기존 폼 호환

정책 확정 이후 [Vue 상태 UI](task-status-ui.md)를 연결했다. 기존 일반 편집 폼/POST는 상태를 보내지 않고 현재 계약을 유지한다. 상태 폼은 Task 상세의 status/version을 기준으로 목표 상태와 문자열 version만 보내고, 성공 DTO로 같은 ID 상세/목록을 갱신한다. 각 폼의 편집 시작 기준은 독립적으로 보존한다.

기존 세 필드 편집과 상태 변경은 버전을 공유하므로 동시에 열어 둔 편집 기준을 자동 교체하지 않는다. 409/응답 유실·잘못된 200/5xx는 입력/기준 보존·잠금, 최신 Task/Workspace 역할 GET·비교·명시적 기준 갱신·별도 수동 저장 원칙을 유지한다. GET에서 목표 상태가 보여도 특정 요청의 성공을 확정하거나 자동 재전송하지 않는다. Activity 조회/피드는 후속이다.

## 후속 격리 수용 기준

1. 데이터/TASK_UPDATE가 있는 V6에서 V7 한 건/Validate/반복 0건, 빈 상태 이력과 기존 모든 데이터·상태/버전/시간·이전 설치 행/체크섬/ACL/소유권·시퀀스 보존을 확인한다. 기존 Task에 허구 이력은 없어야 한다.
2. 실제 여섯 방향 전이를 OWNER/ADMIN/MEMBER에 허용하고 VIEWER 403, 미소속/미존재/잘못된 공간의 Project/다른 Project의 Task 동일 404·양쪽 소속 사용자·404/403/409 순서를 검증한다. MEMBER의 Project 관리 거부는 유지한다.
3. 실제 전환은 상태/시각/버전만 변경 + 정확한 이력 한 건, 서버 행위자·이전/다음 상태·새 버전·공통 시각을 확인한다. 현재 버전 no-op은 행/시각/버전·이력 그대로이고 stale 같은 목표도 409다. 일반 수정/생성에는 상태 이력 0건이다.
4. 동일 Task/version에서 상태↔상태와 상태↔일반 필드 수정의 실제 병렬 트랜잭션은 한 건만 commit/나머지 409다. 상태가 승자일 때만 이력 1건, 일반 수정 승자는 이력 0건이다. 다른 Task는 독립적으로 commit하고 부모/다른 필드는 유지된다.
5. no-op 중 다른 commit의 최종 낙관적 검증, 상태 UPDATE·이력 INSERT·UNIQUE/FK/권한/flush·지연 commit 실패에서 상태/이력 모두 rollback을 확인한다. native SQL의 조건/행 수·refresh/이중 버전 증가 방지를 실제 DB에서 확인한다.
6. 두 필드 strict JSON·상태/문자열 버전·부모/주체·fromStatus/기타 입력 위조·실제/chunked 32 KiB·Origin·기본/쓰기 off·실제 Vite 깊은 PATCH와 안전한 오류·성공 여덟/목록 여섯 필드 회귀를 검증한다.
7. 앱의 Task status 조건부 UPDATE/이력 고정 여섯 컬럼 INSERT가 이력 SELECT/generated keys/직접 sequence 권한 없이 성공해야 한다. 이력 SELECT/id INSERT/전체 INSERT/UPDATE/DELETE/TRUNCATE·Task 금지 컬럼/전체 UPDATE·이력/DDL·부분/과도/PUBLIC/GRANT OPTION/자격증명 오류 거부와 기존 모든 도구 보존을 확인한다. 누락 이력 테이블/새 권한은 쓰기 기동에서 실패해야 한다.
8. 기존 Workspace/Project/Task 조회·생성/세 필드 수정/409·Vue 편집/복구/파서와 seed/관리자 도구·이전 target/빌드를 회귀한다. 모든 쓰기는 고유 격리 PostgreSQL에서 검증하고 소유 자원/임시 자격증명을 정리한다. 실제 로컬 V6/TASK_UPDATE·5/3/6/1/1·Task/Project version 1·보호 파일은 유지한다.

## 영향 파일·대안·다음 한 작업

후속 영향은 V7/상태 전용 Controller·Service/mandatory 어댑터·TaskErrors·전이/no-op 규칙, 이력 고정 컬럼 INSERT/권한 정책·관리자 runner/Gradle/PowerShell·기동 검사·현재 이력/seed/과거 target/미래 fixture·HTTP/동시성/원자성·프록시/권한 테스트와 문서다. 기존 Task DTO/목록 형태는 바꾸지 않는다.

순방향만/인접 전이만·OWNER/ADMIN만·Activity 후속의 대안 대신 사용자는 모든 전이·세 역할·최소 이력 동시 저장을 선택했다. Activity를 범용 JSON 이벤트/메시지로 확장하거나 조회 권한을 미리 부여하지 않는다.

구현/검증은 [구현 기록](task-status-implementation.md)과 [Vue 연결](task-status-ui.md)을 따른다. [상태 이력 조회 계약](task-status-history-read-api-v1.md)도 확정했으며 다음 한 작업은 **Task 상태 이력 조회 API·최소 SELECT 권한 도구 구현과 격리 PostgreSQL 검증**이다.
