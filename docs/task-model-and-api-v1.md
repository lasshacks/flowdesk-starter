# 첫 Task 데이터 모델·생성/조회 계약

- 날짜: 2026-11-07
- 상태: 구현·격리 검증에 이어 실제 로컬 V5/TASK_CREATE·새 앱 생성/조회 확인 완료(2026-11-08). 로컬 5/3/6/1/1·Task 1 TODO/MEDIUM, 기존 Project 1 version 1 유지. Vue Task 목록·상세 조회도 연결·검증 완료(2026-11-08). Vue Task 생성 폼·POST도 연결·격리 검증 완료(2026-11-08). 앞선 Java 396개·실제 Vite 1개·PowerShell 92개와 이번 실제 적용 결과는 [local-setup.md](local-setup.md)에 구분한다.
- 기준: [요구사항](requirements.md), [아키텍처](architecture.md), [기술 스택](tech-stack.md), [로드맵](roadmap.md), [Project 생성/조회](project-model-and-api-v1.md), [현재 Project 수정 계약](project-update-api-v1.md).
- 완료 기준: 확정한 네 필드/역할·부모·HTTP/트랜잭션·V5/최소 권한·기존 도구 전환을 구현하고 격리 PostgreSQL에서 검증한다. 실제 로컬 적용과 Vue는 별도다.

## 사용자와 확정한 정책

| 항목 | 선택 |
|---|---|
| 첫 범위 | 제목·설명·상태·우선순위의 생성/프로젝트별 목록/상세 |
| 접근 관계 | 별도 Task/Project membership 없이 Workspace 멤버십 상속 |
| 생성 역할 | 해당 Workspace OWNER·ADMIN·MEMBER |
| 조회 역할 | 해당 Workspace OWNER·ADMIN·MEMBER·VIEWER |
| 생성 상태 | 서버가 TODO로 고정. 클라이언트가 초기 상태를 선택하지 않음 |
| 우선순위 | LOW·MEDIUM·HIGH 중 선택, 생략하면 MEDIUM |
| 격리/오류 | 미소속·미존재·URL 부모 불일치는 동일 404. 올바른 부모의 VIEWER 생성은 403 |
| 후속 | 담당자·마감일·수정/상태 변경·칸반·댓글/이력·보관/삭제 |

Task 생성 역할은 Project 생성 역할과 다르다. MEMBER는 기존 Project 안에 Task를 만들 수 있지만 새 Project를 만들거나 Project를 수정하는 권한은 얻지 않는다. 다른 공간의 OWNER/ADMIN 역할도 현재 부모 공간에 적용하지 않는다.

## 구현 기본값과 데이터 모델

길이·정규화·중복·페이지/DTO 표기는 아래 구현 기본값으로 둔다. 제목은 고유 키로 사용하지 않고 동일 제목의 독립 Task를 허용한다. 이름 중복과 마찬가지로 POST 재전송을 막는 멱등 정책을 대신하지 않는다.

구현 테이블은 `flowdesk.tasks`다.

| 컬럼 | 형식/제약 | 책임 |
|---|---|---|
| id | bigint GENERATED ALWAYS AS IDENTITY, PK | DB 생성, 응답 문자열 |
| project_id | bigint NOT NULL, projects(id) FK, ON DELETE RESTRICT | 부모 Project 고정 |
| title | varchar(200) NOT NULL, 공백뿐인 값 금지 | 정규화 후 1~200 Unicode 코드 포인트 |
| description | varchar(2000), nullable | 선택 일반 텍스트 |
| status | varchar(16) NOT NULL DEFAULT 'TODO', CHECK TODO/IN_PROGRESS/DONE | 생성은 서버 TODO, 상태 변경 API는 없음 |
| priority | varchar(6) NOT NULL DEFAULT 'MEDIUM', CHECK LOW/MEDIUM/HIGH | 생성 입력/기본값 |
| created_at | timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP | DB 생성 시각 |
| updated_at | timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP | 현재는 생성 시각, 자동 UPDATE 아님 |

- `tasks_project_id_id_idx(project_id,id)`로 부모별 ID 오름차순 목록을 지원한다. 제목 UNIQUE·검색/필터/사용자 지정 정렬 인덱스는 넣지 않는다.
- Task에는 project_id만 저장하고 workspace_id를 중복 저장하지 않는다. 부모 Project의 workspace_id와 요청의 workspaceId를 Service에서 확인한다. Task JPA는 스칼라 projectId와 DB FK를 사용하고 부모 Entity를 cascade하지 않는다.
- 제목은 기존 Java 공백 판정과 같은 앞뒤 정규화, ISO 제어 문자/NUL/잘못된 surrogate 거부를 적용한다. 설명은 생략/null/정규화 후 빈 값이면 null, CRLF/CR→LF, 내부 LF/TAB 허용, 그 외 제어 문자 거부, 최대 2,000 코드 포인트다. HTML/Markdown은 해석하지 않는다.
- 상태와 우선순위는 JPA 문자열 enum/DB CHECK로 표현한다. DB 상태 값 집합은 기존 요구사항의 세 상태지만, 첫 생성 DTO는 status를 받지 않고 TODO만 저장한다. enum을 도입했다고 상태 전환 API가 구현되는 것은 아니다.
- version/assignee_id/due_date/created_by/보관·삭제 플래그/Activity 테이블은 추가하지 않는다. 생성 행위자 검사는 수행하지만 이 단계에서 생성자별 감사 기능을 구현하지 않는다. 수정 버전·할당 자격/멤버십 변경 경합·날짜 의미·Activity 트랜잭션은 해당 기능 전에 정한다.
- Task 생성은 기존 Project/Workspace 이름·설명·version·updated_at을 변경하지 않는다. Task 생성만으로 부모의 편집 버전을 증가시키지 않는다.

## HTTP 요청·응답

중첩 경로를 사용한다. 전역 `/api/tasks`나 JSON 부모 선택 경로는 제공하지 않는다.

```http
POST /api/workspaces/1/projects/1/tasks
Content-Type: application/json
Accept: application/json
Origin: http://127.0.0.1:5173

{"title":"  API 계약 정리  ","description":"첫 줄\r\n검토 내용","priority":"HIGH"}
```

- title은 필수 문자열이다. description은 생략/null/문자열, priority는 생략 또는 정확한 대문자 문자열 LOW/MEDIUM/HIGH만 허용한다. priority:null/빈 값/숫자/소문자/미지원 값은 400이다.
- 최소 요청 `{"title":"첫 작업"}`은 description:null, status:TODO, priority:MEDIUM으로 생성한다.
- status/id/workspaceId/projectId/userId/role/assigneeId/dueDate/version/생성·수정 시각과 모든 미지원 필드, 중복 JSON 키/후행 JSON/잘못된 UTF-8·JSON/쿼리 파라미터는 400이다. status:"TODO"라도 직접 보내면 400이며 무시하지 않는다.
- 기존 UTF-8 application/json/Content-Encoding 정책과 **실제 본문 32 KiB** 제한을 사용한다. 초과는 413, 미지원 미디어/인코딩은 415다. Task를 추가해 Workspace 4 KiB 제한을 늘리지 않는다.
- Workspace/Project/Task ID는 1~Long.MAX_VALUE의 정규 십진 문자열만 받는다. JSON 응답 ID도 문자열이다.

```http
HTTP/1.1 201 Created
Location: /api/workspaces/1/projects/1/tasks/7
Content-Type: application/json
Cache-Control: no-store

{"id":"7","workspaceId":"1","projectId":"1","title":"API 계약 정리","description":"첫 줄\n검토 내용","status":"TODO","priority":"HIGH"}
```

- commit 성공 후 201과 상대 Location을 반환한다. Host/전달 헤더로 Location을 만들지 않는다. 생성과 상세는 동일한 일곱 필드 DTO다. workspaceId는 검증한 Project/요청 부모로 구성하며 Task Entity의 중복 컬럼이 아니다.
- 생성의 독립 재요청은 새 Task를 만든다. 자동 재시도·멱등키·제목 기반 중복 거부는 제공하지 않는다. 응답 유실/시간 초과/5xx/잘못된 201은 결과 불명이며 GET 제목 일치만으로 특정 요청 성공을 단정하지 않는다.

```http
GET /api/workspaces/1/projects/1/tasks?page=0&size=20
Accept: application/json
```

```json
{"items":[{"id":"7","workspaceId":"1","projectId":"1","title":"API 계약 정리","status":"TODO","priority":"HIGH"}],"page":0,"size":20,"hasNext":false}
```

- page 기본 0/범위 0~10000, size 기본 20/범위 1~100의 ID 오름차순 Slice다. totalCount·필터·검색·정렬 선택은 제외한다. 미지원/중복 쿼리는 400이다.
- 목록은 여섯 필드 요약이며 description을 반복하지 않는다. 유효하고 접근 가능한 Project의 빈 목록/범위 내 뒤 페이지는 200/빈 items다. 없는/잘못된 부모는 빈 목록으로 숨기지 않고 404다.

```http
GET /api/workspaces/1/projects/1/tasks/7
Accept: application/json
```

```json
{"id":"7","workspaceId":"1","projectId":"1","title":"API 계약 정리","description":"첫 줄\n검토 내용","status":"TODO","priority":"HIGH"}
```

상세는 쿼리를 받지 않는다. version/myRole/생성자·담당자/이메일/멤버 목록/Entity는 노출하지 않는다. 현재는 수정 API가 없으므로 Task 또는 부모의 버전을 요청/응답에 추가하지 않는다.

## 인가·Service·Repository 경계

| 요청 | 결과 |
|---|---|
| 올바른 부모의 OWNER/ADMIN/MEMBER 생성 | 201 |
| 올바른 부모의 VIEWER 생성 | 403 / TASK_FORBIDDEN |
| 네 역할의 올바른 부모 목록/소속 Task 상세 | 200 |
| 미소속·없는 부모/Task·Project의 Workspace 불일치·Task의 Project 불일치 | 동일 404 / TASK_NOT_FOUND |

입력/개발 접속 검사 다음에 **서버 행위자 → Workspace membership → Project의 id+workspaceId 조회**를 수행한다. 생성은 그 뒤 세 역할을 검사하고 Task를 persist/flush/commit한다. VIEWER라도 부모가 없거나 맞지 않으면 403 대신 404다. 상세는 부모 확인 뒤 Task의 id+projectId로 제한한다. 양쪽 공간/프로젝트 모두 접근 가능한 사용자도 URL 부모와 실제 소속이 다르면 404다.

조회는 public readOnly Service, 생성은 public readOnly=false/READ_COMMITTED/예외 롤백 Service와 기존 트랜잭션을 요구하는 생성 전용 어댑터를 사용한다. 범용 save/delete/merge는 추가하지 않는다. 실제 Task FK도 잘못된 Project 삽입을 거부한다. 현재 부모 이동/멤버십 변경 API가 없으며 향후 해당 기능을 만들 때 인가/부모 변경 경합 정책을 별도로 검토한다.

예정 Repository는 부모 조건 목록 Slice와 id+projectId 상세만 노출한다. 새 task 모듈에서 기존 Project 조회 경계를 재사용하되 Task가 Project의 정적 입력 검사나 생성/수정 서비스를 통해 우회 저장하지 않는다. 구체적인 작은 검증 코드 공유 여부는 구현 시 판단하며 범용 프레임워크를 미리 도입하지 않는다.

오류는 기존 ProblemDetail/no-store/안전한 메시지를 유지한다. 업무 오류는 TASK_FORBIDDEN/403, TASK_NOT_FOUND/404이며 입력 400·접속 403·413/415·일시 장애 503·예상 밖 저장/commit 오류 500을 구분한다. 이 범위에는 409/Task 낙관적 수정 API가 없다. DB 오류 원문/자격증명은 응답·로그에 노출하지 않는다.

## 개발 접속·쓰기 필터

- 실제 로그인은 Phase 2다. 기존 명시적 dev-actor/서버 지정 사용자·직접 loopback·전달 peer 재해석 금지를 유지하고 클라이언트 사용자/역할 헤더를 신뢰하지 않는다.
- 기존 `FLOWDESK_DEV_WORKSPACE_WRITE_ENABLED`/허용 로컬 Origin/JSON 정책을 재사용한다. 기본 프로필은 Task 업무 경로 404, dev-actor/쓰기 off는 Task POST 405이며 조회는 가능하다.
- **기존 `DevWorkspaceWriteFilter.shouldNotFilter`의 POST 패턴은 Workspace 생성과 Project 생성만 포함했다.** 이번 구현에서 `/api/workspaces/{workspaceId}/projects/{projectId}/tasks` POST를 명시적으로 추가하고 실제 Vite를 거치는 Origin 보존/외부 출처 거부를 검증했다. 경로가 더 깊다는 이유로 기존 필터가 자동 보호한다고 가정하지 않는다.

## V5·최소 DB 권한·실행기 전환

- 마이그레이션 파일은 `backend/src/main/resources/db/migration/V5__create_tasks.sql`이다. tasks/PK/FK/길이·enum CHECK/목록 인덱스만 만들고 seed/GRANT/계정/기존 Project 변경은 넣지 않는다. V1~V4 원본/설치 이력과 기존 데이터·version·시간·ACL·시퀀스를 보존한다.
- 새 상태 `TASK_CREATE`는 기존 `PROJECT_UPDATE` 전체 권한 + **tasks SELECT/INSERT**다. Task UPDATE/DELETE/TRUNCATE·컬럼 UPDATE·시퀀스·이력·DDL 권한은 금지한다. MEMBER의 HTTP 생성 자격이 앱 DB 계정의 역할을 바꾸는 것은 아니다.
- 별도 관리자 `infra/provision-task-create.ps1` → `runAppTaskCreateAccess`/전용 runner·정책 리소스로 정확한 PROJECT_UPDATE→TASK_CREATE와 반복 보존을 구현했다. 부분/과도·PUBLIC·GRANT OPTION은 자동 보정하지 않으며 기존 앱 비밀번호도 유지한다. 실제 로컬 적용은 구현/격리 검증 후 별도 요청으로 진행한다.
- AppReadPolicy는 tasks의 정확한 SELECT/INSERT 집합과 새 상태를 확인하도록 전환한다. tasks가 아직 없는 이전 이력/마이그레이션 경로는 안전하게 구분한다. 기존 read/create/rename/Project-create/Project-update 도구도 완전한 TASK_CREATE를 보존한다. Task 권한 일부 상태는 거부한다.
- 현재 이력 검사는 정확한 1/2/3/4/5로 전환하고 migration/권한/seed의 관련 검사·잠금 대상에 tasks를 반영한다. 기존 V1~V4 독립 마이그레이션 테스트는 명시적 target으로 보존한다. 특히 현재 V4MigrationTest의 ‘실제 latest runner가 한 건 실행’ 가정은 V5 추가 시 분리해야 한다. 미래/누락 이력·체크섬 검사를 약화하지 않는다.
- Task 읽기 기동 검사에는 tasks SELECT, 쓰기 기동 검사에는 Task SELECT/INSERT와 금지 권한 검증을 포함한다. 기존 Workspace 세 컬럼·Project 네 컬럼 UPDATE를 그대로 유지하고 Task 컬럼 UPDATE는 허용하지 않는다.
- seed는 Task/Project 예제를 추가하지 않는다. 기존 사용자/공간 예제 일치와 projects/tasks 비어 있음 조건을 확인하며 업무 데이터가 있으면 Apply/Verify를 mismatch로 종료한다. 실제 로컬 seed를 초기화하거나 변경하지 않는다.
- 앱 Flyway 자동 실행 비활성/ddl-auto=validate/OSIV=false와 별도 migrator/관리자 자격증명 경계를 유지한다. 새 Entity를 포함한 앱은 V5가 필요하다. 후속 실제 순서는 앱 중지→V5/Validate→별도 Task 권한 도구→새 앱이다.

## 후속 구현의 격리 수용 기준

1. 데이터와 PROJECT_UPDATE 권한이 있는 V4에서 V5 한 건/Validate/반복 0건, 빈 tasks·FK/enum/null/길이·인덱스와 기존 전체 데이터/이력/소유권·ACL/시퀀스 보존을 확인한다. V5 누락 시 새 JPA 앱은 자동 복구 없이 validate 실패다.
2. OWNER/ADMIN/MEMBER 생성 201과 네 역할 GET 200, VIEWER 생성 403, 올바르지 않은 Project 부모에서 VIEWER도 404, 미소속/미존재/다른 Workspace의 Project·다른 Project의 Task/양쪽 소속 사용자까지 동일 404를 검증한다.
3. 최소 title 요청의 TODO/MEDIUM/null, 우선순위 세 값, 제목 중복 허용, 정규화/Unicode/설명 null·빈 값/엄격한 JSON/미지원 status·assignee·부모 위조/정확한 enum, chunked 포함 실제 32 KiB와 400/413/415를 검증한다.
4. Service commit 후 201/상대 Location/일곱 필드 DTO, 여섯 필드 목록·ID 순서/Slice·빈/뒤 페이지, 부모 데이터/Project version 불변, 저장/지연 commit 실패의 rollback/안전한 오류를 확인한다.
5. 기본/쓰기 off/서버 행위자 누락·위조 헤더/loopback/Task POST Origin·실제 Vite 프록시 경계를 검증한다. 현재 필터의 경로 누락을 그대로 둔 채 Service 테스트만 통과시키지 않는다.
6. 실제 앱의 tasks SELECT/IDENTITY INSERT 성공, Task DML/컬럼 UPDATE·시퀀스/이력/DDL 거부, 잘못된 자격증명·부분/과도 ACL 거부와 기존 모든 도구의 새 상태 보존·자격증명 불변을 확인한다.
7. 기존 Workspace/Project 조회·생성·수정/충돌/health와 최신 migration/seed/권한·과거 target 테스트 및 빌드 회귀를 실행한다. Vue Task UI/실제 로컬 V5/GRANT/Task 생성은 별도 단계다.
8. 실제 로컬 V4/PROJECT_UPDATE·5/3/6/1·Project 1 version 1과 자격증명/V1~V4를 보존한다. 쓰기는 고유 격리 PostgreSQL에서만 검증하고 소유한 앱·컨테이너/볼륨/네트워크·임시 자격증명을 정리한다.

## 대안·영향 파일·다음 한 작업

전체 MVP 필드/할당/날짜를 한 번에 넣는 대안은 사용자 선택에 따라 후속으로 나눴다. OWNER/ADMIN만 Task 생성하는 안 대신 실제 작업 참여자인 MEMBER도 생성하게 하되 Project 관리 권한은 넓히지 않는다. 임의 초기 상태 대신 TODO 고정으로 상태 변경/Activity 계약을 분리한다. 중복 workspace_id/미사용 version/상태 변경 endpoint/Task membership·새 인증·메시지 기술은 도입하지 않는다.

예정 영향 파일은 V5·task 도메인/DTO/Controller/Service/제한된 Repository/생성 어댑터, Task 읽기/기존 쓰기 기동 검사·POST 필터, AppReadPolicy/기존 권한 runner/새 Task runner·정책 SQL/Gradle·PowerShell, MigrationHistory/seed·격리 fixture/이전 target 테스트·권한/HTTP/프록시 검증과 문서다. 현재 Vue 제품 소스와 실제 로컬 설정은 변경하지 않는다.

다음 한 작업은 **Task 상태 변경 API·V7·최소 권한 도구·상태 이력 원자성 구현과 격리 PostgreSQL 검증**이다. 상태 정책은 [별도 계약](task-status-api-v1.md)으로 확정했다. Vue 수정 폼/세 필드·버전/409 복구도 연결·검증을 완료했다. 실제 로컬 V6/TASK_UPDATE·Task 1 version 1/HIGH/TODO 확인도 완료했다. 수정 API/V6/권한 도구·응답 버전 전환은 구현·격리 검증을 완료했다. 수정 정책은 [별도 계약](task-update-api-v1.md)으로 확정했다. 실제 로컬 V5/최소 권한 적용과 MEMBER의 첫 Task 생성·역할별 조회/거부는 2026-11-08 완료했다.


## 구현 파일과 재현 명령 — 2026-11-07

- Task/TaskStatus/TaskPriority·V5: 스칼라 Project FK/정규화/문자열 enum·제약/인덱스.
- TaskController/TaskCreateController·TaskErrors: 엄격한 입력/부모 ID/페이지/안전한 오류·상대 Location.
- TaskQueryService/TaskCommandService·TaskRepository/TaskCreationRepository: 부모/역할 검사·제한 조회·mandatory persist/flush/commit.
- DevTaskReadSettings/DevWorkspaceWriteSettings/DevWorkspaceWriteFilter: Task SELECT/INSERT/금지 권한 검사와 nested POST Origin 검사.
- AppTaskCreateAccessRunner/runAppTaskCreateAccess/provision-task-create.ps1·정책 SQL/공통 실행기: 전용 관리자 작업, 기존 모든 권한 도구의 새 상태 보존.
- MigrationHistory/LocalSeedRunner/IsolatedReadDatabase·V4/V5/Task·기존 API/스키마/실제 Vite 테스트: 현재 이력/격리 fixture와 과거 target 분리.

```powershell
# backend, JDK 21/Docker; frontend node_modules for the Vite proxy check
./gradlew.bat build verifyWorkspaceProxy --no-daemon --console=plain
# root, unique isolated PostgreSQL only
./infra/verify-migration.ps1 -IncludeTaskCreate
```

실제 명령/결과와 검증문 수정·정리는 [작업 기록](local-setup.md)을 따른다. 현재 로컬은 V5/TASK_CREATE 적용을 완료해 새 앱과 호환된다. 명시적 dev-actor/서버 사용자/쓰기 flag·Origin 경계는 여전히 필요하며 검증 앱은 종료했다.


## Vue 조회 연결 — 2026-11-08

ProjectBrowser의 유효한 선택 상세에서 TaskBrowser를 Project ID key로 등록한다. 두 부모 하위 목록/상세 GET만 사용하고 20개 Slice/ID 오름차순·문자열 ID/부모/enum/텍스트/페이지를 런타임 검사한다. 서버 응답의 임의 필드는 반환 DTO에서 제외하고 Task version은 추가하지 않는다. 상태 TODO/IN_PROGRESS/DONE은 할 일/진행 중/완료, 우선순위 LOW/MEDIUM/HIGH는 낮음/보통/높음으로 표시한다.

새 선택/페이지·새로고침/부모 종료 때 진행 요청과 이전 선택을 종료하고 취소된 응답을 무시한다. 목록/상세 로딩·빈 목록·오류/재시도·일반 텍스트 설명과 네 역할 조회를 유지한다. 프론트 297개/빌드와 실제 Chrome GET 검증·로컬 보존 결과는 local-setup.md를 따른다. 생성 폼/POST는 다음 단계다.


## Vue 생성 연결 — 2026-11-08

TaskCreateForm/createTask를 구현했다. OWNER/ADMIN/MEMBER만 폼을 표시하고 myRole은 부모 Workspace 응답에서 받는다. title/description/priority만 POST하며 우선순위 기본은 MEDIUM이다. status는 보내지 않고 서버 201의 TODO/요청 값·두 부모·문자열 ID·상대 Location을 검사한다. 정상 성공 뒤 입력을 초기화하고 목록 0페이지와 새 Task ID 상세를 조회한다.

결과 불명에서는 입력과 저장 잠금을 유지하고 목록 확인은 GET만 수행한다. 초기화는 명시적이며 자동 재전송하지 않는다. 생성 후 후속 GET 실패를 POST 실패로 바꾸지 않고 새 탐색/생성·부모 종료의 늦은 결과를 무시한다. 프론트 355개/빌드·실제 격리 브라우저 POST·로컬 보존 결과는 local-setup.md에 기록했다. 이후 Task 수정 필드/역할·버전 충돌 정책은 [수정 계약](task-update-api-v1.md)으로 확정했다. 수정 API/V6/권한 도구는 구현·격리 검증했고 실제 로컬 적용과 Vue 수정 폼은 후속이다.

## V8 담당자·마감일 확장 — 2026-11-11

아래 항목은 V8에서 추가되어 위 초기 API 설명 중 `assigneeId`/`dueDate` 미지원·목록 여섯 필드·상세 DTO 여덟 필드라는 부분을 대체한다.

- `tasks.assignee_id`는 nullable users FK(`ON DELETE RESTRICT`)이며 `tasks.due_date`는 nullable PostgreSQL `date`다. Workspace membership FK는 두지 않는다. 현재 Workspace membership은 할당/변경 시 Service가 확인하고, 멤버 제거 뒤의 기존 할당은 보존한다.
- Create에서 `assigneeId`/`dueDate` 생략 또는 null은 미지정이다. 값은 각각 양의 정규 십진 문자열과 실제 유효한 `YYYY-MM-DD` 문자열이다. 과거 날짜도 허용한다.
- Task 목록/상세와 생성 응답은 `assigneeId`, `dueDate`를 nullable 문자열로 포함한다. 목록 항목은 여덟 필드가 된다. 상세/생성 TaskView는 version 포함 열 필드다.
- 생성 역할 OWNER·ADMIN·MEMBER, 조회 역할 네 역할, 부모/404 규칙, TODO/MEDIUM 기본값 등 나머지 계약은 유지한다. 전체 정책/권한은 [담당자·마감일 결정](task-assignee-due-date-v1.md)에 기록했다.
