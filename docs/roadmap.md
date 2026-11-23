# 단계별 로드맵

## Phase 0 — 환경/설계 확정
- [x] 개발 환경 버전 확인(JDK, Node.js, Docker, Gradle)
  - 2026-11-02: JDK 21·Node 24/npm 설치 및 실행, Docker Linux 엔진 연결 확인. Gradle Wrapper 검증은 백엔드 생성 시 진행. 명령과 결과는 `local-setup.md` 참고.
  - 2026-11-02: Gradle Wrapper 8.14.3으로 Boot 3.5.16 빌드 및 HTTP 통합 테스트 통과.
- [ ] 주요 미확정 제품 정책 검토
  - 첫 모델의 seed/인증 단계·이메일 정규화·단일 OWNER 정책은 사용자 확인 완료. 권한 매트릭스·초대·삭제 등은 해당 기능 전에 계속 검토한다.
- [x] 백엔드/프론트엔드 초기화 계획 제시 및 승인
  - 백엔드 최소 뼈대 및 프론트엔드 health 화면 구현 승인 후 완료.
- [x] Docker Compose PostgreSQL 기동 및 연결 검증
  - PostgreSQL 17.11 healthy, TCP 비밀번호 인증을 통한 SELECT 1 성공 및 잘못된 비밀번호 거부, Windows 127.0.0.1:5432 TCP 연결 확인. Spring DataSource 연동은 후속 작업.
  - 후속 연결 완료: 최소 권한 flowdesk_app으로 Spring DataSource 연결, Actuator DB 정상·장애·복구 및 DDL 거부 검증. 백엔드 테스트 총 7개 통과, 재시작한 IDE 8080 연결도 확인.
- [x] 첫 health API + Vue 화면에서 호출 확인
  - 백엔드 `GET /api/health` 구현. HTTP 통합 테스트 2개 및 브라우저 응답 확인. Vue 화면 호출은 미완료.
  - Actuator `/actuator/health` 비교 추가. 통합 테스트 총 4개 통과, 패키징 JAR의 두 health 경로 200 응답과 metrics 404 확인.
  - Vue 화면에서 Vite /api 프록시를 통해 Spring 호출 확인. 프론트 테스트 5개·타입 검사·빌드 통과. 실제 Chrome에서 UP 렌더링 확인. DB 연결은 아직 미완료.
**완료 기준:** 재현 가능한 실행 방법과 실제 테스트 결과 문서화.

## Phase 1 — 도메인과 CRUD
- [ ] User/Workspace/Membership/Project/Task 데이터 모델 확정
  - 2026-11-05: 첫 Project 모델/생성·조회 범위 확정. Workspace 멤버십 상속/네 역할 조회, OWNER·ADMIN 생성, 공간 내 이름 중복 허용. project-model-and-api-v1.md에 V3/필드/중첩 API/최소 SELECT·INSERT·격리 수용 기준을 기록했다. 이후 구현/로컬 적용도 완료했으나 Task 모델은 후속이므로 전체 항목은 열어둔다.
  - User·Workspace·WorkspaceMembership 3개 모델과 V1 범위 확정: domain-model-v1.md. Project·Task는 후속이며 전체 항목 완료로 표시하지 않는다.
- [ ] Flyway 스키마 및 JPA 엔티티/Repository
  - 2026-11-05: V3/Project 매핑·생성/조회 API·최소 SELECT/INSERT 도구를 구현하고 Java 합산 298개(영향 구간 재검증 포함)/Vite 1개·PowerShell 69개·Vue 101개와 빌드를 확인했다. 초기 실패/수정 및 명령은 local-setup.md의 Project 항목에 기록한다. 이후 실제 로컬 V3·권한 적용도 완료했다(문서 하단 실행 기록). Vue Project/Task는 후속이다.
  - V1 SQL 작성과 Flyway 테스트 의존성 추가 완료. 격리 PostgreSQL에서 V1 검증 24개 통과, 기존 테스트 포함 총 31개 통과. 로컬 DB 적용·개발 seed·JPA·업무 API는 미완료.
  - 2026-11-03: 전용 migration source set·Gradle JavaExec·PowerShell 프로비저닝/실행기 구현 및 실제 래퍼 경로 격리 검증 25개 통과. 전체 build와 JUnit 31개도 통과했다. infra/.env.migrator → 전용 자식 환경으로 전달하며 명령은 local-migration.md 참고. 실제 로컬 적용은 후속.
  - 이후 사용자 요청으로 로컬 flowdesk DB 적용 완료: migrator 계정·스키마 준비, V1 1건 적용/Validate 성공/재실행 0건, 소유권·빈 데이터·앱 접근 거부 및 임시 앱 Actuator UP 확인. 개발 seed·JPA·업무 API는 미완료이므로 전체 항목은 열어둔다.
  - 개발 seed 데이터·실행 범위 확정: 사용자 5명/공간 2개/멤버십 5개, migrator 재사용·로컬 수동 실행, 빈 테이블 최초 삽입/전체 일치 0건/다른 데이터면 중단. dev-seed.md 참고. seed 구현·격리 검증·로컬 삽입은 미실행이다.
  - 이후 seed Apply/Verify 실행기 구현과 Testcontainers의 실제 Java main 테스트 31개 통과. 기존 포함 전체 JUnit 62개와 build 성공, PowerShell migration 회귀+seed 전체 경로 검증 33개도 통과했다. 실제 로컬 seed 삽입·JPA/API는 미완료다. 명령·결과는 local-setup.md 참고.
  - 사용자 요청으로 실제 로컬 seed 적용 완료: Apply 12행(5명/2개 공간/5개 멤버십), Verify 성공, 반복 Apply 0건. 데이터·ID·timestamps와 V1 이력·앱 권한 보존 확인. JPA 엔티티/Repository·업무 API는 아직 없어 전체 항목은 열어둔다.
  - 첫 JPA 범위 설계 확정: 엔티티 3개/역할 enum/조회 Repository 3개·메서드 5개, 앱 schema USAGE+업무 SELECT 최소 권한 목표. jpa-read-model-v1.md 참고. JPA 구현·읽기 권한 도구/검사 전환·격리 검증·실제 로컬 GRANT는 후속이다.
  - 첫 JPA 조회/읽기 권한 도구 구현·격리 검증 완료: 전체 JUnit 96개, PowerShell migration/seed/권한 경로 41개 통과. 실제 로컬 seed 5/2/5와 CONNECT-only 권한은 유지했고, 로컬 READ 적용·JPA 앱 기동 검증은 별도 작업이다. Project/Task 매핑·쓰기/업무 API는 미완료다.
  - 이후 사용자 요청으로 실제 로컬 READ 적용·반복 성공, 새 빌드 클래스의 JPA 앱 기동/Repository 5개 조회/쓰기 거부/health 200 UP 확인. migration/seed 재실행과 데이터·이력·자격증명 보존도 확인했다. 전체 Phase 1은 HTTP 업무 API·인가·쓰기 등이 남아 열어둔다.
- [ ] 작업 API 및 DTO/Validation/예외 처리
  - 2026-11-04 실제 로컬 V2/최소 컬럼 UPDATE 적용·반복 성공, 새 JAR의 OWNER 수정 200/버전 0→1, stale 409, ADMIN no-op 200/VIEWER 403/미소속·미존재 404/쓰기 off 405·health UP을 확인했다. 데이터 5/3/6·기존 seed/멤버십/시퀀스/V1/자격증명을 유지하고 비-seed 공간 ID 3의 이름/updated_at/version만 변경했다. 검증 앱은 종료했고 Vue 수정 폼이 다음이다.
  - 2026-11-04 이름 수정 구현·격리 검증 완료: PATCH/OWNER·ADMIN/버전 409, V2·no-op 검증·최소 컬럼 UPDATE/기존 도구 호환. Java 245개/실제 Vite POST·PATCH 1개·PowerShell 60개와 Vue 58개/빌드 통과. 실제 로컬은 V1/5·3·6/UPDATE 없음으로 유지했고 V2/권한 적용·새 앱 기동·수정 확인은 다음 단계다.
  - 2026-11-04 이름 수정 설계 확정: OWNER·ADMIN 허용, 미소속/미존재 404, 버전 충돌 409. workspace-rename-api-v1.md에 PATCH·V2·응답 version·최소 컬럼 UPDATE·격리 수용 기준을 기록했다. 구현·검증·로컬 적용은 후속이다.
  - 생성 로컬 적용 완료: 전용 최소 INSERT 최초/반복 적용, Alice의 새 공간 ID 3/OWNER·201/목록·상세 확인, Eve 차단/400·403/health UP을 검증했다. 데이터는 기존 5/2/5에 신규 공간/멤버십 각각 1행만 추가해 5/3/6이다. seed Verify는 추가 업무 데이터로 예상 mismatch다. Vue 생성 폼은 후속이다.
  - 생성 API·원자적 저장/롤백·개발 쓰기/Origin·최소 INSERT 도구 구현/격리 검증 완료. 신규 Java 61개와 기존 포함 전체 199개, 별도 실제 Vite 프록시 1개 및 PowerShell 전체 경로 52개 통과. 실제 로컬 쓰기 GRANT/생성·Vue 생성 폼은 후속이다.
  - 2026-11-03 생성 설계 확정: 모든 기존 사용자 생성 가능, 앞뒤 공백 제거/1~100 코드 포인트·이름 중복 허용, 생성자 OWNER. 별도 개발 쓰기 설정/Origin 경계·두 행의 단일 트랜잭션·최소 INSERT/권한 도구 전환을 workspace-create-api-v1.md에 기록했다. POST/쓰기 도구 구현·격리 검증·실제 로컬 GRANT·Vue 생성은 아직 미실행이다.
  - 2026-11-03: 첫 워크스페이스 목록/상세 계약 확정. 네 역할의 소속 공간 기본 조회, 명시적 개발 모드의 서버 지정 행위자, 미소속/미존재 동일 404를 사용자와 정했다. workspace-read-api-v1.md 참고. Controller/DTO/Service/개발 경계·격리 API 검증은 아직 미구현이다.
  - 이후 개발 모드의 Controller/DTO/조회 Service·소속 검사·접속 경계/설정 검증·안전한 오류 처리를 구현했다. 신규 Testcontainers PostgreSQL/HTTP 검증 42개와 기존 포함 전체 build/JUnit 138개가 통과했다. 실제 로컬 HTTP 적용·Vue 업무 화면·쓰기/작업 API는 후속이므로 전체 항목은 열어둔다.
  - 이후 별도 요청으로 실제 로컬 JAR를 18081 개발 모드에 Alice/Eve로 각각 실행해 목록/상세 200·팀 간 격리/동일 404·입력 400·헤더 위조 무시·health UP을 확인했다. 데이터/이력/ACL/시퀀스·자격증명/V1·부모 환경은 유지했고 임시 서버는 종료했다. Vue 업무 화면·쓰기/작업 API는 후속이다.
- [ ] Vue 라우팅, 프로젝트/작업 목록, 생성/수정 화면
  - 2026-11-05: 워크스페이스 이름 수정 폼·version 문자열 검증/보존·409 입력 유지/최신 GET·명시적 편집 기준 갱신을 구현했다. 프론트 101개/타입 검사·빌드와 실제 Chrome/격리 PostgreSQL의 단일 PATCH·충돌 복구·커밋 후 응답 유실/미재전송·VIEWER 미노출·405/모바일을 확인했다. 기존 로컬 5/3/6·ID 3 version 1을 유지했다. Project/Task 모델·API/화면·라우팅은 후속이다.
  - 2026-11-04: 워크스페이스 생성 폼·POST·성공 후 목록/새 상세 선택, 중복 제출 차단, 결과 불명 잠금/목록 확인·입력 초기화를 구현했다. 프론트 58개/타입 검사·빌드 통과. 프로젝트/작업·수정/삭제·라우팅은 후속이다.
    - 실제 Chrome/고유 PostgreSQL에서 두 번 제출 시 한 POST, 생성 후 상세, 커밋 후 201 유실·GET 확인 동안 미재전송, 쓰기 비활성 405, 모바일 배치를 검증했다. 격리 데이터는 5/4/7이며 로컬 5/3/6은 유지했다. 백엔드 회귀는 제품 코드 변경이 없어 반복하지 않았다.
  - 2026-11-03: 첫 워크스페이스 목록·선택 상세를 기존 Vite 프록시로 연결했다. 로딩/빈 목록/오류·재시도·페이지·취소/늦은 응답 방지를 구현했다. 한 화면이므로 Router/Pinia는 도입하지 않았다. 프로젝트/작업·생성/수정·URL 라우팅은 후속이다.
- [ ] API/프론트 기본 테스트
  - 첫 조회 화면은 프론트 31개 테스트·타입 검사/빌드 통과. 실제 Chrome에서 Alice/A·Eve/B 목록/상세, Vite 프록시 200, 요청 차단 오류/재시도 복구, 모바일 가로 넘침 없음을 확인했다. 백엔드/DB 변경이 없어 JUnit 138개는 반복하지 않았다.
**완료 기준:** 단일 사용자 로컬 CRUD 시나리오 통과.

## Phase 2 — 인증/인가
- [ ] Security Filter Chain과 인증 구조 학습
- [ ] 가입/로그인/비밀번호 해시
- [ ] JWT Access/Refresh Token 정책 결정·구현
- [ ] 프로젝트/워크스페이스 역할별 접근 제어와 IDOR 테스트
- [ ] Redis 도입 여부와 Refresh 상태 정책 검증
- [ ] TOTP MFA 등록·챌린지·복구
**완료 기준:** 다중 사용자 권한/토큰 만료/재발급/로그아웃/MFA 시나리오 테스트.

## Phase 3 — 협업 UX
- [ ] 칸반, 담당자 배정, 필터, 댓글, Activity
- [ ] 멤버 초대/수락, 권한 관리 UI
- [ ] 동시 수정 정책과 충돌 처리
**완료 기준:** A/B/C 사용자 협업 및 권한 시나리오 통과.

## Phase 4 — 실시간 이벤트
- [ ] WebSocket 연결/인증/구독 권한
- [ ] 알림 영속화 및 재접속 시 복원
- [ ] Kafka 도입과 이벤트 모델
- [ ] Outbox/중복 소비/실패 재처리 실험
**완료 기준:** 다중 브라우저 동기화 및 장애 복구 시나리오 통과.

## Phase 5 — 성능/운영
- [ ] Redis 캐시 전후 비교, 캐시 무효화
- [ ] AOP와 관측성(로그, 메트릭, 추적)
- [ ] 동시성/부하/장애 테스트
- [ ] Docker 기반 전체 실행과 CI
**완료 기준:** 측정 결과 및 개선 근거 문서화.

## Phase 6 — WebFlux 비교
- [ ] MVC와 WebFlux의 요청/스레드 모델 비교
- [ ] 논블로킹 외부 I/O 또는 R2DBC 기반 실험
- [ ] 부하/지연/리소스 사용 비교
**완료 기준:** 언제 WebFlux를 선택할지 설명 가능한 비교 보고서.

## 공통 Done 정의
기능 시나리오 충족 + 자동/수동 테스트 결과 + 요청 처리 흐름 설명 + 보안/장애 검토 + 관련 문서 갱신.


### 2026-11-05 실제 로컬 Project 적용 기록

V3 1건/Validate/재실행 0건, 최소 SELECT/INSERT 최초·반복 적용, 새 JAR에서 Workspace 1의 Project ID 1 생성 201/목록·상세 200을 확인했다. 네 역할 상속 조회, VIEWER 생성 403, 미소속·미존재·부모 불일치 404, Origin 403/필드 400, 쓰기 off 405·Actuator UP을 확인했다. 기존 5/3/6·V1/V2/ACL/시퀀스/설정 파일을 보존하고 검증 앱은 종료했다. 이번에는 앱/SQL/도구 소스 변경이 없어 격리 298개/Vite 1개·PowerShell 69개·Vue 101개는 반복하지 않았다. 다음은 Vue Project 목록·상세 조회 연결이며 전체 Phase 1은 계속 열어둔다.


### 2026-11-05 Vue Project 목록·상세

ProjectBrowser/projects.ts를 기존 Workspace 화면에 연결했다. 목록·상세/설명·로딩·빈 목록·오류/재시도·페이지와 공간 변경 시 요청/선택 종료를 구현했다. 기존 fetch/지역 상태를 유지하고 Router/Pinia·인증·DB·새 의존성은 추가하지 않았다. 프론트 144개/타입 검사·빌드와 실제 Chrome/Vite/읽기 전용 로컬 앱에서 부모 전환/늦은 응답·오류 복구·VIEWER/빈 공간·모바일을 확인했다. GET만 사용했고 로컬 5/3/6/1·전체 스냅샷/보호 파일 7개를 보존했다. 자세한 명령은 local-setup.md에 기록한다. Project 생성 폼/POST·Task는 후속이며 Phase 1 전체 항목은 계속 열어둔다.


### 2026-11-05 Vue Project 생성 폼·POST

ProjectCreateForm·createProject를 기존 공간별 목록/상세에 연결했다. OWNER/ADMIN 표시·이름/설명 정규화/검증·중복 제출 차단·201 후 목록/새 상세, 확정 거부와 결과 불명/GET 확인·명시적 초기화를 구현했다. 프론트 194개·타입 검사/빌드와 실제 Chrome/Vite/고유 PostgreSQL에서 생성·커밋된 응답 유실/미재전송·VIEWER/405·모바일을 확인했다. 로컬 5/3/6/1·전체 스냅샷/보호 파일 7개는 유지했고 임시 환경은 정리했다. Project 수정/보관·Task는 후속이며 전체 Phase 1은 열어둔다. 다음은 Project 수정 API의 권한·충돌 정책 확정이다.


### 2026-11-05 Project 수정 정책 확정

사용자 선택으로 이름·설명(설명 null/빈 값 삭제), 해당 공간 OWNER/ADMIN, Project 자체 버전/409를 확정했다. 서로 다른 필드도 한 행의 충돌로 처리하고 공간 이동/보관은 제외한다. project-update-api-v1.md에 PATCH 필수 세 필드·오류 순서/no-op, V4·PROJECT_UPDATE 네 컬럼 GRANT, 기존 도구·응답/프론트 전환과 격리 수용 기준을 기록했다. 이번에는 문서만 변경했다. 다음은 Project 수정 API·V4·최소 UPDATE 권한 도구 구현과 격리 PostgreSQL 검증이며 Phase 1 전체는 열어둔다.


### 2026-11-05 Project 수정 API·V4·권한 도구 구현

이름/설명 PATCH·역할/부모/버전 검사·no-op/commit 충돌, V4·PROJECT_UPDATE 네 컬럼 도구/기존 실행기 보존을 구현했다. Vue 상세/생성 version 보존·기존 테스트 fixture도 전환했다. 실제 격리 실행 결과는 local-setup.md에 기록한다. 로컬 V4/권한 적용·수정 확인과 Vue 수정 폼은 별도이며 Phase 1 전체는 계속 열어둔다.

2026-11-06 최종 검증: 백엔드 340개·실제 Vite 프록시 1개·프론트 203개/빌드·PowerShell 전체 80개 통과. 격리 자원/임시 자격증명을 정리하고 실제 로컬 V3/PROJECT_CREATE·5/3/6/1 및 보호 파일 6개를 보존했다. 다음 한 작업은 **로컬 V4·최소 Project UPDATE 적용과 새 앱의 수정 확인**이다.


### 2026-11-07 실제 로컬 Project 수정 확인

V4 1건/Validate·반복 0건, 최소 Project UPDATE 최초·반복 적용, 새 앱의 OWNER 수정 200/version 1·stale 409·ADMIN no-op 200·MEMBER/VIEWER 403·미소속/부모 불일치 404·Origin/입력·쓰기 off 405·Actuator UP을 확인했다. 5/3/6/1을 유지하고 Project 1 네 필드만 변경했다. 기존 이력/다른 데이터/시퀀스/보호 파일 7개를 보존하고 검증 앱을 종료했다. 제품 소스 변경이 없어 앞선 전체 회귀는 반복하지 않았다. 다음 한 작업은 **Vue Project 이름·설명 수정 폼·버전 보존·409 충돌 안내 연결**이며 Phase 1 전체는 열어둔다.


### 2026-11-07 Vue Project 이름·설명 수정

ProjectUpdateForm/updateProject를 상세에 연결하고 시작 두 필드/문자열 버전·409 입력 유지/저장 잠금·두 필드 비교/최신 역할 확인·명시적 기준 갱신·수동 저장을 구현했다. 결과 불명은 GET 내용이 같아도 성공 처리/자동 재전송하지 않는다. 프론트 **246개/타입 검사·빌드**, 실제 Chrome/Vite/고유 PostgreSQL에서 정상 저장·경쟁 409·명시적 복구·커밋 200 유실·VIEWER/405·데스크톱/모바일을 확인했다. 임시 자원을 정리하고 실제 로컬 V4/5/3/6/1·Project 1 version 1과 보호 파일 8개를 보존했다. 백엔드/SQL/권한을 바꾸지 않아 해당 전체 회귀는 반복하지 않았다. 다음 한 작업은 **Task 데이터 모델·생성/조회 권한 범위 확정**이며 Phase 1 전체는 열어둔다.


### 2026-11-07 첫 Task 데이터 모델·권한 범위 확정

사용자 선택으로 첫 네 필드(제목/설명/상태/우선순위)의 생성/프로젝트별 목록/상세, OWNER/ADMIN/MEMBER 생성·네 역할 조회, 서버 TODO 고정·우선순위 선택/기본 MEDIUM을 확정했다. 담당자/마감일/수정·칸반/댓글·이력은 후속이다. [Task 계약](task-model-and-api-v1.md)에 V5/부모 관계·중첩 인가/DTO/트랜잭션·기존 POST 필터 확장·최소 SELECT/INSERT·기존 실행기/이력·seed 전환과 격리 수용 기준을 기록했다. 이번에는 문서만 변경했다. 다음 한 작업은 **V5·Task 매핑·생성/조회 API·최소 권한 도구 구현과 격리 PostgreSQL 검증**이다. Task 전체 MVP 필드/CRUD와 Phase 1 전체는 완료로 표시하지 않는다.


### 2026-11-07 Task V5·생성/조회 구현·격리 검증

V5/tasks·스칼라 Project FK/enum, 생성/목록/상세 API·세 역할 생성/네 역할 조회·부모 확인·TODO/우선순위, 전용 TASK_CREATE와 기존 도구/이력/seed·기동 검사·Task POST Origin 확장을 구현했다. **백엔드 396개/빌드·실제 Vite 프록시 1개·PowerShell 전체 92개 통과**. V4 독립 target과 기존 Workspace/Project 회귀도 유지했다. 실제 로컬 V4/PROJECT_UPDATE·5/3/6/1·Project 1 version 1 및 보호 파일 7개를 보존하고 격리 자원/자격증명을 정리했다. 다음 한 작업은 **로컬 V5·최소 Task 권한 적용과 새 앱의 생성·조회 확인**이다. 실제 로컬 적용·Vue Task·나머지 Task MVP 필드/수정/상태 변경과 Phase 1 전체는 완료로 표시하지 않는다.


### 2026-11-08 실제 로컬 V5·Task 생성/조회

V5 1건/Validate·재실행 0건, 최소 Task SELECT/INSERT 최초·반복 TASK_CREATE와 새 JAR의 MEMBER 생성 201/TODO·MEDIUM·네 역할 목록/상세 200을 확인했다. MEMBER의 Project 관리와 VIEWER 생성은 403, 미소속/미존재/부모 불일치는 404, 출처·입력/쓰기 off도 검증했다. 기존 5/3/6/1·Project version 1을 유지하고 Task 한 행만 추가해 **5/3/6/1/1**이 됐다. 기존 이력/ACL/시퀀스·보호 파일 9개를 보존하고 검증 앱을 종료했다. 제품 소스를 바꾸지 않아 이전 전체 회귀는 반복하지 않았다. 다음 한 작업은 **Vue Task 목록·상세 조회 연결**이며 전체 Phase 1은 열어둔다.


### 2026-11-08 Vue Task 목록·상세 조회

TaskBrowser/tasks.ts를 선택한 Project 아래에 연결했다. 20개 Slice/별도 상세·한글 상태/우선순위·일반 텍스트 설명·로딩/빈 목록/오류·재시도·부모 전환/취소를 구현했다. 프론트 **15파일/297개·타입 검사/Vite build 통과**. 실제 Chrome/Vite/쓰기 off 새 JAR에서 Alice/VIEWER 조회, Eve의 다른 공간에서 Task 미노출, 503/재시도·공간 변경 시 이전 Task 상세 종료·1280/390px를 확인했다. API 29회 모두 GET이며 로컬 5/3/6/1/1·전체 스냅샷과 보호 파일 9개를 유지하고 임시 프로세스를 종료했다. 백엔드/SQL/권한을 바꾸지 않아 해당 전체 회귀는 반복하지 않았다. 다음 한 작업은 **Vue Task 생성 폼·POST 연결**이며 Task 전체 MVP/Phase 1은 열어둔다.


### 2026-11-08 Vue Task 생성 폼·POST

TaskCreateForm/createTask와 TaskBrowser의 생성 후 목록/상세, ProjectBrowser의 역할 전달을 연결했다. 세 역할 생성/네 역할 조회·기본 MEDIUM/서버 TODO·정규화/검증·중복 차단·201 성공/후속 GET 실패 분리·결과 불명 입력/잠금·GET 확인/명시적 초기화를 제공한다. **17파일/355개·타입 검사/Vite build 통과**. 실제 Chrome/Vite/고유 PostgreSQL에서 MEMBER 생성/Project 관리 미허용·높은 우선순위·commit된 201 유실/미재전송·VIEWER/405·모바일을 확인했다. 격리 Task 2건/부모 불변·임시 자원 정리, 실제 로컬 5/3/6/1/1·전체 스냅샷/보호 파일 9개를 보존했다. 백엔드/SQL/권한 전체 회귀는 변경이 없어 반복하지 않았다. 다음 한 작업은 **Task 수정 API의 필드·권한·충돌 정책 확정**이며 전체 Task MVP/Phase 1은 계속 열어둔다.


### 2026-11-08 Task 수정 정책 확정

사용자가 제목/설명/우선순위·OWNER/ADMIN/MEMBER의 공간 내 모든 Task 수정·Task 자체 버전/409를 선택했다. [수정 계약](task-update-api-v1.md)에 필수 네 필드 PATCH·설명 삭제/no-op·404/403/409 순서, V6/version·TASK_UPDATE 다섯 컬럼과 기존 도구/이력·과거 target/seed·기동 검사, Task 상세/생성의 문자열 버전과 기존 Vue 파서 전환·후속 복구 기준을 기록했다. 이번에는 문서만 변경했다. 현재 로컬은 V5/TASK_CREATE·5/3/6/1/1이며 코드/V6/권한 적용/기능 검증은 후속이다. 다음 한 작업은 **Task 수정 API·V6·최소 UPDATE 권한 도구 구현과 격리 PostgreSQL 검증**이다. 전체 Task MVP/Phase 1은 계속 열어둔다.


### 2026-11-08 Task 수정 API·V6·권한 도구 구현

TaskUpdateController→쓰기 Service의 소속·Project/Task 부모·세 역할/버전→mandatory 수정/OPTIMISTIC 검증·flush/commit, V6/Task @Version·no-op·409/rollback과 TASK_UPDATE 다섯 컬럼을 구현했다. 기존 도구·기동 정책·이력/seed·과거 target/미래 fixture와 Vue 상세/생성 version 문자열도 전환했다. 실제 검증 결과와 최종 정리는 [작업 기록](local-setup.md)에 구분한다. 실제 로컬 V5/TASK_CREATE·5/3/6/1/1은 별도 적용 전까지 유지하고 새 앱에는 V6가 필요하다. 전체 Task MVP/Phase 1과 Vue 수정 폼은 계속 열어둔다.

최종 검증: **Java 446개·실제 Vite 프록시 1개·PowerShell 105개·Vue 364개와 빌드 통과**. 기존 테스트의 기동 오류 문구 기대값 1건 수정 후 전체 회귀를 다시 통과했다. 실제 로컬 V5/TASK_CREATE·5/3/6/1/1·전후 스냅샷/보호 파일 8개를 보존하고 격리 자원/임시 자격증명을 정리했다. 다음 한 작업은 **로컬 V6·최소 Task UPDATE 적용과 새 앱의 수정 확인**이다.


### 2026-11-08 실제 로컬 V6·Task UPDATE·수정 확인

V6 1건/Validate·재실행 0건, 최소 Task UPDATE 최초·반복과 새 앱의 MEMBER 수정 200/version 1/HIGH·TODO 유지·stale 409·OWNER/ADMIN no-op 200·VIEWER 403·미소속/미존재/부모 불일치 404·Origin/입력·쓰기 off 405·Actuator UP을 확인했다. 5/3/6/1/1을 유지하고 Task 1 다섯 필드만 변경했다. 부모 Project version 1/기존 이력·다른 데이터/ACL/시퀀스·보호 파일 10개를 보존하고 검증 앱을 종료했다. 제품 코드 변경이 없어 앞선 전체 회귀는 반복하지 않았다. 다음 한 작업은 **Vue Task 제목·설명·우선순위 수정 폼·버전 보존·409 충돌 안내 연결**이며 전체 Task MVP/Phase 1은 열어둔다.


### 2026-11-08 Vue Task 수정 폼·버전·409 복구

TaskUpdateForm/updateTask를 상세에 연결하고 세 역할/세 필드·문자열 버전·409/결과 불명 입력 유지/저장 잠금·최신 Task/역할 확인·우선순위 포함 비교/명시적 기준 갱신·별도 수동 저장을 구현했다. 프론트 **19파일/415개·타입 검사/Vite build**와 실제 Chrome/Vite/고유 PostgreSQL에서 정상 저장/경쟁 409·수동 복구·commit된 200 유실/미재전송·VIEWER/405·데스크톱/모바일을 확인했다. 실제 로컬 V6/5/3/6/1/1·Task/Project version 1·전체 스냅샷/보호 파일 10개를 보존하고 임시 자원을 정리했다. 백엔드/SQL/권한을 바꾸지 않아 해당 전체 회귀는 반복하지 않았다. 다음 한 작업은 **Task 상태 변경 API의 허용 전이·권한 범위 확정**이며 상태 변경/담당자·날짜·이력/전체 Task MVP·Phase 1은 계속 열어둔다.


### 2026-11-08 Task 상태 변경 전이·권한·이력 정책 확정

사용자가 세 상태 간 모든 전이·OWNER/ADMIN/MEMBER의 공간 내 모든 Task 변경·최소 상태 Activity 동시 저장을 선택했다. [상태 계약](task-status-api-v1.md)에 별도 PATCH/status·version, 공유 Task 버전/409·현재 버전 같은 상태 no-op, V7/이력 모델·조건부 상태 UPDATE/refresh와 이력 INSERT/commit 원자성, TASK_STATUS_CHANGE 최소 컬럼 권한·기존 도구/기동 검사/이력·seed/과거 target·미래 V8 fixture 및 격리 기준을 기록했다. 이번은 문서만 변경했고 실제 로컬 V6/TASK_UPDATE·5/3/6/1/1을 유지한다. 다음 한 작업은 **Task 상태 변경 API·V7·최소 권한 도구·상태 이력 원자성 구현과 격리 PostgreSQL 검증**이다. Activity 조회/UI·다른 종류 이력·전체 Task MVP/Phase 1은 계속 열어둔다.

### 2026-11-10 Task 상태 API·V7·권한 도구 구현

상태 변경 API·최소 상태 Activity·V7·TASK_STATUS_CHANGE 전용 도구와 기존 기동/관리자/seed/history·과거 target/미래 fixture 전환을 구현했다. 실제 검증은 [구현 기록](task-status-implementation.md)과 [실행 기록](local-setup.md)에 구분한다. 다음 한 작업은 **로컬 V7·최소 Task 상태 변경 권한 적용과 새 앱의 상태 변경·이력 확인**이다. Vue 상태 UI·Activity 조회·담당자/마감일·전체 Task MVP/Phase 1은 계속 열어둔다.

### 2026-11-10 실제 로컬 V7·Task 상태/이력 확인

V7 1건/Validate/재실행 0건, TASK_STATUS_CHANGE 최초/반복과 새 JAR의 실제 MEMBER 상태 전환 200·이력 1건/같은 시각·공유 버전 409/no-op·역할/부모/Origin/쓰기 off·Actuator UP을 확인했다. Task 1은 IN_PROGRESS/version 2, 기존 데이터 5/3/6/1/1·부모/다른 필드·기존 ACL/시퀀스와 보호 파일 11개를 보존했다. 검증 앱은 종료했고 DB는 정상 실행 중이다. 다음 한 작업은 **Vue Task 상태 변경 UI·버전 보존·409 충돌 안내 연결**이다. Activity 조회/담당자·날짜·전체 Task MVP/Phase 1은 계속 열어둔다.

### 2026-11-10 Vue Task 상태 변경 UI·공유 버전/409 복구

상태 폼/전용 PATCH·일반 편집과 독립된 입력/시작 버전·성공 목록/상세 갱신·409/결과 불명 잠금과 공동 GET/비교·명시적 기준/수동 저장을 연결했다. 오래된 복구/표시 버전 역행을 막고 일반 폼에도 상태 비교를 추가했다. 21파일/479개·타입 검사/Vite build 및 실제 Chrome/Vite/격리 DB에서 교차 폼·상태끼리 충돌/커밋 응답 유실/VIEWER/405·1280/390px를 확인했다. 실제 로컬 V7/Task version 2·이력 1건·보호 파일 167개를 유지하고 임시 자원을 정리했다. 다음 한 작업은 **Task 상태 변경 이력 조회 API의 계약·권한 범위 확정**이다. 이력 노출/권한·담당자/마감일·전체 Task MVP/Phase 1은 계속 열어둔다.

### 2026-11-10 상태 이력 조회 계약 검토 초안

[Task 상태 이력 조회 초안](task-status-history-read-api-v1.md)에 Task별 GET·인가/DTO·최소 컬럼 SELECT·V7 재사용·후속 격리 기준을 정리했다. 현재 소속 네 역할의 전체 과거 이력 조회, ID/현재 표시 이름 노출, 최신순 버전 커서의 세 추천안은 사용자 답변 대기다. 확정/ADR·구현·실제 로컬 GRANT·Vue 이력 화면을 완료로 표시하지 않는다.

### 2026-11-10 Task 상태 이력 조회 계약 확정

사용자가 네 역할의 전체 과거 이력·ID/현재 표시 이름(이메일 제외)·최신순 버전 커서를 모두 선택했다. [계약](task-status-history-read-api-v1.md)에 Task별 GET/8필드 envelope·6필드 item/현재 이름·strict beforeVersion/size·동일 404·고정 컬럼 SELECT·새 승인 상태/기동·이전 도구 전환·V7 재사용/격리 기준을 확정했다. 초안의 답변 대기는 해소됐으며 구현/실제 로컬 GRANT·Vue 이력 화면은 아직 없다. 다음 한 작업은 **Task 상태 이력 조회 API·최소 SELECT 권한 도구 구현과 격리 PostgreSQL 검증**이다. 전체 Task MVP/Phase 1은 계속 열어둔다.
### 2026-11-10 Task 상태 이력 조회 구현

확정한 Task별 이력 GET·현재 이름/이메일 제외 projection·최신순 버전 커서와 TASK_STATUS_HISTORY_READ 전용 도구를 구현했다. 기존 관리자 도구 보존·원시/유효 ACL·읽기/쓰기 기동 전환과 격리 검증을 포함한다. [구현/검증](task-status-history-implementation.md)을 따른다. 실제 로컬은 V7/TASK_STATUS_CHANGE·Task version 2/이력 1건이며 다음 한 작업은 **로컬 상태 이력 SELECT 권한 적용과 새 앱의 이력 조회 확인**이다. Vue 이력 화면·전체 Task MVP/Phase 1은 계속 열어둔다.

**최종 검증: Java 30클래스/565개·실제 Vite 프록시 1개·Gradle build, PowerShell 전체 134개, Vue 21파일/479개·타입 검사/Vite build가 모두 통과했다.** 새 이력 API 35개/권한 정책 13개를 포함하며 전체 회귀는 실패/오류/스킵 0이다. 최초 집중 실행의 기존 오류 문구 기대값 1건은 수정 후 이 전체 실행으로 통과를 확인했다. 실제 로컬 권한/데이터·보호 파일 52개를 유지하고 임시 자원/격리 자격증명 3개를 정리했다. 다음은 **로컬 상태 이력 SELECT 권한 적용과 새 앱의 이력 조회 확인**이다.

### 2026-11-10 실제 로컬 상태 이력 SELECT·새 앱 GET 확인

실제 로컬에서 전용 TASK_STATUS_HISTORY_READ 실행기로 audit 7개 컬럼 SELECT를 적용하고 새 dev-actor JAR을 읽기 모드로 기동해 검증했다. Actuator UP, Carol의 Task 1 이력 1건(버전 2, TODO→IN_PROGRESS, 정확한 UTC 마이크로초), envelope/item/actor 필드, 마지막 커서의 빈 페이지와 strict size 400을 확인했다. table-wide SELECT는 없고 기존 audit INSERT 6개/Task UPDATE·다른 ACL·데이터·Flyway V1~V7·시퀀스는 보존했다. Vue 이력 화면과 실제 로그인은 후속이다. 다음 한 작업은 **Vue Task 상태 이력 조회 화면 연결**이다. [실행 기록](local-setup.md).
### Vue Task 상태 이력 화면 연결 — 2026-11-10

Task 상세에서 이력을 펼칠 때 최신 20개를 읽고 version cursor로 과거 기록을 이어 보는 UI를 추가했다. 행위자 표시 이름·전이·시각/버전·로딩/빈 결과/재시도/새로고침을 제공한다. API client가 엄격한 응답·cursor를 검사한다. `npm.cmd run build`의 타입 검사와 Vite build 통과. [구현 기록](task-status-history-ui.md). 실제 로그인·전체 Task MVP는 후속 범위다. 다음 한 작업은 **Task 담당자·마감일 필드의 모델·권한 범위 확정**이다.

### 2026-11-10 Task 담당자·마감일 모델/권한 범위 확정

사용자 선택: nullable 단일 담당자, 할당 당시 Workspace의 현재 멤버만 지정 가능(모든 역할 포함), 제거된 멤버의 기존 할당 유지, 마감일은 nullable `YYYY-MM-DD` LocalDate. 기존 쓰기/읽기 역할과 Task version을 유지한다. 생성/수정·목록/상세 계약에 반영하며 담당자·날짜 Activity/필터/알림은 제외한다. [결정·모델 문서](task-assignee-due-date-v1.md). 다음은 V8·API·최소 권한 도구 구현 및 격리 PostgreSQL 검증이다.

### 2026-11-11 Task 담당자·마감일 V8 구현

V8 User FK/LocalDate, Task create/update/list/detail 계약, 현재 membership Service 검사, 단일 version 공유, 최소 컬럼 ACL 도구와 Vue 파서 호환을 구현했다. 상세/검증 결과는 [구현 기록](task-assignee-due-date-implementation.md)에 갱신한다. 실제 로컬 적용은 별도다.

격리 PostgreSQL 89개·Vue 484개 테스트와 빌드가 통과했다. 이어 사용자 승인으로 로컬 V8/Validate·재실행 0건, TASK_ASSIGNEE_DUE_DATE 최초·반복 권한, 새 loopback 앱의 담당자·날짜 지정/조회/해제 10개 검사를 확인했다. Task 1은 필드가 null로 복구됐고 공유 version 2→4/`updated_at`만 바뀌었으며 다른 데이터/활동/ACL/시퀀스는 보존됐다. [상세 실행 기록](local-setup.md). 다음 한 작업은 **Vue Task 생성·수정 폼에 담당자 선택과 마감일 입력을 연결**하는 것이다.

### 2026-11-11 Workspace 멤버 목록 API 계약 확정

현재 Workspace 멤버 조회만 먼저 진행한다. 네 역할은 ID·표시 이름·역할을 조회할 수 있고 이메일은 제외한다. 기본 20·최대 100의 ID 오름차순 페이지 응답을 기존 형식으로 맞춘다. [계약](workspace-member-list-api-v1.md). API/최소 권한 구현과 Vue 목록 연결은 완료했다.

### 2026-11-12 Workspace 멤버 목록/최소 SELECT 구현

GET 및 현재 membership 검사·projection·페이지·404·email 제외를 구현했다. 별도 도구가 기존 세 테이블의 table-wide SELECT를 제거하고 기존 앱 기능에 필요한 정확한 16컬럼 SELECT로 바꾼다. 격리 PostgreSQL targeted suite 76개 통과, PowerShell parser 4개 파일 통과. [구현 기록](workspace-member-list-implementation.md). 실제 로컬 권한 적용과 Vue 담당자 선택 UI는 별도다. 다음 한 작업은 **Vue 담당자 ID 입력을 현재 멤버 선택 목록으로 교체**하는 것이다.

### 2026-11-13 Vue Task 담당자 선택 목록 연결

생성·수정 폼이 Workspace 멤버 API의 현재 이름/역할/ID 후보를 사용한다. API paging과 response parser, 목록 실패 재시도, 원래 할당된 former member 보존 option을 추가했다. 기존 assignment를 유지하는 업데이트는 허용하고, 변경된 non-null assignee는 서버가 현재 membership을 확인하도록 보완했다. Frontend 21개 파일/496개 테스트 및 build 통과; 격리 PostgreSQL `TaskUpdateApiTest` 40개 통과. [구현 기록](workspace-member-list-implementation.md). 같은 날 이후 로컬 column ACL/HTTP도 확인했다. [실행 결과](local-setup.md).

### 2026-11-13 Workspace 멤버 수명주기 정책 확정

기존 계정 전용 초대 코드/링크(7일·1회 사용·재발급 시 이전 코드 무효화), OWNER·ADMIN의 일반 멤버 관리, 현재 OWNER의 단일 트랜잭션 소유권 이전(기존 OWNER는 ADMIN), 제거 뒤 Task assignee/상태 이력 보존 정책을 확정했다. [멤버십 관리 계약](workspace-membership-management-v1.md)에 맞춘 V9/API/최소 권한 도구와 격리 PostgreSQL 검증을 완료했다(ADR-066). 2026-11-19 실제 로컬 V9·ACL 및 새 앱 HTTP 초대/수락·역할 변경·제거·소유권 이전을 확인했다. Vue에 전역 초대 수락, 관리자용 발급/취소, 멤버 역할/제거, OWNER 이전 화면을 연결했다. 24개 프론트 테스트 파일 505개와 타입 검사/Vite build가 통과했다. Chrome/Vite/로컬 PostgreSQL에서도 초대 취소·재발급·1회 수락·만료 코드 거부, 코드 재사용 거부, Alice→Bob→Alice 이전과 390px 레이아웃을 확인했다. DB·ACL·membership timestamp를 복구하고 sequence 소비량은 [local-setup.md](local-setup.md)에 기록했다. 2026-11-20 초대 대기 목록 계약은 ADR-067로 확정했고 API·projection·권한 검사를 구현해 격리 PostgreSQL 테스트 5개를 통과했다. Vue 목록·만료 표시·초대 취소/재조회도 연결해 프론트 24파일/507개 테스트와 vue-tsc/Vite build가 통과했다. [계약](workspace-invitation-list-api-v1.md), [구현](workspace-invitation-list-implementation.md). 로컬 Vite proxy 실제 GET/POST/DELETE/GET과 임시 행 정리를 확인했다. 2026-11-23 Chrome에서 만료 초대와 취소 후 빈 목록 재조회를 메모리 fixture로 확인했다. Project 보관 정책·API 계약은 ADR-068과 [계약 문서](project-archive-api-v1.md)에 기록했다. V10, archive/restore API, `archived_at` 최소 UPDATE runner와 격리 PostgreSQL 테스트를 구현했다. Vue Project 목록 필터·보관 배지·OWNER/ADMIN 확인 단계 보관/복구, archived detail의 읽기 전용 Task 표시를 연결했다. 프론트 26파일/520개 테스트가 통과했다. 로컬 V10/권한과 실제 UI HTTP 확인은 아직 하지 않았다. 다음 한 작업은 **로컬 V10·최소 권한 적용 후 새 앱에서 archive/restore와 Task 읽기 전용 경계 확인**이다.

### 2026-11-23 Project 보관 로컬 적용 완료

V10을 기존 로컬 DB에 적용하고 Validate 및 재실행 0건을 확인했다. `flowdesk_app`에는 `archived_at` column UPDATE만 추가해 table-level Project UPDATE가 없는 것을 확인했다. 새 `dev-actor` 앱에서 Project archive/restore와 목록 필터, archived 상태의 Task·이력 조회, Task 생성/수정/상태 변경 409를 실제 HTTP로 확인했다. [상세 실행 기록](local-setup.md), [구현 결과](project-archive-implementation.md). 다음 한 작업은 **Vue 화면을 로컬 백엔드에 연결해 보관 목록 필터와 확인 단계를 브라우저에서 검증**하는 것이다.

### 2026-11-23 Project archive UI end-to-end 확인

임시 backend 18080·Vite proxy 5174와 headless Chrome으로 기존 IDE/Vite 실행을 건드리지 않고 화면 흐름을 확인했다. 기본 목록 숨김, include archived 배지, Task write form 비표시·상태 이력 표시, 복구 후 활성 목록과 생성 UI 복귀가 통과했다. 검증 후 Project 1은 active(version 9)로 남았고 Task/activity 데이터는 보존됐다. 임시 프로세스와 파일은 정리했다. [화면·DB 결과](local-setup.md), [구현 기록](project-archive-implementation.md). 다음 한 작업은 **다음 도메인 기능의 요구 범위를 문서와 함께 확정**한다.

### 2026-11-23 Task 보관·복구 범위 확정

MVP의 남은 Task 보관을 다음 도메인 범위로 선택했다. soft archive, Task 자체 version/409, 기본 목록 숨김과 모든 Workspace 멤버의 `includeArchived=true`, 상세/상태 이력 읽기 유지·쓰기 차단, OWNER/ADMIN 보관·복구, status Activity 미기록을 확정했다. V11 `tasks.archived_at`/column UPDATE와 부모 Project archive 경계를 [계약](task-archive-api-v1.md) 및 ADR-069에 정리했다. 다음 한 작업으로 V11·API·최소 권한과 격리 검증을 구현했다.
### 2026-11-23 Task 보관 V11/API/최소 권한 구현

Task soft archive를 V11 `tasks.archived_at`, version 기반 archive/restore PATCH, 기본 목록 숨김·`includeArchived=true`, archived Task 쓰기 409로 구현했다. OWNER/ADMIN만 archive/restore하며 Project가 archived 상태면 거부한다. Task 상태 이력은 유지하고 archive 자체 Activity는 만들지 않는다. 새 응답 필드에 맞춰 Vue API 타입/응답 파서도 호환시켰다.

전용 `TASK_ARCHIVE` 권한 실행기는 기존 Task UPDATE 권한을 보존하고 `archived_at` UPDATE만 추가한다. 현재 12컬럼 Task SELECT 경로에도 신규 컬럼 SELECT를 넣어 JPA hydrate 권한을 유지한다. [계약](task-archive-api-v1.md), [구현 기록](task-archive-implementation.md). 실제 로컬 V11/권한 적용은 아직 하지 않았다. 다음 한 작업은 **Task archive 목록 필터·배지·OWNER/ADMIN 보관·복구 UI를 연결하고 격리 브라우저에서 확인**하는 것이다.
