# Task 담당자·마감일 구현 기록

- 날짜: 2026-11-11
- 범위: V8 nullable 담당자/날짜 컬럼, Task 생성·수정·목록·상세 API, 멤버십 검사, 최소 DB 컬럼 권한 도구, 기존 Vue API 파서 호환
- 정책: [결정 문서](task-assignee-due-date-v1.md), [ADR-061](decisions.md)

## 요청에서 DB까지

`POST/PATCH` strict JSON은 `assigneeId` 문자열 ID 또는 null, `dueDate` ISO `YYYY-MM-DD` 또는 null을 파싱한다. Service는 요청자의 현재 Workspace membership과 기존 OWNER/ADMIN/MEMBER 수정 정책을 검사하고, 쓰기 권한이 있는 경우에만 assignee 대상의 동일 Workspace membership을 확인한다. 역할 확인 전에 대상 membership을 조회하지 않아 VIEWER에게 대상 자격 정보를 노출하지 않는다. PostgreSQL은 User FK/nullable date를 강제하고, Task 전체 단일 version이 제목·설명·우선순위·담당자·날짜 간 동시 수정 충돌을 검출한다.

조회/생성/수정 DTO에는 nullable 문자열 필드가 포함된다. Vue API parser는 두 필드의 형식과 calendar 유효성을 검사한다. 생성/수정 폼은 native 날짜 입력과 현재 Workspace 멤버 이름·역할·ID 선택 목록을 제공하며, 빈 값은 미지정/null로 보낸다. 멤버 목록 fetch 실패는 후보 선택만 막고 기존 할당을 보존하며 명시적 재시도를 허용한다. 수정 폼은 409 응답 시 최신 서버 값을 보여 주면서 입력 초안을 유지하고, 사용자가 새 버전으로 다시 저장할 수 있다.

Task 수정 시 기존 assignee ID가 현재 membership에서 사라져도 해당 값을 그대로 보존할 수 있다. 실제로 다른 non-null ID로 변경할 때만 CommandService가 현재 Workspace membership을 요구한다. null 해제는 계속 허용한다.

## 최소 권한

기존 tasks table-wide SELECT/INSERT 권한을 회수한다. 앱에는 Task 매핑에 필요한 SELECT 11개 컬럼, INSERT 8개 컬럼, 업무 수정과 기존 상태 전이를 위한 UPDATE 8개 컬럼만 승인한다. Identity sequence/table DELETE/TRUNCATE 등은 허용하지 않는다. 전용 `runAppTaskAssigneeDueDateAccess`/PowerShell Apply 도구는 이전 승인 상태와 V8 migration history를 검증하고 idempotent하게 적용하며 partial/PUBLIC/과도한 권한은 자동 수선하지 않고 거부한다.

## 검증

- 프론트: `frontend`에서 `npm.cmd run build` 통과(type-check 및 Vite production build), `npm.cmd test -- --run` 21개 파일/493개 테스트 통과.
- 백엔드 컴파일: `compileJava`, `compileTestJava`, `compileMigrationJava` 통과.
- 백엔드 격리 PostgreSQL: `V8MigrationTest`, `TaskAssigneeDueDateAccessPolicyTest`, `TaskApiTest`, `TaskUpdateApiTest` 선택 실행 통과. 총 89개 테스트, 실패/오류/스킵 0. V8 데이터 보존·반복 검증, 정확한 Task 컬럼 ACL·INSERT/UPDATE·금지 권한, 멤버십/날짜 입력·공유 버전 충돌·HTTP 역할/부모 범위를 확인했다.
- 초기 집중 실행에서 개발 쓰기 기동 검사에 Task UPDATE를 금지하는 조건과 8개 허용 컬럼을 동시에 요구하는 모순이 드러나 참조 권한만 금지하도록 수정했다. 시작 권한 테스트도 새 컬럼 단위 ACL 상태를 확인하도록 바꿨고 최종 실행에서 통과했다.
- 실제 로컬 V8 1건/Validate 성공/재실행 0건과 Task 최소 권한 최초·반복 적용을 완료했다. 새 loopback 앱에서 Task 1 담당자(Dave)·날짜 지정/GET/해제/GET 10개 점검을 통과했다. 다른 데이터·Activity·ACL·시퀀스는 유지했고, Task 1의 의도된 변경 결과로 version 2→4와 `updated_at`만 바뀌었다. 전체 기록은 [local-setup.md](local-setup.md)에 있다.

## 다음 한 작업

다음 한 작업은 실제 로컬 column SELECT 적용 뒤 새 앱의 멤버 목록 HTTP를 확인하는 것이다.
