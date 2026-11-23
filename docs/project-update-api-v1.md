# Project 이름·설명 수정 계약

- 날짜: 2026-11-05
- 상태: 백엔드/로컬 적용에 이어 Vue 수정 폼·두 필드/문자열 버전·409/결과 불명 복구 연결 완료(2026-11-07). 프론트 246개/빌드와 실제 격리 Chrome/Vite/PostgreSQL 검증 통과. 로컬 Project 1 version 1, 데이터 5/3/6/1은 보존했다. 앞선 Java/도구 검증과 실제 적용 결과는 [local-setup.md](local-setup.md)에 구분한다.
- 기준: [요구사항](requirements.md), [아키텍처](architecture.md), [기술 스택](tech-stack.md), [로드맵](roadmap.md), [Project 생성/조회](project-model-and-api-v1.md), [Workspace 수정 선례](workspace-rename-api-v1.md).
- 완료 기준: 확정한 수정 필드·역할·HTTP/충돌·V4·최소 권한·응답 전환을 구현하고 격리 PostgreSQL에서 검증한다. 실제 로컬 적용과 Vue 편집 폼은 별도다.

## 사용자와 확정한 정책

| 항목 | 선택 |
|---|---|
| 수정 필드 | 이름과 설명. 설명 null/정규화 후 빈 값은 설명 삭제 |
| 역할 | 해당 Workspace의 OWNER·ADMIN만 수정. 다른 공간의 역할은 적용하지 않음 |
| 충돌 | Project 자체의 버전으로 검출하고 오래된 수정은 409로 거부 |
| 충돌 단위 | Project 한 행. 이름과 설명을 각각 수정해도 같은 버전의 동시 요청이면 충돌 |

이름의 Java 공백 제거·1~100 Unicode 코드 포인트·중복 이름 허용과 설명의 개행/공백 정규화·선택 일반 텍스트·최대 2,000 코드 포인트는 생성과 같다. 설명의 내부 LF/TAB은 허용하고 다른 ISO 제어 문자·잘못된 surrogate는 거부한다. HTML/Markdown을 해석하지 않는다.

ProjectMembership는 계속 없고 Workspace membership을 상속한다. 공간 이동/workspaceId 변경, 보관/복구·삭제·Task·실제 인증·새 역할 관리 API는 이번 범위에 넣지 않는다. Project 수정은 부모 Workspace의 이름/버전/updated_at을 변경하지 않는다.

## HTTP 요청

```http
PATCH /api/workspaces/1/projects/1
Content-Type: application/json
Accept: application/json
Origin: http://127.0.0.1:5173

{"name":"  Release Plan  ","description":"  다음 출시 계획\r\n검토 중  ","version":"0"}
```

- 구현 기본값으로 name/description/version **세 필드를 모두 요구**한다. 두 편집 필드의 스냅샷을 한 번에 저장하는 고정 PATCH 계약이며 범용 JSON Merge Patch는 아니다. description 누락은 400, 명시적 null/빈 문자열/공백은 삭제다. name은 필수 문자열이고 null은 허용하지 않는다.
- version은 상세 조회에서 받은 **Project 버전**이다. Workspace 버전을 보내지 않는다. 0~Long.MAX_VALUE의 정규 십진 문자열(0 또는 선행 0 없는 양의 정수)이며 숫자/null/음수/부호/선행 0/범위 초과는 400이다. Entity의 version을 직접 덮어쓰는 값이 아니다.
- 부모와 Project ID는 기존의 정규 양의 십진 문자열/Long 범위를 유지한다. URL 부모를 JSON으로 바꾸거나 Project ID만 조회하지 않는다.
- 알 수 없는 필드/id/workspaceId/userId/ownerId/role/created_at/updated_at/상태, 중복 JSON 키·후행 JSON·잘못된 UTF-8/JSON·쿼리 파라미터는 400이다. 요청 DTO와 Entity는 분리한다.
- 기존 Project POST와 같은 실제 본문 **32 KiB** 제한/413, UTF-8 application/json·Content-Encoding 정책/415, 명시적 dev-actor·loopback·허용 Origin·쓰기 설정을 사용한다. Workspace 쓰기 본문은 기존 4 KiB를 유지한다.
- 기존 `FLOWDESK_DEV_WORKSPACE_WRITE_ENABLED`를 재사용한다. 기본 프로필 업무 경로는 404, dev-actor/쓰기 off는 PATCH 미등록 405다. Origin/직접 peer 검사와 Service 인가를 각각 유지한다. 필터가 이미 `/api/workspaces/` 하위 PATCH를 검사하므로 새 경로도 실제 HTTP/Vite로 검증한다.

설명 삭제 예시:

```http
PATCH /api/workspaces/1/projects/1
Content-Type: application/json
Origin: http://127.0.0.1:5173

{"name":"Release Plan","description":null,"version":"1"}
```

필드 누락을 '그대로 유지'로 해석하지 않아 삭제 의도를 명확하게 한다. 서로 다른 필드의 동시 수정도 한 행의 충돌로 처리한다는 사용자 선택과 함께 적용한다.

## 응답과 버전 전달

```http
HTTP/1.1 200 OK
Content-Type: application/json
Cache-Control: no-store

{"id":"1","workspaceId":"1","name":"Release Plan","description":"다음 출시 계획\n검토 중","version":"1"}
```

```http
HTTP/1.1 409 Conflict
Content-Type: application/problem+json
Cache-Control: no-store

{"type":"about:blank","title":"Conflict","status":409,"detail":"Project has changed. Read it again before modifying.","code":"PROJECT_VERSION_CONFLICT"}
```

- commit 성공 후 200이다. 실제 변경이면 새 version, no-op이면 기존 version을 문자열로 반환한다. 현재 이름/설명/버전을 409 원문에 포함하지 않는다. 정상 DTO와 오류 DTO를 구분한다.
- 상세 GET·생성 POST·수정 PATCH의 `ProjectView`에 version 문자열을 추가한다. 생성 초기 버전은 "0"이다. 경로와 기존 id/workspaceId/name/description은 유지한다.
- **목록 요약 `ProjectList.Item`은 id/workspaceId/name 세 필드를 유지**한다. 편집 시작 버전은 상세 GET에서 읽으며 요약 목록만으로 수정하지 않는다. 설명/사용자/역할/타임스탬프도 목록에 추가하지 않는다.
- Project 버전과 Workspace 버전은 독립적이다. 부모 버전으로 Project 충돌을 검출하거나 Project 수정마다 부모 버전을 증가시키지 않는다.
- 정규화한 name/description 둘 다 현재 값과 같으면 200/no-op을 기본값으로 한다. version/updated_at을 바꾸지 않되 요청 버전 비교와 트랜잭션 종료 시 낙관적 검증은 수행한다. 내용이 같아도 stale version은 409다.

## 인가·자원 확인·오류 우선순위

| 요청 | 결과 |
|---|---|
| 올바른 부모/Project, 해당 공간 OWNER·ADMIN, 현재 버전 | 200 |
| 올바른 부모/Project, MEMBER·VIEWER | 403 / PROJECT_FORBIDDEN |
| 미소속·없는 부모/Project·URL 부모 불일치 | 같은 404 / PROJECT_NOT_FOUND |
| 자격 있는 요청의 stale 버전/flush·commit 낙관적 충돌 | 409 / PROJECT_VERSION_CONFLICT |

순서는 개발 접속/쓰기·Origin → HTTP 구조/입력 → 서버 행위자 → 부모 membership → **부모+Project 조회** → 역할 → 요청 버전 → 변경/DB 버전 검사다. 잘못된 부모/없는 Project가 MEMBER·VIEWER 요청에서도 403으로 바뀌지 않도록 자원을 역할보다 먼저 확인한다. 소속 없는 사용자에게는 Project 존재를 조회/노출하지 않는다. 올바른 자원의 MEMBER·VIEWER는 stale 버전이어도 403이다. 두 공간 모두에 소속된 사용자도 잘못된 부모의 Project는 404다.

기존 INVALID_REQUEST/400, DEV_ACCESS_DENIED/403, REQUEST_TOO_LARGE/413, UNSUPPORTED_MEDIA_TYPE/415, SERVICE_UNAVAILABLE/503, INTERNAL_ERROR/500을 유지한다. 명시적 버전 불일치와 낙관적 잠금 예외만 409로 분류한다. 임의의 제약/SQL 권한 오류를 충돌로 감추지 않는다.

## Service·JPA·트랜잭션

HTTP → 입력 DTO → public `ProjectCommandService.update` 쓰기 트랜잭션 → 서버 행위자/소속 → `findByIdAndWorkspaceId` → OWNER/ADMIN → 요청 version 비교 → 관리 상태 Entity 변경 → mandatory 쓰기 어댑터의 OPTIMISTIC 검증/flush → Spring 프록시 commit → DTO/200.

- 조회 Service는 readOnly, 수정은 readOnly=false/READ_COMMITTED/예외 롤백이다. 기존 생성의 저장 어댑터와 조회 Repository의 범위는 유지하고 범용 save/delete/merge를 추가하지 않는다.
- Project의 Long `@Version`은 JPA가 관리한다. 클라이언트 값으로 Entity version을 설정하거나 detached Entity를 merge해 소속 검사를 대신하지 않는다.
- 최초 요청 버전 비교만으로 충분하지 않다. 실제 UPDATE도 id/version 조건으로 충돌을 검출해야 한다. 예를 들어 둘 다 version 0을 읽었다면 하나만 커밋하고 나머지는 409다. 다른 필드끼리도 자동 병합하지 않는다.
- name/description 매핑을 수정 가능하게 바꾸고 updated_at은 실제 값 변경 시 서버 시각으로 갱신한다. created_at/id/workspace_id는 UPDATE 대상에서 제외한다. DB DEFAULT만으로 updated_at이 갱신된다고 가정하지 않는다.
- no-op에도 기존 Workspace 선례처럼 OPTIMISTIC 검증을 적용한다. 동시 변경과 경합하면 commit 단계에서도 409가 될 수 있다. 응답 뒤의 미래 변경까지 막는 보장은 아니다.
- flush로 새 version을 얻어 DTO를 만들고, 트랜잭션 프록시가 commit한 다음 Controller가 응답한다. commit 단계의 낙관적 충돌도 409로 변환하고 저장/commit 실패는 행 변경을 남기지 않는다.
- 인가는 이 트랜잭션에서 읽은 membership 기준이다. 향후 역할 변경/탈퇴·공간 이동 API를 도입하면 인가/부모 변경 경합 정책을 별도로 정한다. 현재 없는 기능의 잠금 체계는 미리 추가하지 않는다.

## V4·최소 권한·실행기 전환 설계

- 마이그레이션 파일: `backend/src/main/resources/db/migration/V4__add_project_version.sql`. projects.version bigint NOT NULL DEFAULT 0, CHECK version >= 0만 추가한다. 기존 Project/부모/설명/시간/시퀀스·V1/V2/V3 파일과 설치 이력은 보존한다. 현재 로컬 Project ID 1의 초기 version도 0이 된다.
- `PROJECT_UPDATE` 상태는 기존 PROJECT_CREATE 전체 권한에 **projects UPDATE(name, description, updated_at, version)** 네 컬럼만 추가한다. 기존 Workspace 세 컬럼 UPDATE는 그대로 유지한다. Project 테이블 전체 UPDATE/id/workspace_id/created_at·users/memberships UPDATE·DELETE/TRUNCATE·sequence/history·DDL은 금지한다.
- 별도 관리자 `infra/provision-project-update.ps1` → 전용 Gradle `runAppProjectUpdateAccess`/main/정책 SQL을 구현했다. 정확한 PROJECT_CREATE → PROJECT_UPDATE만 전환하고 반복은 보존한다. 부분/과도·PUBLIC·GRANT OPTION·예상 밖 컬럼 권한을 자동 보충/철회하지 않는다. 자격증명/앱 JAR 분리를 유지한다.
- `AppReadPolicy`는 승인된 네 컬럼의 정확한 집합과 새 상태를 검사한다. 과거 상태는 migration/provisioning 경로에서 계속 인정하고 기존 도구도 완전한 PROJECT_UPDATE를 보존한다. 새 권한의 일부 상태는 모든 관련 도구가 거부한다.
- `DevWorkspaceWriteSettings`는 기존 쓰기 flag를 켤 때 네 컬럼 UPDATE를 포함한 새 정책을 확인한다. 조회 모드는 필요한 SELECT로 동작하고 앱이 권한을 부여하지 않는다.
- `MigrationHistory`와 권한/seed의 현재 이력 기대값은 1/2/3/4로 전환한다. 공통 격리 fixture와 latest 가정도 갱신하되 이전 버전의 독립 마이그레이션 테스트는 명시적 target을 유지한다. 미래 이력/누락 버전·변조 체크섬 검사를 약화하지 않는다.
- 운영 seed에 Project를 넣지 않는다. 기존 예제 일치 + projects 비어 있음 조건을 유지하며 업무 Project가 있으면 Apply/Verify는 mismatch로 종료한다. 실제 로컬 seed는 초기화/수정하지 않는다.
- 앱 부팅의 Flyway 비활성/ddl-auto=validate/OSIV=false는 유지한다. 새 @Version 매핑은 V4를 요구한다. 실제 순서는 앱 중지 → 전용 migrator V4 → 별도 Project UPDATE 권한 도구 → 새 앱이다. 구현/격리 검증 후 실제 로컬 적용은 별도 요청으로 실행한다.

## Vue 계약 전환·충돌 복구 설계

백엔드 응답 전환 시 기존 `Project`/상세·생성 파서가 version 문자열을 검증·보존하도록 함께 갱신한다. 생성의 201 확인은 version "0"까지 검사한다. `ProjectSummary`와 목록 페이지 계약은 유지한다. 내부 version을 사용자가 직접 입력하지 않으며 수정 폼 연결은 별도 단계다.

후속 수정 폼은 편집 시작 name/description/version과 사용자 draft를 분리한다. 성공한 200의 ID/부모·정규화 필드·version을 검증해 상세/같은 ID의 목록 요약만 갱신한다. 실제 변경은 시작 version+1, no-op은 같은 version이다. 정수 증가는 BigInt 비교에만 쓰고 HTTP에는 문자열을 유지한다.

409는 draft/편집 시작 버전을 유지한 채 저장을 잠근다. 최신 상세 GET → 서버 name/description과 draft 비교 → 사용자가 명시적으로 편집 기준 갱신 → 별도 수동 저장 순서다. GET만으로 버전/입력을 교체하거나 자동 병합/재전송하지 않는다. 서로 다른 필드의 수정도 비교 후 사용자가 결정한다. 조회 실패나 권한 상실이면 잠금을 유지한다.

네트워크/시간 초과·5xx·잘못된 200도 결과 불명으로 처리한다. GET에서 내용이 같아도 특정 PATCH의 성공 영수증으로 단정하지 않는다. 버전 검사는 멱등키/정확히 한 번 실행 보장이 아니며 요청 취소도 커밋을 롤백하지 않는다. 공간 전환/언마운트는 이전 요청/늦은 이벤트를 종료한다.

## 구현 후 격리 수용 기준

1. 데이터/권한이 있는 V3에서 V4 적용 1건/Validate/반복 0건, 기존 전체 행/부모·시간/버전/시퀀스·V1~V3 설치 행/체크섬/이전 ACL 보존과 초기 Project version 0을 확인한다. V4 미적용 시 JPA validate가 자동 복구 없이 실패해야 한다.
2. OWNER/ADMIN의 이름만·설명만·둘 다 수정/설명 삭제 200, MEMBER/VIEWER 403, 미소속/미존재/부모 불일치 동일 404를 확인한다. 두 공간 소속 사용자와 권한 없는 사용자의 잘못된 Project도 포함한다.
3. 같은 version의 실제 병렬 트랜잭션은 하나만 커밋/다른 하나 409다. 서로 다른 필드 수정도 충돌하며 승자의 name/description/version/updated_at만 남아야 한다. 부모 Workspace는 변하지 않는다.
4. 순차 stale 409·최신 버전 수동 수정, 정규화된 no-op의 버전/시각 보존·동시 낙관적 검증, 404/403/409 우선순위를 검증한다.
5. 누락 description/잘못된 version·Unicode/제어 문자·32 KiB(실제/chunked)/strict JSON·Origin·기본/쓰기 off·클라이언트 주체/부모 위조 거부, flush/지연 commit 실패 롤백/안전한 오류를 확인한다.
6. 실제 앱의 허용 네 컬럼 성공/금지 컬럼·테이블 전체 UPDATE·sequence/history/DDL 거부와 부분/과도 ACL/잘못된 자격증명 거부, 기존 도구의 새 상태 보존을 확인한다.
7. 상세/생성의 version 문자열과 초기 0, 목록 요약 유지, 기존 조회/생성/Workspace 수정·충돌/Vite PATCH Origin/프론트 테스트·타입 검사/빌드 회귀를 실행한다. 수정 폼의 409/결과 불명·명시적 복구는 후속 Vue 연결 단계에서 검증한다.
8. 실제 로컬 V3/PROJECT_CREATE·5/3/6/1·공간 3 version 1·자격증명/SQL을 보존하고, 모든 쓰기는 고유 격리 DB에서 검증한 뒤 자기 자원을 정리한다. 실제 로컬 V4/GRANT/수정은 별도 단계다.

## 대안과 다음 한 작업

마지막 저장 덮어쓰기는 앞선 변경을 잃을 수 있어 사용자가 선택하지 않았다. 필드별 자동 병합은 설명/이름 간 업무 의미를 추가로 정해야 하므로 현재 한 행의 충돌 검출로 제한한다. JSON version은 기존 Workspace와 같은 흐름으로 학습/오류 처리를 재사용할 수 있다. ETag/If-Match를 혼용하지 않고, 단순 비관적 잠금만으로 요청 전의 오래된 편집 내용을 감지한다고 가정하지 않는다.

다음 한 작업은 **Task 데이터 모델·생성/조회 권한 범위 확정**이다. Project 수정의 백엔드/실제 로컬 적용/Vue 연결은 완료했으며 보관·삭제·이동 정책을 임의로 추가하지 않는다.


## 구현 파일과 재현 명령 — 2026-11-05

- `ProjectUpdateController`: 부모/ID·필수 세 필드·32 KiB strict JSON/정규화 → 수정 Service. 기존 쓰기 필터의 nested PATCH 경계를 재사용한다.
- `ProjectCommandService.update`: 부모 membership → 부모+Project → 역할 → 버전 비교. `ProjectUpdateRepository`는 mandatory 트랜잭션/OPTIMISTIC lock/도메인 update/flush를 담당한다. ProjectView는 version 문자열을 포함하고 목록 요약은 유지한다.
- `Project`/`V4__add_project_version.sql`: Long @Version, 수정 가능한 이름/설명/시간, 기존 행의 version 0/비음수 CHECK.
- `AppProjectUpdateAccessRunner`/`runAppProjectUpdateAccess`/`infra/provision-project-update.ps1`: 관리자 작업으로 정확한 PROJECT_CREATE→PROJECT_UPDATE 전환. 과거 도구도 새 상태를 보존하고 현재 이력은 1/2/3/4를 요구한다.
- `DevWorkspaceWriteSettings`는 Project 네 UPDATE 컬럼을 기동 시 검사한다. Vue의 Project 타입/상세·생성 파서는 version 문자열을 검증·보존하고 생성 "0"을 확인한다.
- `ProjectUpdateForm`/`updateProject`는 두 입력과 편집 기준을 분리한다. 성공 DTO로 상세/현재 목록 행을 갱신하고, 409/결과 불명 뒤 Project·Workspace 역할 GET/두 필드 비교/명시적 기준 갱신/별도 수동 저장을 적용한다. 최신 조회 실패·권한 상실·화면 이탈과 늦은 이벤트도 검사한다.

```powershell
# backend
./gradlew.bat build verifyWorkspaceProxy --no-daemon --console=plain
# root: unique isolated PostgreSQL, including all older tool paths
./infra/verify-migration.ps1 -IncludeProjectUpdate
# frontend
npm test
npm run build
```

실제 로컬은 이번에 변경하지 않는다. 후속 적용 시 앱을 중지하고 아래 순서를 사용한다.

```powershell
./infra/migrate-local.ps1 -Action Info
./infra/migrate-local.ps1 -Action Migrate
./infra/migrate-local.ps1 -Action Validate
./infra/provision-project-update.ps1
# 새 앱 기동: V4/@Version, dev-actor, 쓰기는 기존 명시적 flag/Origin 필요
```

검증 결과·최초 실패/수정·격리/로컬 보존은 [작업 기록](local-setup.md)에 기록한다. 새 앱은 실제 로컬 V3 상태에서 기동하면 JPA validate에 실패할 수 있으므로 V4/필요 권한 적용 후 재기동한다.
