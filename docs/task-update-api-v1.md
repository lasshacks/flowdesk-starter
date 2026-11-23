# Task 제목·설명·우선순위 수정 계약

- 날짜: 2026-11-08
- 상태: PATCH/V6/UPDATE 권한 도구·기존 Vue 응답 파서 전환 구현·격리 검증 완료. Java 446개/Vite 1개·PowerShell 105개·Vue 364개 및 빌드 통과(2026-11-08). 실제 로컬 V6/TASK_UPDATE·새 앱 수정 확인도 완료했다(2026-11-08). Vue 편집 폼/세 필드·버전 보존/409·결과 불명 복구도 연결·검증 완료(2026-11-08, 프론트 415개/빌드·실제 격리 Chrome/DB).
- 기준: [요구사항](requirements.md), [아키텍처](architecture.md), [기술 스택](tech-stack.md), [로드맵](roadmap.md), [Task 생성/조회](task-model-and-api-v1.md), [Project 수정 선례](project-update-api-v1.md).
- 현재 로컬 기준: V6/TASK_UPDATE, 사용자/공간/멤버십/Project/Task 5/3/6/1/1. Project 1 version 1 유지, Task 1 version 1/TODO/HIGH이며 제목/설명/우선순위·버전/수정 시각만 변경했다.
- 완료 기준: 확정한 필드·역할·충돌/HTTP·V6/권한·기존 응답 전환을 구현하고 격리 PostgreSQL에서 검증한다. 실제 로컬 적용·Vue 편집 연결은 별도다.

## 사용자와 확정한 정책

| 항목 | 선택 |
|---|---|
| 수정 필드 | 제목·설명·우선순위. 설명 null/정규화 후 빈 값은 삭제 |
| 수정 역할 | 해당 Workspace OWNER·ADMIN·MEMBER가 그 공간의 모든 Task 수정 가능 |
| 거부/격리 | 올바른 자원의 VIEWER는 403. 미소속·미존재·URL 부모 불일치는 동일 404 |
| 충돌 | Task 자체 버전으로 검출, 오래된 수정은 409 |
| 충돌 단위 | Task 한 행. 서로 다른 편집 필드도 같은 Task의 동시 수정이면 충돌 |
| 후속 | 상태 변경·담당자·마감일·Project 이동·보관/삭제·칸반·댓글/Activity·실제 인증 |

별도 Task/Project membership이나 작성자/담당자 기준은 추가하지 않는다. MEMBER의 Project 생성/수정 권한은 여전히 없다. 다른 공간의 OWNER/ADMIN 역할은 현재 부모 공간에 적용하지 않는다. 네 역할 모두 조회하는 기존 정책을 유지한다.

제목은 Java Character.isWhitespace/isSpaceChar 기준 앞뒤 공백 제거·1~200 Unicode 코드 포인트·중복 허용이다. 설명은 CRLF/CR→LF·앞뒤 공백 제거·최대 2,000 코드 포인트의 선택 일반 텍스트이며 내부 LF/TAB만 제어 문자 예외로 허용한다. 잘못된 surrogate/다른 ISO 제어 문자는 거부하고 HTML/Markdown을 해석하지 않는다. 우선순위는 정확한 LOW/MEDIUM/HIGH다. 수정에서 우선순위 누락을 MEDIUM으로 해석하지 않는다.

## HTTP 요청

```http
PATCH /api/workspaces/1/projects/1/tasks/1
Content-Type: application/json
Accept: application/json
Origin: http://127.0.0.1:5173

{"title":"  API 계약 정리  ","description":"첫 줄\r\n검토 내용","priority":"HIGH","version":"0"}
```

- 구현 기본값으로 title/description/priority/version **네 필드 모두 필수**다. 세 편집 필드의 스냅샷을 저장하는 고정 PATCH 계약이며 범용 JSON Merge Patch는 아니다. 일부 필드만 바꾸려면 나머지 현재 값도 함께 보낸다.
- title은 필수 문자열이며 null/정규화 후 빈 값은 400이다. description은 명시적 null 또는 문자열이어야 하며 빈 값은 삭제다. description 누락은 400으로, '기존 값 유지'와 혼동하지 않는다.
- priority는 필수 문자열 LOW/MEDIUM/HIGH다. 누락/null/빈 값/소문자/다른 값/숫자는 400이다. 생성 POST의 생략 시 MEDIUM 기본값은 기존대로 유지한다.
- version은 상세 GET에서 받은 **Task 버전**이며 0~Long.MAX_VALUE의 정규 십진 문자열이다. 0 또는 선행 0 없는 양의 정수만 허용하고 숫자/null/음수/부호/선행 0/범위 초과는 400이다. Project/Workspace 버전을 대신 보내지 않는다.
- URL의 세 ID는 기존 양의 정규 십진 문자열/Long 범위를 유지한다. JSON의 id/workspaceId/projectId/userId/role/작성자·담당자/날짜·시간·status 및 모든 미지원 필드는 400이다. status:"TODO"라도 거부한다. 상태는 현재 값을 보존한다.
- 중복 JSON 키/후행 JSON/잘못된 UTF-8·JSON/쿼리 파라미터는 400이다. 기존 strict JSON의 실제 본문 32 KiB/413와 UTF-8 application/json·Content-Encoding 정책/415를 재사용한다.
- 기존 dev-actor/서버 지정 사용자·loopback·명시적 허용 Origin/쓰기 flag를 재사용한다. 기본 프로필 업무 경로는 404, dev-actor/쓰기 off는 PATCH 미등록 405다. 이는 실제 로그인/운영 CSRF 정책이 아니다.
- 현재 DevWorkspaceWriteFilter가 `/api/workspaces/` 하위 PATCH를 이미 검사한다. Task의 깊은 PATCH 경로도 기존 검사에 포함되는지 실제 HTTP/Vite로 확인한다. 경로를 추가했다는 이유만으로 새 예외/필터를 만들지 않는다.

설명 삭제 예시:

```json
{"title":"API 계약 정리","description":null,"priority":"HIGH","version":"1"}
```

## 응답·버전·no-op

```http
HTTP/1.1 200 OK
Content-Type: application/json
Cache-Control: no-store

{"id":"1","workspaceId":"1","projectId":"1","title":"API 계약 정리","description":"첫 줄\n검토 내용","status":"TODO","priority":"HIGH","version":"1"}
```

```http
HTTP/1.1 409 Conflict
Content-Type: application/problem+json
Cache-Control: no-store

{"type":"about:blank","title":"Conflict","status":409,"detail":"Task has changed. Read it again before modifying.","code":"TASK_VERSION_CONFLICT"}
```

- commit 성공 후 200을 반환한다. 실제 변경이면 version을 1 증가시키고 updated_at을 서버 시각으로 갱신한다. version은 JSON 문자열로 전달한다. 오류 원문에 현재 Task 내용/버전·SQL·자격증명을 넣지 않는다.
- 상세 GET·생성 POST·수정 PATCH의 TaskView에 version 문자열을 추가해 **여덟 필드**로 전환한다. 생성 초기 버전은 "0"이다. 기존 경로·일곱 필드는 유지한다.
- 목록 TaskList.Item은 **id/workspaceId/projectId/title/status/priority 여섯 필드 그대로**다. 설명/버전/타임스탬프·사용자 정보를 추가하지 않는다. 수정 기준은 목록이 아닌 상세 GET에서 읽는다.
- 세 필드가 정규화 후 모두 현재 값과 같으면 no-op 200, version/updated_at은 유지한다. no-op도 요청 버전 비교와 commit 시점 낙관적 검증을 수행한다. 내용이 같아도 stale version은 409다.
- 기존 status가 TODO 외의 값이어도 그대로 보존한다. 이 PATCH가 상태를 TODO로 초기화하거나 상태 변경 경로가 되지 않게 한다. 생성의 TODO 고정 계약은 유지한다.
- Task 수정은 부모 Project/Workspace의 내용·version/updated_at을 바꾸지 않는다. 같은 부모의 서로 다른 Task는 각각 독립 버전을 사용한다.

## 인가·부모 확인·오류 순서

| 요청 | 결과 |
|---|---|
| 올바른 부모/Task, OWNER·ADMIN·MEMBER, 현재 Task 버전 | 200 |
| 올바른 부모/Task, VIEWER | 403 / TASK_FORBIDDEN |
| 미소속·없는 공간/Project/Task·URL 부모 불일치 | 같은 404 / TASK_NOT_FOUND |
| 자격 있는 요청의 stale 버전/flush·commit 낙관적 충돌 | 409 / TASK_VERSION_CONFLICT |

순서는 개발 접속/쓰기·Origin → HTTP 구조/입력 → 서버 행위자 → Workspace membership → Project id+workspaceId 조회 → **Task id+projectId 조회** → 세 역할 → 요청 Task 버전 → 변경/DB 버전 검사다. 미소속은 자원 존재를 조회/노출하지 않는다. VIEWER의 잘못된 부모/없는 Task도 403 대신 404이며, 올바른 Task의 VIEWER는 stale 버전이어도 403이다. 두 공간 모두에 소속된 사용자도 부모를 바꿔 다른 공간/Project의 Task를 수정할 수 없다.

기존 INVALID_REQUEST/400, DEV_ACCESS_DENIED/403, REQUEST_TOO_LARGE/413, UNSUPPORTED_MEDIA_TYPE/415, SERVICE_UNAVAILABLE/503, INTERNAL_ERROR/500을 유지한다. 명시적 버전 불일치/낙관적 잠금 예외만 409로 분류하고 SQL 권한·임의 제약 오류를 충돌로 감추지 않는다. TaskErrors의 대상 Controller/충돌 예외 처리와 기존 'Task creation' 전용 거부 문구는 수정 요청에도 맞게 전환한다.

## Service·JPA·트랜잭션

HTTP → 분리된 입력 DTO → public TaskCommandService.update 쓰기 트랜잭션 → 서버 행위자/소속·Project/Task 부모 확인 → 역할/요청 버전 → 관리 Entity의 세 필드 변경 → mandatory 수정 어댑터의 OPTIMISTIC 검증/flush → Spring 트랜잭션 프록시 commit → DTO/200.

- 기존 조회 Service는 readOnly, 수정은 readOnly=false/READ_COMMITTED/예외 롤백이다. 조회 Repository의 부모 제한과 기존 생성 persist 경계는 유지하고 범용 save/delete/merge를 노출하지 않는다.
- Task에 Long @Version을 추가한다. 클라이언트 값은 비교용이며 Entity 버전을 직접 덮어쓰지 않는다. 요청 시작 시 비교뿐 아니라 실제 UPDATE의 id/version 조건으로 동시 변경을 검출한다.
- 같은 버전에서 시작한 실제 병렬 변경은 하나만 commit/나머지 409다. 제목과 우선순위 등 서로 다른 필드도 자동 병합하지 않는다. 단순 비관적 잠금만으로 요청 전에 오래된 편집 내용을 검출한다고 가정하지 않는다.
- title/description/priority/updated_at만 수정 가능한 매핑으로 바꾸고 version은 JPA가 관리한다. id/project_id/status/created_at은 UPDATE 대상에서 제외한다. DB DEFAULT가 updated_at을 자동 갱신한다고 가정하지 않는다.
- no-op에도 기존 Project 선례의 OPTIMISTIC 검증을 적용해 commit 단계 경합을 검출한다. 응답 뒤 미래 변경까지 막는 보장은 아니다.
- flush로 새 버전을 얻어 DTO를 만들되 트랜잭션 프록시 commit 성공 뒤 HTTP를 응답한다. flush/지연 commit 실패는 변경을 남기지 않으며 commit 단계 낙관적 충돌도 409로 처리한다.
- 인가는 이 트랜잭션에서 읽은 membership 기준이다. 향후 멤버십/역할 변경·Task 이동·상태 변경 API를 도입하면 경합 정책과 이력/이벤트 원자성을 별도로 정한다. 이번에 Activity/Outbox 등 미래 기술은 추가하지 않는다.

## V6·최소 UPDATE 권한·기존 도구 전환

- 마이그레이션 파일은 `backend/src/main/resources/db/migration/V6__add_task_version.sql`이다. tasks.version bigint NOT NULL DEFAULT 0, CHECK version >= 0만 추가한다. 기존 Task/부모/상태/우선순위·시간·시퀀스/소유권·이전 ACL과 V1~V5 파일/설치 행을 보존한다. 현재 로컬 Task 1도 후속 적용 시 version 0이 된다.
- TASK_UPDATE 상태는 기존 TASK_CREATE 전체에 **tasks UPDATE(title, description, priority, updated_at, version)** 다섯 컬럼만 추가한다. status/id/project_id/created_at·테이블 전체 UPDATE·DELETE/TRUNCATE·시퀀스/history/DDL 권한은 금지한다. 기존 Workspace 세 컬럼·Project 네 컬럼 UPDATE와 다른 최소 권한을 그대로 유지한다.
- `infra/provision-task-update.ps1` → `runAppTaskUpdateAccess`/전용 Java main/정책 SQL은 정확한 TASK_CREATE→TASK_UPDATE와 반복 보존만 수행한다. 기존 앱 비밀번호를 검증·보존하고 partial/excess/PUBLIC/GRANT OPTION을 자동 수선/철회하지 않는다.
- AppReadPolicy/기존 모든 관리자 도구가 새 완전 상태를 인정·보존하고 과거 승인 상태를 계속 구분해야 한다. 다섯 컬럼 중 일부만 있거나 금지 status UPDATE 등이 있으면 거부한다.
- DevWorkspaceWriteSettings는 현재 tasks의 모든 컬럼 UPDATE를 금지하므로 승인된 정확한 다섯 컬럼 검사로 전환해야 한다. 테이블 전체/금지 컬럼 권한 검사는 유지한다. 조회 모드는 필요한 SELECT를 사용하며 앱이 GRANT하지 않는다.
- MigrationHistory/권한·seed/current fixture는 정확한 1~6으로 전환한다. 이전 V1~V5 독립 마이그레이션 테스트는 명시적 target을 유지한다. 특히 V5MigrationTest의 latest 가정과 seed의 미래 V6 fixture는 각각 과거 target/미래 V7로 분리한다. 누락/미래/변조 이력 검사를 약화하지 않는다.
- seed는 기존 사용자/공간 예제 + projects/tasks 비어 있음 조건을 유지한다. Task/Project 업무 데이터 보충·초기화는 하지 않는다.
- Flyway 앱 자동 실행 비활성/ddl-auto=validate/OSIV=false, migrator/앱 계정·자격증명/실행 클래스패스 분리를 유지한다. 새 Task @Version 매핑은 V6를 요구한다. 실제 적용 순서는 앱 중지 → 전용 migrator V6/Validate → 별도 Task UPDATE 권한 도구 → 새 앱이다. 구현·격리 검증 후 실제 로컬 적용은 별도 요청으로 실행한다.

## Vue 응답 전환·후속 수정 폼

백엔드 응답 전환 구현 시 기존 Task 타입/상세·생성 파서에 version 문자열 검증·보존을 함께 추가한다. 생성 201은 초기 "0"까지 확인하며 목록 요약은 그대로다. 이 호환 전환은 백엔드 구현 범위에 포함하고 수정 폼/PATCH 클라이언트는 별도 단계로 둔다.

후속 수정 폼은 편집 시작 title/description/priority/version과 draft를 분리한다. 성공한 200의 두 부모/Task ID·정규화 세 필드/기존 status·버전을 검사해 현재 상세/같은 ID의 목록 요약만 갱신한다. 실제 변경은 시작 version+1, no-op은 같은 version이며 BigInt는 비교에만 쓰고 HTTP 문자열을 유지한다.

409/결과 불명(응답 유실·시간 초과·5xx·잘못된 200)은 draft/시작 버전을 보존하고 저장을 잠근다. 최신 Task GET + Workspace 역할 GET 성공 → 서버 세 필드와 draft 비교 → 사용자의 명시적 편집 기준 갱신 → 별도 수동 저장 순서다. 권한 상실/조회 실패는 잠금을 유지한다. GET만으로 입력/버전을 교체하거나 자동 병합/재전송하지 않는다. GET 내용 일치도 특정 PATCH 성공 영수증은 아니다. 컴포넌트 종료/요청 취소는 늦은 이벤트를 막지만 이미 처리된 commit을 되돌리지 않는다.

## 후속 격리 수용 기준

1. 데이터/TASK_CREATE가 있는 V5에서 V6 1건/Validate/반복 0건, 기존 Task version 0/비음수 제약, 전체 기존 행·부모/시간/상태/시퀀스·이전 설치 행/체크섬/ACL 보존을 확인한다. V6 누락 시 새 JPA 앱은 자동 복구 없이 validate 실패다.
2. OWNER/ADMIN/MEMBER의 세 필드 각각·조합 수정/설명 삭제 200과 VIEWER 403, 미소속/미존재/잘못된 공간의 Project/다른 Project의 Task 동일 404를 검증한다. 두 공간 소속 사용자·VIEWER 잘못된 Task·404/403/409 순서를 포함한다. MEMBER의 Project 관리 거부는 유지한다.
3. 같은 Task/버전의 실제 병렬 수정은 한 건 commit/다른 건 409이며 서로 다른 필드 수정도 충돌한다. 다른 Task는 독립 버전으로 수정되고 부모 Project/Workspace 데이터·version/updated_at과 Task status/created_at/project_id는 불변이다. TODO 외 상태를 가진 격리 fixture도 수정으로 초기화되지 않아야 한다.
4. 순차 stale 409·최신 버전 수동 수정·정규화 no-op의 version/updated_at 유지와 commit 시점 경합 검증, 저장/지연 commit 실패 rollback을 확인한다.
5. 필수 네 필드/잘못된 version·priority/Unicode·제어 문자/미지원 status·부모/주체 위조/strict JSON·실제/chunked 32 KiB·Origin·기본/쓰기 off·안전한 오류를 검증한다.
6. 실제 앱 계정의 승인 다섯 UPDATE 컬럼만 성공, status/id/project_id/created_at/전체 UPDATE·DELETE/TRUNCATE·시퀀스/history/DDL 거부, 부분/과도 ACL·자격증명 오류 거부와 기존 모든 도구의 완전 TASK_UPDATE 보존을 확인한다.
7. 상세/생성/수정의 여덟 필드/문자열 version·생성 0, 기존 목록 여섯 필드·상태 보존과 Vue 파서/생성 계약 전환·기존 Workspace/Project/Task 조회·생성·수정 회귀/빌드·실제 Vite PATCH Origin을 검증한다. 후속 Vue 편집 폼의 충돌/결과 불명 복구는 별도 단계다.
8. 실제 로컬 V5/TASK_CREATE·5/3/6/1/1·Project 1 version 1/Task 1 TODO·MEDIUM·자격증명/V1~V5/JAR를 보존한다. 쓰기는 고유 격리 DB에서만 검증하고 소유 앱/컨테이너·볼륨/네트워크/임시 자격증명을 정리한다. 실제 로컬 V6/GRANT/수정은 별도다.

## 대안·영향 파일·다음 한 작업

OWNER/ADMIN만 수정하는 대안 대신 사용자는 MEMBER도 공간 내 모든 Task를 수정하도록 선택했다. 마지막 저장 덮어쓰기는 앞선 변경 유실을 막지 못하므로 Task 버전/409를 선택했다. 필드별 자동 병합·ETag/If-Match 혼용·범용 PATCH·상태 머신/이력 기술은 추가하지 않는다. 현재 JSON version 방식은 기존 Workspace/Project 선례를 재사용한다.

후속 영향은 Task Entity/수정 Controller·Service/mandatory 어댑터/TaskErrors/TaskView·V6, 권한 정책·새 runner/Gradle/PowerShell/기존 도구/기동 검사·이력/seed/과거 target fixture·격리 테스트, Vue Task 타입/상세·생성 파서·기존 테스트와 문서다. 이번 구현/격리 검증 범위에 반영한다. 실제 로컬 적용·Vue 편집 폼은 별도다.

다음 한 작업은 **Task 상태 변경 API·V7·최소 권한 도구·상태 이력 원자성 구현과 격리 PostgreSQL 검증**이다. 상태 정책은 [별도 계약](task-status-api-v1.md)으로 확정했다. Vue 세 필드 편집/버전·충돌 복구는 연결·검증을 완료했다. 실제 로컬 V6/최소 UPDATE·새 앱 수정 확인은 완료했다. 구현·격리 검증의 실제 명령/결과와 로컬 보존은 [실행 기록](local-setup.md)을 따른다.

## V8 담당자·마감일 PATCH 확장 — 2026-11-11

이 추가 규칙은 위 네 필드 body 규격을 V8에서 대체한다. 전체 스냅샷 PATCH body에는 `title`, `description`, `priority`, `assigneeId`, `dueDate`, `version` 여섯 필드가 모두 필요하다. `assigneeId`와 `dueDate`의 null은 해제를 뜻하고 누락은 400이다. 담당자 ID는 요청 Task의 Workspace 현재 멤버여야 한다. 지정 뒤 그 멤버가 제거돼도 기존 ID는 유지한다.

Task 단일 version/409는 기존 규칙 그대로 모든 변경 필드에 공유된다. TaskView는 기존 여덟 필드에 두 필드를 추가한다. 쓰기는 기존 OWNER·ADMIN·MEMBER이며 VIEWER는 403, 조회는 네 역할이다. 입력/권한/DDL/검증의 실제 구현 기록은 [V8 구현](task-assignee-due-date-implementation.md)을 따른다.
