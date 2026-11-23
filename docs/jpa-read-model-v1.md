# User·Workspace·Membership의 첫 JPA/Repository 범위

- 날짜: 2026-11-03
- 상태: 구현·격리 검증 및 실제 로컬 READ 권한 적용/새 JPA 앱 기동·Repository 조회 확인 완료.
- 후속 현재 상태: [생성 계약](workspace-create-api-v1.md) 구현·격리 검증 후 실제 로컬도 WORKSPACE_CREATE로 전환했다. 조회 계약/SELECT는 유지하고 두 테이블 INSERT만 추가했으며 첫 공간/OWNER 생성으로 데이터가 5/3/6이 됐다. 이 문서의 초기 READ_ONLY 검증 기록은 당시 범위다.
- 목표: 이미 적용한 V1과 seed를 앱 계정으로 조회할 수 있도록 매핑·조회 계약·최소 DB 권한·검증 범위를 정한다.
- 완료 기준: 3개 엔티티/연관관계, 5개 조회 메서드, 트랜잭션/조회 경계, 권한 전환과 격리 검증 기준이 명확하다.
- 사용자 선택: 조회 전용 Repository와 SELECT 최소 권한. 구현·격리 검증 뒤 별도 요청으로 로컬 GRANT와 앱 조회까지 확인했다.

## 포함·제외 범위

- 포함: User, Workspace, WorkspaceMembership, WorkspaceRole enum, 조회 전용 Repository 3개, V1 schema validate, 격리 PostgreSQL 통합 테스트 설계.
- 포함: 앱의 읽기 권한 프로비저닝과 기존 migration/seed 권한 검사 전환을 함께 구현·회귀 검증할 범위.
- 제외: save/delete/update, 생성/소유권 이전/탈퇴 Service, Controller/DTO/HTTP API, 개발 행위자 식별·로그인·인가, Vue 화면, V2/새 테이블.
- SQL과 데이터의 기준은 [V1 모델](domain-model-v1.md)과 [seed](dev-seed.md)다. JPA를 맞추려고 V1·실제 seed를 바꾸지 않는다.

## 엔티티와 패키지

아래 경로에 구현했다. HTTP/쓰기 Service는 추가하지 않았다.

| 클래스 | 위치 (com.flowdesk 아래) | 테이블 |
|---|---|---|
| User | identity/domain/User.java | flowdesk.users |
| Workspace | workspace/domain/Workspace.java | flowdesk.workspaces |
| WorkspaceMembership | workspace/domain/WorkspaceMembership.java | flowdesk.workspace_memberships |
| WorkspaceRole | workspace/domain/WorkspaceRole.java | role 컬럼의 OWNER/ADMIN/MEMBER/VIEWER enum |

- 일반 class, protected 기본 생성자, 필드 접근 매핑, 필요한 getter를 사용한다. 프록시와 엔티티 생성 규약을 위해 엔티티/접근 메서드를 final로 만들지 않는다. Entity를 record나 Lombok @Data로 만들지 않는다.
- setter·저장 팩토리·변경 메서드·관계 전체를 순회하는 toString/equals/hashCode는 이번에 추가하지 않는다. 범용 BaseEntity나 별도 순수 도메인/영속 엔티티 쌍도 미리 만들지 않는다.
- 세 엔티티에 @Entity와 명시적인 @Table(name=..., schema="flowdesk")을 둔다. Java 이름과 SQL 이름이 다른 컬럼도 @Column/@JoinColumn으로 명시한다.

### 공통 컬럼

| DB 컬럼 | Java | 매핑/이번 책임 |
|---|---|---|
| id: bigint identity | Long id | @Id, @GeneratedValue(strategy=IDENTITY). 실제 ID를 조회하며 고정 숫자를 가정하지 않음 |
| created_at: timestamptz | Instant createdAt | nullable=false, insertable=false, updatable=false. 기존 DB 생성 값을 읽음 |
| updated_at: timestamptz | Instant updatedAt | nullable=false, insertable=false, updatable=false. 이번은 조회만 하며 자동 갱신 기능을 추가하지 않음 |

Instant는 고정 Boot BOM의 Hibernate 지원 타입으로 매핑하고 V1 timestamptz와의 호환을 실제 validate/조회로 확인한다. @CreationTimestamp/@UpdateTimestamp·JPA auditing·생성/갱신 callback은 추가하지 않는다. DB updated_at 기본값은 INSERT 때뿐이며 미래 UPDATE 정책은 쓰기 Service 단계에서 설계한다. IDENTITY 표시는 현재 저장 메서드를 허용한다는 뜻이 아니다.

### 개별 컬럼/연관관계

| 엔티티 필드 | DB 컬럼/타입 | 매핑 |
|---|---|---|
| User.email | email varchar(254) NOT NULL | String, length=254, nullable=false |
| User.displayName | display_name varchar(80) NOT NULL | String, length=80, nullable=false |
| Workspace.name | name varchar(100) NOT NULL | String, length=100, nullable=false |
| WorkspaceMembership.user | user_id bigint NOT NULL | User, 단방향 @ManyToOne(fetch=LAZY, optional=false), @JoinColumn(nullable=false) |
| WorkspaceMembership.workspace | workspace_id bigint NOT NULL | Workspace, 단방향 @ManyToOne(fetch=LAZY, optional=false), @JoinColumn(nullable=false) |
| WorkspaceMembership.role | role varchar(16) NOT NULL | WorkspaceRole, @Enumerated(STRING)와 @JdbcTypeCode(SqlTypes.VARCHAR), length=16, nullable=false |

- User/Workspace에 역방향 membership 컬렉션을 추가하지 않는다. cascade를 지정하지 않으며 orphanRemoval 관계도 만들지 않는다. FK 값을 별도 쓰기 필드로 중복 매핑하지 않는다.
- Membership → User의 객체 참조는 identity/workspace 모듈 사이의 JPA 의존성을 만든다. 이번 모듈형 모놀리스에서는 연관관계 학습·쿼리 표현의 단순성을 택한다. ID 값만 저장하고 명시적 조회로 조합하는 대안은 모듈 독립 요구가 생기면 재검토한다.
- role은 PostgreSQL native enum이나 ordinal 숫자로 바꾸지 않고 V1의 varchar+CHECK와 맞춘다. Hibernate 전용 VARCHAR 지정은 기존 SQL 타입을 명시하려는 좁은 사용이다.
- 이름 UNIQUE, OWNER 컬럼 중복, cascade delete, @Version 컬럼을 추가하지 않는다. FK/UNIQUE/CHECK/부분 인덱스의 기준은 기존 SQL이다.

## Repository 계약: 조회 메서드 5개

각 인터페이스는 Spring Data의 Repository<Entity, Long>만 상속하고 @Transactional(readOnly=true)를 명시한다. JpaRepository/CrudRepository의 저장·삭제·무제한 findAll을 함께 노출하지 않는다. 첫 단계에서는 공통 Repository 추상화도 만들지 않는다.

| 인터페이스 / 위치 | 메서드 | 목적 |
|---|---|---|
| UserRepository / identity/repository | Optional<User> findById(Long id) | 존재/필드 조회 |
| UserRepository / identity/repository | Optional<User> findByEmail(String email) | 이미 정규화된 이메일의 정확한 일치 조회 |
| WorkspaceRepository / workspace/repository | Optional<Workspace> findById(Long id) | 공간의 기본 필드 조회 |
| WorkspaceMembershipRepository / workspace/repository | Optional<WorkspaceMembership> findByWorkspace_IdAndUser_Id(Long workspaceId, Long userId) | 특정 공간·사용자의 가입 관계/역할 조회 |
| WorkspaceMembershipRepository / workspace/repository | Slice<WorkspaceMembership> findByUser_IdOrderByWorkspace_IdAsc(Long userId, Pageable pageable) | 사용자 소속 목록을 안정적인 공간 ID 순서로 제한 조회 |

- 이메일 정규화는 향후 입력/Application Service 책임이며 Repository가 임의 입력을 고쳐 저장하지 않는다. 이번 조회의 전제는 정규화된 이메일이고 없으면 Optional.empty다.
- 소속 목록에는 @EntityGraph(attributePaths="workspace")를 적용해 필요한 ToOne workspace를 함께 읽는다. 모든 조회를 EAGER로 바꾸거나 User까지 불필요하게 읽지 않는다. 개별 membership 조회의 연관관계는 기본 LAZY를 유지한다.
- 목록은 Pageable을 명시해 Slice로 제한한다. 이번에는 전체 개수 집계 API가 없어 Page의 count 계약은 추가하지 않는다. 테스트는 bounded PageRequest와 기본 정렬을 사용하고, 외부 page size/정렬 입력 검증은 HTTP/Application Service를 만들 때 정한다.
- workspace ID 또는 user ID를 메서드에 전달했다는 사실은 인가를 뜻하지 않는다. Alice/A 조회가 있고 Eve/A가 없다는 데이터 검증은 가능하지만, 신뢰할 수 있는 요청자 식별과 리소스 접근 검사는 후속 서버 계층의 책임이다.

### 트랜잭션과 응답 경계

Repository 단독 조회에는 명시적인 readOnly 트랜잭션을 둔다. 여러 조회를 묶어 DTO를 만드는 단계에서는 Application Service의 @Transactional(readOnly=true)가 경계를 담당한다. 그 Service/DTO/HTTP 구현은 이번 범위에서 제외한다.

readOnly는 ORM/트랜잭션 설정이지 보안 경계가 아니다. EntityManager를 직접 사용하거나 다른 트랜잭션으로 우회할 가능성은 DB 권한으로 제한한다. Repository가 반환한 Entity를 Controller에서 그대로 직렬화하지 않으며 필요한 LAZY 접근은 트랜잭션 안에서 수행한다. OSIV로 HTTP 응답 시점까지 조회 세션을 연장하지 않는다.

## 구현한 의존성/설정

- spring-boot-starter-data-jpa를 기존 Boot 3.5.16 BOM으로 추가했다. JDBC/Hikari/PostgreSQL은 같은 BOM을 유지하며 Querydsl·Lombok·범용 매퍼는 추가하지 않았다.
- Hibernate 6.6.53.Final, Jakarta Persistence 3.1.0, Spring Data BOM 2025.0.13을 기준으로 JPA 의존성을 해결한다. 실제 dependencyInsight/빌드 결과는 local-setup.md에 기록한다. 최신 Hibernate로 별도 교체하지 않는다.
- spring.jpa.hibernate.ddl-auto=validate, spring.jpa.open-in-view=false를 적용했다. 기존 spring.sql.init.mode=never, 별도 Flyway 실행과 앱 계정 설정은 유지한다. update/create/create-drop과 앱 부팅 Flyway는 사용하지 않는다.
- validate는 매핑된 테이블/컬럼/타입 등 호환 검사다. V1 checksum·모든 CHECK/FK/부분 UNIQUE·권한·데이터 불변식을 전부 증명하는 기능으로 취급하지 않는다.

## 앱 조회 권한과 실행 경계

아래 권한을 전용 도구로 격리 검증한 뒤 2026-11-03 실제 로컬 flowdesk_app에도 적용했다. 현재 로컬 앱은 READ-only 상태다.

| 대상 | flowdesk_app 적용 권한 |
|---|---|
| 현재 FlowDesk DB | CONNECT 유지, CREATE/TEMP 금지 |
| flowdesk schema | USAGE만 추가, CREATE 금지 |
| users/workspaces/workspace_memberships | SELECT만 추가 |
| 업무 테이블 INSERT/UPDATE/DELETE/TRUNCATE 등 | 계속 금지 |
| identity sequence | USAGE/SELECT/UPDATE 부여하지 않음 |
| flyway_schema_history | SELECT·쓰기 등 업무 접근 권한 부여하지 않음 |
| 역할/소유권 | 비관리자 속성과 기존 migrator 소유권 유지 |

세 테이블을 명시적으로 지정한다. ALL TABLES/ALL SEQUENCES·DEFAULT PRIVILEGES·역할 상속으로 미래 객체에 권한을 자동 확장하지 않는다. 이 권한은 전체 3개 업무 테이블을 읽을 수 있게 하므로 사용자/팀별 DB 격리나 RLS가 아니다. 향후 API 인가는 서버에서 별도로 검사한다.

### 권한 프로비저닝과 기존 도구 호환

- infra/provision-app-read.ps1 → run-app-read-gradle.ps1 → runAppReadAccess → AppReadAccessRunner를 구현했다. 정책 SQL은 앱 JAR 밖의 src/migration/resources/db/policy/app-read-access.sql이다. 기존 관리자 연결에서 현재 FlowDesk DB에만 명시적으로 부여하고, 기존 앱 비밀번호를 실제 인증해 확인하며 회전하지 않는다. 다른 역할·PUBLIC·스키마 소유권이나 파일을 바꾸지 않는다.
- migration/seed 검사는 AppReadPolicy로 기존 CONNECT-only와 정확한 READ-only를 구분하도록 조정했다. schema USAGE만 있는 부분 상태도 실패하며 단순히 ACL 검사를 제거하지 않았다.
- 허용 상태를 기존 CONNECT-only와 승인된 READ-only로 구분한다. READ-only에서는 앱에 schema USAGE+세 테이블 SELECT만 있는지, history/sequence/DML/DDL·멤버십 경유 권한 확대가 없는지 검사한다. PUBLIC·다른 역할 grant, 임의 소유권 변경이나 부분/과다 권한은 중단한다.
- 실제 GRANT 작업은 예상 상태를 확인한 관리자 연결의 한 트랜잭션으로 처리하고 재실행 때 같은 권한·비밀번호를 유지한다. 예상 밖 기존 권한을 무작정 REVOKE/확대해 정상처럼 맞추지 않는다.
- 직접 ACL뿐 아니라 역할 속성/멤버십·DB CONNECT의 GRANT OPTION·PUBLIC 접근·컬럼 grant·미래 테이블/시퀀스 default privilege도 확인한다. 보호 대상 DB/schema/객체의 알려진 두 상태 밖이면 모든 전용 도구가 중단된다. 이 정책은 현재 FlowDesk DB의 알려진 V1 범위에 대한 것이며 클러스터 전체 보안 감사를 대신하지 않는다.
- 구현/테스트는 격리 DB에서 먼저 수행한다. 로컬 권한 적용과 새 JPA 앱 재시작은 이후 사용자가 요청한 범위에서 한다. READ 권한이 없는 기존 계정으로 새 JPA 앱을 부팅할 때 validate/업무 조회가 실패할 수 있음을 실행 절차에 명시한다. 앱 부팅이 GRANT를 대신하지 않는다.

## 격리 검증과 완료 기준

1. Testcontainers PostgreSQL 17.11에 실제 V1, migrator, 앱 계정과 동일 seed를 준비한다. 관리자/migrator는 fixture 준비에만 쓰고 EntityManagerFactory/Repository는 flowdesk_app으로 연결한다.
2. ddl-auto=validate 성공과 사용자/공간/가입 관계의 5개 조회 계약을 검증한다. DB 생성 Long ID·Instant·varchar enum이 실제 값과 맞아야 한다. 존재하지 않는 ID/이메일/가입 관계는 empty다.
3. Alice의 A 관계/목록, Eve의 A 미소속/B OWNER를 검증한다. 이는 데이터 조회 테스트이며 API 인증/인가 성공으로 보고하지 않는다.
4. 격리 fixture에서 한 사용자의 소속을 여러 개 준비해 Slice 경계·순서·hasNext를 확인한다. 초기화 이후 SQL 통계로 workspace 접근 시 N+1이 생기지 않는지 확인한다. 일반 membership의 LAZY는 트랜잭션 안에서 명시적으로 검증한다.
5. Repository/Verify 조회 전후 전체 데이터·ID·timestamps·Flyway history가 같고 쓰기 메서드가 공개되지 않아야 한다. ORM SQL에서 의도하지 않은 INSERT/UPDATE/DELETE가 없어야 한다.
6. app의 실제 SELECT 성공과 INSERT/UPDATE/DELETE/TRUNCATE·DDL·sequence/history 접근 거부를 격리 DB에서 검증한다. readOnly=false나 JDBC 경로로 우회해도 DB 권한이 쓰기를 거부해야 한다. 검증용 쓰기는 fixture/트랜잭션으로 격리한다.
7. 스키마 누락/필수 컬럼 변경으로 validate가 실패하고 자동 DDL/seed/migrate로 수정되지 않는지 확인한다. 앱 시작이 실패해도 실제 로컬 데이터를 바꿔 해결하지 않는다.
8. 권한 프로비저닝은 최초/반복·부분/과다 권한 거부·자격증명/대상 불일치를 검증한다. CONNECT-only와 READ-only 각각에서 migration Info/Validate/재실행 0건, seed Verify/반복 Apply 0건을 확인한다.
9. 기존 health 테스트와 전체 build를 확인한다. DatabaseHealthTest는 ORM과 무관한 CONNECT/health 장애·복구를 검증하므로 해당 테스트에서 HibernateJpaAutoConfiguration/JpaRepositoriesAutoConfiguration만 제외했다. HealthApiTest의 DataSource 제외도 유지한다. JPA 통합 테스트는 별도로 실제 validate를 수행한다.
10. main 앱 JAR에 migrator/seed runner·실제 자격증명이 없고 전용 작업의 클래스패스/환경 분리가 유지되는지 확인한다. 로컬 권한 적용·엔티티 부팅 성공은 격리 테스트 성공과 별도로 보고한다.

## 구현 검증과 실제 로컬 적용 순서

- JpaReadRepositoryTest는 실제 flowdesk_app DataSource/EntityManagerFactory로 5개 조회·Long/Instant/enum·LAZY·Slice·workspace fetch·권한 거부를 검증한다. 페이지 fixture만 격리 DB에 추가하며 실제 로컬 seed를 바꾸지 않는다.
- JpaSchemaValidationTest는 격리 스키마/필수 컬럼을 제거해 Hibernate validate 실패와 미복구를 확인한다. AppReadAccessPolicyTest는 실제 별도 JVM의 권한/migration/seed main으로 정상 전환·재실행·부분/과다 권한 거부를 검증한다.
- verify-migration.ps1 -IncludeReadAccess는 기존 migration/seed 검증 뒤 새 PowerShell 권한 도구와 READ-only 상태의 migration Info/Validate/재실행 0건·seed Verify/반복 Apply 0건을 실제 전체 경로로 확인한다. 고유 Compose 프로젝트·임시 포트·가짜 자격증명을 쓰고 검증 볼륨/파일을 정리한다.
- 실제 명령·최종 수량은 [local-setup.md](local-setup.md)에 기록한다. 구현·격리 검증 후 사용자 요청으로 아래 로컬 적용을 수행했다. Vue는 바꾸지 않아 프론트 검증을 반복하지 않는다.
- 최종 검증: 전체 JUnit 96개(기존 62+조회/권한/validate 34), 실패·오류·건너뜀 0. build/dependencyInsight 성공, Hibernate runtime 6.6.53.Final 확인. 실제 PowerShell 경로는 41개 검증 통과 후 검증용 컨테이너·네트워크·볼륨과 자격증명 파일을 정리했다.
- 앱 JAR에는 조회 엔티티가 포함되며 privileged runner/정책 SQL/Flyway/.env는 없다. 구현 검증 종료 당시 로컬은 데이터 5/2/5·V1 성공 1건·CONNECT-only였고 이후 아래 절차로 READ-only를 적용했다.

```powershell
# backend: JPA/권한 테스트만 실행 (Docker 필요)
.\gradlew.bat test --tests 'com.flowdesk.persistence.*' --no-daemon --no-configuration-cache --console=plain

# 루트: 기존 작업과 READ-only 전환의 실제 PowerShell 경로 (격리 DB만 변경)
pwsh -NoProfile -NonInteractive -File infra/verify-migration.ps1 -IncludeReadAccess
```

앱 부팅은 권한을 자동 부여하지 않는다. 실제 로컬 권한은 아래 전용 프로비저닝으로 준비했다. 이후 IDE Run/bootRun/JAR는 같은 앱 설정을 사용하며 아직 업무 HTTP API는 없다.

## 실제 로컬 READ 적용·JPA 확인 — 2026-11-03

- 적용 전 대상 flowdesk-local / 127.0.0.1:5432 / flowdesk, 데이터 5/2/5, V1 성공/checksum=-1384934132, 앱 USAGE/SELECT 미부여를 조회했다.
- infra/provision-app-read.ps1 최초/반복 실행 모두 READ_ONLY 성공. schema USAGE+세 업무 테이블 SELECT만 생겼으며 비밀번호를 유지했다.
- 업무 HTTP API를 추가하지 않고 build/local-jpa-verification의 임시 main을 새 앱 JAR에서 추출한 클래스/의존성으로 javac 컴파일했다. SpringApplication.run(FlowDeskApplication.class)로 임시 18081 서버를 실행해 실제 로컬 앱 계정 Repository 5개 계약을 호출했다.
- Alice의 A OWNER, Eve의 A 미소속/B OWNER·소속 Slice·없는 ID/이메일 empty를 확인했다. DataSource의 실제 current_user=flowdesk_app/current_database=flowdesk, ddl-auto=validate/OSIV=false, 관리자/migrator 환경값 미전달도 확인했다.
- 같은 앱 DataSource에서 0행 조건의 INSERT/UPDATE/DELETE, 트랜잭션 안의 DDL과 history SELECT가 모두 42501로 거부됐다. 검증 SQL은 롤백했으며 임시 객체는 0개였다. 시퀀스는 값을 소비하지 않고 권한 메타데이터로 USAGE/SELECT/UPDATE=false를 확인했다.
- 앱 내부의 직접 HTTP 요청으로 /actuator/health와 /api/health가 모두 200/UP이었다. 검증 완료 후 임시 앱을 종료했고 PostgreSQL은 running/healthy로 유지한다.
- READ-only에서 migration Info SUCCESS/Validate 성공/재실행 0건, seed Verify 성공/반복 Apply 0건을 확인했다. 데이터/ID/timestamps/history 전체 스냅샷·3개 자격증명 파일/V1의 SHA-256·부모 환경이 적용 전후 같았다.
- 앱/실행기 소스나 V1 변경이 없어 전체 빌드/JUnit/Vue 검증을 반복하지 않았다. 임시 main은 build 산출물이며 앱 JAR이나 제품 코드에 넣지 않았다. HTTP 업무 API/Service·로그인·사용자별 인가는 여전히 별도 범위다.

## 근거

- [Spring Data JPA 3.5 Repository 정의](https://docs.spring.io/spring-data/jpa/reference/3.5/repositories/definition.html): 필요한 메서드만 노출하는 계약.
- [Spring Data JPA 3.5 트랜잭션](https://docs.spring.io/spring-data/jpa/reference/3.5/jpa/transactions.html): 조회 트랜잭션과 Service 경계.
- [Spring Data JPA 3.5 쿼리/EntityGraph](https://docs.spring.io/spring-data/jpa/reference/3.5/jpa/query-methods.html): 연관 필드 조회와 필요한 fetch 계획.
- [Hibernate 6.6 사용자 안내](https://docs.hibernate.org/orm/6.6/userguide/html_single/): enum/시간/연관관계 매핑. 안내의 최신 6.6 패치와 프로젝트 BOM 버전은 구분한다.
- [PostgreSQL 17 권한/ACL 검사 함수](https://www.postgresql.org/docs/17/functions-info.html): effective privilege와 schema/table/column/default ACL 상태 확인.

관련 문서: [V1 모델](domain-model-v1.md), [seed](dev-seed.md), [계정/실행 경계](local-migration.md).
