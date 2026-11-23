# 첫 업무 데이터 모델과 Flyway 범위

- 날짜: 2026-11-02
- 상태: V1·seed·READ 권한 로컬 적용과 새 JPA 앱 기동/조회까지 완료. 업무 API·서버 인가·쓰기는 후속.
- 목표: 협업의 기반인 User·Workspace·WorkspaceMembership 관계와 첫 스키마 변경의 경계를 정한다.
- 완료 기준: 필드·관계·제약조건·트랜잭션 책임·계정 경계·마이그레이션 수용 시나리오가 설명 가능하다.

## 사용자와 확정한 제품 정책

1. Phase 1 사용자는 개발용 seed로 준비한다. 회원가입·비밀번호 해시·로그인 구현은 기존 로드맵대로 Phase 2에서 진행한다.
2. 이메일은 앞뒤 공백 제거와 소문자 정규화 후 유일하다. Alice@Example.com과 alice@example.com은 같은 주소로 취급한다.
3. 워크스페이스는 OWNER를 정확히 1명 유지한다. 소유권 이전도 하나의 트랜잭션으로 처리한다.

개발용 seed는 계정의 인증 완료나 로그인 권한을 뜻하지 않는다. 이 설계로 인증 없는 업무 API를 공개하지 않는다. 이후 [첫 조회 계약](workspace-read-api-v1.md)에서 명시적인 로컬 개발 모드의 서버 지정 행위자·Service 소속 검사를 확정하고 구현·격리 검증했다. 실제 인증은 계속 Phase 2다.

## 첫 범위

```text
users 1 ── N workspace_memberships N ── 1 workspaces
```

- User는 계정 주체, Workspace는 협업 공간, WorkspaceMembership은 가입 관계와 해당 공간 안의 역할이다.
- 하나의 사용자는 여러 워크스페이스에 속할 수 있고, 워크스페이스마다 다른 역할을 가질 수 있다.
- 워크스페이스 소유자는 membership의 OWNER로 표현한다. workspaces.owner_user_id를 중복 저장하지 않는다.
- 역할 값은 기존 요구사항의 OWNER·ADMIN·MEMBER·VIEWER를 사용한다. 이 단계에서 역할별 API 권한 매트릭스를 확정하거나 구현하지 않는다.
- membership은 실제로 가입된 관계만 표현한다. 초대 대기·만료·거절·재초대는 향후 초대 모델에서 결정한다.

프로젝트·작업·댓글·Activity·Notification·프로젝트별 멤버십·인증 토큰·MFA는 첫 마이그레이션에 포함하지 않는다.

## 공통 구현 기본값

- 전용 PostgreSQL 스키마: flowdesk. 모든 마이그레이션 SQL은 테이블 이름에 스키마를 명시한다.
- ID: bigint GENERATED ALWAYS AS IDENTITY. INSERT 시 DB가 생성하며 외부 요청이 임의 ID를 지정하게 하지 않는다. UUID와 비교해 단순한 JPA IDENTITY 매핑과 작은 FK를 우선한다. 예측 가능한 ID는 인가를 대신하지 않는다.
- 시간: created_at·updated_at은 timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP. Java 매핑 시 Instant를 기준으로 한다. timestamptz는 표시 시간대를 고정하는 타입이 아니라 시점을 저장하는 타입이다.
- updated_at 기본값은 INSERT 때만 적용된다. UPDATE 시에는 향후 Service/JPA가 갱신하며 자동 갱신 트리거는 V1에 추가하지 않는다.
- 삭제: 첫 기능에는 사용자·워크스페이스·멤버십의 삭제/탈퇴 API를 넣지 않는다. FK는 ON DELETE RESTRICT로 의도하지 않은 연쇄 삭제를 막는다. 보관·탈퇴·개인정보 삭제 정책은 해당 기능 전 확정한다.
- 상태·범용 감사 컬럼·낙관적 락 컬럼은 필요한 변경 기능과 충돌 정책을 정할 때 추가한다. 미래 기능을 위해 빈 컬럼을 미리 늘리지 않는다.
- 아래 문자열 길이는 초기 구현 기본값이다. API 입력 검증과 DB 제약을 같은 기준으로 맞추고, 변경할 때 새 마이그레이션으로 수정한다.

## 테이블

### flowdesk.users — identity 모듈

| 컬럼 | 타입 / 기본값 | 제약·의미 |
|---|---|---|
| id | bigint identity | PK |
| email | varchar(254) | NOT NULL, 정규화된 이메일, UNIQUE |
| display_name | varchar(80) | NOT NULL, 공백만으로 구성 불가 |
| created_at | timestamptz / CURRENT_TIMESTAMP | NOT NULL |
| updated_at | timestamptz / CURRENT_TIMESTAMP | NOT NULL |

- email은 소문자·앞뒤 공백 제거 상태로 저장한다. DB CHECK는 `email = lower(btrim(email))` 및 비어 있지 않음을 검사하고 UNIQUE(email)로 경합에도 중복을 거부한다.
- 이메일 전체 문법이나 실제 소유 여부까지 DB CHECK로 보장하지 않는다. 가입 시 검증·국제화 주소·이메일 변경/검증·계정 삭제 후 주소 재사용 정책은 Phase 2에서 다룬다.
- 원문 대소문자 표시를 위한 별도 이메일 컬럼은 두지 않는다. 초기 seed는 example.test 주소를 사용한다.
- password_hash·email_verified·MFA·토큰 컬럼은 V1에 없다. 개발 사용자 행이 있다고 로그인 가능하다는 의미는 아니다.

### flowdesk.workspaces — workspace 모듈

| 컬럼 | 타입 / 기본값 | 제약·의미 |
|---|---|---|
| id | bigint identity | PK |
| name | varchar(100) | NOT NULL, 공백만으로 구성 불가 |
| created_at | timestamptz / CURRENT_TIMESTAMP | NOT NULL |
| updated_at | timestamptz / CURRENT_TIMESTAMP | NOT NULL |

name은 표시용이고 식별은 id로 한다. V1은 이름의 전역 UNIQUE 제약을 추가하지 않는다. 이름 중복에 대한 API 동작은 워크스페이스 생성 기능을 설계할 때 명시한다.

### flowdesk.workspace_memberships — workspace 모듈

| 컬럼 | 타입 / 기본값 | 제약·의미 |
|---|---|---|
| id | bigint identity | PK |
| workspace_id | bigint | NOT NULL, FK → workspaces.id, ON DELETE RESTRICT |
| user_id | bigint | NOT NULL, FK → users.id, ON DELETE RESTRICT |
| role | varchar(16) | NOT NULL, CHECK OWNER/ADMIN/MEMBER/VIEWER |
| created_at | timestamptz / CURRENT_TIMESTAMP | NOT NULL, 실제 가입 관계 생성 시점 |
| updated_at | timestamptz / CURRENT_TIMESTAMP | NOT NULL |

- UNIQUE(workspace_id, user_id): 같은 사용자의 중복 가입 관계를 DB에서 거부한다.
- UNIQUE INDEX(workspace_id) WHERE role = 'OWNER': 워크스페이스마다 OWNER가 최대 1명인 것을 DB에서 보장한다.
- user_id 인덱스: 사용자가 속한 워크스페이스 목록 조회를 지원한다. workspace_id 선두 조회는 기존 복합 UNIQUE 인덱스를 활용한다. FK 선언만으로 참조하는 쪽 인덱스가 자동 생성된다고 가정하지 않는다.
- 별도 id와 복합 UNIQUE를 선택해 향후 JPA 복합 식별자 매핑을 피한다. 복합 PK(workspace_id, user_id)도 가능하지만 초기 매핑의 단순성을 우선한다.

## OWNER 정확히 1명의 보장 범위

부분 유니크 인덱스만으로는 OWNER 0명을 막을 수 없다. 부분 유니크 인덱스는 최대 1명, Service 트랜잭션은 최소 1명을 담당한다. V1만 적용한 DB가 모든 직접 SQL에 대해 정확히 1명을 강제한다고 주장하지 않는다.

향후 WorkspaceService는 다음을 보장해야 한다.

- 생성: 사용자 확인 → workspace 생성 → 생성자의 OWNER membership 생성 → 한 트랜잭션 커밋. membership 저장이 실패하면 workspace도 롤백한다.
- 소유권 이전: workspace 행 잠금 → 이전 대상의 기존 membership 확인 → 기존 OWNER 강등 → 새 OWNER 승격 → 한 트랜잭션 커밋. 기존 OWNER의 강등 후 역할은 이전 API 설계 전에 별도로 결정한다.
- OWNER 제거·탈퇴·역할 변경: 이전 없는 마지막 OWNER 제거를 거부한다. 관련 변경도 같은 workspace 행 잠금 규칙을 따라야 한다.
- 동시 요청: DB UNIQUE와 workspace 행 잠금을 함께 사용해 복수 OWNER·소유자 유실을 막는다. 전체 칸반 동시 수정 정책이나 @Version 도입은 이 설계에서 확정하지 않는다.

DB constraint trigger로 0명도 금지하는 대안은 이번 범위에서 제외한다. DB 직접 쓰기까지 강제해야 하는 요구가 생기면 재검토한다.

### 멤버 초대·제거 확장 — 정책 확정, 구현 후속

- pending 초대는 가입된 WorkspaceMembership과 별도 데이터로 저장한다. target은 기존 User이며 이메일을 정규화해 유일한 계정으로 해석한다. 초대 코드/링크는 7일 만료·1회 사용이고 DB에는 token digest만 보관한다. 재발급은 이전 pending 초대를 폐기한다.
- 초대 수락 때까지 Membership을 만들지 않는다. 수락 actor가 저장된 대상 사용자와 같아야 한다. Workspace/초대 상태 확인·코드 소비·Membership INSERT는 한 트랜잭션이다.
- OWNER·ADMIN은 일반 멤버 관리, 현재 OWNER만 이미 가입된 멤버에게 소유권을 이전한다. 이전 OWNER는 ADMIN이 되며 Workspace row lock으로 동시 변경을 직렬화한다.
- Membership을 제거해도 User FK를 가리키는 과거 Task assignee와 Task 상태 이력은 지우지 않는다. 이후 새 배정에는 현재 Workspace Membership이 필요하다. 멤버 변경과 담당자 변경의 잠금 순서는 [멤버십 관리 계약](workspace-membership-management-v1.md)에 따른다.
- 새 invitation 테이블·최소 권한·마이그레이션은 구현/격리 검증 후 별도 로컬 적용으로 진행하며, V1은 수정하지 않는다.

## 첫 Flyway 마이그레이션 경계

작성 파일: `backend/src/main/resources/db/migration/V1__create_identity_and_workspace_tables.sql`.
Flyway 테스트 의존성으로 격리 DB에서 적용을 검증했다. V1에 포함한 내용은 다음뿐이다.

1. 미리 준비된 flowdesk 스키마 안에 users → workspaces → workspace_memberships 순서로 생성.
2. ID identity·시간 기본값·NOT NULL·빈 문자열 방지 CHECK·이메일 정규화 CHECK·역할 CHECK·PK·FK·UNIQUE 추가.
3. 단일 OWNER 부분 유니크 인덱스와 membership user_id 인덱스 추가.

다음은 V1 SQL에 넣지 않는다.

- DB/로그인 역할 생성, 비밀번호 설정: Flyway 실행 전 관리자 프로비저닝에서 처리한다.
- 예제 사용자·워크스페이스 INSERT: 개발용 seed로 분리한다. data.sql이나 운영 기본 Flyway 경로에 넣지 않는다.
- 앱의 새 테이블 CRUD 권한: 첫 업무 API에서 필요한 권한을 확인한 뒤 별도 명시적 변경으로 부여한다. V1 시점에는 기존 health 연결 권한을 유지한다.
- 인증·Project·Task 테이블, JPA Entity·Repository, 업무 API·Vue 화면, 자동 인가 기능.

### 실행 역할과 소유권

| 역할 | 책임 / 예정 권한 |
|---|---|
| postgres | 로컬 초기 관리자. flowdesk_migrator 생성과 전용 schema 생성·소유권 지정 |
| flowdesk_migrator | FlowDesk DB CONNECT, flowdesk 스키마 소유자로 해당 스키마 DDL 수행. 슈퍼유저·CREATEDB·CREATEROLE 불허 |
| flowdesk_app | CONNECT+flowdesk schema USAGE+업무 테이블 3개 SELECT. DML/DDL·sequence·Flyway 이력 접근은 금지 |

flowdesk 스키마는 관리자 프로비저닝에서 생성해 migrator에 소유권을 준다. migrator에 DB 전체 CREATE 권한을 상시 주지 않는다. Flyway 이력 테이블도 flowdesk 스키마에 두고 애플리케이션과 다른 자격증명으로 실행한다.

테스트에서는 별도 migrator·앱 역할·DB·스키마를 fixture로 준비한다. 로컬 적용은 앱 부팅과 분리된 전용 작업·별도 자격증명 파일·전용 자식 프로세스로 실행한다. [local-migration.md](local-migration.md)의 전용 migration source set·프로비저닝·실행기를 통해 2026-11-03 실제 로컬 DB에 V1을 적용했다. Flyway는 테스트와 전용 migration 클래스패스에만 포함한다.

### 버전과 변경 규칙

- V1은 PostgreSQL에서 트랜잭션 안에 실행 가능한 CREATE TABLE·일반 CREATE INDEX만 사용한다. CREATE INDEX CONCURRENTLY 같은 비트랜잭션 작업은 없다.
- 적용 이력은 flyway_schema_history가 관리한다. 적용된 V1을 수정하지 않고 V2 이후 변경으로 이어간다. 변경된 checksum을 무조건 repair로 맞추지 않는다.
- 기존 spring.sql.init.mode=never를 유지한다. Hibernate 자동 스키마 변경을 병행하지 않으며 JPA 도입 시 ddl-auto=validate를 기준으로 검토한다.
- 자동 baseline이나 clean으로 기존 상태를 덮어 맞추지 않는다. 예상치 못한 업무 테이블·기존 Flyway 이력이 있으면 적용을 멈추고 상태를 확인한다.
- 전용 schema·계정·권한은 격리 fixture로 검증한 뒤 로컬 V1·seed·READ 권한까지 별도 적용했다. 당시 단계별 명령/결과는 local-setup.md와 jpa-read-model-v1.md에서 구분한다.

## 요구사항에서 향후 요청 흐름으로 연결

```text
워크스페이스 생성 요구사항
→ Vue/HTTP 요청 DTO (후속)
→ 인증된 행위자 확인·Controller 검증 (후속)
→ WorkspaceService @Transactional
→ User 존재 확인 + Workspace/OWNER Membership 저장
→ DB FK·UNIQUE 검증
→ 응답 DTO → 화면
```

User·WorkspaceMembership 존재만으로 요청 권한이 자동 검증되지는 않는다. 인증·서버 인가는 별도 구현하며 Entity를 응답으로 직접 노출하지 않는다.

## 구현 단계의 수용 시나리오

V1MigrationTest의 격리 PostgreSQL에서 아래 항목을 검증했다. 각 테스트는 새 DB를 생성하므로 이전 테스트의 행이나 이력을 재사용하지 않는다.

| 검증 | 기대 결과 |
|---|---|
| 빈 격리 DB에 migrator로 V1 실행 | 세 업무 테이블·제약·인덱스·Flyway 이력 생성 |
| 동일 V1 재실행 | 추가 변경 없이 최신 상태 |
| 적용된 V1 checksum 불일치 | validate 실패, repair 자동 실행 금지 |
| 중복된 정규화 이메일 저장 | UNIQUE 위반 |
| 비정규화 이메일·공백 이름 저장 | CHECK 위반 |
| 같은 사용자·워크스페이스 membership 중복 | UNIQUE 위반 |
| 없는 사용자·워크스페이스로 membership 생성 | FK 위반 |
| 정의 밖 역할 저장 | CHECK 위반 |
| 같은 workspace에 OWNER 2명 저장 | 부분 UNIQUE 위반 |
| 참조 중인 사용자·workspace 삭제 | FK RESTRICT로 실패 |
| flowdesk_app으로 DDL·업무 테이블 읽기 시도 | 현재 승인 범위에서는 거부 |
| ID·시간 기본값 | DB 생성 ID·created_at/updated_at 기본값 확인, 직접 ID 지정 거부 |
| migrator 권한 | 테이블·이력은 migrator 소유, 관리자·DB CREATE·다른 스키마 생성 권한 없음 |

개발용 seed 도구는 구현했다. seed main이 두 예제 공간의 OWNER 수를 확인하는 것과 향후 업무 Service가 일반 생성/이전/탈퇴 트랜잭션을 보장하는 것은 구분한다. 해당 Service는 아직 없다.

개발 seed의 데이터·실행 범위와 실행기는 [dev-seed.md](dev-seed.md)를 따른다. 사용자 5명·워크스페이스 2개·멤버십 5개를 별도 수동 작업에서 기존 migrator로 준비했다. 2026-11-03 로컬 Apply 12행/Verify 성공/반복 Apply 0건을 확인했다. 불일치 시 중단 정책과 앱 권한은 유지한다.

OWNER 최소 1명·이전·행위자 권한 검증은 실제 Service/API 구현 단계에서 추가한다. 아직 해당 기능이 없는 상태에서 테스트 통과를 주장하지 않는다.

## 후속에서 결정할 사항

- 회원가입·이메일 소유 검증·비밀번호 정책·국제화 주소·계정 삭제 후 이메일 재사용.
- 역할별 권한 매트릭스, 개발 단계 행위자 식별, 소유권 이전 후 기존 소유자의 역할.
- 초대·탈퇴·보관·물리 삭제 정책, 프로젝트별 예외 멤버십.
- 첫 쓰기 업무 API에 맞춘 app DML·sequence 권한. 조회 권한과 JPA 기동/조회는 로컬까지 확인했고 HTTP API/인가·쓰기 Service는 후속이다.

첫 JPA는 쓰기 API보다 먼저 [조회 범위](jpa-read-model-v1.md)로 구현·검증했다. 사용자 선택으로 schema USAGE+세 테이블 SELECT를 로컬에 적용하고 실제 앱 계정으로 조회했다. DML/sequence/history는 금지한다. Long IDENTITY·Instant·varchar enum 및 단방향 LAZY는 기존 V1에 맞췄다. 저장/OWNER 변경 Service와 DB 쓰기 권한은 후속이다.

## 참고

설계 당시 읽기 전용 SQL에서 로컬 flowdesk 스키마·업무 테이블·flowdesk_migrator가 없음을 확인했다. 이후 V1 구현과 격리 테스트 후에도 로컬 DB의 세 수량은 모두 0이었다. PK 중복 검증 보강 후 최종 `gradlew.bat build --no-daemon --console=plain`은 29초에 성공했고 V1MigrationTest 24개와 기존 7개가 모두 통과했다. 실행 JAR에는 V1 SQL이 포함되지만 Flyway 런타임 JAR 및 실제 .env 파일은 각각 0개여서 이번 변경으로 앱 자동 마이그레이션을 켜지 않았다.

- [PostgreSQL 부분 인덱스](https://www.postgresql.org/docs/17/indexes-partial.html).
- [PostgreSQL 제약조건과 FK 인덱스](https://www.postgresql.org/docs/17/ddl-constraints.html).
- [Flyway versioned migrations](https://documentation.red-gate.com/fd/versioned-migrations-273973333.html).
