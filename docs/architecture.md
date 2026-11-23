# 아키텍처 초안 v0.1

## 시작 구조
```
Browser (Vue 3 SPA)
    | HTTPS / REST (추후 WebSocket)
Spring Boot modular monolith
    |-- identity       사용자/인증
    |-- workspace      팀/멤버십
    |-- project        프로젝트
    |-- task           작업/댓글/변경이력
    |-- notification   알림(후속)
    |-- common         최소한의 공통 예외/응답/설정
    |
PostgreSQL

후속: Redis, Kafka, WebSocket, WebFlux 실험 모듈
```

## 권장 저장소 구조(구현 시 조정 가능)
```
flowdesk/
  AGENTS.md
  docs/
  frontend/             Vue 3 + TypeScript + Vite
  backend/              Spring Boot + Gradle
  infra/                Docker Compose, 환경설정
```

## 요청 처리 원칙
Vue Component → API Client → HTTP → Security Filter Chain → Controller/DTO validation → Application Service (transaction) → Repository/JPA → PostgreSQL → Response DTO → UI state.

Vue 생성은 WorkspaceCreateForm의 이름/진행/거부·성공/결과 불명 → workspaces.ts POST → 서버 생성 트랜잭션 → 201 확인 → 부모 WorkspaceBrowser의 목록/상세 GET 순서다. POST 성공과 후속 조회 실패를 분리하며 결과 불명 요청은 재전송하지 않는다. 기존 지역 상태/fetch/Vite를 유지하고 Router/Pinia·캐시/인증 저장소는 추가하지 않았다.

Vue 이름 수정(2026-11-05)은 상세 WorkspaceRenameForm의 편집 시작 name/version 스냅샷과 사용자 draft를 분리한다. PATCH 200의 검증한 DTO로 선택 상세/같은 ID의 목록 행만 갱신하며 목록 순서·페이지는 유지한다. 409/결과 불명에서는 입력·저장 잠금을 유지하고 최신 GET → 사용자 확인/편집 기준 갱신 → 별도 수동 저장으로 진행한다. GET만으로 편집 버전을 바꾸거나 저장 성공을 단정하지 않는다. 최종 인가는 기존 서버 Service가 담당한다.

## 도메인 관계 초안

현재 조회 화면은 WorkspaceBrowser가 page/detail 서버 데이터와 선택/로딩/오류 UI 상태를 분리하고, workspaces.ts가 HTTP·응답 형식 검사·안전한 오류를 담당한다. Vite /api 프록시 → 개발 접속 필터 → Controller → Service 소속 검사 → Repository/DB → DTO → 화면으로 이어진다. 선택/페이지 변경 시 이전 요청을 취소하고 취소된 응답을 적용하지 않는다. 한 화면의 지역 상태이므로 Router/Pinia/전역 캐시는 아직 없다. 실제 Spring Security/로그인은 Phase 2다.

첫 단계 User·Workspace·WorkspaceMembership의 확정 필드·제약·트랜잭션 책임·V1 경계는 [domain-model-v1.md](domain-model-v1.md)에 기록한다. 첫 Project의 모델·생성/조회·멤버십 상속 범위는 [Project 계약](project-model-and-api-v1.md)에 따라 V3/매핑·생성/조회·전용 권한 도구를 구현했다. Task 이후 관계는 계속 초안이다.
- User 1:N WorkspaceMembership N:1 Workspace
- Workspace 1:N Project
- Project 1:N Task
- Task N:1 User (assignee, nullable)
- Task 1:N Comment, Activity
- User 1:N Notification (후속)
- ProjectMembership는 첫 범위에 없다. 네 Workspace 역할이 Project 조회를 상속하고 OWNER/ADMIN만 생성한다. 비공개 접근 요구가 생길 때 재검토한다.

Project 설계는 `/api/workspaces/{workspaceId}/projects` 생성/목록과 그 하위 상세 경로를 사용한다. Project Service가 서버 행위자·부모 membership을 확인하고 Repository의 workspace_id 조건으로 자원을 제한한다. Project는 스칼라 workspaceId/DB FK를 사용하며 Workspace Entity를 cascade하지 않는다. 구현·격리 검증 뒤 실제 로컬 V3/PROJECT_CREATE도 적용했다. 새 앱에서 Workspace 1의 Project ID 1 생성/조회·인가를 확인하고 기존 로컬 사용자/공간/멤버십 5·3·6을 유지했다.

## 데이터 설계 규칙

이름 수정은 [수정 계약](workspace-rename-api-v1.md)으로 설계를 확정했다. OWNER/ADMIN 검사와 요청 version 비교 뒤 JPA @Version의 DB 충돌 검사로 보호하고 commit 성공 후 응답한다. V2·컬럼별 UPDATE/도구 전환·응답 version을 구현하고 격리 검증했다. 이후 사용자 요청으로 실제 로컬 V2/WORKSPACE_RENAME 적용과 새 앱의 수정 200/버전 충돌 409도 확인했다.

로컬 DDL은 앱 부팅과 분리된 전용 마이그레이션 프로세스로 실행한다. 앱 계정과 migrator 계정의 파일·클래스패스·환경 전달 경계는 [local-migration.md](local-migration.md)를 따른다. PowerShell → 전용 Gradle JavaExec → Spring 없는 Java main으로 로컬 V1을 적용했다. 현재 앱은 schema USAGE·업무 4개 테이블 SELECT, workspaces/workspace_memberships/projects INSERT와 workspaces UPDATE(name, updated_at, version)만 사용한다. users INSERT, 승인된 세 컬럼 외 UPDATE·DELETE/TRUNCATE·DDL·sequence/history 접근은 금지한다. GRANT와 앱의 쓰기 활성화 설정은 별개다.

개발 seed는 별도 수동 PowerShell → seed 전용 JavaExec/main → JDBC 트랜잭션으로 준비한다. 기존 migrator를 재사용하고 migration source set에만 seed 코드를 둔다. Apply는 테이블 잠금 뒤 빈 상태/전체 일치를 확인하며 한 트랜잭션으로 삽입하고, Verify는 읽기 전용 스냅샷으로 검증한다. V1·앱 부팅·Flyway 기본 실행 경로에 INSERT를 추가하지 않는다. [seed 실행기](dev-seed.md)로 원래 로컬 5/2/5를 준비했고 후속 업무 API로 공간/멤버십이 각각 한 행 추가돼 현재 5/3/6이다. seed의 전체 예제 일치 검사는 추가 업무 데이터를 수정하지 않고 중단한다.

- 식별자는 서버가 생성; 외부 ID를 신뢰하지 않는다.
- Task.assignee는 해당 프로젝트에 접근 가능한 멤버인지 검증한다.
- Task의 상태 변경과 Activity 기록은 같은 DB 트랜잭션으로 처리한다.
- 삭제/보관 정책은 엔티티별로 명시한다.
- 동시 변경 대응은 낙관적 락(`@Version`) 등을 비교한 뒤 결정한다.
- JPA Entity를 API 응답으로 직접 노출하지 않는다.

Project 보관 정책은 [Project archive 계약](project-archive-api-v1.md)과 ADR-068에 정의했다. Project/Task 행과 Task 이력을 삭제하지 않고 보관 Project의 조회는 유지하며 쓰기는 제한한다. Workspace 잠금은 Task mutation 및 archive 간 동시성을 직렬화하고 Project version은 보관/복구·이름 변경의 stale 요청을 검출한다.

Task 보관은 [Task archive 계약](task-archive-api-v1.md)과 ADR-069에 정했다. V11의 nullable `tasks.archived_at`과 기존 Task version을 사용한다. Task archive/restore와 Task update/status, Project archive는 같은 Workspace row lock을 사용해 직렬화한다. archived Task는 멤버에게 상세/상태 이력을 읽게 하지만 기본 목록에서 숨기며, archive/restore는 OWNER/ADMIN만 수행한다. 기존 column-scoped Task SELECT에는 새 컬럼 읽기를 포함하고 별도 archive capability는 `archived_at` column UPDATE만 추가한다. Spring 부팅은 권한을 부여하지 않는다.

첫 JPA 단계는 [읽기 전용 매핑/Repository](jpa-read-model-v1.md)를 구현했다. Membership → User/Workspace는 단방향 LAZY이고 필요한 소속 목록에서만 workspace를 함께 가져온다. Repository는 필요한 조회만 노출하고 OSIV를 끈다. 첫 읽기 권한·조회 검증 뒤에 HTTP 조회 Service를 구현했고, 별도 생성 CommandService/쓰기 어댑터도 추가했다. 조회 Repository/readOnly 계약은 유지한다.

## 인증/인가 설계 검토 항목

첫 생성은 [생성 계약](workspace-create-api-v1.md)에 따라 구현·격리 검증했다. 별도 개발 쓰기/Origin 경계 → Controller의 bounded UTF-8/JSON 검사 → 별도 쓰기 Service 트랜잭션 → 서버 지정 사용자 → Workspace/OWNER membership persist → 커밋 → 201 흐름이다. 조회 Service/readOnly는 유지한다. 실제 로컬도 WORKSPACE_CREATE 권한과 Alice의 첫 공간 ID 3/OWNER 생성을 확인했다. 서버 쓰기는 여전히 명시적으로 활성화해야 하며 검증용 쓰기 서버는 종료했다. 앱의 Flyway 자동 실행은 끄고 migration 리소스는 버전 SQL(V1/V2/V3)만 빌드 시 복사한다.

첫 조회는 [워크스페이스 조회 계약](workspace-read-api-v1.md)에 따라 구현·격리 검증했다. 명시적인 dev-actor 프로필·서버 지정 사용자·loopback 접속 경계를 사용하는 개발 모드에서만 목록/상세를 제공한다. 기본 실행에서는 업무 Controller를 등록하지 않는다. HTTP 입력 검증 → 조회 Service의 readOnly 트랜잭션 → 서버 지정 행위자 조회 → 사용자 조건 목록/공간+사용자 membership 검사 → DTO 변환 흐름을 따른다. 네 역할 모두 소속 공간 기본 조회가 가능하고 미소속/미존재는 동일 404다. 실제 인증은 Phase 2에 도입하며 그 뒤에도 Service 소속 검사는 유지한다. Service는 업무 예외를 던지고 API의 예외 처리기가 ProblemDetail/HTTP로 변환한다. 이후 별도 요청으로 실제 로컬의 Alice/Eve 개발 모드 HTTP 기동·조회·공간 격리도 확인했고 검증 서버는 종료했다.

- Spring Security의 인증과 인가 분리; URL 접근 통제 외에 리소스 소유권/멤버십 검사.
- JWT의 서명·만료·발급자·대상 검증; Access/Refresh Token 수명·회전·폐기 정책.
- 토큰 저장 방식(메모리/쿠키 등)과 XSS/CSRF 위협 모델을 함께 검토.
- MFA 등록, 챌린지, 인증 완료 상태, 복구 수단을 명확히 모델링.
- WebSocket handshake 및 메시지 단위 권한 검증.

## 비동기 이벤트 후속 설계
Task 변경 트랜잭션 → Outbox 레코드(필요 시) → Kafka Producer → Consumer → Notification 저장 → WebSocket 전송. 이벤트는 중복될 수 있다고 가정하고 idempotency를 설계한다.

## WebFlux 실험 경계
초기 API는 Spring MVC + JPA. 후속 별도 모듈/서비스에서 WebFlux의 비동기 I/O를 비교한다. JPA 블로킹 호출을 그대로 리액티브 체인에 넣지 않는다.


## Vue Project 조회 연결 — 2026-11-05

WorkspaceBrowser가 선택 공간의 유효한 상세를 얻으면 공간 ID key로 ProjectBrowser를 등록한다. ProjectBrowser는 자체 page/detail 서버 데이터와 로딩/오류/선택 상태를 가지며 projects.ts가 부모 하위 GET/타임아웃/DTO 검증을 담당한다. 공간 전환·부모 상세 종료는 자식 인스턴스/진행 요청을 종료하고, 목록 새로고침/페이지 이동은 Project 상세를 초기화한다. 늦은 응답은 취소 상태를 검사해 무시한다. 같은 공간 이름 수정은 자식 key를 유지한다.

서버의 readOnly Service·부모 membership/Repository 조건은 그대로다. Vue에서 사용자/역할을 지정하거나 DB 권한을 넓히지 않는다. 한 화면의 지역 상태이므로 Router/Pinia/범용 HTTP 계층은 추가하지 않았다. 설명은 텍스트 바인딩/pre-wrap이다. [프론트 흐름](../frontend/README.md).


## Vue Project 생성 연결 — 2026-11-05

WorkspaceBrowser가 전달한 myRole은 ProjectBrowser의 폼 표시 힌트다. 별도 ProjectCreateForm이 이름/설명 draft·검증·진행/거부/성공/결과 불명을 관리하고 projects.ts가 정규화·POST·201/Location/DTO 검사와 오류 분류를 담당한다. 부모 URL과 서버 행위자/Service 인가·기존 트랜잭션/DB 최소 INSERT 경계는 유지한다.

정상 201을 받으면 폼 성공을 먼저 확정하고 부모가 목록 0페이지/새 ID 상세를 조회한다. 이 GET 실패가 성공한 POST를 실패로 바꾸지 않는다. 탐색 순번/요청 취소로 이전 후속 조회의 늦은 자동 선택을 막는다. 결과 불명에서는 draft/잠금을 유지하고 목록 확인은 GET만 한다. 새 라이브러리·전역 저장소·백엔드/스키마 변경은 없다. [프론트 흐름](../frontend/README.md).


## Project 수정 설계 — 2026-11-05

[수정 계약](project-update-api-v1.md)은 부모 하위 PATCH에서 이름·설명·Project version을 받는다. HTTP 입력 → 서버 행위자/부모 membership → 부모+Project 조회 → OWNER/ADMIN → 요청 버전 비교 → 관리 Entity 변경/DB @Version → flush/commit → DTO/200 순서다. 자원을 역할보다 먼저 확인해 없는/잘못된 부모는 404로 유지한다. 실제 변경은 Project name/description/updated_at/version뿐이며 Workspace 버전은 바꾸지 않는다.

V4/PROJECT_UPDATE와 상세·생성의 version 문자열 전환을 설계했다. 목록 요약은 기존 세 필드를 유지한다. 기존 mandatory 쓰기 어댑터·no-op 낙관적 검증·분리된 관리자 컬럼 GRANT 패턴을 사용하고 앱 자동 DDL/권한 부여는 하지 않는다. 코드/DB 구현은 후속이며 현재 V3 구현을 완료 상태로 오해하지 않는다.


## Project 수정 구현 — 2026-11-05

확정한 [수정 계약](project-update-api-v1.md)의 Controller→Service 소속/부모+Project/역할/버전→mandatory 업데이트/flush→commit→DTO 흐름을 구현했다. Project Long @Version과 no-op OPTIMISTIC 검증을 쓰며 부모 Workspace는 변경하지 않는다. 수정 어댑터도 dev-actor/쓰기 조건으로 등록한다. PROJECT_UPDATE는 Project 네 컬럼 UPDATE만 추가하고 기존 Workspace 세 컬럼/최소 DML 정책을 유지한다. 기존 도구·이력/seed 검사·기동 검사를 함께 전환했다. 실제 로컬 V3/데이터는 그대로이며 후속 V4/권한 적용이 필요하다.


## Vue Project 수정 연결 — 2026-11-07

ProjectBrowser → ProjectUpdateForm의 지역 draft/동결 편집 기준 → updateProject의 부모 하위 PATCH/name·description·문자열 version → 기존 Controller/Service 소속·역할·JPA commit → 검증된 DTO/상세·같은 ID 목록 갱신 흐름이다. 폼은 Project ID로 key를 두어 최신 표시가 바뀌어도 같은 ID의 draft/기준을 보존하고, 다른 Project/공간 선택 시 언마운트해 요청을 취소한다. 복구는 Project GET+Workspace 역할 GET이 모두 성공해야 비교/명시적 기준 갱신이 가능하며 GET만으로 저장을 다시 보내지 않는다. 서버 모델/인가/DB 권한·전역 상태 계층을 변경하지 않았다.


## 첫 Task 생성/조회 설계 — 2026-11-07

[Task 계약](task-model-and-api-v1.md)에 따라 Task는 스칼라 project_id/DB FK로 Project에 속하고 workspace_id를 중복 저장하지 않는다. HTTP 입력/개발 접속 → 서버 행위자 → Workspace membership → Project의 id+workspaceId 조회 → 생성 세 역할 검사 또는 Task의 id+projectId 제한 조회 → Repository/트랜잭션 → DTO 흐름이다. 생성은 부모 확인 뒤 OWNER/ADMIN/MEMBER에 허용하고 네 역할 모두 읽는다. Project 생성/수정 역할은 OWNER/ADMIN 그대로다.

V5는 제목/설명/상태/우선순위·시간/부모 FK만 설계한다. 초기 상태는 TODO 고정이고 우선순위는 선택/기본 MEDIUM이다. 담당자/마감일/상태 변경·Activity/Task version은 후속이므로 앞의 관계 초안 전체를 이번에 구현하지 않는다. 특히 기존 POST 출처 필터는 Task 생성 경로가 아직 없어 명시적 패턴 확장과 실제 Vite 검증이 필요하다. 최소 Task SELECT/INSERT와 기존 PROJECT_UPDATE 보존·이력/seed/기동 검사 전환도 설계 범위이며 현재 코드는 바꾸지 않았다.


## Task 생성/조회 구현 — 2026-11-07

TaskController/TaskCreateController→readOnly QueryService 또는 쓰기 CommandService→행위자/Workspace membership→Project 부모 조건→생성 역할 또는 Task 부모 조건→제한 Repository/mandatory 생성 어댑터→DB→commit 후 DTO 흐름을 구현했다. 스칼라 projectId/enum·readonly 매핑으로 부모 변경/범용 저장을 노출하지 않는다. 기존 개발 POST 출처 필터와 기동 권한 검사에 tasks를 포함했다. V5/전용 TASK_CREATE와 기존 도구 보존·MigrationHistory/seed도 전환했다. 실제 로컬은 V4이므로 새 앱 실행 전에 후속 V5/Task 권한 적용이 필요하다.


## Vue Task 조회 연결 — 2026-11-08

선택한 Project 상세 아래에 Project ID key의 TaskBrowser를 둔다. Workspace 전환은 상위 ProjectBrowser를, Project 선택/페이지/새로고침은 하위 TaskBrowser를 종료한다. Task 자체 목록/상세·선택/로딩/오류는 지역 상태로 분리하고, tasks.ts가 두 부모 하위 GET·5초 제한·응답 검사를 맡는다. 목록/상세 요청은 각각 취소하고 취소된 늦은 결과를 적용하지 않는다. 같은 Project 이름 수정은 key를 유지한다.

TaskBrowser → tasks.ts → Vite /api → 기존 개발 접속 경계/TaskController → TaskQueryService의 readOnly 트랜잭션·서버 행위자/Workspace membership·Project 부모 검사 → TaskRepository의 project_id 조건 → DTO → 문자열 ID/부모·enum 검사 → 제목/상태/우선순위 목록과 일반 텍스트 설명 상세 순서다. 네 역할 모두 조회하며 Spring Security/실제 로그인은 Phase 2다. 새 의존성/인증 저장소·Router/Pinia·DB/권한 변경은 없다.


## Vue Task 생성 연결 — 2026-11-08

ProjectBrowser → TaskBrowser의 Workspace 역할 표시 힌트 → TaskCreateForm의 제목/설명/우선순위 draft·검증/진행/결과 불명 → tasks.ts/createTask의 두 부모 하위 POST(title/description/priority) → 기존 Task POST 개발 쓰기/Origin·JSON 경계 → TaskCommandService의 서버 사용자/멤버십·부모/세 역할 검사 → persist/flush/commit → TODO/201 DTO·상대 Location 검증 → 성공 안내와 목록 0페이지/새 ID 상세 GET 흐름이다.

입력 우선순위 기본은 MEDIUM이며 status/행위자/역할/부모를 본문에 넣지 않는다. 정상 201은 뒤따르는 GET 실패와 분리한다. 결과 불명에서는 입력/저장 잠금을 유지하고 자동 재전송하지 않는다. 새 생성·페이지/선택·부모 종료는 요청 취소/탐색 순번으로 이전 후속 선택을 막는다. 서버·SQL/권한·새 의존성은 변경하지 않는다.


## Task 수정 설계 — 2026-11-08

[Task 수정 계약](task-update-api-v1.md)은 Vue의 편집 시작 세 필드/Task version → 두 부모 하위 PATCH → 기존 개발 접속/쓰기·Origin/JSON 경계 → Controller 입력 → Service 쓰기 트랜잭션의 서버 사용자·Workspace membership→Project id+workspaceId→Task id+projectId→OWNER/ADMIN/MEMBER→요청 버전 → mandatory 수정 어댑터/Task @Version·OPTIMISTIC no-op 검증/flush → commit → DTO/200 흐름이다. stale/낙관적 충돌은 409이며 미소속/미존재/부모 불일치는 동일 404다.

V6에 Task 자체 version만 추가하고 별도 TASK_UPDATE 정책은 title/description/priority/updated_at/version 다섯 컬럼만 허용하는 설계다. 상태·부모/생성 시각 및 부모 Project/Workspace 버전·시각을 바꾸지 않는다. 응답의 Task 상세/생성/수정은 version 문자열을 보존하고 목록 여섯 필드는 유지한다. 이번은 계약만 확정했고 실제 코드/SQL/권한 변경은 없다.


## Task 수정 구현 — 2026-11-08

TaskUpdateController의 네 필드 입력/정규화 → TaskCommandService.update의 서버 사용자/membership·Project/Task 부모·역할/버전 검사 → mandatory TaskUpdateRepository의 OPTIMISTIC 검증/Task.update/flush → commit → TaskView/200을 구현했다. no-op도 commit 시점 검증을 하고 낙관적 충돌은 409다. Task status/부모/created_at과 Project/Workspace 버전/시각을 바꾸지 않는다.

전용 TASK_UPDATE는 다섯 컬럼만 추가하고 기동 검사·기존 도구가 정확한 상태를 확인/보존한다. V6/current 이력·seed/격리 fixture/과거 target을 전환했다. TaskView 상세/생성은 version 문자열 포함 여덟 필드, 목록 요약은 여섯 필드다. 기존 Vue 파서만 함께 전환했고 수정 폼·실제 로컬 V6/권한 적용은 별도다.


## 실제 로컬 Task 수정 확인 — 2026-11-08

로컬 V6/TASK_UPDATE를 적용하고 새 JAR의 실제 PATCH→개발 경계/TaskUpdateController→서버 행위자·부모/역할·버전→JPA flush/commit→200/DTO·조회 흐름을 확인했다. Task 1 version 1/HIGH/TODO와 stale 409/현재 버전 no-op, 다른 역할/부모/출처 거부·쓰기 off를 검증했다. 부모 Project/Workspace와 나머지 데이터는 유지했고 검증 앱은 종료했다. 현재 로컬은 V6/새 앱과 호환되며 실제 인증과 Vue 편집 연결은 후속이다.


## Vue Task 수정 연결 — 2026-11-08

TaskBrowser → TaskUpdateForm의 시작 title/description/priority/문자열 version과 draft → updateTask의 nested PATCH → 기존 Task Controller/Service 부모·역할/버전·트랜잭션 → 200 DTO 검증 → 현재 상세/같은 ID 목록 요약 갱신 흐름이다. 같은 ID props 갱신은 시작 기준을 바꾸지 않고 부모/선택 변경은 자식 인스턴스를 종료한다.

409/결과 불명 복구는 최신 Task GET + Workspace 역할 GET의 공동 성공→세 필드/우선순위 비교→사용자의 명시적 기준 갱신→별도 수동 저장이다. 자동 병합/재전송하지 않고 조회 실패/권한 상실은 잠금을 유지한다. backend/SQL/권한·의존성/전역 상태 계층은 변경하지 않았다. [실행 기록](local-setup.md).


## Task 상태 변경·최소 Activity 설계 — 2026-11-08

[상태 계약](task-status-api-v1.md)은 별도 nested PATCH /status → 기존 개발 경계/Controller 입력 → Service의 서버 사용자/Workspace membership·Project/Task 부모·세 역할/공유 Task version → 상태 전용 조건부 UPDATE(id/version)·행 수 검사/refresh → 고정 컬럼 이력 INSERT → 같은 트랜잭션 commit → 기존 TaskView/200 흐름이다. 일반 수정의 status updatable=false 매핑을 유지하며 native SQL의 버전 검증/증가를 명시적으로 맡긴다. no-op은 상태/이력을 쓰지 않고 기존 OPTIMISTIC 최종 검증을 사용한다.

상태 UPDATE/Activity INSERT/지연 commit 실패는 모두 rollback한다. 상태·일반 수정은 한 Task version으로 경합하며 이력만 비동기 저장하지 않는다. V7은 상태 전이 전용 task_status_activities만 추가하고 기존 행·상태/버전·이력·ACL/시퀀스를 보존한다. 전용 TASK_STATUS_CHANGE는 status UPDATE와 이력 여섯 컬럼 INSERT만 추가하며 이력 SELECT/수정·삭제/직접 시퀀스는 제외한다. 이번은 계약이며 코드/DB는 바꾸지 않았다. 조회/Activity 피드·실시간/메시지는 후속이다.

### Task 상태·이력 저장 경계 구현 — 2026-11-10

일반 수정의 Task.status updatable=false를 유지한다. 상태 전용 mandatory Repository가 id/version 조건·version 증가·영향 행 수 1을 직접 검사하고 EntityManager.refresh 후 DB 시각/새 버전으로 이력 고정 여섯 컬럼 INSERT를 수행한다. INSERT 0건도 실패이며 Service 트랜잭션의 최종 commit까지 상태/이력이 함께 rollback된다. 같은 상태는 기존 OPTIMISTIC commit 검사를 적용하고 상태/일반 수정은 한 Task version으로 경합한다. 이력 ID 조회나 비동기 저장은 사용하지 않는다. [구현 기록](task-status-implementation.md).

### 실제 로컬 상태/이력 트랜잭션 확인 — 2026-11-10

로컬 상태 PATCH → 개발 경계/TaskStatusController → 서버 행위자/부모·역할/공유 버전 → 조건부 UPDATE·refresh·이력 INSERT/commit → 기존 DTO 200/목록·상세 흐름을 확인했다. Task 1은 Carol의 TODO→IN_PROGRESS/version 2이고 Task updated_at과 이력 occurred_at이 같다. no-op/거부·stale·일반 수정 no-op은 새 이력을 만들지 않았다. 앱에는 이력 SELECT가 없고 별도 관리자 검증에서만 실제 이력을 대조했다. 장애/병렬 검증은 앞선 격리 결과와 구분한다. [실행 기록](local-setup.md).

### Vue 상태 변경·일반 편집 교차 경합 — 2026-11-10

TaskBrowser → 독립 TaskStatusForm 시작 스냅샷/선택 → changeTaskStatus(status/문자열 version) → 기존 서버 인가/공유 버전·상태/이력 트랜잭션 → TaskView/200 검증 → 현재 상세/같은 ID 목록 흐름이다. 두 폼의 draft/기준은 독립이고 서버 Task 버전은 하나다. 한 폼의 성공 DTO/재조회로 다른 폼의 입력/버전을 자동 교체하지 않는다.

409/결과 불명 복구는 Task/Workspace 역할 공동 GET·비교 후 명시적 기준 갱신·별도 수동 저장이다. 현재 폼 기준/props보다 오래된 GET을 채택하지 않고 TaskBrowser도 이전 버전으로 화면을 되돌리지 않는다. 일반 폼은 세 필드와 시작/서버 상태, 상태 폼은 시작/서버/선택 상태와 최신 일반 정보를 비교한다. 새 전역 상태 계층이나 백엔드 변경은 없다. [검증/설계](task-status-ui.md).

### Task 상태 이력 조회 계약 — 2026-11-10

[계약](task-status-history-read-api-v1.md)은 Task별 GET → 입력 → 서버 사용자/현재 소속·Project/Task 부모 → 네 역할 → READ_COMMITTED/readOnly 이력/사용자 표시 이름 projection → DTO/200 흐름이다. 행위자의 현재 membership으로 JOIN/필터링하지 않아 탈퇴한 행위자의 기록을 숨기지 않는다. User Entity/이메일은 직렬화하지 않고 항목별 추가 사용자 조회를 피한다.

페이지는 task_id와 배타적 beforeVersion 경계·task_version DESC·size+1 조회로 이어 읽는다. V7의 기존 UNIQUE(task_id,task_version)를 활용하고 V8/별도 상태 버전·전체 count/통합 피드/실시간은 추가하지 않는다. 앱 권한은 기존 TASK_STATUS_CHANGE + audit 7개 컬럼 SELECT로 설계하고 관리자 원시 ACL·읽기/쓰기 기동 검사·기존 도구의 보존을 함께 전환한다. 이번은 계약 확정이며 구현/DB 권한 적용은 후속이다.

### Task 상태 이력 조회 구현 — 2026-11-10

TaskStatusHistoryController → 현재 서버 행위자/Workspace 소속·Project/Task 부모 → READ_COMMITTED/readOnly QueryService → mandatory native projection Repository → 8필드 DTO 흐름을 구현했다. 이력과 users.display_name만 한 쿼리로 읽으며 actor의 현재 membership/이메일 Entity는 조회 결과에 연결하지 않는다. PostgreSQL에서 UTC 문자열로 변환한 timestamptz를 Instant로 읽어 마이크로초를 보존한다.

기존 UNIQUE(task_id,task_version)·배타적 경계·DESC·size+1을 사용하며 새 migration/Entity/의존성은 없다. 관리자 원시 ACL/유효 권한 검증과 dev-actor 읽기/쓰기 기동 검사를 TASK_STATUS_HISTORY_READ로 전환했다. 앱의 자동 GRANT는 없고 실제 로컬에는 별도 적용이 필요하다. [구현·대안·검증](task-status-history-implementation.md).

### 실제 로컬 Task 상태 이력 조회 — 2026-11-10

전용 관리자 도구로 TASK_STATUS_HISTORY_READ를 적용했다. 앱 role은 table-wide SELECT 없이 audit 7개 컬럼만 읽고 기존 상태 UPDATE와 Activity INSERT 권한을 유지한다. 새 JAR의 개발 행위자 Carol로 실제 Controller→현재 Workspace 소속·Project/Task 부모→readOnly projection→DTO 경로와 Actuator UP을 확인했다. 버전 2의 TODO→IN_PROGRESS 기록 1건과 UTC microseconds, 빈 마지막 커서도 맞았다. [실행 결과](local-setup.md).
### Vue Task 상태 이력 화면 — 2026-11-10

Task 상세 → 접힌 TaskStatusHistory → GET `getTaskStatusHistory` → 응답 envelope/item/actor·Task 부모·strict UTC/버전 커서 검증 → 20개 최신 버전 DTO → `<time>`/상태 전이 표시로 연결했다. 한 화면에서 페이지를 이어 붙이고 응답 nextBeforeVersion을 사용한다. 더 보기 실패 시 이미 읽은 항목/커서를 보존한다. 기존 상태 변경 형식/인가/DB는 건드리지 않는다. 이력 API 요청은 사용자가 펼칠 때만 발생한다. 펼쳐진 화면은 Task 상태 변경 뒤 재조회하고 project/task 변경·unmount 때 요청을 취소한다. [UI 구현](task-status-history-ui.md).

### Task 담당자·마감일 모델 결정 — 2026-11-10

Task는 nullable `assignee_id`→User FK와 nullable SQL `date`를 갖는다. Workspace는 Project 경로에서 유도하고 담당자를 지정할 때 같은 Workspace의 현재 membership을 확인한다. Membership FK는 제거된 멤버의 기존 assignment 보존 정책과 충돌하므로 두지 않는다. Task의 낙관적 version은 담당자/날짜를 포함한 모든 수정에 공유한다. API 계약·최소 컬럼 권한·V8 영향은 [결정 문서](task-assignee-due-date-v1.md)에 기록했다.

### Task 담당자·마감일 V8 구현 — 2026-11-11

Task Entity는 scalar nullable User ID와 `LocalDate`를 매핑한다. 생성/전체 PATCH Controller는 정규 ID·날짜를 검증하고 Service는 기존 actor/role/부모 검증 뒤 target membership을 검사한다. Entity dirty checking과 기존 `@Version`/optimistic repository 경계가 두 필드를 저장한다. 목록/상세 DTO는 두 필드를 포함한다. 앱의 기존 Task table-wide SELECT/INSERT는 전용 단계에서 회수하고 column ACL 11/8/8로 전환한다. Vue API parser는 이를 보존하되 편집 UI는 별도다. [구현 흐름](task-assignee-due-date-implementation.md).

### Workspace 현재 멤버 후보 조회 계약 — 2026-11-11

`GET /api/workspaces/{workspaceId}/members`는 서버 행위자와 해당 공간의 현재 membership을 검사한 뒤 ID·표시 이름·역할 projection을 페이지로 반환한다. 이메일/Entity 직렬화는 제외한다. Vue 담당자 선택은 이 목록을 표시하지만 실제 Task POST/PATCH 시 Service가 membership을 다시 확인한다. 초대·역할 변경·제거는 읽기 endpoint와 분리한다. [계약](workspace-member-list-api-v1.md).

### Workspace 현재 멤버 목록 구현 — 2026-11-12

Controller strict page parameters → WorkspaceQueryService actor/current membership check → Repository native `id,display_name,role` Slice projection → string-ID response 흐름이다. read-only GET은 현재 네 역할을 허용하고 404 누출 정책을 공유한다. AppReadPolicy와 전용 admin runner는 세 기반 테이블의 broad SELECT를 exact mapped columns로 전환하며, mixed/partial ACL은 거부한다. [검증 기록](workspace-member-list-implementation.md).

### Vue Task 담당자 선택 — 2026-11-13

`TaskAssigneeSelect`는 select focus 시 workspace-scoped 100-row page를 이어 읽어 이름·역할·ID 후보를 만든다. API parser가 모든 page와 member DTO를 검증하고, 요청 취소/재시도 및 비밀정보 없는 오류 안내를 제공한다. Create/update는 선택된 문자열 ID를 기존 POST/PATCH contract로 전달한다. Update는 현재 후보에 없는 기존 assignee를 별도 보존 항목으로 표시해 유지·해제만 허용한다. Task CommandService는 unchanged 기존 assignment를 보존하고 실제 변경된 non-null assignment만 current membership 검사한다.
## Workspace 멤버 수명주기 — 2026-11-13

현재 멤버 GET와 별도로 pending invitation을 관리한다. 초대는 기존 User ID로 결속하고 실제 WorkspaceMembership은 수락 시 생성한다. 코드 원문은 발급 응답에 한 번만 반환하며 DB에는 digest, 발급/만료/상태 및 대상·공간 관계를 저장한다. 기존 membership table을 초대 전 상태 저장 용도로 재사용하지 않는다.

초대 수락, 역할 변경, 제거, 소유권 이전은 Workspace aggregate 경계에서 처리한다. 각 mutation은 Workspace row lock을 먼저 획득한 뒤 actor membership/role과 대상 상태를 재검증한다. Workspace는 항상 정확히 한 OWNER로 커밋한다. OWNER 이전은 현재 OWNER만 실행하고 이전 OWNER를 ADMIN으로 강등한다. 초대/role/delete endpoint, actor, parent와 resource 확인은 Service transaction 안에 둔다.

Task 담당자 변경과 멤버 제거도 같은 Workspace lock을 공유해 membership 검사와 저장이 직렬화되도록 한다. 멤버십 제거는 `tasks.assignee_id`를 cascade하거나 지우지 않으며 과거 상태 이력도 보존한다. 기존 historical assignee ID를 유지하거나 null/현재 멤버로 바꾸는 기존 Task 계약을 따른다. [정책·수용 기준](workspace-membership-management-v1.md).
