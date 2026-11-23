# FlowDesk 백엔드

> 2026-11-04 실제 로컬에 V2(version 컬럼)와 WORKSPACE_RENAME 최소 컬럼 UPDATE를 적용하고 새 JAR의 조회·수정·버전 충돌을 확인했다. 데이터는 5·3·6이며 기본 쓰기는 계속 비활성화다. 아래 개발 쓰기·Origin 설정을 명시한 실행에서만 생성/수정이 활성화된다. [수정 계약 및 적용 기록](../docs/workspace-rename-api-v1.md)을 따른다.


Java 21, Spring Boot 3.5.16, Gradle Wrapper 8.14.3을 사용한다. V1/V2·seed와 조회/최소 INSERT·세 컬럼 UPDATE를 로컬에 적용하고 Vue/HTTP 조회·첫 공간 생성·이름 수정/버전 충돌도 확인했다. 기본 쓰기는 비활성화이며 실제 로그인은 Phase 2다.

## 실행

먼저 [로컬 DB 안내](../infra/README.md)에 따라 PostgreSQL을 실행하고 앱 계정을 준비한다.

```powershell
cd D:\flowdesk-starter\infra
.\provision-app-db.ps1
```

이 명령은 승인된 최소 권한 정책을 적용하고 앱 전용 비밀번호를 `backend/.env`에 저장한다. 관리자용 `infra/.env`와 분리하며 실제 비밀번호는 출력하지 않는다. Spring은 명시한 config import로 backend/.env를 properties 형식으로 읽는다. OS 환경변수 FLOWDESK_DB_URL·FLOWDESK_DB_USERNAME·FLOWDESK_DB_PASSWORD로도 지정할 수 있다. `.env`가 Spring에서 자동으로 인식되는 파일명인 것은 아니다.

저장소 루트의 PowerShell에서:

```powershell
cd backend
.\gradlew.bat bootRun
```

기본 주소는 `http://127.0.0.1:8080`이다. 로컬 개발 단계이므로 루프백 주소에만 바인딩한다. 종료는 실행한 터미널에서 `Ctrl+C`를 누른다.

다른 터미널에서:

```powershell
$response = Invoke-WebRequest -Uri 'http://127.0.0.1:8080/api/health'
$response.StatusCode
$response.Content
```

예상 결과는 HTTP `200`, `Content-Type: application/json`, 본문 `{"status":"UP"}`이다. 기본 포트를 다른 프로그램이 사용한다면 ` .\gradlew.bat bootRun --args="--server.port=8081"`로 포트를 바꾸고 요청 URL도 맞춘다.

## Actuator health와 비교

기본 실행에서는 업무 조회 API를 등록하지 않아 /api/workspaces는 404다. 개발 조회는 아래 별도 절차를 따른다.

Actuator 의존성 추가 후에는 기존 서버를 `Ctrl+C`로 종료하고 `bootRun`으로 다시 실행해야 한다. 아래 경로는 새로 기동한 앱에서 사용할 수 있다.

```powershell
$headers = @{ Accept = 'application/json' }
foreach ($path in @('/api/health', '/actuator/health')) {
    $response = Invoke-WebRequest -Uri "http://127.0.0.1:8080$path" -Headers $headers
    Write-Output "$path : $($response.StatusCode) / $($response.Headers['Content-Type']) / $($response.Content)"
}
```

두 경로 모두 정상 상태에서 HTTP 200과 `{"status":"UP"}`을 반환한다. `/api/health`는 직접 작성한 Controller의 고정값이다. `/actuator/health`는 이제 DataSourceHealthIndicator를 포함해 실제 DB 연결을 검사한다. DB가 응답하지 않으면 Actuator는 HTTP 503과 `{"status":"DOWN"}`을 반환하지만 기존 Controller는 200/UP을 반환한다. Vue 화면은 여전히 Controller 경로를 사용하므로 DB 상태 표시 화면으로 해석하지 않는다.

`application.properties`에서 HTTP 노출 대상을 `health`로 한정하고 `show-details=never`로 세부 상태를 숨긴다. 기존 127.0.0.1 바인딩을 유지한다. `/actuator/metrics`는 HTTP 404다. 인증 설정은 아직 추가하지 않았다.

Actuator는 요청의 Accept에 따라 `application/json` 또는 `application/vnd.spring-boot.actuator.v3+json` 같은 JSON 미디어 타입으로 응답할 수 있다. 위 명령과 테스트는 비교를 위해 `Accept: application/json`을 지정한다.

### Actuator만 404가 나는 경우

`/api/health`는 200인데 `/actuator/health`만 404라면 실행 중인 앱이 Actuator 의존성을 포함했는지 확인한다. build.gradle에 의존성이 있어도 IDE 실행 클래스패스가 갱신되지 않으면 반영되지 않을 수 있다. 서버 재시작만으로 해결되지 않는 경우 IDE의 Gradle 프로젝트 동기화가 필요하다.

2026-11-02에는 IDE가 생성한 Java 실행 인수 파일에 Actuator가 없음을 확인했다. Actuator가 포함된 최신 JAR로 실행하자 동일한 8080 포트에서 health 200이 반환됐다. 이런 경우 기존 서버를 종료한 뒤 `gradlew.bat bootRun`으로 실행하면 Gradle의 현재 의존성 설정을 기준으로 기동할 수 있다. 다른 Java 프로세스를 일괄 종료하지 않는다.

초기에는 java.configuration.updateBuildConfiguration을 automatic으로, Gradle JDK를 환경변수 참조로 설정했다. 2026-11-03 IDE 실행 문제 조사 뒤에는 설치된 JDK 21 경로를 java.import.gradle.java.home에 명시했다. JDK 설치 위치/버전이 바뀌면 .vscode/settings.json도 갱신한다. 언어 서버 자체의 Java와 Gradle 실행 Java는 구분한다.

변경 후 Java 언어 서버 로그의 `Updated flowdesk-backend-backend`와 IDE 내부 Gradle 클래스패스 모델의 Actuator JAR 반영을 확인했다. 초기 가져오기 로그에는 확장에 내장된 Java 25로 Gradle 8.14.3을 실행해 `Unsupported class file major version 69`로 실패한 기록도 있었다. Gradle을 실행하는 JVM과 애플리케이션의 Java toolchain은 별개다.

이후 사용자가 IDE의 Run Java로 기동한 새 프로세스에서 클래스패스에 Actuator와 자동 설정 JAR가 포함된 것을 확인했고, 같은 프로세스의 `/actuator/health`가 HTTP 200을 반환했다. JAR 우회 실행뿐 아니라 IDE 실행에서도 해결됐음을 검증했다.

## 빌드와 테스트

```powershell
.\gradlew.bat --version
.\gradlew.bat clean build --no-daemon --console=plain
```

최초 실행은 Gradle 배포본과 Maven Central/Gradle Plugin Portal의 의존성을 다운로드한다. 네트워크가 필요하며 사용자 Gradle 캐시에 저장된다. 이후 Gradle을 전역 설치할 필요는 없다. Wrapper 배포본은 설정한 SHA-256으로 검증한다.

`HealthApiTest`는 임의 포트에 실제 내장 서버를 띄워 다음 계약을 확인한다.

- GET `/api/health`: HTTP 200, JSON 타입, `status` 필드 하나와 값 `UP`.
- POST `/api/health`: HTTP 405. 조회 API에는 POST를 허용하지 않는다.
- GET `/actuator/health`: Accept가 application/json일 때 HTTP 200, JSON 응답과 UP, 세부 상태 비노출.
- GET `/actuator/metrics`: HTTP 404로 노출 제한 확인.

기존 HealthApiTest 4개는 DataSource 자동 설정을 제외한 HTTP 계약 테스트다. DatabaseHealthTest 3개는 별도 Testcontainers PostgreSQL을 사용한다. 전체 빌드·테스트에는 실행 가능한 Docker Linux 엔진이 필요하며, Docker가 없다고 DB 테스트를 조용히 건너뛰지 않는다.

- 테스트 DB에도 실제 `infra/app-role.sql` 정책을 적용한 flowdesk_app 계정으로 JDBC 연결 및 SELECT 1을 확인한다. 관리자·CREATE·TEMP 권한 부재와 테이블 생성 거부도 검증한다.
- 등록된 db contributor가 실제 DataSourceHealthIndicator인지 확인하고, HTTP 응답은 status만 포함하는지 검사한다.
- 테스트용 컨테이너를 pause해 503/DOWN을 확인하고 unpause 후 200/UP 복구를 확인한다. 로컬 Compose DB나 기존 사용자 데이터를 장애 실험에 사용하지 않는다.

2026-11-02 실행 결과: `gradlew.bat build --no-daemon --console=plain` 성공, 테스트 총 7개 통과(실패·오류·건너뜀 0). 장애 테스트 중 JDBC/상태 검사 경고는 의도한 결과다.

테스트 결과: `build/reports/tests/test/index.html`.
실행 가능한 JAR: `build/libs/flowdesk-backend-0.0.1-SNAPSHOT.jar`.

```powershell
java -jar build/libs/flowdesk-backend-0.0.1-SNAPSHOT.jar
```

macOS/Linux에서는 같은 인수와 함께 `./gradlew`를 사용한다. 체크아웃 방식에 따라 최초 한 번 `chmod +x gradlew`가 필요할 수 있다.

## V1 Flyway 격리 검증

IDE에서 DataSource URL 누락/SQLState 42501이 나면 DB 권한부터 늘리지 않는다. 실행 클래스패스의 main 설정 리소스와 Gradle JDK를 먼저 확인한다. 2026-11-03에는 source root 중복·Java 25 캐시로 가져오기가 실패한 문제를 수정했고, 재가져오기 후 IDE 클래스패스에 포함된 Flyway가 앱에서 DDL을 시도하지 않도록 application.properties에 spring.flyway.enabled=false를 명시했다. 최신 IDE 인수 파일과 FlowDeskApplication으로 실제 읽기 기동/HTTP 정상 응답을 재확인했다. 앱에는 DB CREATE/관리자 권한을 주지 않는다.

V1 SQL은 `src/main/resources/db/migration/V1__create_identity_and_workspace_tables.sql`이다. 확정한 세 테이블·제약·인덱스만 포함하며 DB 역할·스키마 생성·개발 seed·앱 CRUD 권한은 넣지 않는다.

```powershell
.\gradlew.bat test --tests com.flowdesk.migration.V1MigrationTest --no-daemon --console=plain
```

테스트는 Testcontainers PostgreSQL 17.11을 실행하고, 테스트 케이스마다 새 DB와 flowdesk 스키마를 준비한다. 초기화 관리자와 별도로 만든 비관리자 flowdesk_migrator가 스키마를 소유하고 Flyway를 실행한다. 앱에는 기존 CONNECT 권한만 적용한다. 테스트 자격증명은 매 실행 메모리에서 생성하며 로컬 .env를 사용하지 않는다.

Flyway 11.7.2 core와 PostgreSQL 모듈은 Boot BOM으로 관리하는 **테스트 의존성**이다. 기존 Spring 통합 테스트에서는 spring.flyway.enabled=false로 자동 실행을 제외하고, V1MigrationTest가 Java API로 명시적으로 실행한다. Flyway Gradle 플러그인이나 CLI를 추가한 것은 아니므로 `flywayMigrate` 작업이 생기지는 않는다.

- 신규 V1 적용 1건, 재실행 0건, schema history와 소유권, 초기 사용자 데이터 0건.
- identity ID·시간 기본값, NOT NULL, 정규화 이메일/CHECK, 중복 이메일/가입, FK와 역할, 단일 OWNER 부분 UNIQUE, 부모 삭제 RESTRICT.
- 앱 계정의 업무 테이블 읽기/쓰기·DDL·Flyway 이력 변경 및 마이그레이션 거부.
- migrator의 비관리자·DB CREATE 미허용과 다른 스키마 생성 거부.
- 적용된 SQL의 임시 복사본만 변경해 checksum 불일치를 검증. 원본 V1과 이력은 수정하거나 repair하지 않는다.

2026-11-02 전체 `build` 성공: V1 24개 + 기존 7개 = 31개 통과, 실패·오류·건너뜀 0. 상세 테스트 결과는 build/reports/tests/test/index.html이다.

실행 JAR에 SQL 파일은 들어가지만 Flyway 라이브러리는 들어가지 않는다. 2026-11-02 격리 검증 시점에는 로컬 DB에 미적용이었고, 2026-11-03 전용 실행기로 로컬 V1 적용까지 완료했다. bootRun은 마이그레이션을 자동 실행하지 않는다.

실행 방식은 사용자 확인을 받아 앱 부팅과 분리한 전용 Gradle/Java 작업으로 구현했다. migration source set과 runMigration 작업에만 Flyway 실행 의존성을 추가하며 일반 앱의 런타임에는 넣지 않는다. migrator 자격증명은 infra/.env.migrator에 따로 보관하고 그 작업에만 전달한다. [로컬 적용 절차와 격리 검증 명령](../docs/local-migration.md)을 참고한다. 로컬 V1은 성공 이력·checksum 검증·재실행 0건까지 확인했고, 별도 seed Apply/Verify로 개발 데이터도 준비했다.

## 개발 seed 실행기

seed는 앱 부팅이나 Flyway Migrate에서 자동 실행하지 않는다. [dev-seed.md](../docs/dev-seed.md)의 별도 PowerShell/Gradle 작업이 migration 클래스패스의 LocalSeedRunner를 실행한다. 앱에는 seed 코드·의존성을 넣지 않는다. 실제 로컬 Apply 12행/Verify 성공/반복 0건도 확인했다. 이후 앱의 최소 SELECT 권한을 별도 적용해 JPA 조회까지 확인했으며 업무 HTTP API는 아직 없다.

```powershell
# backend에서 seed 통합 테스트만 실행; Docker 필요
.\gradlew.bat test --tests com.flowdesk.seed.LocalSeedRunnerTest --no-daemon --no-configuration-cache --console=plain
```

빈 업무 테이블의 Apply는 5명/2개 공간/5개 멤버십을 한 트랜잭션으로 삽입한다. 전체 일치 시 0건이며 불일치는 보존하고 실패 종료한다. Verify는 읽기 전용으로 검사한다. V1/대기 버전/checksum·소유권과 역할을 먼저 확인하며 스키마를 자동 복구하지 않는다. 롤백·동시 Apply·잠금 시간 초과까지 seed 테스트 31개로 검증했다.

## JPA 조회와 읽기 권한

User/Workspace/WorkspaceMembership 엔티티와 조회 Repository 3개·메서드 5개를 구현했다. Membership의 단방향 LAZY, 소속 목록의 workspace EntityGraph/Slice, Long IDENTITY·Instant·문자열 enum으로 V1을 읽는다. 저장·삭제·업무 HTTP API는 추가하지 않았다. [매핑/권한 명세](../docs/jpa-read-model-v1.md).

앱 설정은 ddl-auto=validate, open-in-view=false다. 실제 로컬 권한은 별도 infra/provision-app-read.ps1 최초/반복 실행으로 READ-only를 준비했다. 앱 부팅이 권한을 자동 부여하지 않는다. 새 JAR의 클래스/의존성으로 임시 Spring 앱을 기동해 실제 Repository 5개 계약·앱 계정·쓰기 거부와 health 200/UP을 확인한 뒤 종료했다. [로컬 적용 결과](../docs/jpa-read-model-v1.md).

읽기 권한 도구는 기존 앱 비밀번호로 인증하고 관리자 연결의 한 트랜잭션에서 schema USAGE+3개 업무 테이블 SELECT만 부여한다. DML/DDL/sequence/history 접근과 grant option·부분/과다 권한은 거부한다. migration/seed도 기존 CONNECT-only와 승인 READ-only를 함께 검증한다.

```powershell
# backend: JPA/정책 테스트만 (격리 PostgreSQL)
.\gradlew.bat test --tests 'com.flowdesk.persistence.*' --no-daemon --no-configuration-cache --console=plain
```

전체 build/JUnit 96개와 실제 PowerShell 경로 41개 격리 검증이 통과했다. 이후 실제 로컬 READ 적용·JPA 조회를 별도로 확인했고 데이터 5/2/5·V1·자격증명을 유지했다. 읽기 권한은 사용자/팀별 인가가 아니며 HTTP 업무 API·Service/DTO는 후속이다.

## 요청 처리 흐름과 한계

HTTP GET → 내장 Tomcat → DispatcherServlet → `HealthController.health()` → `HealthResponse` → Jackson JSON 직렬화 → HTTP 200.

- `@SpringBootApplication`의 컴포넌트 스캔으로 `com.flowdesk` 아래 Controller를 등록한다.
- health Controller에는 업무 규칙과 저장소 접근이 없어 Service/Repository를 호출하지 않는다. 업무 조회는 아래 dev-actor 모드의 별도 Controller/Service에서 JPA Repository를 호출한다.
- Controller의 `UP`은 앱이 요청에 응답한다는 의미다. Actuator는 등록된 DB 상태 검사를 포함하지만 모든 업무 기능의 정상 동작까지 보장하지 않는다.
- Spring Security/실제 인증은 Phase 2다. 개발 조회는 서버 지정 행위자와 Service 소속 검사로 제한하며 별도 CORS 허용을 추가하지 않는다.
- 업무 테이블은 V1/seed/READ 권한 로컬 적용과 새 JPA 앱 조회까지 확인했다. 개발 모드의 첫 HTTP 조회를 격리 검증했으며, 쓰기와 운영용 모니터링은 후속 작업이다.

Actuator 경로는 HTTP GET → Tomcat → DispatcherServlet → Actuator → DataSourceHealthIndicator → HikariCP DataSource → PostgreSQL JDBC → DB 연결 검사 → 상태 종합 → JSON 응답 흐름이다. HealthController/HealthResponse나 업무 조회 Service/Repository를 호출하지 않는다.

## 첫 워크스페이스 조회 — 개발 전용

POST 생성은 별도 쓰기 활성화/권한/Origin 설정이 필요하다. [생성 계약·적용 결과](../docs/workspace-create-api-v1.md)를 따른다. 현재 로컬 DB 권한은 WORKSPACE_CREATE지만 서버의 쓰기 설정은 기본 false다. 조회에는 기존 dev-actor/사용자 설정만 사용한다. 별도 검증 앱에서 생성한 ID 3은 유지했고 검증 서버는 종료했다.

기본 단위/통합 검증은 .\gradlew.bat build --no-daemon --no-configuration-cache --console=plain이며 Java 245개를 포함한다. 실제 Vite 프록시를 거치는 생성/Origin 검증은 frontend의 npm ci 후 .\gradlew.bat verifyWorkspaceProxy --no-daemon --no-configuration-cache --console=plain으로 별도 실행한다(Node/Docker 필요, 격리 DB만 사용).

V2·seed·READ 권한이 준비된 로컬에서 사용한다(현재 로컬 적용 완료). 기본 실행의 health API와 별개로, 아래 명령은 개발 행위자를 명시한다. 같은 컴퓨터의 모든 요청은 지정 사용자로 처리되며 실제 로그인이 아니다. 외부 공개/터널/리버스 프록시로 제공하지 않는다.

backend 디렉터리의 PowerShell에서 실행한다. 아래는 일반 실행 방법이다. 실제 로컬 검증은 동일 JAR를 java -jar로 별도 포트 18081에 Alice/Eve 각각 기동해 완료했고 검증 서버는 종료했다.

```powershell
$previousActor = [Environment]::GetEnvironmentVariable('FLOWDESK_DEV_ACTOR_EMAIL', 'Process')
try {
    $env:FLOWDESK_DEV_ACTOR_EMAIL = 'alice@example.test'
    .\gradlew.bat bootRun --args='--spring.profiles.active=dev-actor'
} finally {
    [Environment]::SetEnvironmentVariable('FLOWDESK_DEV_ACTOR_EMAIL', $previousActor, 'Process')
}
```

기존 서버가 8080을 사용하면 먼저 해당 서버를 중지하거나 --args에 --server.port=8081을 함께 지정한다. 종료는 Ctrl+C다. 환경 설정은 위 명령을 실행한 프로세스에서만 사용하고 종료 후 복원한다. 행위자 전환은 서버 설정 변경과 재시작으로 하며 HTTP 헤더/쿼리로 바꾸지 않는다.

다른 터미널에서:

```powershell
$list = Invoke-RestMethod 'http://127.0.0.1:8080/api/workspaces?page=0&size=20'
$list
$workspaceId = $list.items[0].id
Invoke-RestMethod "http://127.0.0.1:8080/api/workspaces/$workspaceId"
```

현재 데이터에서 Alice는 A와 자신이 생성한 공간 ID 3, Eve는 B를 조회할 수 있다. ID는 실제 응답에서 가져오며 숫자를 가정하지 않는다. 네 역할 모두 자신의 공간 기본 정보를 읽고 미소속/미존재는 같은 404다. page 기본 0/최대 10000, size 기본 20/최대 100이며 잘못되거나 미지원·중복 쿼리는 400이다. 반환 필드는 id(문자열)/name/myRole이고 목록에 items/page/size/hasNext를 둔다.

행위자 설정 누락/없는 사용자·127.0.0.1 외 bind·전달 헤더 해석 설정은 시작을 실패시킨다. 개발 필터는 직접 peer의 loopback 여부를 확인하고 모든 업무 응답에 no-store를 설정한다. DB 장애는 503, 그 밖의 예상하지 못한 실패는 안전한 500으로 처리한다. [계약·파일·검증 범위](../docs/workspace-read-api-v1.md)를 참고한다.

신규 테스트는 backend에서 .\gradlew.bat test --tests 'com.flowdesk.workspace.*' --no-daemon --no-configuration-cache --console=plain이다. config import 없이 Testcontainers PostgreSQL과 메모리의 테스트 자격증명으로 실행하므로 로컬 .env/DB를 사용하지 않는다.

JDBC starter가 HikariCP 연결 풀을 제공한다. Boot는 spring.datasource 설정과 드라이버를 바탕으로 DataSource를 자동 구성하고 Actuator가 DB contributor를 자동 등록한다. 로컬 풀은 최대 5개, 최소 유휴 0개, 커넥션 대기 3초와 드라이버 연결·소켓 타임아웃을 사용한다. 이는 개별 대기 제한이며 전체 HTTP 요청이 정확히 3초 안에 끝난다는 뜻은 아니다.

별도 포트 18081에 실행한 새 JAR에서 Compose DB 연결을 검증했고, PostgreSQL pg_stat_activity에서 flowdesk_app 세션도 확인했다. 이후 사용자가 재시작한 IDE 서버의 클래스패스에서 JDBC starter·PostgreSQL 드라이버·HikariCP를 확인하고 8080의 Actuator 200/UP 및 flowdesk_app 세션도 확인했다. 앞으로 의존성·설정을 바꾼 뒤에도 서버 재시작이 필요하다. 의존성 자동 동기화와 서버 프로세스 재시작은 별개다.


## 로컬 Project 수정 실행 상태 — 2026-11-07

기존 로컬 DB에 V4/PROJECT_UPDATE를 적용하고 이 JAR의 실제 Project 수정 200/version 1·stale 409·역할별 접근 거부·쓰기 off 405·Actuator UP을 확인했다. 검증 서버는 종료했으며 현재 데이터는 5/3/6/1이다. DB 설정/개발 행위자/쓰기 활성화는 위의 명시적 실행 절차를 따르고, 앱 부팅이 migration/GRANT를 대신하지 않는다. [현재 계약](../docs/project-update-api-v1.md), [실제 명령·결과](../docs/local-setup.md). Vue 편집 폼은 후속이다.


## 첫 Task API — 2026-11-07

Task 생성/목록/상세는 `/api/workspaces/{workspaceId}/projects/{projectId}/tasks`와 하위 `/{taskId}`다. 세 역할(OWNER/ADMIN/MEMBER)은 생성하고 VIEWER 포함 네 역할은 조회한다. 생성 JSON은 필수 title/선택 description/선택 priority이며 status는 받지 않고 서버 TODO다. priority 생략은 MEDIUM, 명시 값은 LOW/MEDIUM/HIGH다. 부모 확인/역할·DB 트랜잭션은 Service에서 검사하고 버전/담당자/마감일/수정/칸반/댓글은 아직 없다.

현재 실제 로컬 DB는 V4로 보존했으므로 새 Task JAR는 후속 V5 migration/최소 Task SELECT·INSERT 적용 뒤 실행해야 한다. 앱 자체는 DDL/GRANT를 수행하지 않는다. `dev-actor`와 서버 지정 사용자/명시적 쓰기 flag·허용 Origin은 기존대로 필요하다. Task POST도 기존 Origin/JSON 필터에 포함했다.

```powershell
# backend: isolated PostgreSQL; no local credential import
./gradlew.bat test --tests '*Task*Test' --tests '*V5MigrationTest' --no-daemon --console=plain
./gradlew.bat build verifyWorkspaceProxy --no-daemon --console=plain
# root: unique isolated Compose project/temporary credentials
./infra/verify-migration.ps1 -IncludeTaskCreate
```

[Task 계약](../docs/task-model-and-api-v1.md)과 [실제 실행 결과](../docs/local-setup.md)를 따른다. 프론트 제품 소스는 바꾸지 않았다.


## 로컬 V5/Task API 실행 준비 완료 — 2026-11-08

기존 로컬 DB의 V5/TASK_CREATE를 적용하고 동일 JAR로 첫 Task ID 1의 MEMBER 생성 201/TODO·MEDIUM과 목록/상세·역할·부모/출처/쓰기 off 405·Actuator UP을 확인했다. 데이터는 5/3/6/1/1, 부모 Project 1 version은 1이다. 검증 서버는 종료했으며 새 앱은 현재 스키마와 호환된다. dev-actor/서버 지정 사용자·명시적 쓰기 flag/Origin은 기존대로 필요하다. 실제 인증이나 Vue Task 화면은 아직 없다. [현재 계약](../docs/task-model-and-api-v1.md), [실제 실행 기록](../docs/local-setup.md).


## Task 수정 API·V6 — 2026-11-08

`PATCH /api/workspaces/{workspaceId}/projects/{projectId}/tasks/{taskId}`는 필수 title/description/priority/version을 받는다. OWNER/ADMIN/MEMBER 허용·VIEWER 403, 미소속/미존재/부모 불일치 동일 404, Task 자체 stale/낙관적 충돌은 409다. no-op도 낙관적 검증을 하며 상태/부모/생성 시각은 보존한다. 상세/생성/수정은 version 문자열을 포함하고 목록은 여섯 필드를 유지한다.

새 매핑은 V6를 요구하며 쓰기 기동에는 별도 TASK_UPDATE 다섯 컬럼 정책이 필요하다. 실제 로컬은 아직 V5/TASK_CREATE이고 적용은 별도다. [수정 계약](../docs/task-update-api-v1.md), [실행 기록](../docs/local-setup.md)을 따른다.

최종 검증: **Java 446개·실제 Vite 프록시 1개/전체 빌드·PowerShell 105개·Vue 364개/빌드 통과**. 실제 로컬 V5/5/3/6/1/1은 보존했고 격리 자원을 정리했다. 다음 단계는 별도 로컬 V6·최소 Task UPDATE 적용/새 앱 수정 확인이다.


## 실제 로컬 Task V6 준비 완료 — 2026-11-08

로컬 V6/TASK_UPDATE와 새 JAR의 실제 Task 수정·409/no-op·세 역할/VIEWER·부모/출처·쓰기 off·Actuator를 확인했다. Task 1 version은 "1", priority HIGH/status TODO이며 Project 1 version 1과 데이터 5/3/6/1/1은 유지됐다. 새 앱은 현재 로컬과 호환되고 쓰기에는 기존 dev-actor/서버 지정 사용자/쓰기 flag·Origin이 여전히 필요하다. 검증 앱은 종료했다. [실행 기록](../docs/local-setup.md).


## 후속 Task 상태 변경·최소 Activity 계약 — 2026-11-08

[상태 계약](../docs/task-status-api-v1.md)에 전용 nested PATCH /status의 두 필드(status/version), 모든 전이·세 역할/공유 Task 버전·no-op, 상태 UPDATE/최소 이력 INSERT의 같은 트랜잭션·V7/최소 권한을 확정했다. 일반 수정은 상태를 계속 보존한다. API/V7/권한 도구는 아직 구현하지 않았고 현재 로컬은 V6/TASK_UPDATE다.

### Task 상태 변경 API — 2026-11-10

`PATCH /api/workspaces/{workspaceId}/projects/{projectId}/tasks/{taskId}/status`는 정확히 `{"status":"IN_PROGRESS","version":"1"}` 두 필드를 받는다. 해당 공간 OWNER/ADMIN/MEMBER가 모든 상태 전이를 수행하며 일반 수정과 version을 공유한다. 현재 버전 동일 상태는 no-op, 오래된 버전은 409다. 상태 UPDATE와 서버 행위자/이전·다음 상태/시간/새 버전의 이력 INSERT가 함께 commit/rollback되고 기존 TaskView를 반환한다. 기본 프로필 404·개발 쓰기 off 405를 유지한다.

V7/최소 상태 권한이 없는 V6/TASK_UPDATE에서는 새 쓰기 앱의 기동 검사가 실패한다. 앱 부팅이 migration/GRANT를 대신하지 않는다. 실제 로컬 적용은 별도 요청으로 진행하며 [구현/격리 검증 명령](../docs/task-status-implementation.md)과 [실행 결과](../docs/local-setup.md)를 따른다.

### 실제 로컬 V7 기동·상태/이력 검증 — 2026-11-10

현재 로컬은 V7/TASK_STATUS_CHANGE이며 검증된 JAR을 flowdesk_app으로 기동해 실제 상태 PATCH를 확인했다. Task 1은 IN_PROGRESS/version "2", Carol의 TODO→IN_PROGRESS 이력은 1건이다. 상태/일반 수정의 stale 409·같은 상태/일반 수정 no-op·OWNER/ADMIN 허용·VIEWER 403/미소속·부모 404·Origin 403/입력 400·쓰기 off 405와 Actuator UP을 확인했다. 임시 검증 앱 6개는 모두 종료했고 8080/18081은 비어 있다. 실제 로컬 V7/최소 권한은 이제 적용됐으며 앱은 자동으로 영구 실행해 두지 않았다. [실행 기록](../docs/local-setup.md).

### Task 상태 이력 조회 계약 — 2026-11-10

예정 GET `/api/workspaces/{workspaceId}/projects/{projectId}/tasks/{taskId}/status-activities`는 현재 소속 네 역할에 전체 상태 이력을 제공한다. actor의 현재 ID/displayName만 포함하고 이메일은 제외한다. 최신 Task 버전부터 beforeVersion/size로 조회하며 기존 이력 UNIQUE를 활용한다. [계약](../docs/task-status-history-read-api-v1.md)을 확정했지만 조회 코드/권한은 아직 없다. 후속 새 dev-actor 앱은 쓰기 on/off 모두 audit SELECT 7개가 필요하도록 기동 검사를 전환하며, 실제 로컬 적용은 구현/격리 검증 뒤 별도로 진행한다.

### Task 상태 이력 조회 구현 — 2026-11-10

위 계약의 GET·strict beforeVersion/size·현재 소속/두 부모 검사·readOnly projection을 구현했다. 쓰기 off에서도 GET을 제공하지만 dev-actor 기동에는 정확한 audit 7컬럼 SELECT가 필요하다. 실제 로컬은 아직 TASK_STATUS_CHANGE이므로 별도 권한 적용 후 새 앱을 실행해야 한다. 기본 프로필은 기존처럼 404다. [구현·흐름·검증](../docs/task-status-history-implementation.md).

### 실제 Task 상태 이력 GET — 2026-11-10

실제 로컬 flowdesk_app에 `TASK_STATUS_HISTORY_READ`를 적용했다. 새 dev-actor 앱은 쓰기 off로 `127.0.0.1:18081`에서 검증했으며 Task 1의 Carol 상태 이력 1건·8필드 envelope·UTC 시각을 반환했다. Actuator UP, beforeVersion=2의 빈 페이지, 잘못된 size=01의 400도 확인했다. 기존 데이터/다른 권한을 보존했다. [실행 기록](../docs/local-setup.md).
