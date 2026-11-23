# FlowDesk 제품 요구사항 v0.1

## 목적과 대상
여러 사용자가 팀/프로젝트에 참여하여 작업을 생성·배정·추적하고, 변경사항과 알림을 실시간으로 공유하는 웹 서비스. 데스크톱 우선 반응형 UI. 초기 목표는 로컬 환경에서 여러 계정으로 협업 시나리오를 재현하는 것이다.

## 도메인 용어
- User: 계정 소유자
- Workspace: 팀 협업 공간
- WorkspaceMembership: 사용자와 워크스페이스 소속 관계
- Project: 워크스페이스 안의 프로젝트
- ProjectMembership: 초기 미도입. Project는 워크스페이스 멤버십을 상속하며 비공개/별도 초대 요구가 생길 때 추가 여부를 재검토한다
- Task: 프로젝트 내 작업
- Comment: 작업 댓글
- Activity: 변경 이력
- Notification: 사용자 알림

## MVP (Phase 1~3)
### 계정 및 인증
- 이메일 기반 회원가입/로그인/로그아웃
- 본인 계정 정보 조회
- 인증되지 않은 요청 차단, 다른 팀 데이터 접근 차단
- JWT, 재발급, MFA는 별도 보안 단계에서 순차 구현(초기 인증 세부 설계 확정 필요)

### 워크스페이스/프로젝트
- 워크스페이스 생성, 목록, 상세
- 멤버 초대/수락/제거(초기에는 개발용 초대 링크 또는 코드 방식, 외부 이메일 발송 불필요)
- 프로젝트 생성, 수정, 보관
- 기본 역할: OWNER, ADMIN, MEMBER, VIEWER. 권한 매트릭스는 구현 전 별도 확정

### 작업/칸반
- 작업 생성, 조회, 수정, 보관
- 제목(필수), 설명, 상태(TODO/IN_PROGRESS/DONE), 우선순위(LOW/MEDIUM/HIGH), 담당자, 마감일
- 프로젝트별 목록 및 칸반 보드, 상태 변경
- 댓글 및 기본 변경 이력
- 목록 필터: 상태, 담당자, 우선순위

## 후속 확장
- WebSocket 기반 작업 변경 알림과 다중 브라우저 동기화
- Kafka 기반 비동기 이벤트 처리, 중복 소비/실패 재처리
- Redis 캐시 및 인증 관련 상태 관리
- MFA(TOTP) 등록·검증·복구 절차
- AOP 감사·성능 측정(민감정보 제외)
- WebFlux 별도 API/서비스 비교 실험
- 운영 모니터링, 부하 테스트, CI/CD

## 핵심 수용 시나리오
1. A가 워크스페이스를 생성하고 B를 초대한다. B가 수락하면 해당 팀의 프로젝트를 볼 수 있다.
2. A가 프로젝트에 작업을 만들고 B에게 배정한다. B는 허용된 권한으로 상태를 변경한다.
3. 무관한 사용자 C가 프로젝트/작업 ID를 직접 바꿔 API를 호출해도 데이터가 노출되지 않는다.
4. A와 B가 서로 다른 브라우저에서 같은 프로젝트를 열었을 때, 실시간 단계 완료 후 변경 알림이 전달된다.
5. 알림 연결이 끊겨도 저장된 변경 이력을 조회할 수 있다.
6. 동시 변경과 실패 재시도 시 데이터 무결성과 이벤트 중복 처리를 검증한다.

## 비기능 요구사항
- 입력 검증, 표준 오류 응답, 구조화된 로그, 기본 접근성, 페이지네이션
- 서버 측 인가, 데이터 격리, 비밀번호 안전한 해시, 비밀정보 비노출
- 로컬 Docker Compose로 DB 등 의존 서비스를 재현
- 테스트: 핵심 도메인 단위 테스트 + API 통합 테스트 + 주요 UI 흐름

## 미확정 결정

### 첫 업무 모델에서 확정한 정책 — 2026-11-02

- 첫 마이그레이션은 User·Workspace·WorkspaceMembership만 포함한다. 상세 필드와 제약은 [domain-model-v1.md](domain-model-v1.md)를 따른다.
- Phase 1 사용자는 개발용 seed로만 준비하고 회원가입·비밀번호 해시·로그인은 Phase 2에서 구현한다. 개발 사용자 행은 인증이나 로그인 권한을 뜻하지 않는다.
- 이메일은 앞뒤 공백 제거·소문자 정규화 후 중복을 금지한다. 실제 이메일 소유 검증·주소 재사용 정책은 아직 확정하지 않았다.
- 워크스페이스 OWNER는 정확히 1명이다. DB는 부분 UNIQUE로 최대 1명을 제한하고 Service 트랜잭션은 생성·이전·탈퇴 과정에서 최소 1명을 유지해야 한다.

### 개발용 seed 범위 — 2026-11-03

- 예제는 사용자 5명·워크스페이스 2개·멤버십 5개다. A에는 OWNER/ADMIN/MEMBER/VIEWER를 각 1명, B에는 A 미소속 사용자 Eve를 OWNER로 둔다. 상세 값은 [dev-seed.md](dev-seed.md)를 따른다.
- 사용자 선택에 따라 기존 migrator를 재사용하는 별도 로컬 수동 작업으로 준비한다. 앱 계정 권한·인증/인가 정책을 확대하지 않는다.
- 빈 업무 테이블에 최초 삽입하고, 전체 예제와 일치하면 0건 처리한다. 다른 데이터가 있으면 자동 수정·보충·삭제하지 않고 중단한다.
- seed 실행기는 격리 PostgreSQL 검증 후 실제 로컬 Apply·Verify까지 완료했다. 업무 API·인증/인가·역할별 권한 매트릭스는 별도 작업이다.

### 첫 JPA 조회 범위 — 2026-11-03

- 사용자 선택으로 User·Workspace·WorkspaceMembership 엔티티와 조회 전용 Repository 3개/메서드 5개를 구현·격리 검증했다. save/delete·쓰기 Service·업무 API는 포함하지 않는다.
- 앱 권한은 flowdesk schema USAGE와 3개 업무 테이블 SELECT다. DML/DDL·sequence·Flyway 이력 권한은 주지 않는다. 구현·격리 검증 뒤 실제 로컬 GRANT·새 JPA 앱 기동/조회도 별도 요청으로 확인했다.
- DB 읽기 권한은 사용자/팀별 인가를 뜻하지 않는다. 아직 인증 없는 업무 API를 공개하지 않는다. 상세 매핑·조회 계약·권한 검사 전환은 [jpa-read-model-v1.md](jpa-read-model-v1.md)를 따른다.

### 첫 워크스페이스 조회 API 정책 — 2026-11-03

- 사용자 선택으로 내 공간 목록과 소속 공간 상세를 첫 HTTP 조회 범위로 정했다. OWNER/ADMIN/MEMBER/VIEWER 모두 자기 소속 공간의 기본 정보 조회가 가능하다. 다른 멤버의 이메일/목록은 포함하지 않는다.
- 실제 로그인은 Phase 2에 유지한다. 명시적인 로컬 개발 모드에서 서버 설정의 seed 사용자 한 명만 행위자로 사용하고, 클라이언트의 사용자 ID/역할을 신뢰하지 않는다. 기본 실행에서는 업무 API를 활성화하지 않는다. 이 개발 행위자는 실제 인증이 아니다.
- Service가 행위자와 공간의 membership을 확인한다. 상세 조회에서 미소속과 미존재는 동일한 404 응답을 사용한다. 역할별 쓰기/초대/프로젝트 권한은 여전히 후속 결정이다.
- 응답 DTO·페이지/오류 계약·접속 경계·수용 테스트는 [workspace-read-api-v1.md](workspace-read-api-v1.md)를 따른다. 개발 모드의 첫 조회 API·Service 인가를 구현했고 신규 격리 42개/전체 138개 테스트가 통과했다. 이후 실제 로컬에서도 Alice/Eve의 목록·상세 200, 공간 격리/404·입력 400·위조 헤더 무시를 확인했다. Vue 업무 화면·실제 인증은 후속이다.

### Vue 워크스페이스 조회 연결 — 2026-11-03

- 기존 조회 계약으로 목록·선택 상세, 로딩/빈 목록/오류·재시도·이전/다음 페이지를 연결했다. 브라우저가 행위자/역할을 지정하지 않는다. 실제 인증·생성/수정·프로젝트/작업 화면은 후속이다.
- 프론트 테스트 31개·타입 검사/빌드와 실제 Chrome의 Alice/A·Eve/B 목록·상세/오류 복구/모바일 배치를 확인했다. [프론트 실행·흐름](../frontend/README.md)을 따른다.

### 워크스페이스 생성 정책 — 2026-11-03

- 서버가 식별한 기존 사용자 모두 새 공간을 만들 수 있다. 기존 공간의 역할은 새 공간의 생성 자격을 제한하지 않으며 생성자 자신을 OWNER로 등록한다.
- 이름은 앞뒤 공백 제거 후 1~100자로 제한하고 동일 사용자의 동일 이름도 허용한다. POST는 자동 재시도하지 않으며 응답 유실 때 목록으로 확인한다.
- 별도 개발 쓰기 설정·명시적 허용 로컬 Origin·JSON 검사를 요구한다. 앱에는 두 업무 테이블 INSERT만 별도 도구로 부여하는 설계이며 실제 로컬 적용은 구현·격리 검증 후 별도 진행한다.
- 공간과 OWNER membership은 Service의 한 트랜잭션으로 저장한다. [생성 계약](workspace-create-api-v1.md)에 따른 구현·격리 검증(Java 199개/별도 프록시 1개·PowerShell 52개) 이후 실제 로컬 최소 INSERT도 적용했다. Alice의 첫 공간 ID 3과 OWNER 관계를 HTTP 201/목록·상세 200 및 DB에서 확인했다. 현재 데이터는 5/3/6이며 기존 데이터는 유지했다. Vue 생성 화면은 후속이다.

### Vue 생성 폼 연결 — 2026-11-04

- 이름 입력·중복 제출 차단·POST·생성 후 목록 갱신/새 ID 상세 선택을 연결했다. 목록 갱신 실패를 이미 성공한 생성의 실패로 바꾸지 않는다.
- 결과 불명(응답 유실/시간 초과/서버 오류/잘못된 201)은 자동 재전송하지 않고 목록 확인·입력 초기화로 처리한다. 클라이언트가 행위자/역할을 지정하지 않으며 실제 인가는 서버에 둔다. 세부 실행·상태는 [프론트 안내](../frontend/README.md)를 따른다.
- 프론트 58개·타입 검사/빌드와 실제 Chrome/격리 PostgreSQL에서 201/선택·중복 차단·커밋 후 응답 유실/미재전송·405와 모바일 배치를 확인했다. 기존 로컬 데이터는 5/3/6으로 유지했다. 실제 인증·수정/삭제·프로젝트/작업은 후속이다.

### 워크스페이스 이름 수정 정책 — 2026-11-04

- 사용자 선택으로 OWNER·ADMIN만 수정 가능하다. MEMBER·VIEWER는 403, 미소속/미존재는 동일 404다.
- 버전 기반 충돌 검출을 사용한다. 오래된 버전이나 동시 수정 충돌은 409이며 자동 덮어쓰기/재시도하지 않는다.
- [수정 계약](workspace-rename-api-v1.md)에 PATCH/name·version, V2, 응답 version, Service 트랜잭션과 컬럼별 최소 UPDATE 설계를 기록했다. 코드/SQL/도구 구현·격리 검증을 완료했다(Java 245개/별도 Vite 1개·PowerShell 60개, Vue 58개/빌드 통과). 이후 실제 로컬 V2/최소 UPDATE와 새 앱의 수정 200·충돌 409·인가/출처 거부도 확인했다. 데이터 5/3/6을 유지하고 ID 3의 이름/수정 시각/버전만 변경했다. 2026-11-05 Vue 이름 수정 폼·문자열 버전 보존·409 입력 유지/명시적 복구도 연결하고 프론트 101개/빌드·실제 Chrome/격리 DB에서 검증했다.

### 첫 Project 모델·생성/조회 정책 — 2026-11-05

- 사용자 선택으로 별도 ProjectMembership 없이 Workspace 멤버십을 상속하고 네 역할 모두 해당 공간의 모든 프로젝트를 조회한다. 생성은 해당 공간 OWNER·ADMIN만 허용한다.
- 이름은 앞뒤 공백 제거·1~100 코드 포인트이며 같은 공간에서도 중복을 허용한다. 미소속·미존재·부모 공간 불일치는 동일 404다.
- [첫 Project 계약](project-model-and-api-v1.md)에 ID/공간 FK/이름/선택 설명/시간, V3, nested 생성·목록·상세, Service 트랜잭션·인가, 최소 SELECT/INSERT와 기존 도구 전환을 기록했다. 설명 2,000자 일반 텍스트는 구현 기본값이다. 수정/보관/Task/비공개 접근·Vue는 후속이다.
- V3/Project 매핑·생성/조회 API·전용 SELECT/INSERT 권한 도구를 구현했다. 격리 검증 이후 실제 로컬 V3/PROJECT_CREATE와 새 앱의 Project ID 1 생성/조회·역할별 접근 차단도 확인했다. 기존 사용자/공간/멤버십 5/3/6을 유지하고 Project만 한 행 추가했다. [실행 기록](local-setup.md)을 따른다.

### 이후 결정
- 가입/초대 정책, 이메일 검증 여부, 프로젝트별 별도 멤버십을 추가할 조건(첫 범위는 미도입)
- 기본 공간 조회 외의 역할별 권한 매트릭스
- JWT 저장·재발급·로그아웃 정책 및 MFA 적용 시점
- UI 디자인 시스템 및 배포 환경

미확정 사항을 Codex가 임의로 제품 정책으로 확정하지 않는다.

### Workspace 멤버 수명주기 — 정책 확정 (2026-11-13)

- 기존 계정에 한해 7일 만료·1회 사용 초대 코드/링크를 발급한다. 외부 이메일 발송/새 계정 생성은 Phase 1 범위가 아니다. 같은 대상 재발급은 기존 코드를 폐기한다.
- OWNER·ADMIN이 초대 발급/취소, non-OWNER의 ADMIN/MEMBER/VIEWER 역할 변경 및 제거를 한다. OWNER 변경은 현재 OWNER가 이미 속한 멤버에게 소유권을 이전하는 단일 트랜잭션에서만 가능하며 이전 OWNER는 ADMIN이 된다.
- 제거된 사람의 과거 Task 담당자와 상태 이력은 유지한다. 제거 후 재배정은 현재 멤버만 가능하다.
- 초대 코드 저장·API 흐름·동시 변경 잠금·권한은 [멤버십 관리 계약](workspace-membership-management-v1.md)을 따른다. 아직 코드/API/schema 구현이나 실제 로컬 권한 적용은 하지 않았다.

### Project 보관·복구 정책 — 2026-11-23

- 사용자가 복구 가능한 soft archive, Task/이력 보존 및 보관 중 Task/Project 읽기 전용, 활성 목록 기본 제외·모든 현재 Workspace 멤버의 `includeArchived=true` 조회, OWNER·ADMIN 보관/복구와 Project version 기반 409를 확정했다.
- 보관은 `archived_at`만 설정하며 Project 이름/설명 수정과 Task 생성/수정/상태 변경은 409로 차단한다. V10/API/최소 UPDATE, Vue 관리 화면, 실제 로컬 적용/새 앱 HTTP·브라우저 검증을 완료했다. [계약](project-archive-api-v1.md), [구현·실행](project-archive-implementation.md), [ADR-068](decisions.md#adr-068-project-보관-복구-정책-2026-11-23).

### Task 보관·복구 정책 — 2026-11-23

- Task는 hard delete 대신 soft archive한다. Task 상태·내용·담당자·마감일·상태 이력을 보존하고 보관 중 상세/이력은 읽을 수 있다. 기본 목록에서 제외하고 모든 현재 Workspace 멤버에게 `includeArchived=true` 조회를 허용한다.
- 보관/복구는 해당 공간 OWNER·ADMIN만 가능하다. Task 자체 version으로 stale 409/no-op을 처리한다. 보관 Task의 수정/상태 변경은 409 `TASK_ARCHIVED`; 부모 Project가 archived면 Task 보관/복구를 409 `PROJECT_ARCHIVED`로 거부한다.
- 보관/복구를 Task 상태 Activity나 별도 archive audit log에 기록하지 않는다. V11 `tasks.archived_at`과 전용 `archived_at` column UPDATE 권한으로 설계하며 table-wide UPDATE/DELETE 권한은 추가하지 않는다.
- [계약](task-archive-api-v1.md)과 [ADR-069](decisions.md#adr-069-task-보관-복구-정책-2026-11-23)에 따른 V11/API/최소 권한 도구 구현과 격리 PostgreSQL 검증을 완료했다. 현재 로컬 DB 적용과 Vue archive 관리 UI는 별도 단계다.


### Vue Project 조회 연결 — 2026-11-05

- 선택한 공간의 목록/프로젝트 상세, 로딩·빈 목록·오류/재시도·페이지 이동을 연결했다. 공간 전환 시 Project 상태/진행 요청을 종료하고 늦은 응답을 새 공간에 적용하지 않는다.
- 부모/문자열 ID·페이지/응답 필드를 런타임 검사하고 설명은 일반 텍스트로 표시한다. 네 역할 조회 정책과 최종 서버 인가를 유지하며 실제 로그인/Project 생성 폼·수정/작업은 후속이다.
- 프론트 144개·타입 검사/빌드와 실제 Chrome/Vite/읽기 전용 앱으로 조회·공간 전환/취소·오류 복구·모바일을 확인했고 로컬 5/3/6/1을 유지했다. 검증 명령·결과는 [프론트 안내](../frontend/README.md)와 [실행 기록](local-setup.md)을 따른다.


### Vue Project 생성 연결 — 2026-11-05

- 공간 OWNER/ADMIN에만 이름/선택 설명 생성 폼을 표시하고 최종 권한은 기존 Service가 검사한다. 부모는 URL에서 고정하고 사용자/역할/별도 Project membership을 입력받지 않는다.
- 진행 가드·중복 제출 차단, 정규화/길이 검사, 생성 성공 후 목록 0페이지/새 상세 GET을 연결했다. 201 성공을 후속 조회 실패와 구분한다.
- 응답 유실/시간 초과/5xx/잘못된 201은 입력 유지/결과 불명 잠금으로 처리한다. GET 확인만으로 성공/해제를 단정하지 않으며 자동 재전송하지 않는다. 이름 중복 허용 정책은 유지한다.
- 프론트 194개/타입 검사·빌드와 실제 Chrome/Vite/고유 PostgreSQL에서 정상 생성·커밋 후 201 유실/미재전송·VIEWER 미노출/조회·쓰기 off 405·모바일을 확인했다. 실제 로컬 데이터 5/3/6/1과 권한/자격증명은 보존했다. [실행 기록](local-setup.md).


### Project 수정 정책 — 2026-11-05

- 사용자 선택으로 이름·설명을 수정하며 설명 null/빈 값은 삭제한다. 생성의 정규화/길이·중복 이름 허용을 유지하고 공간 이동/보관은 제외한다.
- 해당 공간 OWNER/ADMIN만 수정한다. 올바른 자원의 MEMBER/VIEWER는 403, 미소속·미존재·부모 불일치는 동일 404다.
- Project 자체 버전으로 충돌을 검출한다. 서로 다른 필드 수정도 한 Project의 동시 변경이면 409이며 자동 병합/재전송하지 않는다. Workspace 버전과는 독립적이다.
- [수정 계약](project-update-api-v1.md)에 name/description/version PATCH, V4, 상세/생성 응답 버전(목록 요약 유지), 트랜잭션/no-op·권한 도구·Vue 복구/격리 수용 기준을 기록했다. 구현·테스트·실제 로컬 적용은 후속이다.


### Project 수정 구현 — 2026-11-05

확정한 이름/설명·OWNER/ADMIN·Project 버전 충돌 PATCH와 V4/최소 UPDATE 전용 도구를 구현했다. 설명 삭제/no-op·404/403/409 순서와 commit까지의 낙관적 검증을 적용했다. 상세/생성 DTO version 문자열을 Vue가 보존하고 생성 "0"을 확인하며 목록 요약은 유지한다. 실행 검증은 [작업 기록](local-setup.md)에 구분하며 실제 로컬 적용·Vue 수정 폼은 별도 단계다.


### Vue Project 수정 연결 — 2026-11-07

OWNER/ADMIN의 Project 이름·설명 편집/문자열 버전 PATCH를 연결했다. 설명 삭제/null·no-op·두 필드 충돌 정책을 유지한다. 409/결과 불명은 입력/기준 보존·저장 잠금이며 최신 Project/역할 확인→두 필드 비교→명시적 기준 갱신→별도 수동 저장을 요구한다. 성공한 DTO로 현재 상세와 같은 ID 목록만 갱신하고 자동 재전송하지 않는다. 실제 명령/검증은 [실행 기록](local-setup.md)을 따른다.


### 첫 Task 모델·생성/조회 정책 — 2026-11-07

- 사용자 선택으로 첫 범위는 제목/설명/상태/우선순위의 생성·프로젝트별 목록·상세다. 담당자/마감일/수정·상태 변경/칸반/댓글·이력/보관은 후속이다.
- Workspace 멤버십을 상속하고 OWNER/ADMIN/MEMBER가 Task를 생성하며 VIEWER를 포함한 네 역할 모두 조회한다. MEMBER의 Project 생성/수정은 여전히 불가하다. 미소속·미존재·URL 부모 불일치는 동일 404이며 올바른 부모의 VIEWER 생성은 403이다.
- 생성 상태는 서버 TODO 고정, 우선순위 LOW/MEDIUM/HIGH 선택·생략 시 MEDIUM이다. status를 클라이언트가 보내면 400이며 초기 상태 선택을 제공하지 않는다.
- 구현 기본값은 제목 1~200 코드 포인트/중복 허용, 설명 선택 일반 텍스트/최대 2,000 코드 포인트다. [Task 계약](task-model-and-api-v1.md)에 V5/tasks·중첩 부모 검사·DTO/트랜잭션·Task POST 필터 확장·최소 SELECT/INSERT·기존 도구 전환/격리 수용 기준을 기록했다. 이번에는 문서만 확정했으며 구현·실제 로컬 적용은 후속이다.


### Task 생성/조회 구현 — 2026-11-07

확정한 제목/설명/상태/우선순위·세 역할 생성/네 역할 조회·TODO 고정/기본 MEDIUM을 V5/Task JPA·중첩 API·전용 SELECT/INSERT 도구로 구현했다. Task POST Origin 필터/부모+자원 확인/strict 입력/부모 버전 불변과 기존 도구 호환성을 검증한다. 실제 실행 결과는 [작업 기록](local-setup.md)에 구분하며 실제 로컬 V5/권한 적용과 Vue Task는 별도다.


### Vue Task 목록·상세 조회 연결 — 2026-11-08

선택한 Project의 Task 제목/상태/우선순위 목록과 설명 포함 상세·로딩/빈 목록/오류·재시도·페이지를 연결했다. Workspace/Project 전환과 페이지/새로고침 때 이전 선택/요청을 종료하며 늦은 응답을 적용하지 않는다. 네 역할 조회와 서버 인가를 유지하고 생성/수정/상태 변경은 후속이다. 프론트 297개/빌드·실제 Chrome GET 조회와 로컬 5/3/6/1/1 보존을 확인했다. [실행 기록](local-setup.md)을 따른다.


### Vue Task 생성 폼·POST 연결 — 2026-11-08

OWNER/ADMIN/MEMBER의 Task 제목/선택 설명/우선순위 폼과 POST·201 후 목록/새 상세 조회를 연결했다. 기본 MEDIUM/서버 TODO·중복 제목 허용을 유지하며 VIEWER는 조회만 제공한다. MEMBER의 Project 관리 권한은 넓히지 않는다. 중복 제출 차단·정규화/길이 검사·입력 유지/결과 불명 잠금·GET 확인/명시적 초기화를 구현했다. 프론트 355개/빌드·실제 격리 브라우저 검증과 로컬 보존은 [실행 기록](local-setup.md)을 따른다. 수정/상태 변경·담당자/마감일·칸반·댓글은 후속이다.


### Task 수정 정책 확정 — 2026-11-08

사용자 선택으로 제목·설명·우선순위(설명 null/빈 값 삭제), 해당 공간 OWNER/ADMIN/MEMBER의 모든 Task 수정, Task 자체 버전/409를 확정했다. VIEWER 403, 미소속/미존재/부모 불일치 동일 404이며 상태 변경·담당자/날짜·이동/보관은 후속이다. [수정 계약](task-update-api-v1.md)에 필수 네 필드 PATCH·no-op/오류 순서·V6/최소 UPDATE 다섯 컬럼·기존 응답/Vue 전환·격리 기준을 기록했다. 이번은 문서만 변경했으며 코드/V6/로컬 적용은 후속이다.


### Task 수정 구현 — 2026-11-08

확정한 제목/설명/우선순위·OWNER/ADMIN/MEMBER·Task version/409 PATCH와 V6/최소 UPDATE 도구를 구현했다. 부모/Task→역할→버전 순서·설명 삭제/no-op/commit 충돌·상태/부모 불변을 유지한다. 상세/생성의 문자열 버전과 기존 Vue 파서/생성 초기 0 검사도 전환하며 목록 요약은 유지한다. 실제 검증 결과는 [작업 기록](local-setup.md)에 구분한다. 실제 로컬 V6/GRANT/수정과 Vue 편집 폼은 별도다.


### 실제 로컬 Task 수정 확인 — 2026-11-08

V6/최소 Task UPDATE 적용과 새 앱의 MEMBER 실제 수정·409/no-op·OWNER/ADMIN 허용·VIEWER/미소속·부모/출처·쓰기 off를 확인했다. Task 1은 version 1/HIGH/TODO이고 데이터 5/3/6/1/1·부모/다른 행은 유지했다. [실행 기록](local-setup.md)을 따른다. 기존 Vue 응답 파서는 준비됐으며 수정 폼/409 복구 연결은 후속이다.


### Vue Task 수정 연결 — 2026-11-08

세 역할의 제목/설명/우선순위 편집과 문자열 version PATCH를 연결했다. 409/결과 불명은 입력/기준 보존·저장 잠금, 최신 Task/역할 GET·세 필드 비교/명시적 기준 갱신·별도 수동 저장으로 복구한다. 정상 200은 현재 상세/같은 ID 목록만 갱신하며 상태 변경은 포함하지 않는다. 프론트 415개/빌드·실제 격리 Chrome/DB와 로컬 보존 결과는 [실행 기록](local-setup.md)을 따른다.


### Task 상태 변경 정책 확정 — 2026-11-08

사용자는 TODO/IN_PROGRESS/DONE 간 모든 전이(바로 완료·재개 포함), 해당 공간 OWNER/ADMIN/MEMBER의 모든 Task 상태 변경, 실제 전환 시 최소 Activity 동시 저장을 선택했다. VIEWER 403·미소속/미존재/부모 불일치 동일 404, 기존 Task version/409와 현재 버전 같은 상태 no-op을 유지한다. [상태 계약](task-status-api-v1.md)에 두 필드 PATCH/공유 버전·V7/이력 원자성·최소 권한/후속 검증을 기록했다. 이력 조회 API/UI·다른 종류 이력은 후속이며 이번에는 문서만 변경했다.

### Task 상태 변경 구현 — 2026-11-10

확정된 모든 전이·세 역할·공유 Task 버전과 상태/최소 이력 동일 트랜잭션을 구현했다. V7과 컬럼별 최소 권한 전용 도구를 추가했으며 상세 동작/검증은 [구현 기록](task-status-implementation.md)을 따른다. 실제 로컬 V7/권한 적용·Vue 상태 UI·Activity 조회/다른 이력은 후속이다.

### 실제 로컬 Task 상태/이력 확인 — 2026-11-10

로컬 V7/최소 상태 권한과 새 JAR의 실제 HTTP를 확인했다. MEMBER Carol의 단일 TODO→IN_PROGRESS/version 1→2 전환과 정확한 이력 1건, OWNER/ADMIN no-op 허용·VIEWER 403·미소속/부모 404·공유 버전 409·쓰기 off 405를 확인했다. 기존 데이터/일반 필드는 보존했고 Vue 상태 UI는 후속이다. [실행 기록](local-setup.md).

### Vue Task 상태 변경 연결 — 2026-11-10

세 역할의 상태 선택/전용 PATCH를 연결했다. 일반 편집과 공유하는 Task 버전은 각 폼의 시작 기준으로 보존하며 서로의 입력/기준을 자동 덮어쓰지 않는다. 409/결과 불명은 선택 유지·잠금·최신 Task/역할 비교·명시적 기준·별도 수동 저장으로 처리한다. 프론트 479개/빌드·실제 Chrome/격리 DB 및 로컬 보존을 확인했다. [구현 기록](task-status-ui.md). Activity 조회·담당자/마감일·전체 Task MVP는 후속이다.

### Task 상태 변경 이력 조회 정책 확정 — 2026-11-10

현재 Workspace의 OWNER/ADMIN/MEMBER/VIEWER 모두 Task 전체 과거 상태 이력을 조회하도록 확정했다. 가입 전/현재 탈퇴한 행위자 기록도 포함하며 조회자 미소속·미존재·URL 부모 불일치는 같은 404다. 행위자는 ID와 현재 표시 이름만 제공하고 이메일은 제외한다. 최신 Task 버전부터 beforeVersion 커서로 조회하며 기본 20/최대 100건이다. [확정 계약](task-status-history-read-api-v1.md)에 DTO/인가·컬럼 SELECT·기동/도구 전환·V7 재사용과 격리 기준을 기록했다. 구현·실제 로컬 권한 적용·Vue 이력 화면은 후속이다.

### Task 상태 이력 조회 구현 — 2026-11-10

확정한 네 역할/전체 과거 이력·현재 이름/이메일 제외·최신순 버전 커서의 GET을 구현했다. 엄격한 입력과 현재 소속/두 부모 검사·동일 404, 별도 컬럼 SELECT 도구·이전 관리자 도구 보존/기동 전환을 포함한다. 실제 검증은 [구현 기록](task-status-history-implementation.md)을 따른다. 실제 로컬은 V7/TASK_STATUS_CHANGE·Task version 2/이력 1건을 유지하며 SELECT 적용·Vue 이력 화면·전체 Task MVP/Phase 1은 완료로 표시하지 않는다.

### 실제 로컬 상태 이력 조회 확인 — 2026-11-10

실제 로컬에서 전용 TASK_STATUS_HISTORY_READ 실행기로 audit 7개 컬럼 SELECT를 적용하고 새 dev-actor JAR을 읽기 모드로 기동해 검증했다. Actuator UP, Carol의 Task 1 이력 1건(버전 2, TODO→IN_PROGRESS, 정확한 UTC 마이크로초), envelope/item/actor 필드, 마지막 커서의 빈 페이지와 strict size 400을 확인했다. table-wide SELECT는 없고 기존 audit INSERT 6개/Task UPDATE·다른 ACL·데이터·Flyway V1~V7·시퀀스는 보존했다. Vue 이력 화면과 실제 로그인은 후속이다. 다음 한 작업은 **Vue Task 상태 이력 조회 화면 연결**이다. [실제 실행 결과](local-setup.md).
### Task 상태 이력 UI 구현 — 2026-11-10

확정된 GET을 Task 상세에 접힌 이력 패널로 연결했다. 사용자가 펼치면 최신 20건을 읽고 version cursor로 이전 기록을 덧붙이며, 초기/이전 페이지 오류·빈 이력·재시도·새로고침을 구분한다. 이력 요청은 Task 선택만으로 자동 발생하지 않으며 status 변경 후 펼쳐져 있으면 최신 페이지를 다시 읽는다. 현재 행위자 표시 이름·이전→이후 상태·시각·Task 버전을 보여 주며 이메일은 노출하지 않는다. API client가 strict response/cursor/UTC를 검사하고 Task 전환/해제 시 요청을 취소한다. [구현·검증](task-status-history-ui.md). 실제 로그인·전체 Task MVP는 별도 범위다.

### Task 담당자·마감일 정책 확정 — 2026-11-10

- 담당자는 한 명 또는 미지정이며, 지정 당시 해당 Workspace의 멤버만 대상이다. 네 역할 모두 담당자가 될 수 있다. 멤버 제거 후 기존 할당은 유지하고, 이후 재지정은 현재 멤버로 제한한다.
- 마감일은 시간대 없는 `YYYY-MM-DD` 달력 날짜이며 null을 허용한다. 생성·수정 요청과 목록·상세 응답에 포함하고 기존 OWNER·ADMIN·MEMBER 쓰기/네 역할 읽기 정책을 유지한다.
- 담당자·마감일 변경 Activity, 필터/정렬, 알림은 미포함. 전체 계약과 구현 영향은 [Task 담당자·마감일 결정](task-assignee-due-date-v1.md)을 따른다.

### Vue Task 담당자·마감일 입력 — 2026-11-11

Task 생성·수정 화면에서 담당자 ID와 날짜를 설정·해제할 수 있다. 멤버 조회 API가 없으므로 ID 입력을 사용하고, Workspace membership은 서버가 검증한다. 수정 409에서는 초안을 보존하고 새 버전으로 명시적 재저장을 허용한다. 구현 및 검증은 [구현 기록](task-assignee-due-date-implementation.md)을 따른다.

### Workspace 현재 멤버 목록 계약 확정 — 2026-11-11

담당자 후보 조회를 위해 `GET /api/workspaces/{workspaceId}/members`를 정의한다. 현재 소속된 네 역할은 조회 가능하며 ID·표시 이름·역할만 노출한다. 이메일은 제외한다. 초대·수락·역할 변경·제거는 후속 정책으로 둔다. 세부 계약은 [멤버 목록 API 문서](workspace-member-list-api-v1.md)를 따른다.

### Workspace 멤버 목록 API/권한 구현 — 2026-11-12

dev-actor GET, 현재 membership 검사, ID·표시 이름·역할 projection, 페이지 응답을 구현했다. 기존 users/workspaces/workspace_memberships table-wide SELECT는 현재 필요한 exact 16-column SELECT로 전환하는 전용 도구로 분리했고 격리 PostgreSQL에서 검증했다. 로컬 적용과 Vue 선택 UI는 아직 후속이다. [구현 기록](workspace-member-list-implementation.md).

### Vue Task 담당자 선택 연결 — 2026-11-13

Task 생성·수정 폼은 담당자 ID 직접 입력 대신 현재 Workspace의 멤버 이름·역할·ID 목록을 선택한다. 목록은 컨트롤에 포커스할 때 요청하고, 실패하면 명시적 재시도를 제공한다. 이미 할당된 과거 멤버는 수정 화면에서 보존하거나 해제할 수 있지만 신규 후보로 노출하지 않는다. [구현 기록](workspace-member-list-implementation.md).

### Workspace 초대 대기 목록 — 2026-11-20

사용자 선택에 따라 Workspace OWNER·ADMIN만 초대 대기 목록을 조회한다. DB status PENDING인 초대 전체를 포함하며 만료된 항목은 응답의 만료 표시로 구분한다. 대상 표시 이름·이메일, 역할, 생성/만료 시각을 제공하고 코드 원문·digest·내부 target user ID는 제외한다. ACCEPTED/REVOKED 이력은 포함하지 않는다. 상세 경로·페이지·오류·수용 기준은 [Workspace 초대 목록 API 계약](workspace-invitation-list-api-v1.md)을 따른다. dev-actor API, 격리 PostgreSQL, Vue 표시·취소를 구현하고 Vue 507개 테스트와 빌드를 통과했다. 로컬 Vite proxy 실제 HTTP GET/POST/DELETE/GET도 확인했다. Chrome DOM 확인은 후속이다. [구현 기록](workspace-invitation-list-implementation.md).
