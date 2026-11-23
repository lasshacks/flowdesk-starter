# Workspace 초대 대기 목록 구현 기록

- 날짜: 2026-11-20
- 계약: [Workspace 초대 대기 목록 API v1](workspace-invitation-list-api-v1.md)
- 결정: [ADR-067](decisions.md#adr-067-workspace-초대-대기-목록-api-계약-2026-11-20)

## 구현 범위

- `GET /api/workspaces/{workspaceId}/invitations?page=0&size=20`을 `WorkspaceMembershipController`에 추가했다. 기존 controller와 같은 `dev-actor` profile, 명시적 `workspace-write-enabled` 조건 아래에서만 노출한다.
- Service가 요청마다 서버 actor와 현재 Workspace membership을 읽고 OWNER/ADMIN을 허용한다. 다른 현재 역할은 403, 미소속·미존재 공간은 동일 404다. GET은 read-only이며 row lock이나 상태 변경을 하지 않는다.
- `WorkspaceInvitationReadRepository`는 `workspace_id`, `PENDING`을 제한하고 invitation ID 내림차순으로 target display name/email과 role/created/expires를 native projection으로 읽는다. `expired`는 요청 처리 시 서버 `Instant`를 쿼리에 전달해 `expires_at <= now`로 계산한다.
- 응답은 전용 `WorkspaceInvitationList` DTO다. ID는 문자열이며 code/digest/target user ID/inviter 정보는 없다. page 0/size 20 기본, 최대 size 100, `hasNext`는 기존 slice envelope를 재사용한다. 알 수 없는/중복 query와 부적합 숫자는 400이다.
- V9, DDL, 앱 ACL/provisioning 도구, Flyway 이력에는 변경이 없다. 기존 초대 invitation/user 컬럼 최소 SELECT 권한만 사용했다.

## 검증

실행 명령:

```powershell
cd backend
.\gradlew.bat test --tests com.flowdesk.workspace.WorkspaceMembershipManagementApiTest --no-daemon --console=plain
```

Gradle Wrapper 8.14.3은 이 환경에 배포본이 없어 첫 실행의 다운로드가 sandbox 네트워크에서 차단됐다. 권한 상승 실행으로 다운로드 후 테스트를 재실행했다. 첫 컴파일은 controller의 `RequestParam` import 누락으로 실패했고 이를 추가했다. 다음 test compile은 fixture helper의 checked exception 선언 누락으로 실패해 선언을 보완했다. 최종 실행은 `BUILD SUCCESSFUL`, 해당 Testcontainers 기반 PostgreSQL API 클래스 **5 tests, 0 failures/errors/skips**였다. 이어 `bootJar --no-daemon --console=plain`도 `BUILD SUCCESSFUL`로 실행 가능한 JAR을 패키징했다.

목록 테스트는 Alice OWNER와 Bob ADMIN의 허용, Carol MEMBER와 Dave VIEWER의 403, Eve 및 없는 Workspace의 동일 404, 최신 ID 우선 page 0/1과 기본 size, 만료된 PENDING 포함 및 `expired=true`, ACCEPTED·REVOKED/다른 Workspace 초대 제외, 이메일/표시 이름 projection, 비밀 필드 미노출, 잘못된/중복 query 400을 확인했다. 테스트 후 invitation 행과 Eve membership이 정리된다.

## 미실행 범위와 다음 단계

Vue의 `WorkspaceMembershipManager`에 OWNER/ADMIN 전용 목록을 연결했다. manager를 열 때 멤버와 초대를 독립적으로 조회하고, 대상 이름·이메일·역할·발급/만료 시각 및 `expired`를 표시한다. 초대 취소는 기존 DELETE를 사용하고 성공 후 현재 페이지를 다시 읽는다. 발급 코드 표시 상태와 목록 상태를 분리했으며 코드는 목록 응답에서 허용하지 않는다. client는 7개 항목 필드와 page envelope를 엄격히 검증한다.

검증: `frontend`에서 `npm.cmd test -- --run` 결과 **24 files / 507 tests 통과**, `npm.cmd run build`에서 vue-tsc와 Vite build 성공. 테스트는 관리자가 만료 항목을 확인·취소하고 목록이 갱신되는 흐름, VIEWER가 초대 목록 요청을 하지 않는 경계를 포함한다. 이후 로컬 새 앱과 Vite proxy에서 실제 HTTP GET/POST/DELETE/GET을 확인하고 생성한 초대 행을 정리했다. 실제 Chrome DOM 자동 확인은 DevTools 연결 종료/headless timeout으로 마치지 못했다. 결과는 [local-setup.md](local-setup.md)에 기록했다. 로컬 ACL 변경은 없다.

### Chrome 화면과 취소 상호작용 확인 — 2026-11-23

브라우저 검증기에서 초대 fixture를 iframe의 초기 `about:blank` load 시 fetch로 주입하려 했으나 초기 load race로 Vue 앱이 시작되지 않았다. 이를 수정해 Vite middleware가 invitation GET/DELETE 경로만 메모리에서 응답하도록 하고, Workspace 목록·상세·멤버 GET은 dev-actor 백엔드로 전달했다. 첫 브라우저 주소는 기존 일반 Vite 앱에 연결돼 Workspace API 404가 났으며, 검증 서버 포트를 분리한 뒤 확인 화면을 열었다.

Chrome에서 FlowDesk Demo A의 멤버 관리와 만료된 Eve Example 초대, 이름/이메일, `초대 취소` 버튼을 표시했다. 사용자가 취소 버튼을 눌렀고 후속 fixture GET 응답은 `items: []`였다. invitation GET/DELETE는 Vite 메모리 fixture에서 처리했으므로 DB에 초대를 쓰거나 수정하지 않았다. 임시 백엔드/Vite를 종료했고 18080/15173 포트가 응답하지 않는 것을 확인했다. 실제 backend/Vite proxy의 HTTP 쓰기 검증은 앞선 로컬 결과와 구분한다.

Project 보관 결정은 [ADR-068](decisions.md#adr-068-project-보관-복구-정책-2026-11-23)과 [계약](project-archive-api-v1.md)에 기록했다. 다음은 V10/API/권한 구현과 격리 검증이다.
