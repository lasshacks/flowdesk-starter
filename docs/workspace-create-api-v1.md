# 워크스페이스 생성 API의 권한·트랜잭션 범위

- 날짜: 2026-11-03
- 상태: 구현·격리 검증, 실제 로컬 최소 권한/첫 생성 완료. Vue 생성 폼·POST 연결도 2026-11-04 구현·검증 완료.
- 목표/완료 기준: 생성 주체·이름 정책·개발 쓰기 경계, HTTP 계약, 원자적 저장, 최소 DB 권한과 격리 검증 기준을 정한다.
- 기준: [요구사항](requirements.md), [V1 모델](domain-model-v1.md), [조회 API](workspace-read-api-v1.md), [JPA/권한 정책](jpa-read-model-v1.md).

## 사용자와 확정한 정책

| 항목 | 선택 |
|---|---|
| 생성 주체 | 서버가 식별한 기존 사용자 모두. 기존 공간의 OWNER/ADMIN/MEMBER/VIEWER 역할과 무관 |
| 생성자 역할 | 새 공간의 OWNER. 서버가 생성자 자신의 membership을 만든다 |
| 이름 | 앞뒤 공백 제거 후 1~100자, 동일 사용자의 동일 이름도 허용 |
| 개발 쓰기 경계 | 별도 쓰기 설정을 명시적으로 켜고 JSON·허용 로컬 Origin 검사를 통과해야 함 |
| DB 권한 | 공간/멤버십 생성에 필요한 INSERT만 별도 도구로 부여. 실제 로컬 적용은 구현·격리 검증 후 별도 진행 |

Dave가 A의 VIEWER여도 새 C를 만들면 C의 OWNER다. A의 권한은 변하지 않는다. 공간 역할을 전역 사용자 등급으로 해석하지 않는다. 실제 인증/로그인은 계속 Phase 2이며 개발 행위자는 인증한 사용자가 아니다.

## 포함 범위와 대안

- 구현할 범위: POST /api/workspaces, 입력/응답 DTO, 생성 Service·도메인 팩토리·쓰기 전용 영속 어댑터, 개발 쓰기/출처 검사, 최소 INSERT 프로비저닝과 기존 권한 검사 확장, 격리 DB/HTTP 회귀 검증.
- 제외: Vue 생성 폼·버튼, 로그인/JWT/MFA, 초대/타인 membership 생성, 이름 수정/삭제/탈퇴/소유권 이전, 사용자 생성, 이벤트/Outbox, 자동 재시도/멱등키 저장소, 실제 로컬 GRANT·생성 실행.
- 모든 기존 사용자에게 허용하는 선택은 첫 공간이 없는 사용자도 시작할 수 있게 한다. 기존 OWNER/ADMIN만 허용하는 대안은 공간별 역할을 전역 생성 자격으로 사용하고 첫 공간 생성 예외도 필요하다.
- 이름 중복 금지 대안은 사용자별 유일성·소유권 이전 후 의미·동시 생성의 DB 제약을 추가로 정해야 한다. 이번 선택은 표시 이름과 ID를 구분하고 V1을 유지한다.

## HTTP 계약

```http
POST /api/workspaces
Content-Type: application/json
Accept: application/json
Origin: http://127.0.0.1:5173

{"name":"  Design Team  "}
```

- JSON 객체의 name 한 필드만 받는다. 누락/null/문자열 이외 값·알 수 없는 필드·중복 JSON 키·잘못된 JSON은 400이다. 쿼리 파라미터도 받지 않는다.
- id/userId/ownerId/role/memberships/timestamps를 입력으로 받거나 무시하고 통과시키지 않는다. 생성자와 OWNER 역할을 클라이언트가 고를 수 없다.
- 앞뒤 공백은 Character.isWhitespace 또는 Character.isSpaceChar 기준으로 제거하고 내부 공백/대소문자는 보존한다. 제거 후 비어 있으면 400이다. 길이는 Unicode 코드 포인트 1~100으로 검사해 PostgreSQL varchar(100)과 맞춘다. 이름에 NUL/제어문자는 허용하지 않는다. 별도 Unicode 정규화나 이름 유일성은 추가하지 않는다.
- application/json만 허용하며 form/text 등은 415다. 본문은 실제 읽는 바이트 기준 최대 4 KiB로 제한하고 초과는 413이다. Content-Length만 믿지 않는다.

```http
HTTP/1.1 201 Created
Location: /api/workspaces/301
Content-Type: application/json
Cache-Control: no-store

{"id":"301","name":"Design Team","myRole":"OWNER"}
```

- 301은 형식 예시다. 실제 생성 ID를 문자열로 반환하며 Location도 그 ID로 만든 상대 경로다. Host/전달 헤더로 절대 URL을 구성하지 않는다.
- 응답은 기존 WorkspaceView 필드와 같다. 회원/비밀번호/Entity 내부 필드와 전체 membership 목록은 반환하지 않는다.
- 동일 이름의 독립 요청은 각각 새 공간을 만든다. Idempotency-Key 지원은 이번 범위가 아니며 서버/클라이언트는 POST를 자동 재시도하지 않는다.
- 응답 유실/클라이언트 취소/커밋 확인 중 연결 장애에서는 생성 여부를 단정하지 않는다. 목록을 다시 확인하도록 하고 무조건 재전송하지 않는다. DB 트랜잭션만으로 요청 중복이나 응답 전달까지 원자적으로 보장할 수 없다.

| 상황 | 응답 |
|---|---|
| 잘못된 입력 | 400 / INVALID_REQUEST |
| 개발 접속/출처 검사 실패 | 403 / DEV_ACCESS_DENIED |
| 본문 제한 초과 / 미지원 Content-Type | 413 / REQUEST_TOO_LARGE, 415 / UNSUPPORTED_MEDIA_TYPE |
| 기본 프로필 / 개발 프로필이지만 쓰기 설정 꺼짐 | 업무 경로 미등록 404 / POST 미등록 405(조회는 유지) |
| 요청 중 설정 사용자 사라짐 / DB 연결·일시적 트랜잭션 장애 | 503 / SERVICE_UNAVAILABLE |
| 예상하지 못한 무결성/권한/서버 오류 | 안전한 500 / INTERNAL_ERROR |

업무 오류는 기존 ProblemDetail+code를 사용한다. 이름 중복은 409가 아니며, 모든 DB 제약 오류를 일률적으로 400/409로 바꾸지 않는다. 검증된 실패에서는 변경이 롤백되지만 커밋 결과가 불명확한 네트워크 장애까지 미생성이라고 안내하지 않는다. SQL/제약명/자격증명/입력 원문을 응답에 포함하지 않는다.

## 개발 쓰기와 브라우저 출처 경계

1. 기존 dev-actor 프로필·서버 지정 사용자·127.0.0.1 bind·직접 loopback peer·전달 헤더 비활성 조건을 유지한다.
2. FLOWDESK_DEV_WORKSPACE_WRITE_ENABLED=true일 때만 생성 Controller/Service를 등록한다. 기본값은 false다. 값만 주고 dev-actor 프로필을 켜지 않으면 업무 API는 등록되지 않는다. 조회 Service의 readOnly는 유지한다.
3. 쓰기를 켜면 FLOWDESK_DEV_WRITE_ALLOWED_ORIGINS도 명시해야 한다. 예시는 http://127.0.0.1:5173,http://127.0.0.1:8080이며 서버 설정의 정확한 origin 집합만 허용한다. 주소는 http/127.0.0.1/명시 포트로 제한하고 경로/쿼리/fragment/userinfo/와일드카드/null은 금지한다. 다른 포트는 명시적으로 추가해야 한다.
4. POST의 Origin은 필수이고 정확히 한 개여야 한다. 누락/null/외부 출처/접미 도메인·다른 포트·복수 값은 403이다. Referer나 Host/X-Forwarded-*로 허용 출처를 추정하거나 대체하지 않는다. CLI도 명시적으로 허용 Origin을 보내야 한다.
5. Sec-Fetch-Site가 cross-site이면 허용 Origin 문자열이 있어도 거부한다. Fetch Metadata가 없는 클라이언트도 Origin 검사는 생략하지 않는다. 인증용 쿠키/토큰과 새 CORS 허용은 추가하지 않는다. JSON이 아닌 쓰기도 차단한다. GET/HEAD로 생성하지 않는다.
6. 쓰기 활성화 시 필요한 실제 앱 권한을 확인하고 READ_ONLY 상태라면 시작을 실패시킨다. 출처/쓰기 설정 누락도 시작 실패다. 권한은 앱이 자동 GRANT하지 않는다. 전체 ACL의 정확한 검사는 전용 프로비저닝/공통 정책 도구가 담당한다.

이는 개발 중 외부 웹 출처의 쓰기를 제한하는 경계이며 실제 인증이나 운영용 CSRF 설계를 대신하지 않는다. 같은 컴퓨터의 CLI는 Origin을 지정할 수 있고 모든 허용 요청은 서버 지정 행위자를 공유한다. Phase 2에서 인증 저장 방식과 함께 Security/CSRF 정책을 다시 정하며, 전역 CSRF 비활성화를 이번에 도입하지 않는다.

근거: [OWASP 출처 검사](https://cheatsheetseries.owasp.org/cheatsheets/Cross-Site_Request_Forgery_Prevention_Cheat_Sheet.html#using-standard-headers-to-verify-origin).

## Service 트랜잭션

HTTP → 개발 쓰기/출처 검사 → Controller의 JSON/입력 검사 → 생성 Application Service 프록시의 트랜잭션 → 기존 행위자 조회 → Workspace 저장 → 생성자 OWNER membership 저장 → flush/DTO 생성 → 커밋 성공 → Controller의 201/Location → 응답.

- 별도 WorkspaceCommandService의 public 생성 메서드에 readOnly=false, REQUIRED, READ_COMMITTED와 rollbackFor=Exception.class를 명시한다. Controller가 프록시를 통해 호출하며 readOnly 조회 Service 내부 호출로 쓰기 트랜잭션을 시작하지 않는다.
- 같은 EntityManager/DB 트랜잭션에서 새 Workspace와 OWNER membership을 저장한다. 두 저장을 REQUIRES_NEW로 분리하거나 중간에 커밋하지 않는다.
- 행위자는 기존 DevActorProvider로 요청마다 확인한다. 그 사용자의 managed reference를 membership에 연결한다. 요청의 사용자 ID/역할로 대체하지 않는다.
- Workspace.create(normalizedName), WorkspaceMembership.ownerOf(workspace, user) 같은 좁은 팩토리로 유효한 새 객체를 만든다. 임의 ID·시간 setter나 범용 Entity 변경 API는 추가하지 않는다.
- 기존 조회 전용 Repository 3개/메서드 5개는 유지한다. 새 쓰기 어댑터는 EntityManager.persist로 신규 두 엔티티와 flush만 담당하며 Service 트랜잭션을 필수로 한다. save/merge/delete를 통째로 노출하는 JpaRepository로 교체하지 않는다.
- IDENTITY 저장으로 INSERT가 먼저 실행돼도 커밋한 것은 아니다. membership/flush/커밋 전 검증이 실패하면 두 업무 행을 롤백한다. 실패를 잡고 정상 DTO를 반환하지 않는다. 응답 DTO는 트랜잭션 안에서 만들되 성공 응답은 Service 프록시의 커밋 이후 반환한다.
- DB 기본값의 created_at/updated_at을 사용하고 사용자 입력 시간은 저장하지 않는다. 생성에는 기존 공유 Workspace가 없어 소유권 이전용 workspace 행 잠금을 추가하지 않는다. 마지막 OWNER 유지/이전 기능은 별도 범위다.
- 동시 독립 생성은 각각 다른 ID와 OWNER 1명을 갖는다. 중복 이름은 허용하므로 사용자 행 잠금/이름 조회 후 중복 차단/전역 잠금은 추가하지 않는다.
- DB의 OWNER 부분 UNIQUE는 최대 1명만 보장한다. 최소 1명은 이 Service의 두 행 저장 원자성으로 보장하며, 직접 SQL의 고아 공간까지 DB가 금지한다고 주장하지 않는다. 실패 후 identity 시퀀스에 빈 번호가 생길 수 있다.

근거: [Spring 트랜잭션 프록시](https://docs.spring.io/spring-framework/reference/6.2/data-access/transaction/declarative/annotations.html), [롤백 규칙](https://docs.spring.io/spring-framework/reference/6.2/data-access/transaction/declarative/rolling-back.html).

## 앱 DB 권한과 기존 도구 전환

| 대상 | 목표 권한 |
|---|---|
| 현재 DB / flowdesk schema | CONNECT / USAGE 유지, CREATE/TEMP 금지 |
| users/workspaces/workspace_memberships | 기존 SELECT 유지 |
| workspaces / workspace_memberships | 이 두 테이블의 INSERT만 추가 |
| users INSERT, 업무 UPDATE/DELETE/TRUNCATE | 계속 금지 |
| sequence 직접 USAGE/SELECT/UPDATE, history, DDL/역할/소유권 | 계속 금지 |

- V1의 IDENTITY 자동 생성을 사용한다. SERIAL용 nextval을 직접 호출하는 구현과 혼동해 sequence USAGE를 일괄 GRANT하지 않는다. 실제 Hibernate INSERT/생성 키 반환을 격리 PostgreSQL에서 확인하고, 필요가 확인되지 않은 권한을 추가하지 않는다.
- INSERT는 두 테이블 단위로 제한한다. 이 권한만으로 행위자별 OWNER 규칙이 강제되지는 않는다. Service 팩토리/트랜잭션·입력 제한이 담당하며 직접 SQL의 임의 회원/메타데이터 INSERT까지 방지하는 RLS/프로시저 경계는 이번에 추가하지 않는다.
- 현재 AppReadPolicy의 CONNECT_ONLY/READ_ONLY 외에 정확한 WORKSPACE_CREATE 상태를 추가한다. 기존 조건에 두 테이블 INSERT만 모두 있어야 한다. 한 테이블만 INSERT·추가 DML/sequence/users/history·PUBLIC/역할/column/default/grant option 등은 계속 실패시키고 자동 복구/회수하지 않는다.
- 별도 관리자 프로비저닝은 기존 앱 자격증명·V1·ACL을 확인하고 READ_ONLY→WORKSPACE_CREATE를 한 트랜잭션으로 전환한다. 반복 실행은 권한/비밀번호/데이터를 유지한다. CONNECT_ONLY에 바로 쓰기를 주지 않는다. 앱 부팅/migrate/seed에 GRANT를 넣지 않는다.
- LocalMigrationRunner/LocalSeedRunner/읽기 권한 도구와 관련 PowerShell 검증도 승인된 새 권한 상태를 이해하도록 함께 확장한다. 읽기 권한 작업을 재실행해도 승인된 쓰기 권한을 몰래 회수하지 않는다.
- seed의 전체 예제 일치 정책은 유지한다. 실제 새 공간을 만든 뒤 Verify/Apply가 예제와 다름으로 중단하는 것은 정상이며 삭제/리셋/자동 병합으로 회피하지 않는다. migration Info/Validate/Migrate와 권한 검사는 새 상태에서도 정상이어야 한다.
- 기존 V1 스키마로 생성이 가능하므로 V1 수정이나 V2는 계획하지 않는다. 권한 정책 SQL은 별도 실행 도구에 두고 앱 JAR에 privileged runner/정책/자격증명을 포함하지 않는다. 실제 로컬 GRANT와 첫 생성은 후속 요청에서 별도로 실행한다.

근거: [PostgreSQL INSERT 권한/RETURNING](https://www.postgresql.org/docs/17/sql-insert.html), [IDENTITY](https://www.postgresql.org/docs/17/ddl-identity-columns.html), [PostgreSQL 17 IDENTITY 평가 구현](https://github.com/postgres/postgres/blob/REL_17_STABLE/src/backend/executor/execExprInterp.c#L2567).

## 구현 영향과 격리 수용 기준

- 영향 파일: workspace/api의 생성 DTO/Controller·업무 오류 처리, workspace/application의 생성 Service, Workspace/WorkspaceMembership 팩토리, 쓰기 어댑터, 개발 쓰기/Origin 검사·프로필 설정, migration source set의 공통 권한 검사/전용 프로비저닝·PowerShell과 격리 테스트, 관련 문서. User 저장/수정·조회 계약·Vue 생성 화면은 바꾸지 않는다.
- 기존 API/로컬 데이터와 독립된 Testcontainers DB로 검증한다. 실제 로컬에 테스트 공간을 만들지 않는다.

1. 정상 생성은 201/Location/DTO, 정규화된 이름, 신규 공간 1행+생성자 OWNER 1행이다. 새 트랜잭션의 GET 목록/상세에서도 읽힌다. 기존 사용자/공간/멤버십/이력은 유지된다.
2. Dave(VIEWER)와 기존 membership 없는 사용자도 생성할 수 있다. 생성자는 새 공간 OWNER이며 다른 기존 사용자는 새 공간을 조회할 수 없다. 위조 ownerId/role 등은 400이고 추가 행이 없다.
3. 공백·100/101 코드 포인트·다국어/보조 문자·null/숫자/누락/미지원·중복 키·잘못된 JSON/Content-Type/실제 본문 크기 경계를 확인한다. 같은 이름의 재요청/동시 요청은 독립된 두 공간과 각각 OWNER 1명이다.
4. 기본 모드 404/쓰기 off 405·누락 설정/READ_ONLY 시작 실패·정상 GET 유지, Origin 누락/null/외부/접미 도메인/다른 포트/복수 값·cross-site·form/text·전달 헤더 위조를 차단한다. 실제 Vite 프록시가 보존하는 허용 Origin도 확인한다.
5. 공간 INSERT 이후 membership 저장/flush 실패를 격리 DB에서 주입해 둘 다 롤백함을 별도 연결로 검증한다. 임시 제약/트리거는 격리 DB에만 두고, 같은 이름으로 정상 재시도 후 OWNER 1명을 확인한다. 시퀀스 연속성을 요구하지 않는다.
6. 실제 flowdesk_app의 JPA 생성과 생성 키 반환이 sequence GRANT 없이 성공해야 한다. users INSERT·기존 행 UPDATE/DELETE·sequence 직접 접근·history/DDL 등은 거부된다. 부분/과도한 ACL, 반복 권한 적용, migration/seed/읽기 도구 회귀를 검증한다.
7. DB 장애/커밋 실패·응답 불명확 시 허위 성공/미생성 보장을 하지 않고 민감정보 없이 실패한다. 정상/실패 검증 후 기존 테스트·빌드와 전용 PowerShell 전체 경로를 실행한다. 실제 로컬 적용은 그 뒤 별도 단계다.

## 구현과 검증 결과

- Workspace.create/WorkspaceMembership.ownerOf와 별도 WorkspaceCommandService, MANDATORY 트랜잭션의 WorkspaceCreationRepository를 구현했다. 조회 Repository의 5개 계약과 readOnly는 유지했다.
- WorkspaceCreateController는 최대 4097바이트를 읽어 제한을 검사하고 UTF-8·중복 키·추가 JSON/필드를 검증한다. DevWorkspaceWriteSettings/Filter가 origin·쓰기 권한·JSON 경계를 확인한다. WorkspaceErrors는 안전한 오류를 반환하고 연결 장애와 무결성 실패를 구분한다.
- AppReadPolicy는 WORKSPACE_CREATE를 지원한다. AppWorkspaceCreateAccessRunner는 기존 관리자 검증/잠금/트랜잭션 경로를 재사용한다. infra/provision-workspace-create.ps1 → run-app-create-gradle.ps1 → runAppWorkspaceCreateAccess → 전용 SQL로 INSERT만 부여하며 비밀번호를 회전하지 않는다.
- 신규 WorkspaceCreateApiTest 44개/WorkspaceCreateAccessPolicyTest 17개가 통과했다. VIEWER·미소속 사용자 생성, 이름/JSON/본문/Origin 경계, 동시 동일 이름, 저장/커밋 실패 롤백과 실제 권한 거부를 확인했다. 전체 JUnit은 기존 138개를 포함한 199개다.
- 별도 verifyWorkspaceProxy 1개는 실제 Vite 설정에서 포트/프록시 대상만 격리 앱으로 바꿔 허용 Origin 보존/201과 외부 Origin/403·추가 행 0건을 확인했다. Node와 frontend npm ci가 필요하므로 기본 test와 분리했다. 브라우저의 Vite 연결과 맞춰 HTTP/1.1을 사용한다.
- ./infra/verify-migration.ps1 -IncludeWorkspaceCreate는 52개 통과했다. UUID Compose 프로젝트에서 전용 실행기·쓰기 전환/반복·잘못된 대상/부분·과도한 ACL 거부, migration/seed/읽기 도구 호환과 데이터/자격증명/부모 환경 보존을 확인했다. 컨테이너/볼륨/네트워크/임시 자격증명은 정리했다.
- 생성 후 업무 데이터가 추가되면 seed가 중단하는 정책은 별도 Java 통합 테스트로 확인했다. 새 상태에서 migration은 계속 Validate/Migrate 0건으로 동작한다. 시퀀스 직접 GRANT 없이 실제 JPA/JDBC의 IDENTITY 키 생성이 성공했다.
- 실제 로컬은 쓰기를 켜거나 GRANT/생성하지 않았다. 최신 JAR 및 IDE와 같은 클래스패스의 읽기 모드 기동/HTTP만 재확인했고 기존 데이터/이력/ACL/시퀀스·자격증명/V1·부모 환경을 유지했다. Vue 생성 폼은 아직 없다.

## 실행 명령과 후속 로컬 적용

```powershell
# backend: 전체 회귀/패키징. 프록시 검증에는 frontend의 npm ci가 필요
.\gradlew.bat build verifyWorkspaceProxy --no-daemon --no-configuration-cache --console=plain
# 저장소 루트: 고유 Compose 프로젝트만 생성·정리
./infra/verify-migration.ps1 -IncludeWorkspaceCreate
```

실제 로컬은 아래 별도 요청 단계에서 ./infra/provision-workspace-create.ps1로 전환했다. 서버 프로세스에만 FLOWDESK_DEV_ACTOR_EMAIL, FLOWDESK_DEV_WORKSPACE_WRITE_ENABLED=true, FLOWDESK_DEV_WRITE_ALLOWED_ORIGINS를 주입하고 dev-actor로 실행한다. 앱 설정에 관리자/migrator 자격증명을 추가하지 않는다.

## 실제 로컬 적용·첫 생성 — 2026-11-03

후속 [Vue 생성 폼](../frontend/README.md)은 201 확인 후 목록/새 상세를 조회하고, 결과 불명에서는 재전송을 잠근다. 프론트 58개/타입 검사·빌드와 실제 Chrome/격리 PostgreSQL에서 커밋 후 응답 유실까지 확인했다. 그 검증은 실제 로컬에 공간을 추가하지 않았다.

- 사용자 승인 후 전용 권한 작업을 최초/반복 실행해 READ_ONLY→WORKSPACE_CREATE를 확인했다. 기존 SELECT와 workspace/membership INSERT만 허용하며 users INSERT·UPDATE/DELETE/TRUNCATE·sequence·history·DDL은 계속 금지한다. 비밀번호와 V1은 변경하지 않았다.
- 127.0.0.1:18081의 별도 쓰기 앱에서 Alice로 POST를 한 번 전송했다. name의 앞뒤 공백을 제거한 FlowDesk Local Create Check f2937116bdf0가 ID 문자열 "3"으로 생성됐고 201/Location=/api/workspaces/3/myRole=OWNER/no-store를 확인했다.
- DB에는 공간 한 행과 Alice의 OWNER membership 한 행이 함께 저장됐다. Alice 목록/상세 200, Eve 상세 404·목록 미노출, 위조 행위자 헤더 무시, 미지원 ownerId 입력 400·외부 Origin 403을 확인했다. 생성 데이터는 삭제하지 않고 유지한다.
- 전후 기존 사용자/공간/멤버십·Flyway 이력은 같고 신규 두 행만 추가됐다. 현재 사용자/공간/멤버십은 5/3/6이다. history V1 checksum=-1384934132/installed_by=flowdesk_migrator/success=true를 유지했다. 자격증명 3개/V1 파일 해시·부모 환경도 같다.
- migration Validate는 성공했다. seed Verify는 추가 업무 데이터 때문에 예상대로 mismatch로 중단했다. 예제 5/2/5를 바꾸거나 초기화하지 않았다. seed는 업무 데이터 복구/리셋 도구가 아니다.
- 검증용 Alice/Eve 앱은 종료했고 기존 IDE 8080 프로세스(PID 28676)는 유지됐다. 기존 서버의 두 health 경로도 200/UP이다. DB INSERT 권한과 POST 활성화 설정은 별개이며 기존 서버에 쓰기 설정을 자동 추가하지 않았다.
- bootJar는 성공/UP-TO-DATE(7초). 실제 작업 명령은 루트에서 & ./backend/build/local-create-verification/apply-and-verify.ps1이다. Git 제외 build 아래 임시 실행물/영수증이며 POST를 자동 재전송하지 않고 결과 불명 시 이름/관계를 조회한다. 이는 서버의 멱등키 지원이 아니다.
- 앱/권한 도구 소스는 바꾸지 않아 이전 Java 199개·프록시 1개·PowerShell 52개와 프론트 31개는 반복하지 않았다. 다음 작업은 Vue 생성 폼과 POST 연결이다.
