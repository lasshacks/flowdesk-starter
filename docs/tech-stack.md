# 기술 스택과 도입 시점

| 영역 | 초기 선택 | 이유/주의 |
|---|---|---|
| IDE / AI | VS Code + Codex | 파일 기반 지침과 검증 중심 작업 |
| Frontend | Vue 3, TypeScript, Vite | 컴포넌트·반응성·타입 학습 |
| Routing / state | Vue Router, Pinia | 라우팅과 클라이언트 상태 분리 |
| UI | PrimeVue(후보) | 생산성; 적용 전 디자인 선택 확인 |
| HTTP | Axios(후보) | 인터셉터와 오류 처리 학습; fetch도 비교 |
| Backend | Java 21, Spring Boot 3.x/호환 안정 버전 | 실제 생성 시 지원 버전/의존성 호환 확인 |
| Build | Gradle | 재현 가능한 빌드 |
| Persistence | Spring Data JPA, PostgreSQL | ORM, 영속성 컨텍스트, 트랜잭션 학습 |
| Migration | Flyway(권장) | DB 변경 이력 관리 |
| Tests | JUnit 5, Spring Boot Test, Testcontainers, Vitest | 테스트 레벨 구분 |
| Local infra | Docker Compose | 환경 재현 |
| Security (Phase 2) | Spring Security, JWT, TOTP MFA | Filter Chain부터 깊이 학습 |
| Cache (후속) | Redis | 캐시·TTL·인증 상태 실험 |
| Events (후속) | Kafka | 발행/소비/재시도/중복 처리 |
| Realtime (후속) | WebSocket | 연결·인증·재연결·동기화 |
| Reactive (후속) | WebFlux | MVC와 비교 실험 |
| Cross-cutting (후속) | Spring AOP | 프록시/감사/측정; 보안 대체 금지 |

## 버전 원칙
- 프로젝트 생성 시 공식 호환성 확인 후 구체 버전을 고정하고 기록한다.
- Spring Boot 버전과 Spring Security, Java, Gradle, 라이브러리의 호환성을 먼저 확인한다.
- 초기 의존성은 웹, 검증, JPA, PostgreSQL, 마이그레이션, 테스트 등 최소 범위만 포함한다.

## Phase 0 백엔드 고정 버전 — 2026-11-02

- Java 21 (로컬: Temurin 21.0.13+9), Spring Boot 3.5.16, Gradle Wrapper 8.14.3.
- Boot 3.5의 Gradle 8.x 지원 범위와 Java 21 실행 조건(Gradle 8.5 이상)을 함께 충족하는 조합이다. [Boot 요구사항](https://docs.spring.io/spring-boot/3.5/system-requirements.html), [Gradle 호환표](https://docs.gradle.org/current/userguide/compatibility.html).
- Gradle Java toolchain을 21로 지정한다. Spring 의존성은 Boot BOM으로 관리하며, Spring Web과 Boot Test로 시작했다. 이후 사용자가 상태 점검 비교를 요청해 Actuator를 추가했다. Gradle 기본 BOM 지원을 사용해 별도 dependency-management 플러그인은 추가하지 않는다.
- JPA·PostgreSQL·Flyway는 DB 연결 단계에 추가한다. 입력을 받지 않는 health API에는 Validation 의존성을 미리 추가하지 않는다.

## Phase 0 프론트엔드 고정 버전 — 2026-11-02

- 공식 create-vue 3.24.0으로 TypeScript·Vitest·bare 옵션을 사용해 생성했다.
- Node 24.22.2(Volta 프로젝트 설정), npm 11.19.1, Vue 3.5.44, Vite 8.3.5, @vitejs/plugin-vue 6.0.10, TypeScript 6.0.3, vue-tsc 3.3.14, Vitest 4.1.12, jsdom 30.0.1.
- 직접 의존성은 정확한 버전으로, 간접 의존성은 package-lock.json으로 고정한다. 재설치는 npm ci를 사용한다.
- 초기 HTTP 호출은 fetch, 개발 연결은 Vite /api 프록시를 사용한다. Router·Pinia·Axios·디자인 시스템은 실제 화면 요구가 생길 때 검토한다.
- 공식 문서: [Vue 빠른 시작](https://vuejs.org/guide/quick-start.html), [Vue TypeScript 도구](https://vuejs.org/guide/typescript/overview.html), [Vite 개발 프록시](https://vite.dev/config/server-options.html#server-proxy).

## Phase 0 로컬 PostgreSQL — 2026-11-02

- PostgreSQL 공식 이미지 postgres:17.11-bookworm, Docker Compose 프로젝트 flowdesk-local.
- 지원 중인 17 계열의 정확한 마이너 버전을 사용한다. [지원 정책](https://www.postgresql.org/support/versioning/), [공식 이미지 태그](https://hub.docker.com/_/postgres).
- 127.0.0.1:5432 포트 매핑과 명명 볼륨을 사용한다. 로컬 비밀번호는 PowerShell 7 스크립트가 생성하고 Git 제외 대상인 infra/.env에 보관한다.
- 초기에는 DB 자체 연결을 검증했고, 이후 Spring JDBC·DataSource 연결을 추가했다. JPA·Flyway는 아직 추가하지 않았다.

## Phase 0 DataSource와 DB 상태 검사 — 2026-11-02

- spring-boot-starter-jdbc와 PostgreSQL runtime 드라이버를 추가했다. Boot 3.5.16 BOM의 PostgreSQL JDBC 42.7.11, HikariCP 6.3.3을 사용한다.
- Testcontainers PostgreSQL·JUnit Jupiter 1.21.4도 같은 BOM으로 관리한다. 실제 PostgreSQL을 사용하는 DB 통합 테스트이며 전체 테스트에는 Docker가 필요하다.
- 관리자용 infra/.env와 앱용 backend/.env를 분리한다. Spring config import가 로컬 파일을 명시적으로 읽고 OS 환경변수가 이를 덮어쓸 수 있다.
- 애플리케이션은 flowdesk_app 최소 권한 계정으로 연결한다. Actuator의 DataSourceHealthIndicator를 자동 구성하고 health 외 관리 엔드포인트와 상세 상태는 노출하지 않는다.
- 참고: [Spring SQL 자동 설정](https://docs.spring.io/spring-boot/3.5/reference/data/sql.html), [Testcontainers PostgreSQL](https://java.testcontainers.org/modules/databases/postgres/).

## Phase 1 준비 — V1 검증 도구

- Boot BOM의 Flyway core와 flyway-database-postgresql 11.7.2를 테스트 범위에만 추가했다. 실행 앱의 런타임 의존성과 로컬 자동 마이그레이션 설정은 추가하지 않았다.
- V1MigrationTest가 Flyway Java API로 migrate/validate를 명시적으로 호출한다. Flyway Gradle 플러그인·CLI는 아직 도입하지 않았다.
- 새 테스트 DB마다 migrator 계정 소유의 flowdesk 스키마를 준비하고 createSchemas=false, cleanDisabled=true, baselineOnMigrate=false로 검증한다.

## 로컬 migration 실행 계획 확정

- 앱 부팅 자동 적용 대신 전용 migration source set과 Gradle JavaExec/Java main을 사용한다. 2026-11-03 source set·runMigration 작업·runner와 PowerShell 실행기를 구현했다. JavaExec에도 Java 21 launcher를 명시한다.
- 신규 자격증명 파일은 infra/.env.migrator, 전달 대상은 전용 자식 프로세스 환경이다. 일반 앱의 backend/.env 및 런타임 클래스패스와 분리한다.
- 전체 절차와 구현 검증 기준은 [local-migration.md](local-migration.md)를 따른다.

## 개발 seed 실행기 — 2026-11-03

- 기존 migration source set의 JDBC/Flyway 의존성으로 runSeed JavaExec와 LocalSeedRunner를 구현했다. Spring/JPA/새 라이브러리를 추가하지 않았다.
- Apply는 JDBC 트랜잭션과 명시적 테이블 잠금, Verify는 읽기 전용 REPEATABLE READ를 사용한다. 격리 검증 후 실제 로컬 Apply·Verify와 재실행 0건도 확인했다.
- LocalSeedRunnerTest는 Testcontainers PostgreSQL과 별도 JVM의 실제 main을 사용한다. PowerShell 전체 경로는 verify-migration.ps1 -IncludeSeed로 기존 migration과 함께 검증한다. [실행·실패 정책](dev-seed.md).

## 첫 JPA 조회 구현 — 2026-11-03

- spring-boot-starter-data-jpa를 기존 Boot 3.5.16 BOM으로 추가하고 엔티티/enum/조회 Repository를 구현했다.
- 실제 dependencyInsight에서 Hibernate 6.6.53.Final runtime을 확인했다. Jakarta Persistence 3.1.0/Spring Data BOM 2025.0.13도 동일 BOM 관리다. 별도 최신 Hibernate로 교체하지 않는다.
- ddl-auto=validate, open-in-view=false, 조회 전용 Repository, 단방향 LAZY와 필요한 EntityGraph를 적용했다. schema 수정은 계속 별도 Flyway 작업만 담당하며 Querydsl/Lombok/매퍼는 추가하지 않았다.
- 읽기 권한과 기존 도구 호환은 [jpa-read-model-v1.md](jpa-read-model-v1.md)를 따른다. 전체 JUnit 96개/PowerShell 41개 격리 검증 후 실제 로컬 READ 권한 적용·새 JPA 앱 기동/조회·기존 도구 재실행도 별도로 확인했다.

## 첫 워크스페이스 조회 API — 2026-11-03

- 기존 Spring MVC/JPA/Jackson으로 Controller·불변 DTO·readOnly Service를 구현했다. 새 의존성이나 Repository 메서드를 추가하지 않았다. 입력은 좁은 숫자/쿼리 계약을 직접 검사하며 Validation starter를 미리 추가하지 않는다.
- 명시적인 dev-actor 프로필, PostConstruct 설정/사용자 검사, OncePerRequestFilter의 직접 peer 검사로 개발 실행 범위를 제한한다. 이는 인증 기능이 아니며 Spring Security/실제 로그인은 Phase 2다. MVC interceptor보다 앞에서 접속 경계를 검사하고, 최종 소속 검사는 Service가 담당한다.
- Spring Framework 6.2의 ProblemDetail/ResponseEntityExceptionHandler를 사용해 업무 오류를 application/problem+json으로 표현한다. 오류를 일률적으로 200으로 감싸거나 예외 원문을 노출하지 않는다. [공식 오류 응답](https://docs.spring.io/spring-framework/reference/6.2/web/webmvc/mvc-ann-rest-exceptions.html), [Boot 전달 헤더/서버 설정](https://docs.spring.io/spring-boot/3.5/how-to/webserver.html).
- 신규 검증은 기존 Testcontainers PostgreSQL과 실제 임베디드 서버 HTTP를 사용한다. [구현·계약·격리 검증](workspace-read-api-v1.md).

## 워크스페이스 생성 설계 — 2026-11-03

워크스페이스 생성은 [생성 계약](workspace-create-api-v1.md)에 따라 기존 Spring MVC/JPA·EntityManager.persist·개발 쓰기/Origin 검사로 구현했다. 별도 공통 권한 상태/관리자 실행기와 격리 검증도 추가했다. 새 의존성/V2/실제 로컬 쓰기 권한 변경은 없다. 로그인/Spring Security/운영용 CSRF는 Phase 2다. 전체 Java 199개/별도 Vite 프록시 1개와 PowerShell 경로 52개를 검증했다. 앱의 Flyway 자동 실행은 false로 고정하며 IDE Gradle 가져오기는 명시적 JDK 21 경로를 사용한다.

이후 별도 요청으로 실제 로컬에도 최소 INSERT를 적용해 WORKSPACE_CREATE가 됐고, 별도 서버의 첫 생성 201·공간/OWNER 저장·소속 조회/차단을 확인했다. 데이터는 5/3/6이며 DB 권한과 쓰기 기능 활성화는 분리한다. 기존 IDE 8080은 유지했고 검증용 서버는 종료했다.

## Vue 워크스페이스 조회 연결 — 2026-11-03

후속 생성 폼(2026-11-04)도 기존 Composition API/fetch로 구현했다. v-model/submit 이벤트와 단일 진행 상태로 입력/중복 제출을 제어하고, POST의 결과 불명은 조회 오류와 별도 타입으로 관리한다. 새 라이브러리/백엔드 API/스키마 변경은 없다. [Vue 폼 바인딩](https://vuejs.org/guide/essentials/forms.html), [Fetch 응답 처리](https://developer.mozilla.org/en-US/docs/Web/API/Fetch_API/Using_Fetch).

- 기존 Vue Composition API/TypeScript/fetch/Vite와 지역 ref 상태를 유지한다. 의존성/package-lock 변경 없이 목록/선택 상세·페이지·오류를 구성했다. URL 공유/공유 상태 요구가 없어 Router/Pinia/Axios는 추가하지 않았다.
- AbortController와 AbortSignal.any로 선택/언마운트 취소와 5초 제한을 결합하고 취소 후 응답 적용도 막는다. 요청 순번만 비교하는 대안은 진행 중 네트워크 작업을 취소하지 못한다. [Vue 생명주기](https://vuejs.org/api/composition-api-lifecycle.html), [AbortSignal.any](https://developer.mozilla.org/en-US/docs/Web/API/AbortSignal/any_static).
- TypeScript 타입과 별개로 JSON 필드·ID 문자열 범위·역할·페이지/상세 ID를 런타임 검사한다. 코드/HTTP 상태를 안전한 안내로 바꾸고 서버 detail 원문은 화면에 넣지 않는다. Vitest/jsdom과 실제 Chrome/Vite/로컬 API 검증을 구분한다.


## 이름 수정 설계 — 2026-11-04

사용자 선택으로 OWNER/ADMIN 인가와 버전 충돌 검출을 설계했다. 기존 JPA의 Long @Version과 V2를 사용하고 새 라이브러리는 추가하지 않는다. 요청 버전 비교·DB 시점 검증·commit 뒤 응답 및 최소 컬럼 UPDATE 범위는 [수정 계약](workspace-rename-api-v1.md)에 따른다. 코드/SQL/권한 도구 구현과 Java 245개/별도 Vite 1개·PowerShell 60개 격리 검증을 완료했다. 새 의존성을 추가하지 않았다. 이후 실제 로컬에도 V2/최소 UPDATE를 적용하고 새 JAR에서 수정 200/버전 1·오래된 요청 409·쓰기 비활성 405를 확인했다. 앱 계정/인증 정책과 의존성은 바꾸지 않았다. [Jakarta Persistence 3.1](https://jakarta.ee/specifications/persistence/3.1/jakarta-persistence-spec-3.1.html), [PostgreSQL 17 GRANT](https://www.postgresql.org/docs/17/sql-grant.html).


## Vue 이름 수정 연결 — 2026-11-05

기존 Composition API/fetch/Vite와 지역 상태로 WorkspaceRenameForm을 추가했다. Workspace.version은 런타임 검증한 문자열로 보존하고 BigInt는 증가 비교에만 쓴다. 편집 기준·draft·409/결과 불명·최신 GET을 분리하며 확인 없이 버전을 교체하지 않는다. 새 의존성/Router/Pinia는 추가하지 않았다. 프론트 101개/타입 검사·빌드와 실제 Chrome/고유 PostgreSQL에서 충돌·커밋 후 응답 유실/GET 확인·모바일을 검증했다. [프론트 안내](../frontend/README.md), [수정 계약](workspace-rename-api-v1.md).


## 첫 Project 설계 — 2026-11-05

기존 Spring MVC/JPA/PostgreSQL/Flyway로 Project 생성·목록·상세를 설계했다. 새 의존성·ProjectMembership·프로젝트 version은 현재 추가하지 않는다. 스칼라 workspaceId/DB FK, 공간별 ID 정렬 Slice, 별도 쓰기 어댑터와 Service membership 인가를 사용한다. V3/권한 도구/seed·공통 검사 전환은 [Project 계약](project-model-and-api-v1.md)에 따라 기존 라이브러리만으로 구현했다. 격리 검증 뒤 사용자 요청으로 실제 로컬 V3/PROJECT_CREATE 적용과 새 JAR의 Project ID 1 생성·목록/상세·역할별 거부·쓰기 off/health를 확인했다. 새 의존성은 추가하지 않았으며 실행 결과는 local-setup.md에 기록했다.


## Vue Project 조회 — 2026-11-05

기존 Vue Composition API/TypeScript/fetch·AbortController와 Vite 프록시를 재사용했다. ProjectBrowser의 공간 ID key와 별도 목록/상세 요청으로 부모 전환 상태를 격리하고, Project DTO는 런타임 검증한다. Router/Pinia/Axios/새 라이브러리나 백엔드/마이그레이션은 추가하지 않았다. 설명의 일반 텍스트/줄바꿈 표시와 모바일 배치를 확인한다. 결과는 local-setup.md와 frontend/README.md를 따른다.


## Vue Project 생성 — 2026-11-05

Composition API의 computed/ref/emit·fetch/AbortController를 재사용했다. Project 이름/설명 정규화와 Unicode 코드 포인트 검증, 별도 결과 불명 오류 타입, 201 DTO/상대 Location 검증을 추가했다. 기존 Project 조회 파서/오류 코드를 재사용하고 생성 폼·부모 후속 조회를 분리했다. 새 라이브러리·백엔드/마이그레이션 변경은 없다. 프론트 194개와 타입 검사/빌드·실제 격리 브라우저 결과는 local-setup.md에 기록했다.


## Project 수정 설계 — 2026-11-05

기존 Spring MVC/JPA의 Long @Version, 별도 Flyway V4, PostgreSQL 컬럼 UPDATE와 기존 개발 쓰기 경계를 재사용하는 설계다. Project 한 행의 이름/설명 변경에 버전 충돌 409를 적용하고 별도 필드 병합/새 라이브러리/인증 기술을 추가하지 않는다. 상세·생성은 version 문자열을 보존하고 목록 요약은 유지한다. 아직 코드/V4/권한 도구·수정 Vue 구현 전이며 [계약](project-update-api-v1.md)과 local-setup.md에 확정/미실행 범위를 기록했다.


## Project 수정 구현 — 2026-11-05

기존 MVC/JPA @Version·OPTIMISTIC 검증·Flyway V4·PostgreSQL 컬럼 GRANT로 구현했다. 새 의존성은 없다. 상세/생성의 버전은 문자열로 전달하고 Vue 런타임 파서가 보존한다. 조회/생성 화면은 유지하며 편집 UI는 후속이다. 테스트 명령/실제 결과는 local-setup.md를 따른다.


## Vue Project 수정 연결 — 2026-11-07

기존 Vue 3 computed/ref/emit/언마운트, TypeScript, fetch/AbortController, 문자열 버전/BigInt 비교, CSS와 Vitest를 재사용했다. updateProject의 결과 불명 오류 타입과 별도 두 필드 편집 컴포넌트만 추가했으며 새 라이브러리·백엔드/SQL/권한 변경은 없다. 실제 테스트/빌드/격리 브라우저 결과는 local-setup.md를 따른다.


## 첫 Task 설계 — 2026-11-07

기존 Java 21/MVC/JPA/문자열 enum·PostgreSQL CHECK/별도 Flyway V5·명시적 최소 권한 도구를 재사용하는 설계다. 초기 네 필드의 생성/조회만 다루며 DB 전용 enum·새 라이브러리·상태 머신 프레임워크·이벤트 기술은 추가하지 않는다. status/priority는 문자열+CHECK, 부모는 스칼라 FK, 생성 트랜잭션/제한 Repository와 기존 개발 행위자/Origin 경계를 사용한다. 현재는 [계약](task-model-and-api-v1.md)만 확정했고 V5/앱/Vue/권한 구현·테스트는 후속이다.


## Task 구현 — 2026-11-07

기존 Java 21/MVC/JPA·PostgreSQL enum 문자열 CHECK·Flyway V5·별도 JavaExec/PowerShell 관리자 도구를 사용했다. 새 라이브러리는 없다. Task는 입력 정규화/서버 TODO/nullable 설명·제한 조회·쓰기 트랜잭션과 기존 JSON/Origin 정책을 재사용한다. 검증은 기존 JUnit/Testcontainers와 실제 Vite 프록시/PowerShell 경로이며 결과는 local-setup.md에 기록한다. Vue 제품 소스는 이번에 변경하지 않았다.


## Vue Task 생성 — 2026-11-08

기존 Composition API computed/ref/emit·fetch/AbortController·Vite/Vitest를 재사용한다. TaskCreateForm·createTask의 제목/설명 정규화·우선순위 select/기본 MEDIUM·201/TODO/Location 검사·결과 불명 타입을 추가했다. 부모 후속 GET과 입력/성공 상태를 분리하며 백엔드/SQL/권한·새 라이브러리는 변경하지 않는다. 실제 검증 결과는 local-setup.md를 따른다.


## Task 수정 설계 — 2026-11-08

기존 Java 21/MVC/JPA Long @Version·OPTIMISTIC no-op 검증·Flyway V6·PostgreSQL 컬럼 UPDATE와 기존 개발 쓰기 경계를 재사용한다. title/description/priority의 한 Task 충돌을 version/409로 검출하고 부모 버전은 유지한다. 상세/생성/수정 version 문자열·Vue 파서 전환을 설계한다. 새 의존성/상태 머신·이벤트 기술은 추가하지 않는다. 아직 문서 단계이며 [수정 계약](task-update-api-v1.md)을 따른다.


## Task 수정 구현 — 2026-11-08

기존 MVC/JPA @Version·OPTIMISTIC no-op 검증·PostgreSQL 컬럼 GRANT·별도 Flyway V6/관리자 JavaExec·PowerShell을 사용했다. 새 의존성은 없다. Task의 세 필드·서버 수정 시각/버전만 갱신하고 입력/역할/부모/오류 경계를 유지한다. Vue 상세/생성은 version 문자열을 런타임 검사한다. 검증은 기존 JUnit/Testcontainers·실제 Vite/PowerShell 경로와 Vitest/타입 검사·빌드이며 결과는 local-setup.md에 기록한다.


## Vue Task 수정 연결 — 2026-11-08

기존 Vue 3 computed/ref/emit/언마운트·TypeScript·fetch/AbortController·문자열 version/BigInt 검증·CSS/Vitest를 재사용했다. TaskUpdateForm/updateTask와 최신 Task/Workspace 역할 GET·세 필드 비교/수동 복구만 추가했다. 새 라이브러리·백엔드/SQL/권한 변경은 없다. 실제 결과는 local-setup.md를 따른다.


## Task 상태 변경 설계 — 2026-11-08

기존 Java 21/MVC/JPA/EntityManager·PostgreSQL 조건부 UPDATE/FK/CHECK/UNIQUE·별도 Flyway V7/관리자 도구 패턴을 사용하는 설계다. 새 라이브러리는 필요하지 않다. 일반 Task.status 수정 불가 매핑과 공유 version을 유지하고 상태 경로는 native SQL의 명시적 버전 조건/증가·영향 행 수/refresh, 이력은 generated keys/RETURNING 없이 고정 컬럼 INSERT로 계획한다. 상태와 이력의 단일 Service 트랜잭션/no-op 최종 검증·최소 컬럼 권한을 격리 DB에서 확인해야 한다. 아직 문서 단계이며 [상태 계약](task-status-api-v1.md)을 따른다.

### 2026-11-10 상태 전용 native SQL 사용

새 기술/의존성 없이 JPA EntityManager의 native SQL을 상태 전용 저장 어댑터에 한정했다. 일반 수정 매핑/최소 컬럼 경계를 보존하는 대신 @Version 자동 처리에 의존하지 않고 조건/증가/행 수·refresh를 직접 관리한다. 이력 INSERT는 generated keys/RETURNING이 없어 SELECT·sequence 권한이 필요 없다. [구현·검증](task-status-implementation.md).

### 실제 로컬 V7/상태 검증 — 2026-11-10

새 기술/의존성 없이 기존 전용 Flyway/권한 실행기와 검증된 JAR·앱 JDBC 계정으로 실제 상태 변경/이력을 확인했다. 제품 코드/SQL/JAR를 변경하지 않아 전체 회귀·빌드는 반복하지 않고 실제 로컬 migration/ACL·HTTP·DB 대조와 파일 해시를 검증했다. [실행 기록](local-setup.md).

### Vue Task 상태 UI — 2026-11-10

기존 Vue 3 computed/ref/emit/언마운트·TypeScript/fetch·AbortController·문자열 version/BigInt·CSS/Vitest를 재사용했다. 상태 전용 컴포넌트/API 호출과 교차 폼 버전/복구 가드만 추가했고 의존성/라우터/백엔드·DB 정책은 바꾸지 않았다. 실제 브라우저 검증은 기존 CDP/Chrome/Vite·고유 Compose PostgreSQL을 사용했다. [구현·검증](task-status-ui.md).

### Task 상태 이력 조회·버전 커서 설계 — 2026-11-10

새 의존성 없이 기존 MVC/JPA EntityManager native projection·readOnly 트랜잭션과 PostgreSQL 컬럼 권한을 재사용한다. 이력의 Task 버전/기존 복합 UNIQUE를 기준으로 최신순 커서를 설계했다. offset 페이지와 달리 새 기록에 의해 이전 기록의 경계가 밀리지 않지만 임의 페이지 번호 이동은 제공하지 않는다. 실제 SQL/인덱스 계획·최소 권한은 후속 격리 검증에서 확인한다. [확정 계약](task-status-history-read-api-v1.md).

### Task 상태 이력 조회 구현 — 2026-11-10

기존 Spring MVC·JPA EntityManager native projection·readOnly 트랜잭션/PostgreSQL 컬럼 ACL을 재사용했다. 시각의 native JDBC 매핑 차이는 UTC to_char/Instant 변환으로 명시하고 size+1/버전 커서로 전체 count 없이 조회한다. 새 의존성·migration·인덱스는 추가하지 않았다. 기존 Testcontainers/실제 Vite 프록시·PowerShell 실행기 회귀로 검증하며 [구현 기록](task-status-history-implementation.md)에 실제 결과를 구분한다.

### 실제 로컬 이력 조회 — 2026-11-10

PowerShell 전용 로컬 provisioning wrapper로 컬럼 SELECT만 적용했다. 앱은 기존 검증 JAR·flowdesk_app 환경 설정으로 별도 loopback 포트에서 실행했고 GET/Actuator를 검증한 뒤 해당 Java 프로세스만 종료했다. 전체 DB snapshot은 Apply 후·HTTP 후 모두 일치했다. [실행 기록](local-setup.md).
### Vue Task 이력 UI — 2026-11-10

새 dependency 없이 Vue Composition API/ref/watch/onUnmounted, TypeScript, 기존 Task fetch/API 오류·AbortController와 CSS를 사용했다. 응답을 envelope/actor/status/time/cursor 경계로 검증하고 `Intl.DateTimeFormat`으로 표시하며 원래 UTC ISO를 `<time datetime>`에 둔다. 20건 version cursor 페이지를 append한다. `npm.cmd run build`에서 vue-tsc/Vite production build를 확인했다. Vitest와 browser verification은 이 작업에서는 실행하지 않았다. [구현·검증](task-status-history-ui.md).

### 담당자·마감일 저장 타입 — 2026-11-10

담당자 참조는 nullable User ID와 DB FK로 저장하며, 자격은 요청의 Task가 속한 Workspace membership을 Service가 확인한다. 달력 날짜는 Java `LocalDate`/PostgreSQL `date`로 표현해 timezone 변환에 따른 날짜 이동을 피한다.

### Task assignee/due-date API 검증 — 2026-11-11

Task 경로는 strict JSON body를 사용한다. 담당자 ID는 정규 십진 문자열, 날짜는 정확한 ISO date와 LocalDate 파싱으로 검사한다. Task row 하나의 `@Version`을 재사용하므로 두 필드는 기존 수정/상태 변경과 같은 stale 409 경계를 공유한다. ACL 전환은 기존 table-level grants 회수와 PostgreSQL column grants를 한 트랜잭션의 별도 관리자 실행기로 처리한다.

V8 migration/ACL/API 집중 백엔드 검증 89개와 Vue 484개 테스트, vue-tsc/Vite build가 통과했다. 전체 백엔드 회귀는 이번 범위에서 실행하지 않았다. 실제 로컬 V8·권한 적용은 별도 단계다. [구현·격리 결과](task-assignee-due-date-implementation.md).

### Workspace 멤버 목록 계약 — 2026-11-11

멤버 후보는 `WorkspaceMembership`과 User의 표시 이름을 DTO projection으로 읽는다. 기존 0-based page/size/hasNext slice를 재사용하고 ID 오름차순을 고정한다. 이메일은 반환하지 않는다. 사용자 목록용 새 프레임워크나 별도 캐시는 도입하지 않는다. [API 계약](workspace-member-list-api-v1.md).

### Workspace 멤버 목록 구현/권한 — 2026-11-12

Spring Data native interface projection으로 목록 응답에 필요한 세 열만 조회하고 PostgreSQL의 column SELECT ACL로 기존 세 identity/workspace 테이블의 16개 mapped 열만 허용한다. 권한은 별도 migration source-set JavaExec/PowerShell wrapper가 트랜잭션으로 적용하며 Spring 부팅은 GRANT하지 않는다. 격리 결과는 [구현 기록](workspace-member-list-implementation.md).

### 담당자 선택 UI — 2026-11-13

Vue의 native select와 paged fetch를 사용한다. 100명 단위 API page를 strict parser로 검증하며 별도 UI framework나 중앙 store는 추가하지 않았다. 기존 할당이 현재 멤버 목록에 없을 때 별도 option으로 보존해 낡은 ID를 신규 후보와 혼동하지 않는다.

### Workspace 멤버십 관리 정책 — 2026-11-13

초대 코드는 기존 Java 21 `SecureRandom`/`MessageDigest` 표준 API로 256비트 이상 생성 후 digest만 저장하는 방향이다. 새 외부 crypto/mail 라이브러리는 추가하지 않는다. Workspace 행 잠금과 Service `@Transactional`을 OWNER 이전과 invitation acceptance에 적용하고, membership 제거와 Task assignee 재지정은 같은 공간 상태를 직렬화한다. API·schema·권한 구현/검증은 후속이며 [계약](workspace-membership-management-v1.md)을 따른다.
