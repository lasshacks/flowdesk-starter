# 워크스페이스 이름 수정 계약

- 날짜: 2026-11-04
- 상태: API/DB 구현·격리 검증·실제 로컬 적용 완료. 2026-11-05 Vue 수정 폼·버전 보존·충돌 복구 연결/격리 브라우저 검증도 완료.
- 기준: [요구사항](requirements.md), [아키텍처](architecture.md), [생성 계약](workspace-create-api-v1.md), [로드맵](roadmap.md).
- 완료 기준: 수정 자격, HTTP/충돌 처리, V2 및 최소 권한 범위와 검증 시나리오를 확정한다. 실제 로그인·Vue 수정 화면·역할 변경·삭제는 제외한다.

## 확정한 업무 정책

사용자 선택으로 해당 공간의 OWNER·ADMIN만 이름을 수정할 수 있다. MEMBER·VIEWER는 403, 미소속/미존재는 동일 404다. 다른 공간의 OWNER라는 이유로 수정할 수 없다. 기존 생성 자격은 유지한다.

사용자 선택으로 버전 기반 낙관적 충돌 검출을 사용한다. 오래된 화면에서 보낸 변경은 409로 거부하고 자동 덮어쓰기/재시도를 하지 않는다. 현재 상세를 다시 읽고 사용자가 내용을 확인해 새 요청을 만든다.

이름의 공백 제거·Unicode 코드 포인트 1~100·제어 문자/잘못된 surrogate 거부·중복 이름 허용은 생성과 같다. 구현 기본값으로 같은 정규화 이름은 200/no-op, version/updated_at 유지로 정한다. no-op도 권한·요청 버전 검사와 트랜잭션 종료 시 낙관적 검증을 생략하지 않는다.

## HTTP 계약

```http
PATCH /api/workspaces/3
Content-Type: application/json
Accept: application/json
Origin: http://127.0.0.1:5173

{"name":"  Platform Team  ","version":"0"}
```

- name/version만 받는다. version은 조회한 버전이며 Entity version을 직접 설정하는 값이 아니다.
- version은 0~Long.MAX_VALUE의 정규 십진 문자열이다. 0 또는 선행 0 없는 양의 정수만 허용한다. JavaScript 정수 정밀도 손실을 피하려고 ID와 같이 문자열로 전달한다.
- 누락/null/잘못된 타입·음수/범위 초과/선행 0·알 수 없는 필드/중복 키·잘못된 JSON/쿼리 파라미터는 400이다. Entity에 직접 바인딩하지 않는다.
- 기존 UTF-8 JSON 정책(실제 본문 4 KiB/413, Content-Type·encoding/415), 명시적 개발 쓰기·Origin·loopback 경계를 PATCH에도 적용한다. POST만 검사하는 현재 필터는 확장해야 한다.
- 기본 프로필은 업무 경로 미등록 404, dev-actor에서 쓰기 비활성은 PATCH 미등록 405다.

```http
HTTP/1.1 200 OK
Content-Type: application/json
Cache-Control: no-store

{"id":"3","name":"Platform Team","myRole":"OWNER","version":"1"}
```

- 커밋 성공 후 200으로 실제 변경의 증가한 버전을 반환한다. no-op은 기존 버전을 반환한다.
- 목록·상세 GET 및 생성 POST의 WorkspaceView에도 version 문자열을 추가한다. 경로·기존 필드·페이지 구조는 유지하며 생성 시 초기 버전은 "0"이다.
- 2026-11-05 Vue 조회/생성/수정 API client에 version 문자열의 검증·보존을 구현했다. 수정 폼은 상세에서 읽은 편집 시작 버전을 보존하고 409 뒤 GET·명시적 기준 갱신·수동 저장 순서로 복구한다. 구체적 후속 UI 흐름은 아래 절을 따른다.
- 요청/DB version 불일치 및 flush/commit의 낙관적 충돌은 409 / WORKSPACE_VERSION_CONFLICT다. 현재 이름/버전이나 SQL 원문을 오류에 포함하지 않는다.
- 소속 → 역할 → 버전 순서로 검사한다. 미소속에 409로 존재를 노출하지 않는다. MEMBER·VIEWER는 오래된 버전이어도 403 / WORKSPACE_FORBIDDEN이다. 개발 접속/출처 실패의 DEV_ACCESS_DENIED와 구분한다.
- 나머지 400/503/500은 기존 안전한 ProblemDetail 정책이다. 모든 무결성 오류를 409로 처리하지 않는다.
- 응답 유실/시간초과·커밋 결과 불명은 자동 PATCH 재전송하지 않고 GET으로 확인한다. 이름이 같다는 사실만으로 특정 요청의 성공을 증명하지 못한다. 버전 검사는 멱등키나 정확히 한 번 처리를 뜻하지 않는다.

## Service와 영속성 경계

HTTP → 개발 접속/쓰기/Origin 검사 → Controller 입력 검사 → 쓰기 Service 트랜잭션 → 서버 행위자 → membership/역할 검사 → 요청 version 비교 → 관리 상태 Workspace 변경 → flush/버전 검증 → 커밋 → DTO/200.

- public 쓰기 메서드에 readOnly=false/READ_COMMITTED/예외 롤백을 명시한다. 기존 조회 Service의 readOnly는 유지한다.
- Workspace에 Long @Version을 추가하고 JPA가 버전을 관리한다. 요청 version으로 Entity version을 설정하거나 detached merge로 인가를 대체하지 않는다.
- 요청 버전 비교와 UPDATE 시점 DB 버전 조건이 모두 필요하다. 그 사이 다른 트랜잭션이 커밋할 수 있어 SELECT 후 무조건 UPDATE는 허용하지 않는다.
- 실제 변경은 name·updated_at을 서버가 변경하고 JPA가 version을 증가시킨다. created_at·id는 UPDATE에서 제외한다. V1 DEFAULT만으로 updated_at이 갱신되지 않으므로 수정 시각을 반영하도록 매핑을 바꾼다.
- no-op에도 OPTIMISTIC 검증 등으로 트랜잭션 종료 시 읽은 버전을 검사하고 동시 변경은 409로 처리한다. 응답 후 발생하는 다른 요청까지 막는 보장은 없다.
- flush 후 DTO에 증가한 version을 담는다. 트랜잭션 프록시의 commit 실패도 예외 처리에서 409로 변환해야 한다. 낙관적 충돌 예외만 분류하며 임의 무결성/권한 오류는 숨기지 않는다.
- 인가는 이 트랜잭션에서 조회한 membership 기준이다. 역할 변경/탈퇴 기능을 추가할 때 인가 변경과 수정의 경합 정책을 별도로 정한다. 해당 API는 아직 없다.

## V2와 DB 권한 도구 범위

- V2__add_workspace_version.sql에서 workspaces.version bigint NOT NULL DEFAULT 0 및 비음수 CHECK를 추가한다. 기존 행/ID/이름/멤버십을 유지하고 V1/checksum을 변경하지 않는다.
- 앱 부팅은 계속 Flyway 비활성/ddl-auto=validate다. 새 앱은 V1 DB에서 검증 실패할 수 있다. 적용 순서는 서버 중지 → 전용 migrator V2 → 별도 최소 권한 적용 → 새 앱이다. 실제 로컬 실행은 구현·격리 검증 후 별도 단계다.
- 권한 설계는 기존 SELECT/두 INSERT에 workspaces UPDATE(name, updated_at, version)만 추가한다. 테이블 전체 UPDATE·id/created_at·users/memberships UPDATE·DELETE·DDL·sequence/history 접근은 허용하지 않는다.
- 컬럼 ACL도 정확한 집합으로 검사한다. table UPDATE만 확인하는 예외로 검사 정책을 완화하지 않는다. 전용 관리자 도구만 WORKSPACE_CREATE에서 WORKSPACE_RENAME으로 전환한다. 부분/과도한 권한은 자동 보정하지 않고 중단한다.
- migration/seed/read/create 도구는 승인된 새 상태를 보존한다. V2 이후 latest-version 가정/응답 회귀도 함께 갱신한다. 실제 GRANT는 이번에 실행하지 않았다.
- 기존 개발 쓰기 설정을 재사용한다. 새 구현에서 쓰기 활성화는 생성+수정이므로 정확한 수정 권한도 확인한다. 조회만 실행하면 승인된 생성 전용 상태를 계속 인정한다. 앱이 스스로 GRANT하지 않는다.

## 구현 후 격리 수용 기준

1. V1 데이터에서 V2 적용 후 행 보존/초기 version 0, Validate/재실행, V1 checksum 보존을 검증한다.
2. OWNER·ADMIN 200, MEMBER·VIEWER 403, 미소속/미존재 동일 404, 위조 사용자/역할 입력 거부를 검증한다.
3. 같은 version으로 서로 다른 이름을 바꾸는 병렬 요청은 하나만 커밋/다른 하나는 409다. 실제 별도 트랜잭션과 이름/version/updated_at을 확인한다.
4. 순차 stale 요청 409, 최신 버전의 명시적 요청 성공, no-op/낙관적 검증과 오류 우선순위를 검증한다.
5. 입력/4 KiB/Origin·쓰기 비활성, flush/commit 실패 롤백과 안전한 오류를 검증한다.
6. 실제 앱 계정으로 허용 컬럼 성공/금지 변경 거부, 부분·과도 컬럼 ACL 거부와 기존 도구 재실행을 검증한다.
7. GET/POST version·생성 원자성/읽기 인가·Vue 테스트/타입 검사/빌드 회귀를 검증한다. 구현 전 통과했다고 기록하지 않는다.
8. 실제 로컬 5/3/6·자격증명/V1을 보존하고 격리 환경을 정리한다. 실제 로컬 V2·GRANT·수정은 별도 단계다.

## 대안과 근거

마지막 수정 덮어쓰기는 단순하지만 앞선 변경을 잃을 수 있어 사용자가 선택하지 않았다. 비관적 잠금만으로는 요청 전에 이미 오래된 화면을 식별하지 못한다. ETag/If-Match 대신 JSON version으로 기존 DTO/오류 흐름을 유지한다.

- [Jakarta Persistence 3.1 낙관적 잠금·버전](https://jakarta.ee/specifications/persistence/3.1/jakarta-persistence-spec-3.1.html): 버전은 provider가 관리하며 충돌 트랜잭션은 롤백한다.
- [PostgreSQL 17 컬럼 GRANT](https://www.postgresql.org/docs/17/sql-grant.html): 특정 컬럼 UPDATE 권한을 사용할 수 있다.


## 구현 파일과 실행 방법 — 2026-11-04

- `WorkspaceRenameController`/`WorkspaceWriteJson`: PATCH DTO와 POST 공통 bounded strict UTF-8 JSON.
- `WorkspaceCommandService.rename`: 서버 행위자·소속·OWNER/ADMIN·요청 버전 검사.
- `WorkspaceRenameRepository`: 관리 Entity에 OPTIMISTIC 검증·도메인 rename·flush. no-op도 commit 시 검증한다.
- `Workspace.version`/`WorkspaceView.version`: JPA 관리 버전과 GET/POST/PATCH 문자열 DTO. updated_at은 수정 때 서버 시각으로 갱신한다.
- `V2__add_workspace_version.sql`: 버전 컬럼·DEFAULT·CHECK. V1은 유지한다.
- `AppReadPolicy`/`MigrationHistory`: 정확한 WORKSPACE_RENAME 컬럼 ACL과 설치 이력 1,2를 검사한다. seed/read/create는 새 권한 상태를 보존한다.
- `AppWorkspaceRenameAccessRunner`/`runAppWorkspaceRenameAccess`/`infra/provision-workspace-rename.ps1`: 별도 관리자 권한 전환. 앱 부팅 경로에 포함하지 않는다.

검증 명령(backend에서):

```powershell
.\gradlew.bat test --no-daemon --console=plain
.\gradlew.bat build verifyWorkspaceProxy --no-daemon --console=plain
```

루트에서 실제 실행 경로 검증:

```powershell
.\infra\verify-migration.ps1 -IncludeWorkspaceRename
```

프론트 회귀는 frontend의 `npm test`·`npm run build`다. 최종 결과와 로컬 보존 확인은 [실행 기록](local-setup.md)에 기록한다. 후속 실제 로컬 V2/권한/이름 수정 결과는 아래 실제 적용 절에 기록했다.


최종 격리 결과: Java 245개(수정 HTTP 34/수정 권한 10/V2 1/누락 version 스키마 검증 포함)·실제 Vite POST/PATCH 1개·PowerShell 전체 경로 60개·프론트 58개/타입 검사/빌드 통과. 실제 로컬 V1/5·3·6/UPDATE 없음·자격증명/V1을 보존하고 검증 자원을 정리했다. 최초 실패와 최종 명령은 local-setup.md에 구분해 기록했다.


## 실제 로컬 적용·수정 확인 — 2026-11-04

사용자 요청으로 V2 1건/Validate·마이그레이션 재실행 0건, WORKSPACE_RENAME 최초/반복 적용을 완료했다. 기존 앱/연결이 없는 상태에서 별도 작업으로 적용하고 새 JAR를 임시 18081에 기동했다. 현재 실제 로컬은 V2/5·3·6이며 ID 3의 이름은 FlowDesk Local Rename Check 77d6875a385e, version은 "1"이다.

OWNER 수정 200, 목록/상세 200, stale 409, no-op 시각·버전 유지, ADMIN no-op 200, VIEWER 403, 미소속/미존재 동일 404, 입력 400/Origin 403, 쓰기 비활성 PATCH 405와 health UP을 확인했다. seed/멤버십/시퀀스/V1/기존 relation ACL/소유권과 자격증명은 유지했다. 검증 앱은 종료했고 IDE/전역 쓰기 설정은 변경하지 않았다. 실행 명령·영수증/보존 검사는 [local-setup.md](local-setup.md)에 기록했다. Vue 수정 폼은 후속이다.


## Vue 이름 수정 연결 — 2026-11-05

Workspace.version을 검증·보존하고 OWNER/ADMIN의 상세에 수정 폼을 연결했다. 편집 시작 버전으로 name/version만 PATCH하며 성공한 200 DTO로 상세와 같은 ID의 목록 행을 갱신한다. 409는 입력·시작 버전과 저장 잠금을 유지하고 최신 GET → 현재 이름 확인 → 명시적 편집 기준 갱신 → 수동 저장으로 복구한다. GET만으로 version을 바꾸거나 자동 재전송하지 않는다. 결과 불명도 같은 확인 절차를 사용하며 역할 상실/조회 실패면 저장 잠금을 유지한다.

프론트 101개·타입 검사/빌드, 실제 Chrome/Vite/격리 DB의 정상 수정·중복 차단·선행 외부 수정 409·명시적 복구·커밋 후 200 유실/GET 확인/미재전송·VIEWER 미노출·405·모바일 검증이 통과했다. 실제 로컬 데이터/권한은 유지했고 백엔드/스키마는 변경하지 않았다. 상태/실행 안내는 [프론트 README](../frontend/README.md), 실제 명령은 [실행 기록](local-setup.md)을 따른다.
