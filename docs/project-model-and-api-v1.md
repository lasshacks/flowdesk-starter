# Project 첫 데이터 모델·생성/조회 계약

- 날짜: 2026-11-05
- 상태: V3 SQL/JPA/API/권한 도구 구현·격리 검증 및 실제 로컬 적용·생성/조회 확인 완료.
- 기준: [요구사항](requirements.md), [아키텍처](architecture.md), [기술 스택](tech-stack.md), [로드맵](roadmap.md), [기존 업무 모델](domain-model-v1.md).
- 완료 기준: Project 필드·관계·제약·API·역할·트랜잭션·최소 권한·검증 경계를 확정한다. 개별 검증 결과와 실제 로컬 적용을 구분한다.

## 사용자와 확정한 업무 정책

| 항목 | 선택 |
|---|---|
| 소속/조회 | Project는 Workspace 하나에 속한다. 별도 ProjectMembership 없이 워크스페이스 멤버십을 상속하고 네 역할 모두 그 공간의 모든 프로젝트를 조회한다 |
| 생성 | 해당 Workspace의 OWNER·ADMIN만 허용한다. 다른 공간의 역할은 적용하지 않는다 |
| 이름 | 앞뒤 공백 제거·1~100 Unicode 코드 포인트, 동일 공간에서도 중복 허용 |
| 존재 은닉 | 미소속·미존재·URL의 공간과 프로젝트 불일치는 동일한 404 |

생성자는 새 프로젝트 전용 OWNER가 되거나 별도 membership을 얻지 않는다. 권한의 근거는 계속 해당 공간의 membership이다. 비공개 프로젝트/ProjectMembership는 도입하지 않는다. 필요해지면 생성·조회·초대 규칙을 함께 재설계한다.

첫 범위는 생성·소속 공간별 목록·상세다. 수정·보관/복구·삭제·Task·프로젝트별 초대/역할·검색/사용자 지정 정렬·실제 인증·Vue 화면은 후속이다. 보관 상태와 낙관적 version은 해당 변경 기능의 정책을 정할 때 추가한다. 지금은 빈 미래 컬럼을 만들지 않는다.

## 모델과 V3 범위

```text
Workspace 1 ── N Project
User ── WorkspaceMembership ── Workspace의 역할을 Project 접근에 사용
```

마이그레이션 파일은 `backend/src/main/resources/db/migration/V3__create_projects.sql`이다. V1/V2는 수정하지 않는다. 미리 준비된 flowdesk 스키마에 아래 테이블/인덱스만 추가하며 DDL 소유자는 기존 flowdesk_migrator다.

| flowdesk.projects 컬럼 | 타입/기본값 | 제약·의미 |
|---|---|---|
| id | bigint GENERATED ALWAYS AS IDENTITY | PK, 서버 생성 |
| workspace_id | bigint | NOT NULL, FK → workspaces.id, ON DELETE RESTRICT |
| name | varchar(100) | NOT NULL, CHECK char_length(btrim(name)) > 0, UNIQUE 없음 |
| description | varchar(2000) | nullable, 선택 일반 텍스트 |
| created_at | timestamptz / CURRENT_TIMESTAMP | NOT NULL |
| updated_at | timestamptz / CURRENT_TIMESTAMP | NOT NULL, 현재는 INSERT 기본값 |

- `(workspace_id, id)` 인덱스로 공간별 ID 오름차순 목록을 지원한다. FK만 선언하고 이 조회 인덱스가 자동으로 생긴다고 가정하지 않는다.
- name의 API 정규화/제어 문자 검사는 기존 Workspace와 같은 기준이다. DB btrim CHECK가 Java의 모든 Unicode 공백 정책을 강제한다고 주장하지 않는다.
- 설명은 선택 필드라는 구현 기본값이다. 누락/null/빈 값/정규화 후 공백만 남으면 null, 그 외 최대 2,000 코드 포인트다. CRLF/CR을 LF로 바꾸고 기존 Java 공백 기준으로 양끝을 제거한다. 내부 LF/TAB은 허용하며 그 외 ISO 제어 문자/NUL/잘못된 surrogate는 거부한다. DB varchar는 최대 길이를 제한하고 API가 정규화한다.
- HTML/Markdown 해석 없이 일반 텍스트로 저장·응답한다. 후속 Vue는 텍스트 바인딩을 쓰고 v-html로 렌더링하지 않는다.
- updated_at DEFAULT는 UPDATE 시 자동 갱신이 아니다. 이번에는 UPDATE가 없으며 수정 기능에서 시간/버전 매핑을 함께 정한다.
- created_by/프로젝트 owner/감사 이력은 현재 요구가 없어 넣지 않는다. 생성자별 추적이 필요해지면 정책과 스키마를 별도 추가한다. 생성 시 행위자 검사를 생략한다는 뜻은 아니다.
- V3에는 계정/GRANT·seed INSERT·새 membership·상태/Task 테이블을 넣지 않는다. 기존 사용자/공간/멤버십/워크스페이스 버전과 V1/V2 이력을 보존한다.

## HTTP 계약

경로를 Workspace 아래로 고정한다. 새로운 `/api/projects` 전역 경로나 JSON workspaceId 선택은 제공하지 않는다. URL ID는 1~Long.MAX_VALUE의 정규 십진 표기(선행 0/부호 없음)로 검증하고 DTO의 ID는 문자열이다.

### 생성

```http
POST /api/workspaces/3/projects
Content-Type: application/json
Accept: application/json
Origin: http://127.0.0.1:5173

{"name":"  Delivery  ","description":"첫 번째 프로젝트"}
```

- name은 필수 문자열, description은 생략/null/문자열만 허용한다. workspaceId/id/userId/ownerId/role/version/상태/시간·알 수 없는 필드·중복 JSON 키·후행 JSON·쿼리 파라미터는 400이다. Entity에 직접 바인딩하지 않는다.
- UTF-8 application/json만 허용하고 charset/Content-Encoding 정책은 기존 쓰기와 같다. 실제 본문 읽기 기준 최대 32 KiB, 초과는 413이다. 2,000 코드 포인트 설명의 UTF-8/JSON escape 표현을 수용하며 임의 크기 공백/추가 본문은 제한한다. 기존 Workspace 본문 제한 4 KiB는 유지한다.
- 부모 공간은 URL, 행위자는 서버 설정, 생성 자격은 Service가 정한다. 클라이언트가 별도 멤버십/역할을 선택할 수 없다.

```http
HTTP/1.1 201 Created
Location: /api/workspaces/3/projects/21
Content-Type: application/json
Cache-Control: no-store

{"id":"21","workspaceId":"3","name":"Delivery","description":"첫 번째 프로젝트"}
```

- Location은 서버 ID로 만든 상대 경로다. Host/전달 헤더를 사용하지 않는다. 커밋 성공 후 201을 반환한다.
- 동일 이름의 독립 요청은 각각 생성된다. 자동 재시도/멱등키는 제공하지 않는다. 응답 유실·커밋 결과 불명은 GET으로 확인하고 POST를 자동 재전송하지 않는다. 이름 일치만으로 특정 요청의 성공을 증명할 수 없다.

### 목록

```http
GET /api/workspaces/3/projects?page=0&size=20
Accept: application/json
```

```http
HTTP/1.1 200 OK
Content-Type: application/json
Cache-Control: no-store

{"items":[{"id":"21","workspaceId":"3","name":"Delivery"}],"page":0,"size":20,"hasNext":false}
```

- page 기본 0/범위 0~10000, size 기본 20/범위 1~100, 알 수 없는/중복 쿼리는 400이다. ID 오름차순 Slice이며 totalCount를 계산하지 않는다.
- 소속을 확인한 뒤 목록을 읽는다. 소속 공간에 프로젝트가 없거나 유효한 뒤 페이지가 비면 200/빈 items다. 미소속/없는 공간은 빈 목록으로 바꾸지 않고 404다.
- 목록 DTO는 id/workspaceId/name만 반환해 긴 설명을 페이지마다 반복하지 않는다. 이름/설명 검색과 정렬 선택은 제외한다.

### 상세

```http
GET /api/workspaces/3/projects/21
Accept: application/json
```

```http
HTTP/1.1 200 OK
Content-Type: application/json
Cache-Control: no-store

{"id":"21","workspaceId":"3","name":"Delivery","description":"첫 번째 프로젝트"}
```

- 상세와 생성은 같은 DTO다. 설명이 없으면 description:null이다. version/myRole/생성자 이메일/멤버 목록/Entity는 노출하지 않는다. UI 생성 권한 힌트는 부모 WorkspaceView.myRole을 쓰되 서버는 매 요청 재검사한다.
- `project.id`만 조회하고 URL의 workspaceId를 무시하지 않는다. 사용자가 두 공간 모두에 속해도 `/A/projects/B의프로젝트`는 404다.
- Project에는 아직 수정 기능이 없어 Workspace의 version을 프로젝트 버전처럼 받거나 반환하지 않는다.

## 인가와 오류 우선순위

| 행위자/자원 | 생성 | 목록·상세 |
|---|---|---|
| 해당 공간 OWNER·ADMIN | 201 | 200 |
| 해당 공간 MEMBER·VIEWER | 403 / PROJECT_FORBIDDEN | 200 |
| 미소속/없는 공간 | 404 / PROJECT_NOT_FOUND | 404 / PROJECT_NOT_FOUND |
| 공간 내 없는 프로젝트/다른 공간의 프로젝트 | 해당 없음 | 404 / PROJECT_NOT_FOUND |

HTTP 구조/입력 검증 → 서버 행위자 → 부모 membership 조회 → 생성이면 역할 검사/상세면 공간+프로젝트 조회 순서다. 다른 공간의 OWNER에게 전역 권한을 주지 않는다. 업무 403은 기존 개발 출처 실패의 DEV_ACCESS_DENIED와 구분한다. 유효한 ID 요청의 404는 동일한 안전한 코드/설명을 사용하고 현재 데이터/SQL을 포함하지 않는다.

400 INVALID_REQUEST, 413 REQUEST_TOO_LARGE, 415 UNSUPPORTED_MEDIA_TYPE, 503 SERVICE_UNAVAILABLE, 500 INTERNAL_ERROR는 기존 ProblemDetail 원칙을 따른다. 모든 제약/권한 오류를 400/409로 바꾸지 않는다. 중복 이름은 충돌이 아니며 이번 Project에 버전 충돌 409는 없다.

## 계층·트랜잭션·개발 경계

- 생성: 개발 접속/쓰기·Origin → Controller의 32 KiB strict JSON/DTO → ProjectCommandService(readOnly=false, READ_COMMITTED, 예외 롤백) → 현재 사용자 → WorkspaceMembershipRepository 소속/OWNER·ADMIN → Project 팩토리/쓰기 어댑터 persist·flush → 프록시 commit → 201/Location/DTO.
- 조회: 개발 접속 → Controller ID/페이지 검사 → ProjectQueryService(readOnly=true) → 현재 사용자/공간 membership → workspace_id 조건 Repository → DTO → 200. 목록에 이어 Entity별 부모를 lazy 조회하는 N+1을 만들지 않는다.
- Project Entity는 workspaceId 스칼라 FK로 매핑하는 기본값을 사용한다. Workspace Entity를 cascade persist/merge하지 않는다. 조회 Repository는 `findByWorkspaceIdOrderByIdAsc`와 `findByIdAndWorkspaceId` 두 조회만 노출하고 쓰기는 별도 mandatory 어댑터로 제한한다. 범용 save/delete/findAll은 추가하지 않는다.
- 기존 workspace membership 조회 계약을 재사용하고 범용 권한 프레임워크/외부 서비스를 도입하지 않는다. 인가는 트랜잭션에서 읽은 membership 기준이다. 향후 역할 변경/탈퇴와 생성의 동시성은 해당 기능 전 별도 설계한다.
- dev-actor·서버 지정 사용자·loopback·전달 헤더 비활성 정책을 유지한다. 기존 FLOWDESK_DEV_WORKSPACE_WRITE_ENABLED를 공간 내 Project 생성에도 재사용하며 false면 생성 Controller/Service 미등록, true면 기존 허용 Origin·JSON 검사를 nested POST에도 적용한다. 쓰기 필터를 nested POST까지 확장했다.
- 기본 프로필은 업무 API 404, dev-actor/쓰기 off는 프로젝트 조회 가능·POST 405다. 새 dev-actor 앱은 프로젝트 SELECT를 기동 시 확인하고, 쓰기 모드는 INSERT도 확인한다. 누락 권한을 앱이 자동 GRANT하지 않는다. 로그인/Spring Security/운영용 CSRF는 계속 Phase 2다.

## 최소 DB 권한과 기존 도구 전환

- 설계 상태 PROJECT_CREATE는 기존 WORKSPACE_RENAME 전체 권한을 보존하고 projects의 SELECT/INSERT만 추가한다. UPDATE/DELETE/TRUNCATE·sequence/history·DDL·광범위 기본 권한은 추가하지 않는다. 읽기 서버에 DB INSERT 권한이 있어도 HTTP 쓰기는 별도 설정/역할 검사로 제한한다.
- 별도 관리자 Project 권한 도구만 정확한 WORKSPACE_RENAME → PROJECT_CREATE로 전환한다. 반복 실행은 같은 상태를 보존하고 부분/과도·PUBLIC/GRANT OPTION·예상 밖 컬럼 권한은 자동 보정하지 않는다.
- 공통 AppReadPolicy의 고정된 3테이블 검사와 read/create/rename 실행기 결과 허용 상태를 함께 갱신한다. 과거 권한 상태는 migration/provisioning 과정에서 계속 인정하되 Project 권한의 부분 상태는 허용하지 않는다. 기존 세 컬럼 UPDATE만 허용하는 정책은 유지한다.
- MigrationHistory/권한/seed 실행기의 현재 이력 기대값을 1,2,3으로 갱신한다. Project는 운영 seed에 추가하지 않는다. 기존 seed 예제와 Projects 비어 있음일 때만 동일 데이터 0건 처리를 허용하고, 프로젝트 업무 데이터가 있으면 Apply/Verify는 수정·삭제 없이 mismatch로 중단한다.
- 새 Entity는 앱의 JPA validate 대상이므로 기본 프로필도 V3가 필요하다. 실제 적용 순서는 앱 중지 → 전용 migrator V3 → 별도 최소 Project 권한 도구 → 새 앱이다. Flyway 자동 실행/ddl-auto=validate/OSIV=false/기존 자격증명 분리는 유지한다.
- 구현·격리 검증 뒤 별도 사용자 요청으로 로컬 V3와 최소 SELECT/INSERT를 적용했다. 기존 5·3·6·공간 ID 3 version 1을 유지하고 Workspace 1의 Project ID 1만 추가했다. 자격증명은 보존했다.

## 구현 후 격리 수용 기준

1. 데이터가 있는 V2에 V3 적용/Validate/재실행 0건, V1/V2 checksum·모든 기존 행/버전/ACL/시퀀스 보존, 빈 projects·FK RESTRICT·인덱스/길이 제약을 확인한다.
2. 같은 공간 OWNER·ADMIN 생성 201, MEMBER·VIEWER 생성 403/조회 200, 다른 공간 OWNER와 미소속/미존재 404를 실제 HTTP로 검증한다. 소속이 없는 빈 공간 요청도 빈 목록 200으로 숨기지 않는다.
3. 두 공간에 모두 속한 사용자도 URL 부모/프로젝트가 다르면 404다. 목록은 해당 workspace_id만 포함하고 페이지/hasNext/기본값/한도·잘못된 ID를 검증한다.
4. 이름 중복의 순차/병렬 생성은 서로 다른 ID로 성공한다. 이름·설명 정규화/nullable·Unicode 길이·32 KiB·중복/알 수 없는 JSON·클라이언트 주체/역할/부모 지정 거부를 검증한다.
5. 저장/지연 commit 실패는 project 행을 남기지 않고 201을 반환하지 않는다. 행위자 없음·DB 일시 장애·예상하지 못한 제약을 안전한 오류로 분류한다.
6. 실제 앱 계정의 SELECT/IDENTITY INSERT·키 반환, 금지 DML/sequence/history/DDL 거부와 부분/과도한 ACL/잘못된 자격증명 거부를 검사한다. 기존 도구는 PROJECT_CREATE를 보존한다.
7. 실제 Vite nested POST의 Origin 전달/외부 Origin 차단, 기본/읽기 모드·헤더 위조·새 앱 권한 누락 시 실패와 seed 추가 데이터 mismatch를 검증한다.
8. 기존 Workspace API/rename 충돌·마이그레이션·권한·프론트 101개/타입 검사/빌드 회귀를 실행한다. 실제 로컬을 보존하고 격리 환경/자격증명을 정리한다. 구현 전에 통과했다고 기록하지 않는다.

## 대안과 다음 단계

ProjectMembership/비공개 접근은 별도 초대/역할 체계가 필요해 사용자가 현재 선택하지 않았다. 생성 MEMBER 허용과 공간별 이름 UNIQUE도 사용자가 선택하지 않았다. 스칼라 FK는 현재 Project 조회에서 불필요한 부모 Entity 연관/cascade를 줄이며, 명시적 부모 조건·DB FK·Service 인가를 각각 유지한다. creator/상태/version을 지금 추가하는 대안은 현재 API가 사용하지 않는 정책/필드를 늘려 후속으로 둔다.

Vue Project 목록·상세를 연결했다. Project 생성 폼·POST도 연결했다. Project 수정 정책도 확정했으며 다음 한 작업은 수정 API·V4·최소 UPDATE 도구 구현과 격리 검증이다. 새 DB는 여전히 V3와 필요한 SELECT/INSERT를 먼저 적용해야 새 앱을 실행할 수 있다.


## 구현 파일과 실행 절차 — 2026-11-05

- `backend/src/main/java/com/flowdesk/project/`: Entity, 조회 전용 Repository, mandatory persist 어댑터, 조회/생성 Service, DTO, Controller/안전한 오류, 개발 읽기 권한 검사.
- `backend/src/main/java/com/flowdesk/common/api/StrictWriteJson.java`: 기존 strict UTF-8/중복 키/후행 JSON 검사를 재사용한다. 각 Controller가 본문 상한을 지정하고 기존 Workspace의 4 KiB는 유지한다.
- `infra/provision-project-create.ps1` → `run-app-project-gradle.ps1` → Gradle `runAppProjectCreateAccess` → `AppProjectCreateAccessRunner`로 별도 관리자 권한을 전달한다. 앱 JAR에 관리자 실행기/정책 SQL/자격증명을 포함하지 않는다.
- `AppReadPolicy`는 PROJECT_CREATE와 과거 네 상태를 구분한다. 새 테이블 SELECT/INSERT가 일부만 있거나 기존 rename 컬럼 권한이 빠진 경우 기존 도구도 거부한다. seed는 projects가 비어 있어야 한다.

아래 순서를 실제 로컬에 사용자 요청으로 실행해 완료했다. 새 환경에서도 앱을 정지한 뒤 기존 로컬 설정을 사용해 같은 순서로 진행한다. 비밀번호를 명령 인수로 전달하지 않는다.

```powershell
./infra/migrate-local.ps1 -Action Info
./infra/migrate-local.ps1 -Action Migrate
./infra/migrate-local.ps1 -Action Validate
./infra/provision-project-create.ps1
# 이후 새 앱 실행: 조회 dev-actor, 생성은 기존 쓰기 설정/허용 Origin도 필요
```

격리 검증 재현 명령:

```powershell
cd backend
./gradlew.bat build verifyWorkspaceProxy --no-daemon --console=plain
cd ..
./infra/verify-migration.ps1 -IncludeProjectCreate
cd frontend
npm test
npm run build
```

검증 결과는 [로컬 작업 기록](local-setup.md)의 Project 구현 항목을 따른다. 격리 구현 단계에서는 로컬 V2를 보존했고, 이후 로컬 적용 요청으로 V3/PROJECT_CREATE와 첫 Project 생성을 별도 확인했다.

검증 완료: Java 회귀/영향 구간 재검증 합산 298개, 실제 Vite 프록시 1개, PowerShell 전체 경로 69개, Vue 회귀 101개와 빌드. 초기 실패와 수정 후 실행 명령/집계 방식은 local-setup.md에 구분해 기록했다. 실제 로컬 전체 행·이력·소유권/ACL·시퀀스는 이전 스냅샷과 동일하고 V2를 유지했다.


## 실제 로컬 적용 결과 — 2026-11-05

Info의 V3 PENDING → Migrate 1건 → Validate 성공, PROJECT_CREATE 최초/반복 적용, Migrate 재실행 0건을 확인했다. 새 JAR에서 OWNER 생성 201/상대 Location → 목록·상세 200, ADMIN/MEMBER/VIEWER 상속 조회, VIEWER 생성 403, 미소속·없는 자원·부모 불일치 404, 외부 Origin 403/필드 위조 400, 쓰기 off GET 200/POST 405와 Actuator UP을 확인했다.

Workspace 1의 Project ID 1 한 행만 추가됐다. 기존 사용자 5명/공간 3개/멤버십 6개·모든 행/버전/기존 시퀀스·V1/V2 이력·이전 ACL/소유권을 보존했다. 프로젝트에도 UPDATE/DELETE/TRUNCATE·sequence/history·DDL 권한이 없다. 검증용 앱 6개를 종료했고 8080/18081 리스너·앱 DB 연결이 남지 않았다. 기존 설정 파일·V1/V2/V3·JAR 7개 SHA-256이 같고 부모 환경도 동일하다. 영구 쓰기 활성화 설정을 바꾸지 않았다. 자세한 명령/수신 결과는 [작업 기록](local-setup.md)에 남겼다.


## Vue 조회 소비자 — 2026-11-05

선택한 공간의 유효한 상세 아래에 ProjectBrowser를 등록하고 기존 목록·상세 GET 계약을 사용한다. 공간 변경은 상태/진행 요청을 종료하고 페이지 이동·새로고침은 Project 상세를 초기화한다. 문자열 ID/부모 일치/페이지와 설명을 런타임 검증하며 설명은 HTML 해석 없이 텍스트로 표시한다. 별도 ProjectMembership·인증/권한 정책·서버 DTO/SQL은 바꾸지 않았다. [화면/요청 흐름](../frontend/README.md), [검증 기록](local-setup.md). Project 생성 폼·POST는 다음 단계다.


## Vue 생성 소비자 — 2026-11-05

선택 공간의 OWNER/ADMIN에 ProjectCreateForm을 제공하고 기존 POST 계약으로 name/description만 전송한다. 정상 201/상대 Location/부모·ID·이름·설명을 확인한 뒤 목록/새 상세 GET을 진행한다. 확정 거부와 결과 불명을 구분하며 GET의 이름 일치로 요청 성공을 단정하거나 자동 재전송하지 않는다. 같은 공간 이름 중복/서버 인가/DB 최소 권한은 그대로다.

프론트 194개·타입 검사/빌드 및 실제 Chrome/Vite/격리 PostgreSQL에서 정상 생성·커밋된 201 유실/GET 확인 중 잠금·미재전송·VIEWER 미노출/조회·405·모바일을 확인했다. 실제 로컬 5/3/6/1은 변경하지 않았다. [화면 흐름](../frontend/README.md), [검증/정리 기록](local-setup.md).


## 후속 수정 계약 확정 — 2026-11-05

[Project 수정 계약](project-update-api-v1.md)을 별도로 확정했다. 이름·설명/OWNER·ADMIN/Project 자체 버전·409이며 V4와 네 컬럼 UPDATE, 상세·생성 DTO version 문자열을 설계한다. 이 문서의 V3 구현 자체에는 아직 버전/수정 기능이 없다. 목록 요약은 기존 계약을 유지하며 코드/DB 전환·수정 폼은 후속 단계다.


## 후속 수정 구현 — 2026-11-05

[수정 계약](project-update-api-v1.md)의 V4/업데이트 API·최소 권한 도구를 구현했다. 상세/생성 DTO는 version 문자열을 추가하며 목록 요약 세 필드는 유지한다. 기존 Vue 상세/생성 파서도 이를 검증·보존한다. 실제 로컬은 V3/PROJECT_CREATE이므로 후속 V4/권한을 적용한 뒤 새 앱을 실행한다. 격리 결과는 local-setup.md에 기록하고 Vue 수정 폼은 별도다.
