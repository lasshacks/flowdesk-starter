# FlowDesk 로컬 PostgreSQL

## 워크스페이스 생성 권한 — 구현·격리 검증 및 로컬 적용 완료

provision-workspace-create.ps1은 READ_ONLY 상태에 두 테이블(workspaces/workspace_memberships) INSERT만 추가하는 명시적 관리자 작업이다. 기존 앱 비밀번호·V1/소유권/ACL을 확인하고 잠금·트랜잭션으로 GRANT한다. 비밀번호 회전·데이터 변경·sequence 권한은 없다. 앱 부팅과 분리한다. 2026-11-03 실제 로컬 최초/반복 적용과 첫 HTTP 생성(ID 3)을 확인했으며 현재 권한은 WORKSPACE_CREATE다.

공통 AppReadPolicy는 CONNECT_ONLY/READ_ONLY/WORKSPACE_CREATE만 허용한다. 읽기 작업 재실행은 승인된 INSERT를 보존한다. 부분/과도한 권한은 자동 복구하지 않는다. 실제 업무 데이터 추가 뒤 seed Verify/Apply가 예제와 다름으로 중단하는 기존 정책은 유지한다.

루트의 ./infra/verify-migration.ps1 -IncludeWorkspaceCreate는 seed/read 검증도 포함한다. UUID 격리 Compose 프로젝트의 실제 PowerShell/Gradle/Java 경로 52개가 통과했다. 신규 정책/실행기는 앱 JAR에 포함하지 않는다. [계약·후속 적용](../docs/workspace-create-api-v1.md).

Docker Desktop Linux 엔진과 Docker Compose v2, PowerShell 7을 사용한다. PostgreSQL 기동·앱 최소 권한 설정과 Spring DataSource 연결을 검증했다. 별도 실행기로 로컬 V1·개발 seed 적용과 Verify까지 완료했으며 JPA는 후속이다.

## 최초 실행

```powershell
cd D:\flowdesk-starter\infra
.\init-local-env.ps1
docker compose config --quiet
docker compose up -d --wait --wait-timeout 120
docker compose ps
```

- init-local-env.ps1은 32바이트 난수에서 비밀번호를 생성해 로컬 `.env`에 저장한다. 비밀번호는 출력하지 않으며 기존 파일이 있으면 변경하지 않는다. 값의 이름과 형식만 `.env.example`에 기록한다.
- Compose는 이 디렉터리의 `.env`를 읽어 YAML의 `${변수}`를 치환하고 environment 항목을 컨테이너 프로세스에 전달한다. `.env`는 시스템 환경변수가 아니며 Windows의 JAVA_HOME을 수정하지 않는다.
- `config --quiet`는 문법과 변수 해석 오류를 검사하면서 해석된 비밀번호를 출력하지 않는다. 전체 `docker compose config`나 전체 컨테이너 inspect 출력에는 비밀번호가 포함될 수 있으므로 공유하지 않는다.
- 5432가 사용 중이면 기존 DB를 중지하지 말고 `.env`의 POSTGRES_PORT를 비어 있는 포트로 바꾼다.

## 접속 정보

| 항목 | 기본값 |
|---|---|
| 이미지 | postgres:17.11-bookworm |
| 주소 / 포트 | 127.0.0.1:5432 |
| DB | flowdesk |
| 초기 관리자 | postgres |
| 비밀번호 | 로컬 `.env`의 POSTGRES_PASSWORD |
| Compose 프로젝트 | flowdesk-local |
| 서비스 | postgres |
| 데이터 볼륨 | flowdesk-local_postgres_data |
| 컨테이너 데이터 경로 | /var/lib/postgresql/data |

이 postgres 계정은 로컬 DB 초기화와 검증용 관리자다. 애플리케이션은 아래의 flowdesk_app 계정을 사용한다. 호스트 GUI DB 도구는 검증하지 않았지만 Spring JDBC 연결은 검증했다.

## 애플리케이션 계정 준비

Flyway migrator는 아래 앱 계정과 분리한다. 앱 부팅 시 자동 적용하지 않고 전용 작업으로 실행하며 migrator 파일은 Git 제외 infra/.env.migrator를 사용한다. provision-migrator.ps1·migrate-local.ps1과 격리 DB 검증용 verify-migration.ps1을 구현했다. 2026-11-03 실제 로컬 계정·스키마 준비와 V1 적용, Validate 및 재실행 0건을 확인했다. [로컬 마이그레이션 절차](../docs/local-migration.md)를 참고한다.

DB를 기동한 뒤 PowerShell에서 다음을 실행한다.

```powershell
.\provision-app-db.ps1
```

- backend/.env에 별도의 무작위 앱 비밀번호를 생성한다. 기존 파일이 같은 로컬 설정이면 그대로 사용하며, 예상과 다른 값이면 덮어쓰지 않고 종료한다.
- 비밀번호는 psql 표준 입력과 해당 세션의 설정으로 전달하고 명령행 인수·SQL 파일·출력에 실제 값을 넣지 않는다. infra/.env의 관리자 비밀번호는 앱에 전달하지 않는다.
- app-role.sql은 트랜잭션에서 flowdesk_app을 생성·설정한다. FlowDesk DB의 PUBLIC 기본 권한과 앱 직접 권한을 회수한 뒤 CONNECT만 부여한다. public 스키마의 PUBLIC·앱 권한도 회수한다.
- 앱은 관리자·DB 생성·역할 생성·복제·RLS 우회 권한이 없고, FlowDesk DB에서 스키마·테이블·임시 테이블을 생성할 수 없다. 테이블 CRUD·스키마 USAGE 등은 해당 기능을 만들 때 추가한다. SELECT 1에는 테이블 권한이 필요 없다.
- 이 정책은 현재 FlowDesk DB 안의 권한을 제한한다. 클러스터의 다른 DB에 대한 PUBLIC 연결 권한이나 pg_hba 접속 대상 제한은 변경하지 않는다. 기존 다른 프로젝트 DB는 건드리지 않는다.
- 재실행 시 저장된 동일 비밀번호를 사용해 권한을 다시 적용한다. 비밀번호 분실 시 새 파일을 덮어써 복구하는 도구가 아니다. 현재는 새 프로젝트의 앱 역할에만 사용하며 이후 별도 권한을 부여한 뒤에는 정책을 함께 갱신해야 한다.
- 앱 계정은 DDL을 할 수 없으므로 향후 Flyway에는 별도 계정을 마련한다. 이번에 그 계정이나 업무 스키마는 만들지 않았다.

## 개발 seed와 격리 검증

seed-local.ps1의 필수 Action은 Apply/Verify다. 기존 .env.migrator만 전용 자식 환경으로 전달하며 관리자·앱 자격증명을 넣지 않는다. 사용자 5명/공간 2개/멤버십 5개의 [확정 예제와 실행 정책](../docs/dev-seed.md)을 따른다. 격리 검증 후 사용자 요청으로 실제 로컬 Apply·Verify와 반복 0건을 확인했다.

저장소 루트에서 다음 명령은 고유 Compose 프로젝트·임시 포트로 기존 migration 회귀와 seed 실행기의 실제 경로를 검증한 뒤 검증용 DB 볼륨을 정리한다. 실제 flowdesk-local 데이터는 변경하지 않는다.

```powershell
pwsh -NoProfile -NonInteractive -File infra/verify-migration.ps1 -IncludeSeed
```

## 앱 읽기 권한 도구

provision-app-read.ps1은 기존 backend/.env와 infra/.env의 대상/자격증명을 검증한 뒤 전용 Java 작업에서 앱 인증·V1 validate·권한 사전 검사 후 schema USAGE와 세 업무 테이블 SELECT만 부여한다. 비밀번호를 변경하지 않는다. 사용자 요청으로 실제 로컬 최초/반복 실행과 새 JPA 앱 기동·Repository 조회를 확인했다. 현재 로컬 flowdesk_app은 READ-only이며 DML/DDL/sequence/history는 금지된다.

임의 REVOKE/ALL/DEFAULT PRIVILEGES를 사용해 예상 밖 상태를 덮어 맞추지 않는다. 직접/컬럼/default ACL·PUBLIC·역할 멤버십·GRANT OPTION 등도 검사하며 migration/seed 도구와 동일한 CONNECT-only/READ-only 정책을 사용한다. [자세한 권한 범위](../docs/jpa-read-model-v1.md).

```powershell
# 루트: 고유 Compose 프로젝트에서 migration/seed/읽기 권한 전체 경로 검증
pwsh -NoProfile -NonInteractive -File infra/verify-migration.ps1 -IncludeReadAccess
```

최종 41개 검증 통과 후 검증용 볼륨/자격증명 파일을 정리했다. 이 명령은 실제 flowdesk-local 계정에 GRANT하지 않는다.

## 상태 및 실제 SQL 검증

```powershell
docker compose exec -T postgres pg_isready -U postgres -d flowdesk
docker compose exec -T postgres sh -c 'PGPASSWORD="$POSTGRES_PASSWORD" psql -h postgres -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v ON_ERROR_STOP=1 -c "SELECT current_database() AS database, current_user AS username, 1 AS connection_ok; SHOW server_version;"'
```

두 번째 명령은 컨테이너 환경에 있는 비밀번호를 psql에 전달한다. 실제 비밀번호 값을 명령에 직접 적거나 출력하지 않는다. `-h postgres`로 Compose 네트워크 이름을 사용해 TCP 비밀번호 인증을 거친다. 호스트 OS의 127.0.0.1을 통과하는 SQL 경로와는 구분해야 한다.

pg_isready의 성공은 서버가 연결을 받아들인다는 뜻으로, 올바른 비밀번호와 SQL 실행을 증명하지 않는다. [PostgreSQL 공식 설명](https://www.postgresql.org/docs/17/app-pg-isready.html).

대화형 SQL이 필요하면 다음을 사용할 수 있다. 이 방법은 컨테이너 내부 Unix 소켓 연결이므로 비밀번호 인증 검증에는 위 TCP 명령을 사용한다.

```powershell
docker compose exec postgres psql -U postgres -d flowdesk
```

종료는 psql에서 `\q`다.

## 중지와 재실행

```powershell
docker compose stop
docker compose up -d --wait --wait-timeout 120
```

컨테이너를 제거하되 명명 볼륨을 유지하려면 `docker compose down`을 사용한다. 볼륨을 제거하는 `down --volumes`는 저장 데이터를 삭제하므로 이번 작업에서 실행하지 않는다.

초기 DB·사용자·비밀번호 환경변수는 데이터 디렉터리가 비어 있는 최초 초기화 때 적용된다. 이후 `.env`의 비밀번호만 바꾸면 기존 DB 비밀번호는 바뀌지 않는다. 인증 실패를 해결하려고 볼륨을 삭제하지 말고, 기존 설정과 계정을 먼저 확인한다. 이미지 메이저 버전 변경도 데이터 업그레이드 작업과 별도로 검토한다. [공식 이미지 안내](https://hub.docker.com/_/postgres).

## 실제 검증 결과 — 2026-11-02

- `.\init-local-env.ps1`: 로컬 파일 생성. 재실행 시 기존 값 유지 확인.
- `docker compose config --quiet`: 성공. `up -d --wait --wait-timeout 120`: 성공.
- 컨테이너 `healthy`, PostgreSQL 17.11, DB flowdesk, 사용자 postgres, connection_ok=1 확인.
- 잘못된 비밀번호로 같은 TCP 경로 접속 시 `password authentication failed`로 거부됨 확인. 이때의 오류 출력은 의도한 음성 검증 결과다.
- Windows TcpClient로 127.0.0.1:5432 접속 성공. Docker 포트 매핑의 HostIp가 127.0.0.1임을 확인했다.
- 명명 볼륨의 데이터 경로 연결을 확인했다. 컨테이너 재생성 후 레코드 유지 실험은 하지 않았다.
- 가져온 이미지 digest: `postgres@sha256:839fbb5a4942a7758dd93e18b42ea5649807491a0545b5eeeb8f0b8bb11857a5`.
- 기존 타 프로젝트 컨테이너는 변경하지 않았다. FlowDesk PostgreSQL은 실행 중으로 남겨 다음 연결 단계에 사용한다.
- 애플리케이션 코드는 변경하지 않아 Gradle·Vue 테스트는 이번에 반복하지 않았다.


## 이름 수정용 최소 UPDATE — 2026-11-04

`provision-workspace-rename.ps1`은 기존 자격증명/대상을 검증한 뒤 전용 Gradle `runAppWorkspaceRenameAccess`로 WORKSPACE_CREATE → WORKSPACE_RENAME 전환을 수행한다. V2까지 적용된 이력과 정확한 선행 권한을 요구하며 workspaces의 name/updated_at/version 세 컬럼 UPDATE만 부여한다. 재실행과 기존 읽기/생성 도구는 새 상태를 보존하고 부분/과도한 ACL은 자동 복구하지 않는다.

루트에서 `./infra/verify-migration.ps1 -IncludeWorkspaceRename`은 고유 Compose DB에서 migration/seed/read/create/rename 전체 실행 경로를 검사한다. 기존 로컬 DB를 사용하지 않는다. 실제 로컬 V2/권한 적용은 아직 미실행이다. [수정 계약](../docs/workspace-rename-api-v1.md)을 따른다.


2026-11-04 후속 실제 로컬 적용도 완료했다. V2 1건/Validate·재실행 0건, 최소 컬럼 UPDATE 최초/반복 적용과 새 앱의 이름 수정·충돌을 확인했다. 데이터 5/3/6과 기존 자격증명/V1을 보존했다. 현재 상태/명령은 [실행 기록](../docs/local-setup.md), API 계약은 [이름 수정](../docs/workspace-rename-api-v1.md)을 따른다. 앱의 쓰기 활성화는 여전히 명시적 개발 설정이다.


## Project 최소 권한 도구

V3 적용 후 `./infra/provision-project-create.ps1`를 수동 실행한다. 정확한 WORKSPACE_RENAME에서 projects SELECT/INSERT만 추가하고 PROJECT_CREATE 반복 실행은 권한/비밀번호를 보존한다. 부분·과도한 ACL은 자동 수선하지 않는다. 관리자 값은 기존 무시 파일/분리 자식 환경으로만 전달한다. 앱 부팅은 migration/GRANT를 실행하지 않는다.

`./infra/verify-migration.ps1 -IncludeProjectCreate`는 고유 Compose 프로젝트에서 migration/seed/read/create/rename/Project 실행기를 검증하고 자기 볼륨·임시 자격증명을 정리한다. 이후 사용자 요청으로 실제 로컬 V3/PROJECT_CREATE 적용·반복과 새 앱의 첫 Project ID 1 생성/조회를 완료했다. 기존 5/3/6을 유지하고 Project 한 행만 추가했으며 검증 서버는 종료했다. [계약/적용 순서](../docs/project-model-and-api-v1.md).


## Project 최소 UPDATE 도구 — 2026-11-05

V4 이후 `./infra/provision-project-update.ps1`는 기존 앱 인증·이력/권한을 검사하고 PROJECT_CREATE→PROJECT_UPDATE로 전환한다. projects의 name/description/updated_at/version 네 컬럼 UPDATE만 추가하며 반복은 권한/기존 비밀번호를 보존한다. id/workspace_id/created_at·테이블 전체 UPDATE·sequence/history·DDL 권한은 허용하지 않는다. 부분/과도/PUBLIC/GRANT OPTION은 자동 수선하지 않는다.

새 실행기의 현재 이력은 1/2/3/4이며 이전 도구도 정확한 새 권한 상태를 보존한다. 실제 로컬 V4/권한 적용은 별도 요청이며 새 앱 기동 전에 필요하다. `./infra/verify-migration.ps1 -IncludeProjectUpdate`는 고유 격리 DB에서 기존 전체 경로와 새 컬럼 정책을 확인하고 정리한다. [계약/순서](../docs/project-update-api-v1.md), [실제 결과](../docs/local-setup.md).


### 실제 로컬 V4/PROJECT_UPDATE — 2026-11-07

전용 migrator로 V4 1건/Validate/재실행 0건, Project UPDATE 도구 최초/반복 적용을 완료했다. 기존 .env 세 파일과 V1~V3/앱 JAR는 유지했고 네 컬럼 UPDATE만 추가됐다. 기존 프로젝트 한 행의 HTTP 수정만 확인했으며 seed/새 Project 생성은 실행하지 않았다. 로컬 데이터는 5/3/6/1, Project 1 version 1이다. 검증 앱은 종료했고 PostgreSQL은 healthy다. [실행 기록](../docs/local-setup.md).


## Task SELECT/INSERT 실행기 — 2026-11-07

V5 이후 `./infra/provision-task-create.ps1`는 기존 앱 인증/이력/권한을 확인한 뒤 PROJECT_UPDATE→TASK_CREATE로 tasks SELECT/INSERT만 추가한다. 반복 실행과 기존 read/create/rename/Project-create/Project-update는 완전한 TASK_CREATE를 보존한다. Task UPDATE/DELETE/TRUNCATE·컬럼 UPDATE·시퀀스/이력/DDL과 부분/과도/PUBLIC/grant option은 허용하지 않는다. 비밀번호를 교체하거나 이상 상태를 자동 복구하지 않는다.

`./infra/verify-migration.ps1 -IncludeTaskCreate`는 고유 격리 DB에서 V1~V5/seed/과거 모든 도구/새 Task 경로를 실행하고 임시 자원·자격증명을 정리한다. 현재 실제 로컬은 V4/PROJECT_UPDATE로 유지하며 V5/권한 실제 적용은 별도 요청 후 진행한다. [Task 계약](../docs/task-model-and-api-v1.md), [실제 결과](../docs/local-setup.md).


### 실제 로컬 V5/TASK_CREATE 적용 — 2026-11-08

V5 1건/Validate/재실행 0건과 Task SELECT/INSERT 최초·반복 적용을 완료했다. 기존 자격증명/V1~V5/JAR는 유지하고 기존 관계/컬럼 ACL·시퀀스도 보존했다. 첫 Task 한 행만 HTTP로 생성했으며 데이터는 5/3/6/1/1, 기존 Project version 1이다. 앱 Task UPDATE/DELETE/TRUNCATE/컬럼 UPDATE·시퀀스·이력/DDL 접근은 허용하지 않는다. 검증 앱은 종료했고 PostgreSQL은 healthy다. [실제 명령/결과](../docs/local-setup.md).


## Task 최소 UPDATE — 2026-11-08

`provision-task-update.ps1`은 기존 앱 자격증명과 현재 V1~V6 이력을 검증하고 TASK_CREATE→TASK_UPDATE 또는 반복 보존만 수행한다. tasks의 title/description/priority/updated_at/version UPDATE만 추가하며 status/부모/생성 시각·전체 UPDATE/DELETE/TRUNCATE·시퀀스/history/DDL은 금지한다. 기존 모든 도구는 완전한 TASK_UPDATE를 보존하고 부분/과도/PUBLIC/GRANT OPTION은 자동 수선하지 않는다.

격리 전체 실행은 `./infra/verify-migration.ps1 -IncludeTaskUpdate`다. 실제 로컬 V6/권한 적용은 구현·격리 검증 뒤 별도 요청으로 진행한다. [Task 수정 계약](../docs/task-update-api-v1.md), [실제 결과](../docs/local-setup.md).

`verify-migration.ps1 -IncludeTaskUpdate`의 최종 결과는 **105개 검사 통과**다. 실제 새 도구/반복/기존 도구 보존·다섯 컬럼 허용/금지·부분/과도 상태 미수선·자격증명/부모 환경 보존과 정리를 확인했다. 실제 로컬 V5에는 V6/Task UPDATE를 아직 적용하지 않았다.


## 실제 로컬 Task UPDATE 적용 완료 — 2026-11-08

V6 1건/Validate·반복 0건과 Task UPDATE 최초·반복을 실행했다. 정확한 다섯 컬럼만 추가했고 비밀번호/이전 ACL·소유권/시퀀스를 보존했다. 새 앱의 실제 Task 수정도 확인했으며 데이터는 5/3/6/1/1이다. [명령·결과/검증기 수정](../docs/local-setup.md)을 따른다. 이제 로컬은 V6/TASK_UPDATE다.


## 후속 상태 변경 권한 설계 — 2026-11-08

[상태 계약](../docs/task-status-api-v1.md)의 예정 TASK_STATUS_CHANGE는 기존 TASK_UPDATE + tasks UPDATE(status) + task_status_activities INSERT(task_id,actor_user_id,from_status,to_status,task_version,occurred_at)다. 이력은 SELECT/전체 INSERT/id INSERT·수정/삭제·직접 sequence/DDL을 허용하지 않는다. V7·전용 도구/기존 상태 보존·기동/이력/seed 검사는 후속 구현이며 이번에는 SQL/도구·실제 로컬을 바꾸지 않았다.

### Task 상태 변경 최소 권한 — 2026-11-10

전용 `provision-task-status-change.ps1` → `run-app-task-status-change-gradle.ps1` → `runAppTaskStatusChangeAccess` → `AppTaskStatusChangeAccessRunner`를 추가했다. 정확한 TASK_UPDATE에서 tasks의 status UPDATE와 task_status_activities의 입력 여섯 컬럼 INSERT만 원자적으로 부여한다. 비밀번호는 인증 후 유지하며 부분/과도/PUBLIC/GRANT OPTION은 자동 수선하지 않는다. 완전한 TASK_STATUS_CHANGE는 이전 도구에서도 보존한다. 이력 SELECT/전체 INSERT/id/sequence·DDL 권한은 주지 않는다.

저장소 루트의 `pwsh -NoProfile -File infra/verify-migration.ps1 -IncludeTaskStatusChange`는 고유 Compose 프로젝트에서 V1~V7·seed·이전 권한과 새 PowerShell 실행기를 검증하고 해당 자원/임시 자격증명을 정리한다. 실제 로컬 적용은 아직 하지 않았다. 다음 단계의 실제 적용 명령은 [구현 기록](../docs/task-status-implementation.md)을 따른다.

### 실제 로컬 V7·상태 최소 권한 적용 완료 — 2026-11-10

전용 migrator의 V7 1건/Validate/반복 0건과 상태 권한 도구 최초/반복을 완료했다. 기존 TASK_UPDATE에 status UPDATE와 이력 입력 여섯 컬럼 INSERT만 추가했고 이력 SELECT/전체 INSERT/id INSERT/UPDATE/DELETE/TRUNCATE·시퀀스/DDL 권한은 없다. 실제 HTTP 상태 변경으로 이력 1건이 생겼고 기존 데이터/ACL·이전 시퀀스와 보호 파일 11개는 보존했다. PostgreSQL은 실행 중이고 검증용 앱은 종료했다. [실행 기록](../docs/local-setup.md).

### 상태 이력 조회 최소 SELECT 설계 확정 — 2026-11-10

후속 TASK_STATUS_HISTORY_READ는 기존 TASK_STATUS_CHANGE에 task_status_activities의 고정 7개 컬럼 SELECT만 추가한다. 기존 INSERT/상태 UPDATE를 보존하고 table-wide SELECT ACL·부분/과도/PUBLIC/GRANT OPTION은 수선 없이 거부하는 별도 도구와 이전 도구/기동 검사 전환을 설계했다. 현재 컬럼이 7개뿐이어서 SELECT * 문장은 성공할 수 있으며, 목적은 미래 컬럼의 자동 허용을 막는 것이다. V7/데이터/시퀀스는 유지하고 지금 실제 GRANT를 실행하지 않는다. [확정 계약](../docs/task-status-history-read-api-v1.md).

### 상태 이력 SELECT 실행기 구현 — 2026-11-10

`pwsh -NoProfile -File infra/provision-task-status-history-read.ps1` → `run-app-task-status-history-read-gradle.ps1` → `runAppTaskStatusHistoryReadAccess`가 기존 TASK_STATUS_CHANGE에서 audit 7개 컬럼 SELECT만 추가한다. 최초/반복·이전 모든 도구 보존을 지원하며 부분/과도/PUBLIC/GRANT OPTION은 수선하지 않는다. 앱 비밀번호/업무 데이터·history/sequence는 유지한다.

격리 전체 검증은 `pwsh -NoProfile -File infra/verify-migration.ps1 -IncludeTaskStatusHistoryRead`다. 이 도구는 뒤이은 로컬 요청에서 적용했으며 다음 dev-actor 앱은 쓰기 on/off 모두 이 권한을 요구한다. [구현/실제 결과](../docs/task-status-history-implementation.md).

### 실제 로컬 상태 이력 SELECT 적용 — 2026-11-10

`pwsh -NoProfile -File infra/provision-task-status-history-read.ps1`를 실제 `flowdesk-local`에 실행했다. 결과는 `TASK_STATUS_HISTORY_READ`; audit table-wide SELECT는 false, 승인 컬럼 SELECT 7, 기존 INSERT 6, UPDATE 0이다. `flowdesk_app` 비밀번호를 유지했고 Flyway/data/sequence는 변하지 않았다. 새 dev-actor JAR의 실제 이력 GET은 [실행 기록](../docs/local-setup.md)을 따른다.

## Workspace 멤버십 관리 최소 권한 — 2026-11-14

V9 이후 `pwsh -NoProfile -File infra/provision-workspace-membership-management.ps1`는 현재 TASK_ASSIGNEE_DUE_DATE 권한 상태에서만 별도 실행한다. 이 도구는 `workspace_memberships` relation DELETE와 `role,updated_at` UPDATE, `workspace_invitations` 현재 매핑의 column SELECT 10개/INSERT 8개/UPDATE 2개를 추가한다. invitation table-wide DML, 다른 membership 컬럼 UPDATE, sequence, Flyway history, DDL 권한은 주지 않는다. 반복 실행은 상태를 보존하고 부분/과도 권한은 자동 복구하지 않는다.

`pwsh -NoProfile -File infra/verify-migration.ps1 -IncludeWorkspaceMembershipManagement`는 고유 Compose PostgreSQL에서 V1~V9, seed, 과거 권한 도구, 새 관리 권한과 보존/실패 상태를 검증한다. 2026-11-14 기준 153개 검사가 통과하고 임시 자원을 제거했다. 별도의 Gradle Testcontainers 테스트도 HTTP 및 V9 제약을 확인한다. 이번 단계에서는 격리 DB만 사용했으며 실제 로컬 V9/ACL은 적용하지 않았다. 실행 순서와 결정은 [정책](../docs/workspace-membership-management-v1.md) 및 [ADR-066](../docs/decisions.md)을 따른다.

2026-11-19 실제 로컬에는 V9를 적용·Validate하고 관리 ACL 최초/반복 적용을 확인했다. 새 dev-actor JAR의 HTTP 초대/수락·역할 변경·멤버 제거·소유권 이전과 원상 복구도 통과했다. 이 PC의 Windows 예약 TCP 범위에 5432가 있어 로컬 Compose host port와 앱/migrator URL은 15432를 사용한다. 업무 seed 행은 원상 복구했고 검증 초대 행은 삭제했다. 상세 결과는 [local-setup.md](../docs/local-setup.md)를 따른다.
