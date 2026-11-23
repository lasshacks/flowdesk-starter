# 로컬 마이그레이션 실행과 자격증명 전달

- 날짜: 절차 확정 2026-11-02, 실행기 구현 2026-11-03
- 상태: 전용 실행기·프로비저닝 구현 및 2026-11-03 로컬 flowdesk DB V1 적용·검증 완료.
- 사용자 선택: 앱 부팅과 분리된 전용 작업, 앱과 분리된 migrator 자격증명.
- 완료 기준: 실행 주체·파일·권한·전달 경로를 분리하고 격리 PostgreSQL에서 재실행/실패 처리를 검증한다.

## 실행 경계

```text
평소 앱 실행
IDE Run Java / bootRun / 앱 JAR
→ backend/.env의 flowdesk_app
→ HTTP API와 DataSource health 검사

명시적인 마이그레이션 작업
PowerShell 전용 실행기
→ infra/.env.migrator 읽기
→ 마이그레이션 전용 자식 프로세스 환경
→ Gradle JavaExec → 독립 Java main → Flyway
→ flowdesk_migrator로 V1 실행 → 종료
```

별도 Spring Boot 애플리케이션이나 프레임워크를 만들지 않는다. 기존 Flyway Java API와 Gradle Wrapper를 사용하되 전용 source set/의존성으로 분리한다. main 앱의 런타임에 Flyway를 추가하거나 Spring 컨텍스트를 띄우지 않는다.

build·test·bootRun이 로컬 마이그레이션 작업에 의존하게 만들지 않는다. build/test는 계속 격리 PostgreSQL에서 검증하고 로컬 DDL은 명시적인 Migrate 동작에서만 실행한다.

## 파일과 역할

PowerShell 7과 JDK 21, Docker가 필요하다. 아래 파일·Gradle 작업을 구현했다. 실제 자격증명 파일은 프로비저닝을 명시적으로 실행할 때 생성한다.

| 항목 | 역할 / 보관 내용 |
|---|---|
| infra/.env | 현재 DB 초기 관리자 설정. 앱·migration 실행기에 전체 전달하지 않음 |
| backend/.env | 기존 flowdesk_app URL·사용자·비밀번호만 보관 |
| infra/.env.migrator | 신규 migrator 전용 URL·사용자·비밀번호. Git 제외 |
| infra/migrator.env.example | 설정 키와 비밀이 아닌 예시 값 |
| infra/provision-migrator.ps1 | 초기 관리자로 역할·스키마를 준비. V1 업무 테이블은 생성하지 않음 |
| infra/migrate-local.ps1 | Info/Validate/Migrate 중 명시한 동작을 전용 프로세스로 실행 |
| infra/migration-common.ps1 | 엄격한 파일/대상 검증, 자식 환경 생성, 프로세스 종료 코드 처리 |
| infra/run-migration-gradle.ps1 | 자식 PowerShell에서 Wrapper 실행; 내부 Inspect/Provision 동작도 전달 |
| infra/verify-migration.ps1 | 고유 Compose 프로젝트에서 실제 전체 실행 경로 검증 및 검증용 DB 정리 |
| backend/src/migration/java/.../LocalMigrationRunner.java | Spring 없는 작은 Java main. Flyway API 호출만 담당 |
| Gradle runMigration 작업 | migration source set과 전용 실행 클래스패스로 Java main 실행 |
| 기존 V1 SQL | 이미 작성·격리 검증한 main resources의 SQL을 전용 실행 클래스패스에도 제공 |

전용 migration 클래스패스에는 Boot BOM으로 관리하는 Flyway core/PostgreSQL 모듈·JDBC 드라이버와 V1 리소스만 추가한다. main 앱 JAR에 migrator 실행 코드·Flyway 라이브러리·자격증명을 넣지 않는다.

## 자격증명 형식과 전달

infra/.env.migrator의 키는 다음과 같다. 실제 비밀번호를 아래 예시로 설정하지 않는다.

```dotenv
FLOWDESK_MIGRATOR_DB_URL=jdbc:postgresql://127.0.0.1:5432/flowdesk
FLOWDESK_MIGRATOR_DB_USERNAME=flowdesk_migrator
FLOWDESK_MIGRATOR_DB_PASSWORD=replace-with-generated-local-password
```

- 기존과 동일하게 32바이트 난수로 별도 비밀번호를 생성한다. 관리자·앱 비밀번호를 재사용하지 않는다.
- 실제 파일은 기존 .gitignore의 `.env.*` 규칙으로 제외된다. 예시 파일은 실제 값 없이 별도 이름으로 추적한다.
- 실행기는 파일에서 위 세 키만 읽어 ProcessStartInfo의 자식 환경에 지정한다. 시스템/사용자 영구 환경변수나 부모 PowerShell의 `$env:`를 수정하지 않는다.
- 기본 PATH·JAVA_HOME 등 OS 실행 환경은 필요하지만, 관리자/앱 비밀번호를 새로 주입하지 않는다. migrator 키를 일반 앱 설정 파일이나 Spring config import에 추가하지 않는다.
- 자식 환경을 만들 때 상속된 FLOWDESK_·POSTGRES_·SPRING_·FLYWAY_ 설정과 PostgreSQL 비밀번호/서비스/옵션 설정을 제거한다. 그 후 이번 동작에 필요한 값만 넣는다. Inspect/Provision에는 관리자 자격증명도 필요하지만 Info/Validate/Migrate에는 전달하지 않는다.
- 전달 대상은 마이그레이션 프로세스 트리다. Gradle 클라이언트·필요한 일회성 빌드 프로세스·JavaExec JVM은 실행 중 값을 볼 수 있다. 앱 프로세스를 이 트리에서 실행하지 않는다.
- Gradle은 `--no-daemon --no-configuration-cache --console=plain`으로 실행한다. --no-daemon도 JVM 조건에 따라 일회성 프로세스를 만들 수 있으므로 “Gradle 프로세스가 전혀 없다”는 뜻은 아니다. 비밀번호를 장기 재사용 daemon이나 configuration cache에 보관하지 않는다.
- 비밀번호를 명령 인수·`-P`·`-D`·URL 쿼리·일반 SQL 파일·로그에 넣지 않는다. Java main은 환경값을 읽어 Flyway dataSource에 전달하며 전체 설정/환경을 출력하지 않는다.
- 누락·빈 값·잘못된 사용자·대상 URL 불일치면 관리자나 앱 자격증명으로 대체하지 않고 종료한다.

이 파일은 로컬 개발용 평문 파일이며 Git 제외가 암호화를 뜻하지 않는다. 같은 OS 사용자와 관리자가 읽을 수 있고, 프로세스 분리는 OS 보안 샌드박스가 아니다. 운영 비밀정보 보관 방식은 이 절차로 확정하지 않는다.

## 1단계 — 관리자 프로비저닝

provision-migrator.ps1은 아래 순서로 처리한다. 관리자 연결도 임의 SQL CLI 대신 전용 Java main의 JDBC를 사용한다.

1. 현재 Compose 프로젝트와 infra/.env의 DB명·포트를 확인하고 실행 중 서비스의 포트 매핑이 일치하는지 검사한다. 프로젝트 이름은 compose.yaml에서 제한된 형식으로 읽어 --project-name에 명시하므로 상속된 COMPOSE_PROJECT_NAME에 의해 다른 프로젝트가 선택되지 않는다. 127.0.0.1과 현재 Compose DB만 대상으로 한다.
2. 기존 역할·스키마·소유권·자격증명 파일 상태를 읽는다. 예상 밖 역할 속성이나 다른 소유자의 스키마가 있으면 자동 인수하지 않고 중단한다.
3. 최초라면 비밀번호를 생성해 전용 파일에 먼저 보관하고, 관리자 세션의 한 트랜잭션에서 역할과 스키마를 준비한다. 파일 기록과 DB 트랜잭션은 원자적이지 않으므로 DB 실패 시 파일을 유지해 같은 값으로 재시도할 수 있게 한다.
4. flowdesk_migrator는 LOGIN, NOSUPERUSER, NOCREATEDB, NOCREATEROLE, NOINHERIT, NOREPLICATION, NOBYPASSRLS로 만들며 다른 역할의 멤버십도 없어야 한다. FlowDesk DB CONNECT와 flowdesk 스키마 소유권만 부여한다. 기존 app-role.sql 정책으로 PUBLIC의 DB TEMP/CREATE와 public 스키마 권한이 닫혀 있어야 하며, 그렇지 않으면 다른 계정의 권한을 자동 변경하는 대신 중단한다.
5. 관리자가 flowdesk 스키마를 migrator 소유로 생성한다. migrator에 DB 전체 CREATE를 상시 부여하지 않는다. 업무 테이블과 flyway_schema_history는 이후 Flyway가 migrator로 만든다.
6. 앱 역할·기존 앱 비밀번호·V1 SQL·업무 데이터를 수정하지 않는다. 스키마의 PUBLIC 사용 권한이나 앱 DML 권한도 새로 열지 않는다.

재실행 상태별 처리:

| 상태 | 처리 |
|---|---|
| 파일·역할·스키마 모두 없음 | 새 파일 생성 후 역할·스키마 준비 |
| 파일은 있고 역할·스키마 없음 | 앞선 실패 또는 DB 재생성 상태인지 확인하고 기존 값으로 준비 |
| 파일·역할·스키마 있음 | 자격증명·소유권·권한 확인 후 유지, 비밀번호 자동 회전 금지 |
| 역할은 있는데 파일 없음 | 중단. 새 비밀번호로 기존 역할을 덮어쓰지 않음 |
| 스키마 소유권 또는 역할 속성이 예상과 다름 | 중단. 자동 ALTER OWNER·권한 확대 금지 |

프로비저닝 SQL은 관리자 연결에서 실행하지만 실제 비밀번호를 명령 인수나 저장 SQL에 넣지 않는다. JDBC PreparedStatement로 트랜잭션 한정 세션 변수에 전달한다. 동일 DB의 프로비저닝은 advisory transaction lock으로 직렬화하고 파일 생성에는 CreateNew를 사용해 덮어쓰기를 막는다. 일반 도구 실패 출력을 전달하지 않고 허용된 오류 분류만 표시한다.

## 2단계 — 대상과 상태 확인

- URL은 infra/.env에서 확인한 현재 DB와 포트의 루프백 URL이어야 한다. URL에 자격증명이나 임의 원격 주소를 포함하지 않는다.
- 실제 current_database·current_user와 schema 소유자를 확인한다. current_user는 반드시 flowdesk_migrator다.
- 스키마·역할·history가 예상과 다른 경우 중단한다. 관리자 또는 flowdesk_app으로 폴백하지 않는다.
- 새 schema가 비어 있고 history가 없으며 Info가 V1 Pending이면 첫 적용 후보로 본다.
- history 없이 기존 업무 테이블이 있거나 실패/불일치 이력이 있으면 중단한다. baseline·clean·repair로 정상 상태처럼 맞추지 않는다.

Info는 적용/대기 상태를 설명하며 checksum 일치 증명이나 dry-run DDL 검증을 대신하지 않는다.

## 3단계 — 명시적 마이그레이션

다음 명령을 2026-11-03 실제 로컬 flowdesk DB에 실행했다. 이후 새 DB 준비나 상태 확인에도 같은 실행기를 사용한다.

```powershell
cd D:\flowdesk-starter\infra
.\provision-migrator.ps1
.\migrate-local.ps1 -Action Info
.\migrate-local.ps1 -Action Migrate
.\migrate-local.ps1 -Action Validate
.\migrate-local.ps1 -Action Info
```

- Action은 필수이고 Info/Validate/Migrate만 허용한다. 값이 없을 때 Migrate로 기본 실행하지 않는다.
- Migrate는 Flyway의 적용 전 검증을 유지하고 대기 중인 V1을 적용한다. 완료 후 별도 Validate로 이력·파일 일치를 확인한다.
- 처음의 Pending은 아직 적용되지 않았다는 상태다. 최초 실행에서 무조건 독립 Validate가 통과해야만 Migrate하도록 구성하지 않는다. 독립 Validate는 미적용 버전을 실패로 판단할 수 있다.
- 최종 Info에서 V1 Success를 확인하고, 다시 Migrate하면 0건이어야 한다.
- defaultSchema/schemas=flowdesk, createSchemas=false, cleanDisabled=true, baselineOnMigrate=false, validateOnMigrate=true를 사용한다.
- clean/repair/baseline 명령과 무제한 추가 Flyway 인수 전달은 실행기에 제공하지 않는다.
- 성공 여부는 프로세스 종료 코드로 판단한다. 실패 시 뒤 단계로 자동 진행하지 않으며 “실행 프로세스 시작”을 “마이그레이션 성공”으로 보고하지 않는다.

## 4단계 — 적용 결과 확인

- flowdesk에 세 업무 테이블과 flyway_schema_history가 있어야 한다. 스키마·업무 테이블·이력의 소유자는 migrator다.
- V1 성공 이력·파일 checksum 검증과 재실행 0건을 확인한다. 개발 사용자·워크스페이스 행은 0건이어야 한다.
- 위 0건은 마이그레이션만 적용한 직후의 기대값이다. 개발 데이터는 [별도 seed 설계](dev-seed.md)에 따라 이후 명시적으로 준비하며 Flyway Migrate는 seed를 실행하지 않는다.
- 최초 CONNECT-only 단계에서는 앱의 업무 테이블 접근을 거부한다. 이후 승인된 [READ-only 단계](jpa-read-model-v1.md)에서는 3개 업무 테이블 SELECT만 허용하고 DML/DDL·sequence/history 접근은 계속 거부해야 한다. migration/seed 도구는 두 상태를 검증한다.
- 평소 앱의 DataSource는 계속 flowdesk_app이다. Actuator UP으로 V1 적용 여부를 판단하지 않는다. DB 접속은 테이블 없이도 가능하기 때문이다.
- V1 적용 후 원본을 수정하지 않는다. 후속 수정은 V2 이후로 추가한다.

## 실패 처리

| 실패 | 처리 |
|---|---|
| 비밀번호 파일 누락·인증 실패 | 적용 중단. 비밀번호 출력·관리자 폴백·자동 회전 금지 |
| Docker/DB 미실행·연결 시간 초과 | 대상과 기동 상태 확인 후 같은 작업 재실행 |
| URL·DB·사용자·schema 소유권 불일치 | 적용 전 중단 |
| history와 SQL checksum 불일치 | 중단 후 원본/버전 확인, repair 자동 실행 금지 |
| DDL 실패 | 오류·history·실제 객체 상태 확인. 무조건 롤백 성공이나 재실행 가능하다고 가정하지 않음 |
| Gradle/Java 실행 실패 | 자식 종료 코드와 비밀이 없는 로그 확인. 앱 DB 계정 권한을 넓혀 해결하지 않음 |

프로세스 종료 후 부모 셸의 환경이 원래 상태인지 확인한다. 파일의 삭제·비밀번호 회전·계정 폐기는 별도 요청 없이 자동 수행하지 않는다.

## 구현 검증 기준

1. 실제 로컬 DB에 적용하기 전 격리 PostgreSQL에서 프로비저닝·전용 실행기 전체 경로를 검증한다.
2. 최초 적용·재실행 0건·checksum 실패·권한/소유권 및 앱 접근 거부를 확인한다.
3. 없는 자격증명·잘못된 사용자/URL·잘못된 Action이 DB 변경 전에 거부되는지 확인한다.
4. 일반 bootRun/JAR에 migrator 환경값·Flyway runtime·전용 runner가 섞이지 않는지 확인한다.
5. 비밀번호가 명령행·콘솔·예외 메시지·설정 캐시·실행 JAR에 기록되지 않고 부모 셸 환경도 바뀌지 않는지 확인한다.
6. 검증된 결과와 로컬 적용 대상을 제시하고, 로컬 실행을 요청받은 범위에서 적용·확인한다.

## 절차 확정 당시 확인 — 2026-11-02

- 기존 build.gradle은 Flyway를 테스트 의존성으로만 사용하며 일반 앱 런타임에는 추가하지 않았다.
- Spring의 config import는 앱의 .env 두 경로만 지정하며 infra/.env.migrator는 읽지 않는다.
- .gitignore의 .env.* 규칙이 존재하고 현재 infra/.env.migrator는 없다.
- 당시에는 문서만 변경했다. 아래 실행기 구현과는 별도 기록이다.

## 실행기 구현 — 2026-11-03

- migration source set은 Spring을 포함하지 않고 Boot BOM의 Flyway 11.7.2와 PostgreSQL 드라이버를 사용한다. main 앱의 의존성·부팅 경로는 유지한다.
- Java main은 고정 loopback URL·사용자·역할 속성·스키마 권한/소유권·객체 소유권을 검사한다. history 없이 객체가 있는 스키마를 거부한다. Info는 Pending/Success 이외 이력 상태를 거부하며 checksum 검증은 Validate/Migrate의 책임이다.
- 별도 JVM은 환경을 상속하므로 단순히 파일 이름만 분리하지 않고 자식 환경에서 기존 앱/DB 설정을 제거한다. 부모 환경은 수정하지 않는다.
- 다음 명령은 고유 Compose DB와 임시 포트에만 적용하고 종료 시 해당 검증 프로젝트의 볼륨까지 정리한다. 실제 infra/.env·backend/.env·로컬 DB를 읽거나 수정하지 않는다.

```powershell
# 저장소 루트에서
pwsh -NoProfile -NonInteractive -File infra/verify-migration.ps1
```

- 검증은 파일/트랜잭션 실패 재시도, 최초/반복 적용, 권한 거부, 잘못된 입력/인증, 소유권/역할 변조, checksum 불일치를 실제 래퍼 경로로 실행한다. 자식의 상속 환경에 잘못된 앱/관리자 비밀번호와 COMPOSE_PROJECT_NAME을 넣어도 제거되거나 명시적인 대상이 우선하는지 확인한다. 검증용 자격증명 파일도 정리한다.
- 실행기 격리 검증 단계에서는 실제 로컬 migrator 파일·역할·스키마를 생성하지 않았다. 이후 사용자가 로컬 적용을 요청해 아래 단계로 진행했다. 개발 seed·JPA·업무 API는 별도 작업이다.
- 최종 검증 결과: verify-migration.ps1 종료 코드 0, 25개 검증 통과, 검증 프로젝트/볼륨과 자격증명 파일 정리 완료. 별도 build/migrationClasses --rerun-tasks도 성공했고 JUnit 31개가 통과했다. 실제 명령과 검사 범위는 [local-setup.md](local-setup.md)에 기록한다.

## 실제 로컬 적용 — 2026-11-03

- 사용자의 명시적 로컬 적용 요청에 따라 flowdesk-local / 127.0.0.1:5432 / flowdesk를 대상으로 실행했다. 기존 PostgreSQL 볼륨을 사용했다.
- provision-migrator.ps1이 Git 제외 infra/.env.migrator와 flowdesk_migrator 역할·flowdesk 스키마를 준비했다. 스키마와 V1의 3개 업무 테이블·이력 테이블은 migrator 소유다.
- Info: V1 PENDING → Migrate: 1건 → Validate: 성공 → Info: V1 SUCCESS → Migrate 재실행: 0건. history의 installed_by=flowdesk_migrator, checksum=-1384934132, success=true를 확인했다.
- 업무 데이터는 세 테이블 모두 0건이다. 제약조건 11개와 history PK, OWNER 부분 UNIQUE 및 사용자별 멤버십 조회 인덱스도 확인했다.
- 앱 계정의 실제 TCP 인증/SELECT 1 성공, 업무·이력 테이블 SELECT/INSERT/UPDATE/DELETE/TRUNCATE 권한 모두 false. 실제 SELECT·DDL도 42501로 거부됐다. 검증 트랜잭션은 롤백했다.
- migrator와 앱 모두 DB CONNECT만 허용하며 DB CREATE/TEMP·관리자 속성은 없다. migrator는 자신의 flowdesk 스키마 안에서 DDL을 수행한다.
- 마이그레이션 전후 관리자·앱·migrator 파일 및 원본 V1의 SHA-256이 같고 부모 프로세스 환경도 같음을 확인했다. V1은 이제 적용 이력의 원본이므로 수정은 V2 이후로 추가한다.
- 기존 8080 서버가 없어 JAR을 임시 18081에서 검증했다. Actuator와 /api/health 모두 HTTP 200/UP, pg_stat_activity에서 flowdesk_app JDBC 세션 확인 후 임시 앱을 종료했다. PostgreSQL은 healthy로 유지했다.
- 이번에는 실행기·V1·앱 코드 변경이 없어 전체 빌드/JUnit/Vue 테스트는 반복하지 않았다. 앞선 31개/25개 검증 결과와 이번 실제 로컬 적용 결과를 구분한다.

## 참고

- [Gradle JavaExec](https://docs.gradle.org/current/dsl/org.gradle.api.tasks.JavaExec.html).
- [Flyway Validate](https://documentation.red-gate.com/fd/validate-277578898.html).
- 기존 V1 내용과 검증 결과: [domain-model-v1.md](domain-model-v1.md), [backend 실행 안내](../backend/README.md).


## V2와 이름 수정 권한 구현 — 2026-11-04

새 V2는 workspaces.version을 추가한다. 전용 migrator로 적용한 뒤 별도 `infra/provision-workspace-rename.ps1`에서 최소 컬럼 UPDATE를 적용해야 새 쓰기 앱을 실행할 수 있다. V1과 기존 자격증명은 유지한다. 실제 로컬은 아직 V1이며 이번 작업에서는 격리 DB에서만 검증했다(Java 245개/실제 Vite 1개·PowerShell 전체 60개). 앱을 먼저 재기동하면 JPA validate가 실패할 수 있다. 실제 적용 명령/계약은 [수정 계약](workspace-rename-api-v1.md), 결과는 [실행 기록](local-setup.md)을 따른다.


## 실제 로컬 V2·수정 권한 적용 — 2026-11-04

이후 사용자 요청으로 V2 1건과 Validate/재실행 0건, provision-workspace-rename.ps1의 최초/반복 WORKSPACE_RENAME 적용을 완료했다. V1/history/자격증명을 보존하고 name/updated_at/version 컬럼 UPDATE만 허용한다. 새 JAR에서 실제 이름 수정 200/버전 1과 stale 409, 역할별 거부·쓰기 비활성 405를 확인했다. 데이터는 5/3/6이며 기존 비-seed 공간 ID 3의 이름/수정 시각/버전만 변경했다. 검증 서버는 종료했고 기본 쓰기는 비활성이다. [실제 결과](local-setup.md), [계약](workspace-rename-api-v1.md).


## V3·Project 권한 실행기 — 2026-11-05

V3는 projects와 공간별 목록 인덱스를 추가한다. 실제 로컬 적용은 별도 단계이며 현재 로컬 이력은 V1/V2다. 새 앱은 기본 프로필도 JPA validate를 위해 V3가 필요하고, dev-actor는 projects SELECT, 쓰기 모드는 INSERT까지 필요하다. 앱 중지 → 전용 migrator Migrate/Validate → `infra/provision-project-create.ps1` → 새 앱 순서로 진행한다. 사용자/공간 seed에 프로젝트를 추가하지 않으며 projects 데이터가 있으면 seed Apply/Verify는 mismatch로 중단한다. [전체 계약/명령](project-model-and-api-v1.md).


## 실제 로컬 V3·Project 권한 적용 — 2026-11-05

이후 사용자 요청으로 V3 한 건/Validate·재실행 0건과 `provision-project-create.ps1`의 최초/반복 PROJECT_CREATE를 완료했다. 새 앱의 첫 Project ID 1 생성 201/조회 200, 역할별 접근 차단·쓰기 off와 Actuator UP을 확인했다. 기존 5/3/6과 V1/V2/ACL/시퀀스를 보존하고 projects SELECT/INSERT만 추가했다. 현재 로컬 이력은 V1/V2/V3이며 검증 앱은 종료했다. [실제 결과](local-setup.md), [Project 계약](project-model-and-api-v1.md).


## V4·Project UPDATE 구현 — 2026-11-05

V4는 projects.version bigint NOT NULL DEFAULT 0/비음수 CHECK를 추가한다. 실제 로컬은 V3/PROJECT_CREATE로 유지했으며 이번에는 격리 DB에서만 적용/수정한다. 새 앱은 @Version 때문에 V4가 필요하고, 쓰기 모드는 Project 네 컬럼 UPDATE를 요구한다. 후속 실제 순서는 앱 중지→전용 migrator V4/Validate→provision-project-update.ps1→새 앱이다. 기존 V1~V3/자격증명을 보존하며 seed를 초기화하지 않는다. [수정 계약](project-update-api-v1.md), [검증 기록](local-setup.md).


## 실제 로컬 V4·Project UPDATE 적용 — 2026-11-07

사용자 요청으로 기존 flowdesk DB에 V4 1건/Validate/재실행 0건과 `provision-project-update.ps1` 최초·반복 PROJECT_UPDATE를 완료했다. 네 컬럼 UPDATE만 추가했으며 기존 자격증명/V1~V3/소유권·이전 ACL·데이터/시퀀스를 보존했다. 새 JAR로 Project 1의 이름·설명을 한 번 수정해 200/version 1을 확인하고 stale 409·ADMIN no-op·역할/부모/Origin/쓰기 off 경계를 검증했다. 최종 데이터 5/3/6/1이며 검증 앱은 종료했다. seed는 실행하지 않았다. 새 앱은 현재 로컬 V4와 호환되고 개발 쓰기는 기존 명시적 flag가 여전히 필요하다. [실제 명령/결과](local-setup.md), [수정 계약](project-update-api-v1.md).


## V5·Task 권한 도구 구현 — 2026-11-07

V5는 tasks/부모 Project FK·입력 길이·상태/우선순위 CHECK/목록 인덱스만 추가한다. 일반 앱에는 migration/GRANT를 연결하지 않는다. 현재 이력/최신 fixture는 1~5, 기존 과거 migration 검증은 각 target을 유지한다. 후속 실제 순서는 앱 중지→migrate-local.ps1 Info/Migrate/Validate→provision-task-create.ps1→새 앱이다. 새 Task 매핑은 V5가 필요하고 dev-actor 조회에는 tasks SELECT, 쓰기는 INSERT와 기존 최소 정책까지 필요하다. 이번에는 격리 DB에서만 검증하며 실제 로컬 V4/PROJECT_UPDATE·5/3/6/1은 유지한다. [계약](task-model-and-api-v1.md), [명령/실제 결과](local-setup.md).


## 실제 로컬 V5·Task 권한 적용 — 2026-11-08

사용자 요청으로 기존 flowdesk DB에 V5 1건/Validate/재실행 0건과 `provision-task-create.ps1` 최초·반복 TASK_CREATE를 적용했다. tasks SELECT/INSERT만 추가했고 기존 V1~V4/소유권·ACL/데이터/시퀀스와 자격증명을 보존했다. 새 JAR에서 MEMBER Carol의 첫 Task ID 1 생성 201/TODO·MEDIUM과 네 역할 조회·권한/부모·출처·쓰기 off 거부를 확인했다. 최종 데이터는 사용자/공간/멤버십/Project/Task **5/3/6/1/1**이고 부모 Project 1 version은 1 그대로다. seed는 실행하지 않았다. 현재 로컬은 V5/새 앱과 호환되며 검증 서버는 종료했다. [실제 명령/검증문 수정·결과](local-setup.md), [Task 계약](task-model-and-api-v1.md).


## 후속 V6·Task UPDATE 설계 — 2026-11-08

[Task 수정 계약](task-update-api-v1.md)에 V6__add_task_version.sql의 version bigint NOT NULL DEFAULT 0/비음수 CHECK와 별도 TASK_UPDATE 다섯 컬럼 UPDATE를 설계했다. 기존 Task 상태/부모·시간·시퀀스/ACL/V1~V5/자격증명을 보존한다. 권한 도구·기동 검사/current 이력 1~6·기존 모든 도구의 새 상태 보존·과거 target/미래 fixture 분리가 후속 구현 범위다. 아직 SQL/도구는 없으며 실제 로컬 V5/TASK_CREATE를 변경하지 않았다. 구현·격리 검증 뒤 별도 실제 적용은 앱 중지→전용 migrator V6/Validate→Task UPDATE 도구→새 앱 순서다. 앱 부팅 자동 migrate/GRANT는 추가하지 않는다.


## V6·Task UPDATE 도구 구현 — 2026-11-08

V6__add_task_version.sql과 `provision-task-update.ps1`→전용 `runAppTaskUpdateAccess`/AppTaskUpdateAccessRunner를 구현했다. TASK_CREATE→TASK_UPDATE에서 tasks UPDATE(title,description,priority,updated_at,version)만 부여하며 기존 비밀번호/권한과 완전한 새 상태를 보존한다. 부분/과도/PUBLIC/GRANT OPTION은 자동 수선하지 않는다. 이전 도구·기동 정책/current 이력 1~6·seed/과거 target도 전환한다.

실제 로컬은 V5/TASK_CREATE다. 새 앱의 Task @Version은 V6가 필요하다. 격리 검증 뒤 별도 실제 적용 순서는 앱 중지→migrate-local.ps1 Info/Migrate/Validate→provision-task-update.ps1→새 앱이다. 앱 부팅 자동 migration/GRANT는 하지 않는다. [계약](task-update-api-v1.md), [실제 검증 결과](local-setup.md).


## 실제 로컬 V6·Task UPDATE 적용 — 2026-11-08

사용자 요청으로 기존 flowdesk DB에 V6 1건/Validate·V1~V6 SUCCESS/재실행 0건과 provision-task-update.ps1 최초·반복 TASK_UPDATE를 적용했다. 기존 자격증명·V1~V5 설치 행/데이터/ACL/시퀀스를 유지하고 Task version 0·정확한 다섯 컬럼 UPDATE만 추가했다. 새 JAR의 MEMBER 수정 200/version 1/HIGH·TODO 유지·stale 409·OWNER/ADMIN no-op/역할·부모·출처·쓰기 off·health도 확인했다. 최종 5/3/6/1/1이며 Task 1 다섯 필드만 실제 변경했고 부모 Project 1 version 1/나머지는 보존했다. 검증 앱은 종료했으며 새 앱은 현재 로컬 V6/필요 권한과 호환된다. [실행 기록](local-setup.md), [계약](task-update-api-v1.md).


## 후속 V7·상태 변경/최소 Activity 설계 — 2026-11-08

[상태 계약](task-status-api-v1.md)에 V7__create_task_status_activities.sql의 빈 이력 테이블·FK/상태 CHECK/from<>to/version>0/Task+version UNIQUE를 설계했다. 과거 Task 상태/시간을 이력으로 backfill하지 않고 V1~V6 SQL/기존 데이터·ACL/이력·시퀀스를 보존한다. TASK_STATUS_CHANGE 전용 도구는 기존 TASK_UPDATE에서 status UPDATE 한 컬럼과 이력 여섯 입력 컬럼 INSERT만 추가한다. 이력 SELECT/id INSERT/전체 INSERT/UPDATE·DELETE/직접 sequence 권한은 주지 않는다.

현재 이력/seed/current fixture 1~7·이력 잠금/빈 조건·기존 도구 보존/기동 검사·과거 target 6/미래 V8 fixture가 후속 구현 범위다. 실제 적용은 구현·격리 검증 뒤 별도 요청으로 앱 중지→V7/Validate→상태 변경 최소 권한 도구→새 쓰기 앱 순서다. 이번에는 SQL/도구/DB를 변경하지 않았다. 실제 로컬은 V6/TASK_UPDATE다.

### V7·상태 변경 실행기 구현 — 2026-11-10

현재 코드의 migration history/seed·권한 도구는 V1~V7을 요구한다. V7은 빈 task_status_activities/FK/CHECK/UNIQUE만 만들고 V1~V6·기존 Task 상태/시간/버전·ACL을 보존한다. 이후 별도 TASK_STATUS_CHANGE 도구로 status UPDATE·이력 여섯 컬럼 INSERT를 부여한다. 실제 로컬은 V6 상태를 유지하며 이번에는 격리 DB만 변경했다. 새 쓰기 앱 기동 전 V7/최소 권한 적용이 필요하다. 순서와 명령은 [구현 기록](task-status-implementation.md)을 따른다.

### 실제 로컬 V7/최소 상태 권한 완료 — 2026-11-10

별도 요청으로 실제 로컬에 V7 한 건을 적용하고 Validate/반복 0건·권한 도구 최초/반복을 완료했다. Task 상태 UPDATE와 이력 고정 여섯 입력 컬럼 INSERT만 새 권한이며 앱의 이력 조회/시퀀스·DDL 권한은 없다. 새 JAR의 HTTP로 Task 1 상태/버전 1→2와 이력 1건을 확인했다. 이전 마이그레이션·데이터/ACL/시퀀스·자격증명을 보존했고 앱 부팅이 migration/GRANT를 수행하지 않았다. [기록](local-setup.md).

### 상태 이력 조회 권한 전환 계획 — 2026-11-10

확정된 [조회 계약](task-status-history-read-api-v1.md)은 기존 V7을 재사용해 새 migration 없이 audit SELECT 7개 컬럼만 별도 도구로 추가하는 범위다. 기존 쓰기 기동의 SELECT 금지·읽기 기동/이전 관리자 도구를 함께 전환해야 한다. 현재 로컬 TASK_STATUS_CHANGE에는 아직 이 SELECT가 없고 이번에는 실제 DB 명령을 실행하지 않는다. 후속 구현/격리 검증 후 별도 로컬 요청에서 새 권한 적용→새 앱 읽기/쓰기 확인 순서로 진행한다.
