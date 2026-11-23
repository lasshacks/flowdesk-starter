# Task 상태 이력 조회 구현·검증 — 2026-11-10

확정한 [조회 계약](task-status-history-read-api-v1.md)에 따라 Task별 이력 GET과 최소 SELECT 실행기를 구현했다. 이번 범위는 코드·격리 검증이다. 실제 로컬 SELECT 적용과 Vue 이력 화면은 별도 작업이다. V7 SQL·migration history 1~7·기존 데이터 모델은 유지한다.

## 요청과 저장소 흐름

1. TaskStatusHistoryController는 dev-actor에서 `GET /api/workspaces/{workspaceId}/projects/{projectId}/tasks/{taskId}/status-activities`를 제공한다. 쓰기 설정과 독립적이며 beforeVersion/size만 허용한다. 경로/커서는 양의 정규 Long 문자열, size는 1~100/기본 20이다. 빈/중복/미지원 쿼리·자동 보정은 허용하지 않는다.
2. TaskStatusHistoryQueryService의 READ_COMMITTED/readOnly 트랜잭션에서 서버 행위자 → 현재 Workspace 소속 → Project/Task 부모를 검사한다. 네 역할 모두 조회하고 미소속/미존재/부모 불일치는 같은 404다. 가입 시각이나 행위자의 현재 membership으로 과거 기록을 숨기지 않는다.
3. mandatory/readOnly TaskStatusHistoryRepository는 task_id·배타적 task_version 경계·DESC·size+1로 읽는다. native projection은 이력 필드와 users.display_name을 한 번에 선택하며 User Entity/이메일을 응답하지 않는다. 항목별 사용자 쿼리를 실행하지 않는다.
4. native timestamp의 JDBC/Hibernate Java 매핑 차이에 기대지 않도록 PostgreSQL에서 UTC ISO 문자열로 변환하고 Instant로 읽는다. 실제 저장된 마이크로초를 보존하며 세션 timezone과 응답 timezone을 분리한다.
5. size개만 DTO에 넣고 여분 한 건으로 hasNext를 계산한다. 다음 커서는 마지막 반환 taskVersion이고, 마지막/빈 페이지는 null이다. 부모/Task ID·beforeVersion/size를 echo하는 8필드 envelope와 6필드 item/2필드 actor를 사용한다. ID/버전은 문자열, 시간은 UTC ISO다. 현재 이름이 바뀌면 과거 항목의 표시 이름도 바뀐다.

새 기록은 첫 페이지 새로고침에서 확인한다. 이미 받은 마지막 버전보다 작은 기록을 이어 읽으므로 추가된 최신 기록이 다음 페이지를 밀지 않는다. 여러 요청에 걸친 전체 DB 스냅샷이나 임의 page 번호 이동은 제공하지 않는다.

## 최소 권한과 기동 경계

- TASK_STATUS_HISTORY_READ는 기존 TASK_STATUS_CHANGE + audit의 고정 7개 컬럼 SELECT다. 기존 INSERT 6개/Task status UPDATE와 다른 권한을 보존한다. table-wide SELECT ACL·id INSERT·audit UPDATE/DELETE/TRUNCATE/REFERENCES/TRIGGER·직접 시퀀스/DDL·Flyway 이력 권한을 추가하지 않는다.
- AppReadPolicy는 원시 ACL의 SELECT/INSERT를 구분하고 정확한 유효 집합을 검사한다. 부분/과도·PUBLIC/GRANT OPTION은 자동 수선하지 않는다. 현재 7개 컬럼에 모두 SELECT가 있으므로 SELECT * 문장은 가능하지만 미래 컬럼은 자동 허용되지 않는다.
- `infra/provision-task-status-history-read.ps1` → `run-app-task-status-history-read-gradle.ps1` → `runAppTaskStatusHistoryReadAccess` → AppTaskStatusHistoryReadAccessRunner/전용 SQL로 수동 적용한다. 기존 앱 비밀번호를 먼저 인증하고 한 트랜잭션에서 GRANT/검증하며 비밀번호를 유지한다. 이전 모든 관리자 도구도 완전한 새 상태를 보존한다.
- DevTaskStatusHistoryReadSettings는 dev-actor의 쓰기 on/off 모두 SELECT 7개와 audit 금지 권한을 검사한다. DevWorkspaceWriteSettings는 새 컬럼 SELECT를 허용하면서 기존 정확한 쓰기 권한을 유지한다. 앱은 자동 migration/GRANT를 하지 않는다.
- 실제 로컬은 이제 TASK_STATUS_HISTORY_READ다. 다음 dev-actor 앱은 쓰기 on/off 모두 7개 SELECT 권한 검사를 통과한다. 기본 프로필은 새 GET이 비활성이다. 읽기 HTTP 모드가 앱 DB 계정의 기존 쓰기 ACL을 철회하는 것은 아니다.

## 변경 파일과 대안

신규 TaskStatusHistoryController·읽기 기동 검사·Service/DTO·native projection Repository, TaskErrors 범위, AppReadPolicy/AppReadAccessRunner·새 관리자 실행기/SQL·Gradle/PowerShell을 변경했다. 기존 HTTP fixture는 새 조회 권한을 준비하고, 과거 권한/마이그레이션 fixture는 원래 상태를 유지한다. 실제 Vite 프록시와 PowerShell 검증에 새 GET/권한 상태를 포함했다.

새 Activity Entity/지연 로딩이나 항목별 사용자 조회 대신 필요한 필드의 projection을 선택했다. page/offset·날짜/전역 ID 경계 대신 기존 Task 버전 UNIQUE를 재사용한다. 새 migration/인덱스·의존성·일반 Activity 프레임워크·프론트 변경은 없다. 작은 fixture에서는 PostgreSQL이 순차 스캔을 고를 수 있으므로 특정 실행 계획을 강제하지 않는다. 격리 테스트는 EXPLAIN 실행과 기존 복합 인덱스 존재·SQL 경계를 확인하며 대용량 성능 보장을 주장하지 않는다.

## 재현 명령

```powershell
# backend/
./gradlew.bat test --tests '*TaskStatusHistory*' --tests '*TaskStatusApiTest' --no-daemon --console=plain
./gradlew.bat test verifyWorkspaceProxy build --no-daemon --console=plain

# 저장소 루트
pwsh -NoProfile -File infra/verify-migration.ps1 -IncludeTaskStatusHistoryRead

# frontend/
npm.cmd test
npm.cmd run build
```

실제 결과·실패 수정·로컬 보존/자원 정리는 [실행 기록](local-setup.md)에 구분한다. Git 제외 `backend/build/task-status-history-verification/`에 집중/전체/PowerShell/프론트 로그와 검증 영수증을 보관한다. 테스트 결과를 완료로 추측하지 않는다.

## 최종 검증 결과

**최종 검증: Java 30클래스/565개·실제 Vite 프록시 1개·Gradle build, PowerShell 전체 134개, Vue 21파일/479개·타입 검사/Vite build가 모두 통과했다.** 새 이력 API 35개/권한 정책 13개를 포함하며 전체 회귀는 실패/오류/스킵 0이다. 최초 집중 실행의 기존 오류 문구 기대값 1건은 수정 후 이 전체 실행으로 통과를 확인했다.

실제 로컬의 전체 스냅샷·V7/TASK_STATUS_CHANGE·Task version 2/이력 1건과 보호 파일 52개를 보존했다. 고유 Compose 컨테이너/볼륨/네트워크와 Testcontainers는 정리됐고 격리 자격증명 파일 3개도 삭제됐다. 실행 결과 XML은 full-results/proxy-results, 최종 영수증은 `backend/build/task-status-history-verification/receipt.json`에 보관한다.

실제 로컬 적용·HTTP 결과는 [실행 기록](local-setup.md)에 있다. 다음 한 작업은 **Vue Task 상태 이력 조회 화면 연결**이다.

## 실제 로컬 적용 — 2026-11-10

실제 로컬에서 전용 TASK_STATUS_HISTORY_READ 실행기로 audit 7개 컬럼 SELECT를 적용하고 새 dev-actor JAR을 읽기 모드로 기동해 검증했다. Actuator UP, Carol의 Task 1 이력 1건(버전 2, TODO→IN_PROGRESS, 정확한 UTC 마이크로초), envelope/item/actor 필드, 마지막 커서의 빈 페이지와 strict size 400을 확인했다. table-wide SELECT는 없고 기존 audit INSERT 6개/Task UPDATE·다른 ACL·데이터·Flyway V1~V7·시퀀스는 보존했다. Vue 이력 화면과 실제 로그인은 후속이다. 다음 한 작업은 **Vue Task 상태 이력 조회 화면 연결**이다. 상세 증거는 `backend/build/task-status-history-verification/local-grant.log`와 `local-http-receipt.json`이다. Apply는 V7/데이터를 변경하지 않고 activity 컬럼 SELECT ACL 일곱 건만 추가했다.
