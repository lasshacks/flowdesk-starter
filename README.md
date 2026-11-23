# FlowDesk

팀 협업 중심의 실시간 작업 관리 플랫폼. 개인 학습 프로젝트로서 Vue 3부터 Spring Security, JPA, Redis, Kafka, WebSocket, WebFlux까지 단계적으로 설계·구현·검증한다.

## 개발 원칙
- 기능을 먼저 정의하고 기술은 필요에 따라 도입한다.
- 모듈형 모놀리스로 시작한다.
- 코드 생성뿐 아니라 요청 흐름, 대안, 보안, 장애 시나리오를 학습한다.
- 현재 구현 범위는 Spring health API·Actuator, Compose PostgreSQL과 V1/seed, JPA·워크스페이스 조회/생성 API와 Vue 목록·상세·생성 폼이다. 실제 로컬 최소 INSERT/첫 생성과 격리 브라우저의 생성·응답 유실 처리를 확인했다. 워크스페이스 이름 수정 API·버전 충돌 검출·V2·최소 컬럼 UPDATE 도구도 구현했으며 격리 검증을 완료했다. 이후 실제 로컬 V2/최소 UPDATE와 새 앱의 이름 수정·충돌도 확인했다. Vue 이름 수정 폼·문자열 버전 보존·409 충돌 복구도 구현·격리 브라우저 검증을 완료했다. Project V3·생성/조회 API·최소 권한 도구와 실제 로컬 생성/조회도 확인했다. Vue Project 목록·상세와 생성 폼·POST도 연결했으며 실제 로그인·삭제·Project 수정/작업은 후속이다.

## 문서
- `AGENTS.md`: Codex 작업 규칙
- `docs/requirements.md`: 제품 요구사항 및 수용 기준
- `docs/architecture.md`: 구조와 도메인 모델
- `docs/tech-stack.md`: 기술 선정 및 도입 시점
- `docs/roadmap.md`: 단계별 구현 및 완료 조건
- `docs/decisions.md`: 설계 결정 기록
- `docs/learning-notes.md`: 학습 기록
- `docs/codex-first-prompt.md`: Codex 첫 대화에 붙여넣을 프롬프트
- `docs/local-setup.md`: 개발 도구 설치 명령, 환경 갱신 방법, 실제 검증 결과
- `docs/domain-model-v1.md`: 첫 업무 모델의 필드·제약·트랜잭션 책임과 Flyway V1 범위
- `docs/local-migration.md`: 앱과 분리된 로컬 마이그레이션 실행·자격증명 전달 절차와 실제 적용 결과
- [docs/dev-seed.md](docs/dev-seed.md): 개발용 예제 데이터·Apply/Verify 실행기·격리 검증·실패 처리
- [docs/jpa-read-model-v1.md](docs/jpa-read-model-v1.md): 첫 JPA 조회·SELECT 최소 권한 도구·격리 검증·실제 로컬 적용/조회 결과
- [docs/workspace-read-api-v1.md](docs/workspace-read-api-v1.md): 첫 조회 API 계약·개발 행위자·인가 구현과 격리 검증
- [docs/workspace-create-api-v1.md](docs/workspace-create-api-v1.md): 첫 생성 API·개발 쓰기/출처 경계·원자성·최소 INSERT 도구와 격리 검증·로컬 적용/첫 생성 결과

- [docs/workspace-rename-api-v1.md](docs/workspace-rename-api-v1.md): 이름 수정 권한·버전 충돌·V2·최소 UPDATE 및 적용 순서

- [docs/project-model-and-api-v1.md](docs/project-model-and-api-v1.md): Project 모델·생성/조회·V3·최소 권한·Vue/로컬 검증
- [docs/project-update-api-v1.md](docs/project-update-api-v1.md): Project 이름·설명 수정/OWNER·ADMIN·버전 충돌·V4·최소 UPDATE 구현/검증
- [docs/task-model-and-api-v1.md](docs/task-model-and-api-v1.md): 첫 Task 네 필드/멤버 생성·네 역할 조회·TODO/우선순위·V5/API/최소 권한 구현

## 시작

1. [로컬 개발환경](docs/local-setup.md)의 Java 21 환경을 준비하고 [로컬 PostgreSQL 안내](infra/README.md)에 따라 DB와 앱 계정을 준비한다.
2. [백엔드 실행·테스트 안내](backend/README.md)에 따라 서버를 실행한다. /actuator/health는 실제 DB 연결도 검사한다.
3. `GET http://127.0.0.1:8080/api/health`에서 `{"status":"UP"}`을 확인한다.
4. [프론트엔드 실행·테스트 안내](frontend/README.md)에 따라 Vite를 실행하고 `http://127.0.0.1:5173/`에서 목록·상세를 조회한다. 업무 조회에는 백엔드의 명시적인 dev-actor 실행이 필요하다.
5. Vue 화면의 /api/health는 앱 응답 확인용이며, DB 상태는 /actuator/health에서 확인한다.

Codex와 작업할 때는 `AGENTS.md`와 설계 문서를 기준으로 작은 기능 단위로 설계·구현·검증한다. `docs/codex-first-prompt.md`는 프로젝트 시작 당시의 계획 요청 문서로 유지한다.


2026-11-05 Project: V3·매핑·생성/조회 API·최소 SELECT/INSERT 전용 도구를 구현했다. [계약/로컬 적용 순서](docs/project-model-and-api-v1.md), [실행 검증 기록](docs/local-setup.md)을 따른다. 이후 실제 로컬 V3·PROJECT_CREATE 적용과 Project ID 1 생성/조회·역할별 접근 차단도 확인했다. 기존 5/3/6을 유지하고 Project 한 행만 추가했다. Vue Project 목록·상세도 기존 화면에 연결했다. Project 생성 폼·POST도 연결했고 격리 브라우저에서 검증했다. Project 수정/작업은 후속이다.


Project 수정 정책은 이름·설명/해당 공간 OWNER·ADMIN/Project 버전 충돌 409로 확정했다. [수정 계약](docs/project-update-api-v1.md)에 PATCH·V4·최소 컬럼 UPDATE와 응답/도구 전환 범위를 기록했다. 이번에는 설계만 확정했고 실행 코드·실제 로컬 V3/PROJECT_CREATE·5/3/6/1은 변경하지 않았다.


Project 수정 PATCH·V4·네 컬럼 최소 UPDATE 도구와 Vue 상세/생성의 version 보존을 구현했다. [계약/실행 순서](docs/project-update-api-v1.md), [검증 기록](docs/local-setup.md)을 따른다. 2026-11-07 실제 로컬 V4/PROJECT_UPDATE와 새 앱 수정 200·stale 409·역할별 거부·쓰기 off를 확인했다. 데이터는 5/3/6/1이고 Project 1 version은 1이다. 검증 앱은 종료했으며 Vue 수정 폼은 후속이다.


2026-11-07 Vue Project 이름·설명 수정 폼과 문자열 버전 PATCH도 연결했다. 409/결과 불명은 입력을 보존하고 최신 두 필드·역할 확인/명시적 기준 갱신/별도 수동 저장으로 복구한다. 프론트 246개/빌드·실제 격리 Chrome 검증을 통과했고 실제 로컬 V4/5/3/6/1·Project 1 version 1은 보존했다. [수정 계약](docs/project-update-api-v1.md), [실행 기록](docs/local-setup.md)을 따른다. 다음은 Task 데이터 모델·생성/조회 권한 범위 확정이다.


2026-11-07 첫 Task 모델/생성·조회 정책을 확정했다. 제목·설명·상태·우선순위부터 시작하고 OWNER/ADMIN/MEMBER 생성·네 역할 조회, TODO 고정·우선순위 선택/기본 MEDIUM이다. [Task 계약](docs/task-model-and-api-v1.md)에 V5/부모 경계·HTTP/최소 권한/격리 수용 기준을 정리했다. 현재는 설계 문서만 변경했으며 코드·DB/V5 구현은 다음 단계다.


Task V5·매핑·생성/목록/상세 API·최소 SELECT/INSERT 전용 도구를 구현했다. MEMBER는 Task 생성이 가능하지만 기존 Project 관리 권한은 유지한다. [계약/적용 순서](docs/task-model-and-api-v1.md), [실제 검증 기록](docs/local-setup.md)을 따른다. 실제 로컬은 V4/PROJECT_UPDATE로 유지하며 새 앱 기동 전 V5/Task 권한의 별도 적용이 필요하다. Vue Task는 후속이다.

Task 구현의 최종 격리 검증은 Java 396개·실제 Vite 1개·PowerShell 92개 및 빌드가 통과했다. 기존 로컬 V4/데이터는 보존했으며 다음은 로컬 V5·최소 Task 권한 적용과 새 앱의 생성·조회 확인이다.


2026-11-08 실제 로컬 V5/TASK_CREATE와 새 앱의 첫 Task 생성/조회도 확인했다. MEMBER Carol의 Task ID 1 생성은 201/TODO·MEDIUM, 네 역할 조회는 200이며 Project 관리/VIEWER 생성·부모/출처·쓰기 off 경계도 검증했다. 기존 데이터는 보존하고 Task 한 행만 추가해 5/3/6/1/1이다. 검증 서버는 종료했으며 새 앱은 로컬 V5와 호환된다. [실행 기록](docs/local-setup.md)을 따른다. 다음은 **Vue Task 목록·상세 조회 연결**이다.


2026-11-08 Vue Task 목록·상세 조회를 선택한 Project 아래에 연결했다. 제목/상태/우선순위·일반 텍스트 설명·20개 페이지/오류·재시도/부모 전환 취소를 제공한다. 프론트 297개·타입 검사/빌드와 실제 Chrome/Vite/쓰기 off 앱 GET 조회를 확인했고 로컬 5/3/6/1/1을 보존했다. [프론트 안내](frontend/README.md), [실행 기록](docs/local-setup.md)을 따른다. 다음은 **Vue Task 생성 폼·POST 연결**이다.


2026-11-08 Vue Task 생성 폼·POST도 연결했다. OWNER/ADMIN/MEMBER가 제목/선택 설명/우선순위(기본 MEDIUM)를 입력하고 서버 TODO로 생성한다. 201 후 목록/상세 선택·중복 제출 차단·결과 불명 잠금/미재전송을 제공한다. 프론트 355개/타입 검사·빌드 및 실제 Chrome/격리 DB 검증을 통과했고 실제 로컬 5/3/6/1/1은 보존했다. [프론트 안내](frontend/README.md), [실행 기록](docs/local-setup.md)을 따른다. 다음은 **Task 수정 API의 필드·권한·충돌 정책 확정**이다.


2026-11-08 Task 수정 정책을 확정했다. 제목·설명·우선순위와 해당 공간 OWNER/ADMIN/MEMBER의 모든 Task 수정, Task 자체 버전/409다. [수정 계약](docs/task-update-api-v1.md)에 PATCH/V6·최소 UPDATE/응답·Vue 전환/격리 기준을 기록했다. 이번은 문서 단계이며 로컬은 V5/TASK_CREATE·5/3/6/1/1 그대로다. 다음은 **Task 수정 API·V6·최소 UPDATE 권한 도구 구현과 격리 PostgreSQL 검증**이다.


Task 수정 API·V6·최소 다섯 컬럼 UPDATE 전용 도구와 기존 Vue version 응답 파서 전환을 구현했다(2026-11-08). 세 역할/두 부모·Task 자체 충돌/409와 상태·부모 보존을 검증한다. [수정 계약](docs/task-update-api-v1.md), [실행 기록](docs/local-setup.md)을 따른다. 실제 로컬은 V5/TASK_CREATE이며 **새 앱은 V6/필요 권한 적용 후 기동**해야 한다. 이번 작업은 격리 검증이며 실제 로컬 적용과 Vue 수정 폼은 별도다.

Task 수정 구현의 최종 검증은 **Java 446개/Vite 1개·PowerShell 105개·Vue 364개 및 빌드 통과**다. 실제 로컬 V5/5/3/6/1/1·전후 스냅샷/보호 파일 8개는 유지했고 격리 자원을 정리했다. 다음은 **로컬 V6·최소 Task UPDATE 적용과 새 앱의 수정 확인**이다. [실행 기록](docs/local-setup.md)을 따른다.


2026-11-08 실제 로컬 V6/최소 Task UPDATE와 새 앱의 Task 수정도 확인했다. MEMBER Carol의 Task 1 수정은 200/version 1/HIGH·TODO 유지, stale 409·no-op·OWNER/ADMIN·VIEWER/미소속·부모/출처·쓰기 off·health 경계가 통과했다. 기존 데이터 5/3/6/1/1과 부모 Project version 1을 유지하고 Task 다섯 필드만 변경했다. 새 앱은 로컬 V6와 호환되고 검증 서버는 종료했다. [실행 기록](docs/local-setup.md)을 따른다. 다음은 **Vue Task 제목·설명·우선순위 수정 폼·버전 보존·409 충돌 안내 연결**이다.


2026-11-08 Vue Task 제목·설명·우선순위 수정 폼과 문자열 version PATCH를 연결했다. 409/결과 불명은 세 입력을 보존하고 최신 Task/역할·우선순위 포함 비교→명시적 기준 갱신→별도 수동 저장으로 복구한다. 프론트 415개/빌드·실제 격리 Chrome/DB 검증을 통과했고 실제 로컬 V6/5/3/6/1/1·Task 1 version 1은 보존했다. [프론트 안내](frontend/README.md), [실행 기록](docs/local-setup.md)을 따른다. 다음은 **Task 상태 변경 API의 허용 전이·권한 범위 확정**이다.


2026-11-08 Task 상태 정책은 세 상태 간 모든 전이·해당 공간 OWNER/ADMIN/MEMBER·실제 전환 시 최소 Activity의 동시 저장으로 확정했다. [상태 계약](docs/task-status-api-v1.md)에 전용 PATCH/공유 Task 버전·V7/최소 권한·상태/이력 원자성과 격리 기준을 기록했다. 이번은 문서 단계이며 로컬은 V6/TASK_UPDATE 그대로다. 다음은 **Task 상태 변경 API·V7·최소 권한 도구·상태 이력 원자성 구현과 격리 PostgreSQL 검증**이다.

### 2026-11-10 Task 상태 변경 구현

Task 상태 전용 PATCH·V7 최소 이력·TASK_STATUS_CHANGE 권한 도구를 구현했다. 요청/인가/공유 버전·트랜잭션과 재현 명령은 [구현 기록](docs/task-status-implementation.md), 실제 검증 결과는 [실행 기록](docs/local-setup.md)을 따른다. 실제 로컬은 V6/TASK_UPDATE를 유지하며 새 쓰기 앱을 사용하려면 다음 단계에서 V7/최소 상태 권한을 별도 적용해야 한다. Vue 상태 UI·Activity 조회는 후속이다.

### 실제 로컬 상태 변경 확인 — 2026-11-10

로컬 V7/TASK_STATUS_CHANGE 적용과 새 JAR의 HTTP 검증까지 완료했다. Task 1은 Carol의 단일 요청으로 TODO/version 1 → IN_PROGRESS/version 2가 되었고 상태 이력 1건을 남겼다. 기존 데이터 5/3/6/1/1·일반 필드·부모/이전 이력·기존 ACL/시퀀스는 유지했다. 검증 앱은 종료했고 PostgreSQL은 정상 실행 중이다. 다음은 **Vue Task 상태 변경 UI·버전 보존·409 충돌 안내 연결**이다. [검증 기록](docs/local-setup.md).

### Vue Task 상태 변경 연결 완료 — 2026-11-10

Task 상태 선택·전용 PATCH·일반 편집과 독립된 입력/시작 버전·409/결과 불명 잠금·최신 Task/역할 비교·명시적 기준/수동 저장을 연결했다. 프론트 479개/21파일·타입 검사/Vite build와 실제 Chrome/Vite/격리 PostgreSQL에서 양방향 교차 편집 충돌·커밋된 응답 유실·VIEWER/405·데스크톱/모바일을 확인했다. 실제 로컬 V7/Task version 2·이력 1건과 백엔드는 그대로다. [구현·검증](docs/task-status-ui.md). 다음은 **Task 상태 변경 이력 조회 API의 계약·권한 범위 확정**이다.

### Task 상태 이력 조회 계약 확정 — 2026-11-10

현재 소속 네 역할의 전체 과거 이력 조회, 행위자 ID/현재 표시 이름·이메일 비노출, 최신순 Task 버전 커서(기본 20/최대 100)를 확정했다. [계약](docs/task-status-history-read-api-v1.md)에 응답·인가·최소 컬럼 SELECT/기동 전환과 격리 기준을 기록했다. 이번은 문서만 변경했다. 다음은 **Task 상태 이력 조회 API·최소 SELECT 권한 도구 구현과 격리 PostgreSQL 검증**이다.

### Task 상태 이력 조회 구현 — 2026-11-10

Task별 상태 이력 GET·현재 이름 projection·최신순 버전 커서와 최소 SELECT 전용 실행기를 구현했다. 기존 모든 관리자 도구의 새 권한 보존과 개발 앱 읽기/쓰기 기동 검사도 전환했다. [구현·검증](docs/task-status-history-implementation.md). 실제 로컬 V7/Task version 2·이력 1건은 유지하며 다음은 **로컬 상태 이력 SELECT 권한 적용과 새 앱의 이력 조회 확인**이다. Vue 이력 화면은 후속이다.

### Task 상태 이력 조회 실제 적용 — 2026-11-10

전용 TASK_STATUS_HISTORY_READ를 로컬에 적용하고 새 dev-actor 앱의 read-only GET을 확인했다. 기존 Carol의 Task 1 상태 이력 1건을 반환하며 Actuator UP, UTC 정밀 시각, 이메일 비노출, 커서·입력 검증까지 통과했다. 데이터·V1~V7·기존 상태 변경 권한은 유지했다. [실행 기록](docs/local-setup.md). 다음은 **Vue Task 상태 이력 조회 화면 연결**이다.

### Task 상태 이력 Vue 화면 연결 — 2026-11-10

Task 상세의 “상태 변경 이력 보기”를 열면 최신 20건을 읽고 cursor로 더 이전 기록을 덧붙인다. 상태/actor 이름/UTC 시각·작업 버전을 표시하고 loading/빈 결과/실패·재시도를 제공한다. `npm.cmd run build`의 타입 검사와 production build 통과. [구현](docs/task-status-history-ui.md). 다음 한 작업은 **Task 담당자·마감일 필드의 모델·권한 범위 확정**이다. 실제 로그인·전체 Task MVP는 계속 후속 범위다.

### Task 담당자·마감일 범위 확정 — 2026-11-10

담당자는 현재 Workspace 멤버만 지정할 수 있으며 멤버 제거 후 기존 할당은 보존한다. 마감일은 timezone 없는 `YYYY-MM-DD`다. 생성/수정 및 목록/상세에 추가하고 기존 역할·Task version 정책을 유지한다. [정책 및 데이터 모델](docs/task-assignee-due-date-v1.md). 다음은 V8/API/최소 권한 구현과 격리 PostgreSQL 검증이다.

### Task 담당자·마감일 API 및 모델 구현 — 2026-11-11

V8과 생성/수정/목록/상세 API에 nullable 담당자와 달력 날짜를 반영하고, 현재 Workspace membership을 서버에서 확인한다. [정책](docs/task-assignee-due-date-v1.md), [구현·검증](docs/task-assignee-due-date-implementation.md). 실제 로컬 DB 적용과 담당자/날짜 입력 UI는 후속이다.
