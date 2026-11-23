# 개발용 seed 데이터와 실행 범위

- 날짜: 2026-11-03
- 상태: seed 실행기 구현·격리 검증 및 2026-11-03 실제 로컬 Apply·Verify 완료.
- 목표: Phase 1에서 사용자·워크스페이스·멤버십을 조회하고 이후 역할/팀 경계 시나리오를 구현할 작은 예제를 준비한다.
- 완료 기준: 데이터 관계, 실행 계정/대상, 최초·반복 실행, 실패 처리, 구현 검증 기준이 명확하다.
- 사용자 확정: 기존 flowdesk_migrator 재사용·로컬 수동 실행. 빈 업무 테이블에 최초 삽입, 완전히 같은 예제면 0건, 다른 데이터가 있으면 중단.

후속 로컬 업무 생성으로 현재 데이터는 5명/3개 공간/6개 멤버십이다. 원래 seed 5/2/5와 신규 Alice 소유 공간 ID 3을 보존했다. 따라서 seed Verify/Apply는 전체 예제와 다름으로 중단하는 것이 정상이다. 예제 변경·삭제·리셋/자동 병합으로 통과시키지 않는다. [첫 생성 적용 결과](workspace-create-api-v1.md)를 따른다.

## 데이터 구성

예제 이름·이메일은 개발 기본값이며 실제 사용자 정보가 아니다. DB 사용자인 flowdesk_app/flowdesk_migrator와 아래 업무 사용자 행은 서로 다른 개념이다.

| email | display_name | FlowDesk Demo A | FlowDesk Demo B |
|---|---|---|---|
| alice@example.test | Alice | OWNER | — |
| bob@example.test | Bob | ADMIN | — |
| carol@example.test | Carol | MEMBER | — |
| dave@example.test | Dave | VIEWER | — |
| eve@example.test | Eve | — | OWNER |

- 생성 결과: users 5행, workspaces 2행, workspace_memberships 5행. 각 워크스페이스의 OWNER는 정확히 1명이다.
- A의 네 역할을 비교하고, A에 속하지 않은 Eve가 B에만 속하는 관계를 확인할 수 있다. Alice~Dave도 B에는 속하지 않는다.
- 두 워크스페이스는 예제 안에서 서로 다른 이름을 사용한다. 제품의 워크스페이스 이름 중복 금지 정책을 추가하는 것은 아니다.
- 역할별 API 권한, 다른 팀 리소스 접근 차단은 후속 Service/API에서 구현·검증한다. 이 행들을 만들었다고 인가가 구현된 것은 아니다.
- 비밀번호·로그인·토큰·이메일 인증·MFA·초대 대기 데이터는 없다. Project·Task·Comment 등 V1 밖의 데이터도 만들지 않는다.

## 실행 계정과 코드 경계

```text
명시적 PowerShell seed 작업
→ Compose 프로젝트/루프백 URL/자격증명 검증
→ infra/.env.migrator의 필요한 키만 전용 자식 환경에 전달
→ Gradle 전용 JavaExec → Spring 없는 seed Java main
→ V1 이력/소유권 검증 → JDBC 트랜잭션 → 데이터 관계 확인 → 결과/종료 코드
```

- 대상은 기존 flowdesk-local / 127.0.0.1:5432 / flowdesk다. 격리 테스트에서는 고유 Compose 프로젝트와 임시 포트를 사용한다. 실제 포트는 infra/.env와 실행 중 서비스의 매핑으로 검증한다.
- seed도 기존 flowdesk_migrator를 사용한다. 관리자 비밀번호를 전달하거나 앱 계정의 schema/DML/sequence 권한을 추가하지 않는다. 새 계정·자격증명 파일을 만들지 않는다.
- migrator는 스키마 소유자라 DDL 권한도 있다. 전용 DML 계정보다 권한이 넓다는 대가를 받아들이고 로컬 수동 도구로 제한한 사용자 선택이다. 로컬 주소만으로 운영 데이터와의 절대 격리를 보장한다고 주장하지 않는다.
- 일반 IDE Run/bootRun/JAR, build/test, Flyway Migrate에서는 seed가 자동 실행되지 않는다. localhost HTTP seed 엔드포인트나 인증 우회도 만들지 않는다.
- V1은 그대로 유지한다. V2/R__ SQL, data.sql, Flyway 기본 migration 경로에 예제 INSERT를 넣지 않으며 history를 seed 실행 이력으로 사용하지 않는다.
- 기존 migration source set/의존성을 재사용하되 seed 전용 Java main·Gradle 작업으로 분리한다. 앱 런타임에 seed 코드/데이터/Flyway를 추가하지 않는다. 새 라이브러리나 JPA를 미리 도입하지 않는다.
- 작은 고정 예제는 seed 전용 Java 코드의 불변 목록으로 관리한다. 삽입과 비교가 같은 정의를 사용하며, 외부 입력으로 임의 SQL이나 데이터 파일을 받지 않는다.

## 실행 파일과 명령

아래 파일·작업을 구현했다. 실제 로컬 Apply는 격리 검증 후 사용자가 요청한 범위에서 실행한다.

| 항목 | 책임 |
|---|---|
| infra/seed-local.ps1 | 필수 Apply/Verify 동작과 대상 검증, 전용 자식 환경 전달 |
| infra/run-seed-gradle.ps1 | 자식 프로세스에서 Wrapper의 runSeed 호출, 종료 코드 전달 |
| backend/src/migration/java/com/flowdesk/seed/LocalSeedRunner.java | 예제 정의·V1 확인·JDBC 저장/비교, Spring 미사용 |
| backend/build.gradle의 runSeed | migration 클래스패스와 Java 21로 seed main 실행 |
| backend/src/test/java/com/flowdesk/seed/LocalSeedRunnerTest.java | Testcontainers에서 실제 Java main의 최초/반복/불일치/롤백/동시 실행 검증 |
| infra/verify-migration.ps1 -IncludeSeed | migration 회귀와 실제 PowerShell → Gradle → Java → PostgreSQL seed 전체 경로 검증 |

환경 검증/비밀 전달은 migration-common.ps1을 재사용한다. Invoke-MigrationProcess의 Kind는 Migration/Seed만 허용하며 각 Action과 자식 스크립트를 고정한다. 기존 migration 호출의 기본값은 유지한다. 임의 main/스크립트/Gradle 인수를 받는 범용 실행기가 아니다.

```powershell
# 2026-11-03 실제 로컬에 실행한 명령. 반복 Apply는 예제 전체가 같으면 0건이다.
cd D:\flowdesk-starter\infra
.\seed-local.ps1 -Action Apply
.\seed-local.ps1 -Action Verify
```

- Action은 필수이며 Apply/Verify만 허용한다. 생략 시 삽입하지 않는다.
- Apply는 조건에 따라 최초 삽입 또는 일치 상태 유지다. Verify는 예제 일치 여부를 조회·검증하며 빈 테이블이나 불일치는 실패 종료한다. Verify가 자동 삽입하지 않는다.
- reset/clean/force/repair, 임의 URL·사용자·SQL 전달 옵션을 제공하지 않는다. 출력은 결과·행 수·실패 분류만 포함하며 비밀번호와 전체 설정은 출력하지 않는다.

## 최초·반복 실행 규칙

전제는 V1만 성공 적용되고 현재 원본 SQL과 checksum 검증이 통과한 스키마다. 다른 버전/대기/실패 이력은 자동 migrate/baseline/repair하지 않고 중단한다. 후속 V2가 생기면 seed 호환 범위를 별도로 갱신한다.

| 세 업무 테이블의 상태 | Apply | Verify |
|---|---|---|
| 모두 비어 있음 | 5+2+5행을 한 트랜잭션으로 삽입 | 미준비로 실패, 삽입 없음 |
| 예제 전체와 일치 | 성공, 추가/수정 0건 | 성공 |
| 일부만 있거나 행 추가/수정/삭제, 잘못된 관계가 있음 | 변경 전 실패 | 실패 |

일치는 단순 행 수 비교가 아니다.

1. 정확히 5개의 정규화 이메일과 display_name이 일치한다.
2. workspace는 정확히 2개이며 예상 이름이 하나씩 있다. 이름을 제품의 유일키로 취급하거나 이름만으로 기존 데이터를 인수하지 않는다.
3. 실제 생성된 ID를 통해 연결한 email → workspace → role 관계 5개가 정확히 일치한다. 누락·추가 멤버십이 없고 각 OWNER는 정확히 1명이다.
4. ID의 숫자와 생성 시각은 고정 예제 값으로 비교하지 않는다. ID는 INSERT 시 DB가 생성하고 RETURNING으로 참조를 연결하며, 시간은 기존 DB 기본값을 사용한다.

기존 데이터를 삭제·갱신하거나 누락 행만 메우지 않는다. 이메일이 같아도 표시명/관계가 다르면 덮어쓰지 않는다. 사용자가 예제를 수정하거나 업무 데이터를 추가한 뒤 Apply가 중단되는 것은 의도한 동작이다. 초기화/새 예제 버전 적용은 별도 작업으로 다룬다.

## 트랜잭션과 동시 실행

- Apply는 JDBC의 한 트랜잭션 안에서 잠금 → 상태 확인 → 필요 시 사용자/워크스페이스/멤버십 삽입 → 전체 예제 및 OWNER 수 확인 → 커밋한다. 어느 단계든 실패하면 전체 데이터 변경을 롤백한다.
- 상태 판정 전에 users → workspaces → workspace_memberships 순으로 SHARE ROW EXCLUSIVE 테이블 잠금을 얻는다. 같은 seed의 중복 실행과 일반 INSERT/UPDATE/DELETE가 상태 판정 중 끼어드는 것을 막는다. 보통의 읽기 조회는 허용하지만 쓰기는 잠깐 대기하므로 업무 쓰기 작업이 없는 로컬 준비 단계에서 실행한다.
- seed 트랜잭션의 잠금 대기는 3초, SQL 실행은 10초로 제한한다. 사전 확인/Flyway 검증의 JDBC 연결 타임아웃은 3초, 소켓 대기는 15초다. 잠금 실패/시간 초과 시 종료하고 자동 반복 적용하지 않는다. 이는 전체 작업의 총 소요 시간 보장이 아니다.
- Apply는 업무 테이블 잠금 후 history에 SHARE 잠금을 잡아 V1 이력을 다시 확인한다. seed는 history를 갱신하지 않는다. 동시에 DDL/마이그레이션을 실행하는 용도로 사용하지 않는다.
- Verify는 읽기 전용 트랜잭션의 일관된 스냅샷에서 전체 관계를 확인한다. Apply 잠금을 얻거나 데이터를 바꾸지 않는다.
- DB identity 시퀀스 값은 롤백으로 연속성이 보장되지 않는다. 실패 후 ID 간격을 복구하려고 시퀀스를 재설정하거나 ID=1 같은 값을 강제하지 않는다.
- seed는 위 2개 예제의 OWNER 수를 검증한다. 향후 API에서의 OWNER 생성/이전/탈퇴 불변식과 인가 검증을 대신하지 않는다.

잠금 선택 근거: [PostgreSQL 17 명시적 잠금](https://www.postgresql.org/docs/17/explicit-locking.html), [LOCK 명령](https://www.postgresql.org/docs/17/sql-lock.html).

## 구현 완료의 검증 기준

1. 격리 PostgreSQL에 실제 V1·동일 역할 정책을 적용한 뒤 실제 실행기 경로를 검증한다.
2. 빈 DB Apply 12행(5/2/5), Verify 성공, 반복 Apply 0건이며 ID·timestamps·행 내용은 유지된다.
3. 표시명/역할 변경, 추가 행, 일부 행만 존재, 이름만 같은 별도 workspace, 다른 관계이면 실패하고 기존 데이터를 보존한다.
4. membership 저장 중 실패를 유도하면 사용자·workspace 삽입까지 롤백한다. 초기 사용자/공간이 있으면 그 데이터를 바꾸지 않는다. 시퀀스의 연속성은 검사하지 않는다.
5. 동시 Apply 두 개는 순차로 12행/0건 처리되거나 제한 시간 초과로 안전하게 실패한다. 중복 workspace·OWNER·사용자가 생기지 않는다. 일반 쓰기 잠금과의 경합/시간 초과도 격리 DB에서 검증한다.
6. V1 미적용/실패·다른 버전·checksum 불일치, 잘못된 Action/URL/사용자/자격증명, 예상 밖 소유권/권한을 거부하며 관리자 폴백을 하지 않는다.
7. 앱 계정의 기존 CONNECT-only 권한·Flyway history는 유지한다. 앱 JAR에는 seed 코드·데이터·자격증명이 들어가지 않고 부모 환경/자격증명 파일도 바뀌지 않는다.
8. 실제 로컬 seed 적용은 격리 검증 후 적용 대상·예상 행 수를 제시하고 사용자가 요청한 범위에서 실행한다. 이 문서 확정만으로 실제 INSERT를 수행하지 않는다.

## 구현과 검증

- 요구사항/모델의 Phase 1 seed·Phase 2 인증 분리, 이메일 정규화, OWNER 1명, 앱 CONNECT-only 정책과 V1 필드를 확인했다.
- Gradle의 runSeed는 기존 migration 클래스패스로 별도 main을 실행한다. test 작업은 migrationClasses를 컴파일하고 테스트 JVM에 그 클래스패스를 전달한다. 테스트는 Testcontainers/자식 JVM에서만 실행하며 로컬 seed 작업을 호출하지 않는다. 앱의 main 런타임은 유지한다.
- 설계 단계의 문서 표 검사와 별도로 실제 Java main을 실행하는 JUnit 31개가 통과했다. 처음에는 Testcontainers의 URL 형식이 실행기의 좁은 허용 범위와 달라 실패했고 테스트 대상을 127.0.0.1/쿼리 없는 URL로 수정했다. 실행기의 URL 제한을 완화하지 않았다.
- 최초/반복/Verify, 변경·추가·부분 데이터 보존, membership 중간 오류의 전체 롤백, 동시 Apply, 잠금 시간 초과, 입력/인증/스키마/이력/checksum/대기 버전 오류를 격리 DB에서 검증한다. 전체 빌드와 실제 스크립트 검증의 최종 결과는 [local-setup.md](local-setup.md)에 기록한다.
- 실행기 구현 검증 단계에서는 실제 로컬 seed를 넣지 않았다. 이후 사용자의 Apply·Verify 요청으로 아래 로컬 적용을 수행했다. 인증/인가·JPA/API는 별도 작업이다. 프론트 코드는 변경하지 않아 Vue 테스트를 반복하지 않는다.
- 구현 검증 결과: seed JUnit 31개 + 기존 31개 = 전체 62개 통과, build 성공. verify-migration.ps1 -IncludeSeed는 기존 migration 회귀 25개 + seed 경로 8개 = 33개 검증 통과 후 검증 리소스/자격증명 파일을 정리했다. 앱 JAR 경계 검사도 통과했다. 당시 실제 로컬은 읽기 전용 조회로 업무 행 0건·V1 성공 이력 1건을 확인했다.

```powershell
# backend에서 seed 테스트만 실행 (Docker 필요)
.\gradlew.bat test --tests com.flowdesk.seed.LocalSeedRunnerTest --no-daemon --no-configuration-cache --console=plain

# 저장소 루트에서 migration 회귀 + seed 실행기 전체 경로 검증
pwsh -NoProfile -NonInteractive -File infra/verify-migration.ps1 -IncludeSeed
```

## 실제 로컬 Apply·Verify — 2026-11-03

- 사용자 요청에 따라 flowdesk-local / 127.0.0.1:5432 / flowdesk에 기존 migrator로 실행했다. 적용 전 세 업무 테이블 0건과 V1 성공 이력·checksum·스키마 소유권을 확인했다.
- seed-local.ps1 -Action Apply: Seed rows inserted: 12. Verify: users=5 workspaces=2 memberships=5 성공. Apply 재실행: Seed rows inserted: 0.
- 실제 SQL에서도 데이터 표의 다섯 관계와 각 공간 OWNER 1명을 확인했다. Verify와 재실행 전후 전체 행의 ID·timestamps·값이 같았다.
- seed 적용 전후 Flyway history 전체와 당시 앱 권한은 동일했다. V1 checksum=-1384934132, success=true를 유지했다. 당시 앱은 CONNECT-only였고, 이후 별도 [JPA 읽기 권한 적용](jpa-read-model-v1.md)에서 schema USAGE+업무 3개 테이블 SELECT를 추가했다. 쓰기/history/sequence 권한은 계속 금지한다.
- 관리자·migrator·앱 자격증명 파일과 원본 V1의 SHA-256, 부모 프로세스 환경도 같았다. 새 자격증명/권한을 만들지 않았다. PostgreSQL은 running/healthy로 유지한다.
- 이번 실행은 코드 변경이 없어 전체 빌드/JUnit/Vue 검증을 반복하지 않았다. 앞선 격리 테스트의 성공과 이번 로컬 Apply/Verify 결과는 별도로 기록한다. seed는 여전히 로그인·API 인가 구현을 의미하지 않는다.

관련 문서: [첫 모델](domain-model-v1.md), [로컬 migration](local-migration.md), [로드맵](roadmap.md).


## Project 도입 이후 현재 검사 — 2026-11-05

현재 실행기는 V1/V2/V3 이력을 요구한다. seed 예제는 기존 5명/2개 공간/5개 멤버십이며 Project 예제는 추가하지 않는다. projects가 한 행이라도 있으면 Apply/Verify는 수정·보충·삭제 없이 mismatch로 종료한다. Apply는 projects도 같은 트랜잭션에서 잠가 검사와 동시 삽입의 틈을 막고 Verify는 읽기 전용 스냅샷으로 검사한다. 실제 로컬은 추가 Workspace 업무 데이터가 있어 이미 seed 전체 일치 조건을 만족하지 않으며 자동 초기화하지 않는다. [Project 계약](project-model-and-api-v1.md).
