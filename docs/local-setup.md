# 로컬 개발환경 준비

## 범위와 확인 결과 — 2026-11-02

개발 도구 설치에 이어 백엔드 최소 뼈대와 health API까지 검증했다. 컨테이너 생성, PostgreSQL 연결, Vue 화면 호출은 아직 하지 않았다.

| 항목 | 확인한 버전 | 상태 |
|---|---|---|
| JDK / javac | Temurin 21.0.13+9 / 21.0.13 | 설치 및 새 환경변수 적용 후 실행 확인 |
| Node.js | 24.22.2 | 기존 Volta로 설치, 기본 버전 설정 |
| npm | 11.19.1 | Node 배포본에 포함된 버전 실행 확인 |
| Volta | 2.0.2 | 기존 설치 사용 |
| Docker Engine | 27.4.0, Linux | 기존 Docker Desktop 시작 후 연결 확인 |
| Docker Compose | 2.31.0-desktop.2 | 기존 설치 사용 |
| Gradle | Wrapper 8.14.3 | Java 21에서 Boot 3.5.16 빌드·테스트 성공 |

기존 Java 8·17은 삭제하지 않았다. Temurin 설치 후 시스템 JAVA_HOME과 PATH에 Java 21이 반영되었고, Volta 기본 Node는 24.22.2로 변경되었다. 다른 프로젝트의 실행 버전도 확인해야 한다. FlowDesk의 Node 프로젝트별 버전 고정은 프론트엔드 생성 시 진행한다.

## 실행한 설치 명령

```powershell
winget install --id EclipseAdoptium.Temurin.21.JDK --exact --source winget --version 21.0.13.9 --silent --accept-package-agreements --accept-source-agreements --disable-interactivity
volta install node@24
```

- winget은 설치 파일 SHA-256 검증 후 설치 성공을 보고했다.
- `node@24`는 실행 시점의 24 계열 버전을 선택하므로 같은 버전 재현 시 `volta install node@24.22.2`를 사용한다. npm은 별도 설치하지 않았다.
- Gradle은 전역 설치하지 않는다. 백엔드에 포함할 Wrapper로 실행한다.

## 설치 후 터미널 환경 갱신

실행 중이던 터미널과 IDE는 이전 환경변수를 유지할 수 있다. IDE를 완전히 종료한 뒤 다시 열거나, 현재 PowerShell에서 다음처럼 적용한다.

```powershell
$env:JAVA_HOME = [Environment]::GetEnvironmentVariable('JAVA_HOME', 'Machine')
$env:Path = [Environment]::GetEnvironmentVariable('Path', 'Machine') + ';' + [Environment]::GetEnvironmentVariable('Path', 'User')
java -version
javac -version
node --version
npm --version
```

이 명령은 현재 셸의 환경만 다시 읽으며 시스템 환경변수를 추가로 변경하지 않는다. 설치 전 프로세스에서는 Java 17이 실행되었고, 갱신 후 Java와 javac 모두 21.0.13으로 확인했다.

## 검증 명령과 실제 결과

```powershell
Get-Command java,javac -All
java -version
javac -version
node --version
npm --version
volta list all
volta which node
volta which npm
docker --version
docker compose version
docker info --format 'Server={{.ServerVersion}} OSType={{.OSType}}'
```

- Java 실행 경로: `C:\Program Files\Eclipse Adoptium\jdk-21.0.13.9-hotspot\bin\java.exe`.
- Java: `openjdk version "21.0.13"`, `Temurin-21.0.13+9`. javac: `21.0.13`.
- Node: `v24.22.2`. npm: `11.19.1`. Volta 목록: `runtime node@24.22.2 (default)`.
- Node와 npm의 실제 경로가 Volta의 `tools\image\node\24.22.2` 아래임을 확인했다.
- Docker CLI: `27.4.0`. Compose: `v2.31.0-desktop.2`.
- Docker 엔진: `Server=27.4.0 OSType=linux`.

처음에는 Docker 엔진 파이프를 찾지 못했다. 기존 Docker Desktop을 다음 명령으로 시작한 뒤 연결에 성공했다.

```powershell
Start-Process -FilePath 'C:\Program Files\Docker\Docker\Docker Desktop.exe' -WindowStyle Hidden
```

샌드박스 안에서는 npm 사용자 경로와 Docker 설정 접근이 거부되어, 허용된 샌드박스 밖 명령으로 검증했다. 설치 실패와 샌드박스 접근 실패를 구분해야 한다.

## 백엔드 추가 검증 — 2026-11-02

`backend` 디렉터리에서 다음 명령을 실행했다.

```powershell
.\gradlew.bat build --no-daemon --console=plain
```

- `BUILD SUCCESSFUL in 32s`. HTTP 통합 테스트 2개, 실패·오류·건너뜀 0개.
- GET `/api/health`의 200·JSON 응답과 POST의 405 응답을 실제 임의 포트에서 검증했다.
- `Invoke-WebRequest -Uri 'http://127.0.0.1:8080/api/health'`로 별도 실행 중인 서버의 200, application/json, `{"status":"UP"}`도 확인했다.
- 처음 실행한 `clean build`는 세션 종료 후 최종 결과를 확보하지 못해 성공으로 기록하지 않는다. 결과를 확보한 명령은 위의 `build`다.
- 샌드박스에서는 Wrapper 배포본 다운로드가 네트워크 권한 오류로 실패했다. 허용된 샌드박스 밖 환경에서 빌드·테스트를 완료했다.
- Mockito 동적 에이전트와 CDS 경고가 출력되었으나 테스트 실패는 없었다.
- 실행 및 테스트 상세 안내: [backend/README.md](../backend/README.md).

## Actuator 추가 검증 — 2026-11-02

- `backend`에서 `gradlew.bat build --no-daemon --console=plain`: `BUILD SUCCESSFUL in 34s`, 테스트 4개, 실패·오류·건너뜀 0개.
- 패키징한 JAR를 임시 포트 18081로 실행하고 `Invoke-WebRequest`로 검증했다. Accept가 application/json일 때 `/api/health`와 `/actuator/health` 모두 200, application/json, `{"status":"UP"}`.
- Actuator 전용 Accept에 대해 application/vnd.spring-boot.actuator.v3+json 응답도 확인했다. `/actuator/metrics`는 404다.
- 확인용 서버는 종료했다. 기존에 사용자가 실행한 8080 서버는 변경하지 않았으며, Actuator를 사용하려면 새 의존성으로 재시작해야 한다.

## 남은 검증

### 8080 Actuator 404 해결 — 2026-11-02

- 현상: 사용자가 기동한 `/api/health`는 200, `/actuator/health`는 404.
- 원인 확인: 이전 Java 프로세스가 사용한 IDE 생성 임시 인수 파일의 클래스패스에 spring-boot-actuator가 없었다. build.gradle의 의존성 변경이 해당 실행 구성에 반영되지 않았다.
- 조치: 8080 리스너가 FlowDesk임을 확인하고 해당 프로세스만 종료했다. 패키징 JAR에 Actuator와 자동 설정 JAR가 포함됐음을 확인한 뒤 최신 JAR를 백그라운드에서 실행했다.
- 실제 검증: `Invoke-WebRequest`와 Accept application/json으로 `/api/health`와 `/actuator/health` 모두 200, `{"status":"UP"}`. `/actuator/metrics`는 의도한 404.
- 로그: `backend/build/local-server.stdout.log`, `backend/build/local-server.stderr.log`.
- 소스 변경이나 재빌드는 하지 않았다. 실행 환경을 바로잡았으며 향후 IDE 실행 전 Gradle 의존성 동기화 또는 Wrapper의 bootRun을 사용한다.

### 후속 범위

### IDE 동기화 원인 조사 — 2026-11-02

- 변경 전 작업 공간 설정: java.configuration.updateBuildConfiguration=interactive. 변경 시 자동 반영 모드가 아니었다.
- 초기 Java 언어 서버 로그: Gradle Build Server 시작 실패 후 Buildship 폴백, Unsupported class file major version 69. IDE는 확장에 내장된 Java 25를 사용했고 프로젝트 Wrapper는 8.14.3이었다. 현재 사용자 설정에는 Gradle용 JDK 21이 이미 지정되어 있었다.
- 조치: .vscode/settings.json에서 automatic으로 변경하고 Gradle용 Java 경로는 ${env:JAVA_HOME}으로 명시했다. 사용자 전역 설정이나 캐시는 수정·삭제하지 않았다.
- 모델 검증: 21:56:24 로그에 Updated flowdesk-backend-backend in 2485 ms. IDE 내부 variablesAndContainers.dat에서 Actuator 및 Actuator 자동 설정 JAR 포함 확인. settings.json 파싱과 JAVA_HOME 존재도 확인했다.
- 실행 준비: 임시 JAR 서버를 종료하고 FlowDeskApplication.java를 VS Code에서 열었다. 자동으로 Run Java 명령을 호출했으나 새 프로세스가 생성되지 않아 사용자에게 Run Java 실행을 요청했다.
- IDE 실행 최종 검증(22:08): 사용자가 Run Java로 실행한 PID 12760은 Java 21로 main 클래스를 직접 실행하며 새 인수 파일 `cp_aqkkq0rcegs518vf9tq0yxrgu.argfile`을 사용했다. 이 클래스패스에 backend/bin/main 및 Actuator·Actuator 자동 설정 3.5.16 JAR가 모두 포함된 것을 확인했다.
- HTTP 검증: 해당 IDE 프로세스의 8080에서 `/api/health` 및 `/actuator/health`가 각각 200/application/json/`{"status":"UP"}`. `/actuator/metrics`는 의도한 404. JAR 우회 없이 IDE 실행에서도 해결됐음을 확인했다.
- 앱 소스 변경이 없어 Gradle 테스트를 반복하지 않았다. 이번 대상은 IDE 가져오기와 실행 클래스패스다.

### 후속 개발 검증

- Vue/Vite 프로젝트 생성, 타입 검사 및 빌드.
- Docker Compose PostgreSQL 기동 및 DB 연결.
- Vue 화면에서 HTTP API 호출.

## Vue 화면 연결 검증 — 2026-11-02

- 생성 명령: `npm exec --yes --package=create-vue@3.24.0 -- create-vue frontend --typescript --vitest --bare`.
- frontend에서 `npm install --no-fund`로 설치와 잠금 파일 생성을 마쳤다. 최종 결과는 취약점 0건이다.
- `npm run build`: vue-tsc 타입 검사와 Vite 빌드 성공. `npm test`: 1개 파일의 5개 테스트 통과.
- `npm run dev`: 127.0.0.1:5173에서 실행 확인. HTTP 요청으로 화면 HTML 200 및 프록시 /api/health의 200/UP 확인.
- Chrome 헤드리스 검증: 독립 프로필로 실행해 실제 화면의 서버 연결 정상 및 UP DOM 확인. 데스크톱 스크린샷 `.cache/browser-check/health.png` 확인. 확인용 프로필·로그·스크린샷은 frontend/.gitignore로 제외했다.
- 조직이 관리하는 Windows 방화벽 허용 버튼은 변경하지 않았다. 루프백 연결과 브라우저 요청은 정상 동작했다.
- 테스트와 브라우저 검증용 Vite는 작업 종료 때 정리한다. 직접 사용하려면 [frontend/README.md](../frontend/README.md)의 npm run dev를 실행한다.
- 백엔드 소스 변경이 없으므로 이번에는 Gradle 테스트를 다시 실행하지 않았다. 기존 IDE 서버로 실제 연결했다.

## Compose PostgreSQL 검증 — 2026-11-02

- infra/compose.yaml, .env.example, init-local-env.ps1, README.md를 추가했다. 실제 비밀번호는 로컬 infra/.env에 생성했으며 출력하지 않았다.
- docker compose config --quiet 성공. 공식 이미지 postgres:17.11-bookworm을 다운로드하고 up -d --wait --wait-timeout 120 성공.
- FlowDesk 전용 컨테이너 healthy. TCP psql에서 DB flowdesk, 사용자 postgres, SELECT 1 결과 1, server_version 17.11 확인.
- 같은 TCP 경로에서 잘못된 비밀번호는 인증 실패로 거부됐다. Windows TcpClient로 127.0.0.1:5432 접속 성공. Docker 포트 매핑은 127.0.0.1만 사용한다.
- 명명 볼륨 flowdesk-local_postgres_data가 /var/lib/postgresql/data에 연결됐음을 확인했다. 초기 생성된 이 볼륨 외의 기존 프로젝트 데이터는 변경하지 않았다.
- DB 컨테이너는 실행 중으로 남겼다. Spring 연결·업무 스키마 생성은 아직 하지 않았고 앱 코드 변경도 없어 Gradle·Vue 검증을 반복하지 않았다.
- [재실행과 검증 명령](../infra/README.md)을 기록했다. 다음 작업은 Spring DataSource 연결과 Actuator DB 상태 검증이며, 먼저 애플리케이션 계정 권한을 결정해야 한다.

## 앱 계정·DataSource·Actuator DB 검증 — 2026-11-02

- 사용자가 CONNECT/SELECT 1에 필요한 최소 권한과 향후 DDL 계정 분리를 선택했다. infra/app-role.sql과 provision-app-db.ps1을 추가했다. 앱 비밀번호는 별도 backend/.env에 저장하며 실제 값을 출력하지 않았다.
- 역할의 superuser·createdb·createrole·replication·bypassrls는 모두 false. FlowDesk DB의 CONNECT=true, CREATE/TEMP 및 public 스키마 CREATE=false를 조회했다. 정책은 현재 FlowDesk DB의 PUBLIC 권한을 제한하며 다른 DB는 변경하지 않는다.
- provision-app-db.ps1을 재실행해 기존 앱 자격증명 파일이 바뀌지 않는 것을 SHA-256 비교로 확인했다. PowerShell 구문 검사도 통과했다.
- backend에서 `gradlew.bat build --no-daemon --console=plain`: BUILD SUCCESSFUL in 37s. HealthApiTest 4개, DatabaseHealthTest 3개, 실패·오류·건너뜀 0개.
- Testcontainers의 별도 PostgreSQL에 실제 권한 SQL을 적용해 앱 계정 SELECT 1, 일반/임시 테이블 DDL 거부, 실제 db contributor, Actuator 200/UP → DB pause 시 503/DOWN → unpause 후 200/UP을 확인했다. 장애 중 Controller /api/health는 200/UP이었다.
- 별도 포트 18081에 JAR 실행 후 Actuator 200/UP과 PostgreSQL pg_stat_activity의 flowdesk_app 세션을 확인했다. 확인용 JAR는 종료했고 Compose DB와 기존 IDE 서버는 보존했다.
- 사용자가 IDE 서버를 재시작한 뒤 8080의 Actuator와 Controller 모두 200/UP. 새 IDE 인수 파일 cp_40i2y15npoidu9aku4t0trkgc.argfile에 JDBC starter·PostgreSQL 42.7.11·HikariCP 포함을 확인했다. 로컬 DB에는 flowdesk_app/PostgreSQL JDBC Driver 세션이 생성됐다.
- JAR 목록에서 실제 .env 파일 포함 수는 0개였다. Vue 코드는 바꾸지 않아 프론트 테스트를 반복하지 않았다.
- 다음 작업은 첫 업무 도메인의 데이터 모델과 Flyway 마이그레이션 범위를 확정하는 것이다.

## V1 SQL 격리 검증 — 2026-11-02

- V1__create_identity_and_workspace_tables.sql을 추가했다. 세 테이블과 제약·인덱스만 포함하고 seed·역할·schema·권한 부여 SQL은 넣지 않았다.
- Flyway core/PostgreSQL 모듈 11.7.2는 테스트 의존성이다. 실제 앱 런타임이나 부팅 자동 마이그레이션은 추가하지 않았다.
- `gradlew.bat build --no-daemon --console=plain`: 최초 37초 성공. PK 중복 검증 보강 후 최종 BUILD SUCCESSFUL in 29s. V1MigrationTest 24개, DatabaseHealthTest 3개, HealthApiTest 4개 모두 통과. 실패·오류·건너뜀 0개.
- V1 테스트는 케이스별 새 DB와 별도 schema 소유 migrator를 사용한다. migrate 첫 실행 1건·재실행 0건·validate 및 checksum 불일치, 제약 위반과 앱 접근 거부를 검증했다.
- `gradlew.bat dependencyInsight --dependency flyway-core --configuration testRuntimeClasspath --no-daemon --console=plain`: 성공, Boot BOM이 선택한 버전 11.7.2 확인.
- 실행 JAR에는 V1 SQL이 포함되고 Flyway 런타임 JAR 및 실제 .env는 0개다.
- 테스트 후 로컬 Compose DB 읽기 전용 조회: 업무 schema=0, 업무 table=0, flowdesk_migrator=0. 로컬 DB에 적용하지 않았음을 확인했다.
- 프론트엔드 코드를 바꾸지 않아 Vue 테스트는 이번에 반복하지 않았다. 개발 seed와 실제 로컬 적용은 아직 미실행이다.

## 로컬 migration 절차 확정 — 2026-11-02

- 사용자가 별도 작업·별도 자격증명 분리를 선택했다. 확정 절차는 local-migration.md에 기록했다.
- 앱 부팅은 유지하고 향후 별도 Gradle/Java 작업이 infra/.env.migrator에서 읽은 자격증명을 전용 프로세스 환경으로 전달한다. Info → Migrate → Validate → Info 순서다.
- 현재 build.gradle·Spring config import·Git 제외 패턴을 확인했고 migrator 파일이 없음을 확인했다.
- 이번 변경은 문서뿐이다. 예정 스크립트·runner·Gradle 작업·실제 비밀번호 파일·DB 역할/schema를 생성하지 않았다. Gradle/Vue 테스트와 DB 마이그레이션도 실행하지 않았다.

## 전용 프로비저닝·migration 실행기 — 2026-11-03

- 구현: infra/provision-migrator.ps1·migrate-local.ps1·migration-common.ps1·run-migration-gradle.ps1, backend의 migration source set·runMigration JavaExec·LocalMigrationRunner, migrator.env.example.
- `backend`에서 `.\gradlew.bat build migrationClasses --rerun-tasks --no-daemon --no-configuration-cache --console=plain`: BUILD SUCCESSFUL in 41s, 10개 작업 실제 실행. JUnit XML 합계 31개, 실패·오류·건너뜀 0.
- 최초 sandbox 실행은 Wrapper 네트워크 접근 권한 오류로 실패했다. 네트워크/Docker 접근이 가능한 실행에서 위 최종 빌드를 완료했다.
- 중간 격리 실행에서 Flyway 11.7.2가 지원하지 않는 로깅 설정으로 Info가 실패했다. 고정 버전 소스를 확인해 수정했고 실제 Info/Migrate/Validate 경로의 성공을 확인했다.
- 앱 JAR 검사: Flyway 라이브러리·LocalMigrationRunner·.env 항목 모두 없음. PowerShell 파일 전체 문법 검사 통과.
- 검증 중 생성 비밀번호를 빌드/캐시/Gradle daemon 로그 96개 파일에서 검색해 일치 0건을 확인했다. 사용 중인 lock 파일은 검색에서 제외했으며 이 결과를 모든 OS/DB 로그의 비노출 증명으로 확대하지 않는다.
- 실제 로컬 DB 읽기 전용 조회: migrator 역할 0개, 업무 schema 0개. 실제 infra/.env.migrator도 없으며 관리자/앱 비밀번호를 변경하지 않았다.
- 루트에서 `pwsh -NoProfile -NonInteractive -File infra/verify-migration.ps1`: 최종 종료 코드 0, 25개 검증 통과. 최초 적용 1건/재실행 0건, Validate/Info, 비밀번호 재사용·실패 재시도, 앱 접근 거부, 잘못된 입력·인증, 역할/소유권/PUBLIC grant 변조, history 없는 기존 객체, checksum 오류를 실제 PowerShell → Gradle → Java → DB 경로로 확인했다.
- 최종 검증은 상속 환경에 잘못된 앱/관리자 비밀번호와 COMPOSE_PROJECT_NAME을 넣었다. 정상 동작과 부모 환경 불변을 확인했고 자식 출력에서 제공한 비밀번호 일치가 없었다. 검증용 프로젝트 67616f2af3924c5baf1479375a2431bd의 컨테이너·네트워크·볼륨 제거 및 검증 자격증명 파일 삭제를 완료했다. 앞선 실패/성공 검증의 프로젝트도 각각 정리했다.
- Vue 코드는 바꾸지 않아 프론트 빌드/테스트는 미실행이다. 실제 로컬 V1 적용·개발 seed·JPA/API도 미실행이다.

## 실제 로컬 migrator·V1 적용 — 2026-11-03

- 사용자 요청 대상: flowdesk-local / 127.0.0.1:5432 / flowdesk. 기존 Docker 볼륨을 유지했다. 시작 시 Docker Linux 엔진이 꺼져 있어 사전 검사가 실패했다. Docker Desktop을 시작하고 시작 중의 일시적 API 오류 뒤 서버 27.4.0 준비를 확인해 진행했다.
- `docker compose --project-name flowdesk-local --file infra/compose.yaml --env-file infra/.env up -d --wait --wait-timeout 120`: 성공, postgres healthy. 적용 전 migrator 역할 0개·flowdesk 스키마 0개·사용자 테이블 0개를 조회했다.
- 루트에서 `& ./infra/provision-migrator.ps1`: 성공. Git 제외 infra/.env.migrator를 생성하고 비관리자 역할·스키마를 준비했다. 이후 임시 호출 함수의 배열 인수 전달 오류로 Info 전에 중단됐고, 명시적 -Action 호출로 수정해 재개했다. 프로젝트 실행기 코드는 변경하지 않았다.
- 루트에서 `& ./infra/migrate-local.ps1 -Action Info`: Version 1 PENDING.
- `& ./infra/migrate-local.ps1 -Action Migrate`: Migrations executed: 1.
- `& ./infra/migrate-local.ps1 -Action Validate`: Validation successful.
- `& ./infra/migrate-local.ps1 -Action Info`: Version 1 SUCCESS.
- `& ./infra/migrate-local.ps1 -Action Migrate` 재실행: Migrations executed: 0. 위 작업은 오류 없이 종료됐다.
- SQL 확인: flowdesk schema 및 users/workspaces/workspace_memberships/flyway_schema_history 소유자 모두 flowdesk_migrator. V1 success=true, installed_by=flowdesk_migrator, checksum=-1384934132. 업무 행은 모두 0건. 업무 제약조건 11개+history PK와 OWNER 부분 UNIQUE·membership user 인덱스를 확인했다.
- 관리자·앱·migrator 파일 및 원본 V1은 마이그레이션 단계 전후 SHA-256 동일, 부모 프로세스 환경 동일. 실제 비밀번호는 명령행/출력/문서에 기록하지 않았다.
- 앱 비밀번호를 stdin으로 컨테이너 셸의 PGPASSWORD에 전달해 `psql -h postgres -U flowdesk_app -d flowdesk` TCP 접속을 확인했다. SELECT 1 성공, schema USAGE/CREATE=false, 4개 테이블의 SELECT/INSERT/UPDATE/DELETE/TRUNCATE=false. 문자열 테이블명 권한 조회는 스키마 접근 거부가 발생해 객체 OID로 변경했다. 실제 SELECT·DDL은 트랜잭션 안에서 42501 거부를 확인하고 ROLLBACK했다.
- 기존 8080 앱이 없어 JAR을 임시 18081에 실행했다. PowerShell HTTP 폴링은 타임아웃으로 성공 판정을 못했으며, 별도 실행 세션에서 `curl.exe --silent --show-error --fail --max-time 12 --noproxy '*' --include http://127.0.0.1:18081/actuator/health`로 HTTP 200 / UP을 확인했다. /api/health도 200 / UP이며 DB의 pg_stat_activity에 flowdesk_app / PostgreSQL JDBC Driver 세션이 있었다. 임시 앱만 종료했다.
- 최종 PostgreSQL 상태는 running/healthy다. 업무 데이터나 기존 볼륨을 삭제하지 않았다. 개발 seed·JPA/API와 앱 DML 권한 추가는 하지 않았다. 실행기/앱/V1 코드 변경이 없어 전체 빌드/JUnit/Vue 테스트는 반복하지 않았다.

## seed 실행기 구현·격리 PostgreSQL 검증 — 2026-11-03

- 변경: seed-local.ps1·run-seed-gradle.ps1·LocalSeedRunner·runSeed 작업·LocalSeedRunnerTest. 공유 migration-common.ps1에는 고정 Migration/Seed 분기만 추가하고 기존 호출 기본값을 유지했다. LocalMigrationRunner의 기존 권한 검사 메서드를 verifyMigrator로 공개해 두 도구가 같은 검사를 재사용한다.
- `backend`에서 `.\gradlew.bat test --tests com.flowdesk.seed.LocalSeedRunnerTest --no-daemon --no-configuration-cache --console=plain`: 첫 실행은 Testcontainers URL 형식이 허용 대상과 달라 31개 중 13개 실패. 실행기 제한은 유지하고 테스트 URL만 127.0.0.1/쿼리 없는 형식으로 수정했다. 재실행 BUILD SUCCESSFUL in 1m 17s, seed 테스트 31개 통과.
- `.\gradlew.bat build --no-daemon --no-configuration-cache --console=plain`: BUILD SUCCESSFUL in 1m 32s. JUnit XML 합계 62개(기존 31+seed 31), 실패·오류·건너뜀 0. test 작업이 실제 실행됐다.
- seed 테스트는 새 격리 DB마다 V1과 동일 역할 정책을 준비한 뒤 별도 JVM의 실제 main을 실행한다. 최초 12행/Verify/반복 0건, ID·timestamps·history 불변, 변조/추가/부분 행 보존, membership 실패 전체 롤백, 동시 Apply 12/0, 쓰기 잠금 3초 시간 초과 후 재시도를 확인했다. 스키마/버전/checksum/인증/환경/Action 오류도 거부했다.
- 루트에서 `pwsh -NoProfile -NonInteractive -File infra/verify-migration.ps1 -IncludeSeed`: 종료 코드 0, migration 회귀 25개+seed 경로 8개=33개 검증 통과. 잘못된 상속 앱/관리자 비밀번호와 COMPOSE_PROJECT_NAME을 넣은 실제 PowerShell → Gradle → Java → DB 경로를 사용했다. seed 12행/재실행 0건/Verify, 수정 데이터 보존, 잘못된 입력/인증, 앱 권한·history·부모 환경 불변을 확인했고 제공한 비밀번호가 자식 출력에 없었다.
- 검증 프로젝트 flowdesk-migration-check-2a8f72bc652445c48ae23c14400f614d의 컨테이너·네트워크·볼륨을 정리했다. 테스트 자격증명 파일도 정리한다. 실제 flowdesk-local은 변경하지 않는다.
- 앱 JAR에서 LocalSeedRunner/LocalMigrationRunner·Flyway runtime·.env가 제외됐음을 확인했다. 모든 infra PowerShell 파일은 파싱 검사를 통과했다.
- 실제 로컬 DB 읽기 전용 조회: 업무 행 합계 0건, V1 성공 이력 1건. 이번에는 실제 로컬 seed를 넣지 않았다. V1·앱 권한을 변경하지 않았고 JPA/API·인증/인가·프론트 변경도 없어 Vue 빌드/테스트는 반복하지 않았다.

## 실제 로컬 seed Apply·Verify — 2026-11-03

- 사용자 요청 대상 flowdesk-local / 127.0.0.1:5432 / flowdesk를 기존 실행기로 검증했다. 적용 전 users/workspaces/workspace_memberships는 각각 0건, V1 success=true/checksum=-1384934132/installed_by=flowdesk_migrator, schema 소유자도 migrator였다.
- 저장소 루트에서 `& ./infra/seed-local.ps1 -Action Apply`: 성공, Seed rows inserted: 12.
- `& ./infra/seed-local.ps1 -Action Verify`: 성공, Seed verification successful: users=5 workspaces=2 memberships=5.
- `& ./infra/seed-local.ps1 -Action Apply` 재실행: 성공, Seed rows inserted: 0.
- 실제 SQL로 5개 사용자·2개 workspace·5개 membership과 A의 Alice OWNER/Bob ADMIN/Carol MEMBER/Dave VIEWER, B의 Eve OWNER를 확인했다. 각 공간 OWNER는 1명이다.
- 첫 Apply 직후와 Verify/반복 Apply 이후 전체 행의 JSON 스냅샷을 비교해 ID·created_at/updated_at·행 값이 같음을 확인했다. 적용 전후 Flyway history 전체와 앱 테이블 권한 스냅샷도 동일했다.
- 관리자·migrator·앱 자격증명 파일과 V1의 SHA-256, 부모 환경변수 비교도 통과했다. 비밀번호 값은 출력하거나 문서에 기록하지 않았다.
- 추가 SQL 권한 조회: 앱은 DB CONNECT=true, DB CREATE/TEMP=false, flowdesk schema USAGE/CREATE=false, 업무·이력 테이블 SELECT/INSERT/UPDATE/DELETE=false다. seed가 앱 권한을 늘리지 않는다.
- 최종 flowdesk-local-postgres-1은 running/healthy다. 실행기·앱·V1 코드 변경이 없어 전체 빌드/JUnit/Vue 검증은 반복하지 않았다. 이전 격리 테스트의 62개/33개 통과와 이번 실제 로컬 적용 결과를 구분한다. JPA/API·인증/인가 구현은 후속이다.

## 첫 JPA 조회·읽기 권한 정책 구현/격리 검증 — 2026-11-03

- spring-boot-starter-data-jpa, 3개 엔티티/WorkspaceRole, 조회 Repository 3개·메서드 5개, ddl-auto=validate/open-in-view=false를 구현했다. 별도 migration source set에 AppReadPolicy/AppReadAccessRunner/정책 SQL, infra에 provision-app-read.ps1·run-app-read-gradle.ps1을 추가했다. 앱에 privileged 코드를 넣지 않는다.
- `backend`에서 `.\gradlew.bat compileJava migrationClasses --no-daemon --no-configuration-cache --console=plain`: BUILD SUCCESSFUL in 12s, JPA 의존성 해결·엔티티/권한 도구 컴파일 성공.
- `.\gradlew.bat test --tests 'com.flowdesk.persistence.*' --no-daemon --no-configuration-cache --console=plain`: 초기 조회/권한/validate 32개 성공, 1m 45s. 이후 DB CONNECT grant option/PUBLIC CONNECT 거부 2개를 보강했다.
- `.\gradlew.bat build dependencyInsight --dependency hibernate-core --configuration runtimeClasspath --no-daemon --no-configuration-cache --console=plain`: BUILD SUCCESSFUL in 3m 30s. Hibernate runtime 6.6.53.Final 확인. JUnit XML 합계 96개, 실패·오류·건너뜀 0. DatabaseHealth 3/HealthApi 4/V1 24/Seed 31/AppReadPolicy 27/JpaReadRepository 5/JpaSchemaValidation 2개다.
- 앱 계정으로 실제 Repository 5개·Long ID/Instant/varchar enum·부재 empty·Alice/Eve 관계, LAZY와 다중 공간 Slice/hasNext/정렬·workspace fetch SQL 1회, 데이터/시각/history 보존을 확인했다. readOnly=false JDBC/EntityManager 경로의 쓰기도 거부됐고 schema/필수 컬럼 누락은 validate 실패 후 미복구였다.
- 루트에서 `pwsh -NoProfile -NonInteractive -File infra/verify-migration.ps1 -IncludeReadAccess`: 종료 코드 0, 41개 검증 성공. 기존 CONNECT-only에서의 migration/seed와 READ-only 권한 최초/반복 적용·실제 SELECT/쓰기 거부·이후 도구 호환을 검증했다. 과다 권한이면 모든 도구가 실패하고 자동 교정하지 않았다. 자식 출력의 비밀번호 비노출·부모 환경/기존 파일·데이터 보존도 확인했다.
- 검증 프로젝트 flowdesk-migration-check-27af4f7e184f4fe7aa3e600e5b5ed1c6의 컨테이너·네트워크·볼륨과 관리자/migrator/앱 테스트 자격증명 파일을 정리했다.
- 최종 앱 JAR에는 엔티티가 있고 LocalMigrationRunner/LocalSeedRunner/AppReadAccessRunner/AppReadPolicy·정책 SQL·Flyway·.env는 없다. infra PowerShell 전체 파싱 검사도 통과했다.
- 실제 로컬은 읽기 전용으로만 조회했다. 데이터 5/2/5, V1 성공 이력 1건, app schema USAGE=false, 업무/이력 SELECT 가능한 테이블 0개로 CONNECT-only 유지다. 실제 로컬 GRANT·새 JPA 앱 기동/조회는 미실행이며 다음 요청 범위에서 진행한다. Vue 변경이 없어 프론트 빌드/테스트는 반복하지 않았다.

## 실제 로컬 READ 적용·새 JPA 기동/조회 — 2026-11-03

- 사용자 요청으로 flowdesk-local / 127.0.0.1:5432 / flowdesk를 확인했다. 적용 전 데이터 5/2/5, V1 success=true/checksum=-1384934132, app schema USAGE=false/업무 SELECT 0개였다.
- 루트에서 `& ./infra/provision-app-read.ps1`: Application read access: READ_ONLY; existing password preserved. 반복 실행도 동일하게 성공했다.
- 임시 검증 코드는 backend/build/local-jpa-verification/LocalJpaSmoke.java다. 새 flowdesk-backend-0.0.1-SNAPSHOT.jar를 같은 build 하위의 고유 디렉터리에 추출하고 BOOT-INF/classes 및 BOOT-INF/lib/*를 클래스패스로 사용했다. `javac -encoding UTF-8 -cp $classPath -d $verificationRoot .../LocalJpaSmoke.java`로 컴파일하고 `java -cp "$verificationRoot;$classPath" LocalJpaSmoke`에 해당하는 별도 프로세스를 실행했다. 앱/실행기 소스나 패키징에는 넣지 않았다.
- verifier가 SpringApplication.run(FlowDeskApplication.class, --server.port=18081, --debug=false)로 실제 Spring 앱/ORM을 기동했다. 앱 자격증명은 기존 backend/.env로 읽고 관리자/migrator 환경값은 자식 환경에서 제거했다. 실제 DataSource current_user=flowdesk_app/current_database=flowdesk를 확인했다.
- User.findByEmail/findById, Workspace.findById, Membership.findByWorkspace_IdAndUser_Id 및 사용자 소속 Slice의 5개 계약을 실제 Repository Bean에서 실행했다. Alice/A OWNER·Eve/A empty·Eve/B OWNER·각자의 공간 목록과 없는 ID/이메일 empty가 통과했다. ddl-auto=validate와 OSIV=false도 확인했다.
- 같은 앱 DataSource에서 WHERE false인 INSERT/UPDATE/DELETE와 트랜잭션의 CREATE TABLE, history SELECT가 모두 SQLState 42501로 거부됐다. 각 검증은 ROLLBACK했으며 local_smoke_probe 객체는 0개였다. 시퀀스 값을 소비하지 않고 권한 메타데이터로 USAGE/SELECT/UPDATE=false를 확인했다.
- 앱 내부 Java HttpClient의 loopback 직접 요청으로 /actuator/health와 /api/health 모두 200/UP. 임시 앱은 context close 후 정상 종료했다. 정리된 로그는 build/local-jpa-verification/local-jpa-smoke.log에 있으며 3개 실제 비밀번호는 로그 전달 전에 치환했다.
- 이후 `& ./infra/migrate-local.ps1 -Action Info`, Validate, Migrate를 실행해 V1 SUCCESS/Validation successful/Migrations executed: 0을 확인했다. `& ./infra/seed-local.ps1 -Action Verify`, Apply도 5/2/5 검증 성공/Seed rows inserted: 0이었다.
- 적용 전후 전체 데이터/ID/timestamps/Flyway history JSON 스냅샷이 같았다. 관리자·migrator·앱 파일/V1의 SHA-256과 부모 환경도 같았다. 현재 app schema USAGE=true/CREATE=false, 업무 테이블 3개 SELECT=true, 나머지 DML/TRUNCATE·history SELECT·sequence 권한은 false다.
- 최종 PostgreSQL은 running/healthy다. 이번에는 실제 로컬 동작을 검증했고 앱/실행기 소스 변경이 없어 이전 단계의 전체 JUnit 96개/PowerShell 41개를 반복하지 않았다. Vue 코드도 변경하지 않아 프론트 검증은 미실행이다. 아직 업무 HTTP API/Service·인증/인가·쓰기 기능은 없다.

## 첫 조회 API·개발 행위자 경계 구현 및 격리 검증 (2026-11-03)

- 사용자 요청에 따라 ADR-022의 두 GET API·DTO·조회 Service·서버 지정 개발 행위자·접속/설정 검사·안전한 오류 처리를 구현했다. 새 의존성/Repository 메서드/V2·DB 권한 변경은 없다. 기본 모드는 업무 Controller를 등록하지 않는다.
- 실행 명령은 backend 디렉터리 기준이다.

```powershell
.\gradlew.bat compileJava --no-daemon --no-configuration-cache --console=plain
.\gradlew.bat test --tests 'com.flowdesk.workspace.*' --no-daemon --no-configuration-cache --console=plain
.\gradlew.bat build --no-daemon --no-configuration-cache --console=plain
```

- compileJava 성공. 최초 sandbox 실행은 Gradle 배포본 네트워크 접근 제한으로 실패했고, 허용된 실행에서 사용자 캐시를 사용해 성공했다.
- 신규 테스트는 최종 42개 성공. 테스트 클래스패스에만 있는 Flyway의 자동 실행을 명시적으로 끄고 config import도 초기 단계에서 비운다. 실제 테스트 DB에는 기존 전용 runner/V1/seed/READ 권한을 준비한 뒤 flowdesk_app으로 앱을 실행한다. 실제 로컬 자격증명 파일을 신규 테스트에 가져오지 않는다.
- 실패 수정: DB 중단 시 기존 풀 연결이 JpaSystemException → TransactionException → SQLException으로 감싸지면 500이 됐다. JDBC 트랜잭션 실패의 503 매핑을 보완해 전체 API 테스트와 회귀 빌드에서 장애/복구가 통과했다. 일반 권한 오류는 안전한 500으로 유지한다.
- 전체 build는 3분 15초 BUILD SUCCESSFUL. XML 합계 JUnit 138개(기존 96+신규 42), 실패/오류/skip 0. 사용자별 공간 격리/역할/동일 404·입력/헤더·기본 비활성/설정 실패·Service 직접 인가·OSIV=false DTO·목록 SQL 2개·DB 장애/복구를 확인했다. 비loopback peer는 모의 servlet 요청으로 필터를 직접 확인했다.
- API 테스트의 임시 데이터·이력은 초기 스냅샷과 같다. 권한 오류 테스트에서 회수한 격리 DB SELECT는 finally에서 복구했다. 실제 로컬 DB/서버/권한/자격증명에는 적용 작업을 하지 않았다.
- 새 실행 JAR에 API/개발 프로필이 있고 privileged runner/정책 SQL/Flyway/자격증명 파일은 없는 것을 ZIP 엔트리로 확인했다. infra/프론트는 변경하지 않아 PowerShell 41개 전체 경로·Vue 검증은 반복하지 않았다.
- 실제 로컬 개발 모드 실행 방법은 [backend/README.md](../backend/README.md)에 기록했다. 다음 한 작업은 로컬 개발 모드 기동과 실제 HTTP 목록/상세 확인이다. 실제 로그인·Vue 업무 화면·쓰기는 후속이다.

## 실제 로컬 개발 모드 기동·HTTP 확인 (2026-11-03)

- 사용자 요청으로 수행했다. 사전 PostgreSQL은 flowdesk-local-postgres-1 healthy, 8080/18081에는 listener가 없었다. 실제 DB 데이터는 5/2/5다.
- backend에서 .\gradlew.bat bootJar --no-daemon --no-configuration-cache --console=plain: BUILD SUCCESSFUL (7초, 4개 작업 UP-TO-DATE).
- 루트에서 & ./backend/build/local-api-verification/verify-local-api.ps1 실행: 성공. build 아래 Git 제외 임시 검증기이며 새 제품용 실행기를 추가한 것은 아니다.
- 내부 실행은 Java 21의 java -jar backend/build/libs/flowdesk-backend-0.0.1-SNAPSHOT.jar에 --spring.profiles.active=dev-actor --server.port=18081 --server.address=127.0.0.1 --debug=false를 전달했다. 자식 FLOWDESK_DEV_ACTOR_EMAIL에 Alice/Eve를 각각 지정하고 기존 관리자/migrator/Spring/서버 관련 환경은 제거했다. 앱만 backend/.env를 읽게 했다. 비밀번호는 명령 인수나 출력으로 전달하지 않았다.
- 실제 결과: Alice 목록/상세는 FlowDesk Demo A(id 문자열 "1")/OWNER만 200. Eve는 FlowDesk Demo B(id 문자열 "2")/OWNER만 200. 실제 ID는 DB와 HTTP에서 얻었으며 코드에 고정하지 않았다.
- 두 행위자 모두 상대 공간과 Long 최대값 ID에 동일 404/WORKSPACE_NOT_FOUND, userId 쿼리/size=101은 400/INVALID_REQUEST였다. X-User-Id/위조 Bearer/X-Forwarded-For로 행위자가 바뀌지 않았다. 다음 페이지는 200/빈 목록, 두 health 경로는 각각 200/UP이다. JSON 필드·no-store·loopback 실제 listener/PID·flowdesk_app DB 세션도 확인했다.
- 임시 검증기의 첫 시도는 PowerShell이 application/problem+json Content를 byte[]로 반환해 파싱 검사가 중단됐다. 그때 생성한 앱은 finally에서 종료했다. UTF-8 디코딩을 추가한 재실행은 두 사용자/모든 검증이 통과했다. 앱/설정 코드는 수정하지 않았다.
- 전후 사용자/공간/멤버십·Flyway 이력, DB/schema/relation ACL/소유자·역할 속성·시퀀스 스냅샷, 관리자/migrator/앱 파일과 V1 SHA-256, 부모 환경이 모두 같았다. 프로비저닝/마이그레이션/seed/쓰기/장애 주입은 실행하지 않았다.
- 각각 검증 후 생성한 프로세스만 종료했고 18081 listener가 없는 것을 확인했다. sanitized alice.log/eve.log는 같은 build 디렉터리에 두며 공급된 앱/관리자 비밀번호가 로그에 없음을 검사했다. PostgreSQL은 유지한다. IDE 8080 서버의 새 기동을 검증한 결과는 아니다.
- 이번에는 로컬 실행과 문서만 변경했다. 이전 전체 build/JUnit 138개·PowerShell 41개·Vue 검증은 반복하지 않았다. 다음 한 작업은 Vue의 워크스페이스 목록·상세 조회 연결이다.

## Vue 목록·상세 조회 연결과 실제 브라우저 검증 (2026-11-03)

- 새 workspaces.ts/WorkspaceBrowser.vue로 목록·선택 상세·페이지·오류/재시도·취소를 구현하고 App/style을 연결했다. 기존 health 테스트는 HealthStatusCard.spec.ts로 옮겨 회귀를 유지했다. 의존성/package-lock·백엔드/DB는 변경하지 않았다.
- frontend에서 npm test: 3개 파일, 31개 통과(health 5/API 17/화면 9). npm run build: vue-tsc 타입 검사와 Vite dist 생성 성공. 최종 화면 수정 후 같은 검증을 다시 통과했다.
- frontend에서 node .cache/workspace-browser-check.mjs: 성공. 기존 JAR를 8080 dev-actor로 실행하고 Vite 5173·별도 사용자 프로필의 headless Chrome으로 검증했다. 백엔드의 서버 지정 사용자를 Alice/Eve로 각각 재시작했다. 사용자 전환 UI/헤더는 추가하지 않았다.
- 실제 확인: Alice/A 목록 렌더링 → 클릭 상세/소유자, Eve/B 목록/클릭 상세와 A 미표시. Chrome Network의 /api/workspaces와 /api/workspaces/{id}에서 프록시 HTTP 200을 확인했다. 브라우저에서 업무 요청만 차단했을 때 오류/이전 상세 제거, 차단 해제·재시도로 복구됨을 확인했다. 이는 DB 장애 주입 테스트가 아니다.
- 1280px desktop/390px mobile 스크린샷과 DOM은 frontend/.cache/workspace-check에 저장했다. 최종 스크린샷을 생성했으며 모바일 가로 넘침이 없음을 브라우저에서 검사했다. 임시 스크립트·프로필·스크린샷은 Git 제외이며 앱 번들에 넣지 않는다.
- 검증 전후 업무 데이터/이력/relation ACL/시퀀스 스냅샷, 관리자/migrator/앱 .env와 V1 파일 해시가 같았다. 생성한 백엔드/Vite/Chrome 프로세스는 모두 종료했다. 실제 로컬 DB는 유지하며 프로비저닝/seed/마이그레이션을 실행하지 않았다.
- 새 프론트 코드만 바꿔 기존 백엔드 JUnit 138개와 PowerShell 전체 경로 41개는 반복하지 않았다. 의존성 변경이 없어 재설치/npm audit도 실행하지 않았다. 다음 한 작업은 첫 워크스페이스 생성의 API·권한·트랜잭션 범위 확정이다.

## 생성 API·최소 INSERT 도구 구현과 격리 검증 (2026-11-03)

- ADR-026의 생성/권한/트랜잭션 계약을 구현했다. Workspace/OWNER 팩토리, CommandService·MANDATORY persist 어댑터, 조건부 생성 Controller·쓰기 설정/Origin 필터, strict bounded UTF-8/JSON 입력과 기존 오류 처리 확장을 추가했다. 기본 쓰기는 false이며 V1/의존성/조회 Repository·Vue는 유지했다.
- 전용 AppWorkspaceCreateAccessRunner와 최소 INSERT SQL/PowerShell을 추가했다. AppReadPolicy는 정확한 WORKSPACE_CREATE도 검사한다. 기존 읽기/migration/seed 도구와 전환·반복·오류 거부를 검증했다. 실제 로컬 provision-workspace-create.ps1은 실행하지 않았다.
- backend의 .\gradlew.bat compileJava migrationClasses --no-daemon --no-configuration-cache --console=plain 성공. 집중 검증의 생성 API 44개/새 정책 17개/기존 조회 42개가 통과했고, 전체 build는 Java 199개 실패/오류/skip 0으로 성공했다.
- 별도 .\gradlew.bat verifyWorkspaceProxy --no-daemon --no-configuration-cache --console=plain도 1개 통과했다. Node/frontend npm ci를 요구하는 별도 태그 테스트여서 기본 test에서는 제외한다. 실제 Vite 설정의 포트/대상을 격리 서버로 바꿔 허용 Origin/201·불허 Origin/403과 행 미추가를 확인했다. HTTP/1.1로 개발 브라우저 연결과 맞췄다.
- 입력 검증에는 Unicode 코드 포인트 100/101·공백·제어/비정상 surrogate·중복 JSON/추가 필드·명시 타입/실제 인코딩·chunked 4 KiB 경계를 포함한다. VIEWER/미소속 사용자 생성·같은 이름의 동시 독립 생성·생성자 OWNER/타 사용자 차단을 검증했다.
- BEFORE INSERT 트리거 및 지연 제약 트리거를 격리 DB에만 주입해 저장/커밋 실패 뒤 두 행이 남지 않는 것을 별도 연결로 확인했다. 커밋 예외에 감싸진 SQLState도 확인해 무결성 실패는 500, 연결/명시적 일시 장애는 503으로 구분했다. IDENTITY 생성 키는 sequence GRANT 없이 성공하며 직접 sequence/users INSERT/수정·삭제·DDL·history 접근은 거부됐다.
- 루트의 ./infra/verify-migration.ps1 -IncludeWorkspaceCreate: 실제 PowerShell/Gradle/Java 전체 경로 52개 통과. 프로젝트 flowdesk-migration-check-a25a0cd70fe44cd187c58d3ed52b4260의 컨테이너/네트워크/볼륨과 임시 자격증명만 정리했다. 기존 단계+READ 필수/잘못된 대상/반복/최소 INSERT/도구 호환/부분·과도한 ACL 거부·데이터/앱 파일/부모 환경 보존을 확인했고 모든 자식 출력에서 공급한 비밀번호를 검사했다.

## 작업 중 보고된 IDE DataSource/42501 오류 수정 (2026-11-03)

- 첫 증상은 DataSource url 미지정이었다. source의 application.properties에는 기본 URL이 있지만 이전 IDE 실행은 bin/main을 사용했고 main 설정 리소스가 없었다. JDT 모델은 공유 src/main/resources를 migration 출력으로 분류했으며 Gradle 재가져오기는 캐시의 Java 25로 실행돼 Unsupported class file major version 69로 실패했다.
- 수정: migration source root에서 main 리소스 공유를 없애고 processMigrationResources가 V1 SQL만 복사하게 했다. IDE Gradle JDK를 설치된 21 경로로 명시했다. 사용자와 Reload Projects/언어 서버 캐시 재가져오기를 진행했으며 최신 cp_4hswyo2ntps3ej3vxr1m08ox3.argfile은 build/resources/main과 main 클래스 출력을 포함한다. 과거 bin/main만 검사해 새 IDE 출력까지 누락으로 오해하지 않는다.
- 다음 증상은 SQLState 42501/permission denied for database flowdesk였다. 최신 IDE 클래스패스가 migration 출력·Flyway 라이브러리도 포함하는 것을 확인했다. 앱 설정에 spring.flyway.enabled=false를 명시해 앱 부팅의 자동 migration을 차단했다. DB CREATE·관리자 권한을 추가하지 않았다.
- 수정 뒤 .\gradlew.bat processResources bootJar --no-daemon --no-configuration-cache --console=plain 성공. 같은 IDE 인수 파일 + FlowDeskApplication으로 18081에 Alice/Eve 읽기 모드를 실행해 목록/상세/health 200, 미소속/미존재 404·잘못된 입력 400·위조 헤더 무시를 확인했다. 명령은 루트의 & ./backend/build/local-api-verification/verify-local-api.ps1 -UseIdeClasspath다. build 아래 임시 검증기이며 실제 IDE UI의 Run 버튼을 눌렀다는 뜻은 아니다.
- 두 행위자 검증 후 프로세스를 종료했다. 데이터/이력/DB·schema·relation ACL/소유자·역할·시퀀스, 자격증명 3개/V1 해시와 부모 환경이 같았다. 앱/migrator 자격증명은 섞지 않았고 실제 로컬 쓰기는 꺼 둔 채 확인했다.
- Flyway 비활성 설정까지 반영한 최종 .\gradlew.bat build verifyWorkspaceProxy --no-daemon --no-configuration-cache --console=plain: BUILD SUCCESSFUL (4분 47초). 기본 Java 199개와 별도 프록시 1개 실패/오류/skip 0이다. 실행 JAR의 생성 Controller·Flyway 비활성 설정과 privileged runner/정책/Flyway 라이브러리/자격증명 파일 미포함도 확인했다. Vue 코드/의존성을 바꾸지 않아 프론트 테스트 31개와 npm audit은 반복하지 않았다. 다음 한 작업은 실제 로컬 최소 쓰기 권한 적용과 첫 생성 HTTP 확인이다.

## 실제 로컬 최소 INSERT 적용·첫 생성 HTTP (2026-11-03)

- 사용자 승인 뒤 수행했다. 사전 데이터는 5/2/5, 두 대상 INSERT=false, DB healthy였다. 기존 IDE 8080/PID 28676은 유지하고 18081을 사용했다.
- backend의 .\gradlew.bat bootJar --no-daemon --no-configuration-cache --console=plain: BUILD SUCCESSFUL/UP-TO-DATE(7초).
- 루트에서 & ./backend/build/local-create-verification/apply-and-verify.ps1: 성공. Git 제외 build 아래 임시 작업물이 ./infra/provision-workspace-create.ps1을 최초/반복 실행해 WORKSPACE_CREATE를 확인한 뒤 별도 JAR 프로세스에만 Alice/쓰기/허용 Origin을 전달했다. 관리자 설정은 앱에 주지 않았다.
- POST는 정확히 한 번 전송했다. 요청의 앞뒤 공백을 제거한 FlowDesk Local Create Check f2937116bdf0가 ID 문자열 "3"으로 생성됐다. 201, 상대 Location=/api/workspaces/3, myRole=OWNER, no-store를 확인했다. X-User-Id에 Eve ID를 보내도 실제 DB OWNER는 Alice였다. 공간과 membership 각 한 행이며 현재 데이터는 5/3/6이다. 생성한 공간은 남겨뒀다.
- Alice 목록/상세 200, Eve 상세 404/목록 미노출, ownerId 추가 입력 400, 외부 Origin 403, 두 health 경로 200/UP을 확인했다. 신규 ID를 제외한 전체 기존 데이터/이력이 전후 같았다. 자격증명 3개/V1 SHA-256·부모 환경도 같았다.
- 최종 SELECT/INSERT는 workspaces와 workspace_memberships에 true/true, users는 true/false, history는 false/false다. 세 업무 테이블 UPDATE/DELETE/TRUNCATE와 직접 sequence 권한은 false다. V1 이력은 checksum=-1384934132/flowdesk_migrator/success=true로 유지됐다.
- ./infra/migrate-local.ps1 -Action Validate 성공. ./infra/seed-local.ps1 -Action Verify는 추가 업무 데이터로 예상 mismatch였다. 원본 seed/V1 수정·추가 데이터 삭제·권한 자동 회수/리셋은 하지 않았다.
- receipt.json은 POST 시도 전 상태와 생성 ID를 기록해 이 임시 작업의 불명확한 결과를 조회하도록 한다. 서버 멱등키 지원을 추가한 것은 아니다. 앱 로그는 공급된 앱/관리자 비밀번호 미노출 검사 후 같은 build 디렉터리에 저장했다.
- 검증 앱 두 개를 종료하고 18081 listener 0을 확인했다. 기존 PID 28676/8080은 그대로이며 /api/health·/actuator/health 각각 200/UP, PostgreSQL healthy다. 기존 IDE에 쓰기 활성화 설정을 자동 추가하지 않았다.
- 제품 코드/의존성 변경이 없어 기존 Java 199개·프록시 1개·PowerShell 52개·Vue 31개를 반복하지 않았다. 다음 한 작업은 Vue 생성 폼과 POST 연결이다.

## Vue 생성 폼과 실제 Chrome/격리 DB 검증 (2026-11-04)

- WorkspaceCreateForm.vue와 API의 createWorkspace/정규화/결과 불명 타입을 추가하고 부모의 목록 0페이지/새 상세 조회와 연결했다. 이름만 전송하고 201 계약을 확인한다. 중복 제출/자동 재전송·redirect를 막고, 거부/결과 불명·생성 성공/후속 조회 실패를 분리한다. 새 의존성·백엔드/DB 스키마 변경은 없다.
- frontend에서 npm test: 5개 파일/58개 통과. npm run build: vue-tsc 타입 검사·Vite dist 성공. 최초 타입 검사에서 테스트 fetch mock의 빈 인수 tuple 오류가 나 타입을 명시했고 최종 두 명령은 모두 통과했다.
- 루트에서 & ./backend/build/form-browser-verification/verify.ps1 성공. UUID Compose PostgreSQL에 기존 도구로 V1·seed·읽기/최소 INSERT를 준비한 뒤 frontend/.cache/workspace-create-browser.mjs로 실제 Chrome/Vite/앱을 실행했다. 임시 준비 스크립트의 명명 인수 전달과 Vite SPA root 경로를 조정한 재실행에서 전 흐름이 통과했다. 실패한 프로젝트도 각각 정리했고 로컬 상태를 보존했다.
- 최종 프로젝트 flowdesk-migration-check-9567e2e8119448ad8933117fadd4f04b에서 빈 이름은 네트워크 전에 차단, 연속 submit은 한 POST, 201 후 새 상세/OWNER 선택·입력 초기화를 확인했다. 1280px/390px 스크린샷을 확인했고 가로 넘침이 없었다.
- Chrome Fetch 응답 단계에서 두 번째 POST의 커밋한 201만 실패로 바꿨다. 결과 불명 안내/제출 잠금이 표시되고 강제 submit/목록 확인/명시적 입력 초기화 동안 추가 POST는 없었다. 목록 GET에는 저장된 Browser Lost Response가 나타났다. 이후 쓰기를 끈 앱의 세 번째 POST는 405로 거부되며 입력이 유지되고 결과 불명으로 표시되지 않았다.
- 격리 DB의 최종 사용자/공간/멤버십 5/4/7과 Browser Created Workspace·Browser Lost Response 두 공간의 Alice OWNER 관계를 확인했다. 생성된 뒤 응답이 유실돼도 미생성이라고 단정할 수 없음을 실제 HTTP/DB로 검증했다. 이 두 공간은 격리 DB에만 생성됐다.
- 생성한 앱/Vite/Chrome·격리 컨테이너/네트워크/볼륨과 임시 자격증명을 정리했다. 기존 로컬 업무 데이터 5/3/6/이력/relation ACL/시퀀스 스냅샷과 자격증명 3개/V1 해시가 전후 같았다. 스크린샷/DOM은 frontend/.cache/workspace-create-check에 둔다. 모든 임시 검증물은 Git 제외다.
- 백엔드/infra 제품 코드를 바꾸지 않아 Java 199개/별도 프록시 1개·PowerShell 전체 경로 52개는 반복하지 않았다. 실행 안내의 명시적 쓰기/Origin 설정이 필요하며 기존 IDE 서버 설정은 자동으로 바꾸지 않았다. 다음 한 작업은 워크스페이스 이름 수정 API의 역할별 권한·트랜잭션 범위 확정이다.


## 이름 수정 API·V2·최소 UPDATE 구현 검증 — 2026-11-04

- 요청 범위: 확정한 OWNER/ADMIN 수정·버전 충돌 계약을 구현하고 격리 PostgreSQL에서 검증한다. 실제 로컬 V2·GRANT·업무 수정은 실행하지 않는다.
- 구현 흐름: PATCH strict JSON/name·version → 기존 개발 쓰기/Origin 경계 → Service 소속/역할/요청 버전 → 관리 Entity rename·OPTIMISTIC/flush → commit → version DTO/200. no-op도 종료 시 버전을 검사한다.
- V2는 version bigint/default 0/nonnegative CHECK만 추가한다. GET/POST/PATCH에 문자열 버전을 반환하고, 별도 관리자 도구는 workspaces UPDATE(name, updated_at, version)만 부여한다. 정확한 WORKSPACE_RENAME 상태와 이력 1,2를 공통 정책에 추가했다.
- 첫 backend `./gradlew.bat test --no-daemon --console=plain`은 244개 중 음성 입력 두 건이 실패했다. id=id 문장은 identity 규칙의 428C9가 권한 검사보다 먼저 발생해 id=DEFAULT WHERE false로 수정했고, HTTP 인코딩에서 ?로 바뀌던 surrogate는 JSON escape로 전송하도록 수정했다. 기능 코드 실패로 숨기거나 단순히 예상 상태를 완화하지 않았다.
- 다음 `./gradlew.bat build verifyWorkspaceProxy --no-daemon --console=plain`의 기본 test는 13개 클래스/245개 모두 통과했다. 신규 수정 HTTP 34개·권한 10개·V2 1개와 누락 version 스키마 검증 추가를 포함한다. 병렬 HTTP 두 트랜잭션을 행 잠금 뒤에 대기시켜 동일 버전 UPDATE 경합에서 정확히 200/409와 단일 version 증가를 확인했다. no-op의 외부 트랜잭션 종료 시 충돌, 일반/지연 commit 실패의 롤백도 통과했다.
- 같은 명령의 별도 프록시 단계는 새 PATCH 요청의 시간초과로 전체 종료 코드가 1이었다. 기존 POST와 동일하게 HTTP/1.1을 명시한 뒤 `./gradlew.bat verifyWorkspaceProxy --no-daemon --console=plain`은 1개/종료 0으로 통과했다. 실제 Vite→앱에서 생성 201/초기 0, 수정 200/버전 1, 외부 Origin POST/PATCH 403과 데이터 보존을 확인했다.
- 마지막 `./gradlew.bat build -x test --no-daemon --console=plain`은 종료 0/패키징 성공이다. 통과한 245개 뒤에는 프록시 테스트의 전송 설정만 변경했으므로 기본 테스트를 반복하지 않았다. 앱 JAR에 V2를 포함하고 관리자/seed 실행기·권한 SQL·Flyway 라이브러리·.env가 없음을 검사했다.
- frontend `npm test`: 5개 파일/58개 통과. `npm run build`: 타입 검사·Vite 성공. Vue 수정 화면은 이번 범위가 아니며 기존 조회/생성의 추가 version 필드 호환을 유지한다.
- PowerShell 전체 경로 결과와 격리 정리/실제 로컬 보존 확인은 아래 최종 결과에 기록한다.


### 이름 수정 검증 최종 결과

- 루트 `./infra/verify-migration.ps1 -IncludeWorkspaceRename` 최종 종료 0, migration/seed/read/create/rename 전체 60개 검사 통과. 과도한 컬럼/부분 상태는 자동 복구하지 않고 거부하며 기존 관리자/앱 자격증명과 부모 환경을 보존했다.
- 최초 PowerShell 실행은 수정 전 id=id 음성 SQL을 메모리에 갖고 있어 제가 시작한 해당 프로세스 트리만 중지하고 flowdesk-migration-check-f3779bb9edd04bb9b081c6d81820dcd0의 컨테이너/네트워크/볼륨·임시 자격증명을 정리했다. 수정본 최종 실행은 flowdesk-migration-check-7143002b840842f0b96b2699b16cd84d에서 통과했다. 최종 프로젝트도 finally 정리됐고 두 프로젝트의 Docker 자원/임시 자격증명 잔여 0건을 확인했다.
- 실제 로컬은 읽기 전용 스냅샷의 JSON 내용으로 사용자/공간/멤버십·전체 이력·relation ACL이 전후 같음을 확인했다. PowerShell native 출력의 복수 줄 문자열 비교는 표현 차이로 불일치해 JSON 내용 비교로 확인했다. 데이터 5/3/6, 이력 1만 존재, version 컬럼 0개, 앱 table UPDATE/name UPDATE 모두 false다. 실제 V2/GRANT/수정은 미실행이며 PostgreSQL healthy다.
- infra/.env·infra/.env.migrator·backend/.env와 원본 V1의 SHA-256이 전후 같다. 보존 스냅샷은 Git 제외 backend/build/rename-verification에 보관한다.
- 최종 JUnit XML 합계: 기본 13개 클래스/245개, 별도 실제 Vite 1개, 실패/오류 각각 0. 프론트 58개/타입 검사·빌드 성공. 앱 JAR 경계와 PowerShell 15개 문법도 확인했다. 변경한 문서 링크/HTTP 예제/ADR 단일성은 별도 검사했다.
- 다음 한 작업: 실제 로컬 V2·최소 UPDATE 적용과 새 앱 기동·이름 수정/충돌 확인. 그 전에는 V1 DB에 새 앱을 재기동하지 않는다. 기본 프로필에도 JPA validate용 V2가 필요하며 쓰기를 켜려면 추가로 WORKSPACE_RENAME 권한/기존 Origin 설정이 필요하다. Vue 수정 폼은 이후다.


## 실제 로컬 V2·최소 UPDATE·새 앱 이름 수정 확인 — 2026-11-04

- 사용자 요청으로 실제 flowdesk-local / flowdesk DB에 적용했다. 시작 시 8080/18081 앱과 flowdesk_app 연결이 없고 V1·사용자/공간/멤버십 5/3/6이었다. 기존 비-seed 공간 ID 3(Alice OWNER)만 이름 수정 대상으로 골랐다.
- 변경 전 전체 업무 행·history·relation owner/ACL·컬럼 ACL·시퀀스 값과 자격증명 3개/V1/V2/검증 JAR의 SHA-256을 기록했다. 영수증은 Git 제외 backend/build/local-rename-verification/receipt.json이다.
- 루트에서 `./infra/migrate-local.ps1 -Action Info`: V1 SUCCESS/V2 PENDING. `-Action Migrate`: 1건. `-Action Validate`: 성공. 이어 `./infra/provision-workspace-rename.ps1` 최초/반복 모두 WORKSPACE_RENAME/기존 비밀번호 유지, `./infra/migrate-local.ps1 -Action Migrate` 재실행 0건을 확인했다. 모두 종료 0이다.
- V2/GRANT 직후에는 기존 업무 값/시각·V1 history·소유권/relation ACL·시퀀스가 같고 공간 version만 모두 0으로 추가됐음을 확인했다. 스냅샷을 PowerShell JSON으로 보관하면서 UTC 시각이 +09:00로 직렬화돼 문자열 비교가 실패했으나, 동일 시각으로 정규화한 비교에서 모두 같았다. 이후 보존 비교는 시간대를 정규화했다.
- `& ./backend/build/local-rename-verification/verify-http.ps1` 종료 0. 기존 검증 JAR를 127.0.0.1:18081에 actor별로 순차 기동하고 앱 환경에서 관리자/migrator/전역 Spring 설정을 제거했다. 앱은 backend/.env의 기존 flowdesk_app 자격증명만 사용했다. 개발 actor/쓰기/Origin은 자식 프로세스에만 전달했고 부모 환경/자격증명 파일은 바꾸지 않았다.
- Alice의 ID 3 PATCH를 딱 한 번 전송해 200/공백 정규화/OWNER/version 0→1을 확인했다. 현재 이름은 `FlowDesk Local Rename Check 77d6875a385e`다. 요청 직전 PATCH_ATTEMPTED 영수증을 저장하고 응답 불명 시 자동 재전송하지 않도록 했다. 최종 영수증 상태는 VERIFIED다.
- GET 목록/상세 200·새 이름/version 1, 이전 version 0 재요청 409/WORKSPACE_VERSION_CONFLICT, 같은 정규화 이름의 no-op 200/버전·updated_at 유지, 알 수 없는 role 필드 400, 외부 Origin 403을 확인했다. 거부/no-op 뒤 DB 행이 같았다.
- Bob ADMIN은 seed A에 같은 이름/version의 no-op 200으로 자격을 확인했고 seed 행은 바꾸지 않았다. Dave VIEWER는 위조 actor 헤더를 보내도 403/WORKSPACE_FORBIDDEN, Eve의 ID 3 미소속과 Long.MAX_VALUE 미존재 PATCH는 모두 404/WORKSPACE_NOT_FOUND였다.
- 쓰기를 끈 Alice 앱은 GET 200/PATCH 405/Actuator UP이었다. 쓰기 앱에서도 /api/health·/actuator/health가 200/UP이고 pg_stat_activity에서 실제 flowdesk_app 연결을 확인했다. 검증 앱 5개는 각 finally에서 종료했고 최종 8080/18081 리스너가 없었다. 기존 사용자 앱 프로세스를 종료하거나 IDE 설정을 바꾸지 않았다.
- 최종 DB: 데이터 5/3/6, ID 3의 name/updated_at/version만 변경, 두 seed 공간 및 사용자/멤버십/시퀀스 값은 그대로다. V1 history 그대로/V2만 추가(success, installed_by=flowdesk_migrator). 기존 relation owner/ACL은 같고 컬럼 UPDATE는 workspaces name/updated_at/version 세 개만 허용한다. 테이블 전체 UPDATE·다른 컬럼/테이블 UPDATE·users INSERT·DELETE/TRUNCATE·DDL·sequence/history 접근은 계속 금지다.
- 최종 자격증명 3개/V1/V2/JAR 해시가 전후 같고 PostgreSQL healthy다. before/after-schema/after-http/privileges 스냅샷과 비밀을 제거한 로그는 Git 제외 backend/build/local-rename-verification에 보관했다.
- 제품 코드/의존성을 변경하지 않아 이미 통과한 Java 245개/별도 Vite 1개·PowerShell 격리 60개·프론트 58개/빌드는 이번에 반복하지 않았다. 이번 결과는 실제 로컬 도구·JAR 기동·HTTP/DB 검증이다.
- 다음 한 작업: Vue 이름 수정 폼·버전 보존·409 충돌 안내 연결. DB 권한과 개발 쓰기 활성화는 별개이므로 IDE의 일반 실행에서 자동으로 쓰기가 켜지는 것은 아니다.


## Vue 이름 수정 폼·버전 보존·409 안내 — 2026-11-05

- 사용자 요청 범위: 기존 이름 수정 API를 Vue에 연결하고 버전 보존/충돌 복구를 검증한다. 백엔드·V2·권한·의존성은 변경하지 않는다. 실제 로컬은 V2/5·3·6·ID 3 version 1을 유지한다.
- src/api/workspaces.ts는 목록/상세/생성의 version을 문자열로 검증·보존하며 name/version만 보내는 renameWorkspace와 결과 불명 타입을 추가했다. POST의 초기 version 0도 검사한다. WorkspaceRenameForm은 편집 기준/draft/진행/거부·409/결과 불명/최신 GET을 분리하고, 부모 WorkspaceBrowser는 같은 ID의 상세·현재 목록 행만 갱신한다. 스타일은 기존 상세 패널/모바일 규칙을 사용한다.
- frontend `npm test`: 기존 58개와 신규 버전/수정 API 32개·수정 폼/연결 11개, 7개 파일/101개 통과. `npm run build`: vue-tsc 타입 검사/Vite 성공. 마지막 disabled cursor 스타일 반영 후 빌드도 성공했다. 처음 기존 58개 확인과 전체 101개 검증을 구분했다.
- 루트 `& ./backend/build/rename-browser-verification/verify.ps1`: 종료 0. 고유 Compose 프로젝트 flowdesk-migration-check-be2ab8ee93ac42f2a6e991443c8afe00에 기존 전용 도구로 V1/V2·seed·read/create/rename 권한을 준비하고 frontend/.cache/workspace-rename-browser.mjs로 실제 Chrome/Vite/기존 JAR를 실행했다. fake DB URL은 flowdesk_verify만 허용하고 모든 자식 환경에서 실제 앱/관리자/migrator 설정을 제거했다.
- Chrome에서 빈 이름은 PATCH 없이 거부, 연속 submit은 한 PATCH/name·version 0, 성공 후 상세/목록 이름 갱신을 확인했다. 실제 별도 HTTP 클라이언트가 version 1을 먼저 변경해 2로 만든 뒤 브라우저의 stale 요청은 409였고 draft/시작 버전 1·잠금이 유지됐다. 최신 GET에도 draft가 유지되고 저장은 잠겼으며 명시적 기준 갱신은 PATCH를 보내지 않았다. 그 뒤 수동 저장만 version 2로 전송돼 3이 됐다.
- 다음 PATCH는 서버에서 200으로 커밋해 version 4가 된 응답만 Chrome Fetch 응답 단계에서 유실시켰다. UI는 결과 불명/저장 잠금을 보이고 강제 중복 submit에도 재전송하지 않았다. 최신 GET에는 Browser Lost Rename이 나타났지만 성공 안내를 만들어 내거나 자동으로 저장 잠금을 풀지 않았다.
- VIEWER Dave의 실제 조회에서는 수정 폼이 없었다. 쓰기를 끈 Alice 앱에서는 PATCH 405/입력 유지가 결과 불명과 구분됐고 DB는 바뀌지 않았다. 1280px/390px 화면을 캡처하고 desktop/conflict/uncertain 이미지를 확인했으며 가로 넘침이 없었다. 스크린샷/DOM/비밀을 제거한 프로세스 로그는 frontend/.cache/workspace-rename-check다.
- 격리 DB 최종 5/2/5, A만 Browser Lost Rename/version 4, B는 FlowDesk Demo B/version 0이었다. UI PATCH는 정상/충돌/검토 후 저장/응답 유실/405 총 5회이며 별도 클라이언트의 선행 성공 1회와 구분한다. 검증 앱/Vite/Chrome·컨테이너/네트워크/볼륨·임시 자격증명 정리 완료를 확인했다.
- 실제 로컬의 데이터/history/relation ACL/시퀀스와 자격증명 3개/V1/V2 해시가 전후 같았다. 추가 읽기 전용 스냅샷을 이전 로컬 적용 최종 스냅샷과 대조해 컬럼 ACL/소유권까지 같고 ID 3 version 1이 유지됐음을 확인했다. JAR 해시도 같고 실제 로컬 PostgreSQL은 healthy다. 비교 스냅샷은 backend/build/rename-browser-verification/local-after.json이다.
- 백엔드/infra 제품 코드 변경이 없어 기존 Java 245개/별도 프록시 1개·PowerShell 전체 60개는 반복하지 않았다. 이번 실제 격리 Chrome 연결 검증은 기존 JAR를 사용한 별도 결과다. 문서/HTTP 예제/ADR 링크도 검증한다.
- 다음 한 작업: Project 데이터 모델·워크스페이스 역할별 생성/조회 권한 범위 확정. 실제 인증·삭제·프로젝트/작업 화면과 라우팅은 아직 후속이다.


## 첫 Project 데이터 모델·생성/조회 권한 확정 — 2026-11-05

- 사용자 선택: Workspace 멤버십 상속/네 역할 조회, OWNER·ADMIN만 생성, 동일 공간 내 이름 중복 허용. ProjectMembership/비공개 접근은 첫 범위에서 제외했다.
- project-model-and-api-v1.md에 모델 6개 컬럼/공간 FK·인덱스, 예정 V3, 중첩 POST/목록·상세, 입력/DTO/오류, Service 트랜잭션/부모 조건·인가, 최소 SELECT/INSERT와 PROJECT_CREATE/기존 도구·seed 전환·격리 수용 기준을 기록했다.
- 이번에는 문서만 변경했다. V3 SQL·Project 코드·GRANT·seed·실제 DB 작업은 실행하지 않았으며 기존 로컬 V2/5·3·6·ID 3 version 1은 변경 대상이 아니다. 문서 링크/HTTP JSON 예제/ADR 단일성만 검사하고 기능 테스트·빌드는 구현 전이어서 실행하지 않는다.
- 다음 한 작업: V3·Project 매핑·생성/조회 API·최소 권한 도구 구현과 격리 PostgreSQL 검증. 실제 로컬 적용과 Vue 프로젝트 화면은 이후 별도 단계다.


## 2026-11-05 — V3·Project 생성/조회·최소 권한 구현과 격리 검증

### 범위와 요청 흐름

`V3__create_projects.sql`에 projects/FK RESTRICT/공간별 ID 정렬 인덱스를 추가했다. ID·이름·선택 설명·시간을 JPA로 매핑하고, 스칼라 workspaceId와 조회 Repository 두 메서드를 사용한다. 기존 V1/V2는 변경하지 않았다.

HTTP → loopback/Origin·UTF-8 JSON 필터 → Controller 입력 DTO/32 KiB 제한 → Service 트랜잭션의 서버 행위자/부모 membership 검사 → 생성은 OWNER/ADMIN 확인 후 persist/flush → commit → 201/상대 Location 순서다. 목록/상세는 네 역할 모두 허용하고 부모+Project 조건으로 조회하며 미소속/미존재/부모 불일치는 동일 404다. 응답은 문자열 ID DTO이고 Entity·다른 사용자 정보를 노출하지 않는다. Workspace 본문은 기존 4 KiB를 유지한다.

전용 `provision-project-create.ps1`/JavaExec/관리자 main은 정확한 WORKSPACE_RENAME에서 PROJECT_CREATE로 전환한다. 새 권한은 projects SELECT/INSERT만이며 기존 컬럼 UPDATE 제한을 보존한다. 새 상태의 일부/과도한 ACL과 잘못된 자격증명을 거부한다. 기존 도구는 완전한 PROJECT_CREATE를 보존하고 seed는 projects가 비어 있어야 한다. 새 의존성/실제 인증/Vue Project 기능은 추가하지 않았다.

### 실제 실행 명령과 실패 수정

- JDK 21로 `./gradlew.bat compileJava compileMigrationJava compileTestJava --no-daemon --console=plain` 성공. 최초 sandbox에서는 Gradle 네트워크/캐시 접근이 거부되어 허용된 실행 경계로 검증했다.
- 첫 통합 테스트는 Docker 엔진이 꺼져 있어 컨테이너 생성 전에 실패했다. Docker Desktop 시작 후 격리 PostgreSQL로 다시 실행했다.
- 첫 Project 48건 중 46건 통과/2건 실패. 시퀀스를 composite row로 읽은 테스트 SQL과 기동 헬퍼의 자동 권한 보충을 수정했다. 헬퍼가 테스트 대상을 자동 수선하지 않도록 명시적 준비 단계에서만 GRANT한다.
- `./gradlew.bat build verifyWorkspaceProxy --no-daemon --console=plain`: 전체 297건 중 290건 통과/health 7건 실패. Project 쓰기 어댑터가 기본 프로필에도 등록돼 JPA를 제외한 health 테스트가 EntityManager를 요구했다. 어댑터도 dev-actor + 쓰기 설정 조건으로 등록하도록 수정했다. 이 실행에서는 실패로 Vite 작업에 도달하지 않았다.
- 최종 영향 구간 재검증은 `--init-script build/project-final.init.gradle --project-cache-dir build/project-final-cache`로 출력을 분리했다. 나머지 인수: `test --tests '*ProjectApiTest' --tests '*V3MigrationTest' --tests '*JpaSchemaValidationTest' --tests '*Health*Test' verifyWorkspaceProxy build --no-daemon --console=plain`. 기존 실패 7건, Project HTTP, V3/ACL 보존, 새 Project 누락 스키마 검사를 포함한다.
- 기본 앱 JAR는 `./gradlew.bat build -x test --no-daemon --console=plain`로 다시 생성한다. 위 테스트를 생략했다는 뜻이며 테스트 실행 결과와 구분한다.
- 별도 실제 PowerShell 경로: `./infra/verify-migration.ps1 -IncludeProjectCreate` → **69개 통과**. 고유 Compose 프로젝트/임시 자격증명을 사용했다. 부모 환경/자격증명 보존·출력 비밀번호 미노출을 확인했고, 테스트 컨테이너/볼륨/네트워크와 세 임시 자격증명 파일을 정리했다.
- 프론트 회귀: `npm test` → 7파일/101개 통과, `npm run build` → 타입 검사/Vite 빌드 통과.

최종 Java 영향 구간 55건/실제 Vite 프록시 1건과 해당 build는 모두 통과했다. 기본 위치 JAR 재패키징도 성공했다. 초기 전체 회귀와 최종 클래스별 재검증을 합치면 중복 제외 298건의 마지막 결과가 통과다. 전체 297건을 수정 후 다시 한 번 모두 실행한 것은 아니며, 실패한 health 7건·Project 43건·V3 1건·스키마 4건을 최종 코드로 재검증했다. 집계는 `backend/build/project-full-results.json`, `project-final-results.json`, 최종 HTML은 `backend/build/project-final/reports/tests/test/index.html`과 `verifyWorkspaceProxy/index.html`에 남겼다. 최초 전체 실행의 실패 기록도 보존한다.

### 로컬 보존과 다음 단계

실제 `flowdesk-local-postgres-1`에는 읽기 전용 조회만 수행했다. 결과는 `1,2|5/3/6|projects_absent=true|workspace3_version=1`이다. 이 작업에서는 실제 로컬 V3/Project GRANT/seed/이름 변경을 실행하지 않았다. 추가로 기존 `local-rename-verification/after-http.json`과 전체 users/workspaces/memberships·history·관계 소유권/ACL·컬럼 ACL·시퀀스를 비교했고, 시간대 표기만 UTC로 정규화했을 때 모두 동일했다. 새 앱은 V3와 필요한 SELECT/INSERT 적용 전 기동에 실패할 수 있다. 다음 한 작업은 [계약의 순서](project-model-and-api-v1.md)에 따른 실제 로컬 V3·최소 Project 권한 적용과 새 앱의 생성/조회 확인이다.


## 2026-11-05 — 실제 로컬 V3·Project 최소 권한과 새 앱 확인

### 목표와 적용 범위

사용자 요청으로 `flowdesk-local-postgres-1`의 V2/WORKSPACE_RENAME에서 V3/PROJECT_CREATE로 전환했다. 시작 전 8080/18081 리스너와 flowdesk_app DB 연결은 없었고 데이터는 사용자 5명/공간 3개/멤버십 6개였다. 전용 migrator와 관리자 권한 도구를 사용하고 앱 부팅의 Flyway/DDL 자동 적용은 계속 금지한다. 기존 공간의 이름·버전·멤버십을 바꾸지 않고 Workspace 1에 Project 한 건만 생성했다.

### 실행한 명령과 결과

저장소 루트에서 JDK 21을 지정하고 다음 실제 로컬 명령을 순서대로 실행했다. 비밀번호는 기존 무시 파일에서 전용 자식 환경으로만 전달했다.

```powershell
./infra/migrate-local.ps1 -Action Info
./infra/migrate-local.ps1 -Action Migrate
./infra/migrate-local.ps1 -Action Validate
./infra/provision-project-create.ps1
./infra/provision-project-create.ps1
./infra/migrate-local.ps1 -Action Migrate
```

- Info에서 V1/V2 성공과 V3 PENDING을 확인했다. 최초 Migrate는 **1건**, Validate 성공, 마지막 Migrate는 **0건**이다. V1/V2의 설치 행/체크섬은 그대로이며 V3 성공 행만 추가됐다.
- Project 권한 도구의 최초/반복 실행 모두 **PROJECT_CREATE**다. projects SELECT/INSERT만 추가했고 UPDATE/DELETE/TRUNCATE·sequence/history·schema CREATE 권한은 없다. 기존 세 컬럼 UPDATE를 포함한 이전 ACL/소유권은 유지됐다. Project 테이블/시퀀스/인덱스 소유자는 migrator이고 FK RESTRICT와 `(workspace_id,id)` 인덱스가 존재한다.
- 새로 빌드된 기존 JAR를 `backend/build/local-project-verification/verify-http.ps1`에서 18081에 숨겨진 자식 프로세스로 실행했다. 명시적 dev-actor/서버 행위자와 자식 환경의 앱 DataSource 자격증명을 사용했다. 실행 파일/설정은 변경하지 않았다.

### HTTP → Service → DB → 응답 확인

OWNER Alice의 `POST /api/workspaces/1/projects`를 단 한 번 보내 **201**, 문자열 ID **1**, 상대 `Location: /api/workspaces/1/projects/1`, 정규화된 이름/설명을 확인했다. 서버가 부모 소속·OWNER 역할을 검사하고 프로젝트 한 행을 저장/커밋한 후 DTO를 반환했다. DB에도 같은 ID/부모/이름/설명·생성/수정 시각이 존재하고 실제 flowdesk_app 연결이 관찰됐다. POST 시도 전 기록과 수신 ID를 남겨 결과 불명 시 자동 재전송을 막았다.

- 목록·상세 **200**과 요약/상세 DTO 차이를 확인했다. ADMIN Bob·MEMBER Carol·VIEWER Dave 모두 같은 Project 상세 **200**이다.
- VIEWER 생성은 **403 / PROJECT_FORBIDDEN**이다. 클라이언트의 X-User-Id/X-Role로 OWNER를 위조해도 차단됐다.
- Alice가 Workspace 3도 소유하지만 `/api/workspaces/3/projects/1`은 **404 / PROJECT_NOT_FOUND**다. 다른 부모 목록에 Project가 섞이지 않는다.
- Eve의 미소속 목록/상세/생성과 없는 공간/프로젝트는 같은 안전한 **404**다. 자기 공간 2의 빈 목록은 정상 **200/빈 배열**이다.
- 외부 Origin은 **403 / DEV_ACCESS_DENIED**, JSON 부모 필드 위조는 **400 / INVALID_REQUEST**다.
- 쓰기 off 앱은 상세 **200**, POST **405**, Actuator **200 UP**이다. 쓰기 on에서도 `/api/health`·`/actuator/health` **200 UP**을 확인했다.

### 보존·정리·검증 한계

최종 데이터는 **5명/3개 공간/6개 멤버십/Project 1개**다. Project ID 1은 Workspace 1에 속한다. 기존 전체 행/버전(공간 3 version 1 포함), V1/V2 이력, 이전 관계/컬럼 ACL·소유권, 기존 시퀀스가 전후 스냅샷에서 동일했다. 새 Project 시퀀스만 last_value 1이다. 기존 자격증명 파일 3개·V1/V2/V3·JAR의 SHA-256 **7개**가 같고 부모 프로세스 환경도 유지됐다.

소유한 검증 앱 6개를 모두 종료했다. 종료 후 8080/18081 리스너와 flowdesk_app DB 연결은 **0**이며 영구 쓰기 설정은 바꾸지 않았다. 실제 DB를 중지하거나 기존 데이터를 삭제/초기화하는 검증은 하지 않았다. seed Apply/Verify도 실행하지 않았다(기존 추가 Workspace/Project 업무 데이터는 예제 전체 일치 조건을 만족하지 않음).

이번에는 앱/SQL/권한 도구 소스를 바꾸지 않아 빌드·JUnit/격리 PowerShell·Vue 회귀를 반복하지 않았다. 앞선 합산 Java 298개/프록시 1개·PowerShell 69개·Vue 101개 결과와 이번 실제 로컬 HTTP/DB 결과를 구분한다. 이번 변경 파일은 결과/현재 상태 문서와 Git 제외 검증 기록이며, `backend/build/local-project-verification/receipt.json`은 **VERIFIED**다. 새 소스 변경이 없어 재현용 HTTP 스크립트 구문 검사와 문서 링크를 추가 확인했다.

다음 한 작업은 **Vue의 Project 목록·상세 조회 연결**이다.


## 2026-11-05 — Vue Project 목록·상세 조회 연결

### 범위와 변경 파일

`frontend/src/api/projects.ts`, `components/ProjectBrowser.vue`를 추가하고 기존 `WorkspaceBrowser.vue`·`style.css`에 연결했다. 프로젝트 목록·상세/nullable 설명, 로딩·빈 첫/뒤 페이지·오류/재시도·페이지 이동을 구현했다. 부모 상세가 유효할 때만 공간 ID key로 ProjectBrowser를 등록하며 공간 전환은 페이지/선택/진행 요청을 종료한다. 같은 공간 이름 수정은 자식 상태를 유지한다. Project 생성/수정 UI·실제 인증·Router/Pinia/Axios·새 의존성은 추가하지 않았다.

Vue 선택 → 부모 하위 GET → 기존 Vite/개발 접속 필터 → Controller → readOnly ProjectQueryService의 서버 행위자/부모 membership → workspace_id 조건 Repository/DB → 요약/상세 DTO → 화면 순서다. 프론트는 문자열 ID/Long 범위·부모·정렬/페이지·Unicode 이름/설명 필드를 unknown에서 검증하고, 안전한 오류 코드만 표시한다. 설명은 텍스트 바인딩/pre-wrap으로 표시한다. 404/프록시 오류를 빈 목록으로 취급하지 않는다.

### 실행한 테스트·빌드

frontend에서 `npm test` → **9파일/144개 통과**다. 기존 101개 + Project API 33개 + 화면/부모 연결 10개다. 새로운 `projects-api.spec.ts`/`ProjectBrowser.spec.ts`에서 부모/ID/페이지·오류 계약·nullable/Unicode/HTML 텍스트·시간 제한/취소, 늦은 상세 응답·페이지/새로고침·부모 전환/언마운트를 검증했다.

기존 Workspace 테스트의 순서 기반 mock이 새 Project GET을 소비해 1건 실패했으므로 `__tests__/support/workspace-fetch.ts`로 경로별 응답을 구분했다. Workspace 생성/수정 회귀의 의미는 유지하고 Project 부모 전환은 별도 통합 테스트로 확인했다. 처음 타입 검사에서 테스트 헬퍼 함수의 매개변수 범위가 너무 넓어 실패했고 문자열 경로 계약으로 바로잡았다.

`npm run build` → **vue-tsc 타입 검사·Vite 빌드 성공**이다. 최종 25모듈, CSS 7.36 kB/JS 90.74 kB다. 모바일 캡처에서 페이지 버튼의 긴 문구가 줄바꿈되어 표시 문구를 이전/다음 페이지로 줄이고 접근성 이름은 Project 페이지로 유지한 뒤 전체 테스트/빌드와 브라우저 검증을 다시 통과했다.

### 실제 Chrome·Vite·로컬 API 검증

JDK 21 환경에서 `backend/build/project-read-browser-verification/verify.ps1`를 실행했다. 실제 로컬 V3/PROJECT_CREATE와 기존 JAR를 사용하되 서버의 쓰기 플래그는 false로 두었다. 소유한 숨겨진 임시 앱·Vite·Chrome만 사용했다.

- Alice가 Workspace 1의 실제 Project ID 1 목록/상세·설명을 확인하고, Workspace 3의 빈 목록으로 전환했다. 이전 상세의 실제 200 응답을 Chrome에서 지연한 상태로 공간을 바꾸고 해제해도 예전 Project가 새 공간에 표시되지 않았다.
- 503 안내/재시도는 Project 목록의 HTTP 응답만 Chrome에서 제어해 확인했다. 서버 오류 원문은 DOM에 나오지 않았고 수동 재시도는 실제 GET으로 정상 복구했다. 실제 DB를 중지시키지는 않았다.
- VIEWER Dave도 같은 Project를 조회했고, Eve의 자기 공간 2는 빈 목록이며 Alice의 Project가 남지 않았다. 실제 서버 인가·부모 조건은 그대로다.
- 1280 px 데스크톱/390 px 모바일의 최종 캡처를 확인했다. 가로 넘침과 미처리 브라우저 예외는 없었다. 브라우저 API 요청은 **22건/모두 GET**, 쓰기 요청은 **0건**이다.

최종 `frontend/.cache/project-read-check/{desktop.png,mobile.png,error.png,dom.html,summary.json}`과 마스킹된 프로세스 로그를 보관했다. 검증 앱 3개·Vite·Chrome을 모두 종료하고 검증 포트 리스너가 남지 않았음을 확인했다.

### 보존·미실행·다음 작업

실제 로컬의 사용자/공간/멤버십/Project 전체 행·V1/V2/V3 이력·관계 소유권/ACL·컬럼 권한·시퀀스는 실행 전후 스냅샷이 동일했다. 실제 데이터는 계속 **5/3/6/1**이다. 기존 자격증명 파일 3개·V1/V2/V3·JAR의 SHA-256 7개도 유지했다.

백엔드/SQL/권한/의존성을 바꾸지 않아 백엔드 빌드·JUnit/격리 PowerShell 검증과 DB migration/GRANT/seed는 반복하지 않았다. 앞선 Java 합산 298개/프록시 1개·PowerShell 69개와 이번 프론트 144개·실제 브라우저 결과를 구분한다. [프론트 실행/흐름](../frontend/README.md), ADR-038/학습 노트도 갱신했다.

다음 한 작업은 **Vue Project 생성 폼·POST 연결**이다.


## 2026-11-05 — Vue Project 생성 폼·POST 연결

### 목표·파일·처리 흐름

`frontend/src/components/ProjectCreateForm.vue`를 추가하고 `api/projects.ts`·`ProjectBrowser.vue`·`WorkspaceBrowser.vue`·`style.css`에 연결했다. 부모 Workspace의 OWNER/ADMIN에만 폼을 표시하고 MEMBER/VIEWER는 조회/권한 안내를 유지한다. UI 역할은 힌트이며 기존 서버 인가/트랜잭션/최소 권한을 바꾸지 않았다. 이름/선택 설명만 입력받고 부모는 URL로 고정한다.

폼의 정규화/검증·진행 가드 → `createProject`의 name/description POST → Vite/Origin/strict JSON → 서버 행위자·부모 소속/OWNER·ADMIN → Project 저장/commit → 201/상대 Location/DTO 확인 → 성공 안내 → 목록 0페이지/새 ID 상세 GET 순서다. Java 공백/Unicode 길이·제어 문자/CRLF를 서버와 같은 기준으로 처리하고 빈 설명은 null이다. 요청은 10초 제한/credentials=omit/no-store/redirect=error다.

확정 거부 400/403/404/405/413/415와 결과 불명(네트워크·시간 초과·5xx·잘못된 201/Location/DTO)을 구분한다. 결과 불명에서는 draft/잠금을 유지하고 목록 확인은 GET만 한다. 이름 중복 허용이므로 이름 일치로 특정 POST 성공을 단정하지 않는다. 입력 초기화가 있어야 새 요청을 시작하며 자동 재전송하지 않는다. 201 후 GET 실패도 완료한 생성을 실패로 바꾸지 않는다. 연속 생성/새 탐색/종료 시 이전 후속 GET의 늦은 자동 상세 선택을 막는다.

### 실행한 테스트와 빌드

- frontend에서 `npm test`: **11파일/194개 통과**. 기존 144개 + `project-create-api.spec.ts` 35개 + `ProjectCreateForm.spec.ts` 15개다.
- 생성 API/폼 테스트는 정규화·최대 Unicode/nullable·잘못된 부모/필드, 201/Location/DTO, 확정 거부/네트워크/잘못된 성공의 결과 불명, 중복 제출, 네 역할 폼 표시, 입력 보존/잠금/GET 확인·초기화, 언마운트/늦은 이벤트, 후속 목록 실패/페이지 밖 ID/연속 생성의 늦은 자동 선택을 확인한다.
- `npm run build`: **vue-tsc 타입 검사·Vite 빌드 성공**, 최종 27모듈/CSS 8.25 kB·JS 96.66 kB다. 최종 코드로 전체 테스트와 빌드를 확인했다.

### 실제 Chrome·Vite·격리 PostgreSQL

JDK 21 환경에서 `backend/build/project-create-browser-verification/verify.ps1`를 실행했다. 고유 Compose `flowdesk-migration-check-3e39897b680049b4b649439c2f802090`의 flowdesk_verify만 사용하고, 별도 임시 자격증명으로 기존 실제 migration/seed/read/create/rename/Project 도구를 준비했다. Node 검증기는 대상 URL이 flowdesk_verify인지 확인하고 실제 로컬 설정은 자식 앱에 전달하지 않았다.

- 이름/설명 오류는 전송 **0건**, 두 번 submit해도 실제 POST **1건**이다. 정상 201/정규화된 이름·설명/부모·상대 Location을 검증하고 목록/새 상세 선택을 확인했다. `<em>` 문자열은 일반 텍스트이며 실행/HTML 렌더링되지 않는다.
- 두 번째 생성은 DB에 커밋된 **201 응답만 Chrome에서 유실**시켰다. 결과 불명 잠금/입력을 유지하고 submit 재호출/GET 목록 확인·상세 조회 후에도 POST가 추가되지 않았고 폼도 성공 상태로 바뀌지 않았다. 빈 설명은 null이다.
- VIEWER Dave의 Project 생성 폼은 없고 같은 공간의 Project 조회는 가능하다. OWNER Alice의 쓰기 off 앱에서는 POST **405**를 안전하게 안내하며 이름/설명은 보존한다.
- 브라우저 Project POST는 **3건(201 확인 1 + 커밋 응답 유실 1 + 405 거부 1)**이고 실제 생성 행은 **2개**다. 격리 데이터는 최종 **5/2/5/2**, 두 Project 모두 Workspace 1에 속하며 Workspace 버전은 0을 유지했다.
- 1280 px 데스크톱/390 px 모바일의 정상·결과 불명 캡처를 확인했고 가로 넘침이 없다. 캡처/DOM/요약·마스킹 로그는 `frontend/.cache/project-create-check`에 있다.

### 보존·정리·미실행 항목

소유한 임시 앱 3개·Vite·Chrome과 고유 컨테이너/볼륨/네트워크를 정리했다. 임시 자격증명 세 파일도 삭제됐음을 별도 확인했다. 실제 로컬 전체 사용자/공간/멤버십/Project·이력·관계 소유권/ACL·컬럼 권한·시퀀스는 전후 스냅샷이 같았다. 자격증명 파일 3개·V1/V2/V3·JAR SHA-256 **7개**도 유지했다. 실제 로컬은 **5/3/6/1**이며 이번 작업에서 Project를 추가하지 않았다. 영구 쓰기 설정도 변경하지 않았다.

백엔드/SQL/권한 도구/의존성을 바꾸지 않아 백엔드 build/JUnit·별도 전체 PowerShell 회귀는 반복하지 않았다. 앞선 Java 합산 298개/프록시 1개·PowerShell 69개와 이번 프론트 194개·실제 격리 브라우저 결과를 구분한다. 실제 로컬 migrate/GRANT/seed는 실행하지 않았다. 문서/ADR-039/학습 노트와 [프론트 실행 안내](../frontend/README.md)를 갱신했다.

다음 한 작업은 **Project 수정 API의 권한·충돌 정책 확정**이다.


## 2026-11-05 — Project 수정 API 권한·충돌 정책 확정

### 범위와 확정 결과

필수 요구사항/아키텍처/기술/로드맵과 Project/Workspace 수정 코드를 확인한 뒤 사용자가 선택한 이름·설명 수정, 해당 공간 OWNER/ADMIN, Project 자체 버전/409를 확정했다. 설명 null/빈 값은 삭제하고 다른 필드끼리도 한 Project의 동시 변경이면 충돌이다. 공간 이동·보관은 제외한다.

[project-update-api-v1.md](project-update-api-v1.md)에 `PATCH /api/workspaces/{workspaceId}/projects/{projectId}`의 name/description/version 세 필드 필수 계약, 문자열 버전·200/409, no-op, Service/commit, V4와 네 컬럼 최소 UPDATE를 기록했다. 요청 흐름은 입력 → 서버 행위자/부모 소속 → 부모+Project 조회 → 역할 → 버전 비교 → 변경/DB 버전 검증/commit → DTO다. 자원을 역할보다 먼저 확인해 미존재/부모 불일치를 404로 유지한다.

상세/생성/수정 DTO에는 Project version 문자열을 추가하는 설계이며 목록 요약 세 필드는 유지한다. V4·PROJECT_UPDATE와 기존 ACL/기동 검사·이력/seed/fixture·프론트 파서 전환, Vue의 draft/기준 분리·409/결과 불명/명시적 복구, 격리 수용 기준도 포함했다. 결정은 ADR-040, 원리는 learning-notes.md에 기록했다.

### 변경과 실제 검증

이번 변경은 새 계약과 README/프론트 안내·요구사항/아키텍처/기술/로드맵·기존 Project 계약·결정/학습/이 실행 기록 문서뿐이다. Python으로 UTF-8/로컬 링크, HTTP 예제 4개/JSON 파싱, PATCH 필드·설명 null·응답 필드/버전·409 코드·최소 컬럼 집합, ADR-040 단일성을 확인했다. V4 SQL/전용 권한 도구 파일과 Project @Version이 아직 없다는 것도 확인해 설계/구현 상태를 구분했다.

실행 코드·SQL·Vue 동작을 변경하지 않았으므로 기능 테스트/빌드·HTTP·DB migration/GRANT/seed는 실행하지 않았다. 앞선 프론트 194개·실제 격리 브라우저 결과를 이번 정책의 검증 결과로 재사용하지 않는다. 실제 로컬 V3/PROJECT_CREATE·5/3/6/1을 변경하는 작업도 수행하지 않았다.

다음 한 작업은 **Project 수정 API·V4·최소 UPDATE 권한 도구 구현과 격리 PostgreSQL 검증**이다. 실제 로컬 적용과 Vue 수정 폼은 그 뒤 별도 단계다.

## 2026-11-05~06 — Project 수정 API·V4·최소 UPDATE 구현과 격리 검증

### 범위와 처리 흐름

확정한 계약에 따라 `PATCH /api/workspaces/{workspaceId}/projects/{projectId}`를 구현했다. JSON name/description/version 검증 → 개발 행위자 → 부모 소속 → 부모+Project 조회 → OWNER/ADMIN → 요청 버전 → Service 트랜잭션/관리 Entity 변경 → flush/commit → version 문자열을 포함한 DTO 순서다. MEMBER/VIEWER는 403, 미소속·미존재·잘못된 부모는 동일 404, 요청 버전 또는 DB 낙관적 충돌은 `PROJECT_VERSION_CONFLICT` 409다. 설명 null/빈 값은 삭제하며 정규화 후 동일한 변경은 버전/시간을 유지한다. no-op도 OPTIMISTIC 검증으로 트랜잭션 종료 시 선행 변경을 확인한다.

`V4__add_project_version.sql`은 기존 Project에 version 0과 NOT NULL/비음수 CHECK를 추가한다. `ProjectUpdateRepository`는 기존 트랜잭션을 필수로 사용한다. Project 수정은 Workspace 버전/부모/생성 시간을 바꾸지 않는다. 새로운 라이브러리는 추가하지 않았다.

전용 `provision-project-update.ps1`/Gradle task/관리자 runner는 기존 PROJECT_CREATE에서 projects의 name/description/updated_at/version 네 컬럼 UPDATE만 추가한다. 기존 read/create/rename/Project-create/migration/seed 도구와 개발 쓰기 기동 검사도 완전한 PROJECT_UPDATE 상태를 인식한다. 부분·과도한 권한을 자동 수선하지 않는다. 기존 V1~V3 SQL은 유지하고 현재 이력 검사는 1/2/3/4로 전환했다. V2/V3 과거 마이그레이션 테스트는 명시적 target으로 검증한다.

상세/생성 응답에 version 문자열이 추가되어 기존 Vue 파서와 테스트도 함께 전환했다. 생성 응답은 version "0"을 검사한다. 목록 요약은 기존 세 필드이며 이번에는 편집 폼/PATCH 클라이언트를 추가하지 않았다. 결정과 원리는 ADR-041/learning-notes.md에 기록했다.

### 실행 명령과 확인된 결과

JDK 21 환경에서 다음 명령을 실행했다. backend 명령은 backend 디렉터리, npm 명령은 frontend 디렉터리에서 실행했다.

- `./gradlew.bat compileJava compileMigrationJava compileTestJava --no-daemon --console=plain`: 성공.
- `./gradlew.bat test --tests '*ProjectUpdate*Test' --tests '*V4MigrationTest' --no-daemon --console=plain`: 새 기능 집중 검증 **41개 성공**(API 31·권한 9·V4 1).
- `./gradlew.bat --init-script build/project-update-verification/parallel.init.gradle build verifyWorkspaceProxy --no-daemon --console=plain`: **BUILD SUCCESSFUL**, 전체 **340개 + 실제 Vite 프록시 1개**, 실패/오류/skip 0. 각 클래스가 고유 PostgreSQL을 사용하므로 검증 전용 init script로 fork 2개를 사용했다. 제품 빌드 설정에는 추가하지 않았다.
- `npm test -- --run`: **11개 파일/203개 테스트 성공**. `npm run build`: TypeScript 검사/Vite production build 성공.
- 앱 JAR 검사: V4 포함, 자격증명·관리자 정책 SQL/runner·Flyway 라이브러리는 제외됨을 확인했다. 문서 UTF-8/로컬 링크와 ADR-041 단일성을 검사했다.

API 검증은 OWNER/ADMIN, MEMBER/VIEWER, 부모 불일치/미소속, 엄격한 JSON/문자열 버전/크기·Origin, 실제 병렬 UPDATE의 한 성공/한 409, 다른 필드끼리의 충돌, no-op 후 선행 commit, 즉시/지연 제약 실패의 전체 rollback, 기본/쓰기 off 경계를 포함한다. V4 검증은 데이터가 있는 V3에서 실제 migrate/validate/반복, 기존 이력/소유권/ACL/데이터 보존과 null/음수 제약을 확인했다. 권한 검증은 네 컬럼 DML만 허용하고 부모/id/생성 시간·전체 UPDATE·다른 DML/DDL·시퀀스/이력 접근을 거부한다.

PowerShell 전체 검증은 `./infra/verify-migration.ps1 -IncludeProjectUpdate`로 실행한다. 최초 실행에서 검증 스크립트의 과거 V3 개수 기대값이 남아 실패한 것을 V4 기준으로 수정했다. 다음 실행은 새 권한 상태 보존 단계까지 통과했으나 세션 중단으로 최종 성공/정리 기록이 없어 성공으로 계산하지 않았다. 해당 고유 프로젝트와 임시 자격증명 세 파일을 정리하고 2026-11-06 새 고유 PostgreSQL에서 전체 검증을 다시 실행했다. 최종 결과는 아래에 별도로 기록한다.

재실행 결과 **80개 모두 성공**, 종료 코드 0이며 `verification passed: 80 checks`와 실제 PASS 80줄을 함께 확인했다. 자격증명 전달/로그 마스킹·부모 프로세스 환경 보존, V1~V4/seed/read/create/rename/Project-create의 기존 경로와 새 Project-update 경로를 모두 실제 PowerShell→Gradle→Java→PostgreSQL로 실행했다. 새 네 컬럼 GRANT/반복/기존 도구 보존, 실제 UPDATE 롤백, 부분 권한 자동 보충 거부, 과도한 부모 컬럼 권한 자동 철회 없는 거부까지 통과했다.

최종 고유 프로젝트 `flowdesk-migration-check-cebf373fb29343fcad9de3d45fb9bb7b`와 앞선 실패/중단 프로젝트 두 개의 컨테이너·볼륨·네트워크가 없음을 Docker label 조회로 별도 확인했다. 세 프로젝트의 임시 .env/.env.migrator/앱 .env 파일도 모두 삭제됐다. 최종 로그/집계/보존 영수증은 Git 제외 `backend/build/project-update-verification`에 있다. 전체 Java 340개와 프록시 1개, 프론트 203개, PowerShell 80개가 이번 구현의 실제 통과 결과이며 집중 테스트 41개는 Java 전체에 포함된다.

### 실제 로컬 보존과 적용 경계

Docker 재시작 후 실제 로컬은 읽기 전용 스냅샷만 취득했다. 기동 직후 첫 조회는 PostgreSQL starting up으로 실패했으며 readiness 확인 후 재조회했다. 전체 사용자/공간/멤버십/Project·Flyway 이력·소유권/관계 ACL/컬럼 ACL·시퀀스의 전후 스냅샷이 동일하다. 실제 로컬은 **V3/PROJECT_CREATE·5/3/6/1**이며 Project version 컬럼은 아직 없다. 자격증명 세 파일과 V1/V2/V3 SHA-256 **6개**도 그대로다. 앱 JAR는 이번 구현으로 새로 빌드했으므로 보존 해시 대상에서 제외했다.

실제 로컬 V4 migration/GRANT/seed/업무 수정과 실제 브라우저 편집 폼 검증은 실행하지 않았다. **새 앱은 @Version 매핑 때문에 로컬 V4가 필요하며 개발 쓰기 모드는 Project UPDATE 권한도 필요하다.** 다음 한 작업은 **로컬 V4·최소 Project UPDATE 적용과 새 앱의 수정 확인**이다.

## 2026-11-07 — 실제 로컬 V4·최소 Project UPDATE 적용과 새 앱 수정 확인

### 범위·실행 순서

사용자의 명시적 요청으로 기존 `flowdesk-local-postgres-1`/127.0.0.1:5432/flowdesk에 적용했다. 사전 스냅샷에서 V1/V2/V3·5 users/3 workspaces/6 memberships/1 project와 Project 1의 부모 Workspace 1을 확인했다. 기존 HTTP 앱이 없음을 확인한 뒤 실행했으며 seed·새 Project 생성·권한 자동 복구는 하지 않았다.

JDK 21 환경에서 저장소 루트의 다음 실제 명령을 실행했다.

```powershell
./infra/migrate-local.ps1 -Action Info
./infra/migrate-local.ps1 -Action Migrate
./infra/migrate-local.ps1 -Action Validate
./infra/migrate-local.ps1 -Action Info
./infra/migrate-local.ps1 -Action Migrate
./infra/provision-project-update.ps1
./infra/provision-project-update.ps1
./backend/build/local-project-update-verification/verify-http.ps1
```

V4 PENDING → **Migrate 1건 → Validate 성공 → V1~V4 SUCCESS → 재실행 0건**이다. 권한 도구 최초/반복 실행 모두 `PROJECT_UPDATE; existing password preserved`를 확인했다. V4 적용/권한 부여 직후 전체 행은 그대로이고 Project version만 0으로 추가됐다. 기존 V1~V3 설치 이력·소유권·테이블 ACL·시퀀스·기존 컬럼 ACL을 유지했으며 새 ACL은 승인된 Project 네 컬럼뿐이다.

### 실제 새 JAR·HTTP·DB 결과

검증기는 기존 앱 계정 자격증명을 메모리로만 전달하고 환경을 정리한 자식 JVM을 loopback 18081에 순차 기동했다. 개발 행위자는 서버 설정으로만 바꾸고 쓰기 flag/허용 Origin은 검증 자식 프로세스에만 주었다. 영구 .env나 실행 설정은 바꾸지 않았다. 검증 영수증에 PATCH 시도 전에 상태를 저장해 응답 유실 시 자동 재전송하지 않도록 했다.

- **OWNER Alice:** Project 1 상세 GET의 문자열 version "0"을 읽고 이름·설명을 PATCH했다. **200/version "1"**을 받았고 DB commit·후속 GET·목록 요약 반영까지 확인했다. 최종 이름은 `FlowDesk Local Project Update d789b39578f6`, 설명은 `Local V4 HTTP verification\nProject update confirmed`다. 앞뒤 공백과 CRLF 정규화도 확인했다.
- **충돌/no-op:** 같은 version "0" 요청은 **409/PROJECT_VERSION_CONFLICT**다. 현재 version "1"로 동일 값을 보내면 **200/no-op**이며 전체 Project 행·updated_at/version이 그대로다.
- **ADMIN Bob:** 같은 내용/version "1"의 PATCH를 **200/no-op**으로 허용했다. 별도 업무 변경은 남기지 않았다.
- **MEMBER Carol·VIEWER Dave:** GET 200, PATCH **403/PROJECT_FORBIDDEN**이다. 위조 사용자/역할 헤더로 승격되지 않고 stale 버전 검사보다 역할 거부가 먼저다. 없는 Project는 404다.
- **Eve 미소속/미존재·부모 불일치:** **404/PROJECT_NOT_FOUND**다. 미소속과 미존재의 오류 설명도 동일하다.
- **Origin/입력/쓰기 off:** 외부 Origin **403**, 추가 workspaceId 필드 **400**, 쓰기 off 앱의 GET **200/PATCH 405**다. 거부된 요청은 Project를 바꾸지 않았다.
- **상태/접속:** `/api/health`와 `/actuator/health` 모두 **200/UP**, PostgreSQL `pg_stat_activity`에서 실제 flowdesk_app 연결을 확인했다. 상세 DTO는 다섯 필드/version 문자열, 목록 요약은 세 필드다.

HTTP 흐름은 strict JSON → 개발 접속/쓰기 경계 → Controller → Service의 행위자/소속/부모+Project/역할/요청 버전 → 관리 Entity 변경·JPA @Version UPDATE/flush → commit → DTO/200이다. Actuator UP과 별도로 권한/버전/저장 결과를 검증했다.

### 최소 권한·보존·정리

실제 DB에서 SET LOCAL ROLE flowdesk_app와 부작용 없는 WHERE false/ROLLBACK 검사로 네 컬럼 UPDATE는 허용되고 id/workspace_id/created_at UPDATE·DELETE·Flyway 이력 조회는 42501로 거부됨을 확인했다. 유효 UPDATE 컬럼은 정확히 `description,name,updated_at,version`, 테이블 전체 UPDATE·시퀀스 USAGE/SELECT/UPDATE·스키마 CREATE는 모두 false다.

첫 권한 검사에서 `SET id=id`는 GENERATED ALWAYS IDENTITY의 SQL 규칙에 먼저 걸렸다. 트랜잭션은 종료/롤백됐고, `SET id=DEFAULT WHERE false`로 검증문만 수정해 실제 권한 거부를 재확인했다. 제품 코드/권한을 바꾸어 우회하지 않았다.

최종 상태는 **V1~V4/PROJECT_UPDATE·5/3/6/1**이다. 기존 Project 1의 **name/description/updated_at/version 네 필드만** 업무 변경됐으며 version은 1이다. Project 부모/id/created_at, 전체 사용자/공간/멤버십(Workspace 3 version 1 포함), 기존 V1~V3 이력, V4 이후 전체 ACL·시퀀스는 동일하다. 자격증명 세 파일·V1/V2/V3·기존 JAR SHA-256 **7개**와 부모 프로세스 환경도 유지됐다. 캡처한 migration/권한/HTTP/앱 로그에 전달한 비밀번호가 없는지 별도 검사했다.

검증용 앱 여섯 개는 각 시나리오 후 종료했으며 8080/18081에 리스너가 없음을 확인했다. 기존 로컬 PostgreSQL은 healthy로 유지한다. 앱/SQL/권한 도구 소스·의존성은 변경하지 않았으므로 전체 빌드/JUnit/Vite·Vue 테스트·격리 PowerShell 80개는 반복하지 않았다. 앞선 Java 340개/프록시 1개/프론트 203개/PowerShell 80개와 이번 **실제 로컬 적용·HTTP 검증**을 구분한다. 실행 스크립트·스냅샷·마스킹 로그·VERIFIED 영수증은 Git 제외 `backend/build/local-project-update-verification`에 있다.

다음 한 작업은 **Vue Project 이름·설명 수정 폼·버전 보존·409 충돌 안내 연결**이다.

## 2026-11-07 — Vue Project 수정 폼·버전 보존·409 충돌 복구

### 범위·설계·코드 흐름

기존 Project 수정 계약과 Workspace 수정 선례를 읽고 이름/설명 두 필드 편집을 연결했다. `ProjectUpdateForm.vue`는 편집 시작 Project와 사용자 입력을 분리하고, `projects.ts`의 `updateProject`가 부모 하위 PATCH에 정규화한 name/description/문자열 version만 보낸다. 기존 Controller→Service 소속/부모+Project/역할/요청 버전→JPA UPDATE/flush/commit 이후 200 DTO의 부모/id/두 필드/정확한 버전을 검증한다. 실제 변경은 기준+1, no-op은 같은 버전이며 계산에만 BigInt를 사용한다. 설명을 비우면 null로 삭제한다.

성공 이벤트는 `ProjectBrowser.vue`의 현재 상세와 같은 ID의 현재 목록 행만 갱신한다. 별도 GET이 성공 여부를 바꾸지 않으며 부모 Workspace 버전도 바꾸지 않는다. OWNER/ADMIN만 폼을 표시하지만 최종 인가는 서버가 담당한다. CSS는 기존 UI를 확장했고 새 의존성/범용 폼·전역 상태 계층을 추가하지 않았다. 제품 변경은 API/Browser/새 폼/CSS와 테스트 두 파일이다.

409와 결과 불명(응답 유실/5xx/깨진 JSON/잘못된 200)은 입력·시작 버전을 보존하고 저장을 잠근다. 최신 Project GET과 Workspace 역할 GET이 모두 성공해야 이름/설명 각각의 서버 값과 draft를 비교할 수 있다. 사용자가 명시적으로 기준을 갱신해도 draft는 유지하고 별도 수동 저장을 요구한다. GET만으로 성공 처리·버전 교체·자동 재전송하지 않는다. 역할 상실·조회 실패는 잠금을 유지하며 재조회 실패 때 이전 성공 스냅샷을 폐기한다. 다른 Project/공간 선택이나 언마운트는 쓰기/복구 조회를 취소하고 늦은 이벤트를 버린다. 결정은 ADR-043, 원리는 learning-notes.md에 기록했다.

### 실행한 검증

frontend 디렉터리에서 실행했다.

```powershell
npm test -- --reporter=dot
npm run build
```

- **13개 파일/246개 테스트 성공**, 실패 없음. 초기 244개 통과 후 역할 조회 실패·복구 GET 중 언마운트 두 시나리오를 추가해 최종 246개를 확인했다.
- TypeScript 검사와 Vite production build 성공. 29 modules, CSS 9.21 kB/JS 104.07 kB다.
- PATCH 경로/두 필드/null/큰 문자열 버전·정확한 다음 버전·no-op, 전송 전 입력 거부, 명확한 HTTP 거부와 결과 불명 구분, 잘못된 200, 중복 submit, 같은 ID props 변경 시 편집 기준 동결, 409/GET·명시적 기준 갱신·수동 저장, 두 역할의 권한 상실·조회 실패/이전 스냅샷 폐기, 405 입력 유지, 현재 선택 상세/같은 ID 목록 갱신, PATCH/복구 GET 취소와 늦은 이벤트 무시를 검증했다. 기존 조회/생성/Workspace/health 테스트도 함께 통과했다.

### 실제 Chrome·Vite·격리 PostgreSQL

JDK 21 환경의 저장소 루트에서 `./backend/build/project-update-browser-verification/verify.ps1`를 실행했다. 고유 Compose `flowdesk-migration-check-4de03915f9834eaa92d1122e68ab6e56`의 flowdesk_verify만 프로비저닝하고 V1~V4/seed/기존 최소 권한/PROJECT_UPDATE를 실제 도구로 준비했다. 하나의 초기 Project를 격리 DB에 준비하고 임시 앱/Vite/Chrome으로 검증했다. 실제 로컬 설정을 자식 앱에 전달하지 않았다.

- 이름/설명 오류는 PATCH 0건이다. 정상 제출을 두 번 연속 실행해도 PATCH 1건이고 200/정규화된 두 필드/version "1"을 확인했다. 목록/상세가 함께 바뀌며 `<em>` 문자열은 HTML로 실행되지 않는다.
- 별도 클라이언트가 version 1→2를 먼저 저장한 뒤 UI의 version "1" 저장은 실제 **409**다. 두 입력을 유지하고 저장을 잠갔다. 최신 Project/역할 GET 후 서버 두 필드와 입력을 비교해도 PATCH가 추가되지 않았고, 명시적 기준 갱신 후 별도 수동 저장만 version "2"로 전송돼 version 3이 됐다.
- 다음 PATCH는 DB에 version 4로 commit된 **200 응답만 Chrome에서 유실**시켰다. 결과 불명 입력/잠금과 자동 재전송 차단을 확인했다. GET에서 저장된 내용이 확인돼도 성공 안내나 저장 잠금 해제로 바꾸지 않았다. 이 요청의 빈 설명은 null이었다.
- VIEWER Dave는 Project를 조회하지만 수정 폼이 없다. OWNER Alice의 쓰기 off 앱에서는 실제 **405**를 안전하게 표시하고 이름/설명 입력을 유지했다.
- 브라우저 PATCH는 **5건(정상 200·409·수동 복구 200·커밋 응답 유실·405)**, 별도 경쟁 클라이언트 PATCH는 1건이다. 최종 격리 데이터는 **5/2/5/1**, Project version **4**/description null이며 Workspace 버전은 모두 0이다.
- 1280 px 데스크톱/390 px 모바일 캡처를 확인했다. 정상/충돌 비교/결과 불명 화면에 가로 넘침이 없다. 캡처·DOM·요약·마스킹 앱 로그는 Git 제외 `frontend/.cache/project-update-check`에 있다.

### 보존·정리·미실행 사항

소유한 임시 앱 세 개·Vite·Chrome을 종료했고 고유 컨테이너/볼륨/네트워크와 임시 자격증명 세 파일이 없는지 별도 확인했다. 실제 로컬 전체 데이터/이력/소유권·ACL·시퀀스의 전후 스냅샷이 동일했다. 자격증명 세 파일·V1~V4·기존 JAR SHA-256 **8개**도 유지했다. 실제 로컬은 **V4/PROJECT_UPDATE·5/3/6/1·Project 1 version 1** 그대로이며 영구 쓰기 설정도 바꾸지 않았다.

백엔드/SQL/권한 도구·의존성을 변경하지 않아 전체 Java build/JUnit·별도 전체 PowerShell 80개는 반복하지 않았다. 앞선 백엔드 340개/프록시 1개·도구 80개와 이번 프론트 246개/빌드·실제 격리 브라우저 결과를 구분한다. 실제 로컬 migration/GRANT/seed/업무 PATCH는 실행하지 않았다.

다음 한 작업은 **Task 데이터 모델·생성/조회 권한 범위 확정**이다.


## 2026-11-07 — 첫 Task 모델·생성/조회 정책 확정

필수 요구사항/아키텍처/기술/로드맵과 기존 Project 생성/조회·Repository/쓰기 필터·기동 권한 검사·MigrationHistory/seed·V4 테스트를 확인했다. 사용자 답변으로 네 필드부터 단계적으로 진행, 세 역할 생성/네 역할 조회, TODO 고정/우선순위 선택·기본 MEDIUM을 확정했다.

[Task 계약](task-model-and-api-v1.md)에 제목/설명 기본값·Project FK·중첩 API·Service 부모/역할 검사·DTO/오류·V5/최소 TASK_CREATE·기존 도구/이력·seed/기동 검사 전환과 격리 수용 기준을 기록했다. 특히 기존 POST 필터는 Task 생성 경로를 보호하지 않아 후속 패턴 추가가 필요하며, V4 테스트의 latest runner 가정도 V5 추가와 분리해야 한다. 담당자·마감일·수정/상태 변경·칸반·댓글/이력은 이번 범위에서 제외했다.

이번 변경은 문서뿐이다. Python 검증으로 문서 UTF-8/로컬 링크, HTTP/JSON 예제 4개의 필드·TODO/우선순위·문자열 ID/목록 요약 6필드·상세 7필드 계약과 ADR-044 단일성을 확인했다. V5/Task 실행 코드/전용 권한 도구가 아직 없는 것도 확인해 설계와 구현 상태를 구분했다. 실행 코드·SQL·Vue 동작을 변경하지 않아 기능 테스트/빌드는 실행하지 않으며 앞선 246개 등의 결과를 Task 검증으로 계산하지 않는다. 실제 로컬 DB/자격증명/권한/migration/seed에는 접근·변경하지 않았다. V5/Task 구현은 아직 없다.

다음 한 작업은 **V5·Task 매핑·생성/조회 API·최소 권한 도구 구현과 격리 PostgreSQL 검증**이다. 실제 로컬 적용과 Vue Task 연결은 그 뒤 별도 단계다.

## 2026-11-07 — V5·Task 생성/조회·최소 권한 도구 구현과 격리 검증

### 범위·구현 흐름

확정한 Task 계약의 제목/설명/상태/우선순위, OWNER/ADMIN/MEMBER 생성·네 역할 조회, 서버 TODO 고정·우선순위 선택/기본 MEDIUM을 구현했다. 담당자/마감일/수정·상태 변경/Task version/Activity/Vue는 추가하지 않았다. 새 의존성도 없다.

V5는 tasks의 Project FK/입력 길이·enum CHECK/ID/시간과 부모별 목록 인덱스를 만든다. Task는 스칼라 projectId만 저장하고 부모 Entity를 cascade하지 않는다. HTTP strict JSON/32 KiB·ID/페이지 검증 → 서버 행위자/Workspace membership → Project id+workspaceId → 생성 세 역할 또는 Task id+projectId → 조회 readOnly/생성 쓰기 Service → 제한 Repository/mandatory persist·flush → commit 후 DTO/201 흐름이다. 생성과 상세는 7필드, 목록은 description을 뺀 6필드이며 부모의 version/updated_at은 바꾸지 않는다.

TaskController/TaskCreateController/TaskErrors와 Task 도메인/enum·DTO·Service·제한 Repository/생성 어댑터를 추가했다. DevTaskReadSettings는 Task SELECT, 기존 쓰기 기동 검사는 Task SELECT/INSERT와 금지 DML/컬럼 UPDATE·시퀀스를 검사한다. 기존 POST 필터에 Task 경로를 명시적으로 추가했다. MEMBER의 Task 생성 허용을 Project 관리 권한으로 확대하지 않는다.

전용 `provision-task-create.ps1`→`runAppTaskCreateAccess`→AppTaskCreateAccessRunner/관리자 정책 SQL은 기존 PROJECT_UPDATE에 tasks SELECT/INSERT만 더하는 TASK_CREATE를 만든다. 기존 read/create/rename/Project-create/Project-update 도구는 완전한 새 상태를 유지한다. 부분/과도/PUBLIC/grant option·잘못된 인증/이력은 자동 보정하지 않는다. MigrationHistory/seed/fixture의 latest는 1~5, V4 독립 검증은 명시적 target 4로 전환했다. 앱 권한 실패를 테스트하는 fixture 복원은 정확한 기존 GRANT를 직접 복구하고, 앱 launcher가 권한을 자동 부여하도록 바꾸지 않았다.

### 실제 명령과 결과

JDK 21/Docker 환경에서 backend 디렉터리의 다음 명령을 실행했다.

```powershell
./gradlew.bat compileJava compileMigrationJava --no-daemon --console=plain
./gradlew.bat test --tests '*Task*Test' --tests '*V5MigrationTest' --tests '*V4MigrationTest' --no-daemon --console=plain
./gradlew.bat --init-script build/task-verification/parallel.init.gradle build verifyWorkspaceProxy --no-daemon --console=plain
```

컴파일은 성공했다. 집중 테스트는 **56개 모두 성공**(Task API 45·최소 권한 9·V5 1·기존 V4 target 1)이며 오류/실패/skip이 없다. 전체 회귀는 클래스별 고유 PostgreSQL을 사용하는 검증 전용 fork 2개 init script로 실행하며 제품 build 설정의 병렬 수를 변경하지 않았다. 최종 전체 회귀는 **22개 클래스/396개 테스트**, 실제 Vite 프록시는 **1개 테스트** 모두 성공했고 실패/오류/skip이 없다. `build verifyWorkspaceProxy`는 **BUILD SUCCESSFUL (9m 35s)**였다. 실제 Vite로 Task POST 정상 201/TODO·MEDIUM·DB 한 행과 외부 Origin 403, 부모 Project version 불변을 확인했다.

Task API는 다섯 사용자 역할·잘못된 부모/다른 Task·양쪽 공간 소속·MEMBER의 Project 관리 거부, 기본/세 우선순위/중복 제목·문자열 ID/상대 Location/목록·상세 필드, strict JSON/status·assignee·부모 위조·Unicode·chunked 32 KiB, 실제 중복 동시 생성, 기본/쓰기 off/Origin·Task SELECT/INSERT 누락 기동 실패, 즉시/지연 commit 실패 rollback과 부모 전체 행 불변을 검증한다. V5는 데이터/PROJECT_UPDATE가 있는 V4에서 실제 runner의 한 건 적용·Validate·반복 0건과 기존 행/이력/ACL/소유권·시퀀스 보존, Task 제약/인덱스/기본값을 확인한다. 권한 테스트는 실제 앱 IDENTITY INSERT와 금지 UPDATE/DELETE/TRUNCATE·시퀀스/이력/DDL, 기존 모든 Java 실행기 보존·부분/과도 상태/인증/체크섬 거부를 포함한다.

저장소 루트에서 `./infra/verify-migration.ps1 -IncludeTaskCreate`도 실행했다. 기존 전체 V1~V5/seed/권한 경로와 새 Task PowerShell→Gradle→Java 경로를 고유 격리 DB에서 검증했고 **92개 모두 성공/종료 코드 0**이다. 실제 PASS 92줄과 성공 marker를 함께 확인했다. Task 최초/반복 SELECT·INSERT, 기존 5개 권한 도구의 TASK_CREATE 보존, 실제 IDENTITY INSERT/롤백, Task UPDATE·시퀀스 권한 부재, 부분/과도 권한 자동 보정 거부·비밀번호/부모 환경 보존이 통과했다. 초기 편집 보조 스크립트의 겹치는 치환/파일 인코딩·경로 오류는 실행 코드 적용 단계에서 수정했고, 이를 기능 검증 성공으로 계산하지 않았다.

앱 JAR에 V5/Task Controller/Entity가 포함되고 관리자 정책 SQL/runner·Flyway 라이브러리·.env가 없는지 확인했다. 모든 infra PowerShell 파일을 parser로 검사했다. Vue 제품 소스/계약·의존성은 변경하지 않아 이전 프론트 246개 테스트/빌드는 반복하지 않는다.

### 실제 로컬 보존과 적용 경계

사전 로컬 스냅샷 시 기존 PostgreSQL이 중지돼 첫 조회가 실패했다. 기존 컨테이너를 기동한 뒤 **읽기 전용** 기준 스냅샷을 저장했다. 실제 로컬 migration/GRANT/seed/Task 생성은 실행하지 않았다. 자격증명 세 파일·V1~V4 원본 해시 7개를 보존하며 앱 JAR는 이번에 새로 빌드하므로 기존 해시 보존 대상에서 제외한다. 최종 전후 스냅샷에서 전체 업무 데이터/Flyway 이력/소유권·관계 ACL/컬럼 ACL/시퀀스가 동일했다. 실제 로컬은 **V4/PROJECT_UPDATE·5/3/6/1·Project 1 version 1**이며 tasks 테이블은 없다. 보호 파일 7개도 일치했다. 고유 Compose `flowdesk-migration-check-2a9066b5c3244d6cb138e4400f7bdbd6`의 컨테이너·볼륨·네트워크와 임시 자격증명 세 파일이 없는지 별도 확인했다. JUnit/프록시 임시 앱은 테스트 종료 시 정리됐다. 로그/집계/전후 스냅샷/VERIFIED 영수증은 Git 제외 `backend/build/task-verification`에 있다. 문서 UTF-8/로컬 링크·HTTP/JSON 예제 4개·ADR-045 단일성도 확인했다.

새 앱은 Task 매핑 때문에 현재 로컬 V4에 바로 실행하면 JPA validate가 실패한다. 다음 한 작업은 **로컬 V5·최소 Task 권한 적용과 새 앱의 생성·조회 확인**이다. Vue Task는 그 뒤 별도 단계다.

## 2026-11-08 — 실제 로컬 V5·최소 Task 권한과 새 앱 생성/조회

### 범위·실행 순서

사용자의 명시적 요청에 따라 기존 flowdesk-local-postgres-1/127.0.0.1:5432/flowdesk에 적용했다. 사전 스냅샷에서 V1~V4·5 users/3 workspaces/6 memberships/1 project, Project 1의 Workspace 1·version 1과 tasks 테이블 부재를 확인했다. 실행 중인 8080/18081 앱은 없었다. 단일 Task 생성자는 MEMBER Carol로 정해 Task 생성 자격과 기존 Project 관리 권한의 차이를 실제 HTTP로 검증했다. seed/추가 Project/영구 쓰기 설정 변경은 하지 않았다.

JDK 21 환경의 저장소 루트에서 다음 명령을 실행했다.

```powershell
./infra/migrate-local.ps1 -Action Info
./infra/migrate-local.ps1 -Action Migrate
./infra/migrate-local.ps1 -Action Validate
./infra/migrate-local.ps1 -Action Info
./infra/migrate-local.ps1 -Action Migrate
./infra/provision-task-create.ps1
./infra/provision-task-create.ps1
./backend/build/local-task-verification/verify-http.ps1
```

V5 PENDING → **Migrate 1건 → Validate 성공 → V1~V5 SUCCESS → 재실행 0건**이다. 권한 도구 최초/반복 모두 `TASK_CREATE; existing password preserved`를 확인했다. V5/GRANT 직후 tasks는 비어 있고, 기존 네 업무 테이블/설치 이력·소유권/테이블·컬럼 ACL/시퀀스는 동일했다. 새 tasks·인덱스/시퀀스만 migrator 소유로 추가됐고 앱에는 tasks SELECT/INSERT만 생겼다.

첫 스냅샷 검사에서 소유자의 전체 ACL 문자열을 과거 문자 집합으로 고정해 비교한 기대값이 실제 표기와 달라 중단됐다. DB/권한은 수정하지 않고 검증문을 바꿨다. 소유권과 소유자 엔트리를 분리하고 앱의 정확한 SELECT/INSERT·grant option 부재·다른 grantee 부재를 검사했으며 SQL `aclexplode` 의미 기반 검사까지 통과했다. POST 시도 전의 검사 실패였으므로 Task 생성 재시도는 없었다.

### 실제 새 JAR·HTTP 결과

기존 앱 자격증명을 메모리로만 전달한 별도 JVM을 loopback 18081에 순차 실행했다. 자식 환경의 앱/관리자/Flyway/서버 설정을 정리하고 config import를 비활성화했으며, 쓰기 flag/허용 Origin과 행위자는 검증 자식에만 주었다. POST 직전 영수증을 POST_ATTEMPTED로 저장해 결과 불명 때 자동으로 재전송하지 않게 했다.

- **MEMBER Carol 생성:** Workspace GET의 MEMBER를 확인하고 Project 1에 제목/설명만 POST했다. **201/Task ID "1"/TODO/MEDIUM/상대 Location**을 확인했다. 앞뒤 공백/CRLF 정규화도 적용됐다. 제목은 `FlowDesk Local Task Check 163c1510dd89`, 설명은 `Local V5 HTTP verification\nTask description`이다. priority/status를 요청에 넣지 않아 기본값을 확인했다.
- **조회:** Carol의 목록/상세 200과 DB commit을 확인했다. 상세는 일곱 필드/문자열 ID, 목록 요약은 description을 뺀 여섯 필드다. 유효한 뒤 페이지는 빈 목록 200이다. OWNER Alice/ADMIN Bob/VIEWER Dave의 목록·상세도 200이다. OWNER/ADMIN으로 추가 Task는 생성하지 않았다.
- **권한:** Carol의 Project 생성/PATCH는 여전히 **403/PROJECT_FORBIDDEN**이다. Dave의 Task 생성은 위조 사용자/역할 헤더를 보내도 **403/TASK_FORBIDDEN**이다.
- **부모/존재:** 잘못된 Workspace의 Project, 없는 Project/Task, 미소속 Eve의 목록/상세/POST와 Eve의 자기 공간 아래 다른 Project는 모두 **404/TASK_NOT_FOUND**다. VIEWER도 없는 부모에 생성하면 역할 403보다 404가 먼저며 미소속/미존재 오류 설명은 동일하다.
- **입력/출처/쓰기 off:** 외부 Origin **403**, status:"TODO" 강제·projectId JSON 위조 **400**, 쓰기 off의 Task GET **200/POST 405**다. 추가 Task 행은 남지 않았다.
- **상태:** `/api/health`·`/actuator/health` **200/UP**과 pg_stat_activity의 실제 flowdesk_app 연결을 확인했다.

흐름은 HTTP 입력/개발 출처 → Controller → Service의 행위자/Workspace membership/Project 부모/역할 → Task persist·flush/commit → 201 DTO/Location → 후속 GET이다. Task 생성으로 부모 Project의 편집 버전을 증가시키지 않는다. Task UI는 아직 없다.

### DB 권한·보존·정리

실제 SET LOCAL ROLE flowdesk_app/ROLLBACK 검사에서 tasks SELECT·0행 INSERT는 허용되고 title/project_id/id UPDATE·DELETE·이력 조회는 **42501**로 거부됐다. 0행 INSERT는 새 행이나 시퀀스 증가를 만들지 않는다. SQL 메타데이터에서도 SELECT/INSERT만 true이며 UPDATE/DELETE/TRUNCATE·컬럼 UPDATE·시퀀스 USAGE/SELECT/UPDATE·스키마 CREATE·public USAGE·DB CREATE/TEMP는 모두 false다. 소유자 외 ACL은 정확히 앱의 nongrantable SELECT/INSERT 두 항목이다.

최종 상태는 **V1~V5/TASK_CREATE·5/3/6/1/1**이다. Task 1만 추가됐고 Project 1의 name/description/version 1/updated_at, 전체 사용자/공간/멤버십, V1~V4 설치 이력, 이전 소유권/ACL/기존 시퀀스는 그대로다. 새 Task created_at=updated_at이고 tasks_id_seq만 새 ID 1까지 진행했다. 자격증명 세 파일·V1~V5·기존 JAR SHA-256 **9개**와 부모 환경도 유지됐으며 캡처 로그에 전달한 비밀번호가 없는지 별도 확인했다.

검증 앱 여섯 개는 모두 종료했고 8080/18081 리스너가 없음을 확인했다. PostgreSQL은 healthy다. 스크립트/로그/전후 스냅샷/권한 검사/VERIFIED 영수증은 Git 제외 `backend/build/local-task-verification`에 있다. 제품 코드/SQL/권한 도구·의존성은 변경하지 않아 전체 빌드/JUnit/Vite/PowerShell·프론트 테스트는 반복하지 않았다. 앞선 Java 396개·Vite 1개·PowerShell 92개·프론트 246개와 이번 실제 로컬 적용/HTTP 검증을 구분한다.

다음 한 작업은 **Vue Task 목록·상세 조회 연결**이다.


## 2026-11-08 — Vue Task 목록·상세 조회 연결

### 목표·변경·흐름

선택한 Project의 작업 목록/상세를 연결했다. `frontend/src/api/tasks.ts`의 중첩 GET·5초 제한·unknown 응답 검사, `components/TaskBrowser.vue`의 목록/선택 상세·페이지/로딩/빈 목록/안전한 오류와 재시도, ProjectBrowser의 Project ID key 등록, 기존 카드/모바일 CSS 재사용이 변경 범위다. 새 의존성·백엔드/SQL/GRANT·.env 변경은 없다. 네 역할 조회 정책을 유지한다.

Vue Project 선택 → Task 목록/상세 GET → 기존 개발 필터/TaskController → TaskQueryService readOnly 트랜잭션의 서버 행위자/Workspace membership·Project 부모 확인 → TaskRepository/DB → DTO → 문자열 Long ID·두 부모/enum/텍스트·페이지/정렬 검사 → 한국어 상태/우선순위와 일반 텍스트 설명 화면 순서다. Task의 설명은 보간/pre-wrap이며 HTML을 실행하지 않는다. 부모 key/요청 취소/취소 상태 검사가 이전 프로젝트 데이터의 뒤늦은 덮어쓰기를 막는다.

### 실행한 검증

```powershell
# frontend
npm test -- --reporter=dot
npm run build
# root; Docker, 기존 로컬 V5/TASK_CREATE, JDK 21, Chrome 설치 필요
./backend/build/task-read-browser-verification/verify.ps1
```

- 프론트 **15파일/297개 통과**(기존 246개 + 신규 51개), 타입 검사/Vite build 성공. Task API/화면에서 잘못된 ID·부모/enum/텍스트/페이지/정렬·다른 상세/프로젝트 거부, 문자열 큰 ID 보존, 5초 제한/외부 취소, 404/503·연결 실패의 안전한 안내/재시도, 빈 목록/nullable 설명/HTML 비실행·페이지·늦은 상세/목록 무시·언마운트·실제 Project 전환 통합을 확인했다. 기존 Project 단위 시나리오 세 파일은 새 자식만 mock해 기존 요청 순서를 유지하고 실제 Task 부모 통합은 TaskBrowser.spec.ts에서 검사한다.
- 실제 Chrome/Vite/읽기 전용 새 JAR: Alice·VIEWER Dave Task 목록/상세/설명·TODO/MEDIUM 한글 라벨, Eve의 다른 공간에서 Task 미노출, 503 원문 비노출/재시도, 응답을 보류한 Task 상세 중 공간 전환/늦은 응답 무시, 1280/390px 배치·모바일 가로 넘침 없음을 확인했다. **29개 API 모두 GET·브라우저 예외 0건**이다. 임시 앱/Chrome/Vite는 종료했다.
- 실제 로컬은 **5/3/6/1/1**, Project 1 version 1/Task 1 TODO·MEDIUM을 포함해 업무 행·이력/ACL/시퀀스의 전후 전체 스냅샷이 일치했다. 자격증명 3개/V1~V5/JAR **9개 SHA-256**도 같았다. 중지된 기존 로컬 PostgreSQL 컨테이너를 재시작했고 검증 뒤 healthy 상태로 두었다. 로컬 새 Task/Project를 만들지 않았다.
- 백엔드/SQL/권한 소스를 바꾸지 않아 앞선 Java 396개/Vite 1개·PowerShell 92개 전체 회귀는 반복하지 않았다. 이번의 실제 브라우저 프록시/DB GET 검증과 구분한다. Task 생성 폼/POST·수정·상태 변경은 이번 검증 대상이 아니다.

### 검증 중 수정과 산출물

첫 테스트 실행은 복사한 Project 기대값에 남은 Task version/DOMException title 기대값 때문에 3건 실패했다. Task에 version이 없는 실제 계약과 오류 name 기대값으로 수정한 뒤 전체 통과했다. 추가한 통합 테스트의 Array.at 사용은 현재 TS lib 설정에서 타입 검사에 실패해 substring으로 수정했다. 최초 브라우저 검증 사전 스냅샷은 PostgreSQL 컨테이너가 중지되어 실패해 기존 컨테이너만 시작했다. 다음 검증에서 앱은 정상 기동했으나 임시 포트 5060이 fetch 제한 포트라 기동 확인이 실패했다. 검증기의 임시 포트를 빈 20000~44999 범위로 제한해 다시 실행했고 통과했다. 제품 동작/DB를 수정한 것이 아니다.

Git 제외 `backend/build/task-read-browser-verification/verify.ps1`/`result.log`, `frontend/.cache/task-read-browser.mjs`, `.cache/task-read-tests.log`, `.cache/task-read-check/{desktop,mobile,error}.png`·summary.json과 종료 로그에 명령/증거를 보관했다. 다음 한 작업은 **Vue Task 생성 폼·POST 연결**이다.


## 2026-11-08 — Vue Task 생성 폼·POST 연결

### 목표·설계·변경 흐름

선택한 Project 아래에서 OWNER/ADMIN/MEMBER가 제목·선택 설명·우선순위로 Task를 생성한다. 기본 우선순위는 MEDIUM이고 상태 선택은 제공하지 않는다. VIEWER는 기존 조회를 유지하며 MEMBER의 Project 생성/수정 권한은 넓히지 않는다. 부모 Workspace의 myRole은 폼 표시 힌트로만 전달하고 서버 인가를 유지한다.

변경 파일은 frontend/src/components/TaskCreateForm.vue, TaskBrowser.vue, ProjectBrowser.vue, api/tasks.ts, style.css와 신규 Task 생성 API/화면 테스트 두 파일이다. 새 의존성·백엔드/SQL/권한·영구 환경 파일은 변경하지 않았다. 기존 Vue 지역 상태/fetch·프로젝트 폼 패턴을 재사용하고 공통 폼 프레임워크로 확장하지 않았다.

Vue 제목/설명/우선순위 입력·정규화/코드 포인트 검증 → createTask의 두 부모 하위 POST(title/description/priority만) → 기존 Task POST 쓰기/Origin·JSON 경계/Controller → TaskCommandService의 서버 사용자·Workspace 멤버십/Project 부모·세 역할 검사/쓰기 트랜잭션 → Task persist/flush/commit → 201/일곱 필드 DTO·상대 Location → 클라이언트 부모/문자열 ID·요청 값/TODO 검사 → 성공 안내·입력/우선순위 초기화 → 목록 0페이지/새 ID 상세 GET 순서다.

진행 가드/disabled로 중복 제출을 차단한다. 정상 201 성공과 후속 GET 실패는 별개로 표시한다. 목록 첫 페이지에 새 항목이 없어도 목록을 변조하지 않고 생성 ID 상세를 조회한다. 탐색 순번/abort로 이전 후속 조회가 새 생성·선택을 덮어쓰지 않게 한다. 400/403/404/405/413/415는 입력 유지/확정 거부이고, 네트워크/10초 제한/5xx/잘못된 201은 입력과 저장 잠금의 결과 불명이다. 이때 목록 확인은 GET뿐이며 같은 제목을 발견해도 성공/잠금 해제를 단정하지 않는다. 명시적 입력 초기화 뒤 별도 수동 제출만 허용한다.

### 실행한 검증과 결과

```powershell
# frontend
npm test -- --reporter=dot
npm run build
# root; Docker/JDK 21/Chrome, 고유 flowdesk_verify DB만 쓰기
$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-21.0.13.9-hotspot'
./backend/build/task-create-browser-verification/verify.ps1
```

- 프론트 **17파일/355개 통과**(앞선 297개 + 신규 58개), 타입 검사/Vite build 성공. 두 부모/입력 정규화·Unicode 경계·세 우선순위/기본값·status 미전송, 201/Location/DTO 검사, 거부/결과 불명 분류·취소/시간 제한, 네 역할/폼 표시, 우선순위 포함 진행·결과 불명 입력 잠금/초기화, 늦은 성공/후속 조회 무시, 201 뒤 GET 실패와 첫 페이지 밖 새 ID 선택을 확인했다.
- 실제 Chrome/Vite/새 JAR·고유 격리 PostgreSQL에서 MEMBER Carol의 Task 생성(높음/HIGH·TODO)/조회·설명 일반 텍스트·Project 관리 폼 미노출, 빈 제목/설명 길이 검증, 연속 submit의 단일 POST/201 후 목록·새 상세 선택/입력 초기화를 확인했다. 1280px/390px 캡처와 모바일 가로 넘침 없음도 확인했다.
- 서버가 commit한 두 번째 Task의 201 응답만 CDP에서 끊어 결과 불명 잠금/입력 유지·목록 GET 확인과 상세 조회 동안 미재전송을 검증했다. VIEWER Dave는 생성 폼 없이 조회했다. 쓰기 off Carol의 POST는 405이며 입력을 유지하고 결과 불명으로 분류하지 않았다.
- 브라우저 Task POST는 **3회**, 정상/응답 유실로 실제 commit은 **2건**, 나머지 **1회는 405 거부**다. 격리 DB 최종 **5/2/5/1/2**·Task 제목/설명/TODO·HIGH/MEDIUM과 부모 Project name/description/version 0/created_at=updated_at·Workspace version 0을 확인했다. 기존 V5/관리자 도구 9단계는 격리 DB 준비에만 재사용했다.
- 실제 로컬 **5/3/6/1/1**은 데이터/이력/ACL/시퀀스 전체 스냅샷이 같고 자격증명 3개/V1~V5/JAR **9개 SHA-256**도 유지했다. 실제 로컬 쓰기·GRANT/migration은 하지 않았다. 소유한 검증 앱/Vite/Chrome과 고유 컨테이너/볼륨/네트워크·임시 자격증명을 정리했다.
- 백엔드/SQL/권한 소스가 바뀌지 않아 Java 396개/기존 별도 Vite 1개·PowerShell 전체 92개 회귀는 반복하지 않았다. 이번 실제 브라우저 POST/DB 검증과 구분한다. Task 수정/상태 변경·인증은 후속이다.

### 검증 중 수정·산출물·다음 작업

첫 프론트 실행에서 복사한 테스트의 한국어 조사(작업가)와 목록 버튼의 상태/우선순위 표시 기대값이 달라 7개 실패했다. 실제 Task 화면의 문구/계약에 맞게 기대값을 수정하고 전체 통과했다. 제품 문구의 조사도 다듬었다. API/서버 동작·DB 복구는 필요하지 않았다.

Git 제외 frontend/.cache/task-create-tests.log·task-create-build.log, .cache/task-create-browser.mjs·task-create-check/{desktop,mobile,uncertain}.png/summary.json/종료 로그와 backend/build/task-create-browser-verification/verify.ps1·result.log에 재현/증거를 남겼다. 다음 한 작업은 **Task 수정 API의 필드·권한·충돌 정책 확정**이다.


## 2026-11-08 — Task 수정 API 필드·권한·충돌 정책 확정

사용자가 세 질문에 답해 제목/설명/우선순위, Workspace OWNER/ADMIN/MEMBER의 공간 내 모든 Task 수정, Task 자체 버전/409를 확정했다. VIEWER는 올바른 Task에서 403이고 미소속/미존재/두 단계 부모 불일치는 동일 404다. 상태 변경/담당자·날짜/이동·보관은 후속이다. [수정 계약](task-update-api-v1.md)과 ADR-049에 선택·대안/이유·결과를 기록했다.

구현 기본값은 필수 title/description/priority/version 네 필드 PATCH, 설명 null/빈 값 삭제·우선순위 누락 400, 기존 정규화/길이/중복 제목 허용과 32 KiB strict JSON, 정규화 no-op의 시각/버전 유지 + stale/commit 낙관적 검증이다. Task 상세/생성/수정은 version 문자열 포함 여덟 필드, 목록 요약 여섯 필드는 유지하는 후속 전환을 설계했다. 버전은 부모와 독립적이다.

요청 흐름은 후속 Vue 편집 기준/Task version → nested PATCH → 기존 개발 접속/쓰기·Origin/JSON 검사 → Controller → 쓰기 Service의 서버 사용자/Workspace membership→Project 부모→Task 부모→세 역할/버전 검사 → mandatory 수정/OPTIMISTIC 검증/flush/commit → DTO/200 또는 TASK_VERSION_CONFLICT/409 → 사용자 명시적 복구다. 아직 실행 코드가 아니다.

V6__add_task_version.sql에는 version bigint 기본 0/비음수 CHECK만, 별도 TASK_UPDATE 도구에는 tasks UPDATE(title,description,priority,updated_at,version) 다섯 컬럼만 계획했다. 기존 모든 도구/이력·seed/기동 검사·과거 target/미래 V7 fixture 전환과 Vue 파서 호환 변경·격리 수용 기준도 계약에 포함했다. 현재 tasks 전체 컬럼 UPDATE 금지 기동 검사가 후속에 바뀌어야 한다는 점과 기존 PATCH 필터가 nested Task 경로를 이미 포함한다는 점을 코드에서 확인했다.

변경은 계약/요구사항·아키텍처·기술 스택·로드맵/ADR·학습·마이그레이션/프론트·루트 안내와 이 기록뿐이다. Python으로 UTF-8/코드 펜스·상대 링크·HTTP/JSON 예시를 검사했다. 계약 예시 JSON 4개, ADR-049 단일 기록, V6 SQL 미생성, 구현/설정 파일 178개 SHA-256 동일을 확인했다. 검증 결과는 Git 제외 backend/build/task-update-contract-verification/result.json에 보관한다.

기능 코드/SQL을 변경하지 않아 테스트·빌드는 실행하지 않았다. DB/GRANT/migration·앱 기동·외부 서비스 작업도 하지 않았다. 문서의 현재 로컬 기준은 앞선 V5/TASK_CREATE·5/3/6/1/1/Project 1 version 1이며 이번에 DB를 재검증한 것은 아니다. 다음 한 작업은 **Task 수정 API·V6·최소 UPDATE 권한 도구 구현과 격리 PostgreSQL 검증**이다. 실제 로컬 적용과 Vue 편집 폼 연결은 그 후 별도다.


## 2026-11-08 — Task 수정 API·V6·최소 UPDATE 구현·격리 검증

### 범위·변경·요청 흐름

ADR-049의 제목/설명/우선순위·OWNER/ADMIN/MEMBER·Task 버전/409를 구현했다. TaskUpdateController는 필수 네 필드와 기존 32 KiB strict JSON·정규화를 검사한다. TaskCommandService.update는 서버 사용자/Workspace membership→Project id+workspaceId→Task id+projectId→세 역할→요청 버전을 확인한다. TaskUpdateRepository는 mandatory 트랜잭션/OPTIMISTIC 검증·도메인 변경/flush를 맡고, Service 트랜잭션 프록시가 commit한 뒤 Controller가 DTO/200을 응답한다. stale/flush·commit 낙관적 충돌은 TASK_VERSION_CONFLICT/409이며 404/403 순서를 유지한다.

Task Entity에 Long @Version을 추가했고 title/description/priority/서버 updated_at만 변경한다. 정규화 no-op은 시각/버전을 유지하지만 트랜잭션 종료 시 버전을 검증한다. Task status/id/project_id/created_at·부모 Project/Workspace 데이터/버전/시각은 보존한다. V6__add_task_version.sql은 version bigint NOT NULL DEFAULT 0/비음수 CHECK만 추가한다. V1~V5 SQL은 수정하지 않는다.

AppTaskUpdateAccessRunner·runAppTaskUpdateAccess·provision-task-update.ps1/run-app-task-update-gradle.ps1·정책 SQL로 TASK_CREATE→TASK_UPDATE/반복 보존을 구현했다. tasks UPDATE(title,description,priority,updated_at,version)만 추가하고 기존 모든 관리자 도구/기동 정책·MigrationHistory/seed/current fixture를 전환했다. 부분/과도/PUBLIC/GRANT OPTION은 자동 수선하지 않는다. 과거 V5MigrationTest는 명시적 target 5, 미래 seed fixture는 V7을 사용한다.

TaskView의 상세/생성/수정은 version 문자열 포함 여덟 필드, 목록은 기존 여섯 필드다. 기존 Vue Task 타입·상세/생성 파서·초기 0 확인과 테스트 fixture를 전환했다. Task 편집 폼/PATCH 클라이언트·상태 전환/담당자·날짜·이력은 추가하지 않았다. 새 의존성은 없다.

### 실제 실행 명령

```powershell
# root; JDK 21/Docker, 기존 frontend node_modules 필요
$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-21.0.13.9-hotspot'
./backend/gradlew.bat -p backend compileJava compileMigrationJava compileTestJava --no-daemon --console=plain
./backend/gradlew.bat -p backend test --tests '*TaskUpdate*' --tests '*V6MigrationTest' --tests '*V5MigrationTest' --no-daemon --console=plain
./backend/gradlew.bat -p backend --init-script build/task-update-verification/parallel.init.gradle build verifyWorkspaceProxy --no-daemon --console=plain
./infra/verify-migration.ps1 -IncludeTaskUpdate
# frontend
npm test -- --reporter=dot
npm run build
```

Git 제외 parallel.init.gradle은 검증 JVM을 2개 병렬 실행하도록 지정했으며 제품 build.gradle의 테스트 병렬 설정은 바꾸지 않았다. 컴파일 호출은 backend 작업 디렉터리에서 ./gradlew.bat로 실행한 것과 동일한 작업이다.

### 검증 기록

- 컴파일 성공. 집중 **49개/4클래스, 실패·오류·스킵 0**(2분 44초): TaskUpdateApiTest 37개·TaskUpdateAccessPolicyTest 10개·V6/V5 migration 각 1개다. 이후 추가한 서로 다른 Task 동시 수정 1개는 전체 회귀에 포함한다.
- Vue **17파일/364개 통과**, 타입 검사/Vite build 성공. Task version 누락/숫자/음수/선행 0/범위 초과 거부·큰 문자열 보존·목록 요약 유지·생성 초기 0/잘못된 201 결과 불명을 검사했다. 앞선 355개에 새 버전 검사 9개를 추가했다.
- 집중 API는 OWNER/ADMIN/MEMBER 수정·VIEWER/미소속·두 부모/자원 및 오류 순서, 세 필드/설명 삭제·no-op/순차 stale, 실제 두 병렬 트랜잭션의 한 건 200/한 건 409(서로 다른 필드 포함), no-op 중 별도 commit의 최종 충돌, flush/지연 commit 실패 rollback·strict JSON/길이/Unicode·Origin/기본/쓰기 off·기동 권한 누락을 확인했다. 상태 IN_PROGRESS와 protected 필드/다른 Task·부모는 유지한다.
- 집중 권한은 다섯 컬럼만 실제 UPDATE 가능, status/id/project_id/created_at·전체 UPDATE/DELETE/TRUNCATE·시퀀스/history/DDL 금지, 부분/과도/PUBLIC/GRANT OPTION/자격증명·이력 오류 거부, 기존 도구 보존을 확인했다. V6는 데이터가 있는 V5에서 1건/Validate/반복 0건·기존 데이터/시간/상태/ACL/시퀀스/이전 이력 보존과 초기 version 0/제약을 확인했다.
- 새 JAR의 TaskUpdateController/V6 포함과 관리자 runner·정책 SQL/Flyway 실행 의존성/.env 제외를 검사했다.

전체 백엔드/Vite·PowerShell 결과와 최종 정리/로컬 보존 결과는 완료 후 아래에 기록한다. 현재 실제 로컬 V5/TASK_CREATE는 적용 대상이 아니며 별도 요청 전까지 유지한다. 새 앱의 로컬 기동은 V6/필요 권한 적용 뒤 수행해야 한다.


### 전체 회귀 중 수정 기록

첫 전체 실행은 446개/25클래스 중 445개 통과·1개 실패·오류/스킵 0이었다(12분 16초). TaskApiTest.originWriteDisabledAndStartupPermissions의 기동 오류 문구 기대값이 이전 'Task-create access'로 남아 있었으며, 현재 요구 정책은 Task-update policy다. 테스트 기대값을 수정했고 실행 중이던 컴파일 결과에는 반영되지 않아, 종료 후 전체 build/verifyWorkspaceProxy를 다시 실행했다. 실패한 XML/로그는 first-full-results/first-full.log로 보존하고 최종 결과와 구분한다.

실행 보조 스크립트 작성 중 PowerShell here-string 종료 문자와 Python 내 SQL here-string이 겹쳐 한 호출이 파싱 단계에서 중단된 적이 있다. 해당 호출은 실행되지 않았고 apply_patch로 나눠 적용한 뒤 PowerShell 구문 검사를 통과했다. 제품 SQL/DB 작업 실패는 아니다. 관련 없는 health/조회 테스트 3개에 생긴 인코딩/개행 차이는 원본 SHA-256과 일치하는 바이트로 복원했다.

### 최종 결과·정리·다음 작업

- 수정본 전체 **Java 446개/25클래스·실제 Vite 프록시 1개**, 실패/오류/스킵 0, **BUILD SUCCESSFUL in 11m 50s**다. 서로 다른 Task의 실제 병렬 수정은 각각 version 1로 성공했고, 같은 Task의 서로 다른 필드 경합·no-op 최종 검증·즉시/지연 실패 rollback과 기존 Workspace/Project/Task 생성·조회 회귀도 포함한다. 프록시에서 Task PATCH의 허용 Origin 200/잘못된 Origin 403·TODO 유지/Task version 1·부모 Project version 유지가 통과했다.
- 실제 PowerShell→Gradle→Java 도구 전체는 **105개 검사 통과**했다. 새 TASK_UPDATE 최초/반복·기존 6개 권한 도구 보존·다섯 컬럼 실제 UPDATE/rollback·부분 version 권한과 과도 status 권한의 거부/미수선·앱 자격증명/부모 프로세스 환경 보존과 기존 모든 단계도 포함한다. 모든 자식 출력에서 제공한 비밀번호가 없는지 검사했다.
- 실제 로컬 스냅샷의 전후 UTF-8 텍스트 및 JSON 내용이 동일하다. 사용자/공간/멤버십/Project/Task는 **5/3/6/1/1**, 설치 이력 V1~V5, Task version 컬럼 없음, Project 1 version 1/Task 1 TODO·MEDIUM·기존 행/시간·테이블/컬럼 ACL·소유권/시퀀스를 유지했다. 자격증명 3개와 V1~V5 SQL의 **8개 SHA-256**도 같았다. JAR는 이번 빌드 결과로 갱신한 것이므로 이전 해시 보존 대상으로 세지 않는다.
- 최종 보존 검사의 PowerShell 메모리 문자열 비교에서 한 번 불일치가 나왔다. 저장된 전후 파일은 UTF-8 텍스트 길이 11,829자와 JSON 전체가 동일해 실제 DB 변경이 없는 것을 확인했다. 최종 검증은 이 두 파일의 직접 텍스트/구조 비교로 확정했다. DB 자동 복구·롤백·수선은 실행하지 않았다.
- 고유 Compose flowdesk-migration-check-38a4807929cf43349c20952b99120835의 컨테이너/볼륨/네트워크와 임시 .env 3개가 모두 없는 것을 확인했다. Testcontainers 자원/검증 JVM/Vite도 종료됐고 Docker에는 기존 로컬 PostgreSQL(healthy)/기존 MariaDB만 남았다.
- Git 제외 backend/build/task-update-verification에 compile/focused/first-full/full/powershell 로그·집중/최초 전체 XML, java-summary/proxy-summary/jar-check·전후 로컬 스냅샷/보호 파일 해시·receipt.json을 남겼다. 최종 receipt 상태는 VERIFIED다. frontend/.cache/task-version-tests.log/task-version-build.log도 보관한다.

현재 로컬은 V5/TASK_CREATE다. 새 JAR/IDE 앱의 Task @Version 매핑에는 V6가 필요하고 쓰기 모드에는 TASK_UPDATE도 필요하다. 이번에 실제 로컬 V6/GRANT/업무 수정을 실행하지 않았다. 다음 한 작업은 **로컬 V6·최소 Task UPDATE 적용과 새 앱의 수정 확인**이다. Vue 수정 폼·버전 보존/409 복구 연결은 그다음 별도다.


## 2026-11-08 — 실제 로컬 V6·최소 Task UPDATE·새 앱 수정 확인

### 목표·적용 범위·흐름

사용자 요청으로 기존 로컬 flowdesk DB에 V6와 TASK_UPDATE를 적용하고, 앞서 격리 검증한 새 JAR의 실제 HTTP 수정을 확인했다. 애플리케이션/SQL/권한 도구·의존성 코드는 바꾸지 않았다. 기존 Task 1 한 행의 제목/설명/우선순위를 MEMBER Carol로 한 번 수정하고 버전/시각이 함께 바뀌는 범위다. 다른 역할의 허용 검사는 같은 값 no-op, 거부 검사는 변경을 남기지 않는 요청으로 했다.

HTTP PATCH(title/description/priority/version) → 기존 개발 접속·쓰기/Origin·JSON 경계 → TaskUpdateController → TaskCommandService의 서버 사용자/membership·Project 부모·Task 부모·세 역할/버전 검사 → JPA OPTIMISTIC 검증·Task 변경/flush → commit → 200/version 1 → 상세/목록 GET·DB 행 비교 흐름이다. 실제 로그인/운영 인증은 여전히 Phase 2다.

### 실행 명령과 결과

```powershell
# root; JDK 21, 기존 로컬 PostgreSQL healthy, 실행 앱 포트 비어 있음 확인 후
$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-21.0.13.9-hotspot'
./infra/migrate-local.ps1 -Action Info
./infra/migrate-local.ps1 -Action Migrate
./infra/migrate-local.ps1 -Action Validate
./infra/migrate-local.ps1 -Action Info
./infra/migrate-local.ps1 -Action Migrate
./infra/provision-task-update.ps1
./infra/provision-task-update.ps1
```

이 명령은 Git 제외 `backend/build/local-task-update-verification/apply-schema.ps1`/`finish-schema.ps1`의 결과 검사·로그 저장으로 실행했다. Info의 V6 PENDING → **V6 1건 적용 → Validate 성공 → V1~V6 SUCCESS → 반복 0건**, 최초·반복 **TASK_UPDATE/기존 비밀번호 보존**을 확인했다.

- 적용 전 5/3/6/1/1·V5·Task 1 version 없음, 적용 후 기존 Task는 version 0이다. 기존 사용자/공간/멤버십/Project/Task의 이전 필드·시간, V1~V5 설치 행, 테이블 ACL/소유권·시퀀스가 같았다. 컬럼은 tasks.version 하나만 추가됐고 다섯 승인 컬럼의 UPDATE ACL만 추가됐다.
- 권한 메타데이터는 tasks SELECT/INSERT 유지, **UPDATE(title,description,priority,updated_at,version)만**·정확한 raw ACL/비위임 권한을 확인했다. 테이블 전체 UPDATE/DELETE/TRUNCATE·시퀀스/history·schema/database CREATE/TEMP·public USAGE는 없다. SET LOCAL ROLE flowdesk_app 트랜잭션에서 승인 다섯 컬럼의 0건 UPDATE/조회 성공, status/project_id/created_at/id UPDATE·DELETE와 history 조회의 42501 거부를 확인하고 ROLLBACK했다. 실제 업무 행·시퀀스는 이 probe로 바뀌지 않았다.
- `verify-http.ps1`은 자격증명을 자식 환경에만 전달하는 loopback 18081 새 JAR를 역할별로 총 6회 띄웠다. 기존/검증 앱이 없음을 먼저 확인했고 소유한 프로세스만 종료했다. 영구 .env/쓰기 설정을 변경하지 않았다.

### 실제 HTTP·DB 결과

- **MEMBER Carol PATCH 200**, 제목/설명 공백·CRLF 정규화, priority HIGH, status TODO 유지, Task version **0→1**, 여덟 필드/문자열 ID·버전 DTO를 확인했다. 상세 GET/목록 200에서 새 값과 여섯 필드 목록 요약을 확인했다. /api/health·/actuator/health UP과 flowdesk_app의 실제 DB 세션도 확인했다.
- 같은 version 0의 stale PATCH는 **409/TASK_VERSION_CONFLICT**, 현재 version 1의 같은 값 no-op은 **200/version 1**이다. 두 요청 후 DB의 행·updated_at이 그대로임을 비교했다. OWNER Alice·ADMIN Bob도 동일 값 no-op 200/버전·시각 유지와 목록·상세 200을 확인했다. OWNER/ADMIN의 실제 값 변경·설명 삭제·병렬 경합/rollback은 앞선 격리 테스트와 구분한다.
- VIEWER Dave의 GET/목록은 200, stale 수정은 **403/TASK_FORBIDDEN**이며 X-User-Id/X-Role을 위조해도 같았다. 없는 Task/잘못된 부모는 역할보다 먼저 404다. Eve의 미소속·미존재·자기 공간/다른 Project 요청은 같은 404/TASK_NOT_FOUND였고 오류 detail도 같았다.
- MEMBER의 Project 수정은 기존대로 403, 잘못된 Origin은 403, status/부모를 본문에 넣으면 400이다. 쓰기 off Carol 앱은 GET/목록 200·PATCH 405·Actuator UP이었다. 거부/no-op 이후 추가 변경은 없었다.
- 최종 **5/3/6/1/1**, 기존 Task 1의 **title/description/priority/updated_at/version 다섯 필드만** 적용 후 기준에서 달라졌다. status TODO/project_id/created_at, 부모 Project 1 version 1·전체 행, Workspace/사용자/멤버십, migration 후 이력·ACL/시퀀스는 동일하다. 영수증으로 첫 PATCH 시도/확인을 기록해 결과 불명 자동 재전송을 막았다.
- 자격증명 3개/V1~V6 SQL/JAR의 **10개 SHA-256**은 작업 전과 같다. 새 JAR를 재빌드하지 않고 앞선 검증 결과 그대로 실행했다. 공급 비밀번호의 캡처 로그 유출이 없고 부모 프로세스 환경도 유지됐다. 검증 앱 6개를 종료해 8080/18081은 비어 있고 로컬 PostgreSQL은 healthy다.

### 검증 중 수정·미실행·산출물

확인용 apply-schema.ps1이 최초 권한 적용 성공 로그를 출력하며 빈 인자 해시테이블의 Action 속성을 읽어 StrictMode 오류로 중단됐다. V6/Validate/최초 GRANT 자체는 성공했다. 인덱서로 출력 부분을 수정하고 저장된 최초 TASK_UPDATE 결과를 확인한 뒤 finish-schema.ps1로 반복 GRANT/스냅샷 단계부터 이어갔다. 업무 PATCH는 그 이후 한 번만 실제 값을 변경했고, 제품 코드나 DB 수선은 필요하지 않았다.

제품 코드/SQL/권한 정책을 바꾸지 않아 앞선 **Java 446개/Vite 1개·PowerShell 105개·Vue 364개/빌드** 전체 회귀는 반복하지 않았다. 이번 실제 로컬 migration/권한·SQL probe·새 JAR HTTP/DB 검증과 구분한다. seed/새 업무 행 삽입은 하지 않았다.

Git 제외 backend/build/local-task-update-verification에 apply-schema/finish-schema/verify-http.ps1, snapshot/privileges/privilege-probe.sql, before/after-schema/after-http.json, migration/grant/http/역할별 종료 로그·권한 JSON과 최종 **receipt.json Status VERIFIED**를 보관한다. 현재 새 앱은 로컬 V6/필요 권한과 호환된다. 다음 한 작업은 **Vue Task 제목·설명·우선순위 수정 폼·버전 보존·409 충돌 안내 연결**이다.


## 2026-11-08 — Vue Task 수정 폼·버전 보존·409 충돌 안내 연결

### 목표·구현·요청 흐름

TaskUpdateForm/updateTask를 기존 TaskBrowser 상세에 연결했다. OWNER/ADMIN/MEMBER에게 제목/설명/우선순위 편집을 제공하며 VIEWER는 조회를 유지한다. 상태 변경·담당자/마감일·이력은 이번 범위가 아니다. TaskBrowser는 Task ID key와 부모/선택/수명 가드를 유지하고 성공/최신 조회 DTO로 현재 상세·같은 ID 목록의 제목/상태/우선순위만 갱신한다. 추가 성공 GET을 보내거나 페이지/다른 항목을 바꾸지 않는다.

Vue의 편집 시작 Task/문자열 version과 세 draft → updateTask nested PATCH(title/description/priority/version만) → 기존 개발 접속/쓰기·Origin·JSON 경계/Task Controller → 서버 멤버십·두 부모/역할·Task 버전 검사/쓰기 트랜잭션 → DB commit/200 → 부모/ID·정규화 세 필드·기존 status·버전 검사 → 상세/목록 갱신 흐름이다. 실제 변경은 version+1, 정규화 no-op은 같은 version이며 BigInt는 비교에만 쓰고 HTTP에는 문자열을 유지한다.

409/결과 불명(응답 유실·시간 초과·5xx·잘못된 200)은 입력/편집 기준을 유지하고 저장을 잠근다. 최신 Task GET + Workspace 역할 GET이 모두 성공해야 서버/입력 제목·설명·우선순위 비교를 표시한다. 사용자가 명시적으로 최신 기준을 채택해도 draft는 그대로이며 PATCH를 보내지 않는다. 별도 수동 저장만 새 버전을 사용한다. 역할 상실/조회 실패는 잠금을 유지하고 GET 내용 일치도 특정 PATCH 성공으로 단정하지 않는다. 언마운트는 쓰기/복구 요청과 늦은 이벤트를 종료한다.

변경 파일은 frontend/src/components/TaskUpdateForm.vue·TaskBrowser.vue, api/tasks.ts, style.css, 신규 task-update-api.spec.ts·TaskUpdateForm.spec.ts와 문서다. 기존 Project 폼/공통 CSS·Composition API/fetch/Vite 패턴을 재사용했고 새 의존성/전역 상태 계층·백엔드/SQL/권한·영구 .env는 변경하지 않았다.

### 실행한 검증과 결과

```powershell
# frontend
npm test -- --reporter=dot
npm run build
# root; 고유 격리 PostgreSQL만 업무 쓰기
$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-21.0.13.9-hotspot'
./backend/build/task-update-browser-verification/verify.ps1
```

- 프론트 **19파일/415개 통과**(기존 364개 + 신규 51개), 타입 검사/Vite build 성공. 두 부모/큰 문자열 버전/정규화·no-op/설명 삭제·우선순위만 변경, 상태/우선순위/버전·필드가 다른 200 거부, 확정 거부/결과 불명 분류·시간 제한/취소, 세 역할 편집·VIEWER 미노출, props 갱신 시 시작 기준/입력 보존, 409/결과 불명 저장 잠금·두 GET 공동 성공·역할 상실/재조회 실패·우선순위 비교/명시적 기준 갱신·수동 저장, 현재 상세/같은 ID 목록만 갱신·늦은 이벤트 무시를 확인했다.
- 실제 Chrome/Vite/새 JAR/고유 PostgreSQL에서 MEMBER의 세 필드 정상 저장·한 번의 PATCH로 중복 제출 차단·일반 텍스트 설명/목록·상세/우선순위 갱신을 확인했다. 다른 HTTP 클라이언트가 먼저 version 2를 저장한 뒤 브라우저의 version 1 요청은 409였다. 세 draft/시작 버전·저장 잠금 유지, 서버 LOW/내 HIGH 비교, 최신 기준 명시적 채택 후 별도 수동 저장(version 2→3)을 확인했다.
- 다음 저장의 서버 commit 후 200 응답만 CDP에서 끊었다. 폼은 결과 불명 잠금/입력을 유지했고 최신 GET에서 같은 값이 보여도 성공 처리·자동 재전송하지 않았다. DB에는 해당 변경/version 4가 남았다. VIEWER는 폼 없이 조회했고, 쓰기 off MEMBER의 405는 세 입력 유지/안전한 거부로 처리했다.
- 브라우저 PATCH **5회**와 별도 클라이언트 **1회** 중 정상 commit 4건, stale 409 1건, 쓰기 off 405 1건이다. 격리 최종 **5/2/5/1/1**, Task 1 title='Browser Lost Task Update'/description null/priority MEDIUM/status TODO/version 4, 부모 Project 원래 이름/설명·version 0/시간과 Workspace version 0을 확인했다. 1280/390px 캡처와 모바일 가로 넘침 없음도 확인했다.
- 실제 로컬 전체 스냅샷은 이전 V6 적용 후 결과와 동일하다. **5/3/6/1/1**, Project 1 version 1/Task 1 version 1·HIGH/TODO 및 데이터/이력/테이블·컬럼 ACL/소유권·시퀀스를 보존했다. 자격증명 3개/V1~V6/JAR **10개 SHA-256**도 같았다.
- 소유 앱/Vite/Chrome, 고유 Compose flowdesk-migration-check-8133b09db8394e91aa0448432f101bbb의 컨테이너/볼륨/네트워크와 임시 자격증명 3개를 정리·별도 확인했다. 기존 로컬 PostgreSQL은 healthy다.
- 백엔드/SQL/권한 소스가 바뀌지 않아 앞선 Java 446개/별도 프록시 테스트 1개·PowerShell 전체 105개 회귀는 반복하지 않았다. 이번 실제 Chrome/Vite/DB PATCH 검증과 구분한다. 격리 DB 준비에는 기존 전용 도구 10단계를 사용했다.

### 발견·수정·산출물

첫 프론트 실행에서 폼 제목 heading과 제목 input의 ID 중복으로 제목 입력이 정상 반영되지 않는 문제를 발견해 heading을 task-update-heading으로 분리했다. 함께 추가한 TaskBrowser 통합 fixture의 필수 부모 props와 기존 CSS 이름도 정리했다. 테스트 10개 실패 후 이 수정으로 1개만 남았고, 남은 405 안내 기대값의 한국어 조사를 실제 문구에 맞춰 고친 뒤 전체 415개와 빌드를 통과했다. 브라우저 검증은 수정본으로 통과했다.

Git 제외 frontend/.cache/task-update-tests.log/task-update-build.log, task-update-browser.mjs, task-update-check/{desktop,mobile,conflict,uncertain}.png·summary.json/종료 로그, backend/build/task-update-browser-verification/verify.ps1/result.log/local-after.json/receipt.json에 증거를 보관했다. 최종 receipt 상태 VERIFIED다. 다음 한 작업은 **Task 상태 변경 API의 허용 전이·권한 범위 확정**이다. 상태 변경/이력 정책은 아직 정하지 않았다.
## Task 상태 변경 계약 확정 (2026-11-08)

- 목표/완료 기준: 사용자 답변에 따라 허용 전이·권한·최소 이력 범위를 문서로 확정했다. [계약](task-status-api-v1.md)과 ADR-053에 선택·대안·후속 검증 기준을 기록했다.
- 요구사항 → HTTP → Service/DB → 응답: TODO/IN_PROGRESS/DONE 사이 모든 전이를 허용한다. 전용 PATCH `/api/workspaces/{workspaceId}/projects/{projectId}/tasks/{taskId}/status`에 status/version을 보내고, 서버가 해당 Workspace의 OWNER/ADMIN/MEMBER인지 확인한다. 일반 수정과 같은 Task 버전으로 충돌을 검출하며 오래된 버전은 409다. 현재 버전의 같은 상태는 버전/시각/이력 변경 없이 200이다.
- 실제 전환은 상태/시각/버전 변경과 서버 행위자·이전/이후 상태·새 버전·시각의 이력 INSERT를 같은 트랜잭션으로 처리한다. 이력 실패 시 상태도 rollback한다. 기존 TaskView 응답을 유지하고 이력 조회/화면은 후속이다.
- 설계: 일반 수정의 status `updatable=false`를 유지하고 상태 전용 조건부 UPDATE의 영향 행 수 검사/refresh와 이력 INSERT를 묶는다. native SQL에는 JPA 버전 검사가 자동 적용되지 않으므로 구현 때 직접 검증한다. V7과 status UPDATE/이력 여섯 입력 컬럼 INSERT만 추가하는 별도 권한 도구를 계획했다. 이력 SELECT/시퀀스 권한은 추가하지 않는 구현안을 격리 DB에서 확인한다.
- 변경 파일: 전용 계약, requirements/architecture/tech-stack/roadmap, 기존 Task 계약, decisions/learning-notes/local-migration, 각 README와 이 기록이다. 구현 코드/SQL/실제 DB는 변경하지 않았다.
- 실행한 검증: PowerShell에서 Python 문서 검사 스크립트를 실행해 HTTP JSON 예제 2개, 전이 행렬 6개 허용/3개 no-op, 계약 링크 11개, UTF-8/코드 블록·ADR 중복을 확인했다. 작업 전 SHA-256 기준과 비교한 구현/설정/JAR 등 192개 파일은 동일했다. 결과는 `backend/build/task-status-contract-verification/result.json`의 VERIFIED다.
- 미실행: 문서만 변경했으므로 Gradle/npm 테스트·빌드 및 PostgreSQL 명령은 실행하지 않았다. 이번 검증은 API/SQL 동작의 성공을 의미하지 않는다.
- 다음 한 가지: Task 상태 변경 API·V7·최소 권한 도구·상태 이력 원자성 구현과 격리 PostgreSQL 검증.


## Task 상태 변경 API·V7·최소 권한 도구 구현·격리 검증 (2026-11-10)

- 목표/완료 기준: 확정된 모든 전이·OWNER/ADMIN/MEMBER·공유 버전/409·최소 이력 동일 트랜잭션을 구현하고 고유 PostgreSQL에서 검증한다. 실제 로컬 V7/GRANT와 Vue 상태 UI는 이번 범위에서 실행하지 않는다. [구현/재현 명령](task-status-implementation.md), [계약](task-status-api-v1.md), ADR-054를 따른다.
- HTTP → Controller → Service/Transaction → Repository/DB → 응답: strict status/version PATCH를 개발 접속/Origin/쓰기 경계 뒤 처리한다. 서버 행위자/소속/Project·Task 부모/역할/버전 검사 후 조건부 상태 UPDATE·영향 행 수/refresh·이력 여섯 컬럼 INSERT/1건 확인을 한 READ_COMMITTED 트랜잭션에서 수행한다. no-op은 OPTIMISTIC commit 검증만 하고 이력을 만들지 않는다. 최종 commit 성공 후 기존 TaskView를 200으로 반환한다. 이력 실패/0건·최종 commit 실패는 상태도 rollback한다.
- 변경 파일: TaskStatusController·쓰기 off 전용 거부 Controller·TaskErrors/CommandService·TaskStatusChangeRepository, V7 SQL, AppReadPolicy/AppReadAccessRunner·새 실행기/정책 SQL·MigrationHistory/seed, Gradle/PowerShell 도구/기동 검사, 격리 API/정책/마이그레이션/기존 writer/proxy·schema-validation fixture와 문서다. Vue 소스/응답 필드는 변경하지 않았다.
- 설계 선택: Task.status updatable=false와 일반 수정 @Version 경계를 보존하고, native SQL 버전 조건/증가·행 수를 직접 맡는다. audit ID 반환이 필요 없어 generated keys/RETURNING/SELECT 없이 INSERT한다. 앱에는 기존 TASK_UPDATE + status UPDATE + audit 입력 여섯 컬럼 INSERT만 허용하며 부분/과도/PUBLIC/GRANT OPTION은 수선하지 않는다. 이전 도구와 새 쓰기 기동·현재 V1~V7/과거 target 6/미래 V8 fixture를 함께 전환했다.
- 첫 집중 실행(11월 8일): `./gradlew.bat test --tests '*TaskStatus*' --tests '*V7MigrationTest' --tests '*V6MigrationTest' --no-daemon --console=plain`에서 67개 중 2개가 실패했다. 전체 컬럼 UPDATE probe가 identity 규칙(428C9)에 먼저 걸려, 보호 컬럼의 권한을 직접 검사하도록 SQL을 수정했다. GET 대응이 없는 `/status`는 쓰기 off 기본 404여서 개발 모드의 거부 Controller로 확정된 405를 명시했다.
- 재개 시(11월 10일): Docker Desktop 종료로 4개 클래스가 초기화 단계에서 실패했다. Docker를 백그라운드로 시작하고 준비 확인 후 다시 실행했다. 중단된 이전 고유 Compose 프로젝트 99ece103d8b24511953356271add04a5만 down/volume 정리하고 그 복사본 자격증명 3개를 삭제했다. 실제 로컬 volume은 대상으로 삼지 않았다.
- 수정 후 집중 검증: 같은 명령의 `focused-ready.log`가 BUILD SUCCESSFUL이다. XML 기준 API 55개·권한 13개·V7 1개·기존 V6 1개, 총 70개/4클래스가 실패/오류/skip 없이 통과했다. 18번의 세 역할/모든 방향 전이·정확한 서버 행위자/새 버전/같은 DB 시각, no-op/stale/입력/Origin/기본·쓰기 off, 실제 병렬 상태↔상태/상태↔일반 수정·각 경로 승자, UPDATE/INSERT/FK/UNIQUE/권한·지연 commit rollback·다른 Task 독립성을 확인했다. 이후 이력 INSERT 0건 rollback과 두 번째 GRANT 실패 시 첫 GRANT rollback 테스트를 추가해 최종 전체 검증에 포함했다.
- Vue 회귀: frontend의 `npm.cmd test`에서 415개/19파일 통과, `npm.cmd run build`에서 vue-tsc와 Vite production build가 성공했다. 결과는 frontend-test.log/frontend-build.log다. 기존 추적 대상 프론트 소스·설정 39개 해시도 이전 기준과 같았다.
- 로컬 보존: 실제 로컬은 V1~V6/TASK_UPDATE와 사용자/공간/멤버십/Project/Task 5/3/6/1/1을 유지했다. 작업 전후 JSON 전체의 업무 행·설치 이력·ACL/소유권·컬럼 권한·시퀀스가 같고 자격증명 3개/V1~V6 SQL 총 9개 SHA-256이 같다. 읽기 전용 스냅샷을 위해 기존 컨테이너를 잠시 시작한 뒤 원래 중지 상태로 복원했다. 로컬 Migrate/GRANT/업무 DML은 실행하지 않았다.
- JAR/정적 검사: 상태 API/거부 Controller/Repository·V7은 포함하고 관리자 클래스·정책 SQL·Flyway runtime·환경 파일은 제외했다. PowerShell 파일 파싱과 UTF-8/코드 블록/계약·구현 링크/ADR 중복 검사를 통과했다.
- 전체 회귀의 기존 fixture 수정: V7의 새 FK 때문에 JpaSchemaValidationTest의 Task 누락 준비 SQL이 테이블 삭제 전에 실패했다. 고유 Testcontainers DB에서만 `DROP TABLE ... CASCADE`로 참조 제약도 제거해 의도한 누락 상태를 만들도록 수정했다. 최종 전체/실제 Vite·빌드 및 PowerShell 결과는 아래에 이어 기록한다.

### 최종 결과 — 2026-11-10

- 최초 전체 명령 `./gradlew.bat test verifyWorkspaceProxy build --no-daemon --console=plain`은 28클래스/516개 중 515개 통과·JPA Task 누락 fixture 1개 실패였다. 이 실행은 :test에서 실패했으므로 이후 프록시/빌드 완료로 간주하지 않았다. 원본 XML은 `first-full-results/`에 보존했다.
- 수정 후 실제 명령 `./gradlew.bat test --tests '*JpaSchemaValidationTest' --tests '*TaskStatusAccessPolicyTest.secondGrantFailureRollsBackTheFirstGrant' verifyWorkspaceProxy build --no-daemon --console=plain`이 BUILD SUCCESSFUL(1분 8초)이다. JPA 검증 7개와 새 권한 원자성 1개, 총 8개가 실패/오류/skip 없이 통과했다. 최초 전체의 나머지 검증 결과를 유지하고 같은 이름의 JPA 7개 결과를 교체한 고유 검증은 516−7+8=517개이며 모두 통과다. 전체 517개를 한 번에 재실행한 결과와 구분한다.
- 실제 Vite 프록시 검증 1개도 통과했다. 기존 Workspace/Project/Task 생성·일반 수정 뒤 깊은 Task `/status` PATCH의 200/version 2/DONE·정확한 이력 1건, 외부 Origin 403을 확인했다. 생성/일반 수정 단계의 상태 이력은 0건이다. 이후 assemble/check/build가 완료됐고 JAR 경계/해시도 최종 결과와 같다.
- `pwsh -NoProfile -File infra/verify-migration.ps1 -IncludeTaskStatusChange`는 118 checks 통과다. V1~V7·Validate/반복 0건·seed·모든 이전 상태·최초/반복 status 도구/이전 도구 보존·audit SELECT/시퀀스 없이 INSERT·금지 권한/부분 상태 거부·rollback·비밀번호와 부모 환경 보존을 확인했다. 이 격리 복사본의 관리자 도구/SQL/Gradle 관련 50개 파일은 최종 코드와 해시가 같다.
- 자원 정리: 현재 고유 프로젝트 231f4eb5e16b4ce4bccda4d2f5e81f4c와 앞서 중단된 프로젝트 99ece103d8b24511953356271add04a5의 컨테이너/볼륨/네트워크가 모두 없음을 Docker label 조회로 확인했다. 각 프로젝트 자격증명 3개, 합계 6개도 삭제됐다. 실제 로컬 컨테이너는 원래 중지 상태다.
- 최종 영수증 `backend/build/task-status-verification/receipt.json`은 VERIFIED다. backend-full.log(최초 전체), backend-corrected.log(수정 검증/실제 프록시/빌드), powershell-final.log, frontend-test.log/frontend-build.log, artifact/local-preservation/cleanup JSON을 함께 보관했다. 이력 INSERT 0건과 두 번째 GRANT 실패 시 첫 GRANT rollback도 최종 고유 검증에 포함한다.
- 미실행/후속: 실제 로컬 V7 Migrate·상태 변경 GRANT·업무 상태 PATCH는 실행하지 않았다. Vue 상태 UI/브라우저 상태 조작·Activity 조회 API도 이번 범위가 아니다. 기존 Vue 테스트/빌드와 실제 HTTP/Vite 프록시 결과를 UI 연결 완료로 바꾸지 않는다.
- 다음 한 가지: **로컬 V7·최소 Task 상태 변경 권한 적용과 새 앱의 상태 변경·이력 확인**.

## 실제 로컬 V7·최소 상태 권한 적용과 새 JAR의 상태/이력 확인 (2026-11-10)

- 목표/완료 기준: 실제 로컬 V6에 V7/최소 상태 권한을 적용하고 새 앱의 상태 PATCH·이력·버전/인가·기존 데이터 보존을 확인한다. 사용자의 이번 요청으로 실제 적용과 단일 업무 전환을 승인받았다. 기존 구현/격리 검증과 구분하며 제품 코드/SQL/의존성은 변경하지 않는다.
- 사전 확인: 8080/18081 리스너가 없었고 기존 flowdesk-local-postgres-1은 중지 상태였다. 기존 컨테이너를 시작하고 loopback/환경 파일의 동일 DB/계정, V1~V6·Task 1 TODO/version 1·앞선 VERIFIED JAR 해시를 확인했다. Task는 1건, 사용자/공간/멤버십/Project/Task는 5/3/6/1/1이었다. 자격증명 3개·V1~V7 SQL·JAR 총 11개 SHA-256과 전체 데이터/이력/ACL/시퀀스를 저장했다.
- 실행한 실제 명령: `pwsh -NoProfile -File backend/build/local-task-status-verification/prepare.ps1`, `pwsh -NoProfile -File backend/build/local-task-status-verification/apply-schema.ps1`을 실행했다. apply-schema 안에서 기존 `infra/migrate-local.ps1 -Action Info/Migrate/Validate/Info/Migrate`와 `infra/provision-task-status-change.ps1` 최초/반복을 호출했다. V7 1건·Validate 성공·재실행 0건, TASK_STATUS_CHANGE 최초/반복 성공이다.
- 스키마/권한 대조: after-schema.json/privileges.json을 `python backend/build/local-task-status-verification/verify-snapshots.py schema`로 확인했다. 기존 업무 행/시간/버전·V1~V6 설치 이력/기존 relation ACL/기존 시퀀스는 그대로이고 새 이력 테이블은 비어 있었다. Task status UPDATE와 이력 입력 6개 컬럼 INSERT만 추가됐다. 이력 전체 INSERT/id INSERT/SELECT/UPDATE/DELETE/TRUNCATE·직접 시퀀스·DDL/이력 권한은 없다. 원시 ACL의 grantee/grantor/GRANT OPTION 및 V7 FK 2·CHECK 4·PK 1·UNIQUE 1/identity ALWAYS도 확인했다.
- HTTP 실행: `pwsh -NoProfile -File backend/build/local-task-status-verification/verify-http.ps1`이 HTTP_VERIFIED로 완료됐다. 검증된 JAR을 앱 자격증명만 가진 child process/127.0.0.1:18081/dev-actor로 기동했다. 행위자/쓰기 flag/Origin은 이 프로세스에만 주입하고 .env/부모 환경은 바꾸지 않았다. 본 요청 전에 PATCH_ATTEMPTED, 200/DTO 확인 후 PATCH_CONFIRMED를 저장해 결과 불명 시 자동 재전송하지 않게 했다.
- HTTP → Controller → Service/Transaction → Repository/DB → 응답: Carol(MEMBER)의 `/api/workspaces/1/projects/1/tasks/1/status` PATCH `{"status":"IN_PROGRESS","version":"1"}`가 200을 반환했다. 개발 접속/Origin·strict 입력 → 서버 행위자/부모·역할/버전 → 조건부 상태 UPDATE·refresh → 고정 컬럼 이력 INSERT/commit → 기존 TaskView 8필드 순서다. Task 1은 IN_PROGRESS/version "2"이며 제목/설명/우선순위 HIGH·부모/생성 시각은 유지했다. 목록 6필드/상세 GET도 반영됐다.
- 이력 검증: 별도 관리자 읽기로 이력 ID 1·Task 1·actor Carol(id 3)·TODO→IN_PROGRESS·task_version 2·occurred_at=Task updated_at을 확인했다. 앱에 이력 SELECT/generated keys/직접 시퀀스 권한을 주지 않고 실제 전환에 성공했다.
- 추가 HTTP: 이전 version 1의 상태 요청과 일반 수정은 409/TASK_VERSION_CONFLICT다. 최신 version 2의 같은 상태와 일반 수정 no-op은 200이며 행/시각/버전/이력은 그대로다. OWNER/ADMIN도 상태 no-op 200으로 허용을 확인했다. VIEWER는 위조 사용자/역할 헤더·stale version을 보내도 403, 미소속/미존재/부모 불일치는 같은 404다. 외부/누락 Origin 403, 행위자 추가 필드·소문자 상태 400, MEMBER의 Project 관리 403을 유지했다. 쓰기 off의 조회 200/상태 PATCH 405와 /api/health·/actuator/health UP, 실제 flowdesk_app JDBC 세션을 확인했다.
- 최종 대조: 전체 after-http.json에 대해 `python backend/build/local-task-status-verification/verify-snapshots.py final`이 VERIFIED다. 기존 Task의 status/updated_at/version 세 컬럼만 바뀌었고 이력 1건/새 audit sequence last_value 1만 추가됐다. 부모 Project/Workspace·다른 필드/행·이전 마이그레이션 이력·기존 ACL/시퀀스와 보호 파일 11개는 그대로다. 데이터 5/3/6/1/1 + 상태 이력 1건이다.
- 정리/실행 상태: Carol 쓰기 on/off·Alice·Bob·Dave·Eve의 앱 6개를 모두 종료했고 8080/18081은 비어 있다. 자격증명 로그 비노출/부모 환경 보존을 확인했으며 임시 자격증명 파일은 만들지 않았다. PostgreSQL은 running/healthy로 유지했다. 영수증은 Git 제외 `backend/build/local-task-status-verification/receipt.json`의 VERIFIED이며 단계별 migration/grant/http/앱 로그와 스냅샷을 함께 보관했다.
- 미실행: 제품 코드·SQL·JAR 변경이 없어 이전 백엔드 고유 517개·프록시 1개·PowerShell 118개·Vue 415개/빌드를 반복하지 않았다. 실제 로컬에 장애 주입·병렬 경합·데이터 초기화를 하지 않았고 해당 검증은 앞선 격리 결과를 따른다. Vue 상태 UI와 Activity 조회는 이번 범위가 아니다.
- 다음 한 가지: **Vue Task 상태 변경 UI·버전 보존·409 충돌 안내 연결**.

## Vue Task 상태 변경 UI·공유 버전·409 복구 (2026-11-10)

- 목표/완료 기준: 기존 Task 상세에 세 역할의 상태 선택·전용 PATCH·문자열 버전·일반 편집과 독립된 기준·409/결과 불명 복구를 연결하고 실제 브라우저/격리 DB까지 확인한다. [설계/변경 파일/흐름](task-status-ui.md), ADR-056을 따른다. 백엔드/SQL/권한·의존성은 변경하지 않는다.
- Vue → HTTP → 기존 서버 → 응답/화면: TaskStatusForm의 시작 Task/목표 상태 → changeTaskStatus의 status/version 두 필드 PATCH → 기존 개발 경계/Controller·Service의 서버 행위자/부모·역할/공유 버전·상태/이력 트랜잭션 → 200 TaskView의 ID/부모·상태/버전·일반 필드 검증 → 현재 상세/같은 ID 목록만 갱신한다. 일반 폼의 세 입력/시작 버전은 상태 저장/props 갱신으로 바꾸지 않고 반대 방향도 같다.
- 복구: 409/결과 불명은 선택/입력과 잠금을 유지한다. Task GET + Workspace 역할 GET이 모두 성공하면 상태/일반 필드 비교 → 명시적 기준 갱신(선택 유지/PATCH 없음) → 별도 수동 저장이다. 두 폼 모두 이미 아는 버전보다 오래된 복구 응답을 채택하지 않고 TaskBrowser는 이전 버전으로 화면을 되돌리지 않는다. 역할 상실·조회 실패·언마운트 후 응답을 막는다. GET에 선택과 같은 상태가 보여도 이전 요청 성공으로 처리하거나 자동 재전송하지 않는다.
- 변경 파일: api/tasks.ts·신규 TaskStatusForm.vue·TaskBrowser.vue·TaskUpdateForm.vue·style.css, 신규 task-status-api.spec.ts/TaskStatusForm.spec.ts와 기존 TaskUpdateForm.spec.ts다. 새 패키지/라우터/전역 상태 계층은 없다. 스타일은 기존 폼/select·비교/복구 스타일을 재사용했다.
- 단위/컴포넌트 명령: frontend에서 `npm.cmd test`, `npm.cmd run build`. 최초 477개/21파일·타입 검사/빌드가 통과했고, 오래된 복구 GET과 실패 후 이전 비교 결과 삭제 검증을 보강한 최종 **479개/21파일** 및 vue-tsc/Vite production build도 통과했다. 테스트 실패는 없었다. 로그는 backend/build/task-status-ui-verification/frontend-test-first.log, frontend-test.log, frontend-build.log다.
- 실제 브라우저 명령: 저장소 루트 `pwsh -NoProfile -File backend/build/task-status-browser-verification/verify.ps1`. 고유 Compose flowdesk-migration-check-334f05742c5245d2913efcf074f7b2ff·flowdesk_verify에서 V1~V7/seed/기존 권한·TASK_STATUS_CHANGE를 준비했다. 기존 JAR/새 Vue/Vite와 실제 headless Chrome/CDP를 사용했으며 앱 URL의 DB 이름도 flowdesk_verify로 제한했다. 실제 로컬에는 읽기 전용 스냅샷만 수행했다.
- Chrome 결과: MEMBER의 상태 저장/이중 submit 한 요청·목록/상세, 일반 폼 세 입력/기준 유지 후 상태→일반 409·상태 포함 비교/명시적 기준/별도 저장, 일반→상태 충돌과 목표 DONE 보존/서버 일반 정보 비교·수동 저장, 다른 클라이언트의 상태 변경에 대한 409를 확인했다. 서버 commit 후 200 응답만 끊어 결과 불명 잠금/미재전송·내용 일치 GET이 성공 영수증이 아님도 확인했다. VIEWER는 두 폼을 숨기고 읽기만 제공했으며 쓰기 off의 상태 PATCH 405는 선택을 유지한 안전한 거부 안내였다.
- 실제 브라우저/DB 대조: 브라우저 상태 PATCH 6개(정상/409/정상/409/commit 후 응답 유실/405), 일반 PATCH 2개(409/성공), 다른 HTTP 클라이언트 2개다. 격리 Task는 version 6/IN_PROGRESS/LOW이며 상태 이력은 Carol의 버전 1/4/5/6 총 4건이다. 일반 수정 버전 2/3에는 상태 이력이 없고 부모 Project/Workspace version 0·시각/내용과 기존 예제는 유지됐다.
- 화면 확인: 1280px와 390px에서 넘침 없이 선택·저장·비교·복구를 확인하고 desktop.png/mobile.png/conflict.png/uncertain.png를 저장했다. 실제 desktop/mobile 이미지도 열어 배치를 확인했다. 스크린샷·summary.json·프로세스 로그는 frontend/.cache/task-status-check/, 브라우저 검증 로그는 backend/build/task-status-ui-verification/browser.log다.
- 보존/정리: 실제 로컬 전체 스냅샷은 backend/build/local-task-status-verification/after-http.json과 같았다. V7/5/3/6/1/1 + 상태 이력 1건, Task IN_PROGRESS/HIGH/version 2·Project version 1을 유지했다. 백엔드/infra/자격증명/JAR/package 등 167개 파일 SHA-256이 동일하다. 소유 앱/Chrome/Vite·고유 Compose 컨테이너/볼륨/네트워크를 종료/제거하고 임시 자격증명 3개 삭제도 Docker label/파일 조회로 확인했다.
- 최종 영수증: backend/build/task-status-ui-verification/receipt.json과 backend/build/task-status-browser-verification/receipt.json 모두 VERIFIED다. 부모 환경/실제 로컬 설정을 변경하지 않았다.
- 미실행: 백엔드/SQL/권한·JAR이 같아 Java 517개/PowerShell 전체 118개 회귀는 반복하지 않았다. 이번 실제 브라우저 검증의 DB 권한 준비/HTTP·이력 결과와 기존 백엔드 검증을 구분한다. 실시간/칸반·Activity 조회·담당자/마감일은 이번 범위가 아니다.
- 다음 한 가지: **Task 상태 변경 이력 조회 API의 계약·권한 범위 확정**. 이력 노출 필드/권한·페이지 정책은 다음 단계에서 정한다.

## Task 상태 이력 조회 계약 초안·정책 확인 대기 (2026-11-10)

- 목표: 상태 이력 조회의 계약/권한 범위를 정한다. AGENTS.md의 업무·보안 결정 확인 지침에 따라 네 역할의 전체 과거 이력 조회·행위자 ID/현재 이름 노출·최신순 버전 커서를 질문했다. 세 답변은 대기 상태이며 ADR에 확정으로 기록하지 않았다.
- 작성한 초안: docs/task-status-history-read-api-v1.md. Task별 GET → 기존 서버 행위자/소속·Project/Task 부모/역할 → readOnly 이력 조회/DTO, 추천 응답과 커서, 고정 7개 컬럼 SELECT·기존 도구/기동 SELECT 금지 전환·V7 재사용·격리 기준을 검토안으로 기록했다. 현재 추천안/대안을 명시적으로 구분했다.
- 실행한 검증: PowerShell에서 Python 문서 검사로 JSON 예시 1개(응답 8필드/이력 6필드·actor 2필드), 로컬 문서 링크/코드 블록·UTF-8을 확인했다. 작업 전 SHA-256과 비교한 코드/설정/JAR·자격증명 등 207개 파일은 같다. 결과는 Git 제외 backend/build/task-status-history-contract-verification/result.json의 DRAFT_VERIFIED_PENDING_USER_CHOICES다.
- 미실행: 문서만 변경했으므로 테스트/빌드·DB 조회/GRANT/DDL/DML은 실행하지 않았다. API·권한 도구·화면 구현 완료를 뜻하지 않는다. 답변 후 계약/ADR을 확정하고 다음 구현 범위를 정한다.

## Task 상태 이력 조회 계약·권한 범위 확정 (2026-11-10)

- 사용자 확정: 현재 Workspace 네 역할의 Task 전체 과거 상태 이력 조회(가입 전/현재 탈퇴한 행위자 기록 포함), 행위자 ID·현재 표시 이름/이메일 제외, 최신순 Task 버전 커서·기본 20/최대 100건이다. 앞선 초안의 세 답변 대기는 해소됐으며 ADR-057과 docs/task-status-history-read-api-v1.md에 기록했다.
- 목표/완료 기준: Task별 GET/응답·서버 현재 소속/두 부모·동일 404, 표시 이름의 현재 시점 의미, strict beforeVersion/size·다음 커서/빈 목록, readOnly projection·V7 재사용·고정 컬럼 SELECT/기동·이전 도구 전환/격리 기준까지 계약으로 확정했다. 이번은 문서만 변경한다.
- 예정 흐름: GET status-activities → 입력 검증 → 서버 사용자/현재 Workspace 소속 → Project·Task 부모 → 네 역할 → 이력/users.display_name projection → 부모/Task·items·beforeVersion/size·hasNext/nextBeforeVersion의 DTO/200이다. 과거 이름/역할 스냅샷으로 표현하지 않고 행위자 현재 membership으로 이력을 숨기지 않는다. 읽기 자체는 Task/이력·버전/시간·시퀀스를 변경하지 않는다.
- 권한 설계: TASK_STATUS_CHANGE에 audit 7개 컬럼 SELECT만 더하는 TASK_STATUS_HISTORY_READ/별도 도구를 정했다. 기존 INSERT/다른 권한을 유지하고 table-wide ACL·부분/과도/PUBLIC/GRANT OPTION은 수선하지 않는다. 현재 모든 컬럼을 허용하므로 SELECT * 문장은 성공할 수 있고, 미래 컬럼 자동 허용을 막는 것이 목적이다. 새 dev-actor 앱은 쓰기 on/off 모두 SELECT가 필요하며 기동 검사/이전 도구를 함께 전환한다. 실제 로컬 적용은 구현/격리 검증 뒤 별도 요청이다.
- 변경 파일: 전용 이력 조회 계약·ADR/learning-notes·requirements/architecture/tech-stack/roadmap/local-migration, README들·이전 상태/UI 계약 링크와 이 기록이다. 코드/SQL/권한 도구·API/실제 DB/이력 화면은 변경하지 않았다.
- 실행한 검증: PowerShell에서 Python 문서 검사로 JSON 응답 1개(8필드 envelope/6필드 item/2필드 actor), 상태 계약 링크·UTF-8/코드 블록·ADR 중복과 확정 전 표현 정리를 확인했다. 커서 예시 14/10→새 16 추가 후 8/3→1의 경계 계산도 확인했다. 작업 전 SHA-256과 비교한 코드/설정/JAR/자격증명 등 207개 파일이 같다. 결과 영수증은 Git 제외 backend/build/task-status-history-contract-verification/result.json의 CONTRACT_VERIFIED다.
- 미실행: 문서만 변경해 Gradle/npm 테스트·빌드, DB 조회/GRANT/DDL/DML은 실행하지 않았다. 문서 검사는 실제 API/SQL 동작 통과를 뜻하지 않는다.
- 다음 한 가지: **Task 상태 이력 조회 API·최소 SELECT 권한 도구 구현과 격리 PostgreSQL 검증**. 실제 로컬 권한 적용과 Vue 이력 화면은 그 뒤 별도 단계다.
### 2026-11-10 Task 상태 이력 조회 API·최소 SELECT 도구·격리 검증

- 범위/완료 기준: ADR-057의 GET/인가·현재 이름/이메일 비노출·최신순 버전 커서, TASK_STATUS_HISTORY_READ 전용 도구·기존 도구/기동 검사 전환을 구현하고 격리 PostgreSQL에서 검증한다. 실제 로컬 GRANT와 Vue 이력 화면은 실행 범위가 아니다. 흐름/파일/대안은 [구현 기록](task-status-history-implementation.md), 선택은 ADR-058을 따른다.
- 집중 명령(backend): `./gradlew.bat test --tests '*TaskStatusHistory*' --tests '*TaskStatusApiTest' --no-daemon --console=plain`. 첫 실행은 104개 중 103개 통과/기존 TaskStatusApiTest의 누락 테이블 기동 오류 문구 기대값 1개 실패였다. 새 History API 35개/권한 정책 13개는 모두 통과했다. 누락 테이블은 읽기/쓰기 검사 중 먼저 실행된 검사에서 거부될 수 있어, 쓰기 모드 기대값을 IllegalStateException/policy로 수정하고 읽기 모드는 새 읽기 정책 문구를 확인하도록 유지했다. 거부 동작을 완화하지 않았다. 원본 XML은 `backend/build/task-status-history-verification/focused-results/`, 로그는 focused.log에 보관한다.
- 전체 명령(backend): `./gradlew.bat test verifyWorkspaceProxy build --no-daemon --console=plain`. 새 테스트뿐 아니라 이전 모든 API/JPA/권한·migration/seed/과거 target/미래 fixture와 실제 Vite 프록시 GET을 함께 검증한다. 실제 완료 수와 결과는 아래 최종 항목으로 기록한다.
- 전체 실제 PowerShell 명령(루트): `pwsh -NoProfile -File infra/verify-migration.ps1 -IncludeTaskStatusHistoryRead`. 고유 Compose 프로젝트 `flowdesk-migration-check-b7ed73ebbf68434fa9405c0258b5059b`와 임의 포트/독립 DB에서 처음부터 모든 이전 권한 단계를 통과한 뒤 새 SELECT를 적용한다. 제공한 비밀번호의 자식 출력 노출도 검사하며, 실제 완료 수·정리는 아래 최종 항목으로 기록한다.
- 프론트 명령(frontend): `npm.cmd test`, `npm.cmd run build`. **21파일/479개 통과·vue-tsc/Vite build 성공**. 프론트 소스/package는 변경하지 않았다. 이력 화면은 구현/Chrome 검증하지 않았으며 실제 Vite GET은 백엔드 프록시 통합 테스트로 구분한다.
- 정적/산출물 확인: infra PowerShell 27개 구문 검사 통과. 새 실행 JAR에 조회 런타임 코드와 정확히 V1~V7이 있고 관리자 클래스/권한 SQL·Flyway 실행 라이브러리·환경 파일은 제외됐다. artifact.json에 JAR SHA-256을 저장했다. 작은 fixture의 EXPLAIN/기존 인덱스 존재는 확인하지만 특정 계획 강제나 대용량 성능 검증은 하지 않는다.
- 실제 로컬은 관리자 읽기 전용 snapshot.sql로 대조했다. 업무/Activity·Flyway history·관계/컬럼 ACL·시퀀스 전후가 같고, 이전 실제 HTTP 검증 after-http.json과도 같다. V7/TASK_STATUS_CHANGE·5/3/6/1/1 + 이력 1건·Task 1 IN_PROGRESS/HIGH/version 2를 유지한다. 실제 Apply·새 로컬 앱 기동/이력 HTTP는 미실행이다. Vue 소스/기존 V1~V7/세 환경 파일/package 등 보호 파일 52개 해시도 같다. 새 dev-actor 앱은 쓰기 on/off 모두 이력 SELECT가 필요하므로 별도 로컬 권한 적용 뒤 실행해야 한다.
- 로그/증거: Git 제외 `backend/build/task-status-history-verification/`의 backend-full.log/powershell.log/frontend-test.log/frontend-build.log·artifact.json·local-before/after.json·protected-before.json 및 최종 receipt.json이다. 최종 결과가 나오기 전 pending 실행을 통과로 간주하지 않는다.

**최종 검증: Java 30클래스/565개·실제 Vite 프록시 1개·Gradle build, PowerShell 전체 134개, Vue 21파일/479개·타입 검사/Vite build가 모두 통과했다.** 새 이력 API 35개/권한 정책 13개를 포함하며 전체 회귀는 실패/오류/스킵 0이다. 최초 집중 실행의 기존 오류 문구 기대값 1건은 수정 후 이 전체 실행으로 통과를 확인했다.

- 최종 종료 코드: backend 전체/실제 Vite proxy/build 및 PowerShell 전체 실행 모두 0. XML 기준 test 30클래스/565개·verifyWorkspaceProxy 1클래스/1개, 실패/오류/스킵 0이다. 프론트 두 명령도 모두 0이었다. 전체/프록시 XML은 full-results/proxy-results에 복사해 다음 실행과 구분했다.
- 자원 정리 확인: 위 고유 Compose label의 컨테이너/볼륨/네트워크 0개, 실행 중 Testcontainers 0개이며 실제 로컬 PostgreSQL은 healthy다. 독립 검증 디렉터리 infra/.env·infra/.env.migrator·backend/.env 세 파일 삭제를 확인했다. 사용자 기존 컨테이너/IDE 프로세스는 종료하지 않았다.
- 최종 보존: 마지막 읽기 전용 local-after.json이 local-before.json 및 이전 after-http.json과 일치하고 보호 파일 52개/새 JAR 해시도 일치한다. receipt.json의 Status=VERIFIED, ActualLocalSelectApplied=false다.
- 다음 한 작업: **로컬 상태 이력 SELECT 권한 적용과 새 앱의 이력 조회 확인**. Vue 이력 화면·전체 Task MVP/Phase 1은 별도 후속이다.
### 2026-11-10 실제 로컬 상태 이력 SELECT 적용 및 새 앱 GET

#### 실행 범위

사용자가 요청한 TASK_STATUS_HISTORY_READ 로컬 적용과 실제 앱 이력 HTTP 확인이다. 격리 전체 검증에서 검증한 새 JAR(`backend/build/task-status-history-verification/artifact.json`의 SHA-256)과 기존 전용 wrapper를 재사용했다. `infra/.env`/`backend/.env`에서 대상만 확인했으며 비밀번호는 출력하거나 변경하지 않았다. Apps 8080/18081이 비어 있는지 검사하고 실제 DB read-only pre-snapshot이 직전 V7 기준과 같은지 확인했다.

#### 실행과 결과

```powershell
pwsh -NoProfile -File infra/provision-task-status-history-read.ps1
pwsh -NoProfile -File backend/build/task-status-history-verification/verify-local-history-http.ps1
```

첫 명령이 전용 JavaExec/관리자 runner로 TASK_STATUS_HISTORY_READ를 적용했다. AFTER ACL: `has_table SELECT=false`, audit column SELECT 7, 기존 INSERT 6, UPDATE 0, REFERENCES/sequence 접근은 없다. 새 컬럼 7개 SELECT는 `id`의 SELECT ACL 1건과 이전 여섯 INSERT ACL에 SELECT 비트를 추가했다. `flowdesk_app` 비밀번호·schema/table ACL·V7·업무/Activity 데이터·identity sequence·나머지 모든 컬럼 ACL은 보존했다.

두 번째 명령은 새 JAR을 쓰기 off, `dev-actor`/Carol, `127.0.0.1:18081`에서 실행하고 자체 PID를 종료했다. 12개 확인 모두 통과했다: Actuator UP/loopback·앱 계정 기동, HTTP 200 JSON, 정확한 envelope 8/item 6/actor 2 필드, Carol ID 3·현재 이름/이메일 없음, 기존 version 2 TODO→IN_PROGRESS 이력 한 건, PostgreSQL UTC `to_char(...US...)`와 같은 여섯자리 microsecond 문자열, `beforeVersion=2` 빈 마지막 페이지, `size=01` 400. 새 이력·DB row 변경은 만들지 않았다.

Apply 직후와 GET 후의 전체 snapshot은 동일하다. Apply 전 스냅샷에서 바뀐 것은 승인된 audit 7개 컬럼 ACL뿐이다. 5 user/3 workspace/6 membership/1 project/1 task/1 status activity, Task 1 IN_PROGRESS/HIGH/version 2·Project/Workspace version 1·Flyway V1~V7·sequence와 나머지 ACL은 기존 그대로다. 실제 데이터는 사용자 예제/개발 fixture이므로 새 인증을 의미하지 않는다. 현재 dev-actor 앱을 다음에 실행할 때도 status-history-read 정책 검사가 필요하다.

#### 검증기 수정과 보존

임시 HTTP verifier에서 `Invoke-WebRequest` body byte[]를 숫자 목록으로 문자열화한 문제가 있었다. UTF-8로 디코딩한 뒤 Actuator/API JSON을 읽도록 바꿨다. JSON 자동 날짜 변환을 거치지 않도록 응답 원문에서 시각을 뽑아 PostgreSQL UTC 문자열과 비교했다. 두 점검은 verifier만 수정했고 애플리케이션/JAR/DB 데이터는 바꾸지 않았다. apply script의 전체 스냅샷은 예상된 ACL 변경까지 포함하므로, 데이터·history·relations·sequences가 같은지와 일곱 차이 ACL을 각각 따로 증명했다.

영수증 `backend/build/task-status-history-verification/local-http-receipt.json`, Apply 출력 `local-grant.log`, 새 앱 로그(비밀정보 redacted) `local-app.log`, Apply 전/후/HTTP 후 스냅샷 `current-before-apply.json`, `current-after-apply.json`, `local-after-http.json`에 저장했다. protected-before.json의 52개 파일 해시는 전후 같고, 8080/18081은 비어 있으며 로컬 PostgreSQL은 healthy로 계속 실행 중이다. 기존 서비스/IDE 프로세스는 종료하지 않았다.

최종 결과: **전용 로컬 grant 성공, 실제 앱 조회 12/12 성공, 전후 데이터/마이그레이션/sequence 보존**. 다음 한 작업은 **Vue Task 상태 이력 조회 화면 연결**이다.

## 실제 로컬 V8·Task 담당자/마감일 권한 및 HTTP 확인 — 2026-11-11

### 실행 범위와 사전 상태

사용자가 실제 로컬 V8 적용, 최소 Task 컬럼 권한 전환, 새 앱 HTTP 확인을 승인했다. `flowdesk-local` Compose PostgreSQL은 healthy였고 8080/18081은 비어 있었다. 대상 `127.0.0.1:5432/flowdesk`와 앱 계정 파일은 전용 wrapper로 대조했다. 사전 읽기 스냅샷은 V1~V7, user/workspace/membership/Project/Task **5/3/6/1/1**, Task 1 `IN_PROGRESS/HIGH/version 2`, 상태 이력 1건을 확인했다. Task 1에는 담당자와 마감일이 없었다. V1~V7 체크섬은 현재 파일과 일치했다. 적용 전 스냅샷 및 자격증명 세 파일·V1~V8 SQL·새 JAR 등 12개 보호 파일 해시를 저장했다.

### 마이그레이션과 ACL

`infra/migrate-local.ps1 -Action Info`는 V8 PENDING을 확인했다. V8이 대기 중일 때 별도 `Validate`를 먼저 실행하면 Flyway 기본 검증이 pending versioned migration도 오류로 처리하므로 종료됐다. 이때 DB는 바뀌지 않았다. 이는 checksum 불일치가 아니었으며, Flyway가 기록한 V1~V7 checksum 모두 현재 스크립트와 일치했다. Flyway 문서는 pending 마이그레이션을 무시하는 validation 패턴을 별도로 설명한다([pending migration 검증 규칙](https://documentation.red-gate.com/fd/tutorial-custom-validation-rules-277579341.html)).

전용 migrator의 `Migrate`는 validate-on-migrate로 기존 이력을 확인하고 V8 1건을 적용했다. 적용 후 `Validate` 성공, 반복 `Migrate` 0건이다. V8 history는 `flowdesk_migrator` 설치·success=true이며 `assignee_id bigint NULL REFERENCES users(id) ON DELETE RESTRICT`, `due_date date NULL`을 확인했다. 기존 Task는 그대로였고 두 필드는 null이다.

`infra/provision-task-assignee-due-date.ps1`를 최초·반복 실행해 모두 `TASK_ASSIGNEE_DUE_DATE` 성공을 확인했다. 앱 권한은 Task SELECT 11개, INSERT 8개, UPDATE 8개 열로 한정됐다. Task table-wide SELECT/INSERT, DELETE/TRUNCATE/REFERENCES/TRIGGER, identity sequence 권한은 없다. 기존 Task status-activity SELECT 7개/INSERT 6개 권한은 유지됐다. 비밀번호·기존 migration SQL 및 패키징 JAR 해시는 변하지 않았다.

### 새 앱 HTTP와 보존

새 JAR을 Carol 개발 행위자·쓰기 활성화·`127.0.0.1:18081` loopback으로만 잠시 실행했다. Actuator UP과 Task 목록/상세의 nullable V8 필드를 확인한 뒤 Task 1에 현재 Workspace VIEWER Dave(ID 4)와 `2027-04-05`를 지정했다. PATCH 200/version 2→3, 후속 GET 반영을 확인하고 같은 API로 담당자/날짜를 null로 지워 version 4, GET 반영까지 확인했다. 총 10개 HTTP/권한 점검이 통과했다. 상태 이력은 추가되지 않았다.

검증 뒤 Task 1은 원래 제목/설명/우선순위/상태/부모/생성 시각과 null 담당자·마감일로 돌아왔고, 의도된 수정의 결과로 `updated_at`과 공유 version 2→4만 달라졌다. 다른 데이터 행, 활동 이력, ACL, 시퀀스는 HTTP 전후가 같다. 최종 데이터는 5/3/6/1/1과 상태 이력 1건이다. 임시 앱 프로세스는 종료했고 18081은 비었으며 8080/IDE 프로세스는 건드리지 않았다. PostgreSQL은 healthy로 계속 실행 중이다.

실행 영수증/스냅샷/비밀 값이 제거된 앱 로그는 Git 제외 `backend/build/local-task-assignee-due-date-verification/`에 저장했다. 그 안에 `receipt.json`, `after-schema.json`, `privileges-after.json`, `http-receipt.json`, `before-http.json`, `after-http.json`이 있다.

최종 결과: **V8 1건/Validate 성공/재실행 0건, 최소 권한 최초·반복 성공, 실제 새 앱 담당자·날짜 지정/해제 HTTP 10/10, 다른 로컬 데이터·권한 보존**. 다음 한 작업은 **Vue Task 생성·수정 폼에 담당자 선택과 마감일 입력을 연결**하는 것이다.

## Workspace 멤버 목록 SELECT 정책 — 구현/격리 검증, 로컬 미적용 (2026-11-12)

`GET /api/workspaces/{workspaceId}/members?page=0&size=20`를 추가했다. 현재 membership이 있는 네 역할은 ID·표시 이름·역할만 읽으며 이메일은 제외한다. 미소속/미존재는 동일 404다. 새 executor `infra/provision-workspace-member-list-read.ps1`는 기존 `users`, `workspaces`, `workspace_memberships` table-wide SELECT를 회수하고 현재 JPA 사용 컬럼 총 16개에 대한 SELECT만 명시적으로 부여한다. 앱 비밀번호는 기존 값을 인증에만 사용하며 변경하지 않는다. 초대·제거 DML은 포함되지 않는다.

격리 PostgreSQL에서 기존 권한 누적 상태→exact 16-column 전환, 재실행 idempotency, 불완전 column ACL 거부, 기존 Workspace API 호환 및 멤버 목록 endpoint를 확인했다. `WorkspaceReadApiTest`, `WorkspaceMemberListAccessPolicyTest`, `AppReadAccessPolicyTest` 합계 76개 통과; PowerShell 변경 파일 parse도 통과했다.

이번 로컬 적용 결과는 아래 2026-11-13 기록을 참조한다.

## Vue Task 담당자 선택 UI — 검증만 완료 (2026-11-13)

Task 생성·수정 폼에서 담당자 이름·역할·ID 목록을 선택하도록 연결했다. 멤버 목록을 열 때 API를 호출하고, 페이지 검증·오류 재시도·Task 저장시 서버 membership 재검증을 유지한다. 기존 할당된 사용자가 현재 멤버가 아니면 기존 값 유지/해제만 가능하다.

검증: Frontend 21개 파일/496개 테스트, `npm.cmd run build` type-check와 Vite build 통과. 격리 PostgreSQL의 `TaskUpdateApiTest` 40개 통과.

## Workspace 멤버 목록 — 실제 로컬 ACL·HTTP 검증 (2026-11-13)

요청한 로컬 적용은 저장소 루트에서 `pwsh -NoProfile -File infra/provision-workspace-member-list-read.ps1`로 수행했다. 전용 runner는 `flowdesk_app`의 기존 비밀번호로 인증하고 Flyway 이력과 기존 권한 상태를 검사한 뒤 users/workspaces/workspace_memberships의 table-wide SELECT를 제거하고 승인된 16개 mapped column SELECT를 적용했다. 성공 출력은 `Application workspace-member-list-read access: TASK_ASSIGNEE_DUE_DATE; existing password preserved.`였다. DB schema/data/migration은 변경하지 않았고 앱 비밀번호도 유지됐다.

최신 소스를 패키징하기 위해 JDK 21의 캐시된 Gradle 8.14.3으로 `bootJar --no-daemon --console=plain`을 실행했다. Gradle Wrapper는 배포판 다운로드가 네트워크 제한으로 거부됐지만, 캐시 Gradle 빌드는 성공했다. 새 JAR을 별도 dev-actor 앱으로 `127.0.0.1:18081`에 실행했다. `/actuator/health`는 200/UP, JPA/DataSource는 실제 PostgreSQL에 연결됐고, `/api/workspaces/1/members?page=0&size=20`은 200으로 멤버 4명과 OWNER/ADMIN/MEMBER/VIEWER 역할을 반환했다. JSON에는 ID·표시 이름·역할만 있고 이메일은 없었다.

검증기는 응답 byte[]를 JSON으로 읽지 못한 첫 시도 뒤 UTF-8 디코딩을 명시해 재실행했고, 최종 검증은 성공했다. 앱은 검증기가 시작한 프로세스만 종료했고 18081은 비었다. 8080의 기존 IDE 앱은 건드리지 않았다. 기록은 Git 제외 `backend/build/local-workspace-member-list-verification/receipt.json` 및 비밀을 치환한 `app.log`에 있다. 이번 단계에서 JUnit/Vue 테스트는 반복 실행하지 않았다. Workspace 멤버십 관리 범위는 이후 사용자 답변으로 확정했으며 [정책 계약](workspace-membership-management-v1.md)을 따른다. 다음 한 작업은 **멤버십 관리 API·초대 V9·최소 권한 도구를 구현하고 격리 PostgreSQL에서 검증**하는 것이다.

## 실제 로컬 V9·Workspace 멤버십 관리 ACL 및 HTTP 확인 — 2026-11-19

### 목표와 대상 확인

승인된 로컬 적용으로 전용 migrator가 V9를 적용하고, 앱 계정에 멤버십 관리 기능의 최소 권한을 추가했다. Compose의 기존 named volume은 유지했다. Windows TCP 예약 범위에 5432가 포함되어 Docker의 127.0.0.1:5432 게시가 거부됐다. 사용 가능한 15432로 `infra/.env`의 `POSTGRES_PORT`, `backend/.env`와 `infra/.env.migrator`의 JDBC URL을 일치시켰다. 세 자격증명은 변경하지 않았다.

Compose PostgreSQL은 `docker compose ... up -d --wait`로 healthy 상태가 됐고, 전용 `migrate-local.ps1 -Action Info`는 V1~V8 SUCCESS/V9 PENDING 및 target 일치를 확인했다.

### 마이그레이션과 최소 ACL

실행 명령:

```powershell
pwsh -NoProfile -File infra/migrate-local.ps1 -Action Migrate
pwsh -NoProfile -File infra/migrate-local.ps1 -Action Validate
pwsh -NoProfile -File infra/provision-workspace-membership-management.ps1
```

V9 migration 1건 적용, Flyway Validate 성공, `WORKSPACE_MEMBERSHIP_MANAGEMENT` 권한 최초 적용 성공을 확인했다. 새 JAR을 만든 뒤 관리 ACL 도구를 반복 실행해도 성공했다. 도구는 앱 기존 비밀번호를 보존했다. DB에서 membership DELETE 권한 1, membership UPDATE 컬럼 2, invitation SELECT/INSERT/UPDATE 컬럼 `10/8/2`, invitation table-wide DML 없음, invitation 행 0을 확인했다. 실제 새 앱 기동은 정확한 관리 권한 범위에 대한 startup policy 검사를 통과했다.

### 실제 새 앱 HTTP

JDK 21과 Gradle Wrapper 8.14.3으로 `backend`에서 `.\gradlew.bat bootJar --no-daemon --console=plain`을 실행해 `BUILD SUCCESSFUL`을 확인했다. 새 JAR은 `dev-actor`, 활성화된 로컬 쓰기, loopback `127.0.0.1:18081`에서만 실행했다. Alice/Bob/Eve 행위자는 각 요청 구간마다 서버 환경으로 지정했고 명시된 Vite Origin을 사용했다.

통과한 HTTP 동작:

- `/actuator/health`: 200/UP, DB 연결 포함.
- `/api/workspaces/1/members?page=0&size=20`: 200, 기존 네 멤버/네 역할.
- Alice가 Eve 초대: 201. Eve 행위자로 코드 수락: 201/MEMBER.
- Alice의 Eve 역할 변경: 200/VIEWER. Eve membership 제거: 204.
- 소유권 Alice→Bob: 200. Bob→Alice 복구: 200.

임시 초대가 만든 Eve membership은 제거했고 소유권은 Alice OWNER/Bob ADMIN으로 복구했다. 검증용 invitation 행은 관리자 연결로 해당 신규 행 하나만 삭제했다. 전후 seed 값은 users/workspaces/memberships `5/3/6`, invitation 행 `0`, Workspace 1의 Alice OWNER/Bob ADMIN으로 돌아왔다. identity sequence는 발급에 따라 1 증가했다. PostgreSQL sequence는 삭제/롤백으로 rewind하지 않았다. 검증 앱은 종료했고 18081은 다시 비었다.

### 미실행 범위

이 단계에서는 전체 Gradle/JUnit 회귀나 Vue 테스트를 다시 돌리지 않았다. 직전 구현 단계의 격리 PostgreSQL 153 checks 및 관련 221 API 회귀를 참조한다. 이번에는 실제 로컬 migration/ACL과 위 HTTP 흐름에 집중했다. 최초의 HTTP harness 두 번은 응답 형식/헤더 인수 오류로 데이터 요청 전에 실패했고, DB 행 수 0/기존 memberships 4를 확인한 후 올바른 curl 검증기로 재실행했다.

영수증과 redacted 앱 로그는 Git 제외 `backend/build/local-workspace-membership-management-verification/`에 있다. 이번 Windows 환경 설정은 Compose/JDBC의 host port 15432를 사용한다.

최종 결과: **V9 적용/Validate 성공, 관리 ACL 최초·반복 성공, 실제 HTTP 6개 주요 동작 성공, 원래 seed 멤버십·역할 복구**. 다음 한 작업은 **Vue 초대·멤버 관리 화면에 API 연결**이다.

### Chrome/Vite Workspace 초대·멤버 관리 검증 — 2026-11-19

Git 제외 임시 브라우저 검증기 `node frontend/.cache/workspace-membership-browser.mjs`로 기존 로컬 PostgreSQL과 새 백엔드 JAR을 사용해 실제 화면 흐름을 확인했다. Chrome에서 Alice가 멤버 목록을 열고 Eve 초대를 발급했고, Eve actor가 코드를 수락한 뒤 Alice가 Eve 역할을 VIEWER로 바꾸고 제거했다. API 응답은 발급 201, 수락 201, 역할 변경 200, 제거 204였다. 390px 화면에서 가로 overflow도 없었다.

첫 시도는 화면 갱신 직후 CDP 응답 이벤트를 확인한 검증기 race로 중단됐다. 초대·수락·역할 변경까지 실제로 실행됐으며 finally 정리 후 DB 행 상태를 확인했다. 응답 이벤트 대기를 보완해 재실행한 결과 전체 흐름이 통과했다. 두 시도에서 만든 초대와 Eve membership은 매번 정리했다. DB 데이터·Flyway·ACL·보호 파일은 시작 snapshot/hash와 일치하고 users/workspaces/memberships/invitations는 `5/3/6/0`이었다. 두 실행 시도에서 초대와 membership identity sequence는 각각 총 2 증가했다. sequence는 PostgreSQL이 행 정리 후에도 되돌리지 않으므로 이 증가는 정상이다.

검증 종료 후 Vite와 백엔드 포트 5173/8080은 비었고 PostgreSQL은 healthy 상태로 유지됐다. 이번 시도는 초대·수락·역할 변경·제거까지만 브라우저로 확인했다. 다음 기록에서 소유권 이전 UI를 별도로 확인한다.

### Chrome/Vite Workspace 소유권 이전 확인 — 2026-11-19

같은 임시 검증기를 확장해 Alice의 확인 창에서 Bob에게 소유권 이전, Bob actor로 재기동해 Alice에게 복구하는 전체 UI 경로를 실행했다. 두 POST 모두 `/api/workspaces/1/ownership-transfer` 200이었다. 각 화면에서 수신 대상과 확인 문구, 이전 OWNER가 ADMIN이 됐다는 메시지를 확인했고 DB에서도 이전 후 Alice ADMIN/Bob OWNER, 복구 후 Alice OWNER/Bob ADMIN을 확인했다.

첫 실행은 기능은 끝까지 동작했지만 전후 DB snapshot에서 역할 변경 시각 `updated_at`도 바뀐 것을 검출했다. 원래 역할은 이미 복구된 상태였으며 snapshot의 Alice/Bob 시각으로 복구했다. 검증기를 보완해 재실행할 때는 finally에서 seed membership의 역할·시각을 사전 snapshot으로 복원하도록 했고, 전체 업무 데이터·Flyway·ACL·보호 파일 snapshot 비교가 통과했다. 초대와 임시 Eve membership은 제거됐다. 이 소유권 확인 작업의 두 실행 시도에서 초대/member ID sequence는 각각 추가로 2 증가했다. 앞선 초대·멤버 브라우저 검증의 증가분을 포함하면 해당 브라우저 검증 시작 전 기준 대비 각 sequence는 총 4 증가했다.

최종 상태는 users/workspaces/memberships/invitations `5/3/6/0`, Alice OWNER/Bob ADMIN이며 원래 `updated_at`도 복구됐다. Vite/백엔드 포트 5173/8080은 비었고 PostgreSQL은 healthy 상태다.

### Chrome/Vite Workspace 초대 취소 확인 — 2026-11-19

Alice가 Eve에게 초대를 발급한 뒤 화면의 ‘이 초대 취소’를 눌렀다. UI에서 코드 영역이 제거되고 취소 완료 메시지가 보였으며 DELETE 응답은 204였다. Eve actor에서 발급 때 받은 같은 코드를 수락하려 하자 POST가 404로 거부됐고, 안내 문구가 나타났다. Alice 화면의 멤버 목록은 원래 네 명 그대로였다. 소유권 Alice→Bob→Alice 복구 확인과 390px 레이아웃도 함께 재검증했다.

임시 취소 invitation은 종료 시 제거했다. 초대 sequence는 이 실행에서 1 증가했고 membership sequence는 변하지 않았다. 전체 DB·Flyway·ACL·역할 timestamp 및 보호 파일 snapshot이 일치했다. 최종 counts는 `5/3/6/0`, Alice OWNER/Bob ADMIN이며 8080/5173은 비었다.

### Chrome/Vite Workspace 초대 재발급 확인 — 2026-11-19

Alice가 Eve에게 초대를 발급한 뒤 같은 대상에게 다시 발급했다. 두 발급 응답은 각각 201이며 화면에 새 코드가 표시됐다. Eve actor에서 이전 코드를 제출하자 `POST /api/workspace-invitations/accept`가 404로 거부됐고, 새 코드는 201로 수락돼 Workspace A의 멤버가 됐다. Alice가 임시 Eve membership을 UI에서 제거했고 응답은 204였다. 이어 소유권 Alice→Bob→Alice 복구와 390px 화면도 확인했다.

첫 실행은 UI 동작을 끝까지 확인했으나 cleanup 코드의 scope 오류로 snapshot 비교 전에 중단됐다. 그 실행의 두 초대 행과 Eve membership을 관리자 연결로 정리하고 기존 Alice/Bob 역할·시각을 복구했다. 수정한 cleanup을 포함한 재실행에서는 전체 DB/Flyway/ACL/보호 파일 snapshot 비교가 통과했다. 이 작업의 두 시도에서 invitation sequence는 총 4, membership sequence는 총 2 증가했다. PostgreSQL sequence는 정리 후에도 되감지 않는다. 최종 counts `5/3/6/0`, Alice OWNER/Bob ADMIN 및 timestamp는 기준 상태와 일치하며, Vite/백엔드 포트 5173/8080은 비었다.

다음 한 작업은 **수락한 초대 코드를 다시 사용하면 거부되는지 확인**하는 것이다.

### Chrome/Vite Workspace 초대 1회 사용 확인 — 2026-11-19

Eve actor가 Alice의 초대 코드를 처음 제출하자 수락 응답 201과 Workspace 선택을 확인했다. 화면에 코드를 다시 입력해 두 번째로 제출하자 404와 만료·취소·사용된 초대 안내가 나타났다. DB의 Workspace 1 Eve membership은 정확히 1건이었고, Alice가 해당 임시 멤버십을 화면에서 제거해 204를 확인했다. 소유권 Alice→Bob→Alice 복구와 390px 레이아웃도 다시 통과했다.

검증 초대와 Eve membership 행은 정리됐고, 업무 데이터·Flyway·ACL·Alice/Bob 역할 timestamp snapshot이 통과했다. 이 실행의 invitation sequence는 1 증가했고 membership sequence는 변하지 않았다. 행 삭제로 sequence가 되돌아가지는 않는다. 최종 데이터 수는 `5/3/6/0`, Alice OWNER/Bob ADMIN이고 Vite/백엔드 포트 5173/8080은 비었다.

### Chrome/Vite Workspace 초대 만료 확인 — 2026-11-19

Alice가 발급한 Eve 대상 초대의 테스트 행에 한해 DB 관리자 연결로 `created_at`을 8일 전, `expires_at`을 1초 전으로 설정했다. 두 시각 모두 원래 행과 `expires_at > created_at` 제약을 유지했다. Eve actor가 화면에서 코드를 수락하려 했을 때 API는 404, 화면은 만료·취소·사용된 초대 안내를 표시했다. Eve membership은 생성되지 않았고 Alice의 멤버 목록도 기존 네 명 그대로였다. Alice→Bob→Alice 소유권 복구와 390px 화면도 재확인했다.

임시 invitation을 제거한 뒤 전체 DB·Flyway·ACL·역할 timestamp 및 보호 파일 snapshot 비교가 통과했다. invitation sequence는 1 증가했고 membership sequence는 변하지 않았다. 최종 counts는 `5/3/6/0`, Alice OWNER/Bob ADMIN이며 5173/8080 포트는 비었다.

### Workspace 초대 대기 목록 실제 HTTP 확인 — 2026-11-20

기존 `flowdesk-local` PostgreSQL을 데이터 볼륨 초기화 없이 기동해 healthy를 확인했다. 2026-11-20 21:53에 만들어져 목록 API class를 포함한 기존 백엔드 JAR을 loopback 8080에서 Alice `dev-actor`, 명시적 Workspace 쓰기 활성화와 Vite Origin 허용으로 실행했고 Vue 개발 서버는 loopback 5173에서 실행했다. Wrapper 재빌드는 Gradle 배포본 네트워크 다운로드 제한으로 실패했고, 캐시 Gradle 직접 실행도 Spring Boot 플러그인 해석에 실패해 이번 검증에서는 기존 JAR을 재사용했다. 실제 Vite `/api` 프록시 요청으로 Workspace 1 초대 목록 GET 200/빈 목록, Eve 초대 POST 201, 목록 GET 200/대상 표시 이름·이메일·역할 확인, 초대 DELETE 204, 후속 GET 200/빈 목록을 확인했다. 목록 응답에는 코드 원문/digest가 없었다. 임시 초대는 `REVOKED` 상태를 확인한 뒤 승인 범위의 해당 테스트 행만 관리자 연결로 삭제했다. 정리 스크립트는 `psql` DELETE 출력 형식 비교에서 nonzero로 끝났지만, 직후 조회에서 해당 invitation이 0건임을 확인했다. 사용자/Workspace/membership/Project/Task/activity counts는 `5/3/6/1/1/1`, invitation은 0건이며 Workspace 1 역할은 Alice OWNER/Bob ADMIN이다. invitation identity sequence는 1 증가했다.

대화형 Chrome 자동 확인은 DevTools WebSocket이 1006으로 닫히고 headless `--dump-dom`도 시간 초과돼 브라우저 DOM/시각 검증은 완료하지 못했다. 이번 결과는 실제 백엔드와 Vite 프록시 HTTP 확인이다. 검증 백엔드/Vite 포트는 종료 후 비었고 PostgreSQL은 healthy로 유지했다. 새 migration, ACL 적용, 기존 업무 데이터 수정은 수행하지 않았다.


### Chrome/Vite Workspace 초대 대기 목록 확인 — 2026-11-23

Chrome에서 Workspace 목록·상세·멤버 관리 화면을 열고, Vite middleware로 제공한 메모리 fixture의 만료된 Eve 초대 행/대상 정보/취소 버튼을 확인했다. 사용자가 초대 취소 버튼을 클릭한 뒤 /api/workspaces/1/invitations fixture 응답이 HTTP 200과 items: []를 반환했다. Vue 멤버/Workspace 조회는 dev-actor 백엔드로 전달했으며, invitation GET/DELETE만 메모리에서 응답했다. 따라서 이 검증에서 DB 데이터는 변경되지 않았다. 임시 백엔드/Vite를 종료했고 18080/15173 포트는 응답하지 않음을 확인했다. 실제 backend/Vite proxy의 초대 생성·취소 HTTP 검증은 2026-11-20 기록과 구분한다.

### Project V10·보관 권한·HTTP 확인 — 2026-11-23

기존 `flowdesk-local` PostgreSQL을 유지했다. `migrate-local.ps1 -Action Info`에서 V1–V9 SUCCESS/V10 PENDING을 확인했고, 독립 Validate는 미적용 V10이 있어 실패했다. V1–V9의 저장 checksum과 resolved checksum은 모두 일치했다. 이를 mismatch로 오판하지 않고 전용 migrator의 `Migrate`를 실행해 V10 1건을 적용했다. 후속 Validate 성공, V1–V10 SUCCESS, 재실행 `Migrations executed: 0`을 확인했다.

`provision-project-archive.ps1`은 `flowdesk_app`의 기존 권한 상태를 보존하면서 `projects.archived_at` column UPDATE를 추가했다. DB 조회로 `has_column_privilege(... archived_at, UPDATE)=true`, `has_table_privilege(... projects, UPDATE)=false`를 확인했다. DB에는 Flyway 이력 10건, Project 1행, Task 1행, Task activity 1행이 남아 있다.

새 Gradle `bootRun` 앱을 `127.0.0.1:18080`에서 `dev-actor` Alice, Workspace write on, `flowdesk.dev.project-archive-enabled=true`로 실행했다. 앱의 datasource/JPA/Actuator 기동은 성공했고 `/actuator/health`는 `{"status":"UP"}`을 반환했다. Project 1을 보관해 version 1→2, 기본 목록에서 숨김, `includeArchived=true` 목록에는 표시, Project 상세·Task 상세·상태 이력 GET 200, Task 생성/수정/상태 PATCH는 각각 409 `PROJECT_ARCHIVED`를 확인했다. 그 뒤 복구해 version 3, 기본 목록 재등장을 확인했다. Task/activity와 다른 업무 행은 바뀌지 않았다. 보관·복구 자체가 `updated_at`과 Project version을 변경하므로 version은 원래 값으로 되돌리지 않았다.

검증 앱을 종료하고 18080 listener가 없음을 확인했다. 임시 HTTP 검증 스크립트는 제거했다. 기본 앱 설정과 Workspace write 설정은 변경하지 않았고, Project archive gate는 해당 임시 실행의 명령 인수에만 적용했다.

### Vue Project 보관·복구 브라우저 확인 — 2026-11-23

기존 IDE backend(8080)와 Vite(5173)는 보존했다. UI 검증용 별도 backend(18080)와 임시 Vite 설정(5174→18080), 분리된 headless Chrome profile을 사용했다. Alice가 Workspace 1의 Project 1을 실제 화면에서 선택해 보관 확인 문구와 보관 처리를 실행했다. 기본 Project 목록에서 행이 숨겨졌고, `보관된 프로젝트 포함`을 선택하면 `보관됨` 배지가 나타났다.

보관 상세 화면에서 상태 안내를 확인했고, Task를 열어 생성·수정·상태 변경 폼이 모두 숨겨졌음을 확인했다. 상태 변경 이력 버튼은 동작했고 기존 이력을 화면에 표시했다. 복구 확인 후 archived 안내/배지가 사라지고 Project가 기본 목록에 돌아왔으며 Task 생성 폼도 다시 나타났다. 화면·HTTP API 둘 다 Project를 active 상태로 남겼다.

브라우저 상호작용 검증 과정의 반복 archive/restore로 Project 1은 version 9, `archived_at IS NULL` 상태다. Project/Task/activity 행 수는 각각 `1/1/1`; 해당 데이터 외 변경은 없다. `flowdesk_app`은 archived_at column UPDATE 가능, Project table UPDATE 불가 상태다. 임시 backend/Vite/Chrome 프로세스와 config/log/screenshot/profile 파일을 제거했고, 기존 8080/5173만 계속 실행 중임을 확인했다.

2026-11-23 Project 보관·복구 API, V10 migration, `provision-project-archive.ps1`, 격리 PostgreSQL 검증을 구현했다. `V10MigrationTest`, `ProjectArchiveAccessPolicyTest`, `ProjectArchiveApiTest` 4개, Project API 43개, Project update 31개가 통과했다(80 tests). `infra/verify-migration.ps1 -IncludeProjectArchive`는 고유 Compose 프로젝트에서 91 checks를 통과했고 정리됐다. Vue 24개 파일의 507 tests와 type-check/Vite production build도 통과했다. 이 변경에서는 `infra/.env`의 로컬 DB migration이나 app GRANT를 실행하지 않았다. archive API를 쓰려면 로컬 V10과 전용 archive UPDATE 권한을 별도로 적용하고 실행 시 `flowdesk.dev.project-archive-enabled=true`를 명시해야 한다. 다음 작업은 Vue 보관/복구 화면 연결이다.
