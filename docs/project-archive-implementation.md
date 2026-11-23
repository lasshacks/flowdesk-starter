# Project 보관·복구 구현 결과

날짜: 2026-11-23  
정책/HTTP 계약: [Project 보관·복구 API v1](project-archive-api-v1.md), ADR-068

## 목표 및 완료 기준

V10의 nullable `archived_at`, 버전 검사 보관/복구 API, 기본 목록 숨김과 명시적 포함 조회, archived Project 쓰기 차단, 최소 DB 권한 도구를 구현한다. 검증은 격리 PostgreSQL에서만 수행하고 실제 `infra/.env` DB migration/GRANT는 범위에서 제외한다.

## 설계 및 요청 흐름

Project 목록 GET → Workspace membership → 기본 active query 또는 `includeArchived=true` query → DTO의 `archived`.

Archive PATCH → strict JSON → 서버 dev actor/current membership/Project → OWNER·ADMIN → Project version 검사 → Workspace write lock 및 Project optimistic lock → `archived_at`, `updated_at`, Project version → 200 DTO. Task write는 기존 Workspace read lock과 직렬화되고 archived Project면 409 `PROJECT_ARCHIVED`; Task/detail/history read는 유지한다.

V10은 `projects.archived_at TIMESTAMPTZ NULL` 하나만 추가한다. 기존 Project table-level SELECT가 신규 컬럼 읽기도 허용한다. 별도 권한 runner는 기존 Project UPDATE capability의 네 열에 `archived_at` UPDATE 하나를 더하며, `flowdesk.dev.project-archive-enabled=true`인 앱 기동에서 이를 검사한다.

## 변경 파일

- Backend: Project model/DTO/query/controller/service, Task command guard, archive settings 및 오류, V10 migration.
- Migration tooling: Project archive runner/SQL policy, Gradle task, dedicated PowerShell provision wrapper, 격리 migration verifier.
- Tests: V10 migration 보존 테스트, archive ACL runner 테스트, archive/restore HTTP·visibility·authorization·Task create guard 테스트. 기존 Project JSON 및 Vue response fixtures를 새 DTO에 맞췄다.
- Docs: requirements, ADR-068, contract, learning notes, roadmap/local setup.

## 실행한 검증

- Backend Java 컴파일: `compileJava compileMigrationJava compileTestJava` 성공.
- 격리 PostgreSQL API/ACL/migration 회귀: `V10MigrationTest`, `ProjectArchiveAccessPolicyTest`, `ProjectArchiveApiTest` 4개, `ProjectApiTest` 43개, `ProjectUpdateApiTest` 31개 — 총 80 tests 통과, 실패 0. ProjectArchiveApiTest는 Project archive/restore·기본/포함 목록·상세·stale/no-op·role deny·Project rename 및 Task create/update/status 차단·Task/history read 보존·archive와 Task 생성의 동시 실행 직렬화를 확인했다.
- `pwsh -NoProfile -File infra/verify-migration.ps1 -IncludeProjectArchive`: **91 checks 통과**. 고유 Compose project에서 V1–V10, 반복 적용, archive UPDATE 1열, 기존 권한 도구들의 grant 보존, 과잉 UPDATE 거부, 데이터/credential/environment 불변을 확인하고 container·volume·network를 정리했다.
- Vue 보관 UI: 목록 필터/배지, OWNER·ADMIN 확인 단계 보관·복구, archived Project Task 읽기 전용 화면을 연결했다. `npm test -- --run --reporter=dot`: 26 files/520 tests 통과. `npm run build`: vue-tsc와 Vite build 통과.
- 로컬 V10 및 전용 GRANT를 뒤에 적용하고 검증했다. 상세 결과는 [local setup](local-setup.md)에 기록했다.

## 배운 원리 / 미해결

- Workspace lock은 여러 Task 변경과 Project archive를 한 순서로 직렬화하고, Project `@Version`은 archive와 rename 간 오래된 client 상태를 막는다.
- 기존 table-level SELECT는 뒤에 추가한 열에도 적용된다. 컬럼을 쓰려면 별도 UPDATE grant가 필요하다.
- 로컬 HTTP 및 브라우저 검증은 기존 개발용 Project 1을 사용하고 active 상태로 복구했다. 보관/복구로 Project version과 `updated_at`은 증가했으며 Task/activity 행과 다른 DB 행은 변경되지 않았다.
- 임시 loopback 앱은 port 18080, Vite proxy는 5174에서 `dev-actor`, Workspace write, Project archive 설정을 명시해 실행했다. 화면에서 기본 목록 숨김, 보관 필터 배지, archived 상태 Task 쓰기 폼 숨김과 이력 조회, 복구 후 활성 목록/쓰기 UI를 확인했다. 임시 프로세스와 검증 파일은 정리했고 기존 8080/5173 서비스는 그대로 두었다. 기본 설정의 archive mode는 계속 비활성이다.

다음 한 작업: 다음 도메인 기능의 범위를 requirements/roadmap과 함께 확정한다.
