# Task 상태 변경 구현·검증 — 2026-11-10

확정 정책은 [상태 변경 계약](task-status-api-v1.md)을 따른다. API·V7·최소 권한 도구/격리 검증과 실제 로컬 적용·새 JAR의 상태/이력 확인을 아래에 구분한다. 이후 [Vue 상태 UI·공유 버전 복구](task-status-ui.md)도 연결하고 검증했다.

## 요청부터 commit까지

1. 개발 모드·쓰기 설정·loopback·Origin/JSON 검사를 통과한 `PATCH /api/workspaces/{workspaceId}/projects/{projectId}/tasks/{taskId}/status`만 처리한다. 요청은 정확한 대문자 status와 문자열 version 두 필드다.
2. TaskStatusController가 strict JSON·ID/버전을 검사하고 TaskCommandService가 서버 행위자 → 공간 소속 → Project/Task 부모 → OWNER/ADMIN/MEMBER → 읽은 버전을 확인한다. VIEWER 403, 미소속/미존재/부모 불일치 404, 오래된 버전 409다.
3. READ_COMMITTED Service 트랜잭션에서 상태 전용 Repository를 호출한다. 현재 버전의 같은 상태는 OPTIMISTIC commit 검사만 수행한다. 서로 다른 세 상태 간 모든 전이는 허용한다.
4. 실제 변경은 native SQL의 id/version 조건·version+1·영향 행 수 1 검사로 처리한다. Task.status의 `updatable=false`를 유지해 일반 수정 SQL에 상태가 섞이지 않는다. 관리 Entity를 refresh해 DB에 저장된 상태/버전/시각을 읽는다.
5. 같은 트랜잭션에서 고정 여섯 컬럼의 상태 이력을 INSERT하고 영향 행 수 1을 확인한다. generated keys/RETURNING/후속 SELECT는 없다. 이력 FK/UNIQUE·권한·INSERT 실패/0건·최종 commit 실패는 전체 rollback한다.
6. Service 프록시의 commit 성공 뒤 기존 여덟 필드 TaskView를 200으로 반환한다. 일반 수정과 상태 변경이 같은 version을 사용하면 하나만 성공하며, 일반 수정 승자는 상태 이력이 없다. 목록 여섯 필드/생성 TODO·version 0은 유지한다.

쓰기 off일 때 `/status`는 대응하는 GET 경로가 없어 Spring 기본 동작만으로는 404였다. 개발 프로필·쓰기 off에만 활성화되는 거부 Controller로 계약의 405를 명시한다. 이 Controller에는 쓰기 Service/Repository 의존성이 없다. 기본 프로필은 404를 유지한다.

## DB와 관리자 실행 경계

- V7은 빈 `flowdesk.task_status_activities`만 추가한다. Task/User FK·세 상태 CHECK·from≠to·양수 버전·Task/버전 UNIQUE를 적용하고 과거 상태를 backfill하지 않는다.
- `TASK_STATUS_CHANGE`는 정확한 TASK_UPDATE에 `tasks UPDATE(status)`와 이력 입력 여섯 컬럼 INSERT만 더한다. 이력 SELECT/전체 INSERT/id INSERT/UPDATE/DELETE/TRUNCATE·시퀀스/DDL 권한은 주지 않는다.
- `AppTaskStatusChangeAccessRunner`/`runAppTaskStatusChangeAccess`/`infra/provision-task-status-change.ps1`이 관리자 자격증명으로 수동 적용한다. 기존 비밀번호를 인증한 뒤 단일 트랜잭션으로 GRANT하며, 부분/과도/PUBLIC/GRANT OPTION 상태는 수선하지 않는다. 이전 도구들은 완전한 새 상태를 보존한다.
- 앱 기동 검사는 새 상태의 유효 권한/이력 테이블을 요구한다. 앱 Flyway 비활성·JPA validate·OSIV=false는 유지한다. 읽기 전용 앱은 V6 Task 매핑으로 조회할 수 있다.
- 현재 migration history/seed는 V1~V7을 요구한다. 과거 V6 테스트는 target 6을 명시하고 미래 seed fixture는 V8로 옮긴다. seed/관리자 잠금에 새 테이블을 포함하며 이력이 있으면 seed가 데이터 불일치로 중단한다.

## 재현 명령과 결과 기록

저장소 루트에서 다음 명령을 사용한다. Gradle 명령은 backend 디렉터리에서 실행한다. 모든 DB 쓰기는 Testcontainers 또는 고유 Compose 프로젝트에서만 수행한다.

```powershell
# backend/
./gradlew.bat test --tests '*TaskStatus*' --tests '*V7MigrationTest' --tests '*V6MigrationTest' --no-daemon --console=plain
./gradlew.bat test verifyWorkspaceProxy build --no-daemon --console=plain

# 저장소 루트
pwsh -NoProfile -File infra/verify-migration.ps1 -IncludeTaskStatusChange

# frontend/
npm.cmd test
npm.cmd run build
```

실제 실행 결과·실패 수정·자원 정리/로컬 보존 결과는 [실행 기록](local-setup.md)에 구분한다. 로그와 검증 영수증은 Git 제외 `backend/build/task-status-verification/`에 저장한다.

## 후속 실제 로컬 적용

구현/격리 검증 단계에서는 실제 로컬 DB에 V7/권한을 적용하지 않았다. 이후 사용자 요청으로 앱 포트 비어 있음 확인 → 전용 migrator V7 Migrate/Validate/반복 → `pwsh -NoProfile -File infra/provision-task-status-change.ps1` 최초/반복 → 새 JAR의 HTTP 상태 변경/이력 검증 순서로 적용했다. V7/새 권한이 없는 이전 V6 환경에서는 새 쓰기 앱의 기동 검사가 실패한다.

다음 한 작업은 **Vue Task 상태 변경 UI·버전 보존·409 충돌 안내 연결**이다.

## 완료한 검증

| 검증 | 실제 결과 |
|---|---|
| 최초 백엔드 전체 | 28클래스/516개 중 515 통과, 새 FK에 따른 기존 누락 fixture 1개 실패 |
| 수정 후 해당 검증/추가 검증 | JPA 7개·GRANT 원자성 1개, 총 8개 통과 |
| 현재 고유 백엔드 검증 | 516−7+8=517개 모두 통과. 단계별 결과를 교체한 합계이며 한 번의 전체 재실행은 아님 |
| 실제 Vite 프록시 | 1개 통과, 깊은 상태 PATCH·Origin 거부·상태 이력 확인 |
| Gradle build | 수정 검증/프록시 이후 성공 |
| PowerShell 전용 실행기 | 고유 Compose에서 118 checks 통과 |
| 기존 Vue | 415개/19파일·타입 검사/Vite build 통과, 소스/응답 계약 유지 |
| 실제 로컬 보존 | V6·5/3/6/1/1 전체 스냅샷/보호 파일 9개 동일, 원래 중지 상태 복원 |
| 정리 | 두 고유 Compose 프로젝트/볼륨/네트워크와 임시 자격증명 6개 제거 확인 |

결과 영수증은 Git 제외 `backend/build/task-status-verification/receipt.json`의 VERIFIED다. 최초 실패와 재검증 명령은 실행 기록에 남겼다. 위 표는 구현/격리 검증 단계의 결과이며 이후 실제 로컬 적용은 아래에 구분한다.

## 실제 로컬 적용·검증 완료 — 2026-11-10

사용자 별도 요청으로 로컬 V7 Migrate 1건/Validate/반복 0건, TASK_STATUS_CHANGE 최초/반복과 새 JAR의 실제 HTTP를 확인했다. Carol(MEMBER)이 기존 Task 1을 TODO/version 1 → IN_PROGRESS/version 2로 한 번 변경했고, Carol·이전/다음 상태·새 버전·Task와 같은 DB 저장 시각의 이력 1건을 확인했다. 제목/설명/HIGH·부모/기존 데이터 5/3/6/1/1·기존 이력/ACL/시퀀스·보호 파일 11개는 유지했다.

stale 상태/일반 수정 409·최신 버전 상태/일반 no-op·OWNER/ADMIN 허용·VIEWER 403·미소속/부모 404·Origin/입력·쓰기 off 405·Actuator UP도 확인했다. 앱에는 이력 SELECT/시퀀스 권한이 없다. 검증 앱 6개를 종료했고 DB는 running/healthy다. 실제 장애/병렬 검증은 이전 격리 결과와 구분하며 제품 코드가 같아 전체 회귀/빌드를 반복하지 않았다.

최신 로컬 영수증은 Git 제외 `backend/build/local-task-status-verification/receipt.json`의 VERIFIED다. 실제 명령·대조/정리 결과는 [실행 기록](local-setup.md)을 따른다. 다음은 **Vue Task 상태 변경 UI·버전 보존·409 충돌 안내 연결**이다.

## Vue 상태 UI 연결 완료 — 2026-11-10

상태 전용 폼/PATCH·독립된 입력/공유 버전·409/결과 불명 복구를 연결했다. [Vue 설계/검증 기록](task-status-ui.md)을 따른다. 479개/21파일·타입 검사/Vite build와 실제 Chrome/Vite/격리 DB의 교차 폼 충돌·커밋 응답 유실·VIEWER/405·1280/390px를 확인했다. 실제 로컬 V7/Task version 2·이력 1건과 백엔드는 유지했다. 다음은 **Task 상태 변경 이력 조회 API의 계약·권한 범위 확정**이다.
