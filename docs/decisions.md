# Architecture Decision Log

## ADR-001 — 모듈형 모놀리스로 시작
- 날짜: 2026-11-02
- 상태: 채택
- 맥락: 인증·협업·이벤트 기술을 단계적으로 학습하되 초기 복잡도를 통제해야 함.
- 결정: Spring Boot 단일 배포 애플리케이션에 도메인별 모듈 경계를 둔다.
- 대안: 초기부터 MSA.
- 근거: 개발·테스트·운영 복잡도 감소, 이후 분리 기준을 실제 요구로 검증 가능.

## ADR-002 — Vue 3 + TypeScript
- 날짜: 2026-11-02
- 상태: 채택
- 맥락: 프론트엔드도 학습하면서 백엔드에 더 깊게 집중.
- 결정: Vue 3 + TypeScript + Vite.
- 대안: React, Angular.
- 근거: 명료한 컴포넌트 구조와 빠른 개발 피드백.

## ADR-003 — Spring MVC + JPA를 초기 기준선으로 사용
- 날짜: 2026-11-02
- 상태: 채택
- 맥락: WebFlux 학습은 필요하지만 초기부터 모든 I/O 모델을 혼합하면 비교가 어려움.
- 결정: 초기에는 MVC + JPA, 후속 별도 WebFlux 실험.
- 대안: 전체 WebFlux + R2DBC.
- 근거: 트랜잭션/ORM 기본기를 확보하고 비교 기준선을 마련.

## ADR-004 — 로컬 JDK와 Node 실행 환경
- 날짜: 2026-11-02
- 상태: 채택
- 맥락: Java 17과 Node 14가 실행되고 있어 문서의 Java 21 및 현재 Vue/Vite 개발환경 요구를 충족할 준비가 필요했다. 사용자가 다운로드와 설치를 승인했다.
- 결정: Temurin JDK 21.0.13+9를 winget으로 설치하고, 기존 Volta로 Node 24.22.2와 포함된 npm 11.19.1을 사용한다. 기존 Docker Desktop을 시작해 Linux 엔진 연결을 확인한다. Gradle은 백엔드 생성 시 Wrapper로 고정한다.
- 대안: 다른 OpenJDK 배포판, Node MSI 설치, Gradle 전역 설치.
- 근거: Temurin은 OpenJDK 21 배포판이며 공식 설치 패키지를 제공한다. 기존 Node 버전 관리 도구를 재사용하고, Gradle은 프로젝트 단위 재현성을 확보한다.
- 결과/재검토 조건: 기존 Java 8·17은 유지했다. 시스템 JAVA_HOME/PATH에 Java 21이 반영되고 Volta 기본 Node가 변경되므로 기존 프로젝트 영향은 확인이 필요하다. Java와 Node/npm 실행 및 Docker 엔진 연결은 검증했다. 프론트엔드 생성 시 Node 버전을 프로젝트에 고정하고 백엔드 생성 시 Boot/Wrapper 호환성을 확인한다. 상세 명령과 미검증 항목은 `local-setup.md`에 기록한다.

## ADR-005 — 첫 HTTP API와 백엔드 빌드 기준선
- 날짜: 2026-11-02
- 상태: 채택
- 맥락: 사용자가 백엔드 최소 뼈대와 health API 구현·검증을 승인했다. 요청 처리 과정을 학습하고 DB·인증 없이 서버 실행을 재현할 필요가 있다.
- 결정: Java 21 toolchain, Spring Boot 3.5.16, Gradle Wrapper 8.14.3을 사용한다. Boot BOM은 Gradle 기본 platform 기능으로 적용한다. Spring Web과 Boot Test만 포함한다. `GET /api/health`는 Controller에서 `HealthResponse` record를 반환한다. 개발 서버는 127.0.0.1:8080으로 실행한다.
- 대안: Initializr로 최신 기본값 생성, Boot 4.x로 변경, Actuator health 사용, DB 의존성까지 한 번에 추가.
- 근거: 조회한 Initializr 목록에는 4.x만 있었으므로 기존 3.x 계획을 유지해 파일을 직접 작성했다. 공식 저장소에서 Boot 아티팩트를 확인했고, Gradle 공식 Wrapper JAR의 SHA-256과 배포본 체크섬을 검증·고정했다. Controller는 매핑과 DTO 직렬화를 직접 학습하기 적합하다.
- 결과/재검토 조건: 실제 포트를 여는 HTTP 통합 테스트에서 GET 응답 계약과 POST 405를 검증했다. `UP`은 앱 응답 가능 여부만 뜻하며 DB 상태는 나타내지 않는다. 운영 상태 점검 단계에는 Actuator 및 HealthIndicator 사용을 검토한다. 업무 API·보안·DB 연결은 이번 범위 밖이다.

## ADR-006 — Controller와 Actuator health를 함께 검증
- 날짜: 2026-11-02
- 상태: 채택
- 맥락: 사용자가 Actuator로도 HTTP 상태 점검을 검증하고 두 방식의 차이를 학습하길 요청했다.
- 결정: Boot BOM이 관리하는 spring-boot-starter-actuator를 추가한다. 기존 `/api/health`를 유지하고 `/actuator/health`와 비교한다. HTTP 노출 목록은 health만 명시하고 세부 상태는 숨긴다. 기존 루프백 바인딩을 유지한다.
- 대안: Controller를 제거하고 Actuator로 대체, 모든 관리 엔드포인트 노출, 사용자 정의 HealthIndicator를 즉시 추가.
- 근거: 작은 변경으로 직접 작성한 고정 응답과 프레임워크가 종합한 상태 응답을 비교할 수 있다. DB 없이 임의 장애 로직을 추가할 필요는 없으며 노출 범위도 상태 점검에 한정한다.
- 결과/재검토 조건: 임의 포트 통합 테스트에서 Actuator의 JSON·UP과 metrics 404를 검증한다. 응답의 UP은 등록된 검사 범위에서의 정상이며 DB나 모든 업무 기능의 정상 동작을 뜻하지 않는다. DB 연결 후 장애 상태와 503 응답은 별도 검증한다.

## ADR-007 — VS Code의 Gradle 의존성 자동 반영
- 날짜: 2026-11-02
- 상태: 채택
- 맥락: Gradle 빌드 JAR에는 Actuator가 있지만 IDE 실행 인수의 클래스패스에는 없어 health가 404였다. 사용자가 우회 실행을 넘어 IDE 원인 조사와 검증을 요청했다.
- 결정: 기존 작업 공간 설정의 java.configuration.updateBuildConfiguration을 interactive에서 automatic으로 바꾸고, java.import.gradle.java.home을 ${env:JAVA_HOME}으로 지정한다. JAVA_HOME은 설치한 JDK 21을 사용한다.
- 대안: 의존성 변경마다 Java: Reload Projects 수동 실행, JAR 또는 bootRun으로만 실행, Gradle/Boot 메이저 버전 변경.
- 근거: 기존 모드는 변경마다 사용자 확인을 요청한다. 초기 IDE 가져오기에는 Java 25와 Gradle 8.14.3 호환 실패 로그도 있어, Gradle 실행 JVM을 개발환경과 맞춘다. 사용자 전역 설정과 애플리케이션 코드는 변경하지 않는다.
- 결과/재검토 조건: 설정 변경 후 Java 언어 서버가 프로젝트 갱신 성공을 기록했고 IDE 클래스패스 모델에서 Actuator 및 자동 설정 JAR를 확인했다. 실제 Run Java 실행 인수와 HTTP 응답의 검증 결과는 local-setup.md에 별도 기록한다. 자동 가져오기는 build.gradle 변경 시 비용이 들지만 작은 단일 백엔드 프로젝트에서는 의존성 일관성을 우선한다.

## ADR-008 — 첫 Vue 화면과 개발용 API 연결
- 날짜: 2026-11-02
- 상태: 채택
- 맥락: 사용자가 다음 단계인 Vue 최소 화면에서 기존 health API 호출을 승인했다. 화면과 HTTP 처리 흐름을 작은 단위로 검증해야 한다.
- 결정: create-vue로 Vue 3 Composition API·TypeScript·Vite 뼈대를 생성한다. API 함수와 상태 카드 컴포넌트의 책임을 나눈다. fetch로 상대 경로 /api/health를 요청하고, Vite 개발 프록시로 127.0.0.1:8080에 전달한다. Vite는 127.0.0.1:5173에만 바인딩한다. 백엔드 CORS 설정은 이번 변경에서 수정하지 않는다.
- 대안: Axios 추가, 브라우저에서 8080에 직접 접근하며 백엔드 개발 CORS 정책 추가, 초기 Router·Pinia·UI 라이브러리 도입.
- 근거: 단일 조회에는 fetch와 로컬 컴포넌트 상태로 충분하다. 프록시는 개발 중 브라우저의 동일 출처 요청을 유지하고 보안 정책 확정과 업무 인증 도입을 분리한다. 운영 환경의 프록시/CORS 정책은 아직 결정하지 않는다.
- 결과/재검토 조건: 서버 응답과 로딩·오류 상태를 분리하고, HTTP 오류·잘못된 응답 구조·5초 제한을 처리한다. 테스트 5개, 타입 검사·빌드, 실제 브라우저와 Spring 연결 검증을 완료했다. Vitest는 취약점 수정 버전 4.1.12를 사용하고 Vue Test Utils 대신 Vue createApp으로 테스트해 불필요한 간접 의존성을 제거했다. 의존성 잠금 및 Node 버전을 고정했다. 운영 배포, 인증, 공통 서버 상태 관리가 필요해지면 관련 결정을 다시 검토한다.

## ADR-009 — 로컬 PostgreSQL을 독립 Compose 프로젝트로 준비
- 날짜: 2026-11-02
- 상태: 채택
- 맥락: 사용자가 Docker Compose PostgreSQL 기동 및 연결 검증 진행을 승인했다. Docker에는 기존 타 프로젝트 컨테이너가 있어 격리가 필요했다.
- 결정: postgres:17.11-bookworm을 flowdesk-local 프로젝트에 추가한다. 127.0.0.1:5432와 전용 명명 볼륨을 사용한다. 로컬 초기화 관리자 postgres와 DB flowdesk를 준비하며 비밀번호는 스크립트로 생성해 Git 제외 .env에 저장한다. 기존 파일은 덮어쓰지 않는다.
- 대안: 호스트에 PostgreSQL 직접 설치, 기존 DB 재사용, 18 계열 선택, localhost 외 인터페이스에 포트 공개.
- 근거: Compose로 DB 기동을 재현하고 기존 프로젝트를 분리한다. 지원 중인 17 계열의 버전을 고정해 향후 연결·마이그레이션 학습의 기준선을 만든다. 상태 검사와 인증 SQL을 나눠 검증한다.
- 결과/재검토 조건: healthy, 인증 SQL의 SELECT 1과 잘못된 비밀번호 거부, 호스트 TCP 포트 및 볼륨 연결을 검증했다. 관리자는 로컬 초기화용이며 애플리케이션 권한 정책은 아직 확정하지 않았다. Spring 연동 전 계정 권한을 결정한다. DB 메이저 버전 변경 및 볼륨 삭제는 별도 승인과 계획이 필요하다.

## ADR-010 — 최소 권한 앱 계정과 JDBC 기반 DB 상태 검사
- 날짜: 2026-11-02
- 상태: 채택
- 맥락: 사용자가 앱 권한 결정과 Spring DataSource·Actuator DB 검증을 요청했고, 추가 질문에 최소 권한과 향후 DDL 계정 분리를 선택했다.
- 결정: flowdesk_app은 로그인 가능한 비관리자 역할로 만들고 FlowDesk DB의 CONNECT만 부여한다. 이 DB의 PUBLIC 기본 CONNECT·TEMP 권한과 public 스키마 권한을 제한하며 DDL·테이블 CRUD는 부여하지 않는다. 앱 비밀번호는 관리자와 별도로 생성해 backend/.env에 보관한다. JDBC starter·PostgreSQL 드라이버만 추가하고 JPA·Flyway는 도입하지 않는다.
- 대안: 관리자 공유, 앱 계정에 DDL 허용, JPA·마이그레이션까지 한 번에 도입, 로컬 Compose DB를 테스트마다 중지.
- 근거: SELECT 1과 연결 검증에는 테이블 권한이 필요 없다. 기본 PUBLIC 권한을 고려해야 실제 최소 권한이 된다. JDBC/DataSource/HikariCP의 연결 흐름을 먼저 확보하고 실제 DB 검사는 Actuator 자동 설정을 사용한다.
- 결과/재검토 조건: 운영용 권한 정책이 아닌 현재 로컬 FlowDesk DB의 기준선이다. 클러스터의 다른 DB PUBLIC 접속 정책은 바꾸지 않았다. 이후 업무 스키마 USAGE·DML 및 별도 Flyway 계정 권한은 해당 단계에서 확정한다. Testcontainers의 격리 DB에서 동일 정책·DDL 거부·장애/복구를 검증했다. 총 7개 테스트, 로컬 JAR 연결, IDE 실행 클래스패스와 8080의 앱 계정 세션을 확인했다. 상세 정보 비노출을 유지한다.

## ADR-011 — 첫 업무 모델과 Flyway V1의 범위
- 날짜: 2026-11-02
- 상태: 채택(모델·범위). SQL 구현·설치·적용은 미실행.
- 맥락: 사용자가 첫 업무 도메인의 데이터 모델과 Flyway 범위 확정을 요청했다. 질문에 개발용 seed/인증 Phase 2, 정규화 이메일 유일성, 워크스페이스 단일 OWNER를 선택했다.
- 결정: flowdesk 스키마의 users·workspaces·workspace_memberships 3개 테이블과 필수 PK/FK/CHECK/UNIQUE/인덱스만 V1 대상으로 한다. bigint identity와 timestamptz를 사용하고 업무 역할은 membership에 둔다. OWNER 부분 UNIQUE는 최대 1명을, 향후 Service 트랜잭션은 최소 1명을 보장한다.
- 대안: Project·Task까지 한 번에 설계, 현재 비밀번호 해시 포함, 복수 OWNER, 복합 membership PK, 소유자를 workspace 컬럼에도 중복 저장, constraint trigger로 최소 1명까지 DB에서 강제.
- 근거: 먼저 협업 공간과 소속 관계를 작게 검증한다. 인증과 개발 seed를 분리하고, 동일 사실의 중복 저장과 불필요한 복합 식별자·트리거를 피한다. 동시 중복은 DB 제약으로, 여러 행을 묶는 업무 불변식은 트랜잭션으로 다룬다.
- 결과/재검토 조건: 상세 필드·검증 계획은 domain-model-v1.md에 기록했다. Flyway 이력·업무 테이블은 별도 migrator가 소유하고 앱에는 아직 새 DML 권한을 부여하지 않는다. seed·계정 생성·JPA·API는 V1 SQL 밖에 둔다. 마이그레이션 실행 방식과 DDL 자격증명 주입은 구현 전에 확정한다. 단일 OWNER의 DB 직접 쓰기 보장이나 이름/삭제/권한 정책 변경은 해당 기능 때 재검토한다.

## ADR-012 — V1을 테스트 범위의 Flyway로 먼저 검증
- 날짜: 2026-11-02
- 상태: 채택
- 맥락: 사용자가 V1 SQL 작성과 격리 PostgreSQL의 마이그레이션·제약조건 검증을 요청했다. 로컬 DB 적용과 DDL 자격증명 주입 방식은 아직 확정하지 않았다.
- 결정: 확정 모델의 V1 SQL을 main resources에 추가하고 Flyway core/PostgreSQL 모듈은 테스트 의존성으로만 추가한다. 테스트용 비관리자 migrator와 전용 스키마를 준비하며 케이스마다 새 DB를 사용한다. 기존 Spring 통합 테스트의 자동 마이그레이션은 명시적으로 끈다.
- 대안: 앱 런타임에 Flyway를 바로 추가해 부팅 때 로컬 DB에 적용, 관리자 계정으로만 검증, 공유 로컬 스키마에 테스트 데이터 작성.
- 근거: SQL·권한·이력 검증과 로컬 적용을 분리해 작고 재현 가능한 변경을 유지한다. migrator 스키마 소유권과 앱 DDL 금지를 실제로 확인한다. V1에 schema/role/seed 생성이나 권한 확대를 넣지 않는다.
- 결과/재검토 조건: 새 적용·재실행·checksum 불일치·PK/FK/CHECK/UNIQUE/RESTRICT·identity·앱 접근 거부 검증 포함 24개가 통과했다. 기존 포함 31개 테스트 성공. 로컬 DB의 업무 스키마·테이블·migrator는 모두 0개로 유지된다. 로컬 적용은 실행 방식과 DDL 자격증명 경계를 정한 뒤 별도 작업으로 진행한다.

## ADR-013 — 로컬 Flyway 실행과 migrator 자격증명 전달 경계
- 날짜: 2026-11-02
- 상태: 채택(절차). 실행기 구현·자격증명 생성·로컬 DB 적용은 미실행.
- 맥락: 사용자가 로컬 적용 방식과 자격증명 주입 절차 확정을 요청했고, 질문에 별도 작업·별도 자격증명 분리를 선택했다.
- 결정: 일반 앱 부팅은 flowdesk_app만 사용한다. 로컬 마이그레이션은 별도 Gradle JavaExec/Java main으로 명시적으로 실행한다. migrator 자격증명은 Git 제외 infra/.env.migrator에 저장하고 전용 실행기가 자식 프로세스 환경에만 전달한다. 시스템/사용자/부모 셸 환경이나 앱 설정에 등록하지 않는다.
- 대안: 앱 부팅 때 다른 계정으로 자동 실행, 앱 파일에 DDL 자격증명 추가, 비밀번호를 명령행 인수로 전달, 별도 Flyway CLI·Gradle 플러그인 도입.
- 근거: 검증한 Flyway Java API와 Gradle Wrapper를 재사용해 앱 런타임과 DDL 실행 책임을 분리한다. 전용 migration source set/클래스패스로 최소 실행기를 구성할 예정이며 일반 build/test/bootRun이 로컬 DDL을 실행하지 않게 한다.
- 결과/재검토 조건: Info → Migrate → Validate → Info 순서와 최소 권한·bootstrap/재실행·실패 처리·검증 기준을 local-migration.md에 확정했다. clean/repair/baseline은 노출하지 않는다. 파일은 평문 로컬 개발용이며 같은 OS 사용자와의 강한 격리를 뜻하지 않는다. 운영 비밀정보 관리·배포 실행 방식은 후속이다. 다음 작업은 이 절차의 구현과 격리 검증이며 실제 로컬 적용은 아직 하지 않았다.

## ADR-014 — 전용 migration 실행기와 보수적인 bootstrap 구현
- 날짜: 2026-11-03
- 상태: 채택·구현. 실제 로컬 DB 적용은 별도 작업.
- 맥락: 사용자가 ADR-013의 프로비저닝·실행기 구현과 격리 PostgreSQL 검증을 요청했다.
- 결정: PowerShell 7 실행기가 엄격한 파일 형식과 실행 중 Compose 포트를 검사한 뒤 자식 프로세스 환경에 필요한 키만 전달한다. Gradle migration source set과 Java 21 JavaExec가 Spring 없는 Java main을 실행한다. Inspect/Provision만 관리자 자격증명을 받고 Info/Validate/Migrate는 migrator 값만 받는다. 일반 앱과 build/test는 로컬 DDL을 실행하지 않는다.
- 대안: 별도 CLI/플러그인 도입, PowerShell에서 비밀번호가 들어간 SQL을 직접 조립, 기존 계정을 새 비밀번호로 자동 복구, PUBLIC 권한을 전용 프로비저닝에서 일괄 변경.
- 근거: 기존 Wrapper·Flyway API를 재사용하고 PreparedStatement/트랜잭션 한정 세션 변수로 비밀번호를 전달한다. CreateNew 파일 기록과 DB 트랜잭션은 별개이므로 실패 때 파일을 남겨 재시도한다. advisory transaction lock으로 같은 DB의 bootstrap을 직렬화한다. 다른 계정 권한을 바꾸는 대신 기존 앱 권한 정책이 준비되지 않았으면 중단한다.
- 결과/재검토 조건: 전용 검증 스크립트가 고유 Compose 프로젝트·임시 포트에서 실제 래퍼 경로를 실행하고 검증용 볼륨을 정리한다. 자격증명/대상·소유권·역할·스키마 권한 불일치와 checksum 오류는 실패 종료한다. 오류 출력은 허용된 분류만 전달한다. 향후 앱에 DML을 부여할 때 스키마 ACL 사전 검사와 이 검증을 함께 변경해야 한다. 현재 스키마 ACL 검사는 소유자 외 grant를 거부한다. 운영 환경·다른 DB에 대한 접근 제한은 이번 결정 범위가 아니다.

## ADR-015 — 검증된 전용 실행기로 로컬 V1 적용
- 날짜: 2026-11-03
- 상태: 적용·검증 완료
- 맥락: 전용 실행기와 격리 DB 25개 검증 완료 후 사용자가 로컬 DB의 migrator 준비·V1 적용·결과 확인을 명시적으로 요청했다.
- 결정: 기존 flowdesk-local PostgreSQL 볼륨을 유지하고 127.0.0.1:5432/flowdesk에만 적용한다. 관리자 프로비저닝과 migrator Flyway 실행을 분리하며 앱 계정 권한은 확대하지 않는다.
- 대안: 앱 부팅 때 적용, 관리자 계정으로 V1 실행, 검증용 DB 결과만으로 로컬 적용 성공을 추정.
- 근거: 기존 계정 분리 정책과 검증된 실행 경로를 실제 개발 DB에도 동일하게 사용한다. 스키마 적용 성공과 앱 연결 성공은 서로 다른 검증으로 확인한다.
- 결과/재검토 조건: migrator 파일·역할·스키마 준비, V1 1건 적용/Validate 성공/Info SUCCESS/재실행 0건. 세 업무 테이블과 history는 migrator 소유이며 업무 데이터는 0건이다. 앱의 테이블·이력 권한 부재와 SELECT/DDL 42501 거부, 임시 앱의 Actuator 200/UP 및 실제 flowdesk_app JDBC 세션을 확인했다. 다음 DDL 변경은 V2 이후로 추가한다. seed·JPA·앱 DML 권한은 별도 작업이다.

## ADR-016 — 개발 seed의 고정 예제와 비파괴적 수동 실행
- 날짜: 2026-11-03
- 상태: 채택(설계). seed 구현·삽입은 미실행.
- 맥락: 로컬 V1 적용 후 사용자가 개발용 seed 데이터와 실행 범위 확정을 요청했다. 실행 계정과 재실행 정책을 질문해 기존 migrator 재사용·로컬 수동 실행 및 빈 테이블 최초 삽입/전체 일치 0건/불일치 중단을 선택받았다.
- 결정: 사용자 5명·workspace 2개·membership 5개를 예제로 둔다. A에는 Alice OWNER/Bob ADMIN/Carol MEMBER/Dave VIEWER, B에는 Eve OWNER만 둔다. 기존 migration source set을 재사용하는 별도 seed Java main/JavaExec/PowerShell 작업을 계획한다. 앱·관리자 자격증명은 전달하지 않고 기존 migrator 파일을 전용 자식 환경으로 읽는다.
- 대안: 사용자만 최소 seed, Flyway/data.sql·앱 부팅 자동 INSERT, DML 전용 seeder 계정, 누락 예제 추가/upsert, 기존 데이터 삭제 후 재삽입.
- 근거: 네 역할과 팀 경계를 보여주는 작은 예제를 준비하되 인증/인가 구현으로 혼동하지 않는다. 이름이 유일하지 않은 workspace를 기존 데이터와 자동 병합하지 않는다. 별도 DML 계정보다 넓은 migrator 권한은 로컬 수동 작업이라는 범위에서 사용자 선택으로 수용하며 앱 권한은 유지한다.
- 결과/재검토 조건: 고정 ID·V1 변경·seed 이력 테이블 없이 DB 생성 ID와 기존 시간 기본값을 쓴다. Apply는 세 테이블 잠금과 단일 트랜잭션으로 판정/삽입/OWNER 수 확인 후 커밋하며 실패 시 롤백한다. Verify는 조회 검증만 한다. 실행기는 아직 없고 실제 DB에 삽입하지 않았다. 상세 명세와 격리 검증 기준은 dev-seed.md에 기록한다. 운영용 초기 데이터·새 schema 버전·reset·유연한 seed 보충이 필요해지면 다시 설계한다.

## ADR-017 — seed 실행기의 단일 트랜잭션과 실제 프로세스 검증
- 날짜: 2026-11-03
- 상태: 구현. 실제 로컬 seed는 미적용.
- 맥락: 사용자가 ADR-016의 seed 실행기와 격리 PostgreSQL 검증을 요청했다.
- 결정: seed-local.ps1 → 고정 Seed용 자식 스크립트 → runSeed JavaExec → LocalSeedRunner를 구현한다. 환경·대상 검증은 migration-common.ps1, 역할/소유권 검사는 LocalMigrationRunner.verifyMigrator를 재사용한다. 공유 PowerShell 실행의 Kind/Action은 두 작업의 고정 목록만 허용한다.
- 대안: 앱 런타임 seed/관리 HTTP 엔드포인트, migration main에 데이터 작업 합치기, 별도 seed 프레임워크/계정/스키마, JDBC 내부 함수만 모의 테스트.
- 근거: 이미 확정된 클래스패스·자격증명 분리 경계를 유지하면서 실제 entry point의 오류 종료·정보 비노출·권한을 검증한다. Apply는 업무 테이블 잠금과 history SHARE 잠금 뒤 상태/버전을 다시 확인하고 전체 삽입/OWNER 검증을 커밋한다. Verify는 읽기 전용 REPEATABLE READ로 전체 관계를 읽는다. 현재 V1만 허용하며 데이터·history를 자동 교정하지 않는다.
- 결과/재검토 조건: seed Java main 테스트 31개와 기존 포함 전체 JUnit 62개가 통과했다. 예제 12행/반복 0건, 원자적 롤백·불일치 보존·동시 실행·잠금 시간 초과와 스키마/이력 오류를 검증한다. PowerShell 실제 경로는 기존 migration 회귀에 IncludeSeed를 더해 검증한다. 실제 명령/최종 결과는 local-setup.md에 기록한다. 앱 JAR·V1·앱 권한·로컬 데이터는 유지한다. V2 이후에는 seed 호환 범위를 명시적으로 갱신한다.

## ADR-018 — 개발 seed의 실제 로컬 Apply·Verify
- 날짜: 2026-11-03
- 상태: 적용·검증 완료
- 맥락: 격리 seed 테스트 31개·기존 포함 JUnit 62개·PowerShell 회귀/seed 경로 33개 검증 후 사용자가 로컬 seed Apply·Verify 실행을 요청했다.
- 결정: 기존 flowdesk-local / 127.0.0.1:5432 / flowdesk의 V1·빈 업무 테이블을 확인하고 기존 migrator로 명시적 Apply·Verify를 실행한다. 앱 권한이나 V1을 변경하지 않는다.
- 대안: 앱 부팅 자동 seed, 관리자 직접 INSERT, 실제 로컬 적용을 격리 테스트 결과로 대체해 추정.
- 근거: 확정된 로컬 수동 실행 경계를 유지하고 실제 DB의 준비 상태·관계·재실행을 독립적으로 검증한다.
- 결과/재검토 조건: Apply 12행(5명/2개 공간/5개 멤버십), Verify 성공, 반복 Apply 0건. 각 OWNER 1명과 확정 관계, 재실행 전후 ID·timestamps·값 불변을 확인했다. Flyway history·앱 권한·자격증명 파일·원본 V1·부모 환경은 유지됐다. 이후 예제/업무 데이터가 바뀌면 기존 정책대로 중단하며 reset/merge를 자동 수행하지 않는다. JPA/API·인증/인가·앱 DML 권한은 별도 범위다.

## ADR-019 — V1에 맞춘 조회 전용 JPA와 최소 읽기 권한 설계
- 날짜: 2026-11-03
- 상태: 채택(설계). JPA 코드/의존성·DB 권한 변경은 미실행.
- 맥락: 사용자가 User·Workspace·Membership의 JPA 매핑과 Repository 범위 확정을 요청했다. 보안/범위 질문에 조회 전용 Repository·SELECT 최소 권한 설계를 선택했다.
- 결정: 엔티티 3개와 WorkspaceRole enum을 V1에 맞추고 Membership에서 User/Workspace로 단방향 LAZY를 둔다. Long IDENTITY, Instant, varchar 문자열 enum을 사용한다. Spring Data Repository에 조회 5개만 선언하며 readOnly 트랜잭션/OSIV 비활성·필요한 workspace EntityGraph를 계획한다. 앱 목표 권한은 schema USAGE+3개 업무 테이블 SELECT이고, DML/DDL/sequence/history는 금지한다.
- 대안: 처음부터 JpaRepository 전체 CRUD·쓰기 Service, ID 참조만 두는 매핑, 양방향/EAGER/cascade, Hibernate schema update, 조회 때도 migrator 사용.
- 근거: 이미 적용한 SQL/seed와 ORM 조회를 작게 검증하고 미확정 쓰기/인가 규칙을 앞당기지 않는다. 조회 Entity·LAZY·트랜잭션과 DB 최소 권한을 구분해 학습한다. 사용자별 리소스 인가는 후속 서버 계층의 별도 책임이다.
- 결과/재검토 조건: 상세 매핑/메서드/격리 테스트는 jpa-read-model-v1.md에 확정했다. 기존 migration/seed의 소유자 전용 ACL 검사는 새 앱 USAGE와 충돌하므로 승인된 CONNECT-only/READ-only 상태를 구분하도록 함께 구현·회귀 검증한다. 실제 로컬 GRANT는 그 이후 요청 범위에서 한다. V1·seed 데이터·현재 앱 권한은 이번에 바꾸지 않았다. 쓰기/OWNER Service·API·페이지 입력·V2 도입 시 해당 계약을 별도로 확장한다.

## ADR-020 — 조회 JPA와 두 상태의 앱 권한 정책 구현
- 날짜: 2026-11-03
- 상태: 구현·격리 검증 완료. 실제 로컬 READ 권한은 미적용.
- 맥락: 사용자가 ADR-019의 매핑/Repository·권한 정책 구현과 격리 PostgreSQL 검증을 요청했다.
- 결정: 3개 엔티티/enum·조회 5개, validate/OSIV 비활성 설정과 전용 AppReadAccessRunner/정책 SQL/PowerShell 도구를 구현한다. 기존 migration/seed는 공통 AppReadPolicy로 CONNECT-only 또는 정확한 READ-only만 허용한다. 관리자 프로비저닝에서 앱 비밀번호를 인증하고 현재 V1/ACL 상태를 확인한 뒤 지정된 GRANT만 한 트랜잭션으로 실행한다.
- 대안: JpaRepository 전체 CRUD, Hibernate update/create, 조회에도 migrator 사용, ACL 검사를 제거해 모든 grant 수용, 권한 자동 회수/보충, 로컬을 테스트 DB로 사용.
- 근거: 확정된 V1과 최소 읽기 권한을 실제 앱 계정의 ORM으로 확인하고, 기존 도구가 승인된 권한 전환 후에도 동작하게 한다. 조회 트랜잭션은 보안 경계가 아니므로 JDBC/EntityManager 쓰기 경로도 DB에서 거부함을 검증한다. schema/table/column/default ACL·PUBLIC·역할 멤버십·grant option을 함께 검사하며 예기치 않은 권한은 자동 교정하지 않는다.
- 결과/재검토 조건: 전체 build/JUnit 96개와 PowerShell 실제 경로 41개 검증이 통과했다. Query/LAZY/Slice/ToOne fetch/쓰기 거부/validate 미복구, 정상 전환·반복·잘못된 권한 거부·migration/seed 두 상태 호환을 확인했다. 앱 JAR에는 privileged runner/정책/Flyway/자격증명이 없다. 실제 로컬은 데이터 5/2/5·V1 성공 이력 1건·CONNECT-only다. 로컬 GRANT·새 앱 기동/조회는 다음 요청 범위에서 별도로 검증한다. HTTP/API 인가·쓰기·V2는 후속이다.

## ADR-021 — 실제 로컬 READ 적용과 새 JPA 앱 조회 검증
- 날짜: 2026-11-03
- 상태: 적용·검증 완료
- 맥락: 사용자가 격리 검증된 읽기 권한 정책을 실제 로컬에 적용하고 새 JPA 앱 기동·조회를 확인하도록 요청했다.
- 결정: 기존 flowdesk-local / 127.0.0.1:5432 / flowdesk에 전용 권한 도구를 최초/반복 실행한다. 업무 HTTP API를 추가하지 않고 build 아래 임시 검증 main이 새 앱 JAR의 클래스/의존성과 같은 SpringApplication 구성을 사용해 실제 Repository 계약을 호출한다. 검증 후 임시 앱을 종료한다.
- 대안: 임시 공개 업무 HTTP 엔드포인트 추가, health UP만으로 Repository 성공을 추정, 앱 코드에 권한 GRANT/관리자 자격증명 추가, IDE에서 실행했다는 가정만으로 결과 보고.
- 근거: 확정된 인증/인가 경계를 유지하면서 실제 로컬 ORM/DB 계정/조회 결과를 직접 확인한다. 관리자 GRANT 작업과 앱 읽기 프로세스의 자격증명을 분리하고 0행 DML·롤백으로 쓰기 거부를 확인해 업무 데이터를 보존한다.
- 결과/재검토 조건: READ_ONLY 최초/반복 성공, 실제 Repository 5개 계약/Alice·Eve 관계/empty 결과·DataSource flowdesk_app 확인. DML/DDL/history 42501 거부, health 두 경로 200/UP. migration Validate/재실행 0건과 seed Verify/반복 Apply 0건을 확인했다. 데이터/ID/timestamps/history·자격증명 파일/V1·부모 환경은 유지했다. 현재 권한은 schema USAGE+업무 SELECT뿐이며 API 인가·쓰기 권한은 별도 결정이다.

## ADR-022 — 첫 워크스페이스 조회 API와 개발 행위자·인가 경계
- 날짜: 2026-11-03
- 상태: 사용자 선택 반영·설계 확정. 구현/HTTP 검증 전.
- 맥락: 실제 로컬 JPA 조회까지 확인한 뒤 첫 조회 API의 계약과 행위자 식별·인가 경계를 확정하도록 요청받았다. 로그인은 Phase 2이고 DB SELECT 권한은 사용자별 접근 제어를 제공하지 않는다.
- 결정: GET /api/workspaces와 GET /api/workspaces/{workspaceId}를 첫 범위로 한다. OWNER/ADMIN/MEMBER/VIEWER 모두 자기 소속 공간의 기본 정보만 조회한다. 사용자가 선택한 로컬 개발 모드는 명시적인 dev-actor 프로필과 서버 지정 seed 사용자 설정을 요구하고, 기본 실행에서는 업무 API를 활성화하지 않는다. 클라이언트 사용자 ID/역할은 행위자 근거로 쓰지 않는다. Service 트랜잭션에서 행위자를 해석하고 목록 사용자 조건/상세 membership을 검사하며, 미소속과 미존재는 같은 404로 응답한다.
- 대안: 목록만 먼저 제공, 로그인/실제 인증을 Phase 1로 앞당김, 클라이언트 사용자 ID 헤더로 행위자 선택, 미소속 403/미존재 404 구분, Entity/Slice 직접 응답.
- 근거: 목록과 직접 ID 상세 요청 모두에서 공간 격리를 검증한다. 서버 지정 행위자는 클라이언트 자칭 사용자 전환 경로를 줄이되 실제 인증으로 취급하지 않는다. 기본 비활성·loopback 접속 제한·필수 설정/사용자 검증으로 개발 모드 실행 범위를 제한한다. 동일 404는 공간 존재 정보의 구분을 줄인다. 전용 DTO는 영속 객체 내부 정보와 페이지 구현의 노출을 피한다.
- 결과/재검토 조건: 상세 계약은 [workspace-read-api-v1.md](workspace-read-api-v1.md)에 기록했다. ID는 JSON 십진 문자열, 목록은 고정 ID 순서 Slice와 page/size 제한, 오류는 ProblemDetail+code로 정한다. 개발 모드는 모든 로컬 요청이 지정 행위자를 공유하므로 다중 사용자 인증이 아니며 외부 공개 용도로 쓰지 않는다. API/Service/개발 경계·격리 테스트 구현은 다음 단계다. 실제 인증 도입 시 행위자 공급을 바꾸되 Service 소속 검사는 유지한다. DB/V1/seed/권한·기존 앱 코드는 이번 설계 확정에서 바꾸지 않았다.

## ADR-023 — 개발 모드 워크스페이스 조회 구현과 격리 HTTP 검증
- 날짜: 2026-11-03
- 상태: 구현·격리 검증 및 전체 빌드/JUnit 138개 통과.
- 맥락: 사용자가 ADR-022에 따른 조회 API·개발 행위자 경계 구현과 격리 PostgreSQL 통합 검증을 요청했다.
- 결정: dev-actor 프로필에서만 서버 지정 행위자/조회 Service/Controller/업무 오류 처리/접속 필터를 등록한다. 시작 시 기존 사용자와 loopback bind·전달 헤더 비활성 구성을 확인한다. 요청마다 서버 설정 사용자를 조회하고 같은 readOnly 트랜잭션 안에서 membership 검사와 DTO 변환을 수행한다. 기존 쿼리 5개와 READ 권한·V1/seed를 유지한다.
- 대안: Spring Security/로그인 도입을 앞당김, 클라이언트 ID 헤더로 사용자 전환, Controller에서만 소속 검사, Entity 직접 반환과 OSIV 유지, 모든 JPA 예외를 일률적으로 503 처리.
- 근거: 승인된 개발 식별/인가 경계를 작은 MVC/JPA 구현으로 검증한다. Service 업무 예외는 HTTP에 의존하지 않고 API 계층이 안전한 ProblemDetail로 변환한다. JDBC 연결의 트랜잭션 시작/종료 실패는 Hibernate/JPA 예외로 감싸질 수 있어 연결 리소스 예외 외의 JDBC 트랜잭션 실패도 503으로 매핑한다. SQL 권한 오류 같은 다른 예외는 안전한 500으로 유지한다.
- 결과/재검토 조건: 신규 격리 검증 42개 및 기존 포함 전체 build/JUnit 138개 통과. 사용자별 목록/상세·네 역할·동일 404·입력/헤더 위조·기본 비활성/설정 실패·Service 직접 소속 검사·OSIV=false DTO·SQL 2개 목록·장애/복구·안전한 500을 확인했다. 변경 테스트 데이터/이력 스냅샷은 원복됐고 로컬 .env/DB는 신규 검증에 사용하지 않는다. 실행 JAR에 privileged runner/정책/Flyway/자격증명이 없는 것도 확인했다. 실제 로컬 HTTP 기동, Vue 업무 화면, 실제 인증·쓰기는 후속이다. 상세는 [조회 계약](workspace-read-api-v1.md)을 따른다.

## ADR-024 — 실제 로컬 개발 모드 HTTP 조회·격리 확인
- 날짜: 2026-11-03
- 상태: 실행·검증 완료. 임시 서버 종료.
- 맥락: 사용자가 격리 검증된 조회 API를 실제 로컬 개발 모드로 기동하고 HTTP 목록/상세를 확인하도록 요청했다.
- 결정: 기존 로컬 DB/V1/seed/READ 권한을 유지하고 최신 JAR를 127.0.0.1:18081에 실행한다. 서버 지정 행위자를 Alice/Eve로 바꿔 각각 별도 프로세스로 검증한 뒤, 직접 생성한 프로세스만 종료한다. build 아래 임시 PowerShell이 자식 환경을 정리하고 앱 전용 설정만 읽게 하며 SQL 조회/파일 해시로 전후 상태를 비교한다.
- 대안: 8080의 기존 IDE 서버가 최신 구성이라고 가정, 행위자를 클라이언트 헤더로 바꿈, 검증을 위해 데이터/권한을 초기화, 서버를 계속 백그라운드 실행.
- 근거: 실제 빌드/설정의 전체 HTTP→Service→JPA→로컬 PostgreSQL 경로와 서로 다른 행위자의 소속 경계를 확인한다. 기존 서버와 충돌하지 않고, 상태 변경 없이 새 프로세스만 제한된 시간 실행한다.
- 결과/재검토 조건: bootJar 성공/현재 소스 반영 확인. Alice는 A(id 문자열 1), Eve는 B(id 문자열 2)만 목록/상세 200/OWNER다. 상대 공간/미존재 동일 404, 입력 400, 위조 헤더에도 행위자 유지, 빈 페이지 200, health 두 경로 200/UP. 업무 데이터 5/2/5·이력·ACL/소유자·역할 속성·시퀀스, 자격증명 3개/V1 파일 해시·부모 환경이 유지됐다. 검증 서버는 종료했다. IDE 8080 적용과 Vue 업무 화면은 이번 결과에 포함하지 않는다. 실제 인증은 Phase 2이며 후속 한 작업은 Vue에서 목록·상세 조회 연결이다.

## ADR-025 — Vue의 워크스페이스 목록·선택 상세 연결
- 날짜: 2026-11-03
- 상태: 구현·프론트 검증·실제 Chrome 연결 확인 완료.
- 맥락: 사용자가 로컬 HTTP 조회 확인 이후 Vue에서 목록·상세를 연결하도록 요청했다. 읽기 API/개발 행위자·인가 정책은 기존 계약을 유지한다.
- 결정: 기존 fetch/Vite 프록시와 Composition API의 지역 상태로 한 화면에 목록/선택 상세를 구성한다. 최초 목록만 읽고 선택할 때 상세를 조회한다. 크기 20의 이전/다음 페이지와 현재 페이지 새로고침, 로딩/빈 목록/오류·재시도를 제공한다. ID는 문자열로 유지하고 응답을 런타임 검사한다. 선택/페이지/새로고침/언마운트 시 이전 요청을 취소하고 취소된 응답은 적용하지 않는다. health는 접이식 보조 영역으로 유지한다.
- 대안: Router로 목록/상세 URL 분리, Pinia/쿼리 캐시 도입, Axios/스키마 라이브러리 추가, 자동으로 첫 상세 선택, 이전 응답을 남긴 채 새 선택 로딩, 요청 순번만으로 경합 처리.
- 근거: 현재 한 화면에는 공유 URL·화면 간 상태가 필요하지 않아 새 의존성을 늘리지 않는다. 데이터와 UI 상태를 분리하고 이전 상세를 지워 실패/선택 변경 시 잘못된 정보를 표시하지 않는다. 요청 취소와 응답 적용 전 확인으로 네트워크 작업과 늦은 응답을 함께 제어한다. 클라이언트가 행위자/역할을 선택하지 않으며 최종 인가는 계속 서버 Service가 담당한다.
- 결과/재검토 조건: npm test 31개(기존 health 5+API 17+화면 9), 타입 검사/빌드 통과. 실제 Chrome에서 Vite→로컬 Spring/PostgreSQL의 Alice/A·Eve/B 목록·클릭 상세 200, 브라우저 요청 차단 오류/재시도, 390px 폭 가로 넘침 없음을 확인했다. 데이터/이력/relation ACL/시퀀스·자격증명/V1 파일을 보존하고 검증 서버/브라우저는 종료했다. 백엔드/DB·의존성 파일은 바꾸지 않았다. URL 공유/뒤로가기/다중 화면·공유 상태 요구가 생길 때 Router/상태 도구를 검토한다.

## ADR-026 — 워크스페이스 생성 자격·단일 트랜잭션과 개발 쓰기 경계
- 날짜: 2026-11-03
- 상태: 사용자 선택 반영·설계 확정. 구현/격리 검증/실제 로컬 적용 전.
- 맥락: 조회 API와 Vue 화면 연결 이후 사용자가 생성 API의 권한·트랜잭션 범위 확정을 요청했다. 현재 앱 DB 계정은 SELECT만 허용되고 실제 인증은 Phase 2다.
- 결정: 사용자 선택으로 모든 기존 사용자가 기존 공간 역할과 무관하게 생성 가능하고 생성자 자신이 OWNER가 된다. 이름은 앞뒤 공백 제거 후 1~100 코드 포인트, 동일 사용자/이름도 허용한다. POST /api/workspaces는 별도 개발 쓰기 설정과 명시적 허용 로컬 Origin/JSON 검사로 제한한다. 별도 쓰기 Service의 한 트랜잭션으로 공간+OWNER membership을 persist하고 커밋 성공 후 201/상대 Location/조회와 같은 DTO를 반환한다. 기존 조회 Repository/readOnly는 유지하며 두 테이블 INSERT만 별도 도구로 추가하는 권한 상태를 설계한다.
- 대안: 기존 OWNER/ADMIN만 생성, 사용자별 이름 유일성/새 제약, 멱등키 저장소, 저장별 REQUIRES_NEW, 전체 JpaRepository CRUD/광범위 DML·sequence GRANT, 기본 개발 모드에 쓰기 자동 활성화, 실제 인증을 먼저 도입.
- 근거: 첫 공간이 없는 사용자도 시작하고 공간별 역할을 전역 권한으로 혼동하지 않는다. 생성 원자성으로 OWNER 없는 공간을 Service 경로에서 커밋하지 않는다. 개발 행위자는 로컬 요청이 공유하므로 조회 때보다 쓰기 활성화/출처 경계를 강화한다. 자동 POST 재시도를 제외해 중복 생성 위험을 숨기지 않으며, 커밋 응답 유실은 단순 DB 롤백으로 해결된다고 주장하지 않는다.
- 결과/재검토 조건: [workspace-create-api-v1.md](workspace-create-api-v1.md)에 HTTP/오류/출처·설정/원자성·권한 상태/기존 도구·격리 수용 기준을 기록했다. 생성 구현 시 migration/seed/읽기 도구도 정확한 WORKSPACE_CREATE 권한을 인정하되 부분/과도한 권한을 계속 거부해야 한다. seed 전체 일치 정책은 유지하므로 업무 데이터 추가 뒤 seed Verify/Apply 중단은 정상이다. 실제 로컬 GRANT/생성은 구현·격리 검증 뒤 별도 단계다. 이번에 앱/DB/권한/의존성은 바꾸지 않았다.

## ADR-027 — 생성 API와 최소 INSERT 전환·격리 검증 구현
- 날짜: 2026-11-03
- 상태: 구현·격리 검증 완료. 실제 로컬 쓰기 권한/생성 미적용.
- 맥락: 사용자가 ADR-026의 생성 API·최소 쓰기 권한 도구 구현과 격리 PostgreSQL 검증을 요청했다.
- 결정: 별도 조건부 CommandService/생성 Controller·필수 Origin/JSON/4 KiB 경계·도메인 팩토리·MANDATORY 영속 어댑터를 구현했다. 한 트랜잭션의 두 persist/flush 후 커밋 성공만 201로 응답한다. 기존 조회 계약/의존성/V1을 유지한다. 공통 정책은 정확한 WORKSPACE_CREATE도 허용하며 새 전용 관리자 엔트리/SQL/PowerShell이 READ_ONLY에서만 전환하고 재실행/읽기 GRANT는 승인된 권한을 보존한다.
- 대안: JpaRepository 전체 CRUD 노출, 범용 setter/요청 Entity 바인딩, 광범위 DML·sequence 권한, ACL 검사 완화, 앱 부팅에서 GRANT, 모든 JDBC 트랜잭션 예외를 503으로 처리.
- 근거: 클라이언트의 주체/역할 지정과 불필요한 DB 권한을 제한한다. 생성자 OWNER를 단일 트랜잭션으로 보장하고 임의 제약/커밋 실패를 허위 성공이나 일시 장애로 숨기지 않는다. 정상·부분·과도한 권한과 migration/seed 호환을 실제 실행기로 확인한다.
- 결과/재검토 조건: 신규 API 44개·권한 17개, 기존 포함 Java 199개, 별도 실제 Vite 프록시 1개와 실제 PowerShell/Gradle 경로 52개가 통과했다. Unicode/본문/Origin/동시 이름·VIEWER/미소속 사용자·저장/커밋 롤백·생성 키·최소 권한·잘못된 ACL·도구 호환을 확인했다. 구현 중 JDBC 트랜잭션의 SQLState를 분류해 무결성 실패는 500, 연결/명시적 일시 장애는 503으로 구분했다. 실제 로컬 쓰기는 미적용이며 Vue 생성 폼/인증은 후속이다. 자세한 명령/범위는 workspace-create-api-v1.md와 local-setup.md를 따른다.

## ADR-028 — IDE 리소스/Gradle JDK와 앱 Flyway 자동 실행 경계 수정
- 날짜: 2026-11-03
- 상태: 설정 수정 및 최신 IDE 클래스패스의 기동/읽기 확인 완료.
- 맥락: 생성 구현 중 사용자가 IDE에서 DataSource URL 누락, 이후 SQLState 42501/DB 권한 거부를 보고했다. 이전 IDE 모델은 main 리소스를 migration 출력으로 취급했고 Gradle 가져오기가 캐시의 Java 25로 실행돼 major version 69 오류로 실패했다. 재가져오기 후에는 main 리소스가 포함됐지만 migration 출력/Flyway도 IDE 실행 클래스패스에 포함됐다.
- 결정: sourceSets는 별도 리소스 루트를 유지하고 processMigrationResources에서 V1 SQL만 복사한다. java.import.gradle.java.home은 설치된 JDK 21 경로를 명시한다. 사용자와 IDE 캐시 재가져오기를 진행하고 앱 설정에 spring.flyway.enabled=false를 추가해 IDE 클래스패스에 Flyway가 있어도 앱 부팅의 DDL을 차단한다.
- 대안: 앱에 DB CREATE/관리자 권한 부여, main 리소스를 IDE 출력에 수동 복사, 설정 없는 기동을 유지하고 JAR만 정상이라고 보고.
- 근거: 리소스가 안 읽히는 문제와 부팅 시 잘못된 초기화가 실행되는 문제를 각각 해결한다. Gradle 빌드와 IDE 실행 모델/클래스패스는 별도이며 전용 migration 경계를 권한 확대 없이 유지한다.
- 결과/재검토 조건: 최신 IDE 인수 파일에는 build/resources/main이 존재한다. 그 파일과 FlowDeskApplication으로 별도 loopback 포트에서 실제 Alice/Eve 읽기 모드를 실행해 목록/상세·health 200과 차단 응답을 확인했다. 권한/데이터/이력/시퀀스·자격증명/V1·부모 환경이 유지됐고 검증 앱은 종료했다. 실제 IDE UI에서 8080 Run 버튼을 다시 눌렀다고 가정한 결과는 아니다. JDK 설치 경로/버전을 바꾸면 이 명시적 IDE 경로도 갱신한다.

## ADR-029 — 로컬 최소 INSERT 적용과 첫 생성 HTTP 확인
- 날짜: 2026-11-03
- 상태: 실제 로컬 적용·검증 완료. 생성 데이터 유지, 검증 서버 종료.
- 맥락: 사용자가 IDE 정상 기동을 확인한 뒤 제안된 다음 단계인 로컬 최소 쓰기 권한 적용과 첫 생성 HTTP 확인을 진행하도록 승인했다.
- 결정: 기존 로컬 flowdesk에서 전용 프로비저닝을 최초/반복 실행해 WORKSPACE_CREATE로 전환한다. 기존 IDE 8080을 유지하고 18081의 별도 앱에만 Alice/쓰기 활성화/허용 Origin을 주입한다. 검증 이름의 공간 한 개를 POST로 생성해 보존하고 Alice 조회·Eve 차단·기존 상태 유지와 최소 권한을 확인한다. POST 결과가 불명확하면 자동 재전송하지 않는다.
- 대안: 실제 적용 없이 격리 테스트만 반복, 앱에 관리자 권한 전달, 생성 뒤 직접 SQL로 삭제/seed 리셋, 기존 IDE 프로세스를 임의 종료하거나 쓰기 설정 자동 변경.
- 근거: 격리 검증된 정책의 실제 연결/권한/Service 트랜잭션을 확인하면서 원래 데이터와 현재 IDE 실행을 보존한다. DB INSERT 권한과 API 쓰기 활성화는 각각 관리한다. 실제 업무 데이터 추가 뒤 seed 불일치를 실패 복구 대상으로 오해하지 않는다.
- 결과/재검토 조건: POST 201/Location=/api/workspaces/3/OWNER, 이름 FlowDesk Local Create Check f2937116bdf0와 Alice의 OWNER 한 건을 확인했다. 데이터 5/2/5→5/3/6. Alice 목록/상세 200·Eve 상세 404/목록 미노출, 위조 사용자 헤더 무시·입력 400·외부 Origin 403·health UP이다. 기존 데이터/이력·자격증명/V1 해시·부모 환경을 유지했다. migration Validate 성공, seed Verify는 추가 업무 데이터로 예상 mismatch이며 수정/삭제하지 않았다. 기존 PID 28676/8080은 유지되고 health 두 경로 200/UP, 검증 서버는 종료했다. Vue 생성 폼은 다음 단계다.

## ADR-030 — Vue 생성 폼·확정 성공과 결과 불명의 분리
- 날짜: 2026-11-04
- 상태: 구현·프론트 검증·실제 Chrome/격리 PostgreSQL 검증 완료.
- 맥락: 사용자가 로컬 첫 생성 확인 뒤 Vue 생성 폼과 POST 연결을 요청했다. 이름 중복 허용·서버 지정 행위자·별도 쓰기/Origin·자동 POST 재시도 제외 계약을 유지한다.
- 결정: WorkspaceCreateForm의 이름/진행/거부/성공/결과 불명 상태와 WorkspaceBrowser의 조회 상태를 분리한다. 클라이언트가 201/Location/이름/문자열 ID/OWNER를 확인하면 성공 이벤트를 보내고 부모가 목록 0페이지와 새 ID 상세를 GET한다. 새 공간을 페이지 데이터에 임의 삽입하지 않는다. POST는 이름만 보내며 쿠키/역할/사용자/Origin을 직접 지정하지 않는다. Unicode 코드 포인트 검증·동기 중복 제출 가드·10초 제한·redirect=error를 사용한다.
- 대안: 2xx면 무조건 성공, 이름 중복 금지로 재시도 문제 우회, POST를 GET 재시도와 통합, 응답 유실을 미생성으로 단정, 페이지 갱신 실패 시 POST 재전송, 새 항목을 임의로 0페이지에 추가, Router/Pinia/새 폼 라이브러리 도입.
- 근거: 응답이 유실돼도 서버 저장은 끝날 수 있다. 400/403/404/405/413/415 거부와 네트워크/시간 초과·5xx/잘못된 201의 결과 불명을 구분하고 후자는 재제출을 잠근다. 목록 확인은 GET만 하며 명시적 입력 초기화는 새 요청 준비일 뿐 자동 재전송이 아니다. 성공한 생성과 후속 조회 실패도 각각 표시한다. 서버 인가·DB 원자성은 기존 경계를 유지한다.
- 결과/재검토 조건: 프론트 58개·타입 검사/빌드 통과. 실제 Chrome+Vite+격리 DB에서 정상 생성/선택·중복 제출 차단·커밋한 201만 유실시킨 뒤 잠금/GET 확인/미재전송·405와 모바일 배치를 확인했다. 격리 DB의 두 생성/OWNER는 5/4/7로 확인 후 컨테이너/볼륨을 정리했고 실제 로컬 5/3/6·자격증명/V1은 유지됐다. 백엔드/의존성/스키마 변경은 없다. 서버 멱등키/실제 인증·수정/삭제·프로젝트/작업은 후속이다.

## ADR-031 — 이름 수정 권한과 버전 충돌 검출
- 날짜: 2026-11-04
- 상태: 사용자 정책 확정·설계 완료. 구현/격리 검증/로컬 적용 전.
- 맥락: Vue 생성 연결 이후 이름 수정 범위를 정했다. 현재 앱에는 UPDATE 권한과 Workspace version이 없다.
- 결정: 사용자 선택으로 해당 공간 OWNER·ADMIN만 허용, MEMBER·VIEWER 403, 미소속/미존재 동일 404, 버전 충돌 409를 사용한다. PATCH/name·version 문자열, 조회/생성 응답 version, V2 bigint와 JPA @Version, Service 단일 트랜잭션, 최소 UPDATE(name, updated_at, version)를 설계한다. 기본값/수용 기준은 [수정 계약](workspace-rename-api-v1.md)에 기록했다.
- 대안: OWNER만 허용, 마지막 수정 덮어쓰기, 비관적 잠금만 사용, ETag/If-Match, 전체 UPDATE, V1 수정.
- 이유: 관리자 수정 권한과 일반 멤버 쓰기 제한을 구분한다. 클라이언트 버전 비교와 DB UPDATE 검사를 함께 사용해 변경 유실을 막는다. 컬럼 권한과 별도 V2로 기존 경계를 유지한다.
- 결과: 계약·요구사항·아키텍처·로드맵·학습 기록을 갱신하고 문서 검증만 수행한다. 코드/DDL/GRANT/데이터는 변경하지 않았다. 다음은 수정 API·V2·최소 UPDATE 도구 구현과 격리 PostgreSQL 검증이다. 실제 로컬 적용과 Vue 수정 폼은 이후 별도 단계다.

## ADR-032 — 이름 수정 구현·V2와 컬럼 UPDATE 전환
- 날짜: 2026-11-04
- 상태: 구현·격리 검증 완료. 실제 로컬 V2/UPDATE 적용 전.
- 맥락: 사용자가 ADR-031의 수정 API·V2·최소 UPDATE 도구 구현과 격리 PostgreSQL 검증을 요청했다. 기존 V1/로컬 데이터는 보존한다.
- 결정: PATCH/name·version 문자열, 소속/OWNER·ADMIN/요청 버전 검사, JPA @Version 및 no-op OPTIMISTIC 검증, flush 뒤 DTO·commit 뒤 응답을 구현했다. V2에 bigint version을 추가하고 별도 관리자 도구에서 workspaces 세 컬럼 UPDATE만 부여한다. 공통 ACL/이력 검사와 seed/read/create 실행기는 정확한 새 상태를 보존한다. 상세 구현과 명령은 [수정 계약](workspace-rename-api-v1.md)에 기록한다.
- 대안: 무조건 UPDATE/자동 재시도, 테이블 전체 UPDATE, Entity 직접 입력/버전 setter, V1 수정, 앱 기동에서 자동 migrate/GRANT, no-op이면 DB 검증 생략.
- 이유: 오래된 화면과 UPDATE 사이 경합을 모두 검출하고 인가/버전/응답 경계를 분리한다. 승인된 최소 권한만 별도 작업으로 확대하며 기존 readOnly 조회와 앱/DDL 계정 분리를 유지한다.
- 결과: API/V2/권한 실행기와 HTTP·DB·도구 회귀를 추가했다. 문법/전송 단계에서 바뀌던 두 음성 테스트 입력(identity DEFAULT·surrogate JSON escape)을 수정했다. Java 245개/별도 실제 Vite POST·PATCH 1개, PowerShell 전체 경로 60개, 프론트 58개와 타입 검사/빌드가 통과했다. 테스트 전송의 HTTP/1.1 설정을 맞춰 프록시 timeout도 해결했다. 로컬 데이터 5/3/6·이력/ACL과 자격증명 3개/V1 해시를 보존했고 격리 자원을 정리했다. 상세 명령/최초 실패와 최종 결과는 local-setup.md에 기록했다. 실제 로컬 V2/UPDATE/수정과 Vue 수정 화면은 별도 단계다.

## ADR-033 — 로컬 V2·최소 UPDATE와 실제 이름 수정 확인
- 날짜: 2026-11-04
- 상태: 실제 로컬 적용·HTTP/DB 검증 완료.
- 맥락: 사용자가 격리 검증 후 로컬 V2·최소 UPDATE 적용과 새 앱 기동·수정 확인을 요청했다. 시작 상태는 앱 프로세스/연결 없음, V1/5·3·6/WORKSPACE_CREATE였다.
- 결정: 전용 migrator로 V2 1건을 적용·Validate하고 전용 관리자 도구로 WORKSPACE_RENAME을 최초/반복 적용했다. 기존 비-seed 공간 ID 3을 Alice OWNER로 한 번 수정하고 영수증으로 재전송을 방지했다. 임시 18081 JAR 앱의 actor/쓰기/Origin은 자식 프로세스에만 설정하고 검증 후 종료했다.
- 대안: seed 공간 변경/복원, 새 검증 공간 추가, SQL 직접 UPDATE, IDE/전역 쓰기 설정 변경, 응답 불명 시 PATCH 자동 재전송.
- 이유: 실제 API·트랜잭션·DB 권한을 확인하면서 seed/행 수/다른 멤버십과 자격증명을 유지한다. 버전 충돌을 자동 재시도로 덮어쓰지 않는다. 제품 코드와 기존 검증 JAR은 변경하지 않는다.
- 결과: ID 3은 FlowDesk Local Rename Check 77d6875a385e/version 1이다. OWNER 수정 200, GET 200, stale 409, no-op 버전/시각 유지, ADMIN no-op 200, VIEWER 403, 미소속·미존재 404, 입력 400/Origin 403, 쓰기 off 405와 Actuator UP을 확인했다. V2 migrator 이력, 세 컬럼 UPDATE만 허용, 기존 데이터 5/3/6·seed/멤버십/시퀀스/V1 이력/소유권/relation ACL과 자격증명 3개/V1/V2/JAR 해시 보존을 확인했다. 실제 명령·영수증·표현 차이 비교는 local-setup.md에 기록한다. 다음은 Vue 이름 수정 폼·버전 충돌 안내 연결이다.

## ADR-034 — Vue 이름 수정의 편집 버전·409 명시적 복구
- 날짜: 2026-11-05
- 상태: 구현·프론트 검증·실제 Chrome/격리 DB 확인 완료.
- 맥락: 사용자가 로컬 수정 API 확인 이후 Vue 이름 수정 폼·버전 보존·409 안내 연결을 요청했다. 기존 Vue 응답 파서는 version을 버리고 있었다.
- 결정: 기존 Composition API/fetch를 유지하고 Workspace.version을 정규 십진 문자열로 검증·보존한다. OWNER/ADMIN의 상세에만 수정 폼을 표시하고 편집 시작 스냅샷과 draft를 분리한다. name/version만 PATCH하고 중복 제출/자동 재시도를 막는다. 정상 200의 ID/이름/역할/증가 또는 no-op version을 확인해 부모 상세와 같은 ID의 목록 행을 갱신한다. 409와 결과 불명은 입력 유지/저장 잠금 → 최신 GET → 명시적 편집 기준 갱신 → 수동 저장으로 처리한다. 최신 역할이 수정 불가이거나 GET 실패면 잠금을 유지한다.
- 대안: version을 number로 변환, props/watch로 편집 버전 자동 갱신, 409 뒤 최신 버전으로 자동 재전송, GET 이름 일치만으로 성공 처리, PATCH 200 뒤 전체 목록/상세를 지우고 재조회, Router/Pinia/폼 라이브러리 도입.
- 이유: JavaScript 큰 정수 정밀도와 stale draft의 덮어쓰기를 방지한다. 이미 커밋한 응답이 유실될 수 있어 결과 불명을 확정 실패로 취급하지 않는다. 서버의 ID 정렬/페이지 경계를 유지하면서 확정된 응답만 반영한다. UI 역할 안내는 서버 인가를 대신하지 않는다.
- 결과: 기존 58개+신규 API/버전 32개+수정 폼/연결 11개=101개와 타입 검사/빌드 통과. 실제 Chrome/Vite/고유 DB에서 두 번 제출 시 한 PATCH, 외부 선행 저장 후 409/입력 유지/GET·명시적 기준 갱신·수동 성공, 커밋된 200 유실 후 잠금/GET 확인/미재전송, VIEWER 폼 미노출, 쓰기 off 405·모바일 배치를 확인했다. 격리 DB만 A version 4로 바뀌었고 실제 로컬 5/3/6·ID 3 version 1 및 데이터/이력/전체 ACL/시퀀스·자격증명/V1/V2/JAR는 유지했다. 백엔드/의존성/스키마는 변경하지 않았다. 다음은 Project 모델·생성/조회 권한 범위 확정이다.

## ADR-035 — 첫 Project 모델과 Workspace 역할 상속
- 날짜: 2026-11-05
- 상태: 사용자 정책 확정·설계 완료. 코드/V3/권한 도구 구현·격리 검증/실제 로컬 적용 전.
- 맥락: Workspace Vue 이름 수정까지 확인한 뒤 사용자가 Project 데이터 모델·생성/조회 권한 범위 확정을 요청했다.
- 결정: 사용자 선택으로 ProjectMembership 없이 해당 Workspace의 네 역할 모두 조회, OWNER/ADMIN만 생성, 같은 공간 내 이름 중복 허용을 채택했다. ID·workspace_id FK·이름·선택 일반 텍스트 설명·생성/수정 시각을 첫 모델로 정한다. V3와 `(workspace_id,id)` 인덱스, 부모 하위 POST/GET/상세, 소속/역할·부모 일치 검사, 기존 개발 쓰기 경계와 최소 projects SELECT/INSERT/정확한 PROJECT_CREATE 상태를 설계한다. 구현 기본값·응답/오류·도구/seed 전환·수용 기준은 [Project 계약](project-model-and-api-v1.md)에 기록했다.
- 대안: ProjectMembership/비공개 프로젝트, MEMBER 생성 허용, 공간별 이름 UNIQUE, 전역 Project 경로/JSON 부모 지정, Workspace Entity 연관/cascade, creator/상태/version 선추가, 광범위 DML/앱 자동 GRANT.
- 이유: 별도 초대·역할 체계를 늘리지 않고 현재 공간 경계를 재사용한다. 전역 역할로 혼동하거나 ID만 바꿔 다른 부모의 프로젝트에 접근하지 못하게 한다. 현재 생성/조회에 필요한 데이터/SQL 권한만 추가하고 수정/보관의 충돌 정책은 후속으로 둔다.
- 결과: Project 계약과 요구사항·아키텍처·로드맵·기술/학습 기록을 갱신했다. 문서 링크/HTTP JSON/결정 단일성만 검증하며 기능 테스트·빌드·DB 작업은 하지 않는다. 다음은 V3·Project 매핑·생성/조회 API·최소 권한 도구 구현과 격리 PostgreSQL 검증이다.

## 향후 기록 양식
### ADR-NNN — 제목
- 날짜:
- 상태: 제안/채택/폐기
- 맥락:
- 결정:
- 대안:
- 근거:
- 결과/재검토 조건:


## ADR-036 — Project 생성/조회 구현과 권한 상태 전환 (2026-11-05)

- 맥락: ADR-035의 확정 계약을 기존 Workspace API/권한 실행기와 함께 구현해야 한다.
- 선택: V3의 스칼라 FK Entity와 조회 Repository 두 메서드, 별도 mandatory persist, Service의 workspace membership/역할 검사를 사용한다. strict JSON 읽기를 공통 코드로 추출하고 Workspace 4 KiB/Project 32 KiB를 개별 지정한다. 기존 개발 쓰기 필터의 nested POST를 보호한다.
- 대안: JPA 부모 cascade/범용 CRUD Repository, 새 보안 프레임워크, 모든 테이블에 일괄 DML 부여, 앱 부팅 자동 GRANT.
- 이유: 확정된 생성·조회 범위에 필요한 책임과 권한만 추가하고 기존 자원·실행 경계를 유지한다.
- 결과: PROJECT_CREATE는 WORKSPACE_RENAME + projects SELECT/INSERT다. 기존 도구도 완전한 새 상태를 보존하며 부분/과도한 상태는 자동 수정하지 않는다. MigrationHistory는 1/2/3, seed는 기존 예제와 projects 비어 있음을 요구한다. 검증은 격리 PostgreSQL에서 수행하며 실제 로컬 V3/권한 적용은 별도 요청이다. 결과/명령은 local-setup.md에 기록한다.


## ADR-037 — 실제 로컬 Project 적용과 검증용 생성 범위 (2026-11-05)

- 맥락: 사용자가 격리 검증이 끝난 V3/최소 Project 권한을 실제 로컬에 적용하고 새 앱의 생성/조회를 확인하도록 요청했다.
- 선택: 앱/DB 연결이 없는 상태를 확인하고 전용 migrator V3 → 별도 Project 권한 도구 → 새 JAR 순서를 사용했다. 기존 행을 유지하고 OWNER Alice의 Workspace 1에 Project 한 건만 실제 HTTP로 생성했다. 실행 기록에 POST 시도 상태와 응답 ID를 남기고 불명확한 요청은 자동 재전송하지 않는다.
- 대안: 앱 자동 DDL/GRANT, seed 초기화, 각 역할 검증마다 성공 Project 추가, 응답 유실 시 POST 재시도.
- 이유: 스키마/권한/HTTP 인가를 구분하고 기존 업무 데이터와 최소 권한을 보존한다. 실제 생성 검증의 효과는 한 행으로 제한하며 역할별 거부 요청은 행을 만들지 않아야 한다.
- 결과: V3 1건/Validate·반복 0건, PROJECT_CREATE 최초/반복 성공, ID 1 생성·조회/역할별 차단·쓰기 off/health를 확인했다. 기존 5/3/6·V1/V2·ACL/시퀀스·자격증명/SQL/JAR를 보존했다. 설정 파일의 쓰기 기본값은 유지했고 소유한 임시 앱 6개는 모두 종료했다. Vue Project 조회는 후속이다.


## ADR-038 — 선택한 공간의 Project 조회 화면과 요청 생명주기 (2026-11-05)

- 맥락: 실제 로컬 Project 생성/조회 확인 뒤 Vue에서 목록·상세를 연결하도록 요청받았다.
- 선택: 유효한 Workspace 상세 아래에 별도 ProjectBrowser를 공간 ID key로 등록한다. 프로젝트 목록/상세는 독립 지역 상태/AbortController로 관리하고, 공간 전환·페이지 이동·언마운트 시 취소와 늦은 응답 가드를 함께 사용한다. projects.ts는 문자열 ID/부모·페이지/응답 필드를 런타임 검사하고 일반 텍스트 설명만 표시한다.
- 대안: WorkspaceBrowser에 모든 Project 상태 추가, 부모 변경을 전역 저장소로 동기화, Router/Pinia/Axios 선도입, 취소 없이 최종 응답만 적용, HTML/Markdown 설명 렌더링.
- 이유: 현재 한 화면의 계층 구조와 기존 스택을 유지하면서 공간 간 상태 혼입을 차단한다. TypeScript 타입만으로 HTTP 응답을 신뢰하지 않고 서버의 소속 인가를 계속 최종 기준으로 둔다.
- 결과: 생성/수정 API·DB·보안 설정·의존성을 바꾸지 않았다. 새 API/컴포넌트·기존 부모 연결/CSS와 테스트·문서를 변경했으며 명령/검증 결과는 local-setup.md에 구분해 기록한다. Project 생성 폼/POST는 후속이다.


## ADR-039 — Project 생성 폼과 결과 불명/후속 조회 경계 (2026-11-05)

- 맥락: Vue Project 조회 연결 뒤 기존 POST API를 생성 폼에 연결하도록 요청받았다.
- 선택: 부모의 OWNER/ADMIN 힌트로 별도 폼을 표시하고 이름/선택 설명의 Java 공백·Unicode·개행을 정규화한다. POST의 201/상대 Location/DTO를 검증하고, 확정 거부는 입력을 유지하며 그 밖의 불명확한 응답/네트워크 실패는 잠금·GET 확인·명시적 초기화로 처리한다. 성공과 후속 목록/상세 GET을 분리하고 탐색 순번으로 늦은 자동 선택을 차단한다.
- 대안: 폼/목록 상태를 모두 부모에 결합, 중복 이름 차단, 5xx 자동 POST 재시도, GET 이름 일치로 자동 성공/잠금 해제, 프론트 역할 검사만으로 권한 확정.
- 이유: 이름 중복 허용/멱등키 없음·커밋 뒤 응답 유실을 고려한다. UI 역할은 서버 인가를 대체하지 않고, 완료한 생성과 이후 조회 장애를 혼동하지 않는다. 기존 fetch/지역 상태를 유지한다.
- 결과: 백엔드/권한/SQL/새 의존성 없이 프론트 폼/API/부모 연결/CSS/테스트를 변경했다. 프론트 194개·빌드와 실제 격리 DB 브라우저에서 한 번의 전송/실제 저장, 커밋 후 응답 유실/미재전송, VIEWER 폼 미노출/조회, 쓰기 off 405를 확인했다. 실제 로컬 5/3/6/1을 보존했고 임시 앱/DB는 정리했다. 다음은 Project 수정 정책 확정이다.


## ADR-040 — Project 이름·설명 수정 권한과 행 단위 충돌 (2026-11-05)

- 상태: 사용자 선택 확정·설계 완료. 코드/V4/권한 도구/수정 폼 구현·검증·실제 로컬 적용 전.
- 맥락: Vue Project 생성 폼까지 완료한 뒤 사용자가 수정 API의 권한·충돌 정책 확정을 요청했다.
- 선택: 이름·설명 수정(설명 null/빈 값 삭제), 해당 Workspace OWNER/ADMIN 허용, Project 자체 버전 기반 409를 채택했다. 다른 필드도 같은 행의 동시 변경이면 충돌이다. 기존 정규화/길이·중복 이름 허용/부모 경계를 유지한다.
- 구현 기본값: name/description/version 세 필드 필수 PATCH, 버전/시간을 유지하는 검증된 no-op, 자원 조회→역할→버전 오류 순서, 상세/생성의 version 문자열과 목록 요약 유지. V4·PROJECT_UPDATE 네 컬럼과 기존 도구/응답 전환은 [계약](project-update-api-v1.md)에 정의한다.
- 대안: 이름만 수정, OWNER 단독/MEMBER까지 허용, 마지막 저장 덮어쓰기, 필드별 자동 병합, Workspace 버전 공유, 설명 누락을 자동 삭제로 해석, 테이블 전체 UPDATE/앱 자동 GRANT.
- 이유: 사용자 선택에 따라 역할 경계를 유지하고 오래된 화면/선행 변경 손실을 막는다. 부모/자원 확인을 역할보다 먼저 수행해 미존재/부모 불일치를 404로 유지한다. 클라이언트/DB 최소 권한 책임을 분리하고 미래 보관/이동 정책은 넣지 않는다.
- 결과: 계약/관련 문서만 갱신했다. 실행 코드·SQL·실제 로컬 V3/PROJECT_CREATE·5/3/6/1은 이번 작업에서 변경하지 않았다. 문서 링크/예제/정책 일관성을 검증하고 기능 테스트/빌드는 실행하지 않는다. 다음은 수정 API·V4·최소 UPDATE 권한 도구 구현과 격리 PostgreSQL 검증이다.


## ADR-041 — Project 수정 구현과 권한·응답 전환 (2026-11-05)

- 맥락: ADR-040의 사용자 확정 정책을 기존 생성/조회·권한 도구·Vue 소비자와 함께 구현한다.
- 선택: 부모+Project 조회 뒤 역할/요청 버전을 검사하고 관리 Entity의 Long @Version·mandatory OPTIMISTIC update/flush·프록시 commit을 사용한다. V4/PROJECT_UPDATE는 네 컬럼 UPDATE만 추가하고 모든 기존 도구가 완전한 새 상태를 보존한다. 상세/생성의 version 문자열을 Vue가 보존하며 목록 요약은 유지한다.
- 대안: 최초 버전 비교만 수행, 무조건 UPDATE/merge, Project 테이블 전체 UPDATE, 부분 ACL 자동 복구, Workspace 버전 공유, 목록 DTO/새 편집 프레임워크 선도입.
- 이유: 요청 전 stale 상태와 UPDATE/commit 경합을 함께 검출하고, 부모 격리/기존 권한·현재 UI를 유지한다. no-op은 버전/시간을 바꾸지 않아도 종료 시 검증한다. 이전 마이그레이션 테스트는 명시적 target을 사용해 latest 추가와 분리한다.
- 결과: 실행 코드/V4/전용 도구·검사/fixture·Vue 버전 파서와 테스트/문서를 변경했다. 실제 명령/결과/초기 검증 실패는 local-setup.md에 구분해 기록한다. 실제 로컬 V4/권한/수정과 Vue 수정 폼은 별도 단계다.
- 2026-11-06 검증 완료: Java 340개·실제 Vite 프록시 1개·프론트 203개/빌드·PowerShell 전체 80개 통과. 실패/중단된 검증은 별도로 기록하고 새 격리 실행의 성공/정리를 확인했다. 실제 로컬 V3/PROJECT_CREATE·5/3/6/1과 보호 파일 6개는 유지했다.


## ADR-042 — 실제 로컬 Project 수정의 적용·검증 경계 (2026-11-07)

- 맥락: 사용자가 로컬 V4·최소 Project UPDATE 적용과 새 앱의 수정 확인을 명시적으로 요청했다. 격리 구현 검증은 ADR-041에서 완료했다.
- 선택: 전용 migrator V4/Validate/반복 → 별도 최소 권한 도구 최초/반복 → 임시 loopback 새 JAR/서버 지정 행위자로 HTTP 검증했다. 기존 Project 1을 한 번 수정하고 ADMIN은 no-op으로 확인했다. PATCH 전 시도 영수증을 남겨 결과 불명 때 자동 재전송하지 않는다. 영구 쓰기 설정은 유지한다.
- 대안: 앱 부팅 자동 migration/GRANT, 직접 관리자 SQL로 업무 수정, 테스트용 Project 추가, 이전 version으로 수동 복구, 영구 쓰기 모드 활성화.
- 이유: 기존 실행기/최소 권한과 실제 Controller→Service→JPA→commit 흐름을 함께 검증하고 기존 행 수·부모/다른 데이터를 보존한다. 성공한 버전을 되돌리면 낙관적 검증 이력을 왜곡하므로 version 1을 유지한다.
- 결과: 로컬 V4/PROJECT_UPDATE·5/3/6/1, Project 1 name/description/updated_at/version만 변경했다. 수정/충돌/인가/출처/쓰기 off와 DB 최소 권한 검증이 통과했고 자격증명/SQL/JAR 7개·부모 환경을 보존했다. 검증 앱은 종료했으며 실제 명령·검증문 수정은 local-setup.md에 기록했다. 다음은 Vue 수정 폼 연결이다.


## ADR-043 — Vue Project 두 필드 편집과 명시적 충돌 복구 (2026-11-07)

- 맥락: Project 수정 API/V4/최소 UPDATE와 실제 로컬 검증을 완료한 뒤 사용자가 Vue 이름·설명 수정 폼/버전 보존/409 안내 연결을 요청했다.
- 선택: 별도 ProjectUpdateForm에서 편집 시작 Project 스냅샷과 두 입력을 분리한다. updateProject는 부모 하위 PATCH/문자열 버전·정규화/200 전체 DTO/정확한 다음 버전을 검증하고, 성공 이벤트로 선택 상세와 같은 ID의 현재 목록 요약만 갱신한다. OWNER/ADMIN 표시 힌트와 서버 최종 인가를 유지한다.
- 선택: 409/결과 불명은 입력과 기준을 유지하고 저장을 잠근다. 최신 Project와 Workspace 역할 GET이 모두 성공해야 두 필드 비교/명시적 기준 갱신이 가능하며 별도 수동 저장을 요구한다. GET 내용 일치는 특정 PATCH의 성공 증명이 아니다. 최신 조회 실패는 이전 성공 스냅샷을 폐기한다.
- 대안: props version 자동 동기화, 마지막 저장 덮어쓰기/필드별 자동 병합, GET 직후 자동 재전송, 버전을 number로 변환, Project DTO에 역할 추가, Router/Pinia/범용 폼 계층 도입.
- 이유: 한 행 단위 충돌 정책과 응답 유실·권한 상실·늦은 응답 경계를 보존한다. 기존 Composition API/fetch/지역 상태/Workspace 선례로 충분해 새 의존성을 추가하지 않는다.
- 결과: Vue 폼/API/선택 상세·목록 연결/CSS/테스트와 문서를 변경했다. 실제 실행 명령과 결과는 local-setup.md에 기록하며 백엔드/SQL/권한/영구 환경 설정은 바꾸지 않는다. 실제 로컬 데이터는 보존하고 실제 브라우저 쓰기는 고유 격리 DB에서만 검증한다.

- 검증 결과: 프론트 246개/타입 검사·빌드와 실제 Chrome/Vite/격리 PostgreSQL 검증 통과. 200 정상·409 수동 복구·commit된 응답 유실·VIEWER/405·모바일을 확인했다. 임시 자원/자격증명을 정리하고 실제 로컬 스냅샷·보호 파일 8개를 보존했다.


## ADR-044 — 첫 Task 네 필드·멤버 생성·초기 TODO (2026-11-07)

- 맥락: Project 수정 Vue 연결 이후 사용자가 Task 데이터 모델·생성/조회 권한 범위 확정을 요청했다. 기존 요구사항은 담당자/마감일/칸반/댓글·이력까지 포함하지만 작은 단계로 범위를 나눠 확인했다.
- 사용자 선택: 첫 생성/목록/상세는 제목/설명/상태/우선순위, Workspace 멤버십 상속/OWNER·ADMIN·MEMBER 생성/네 역할 조회, 서버 TODO 고정/우선순위 선택·생략 시 MEDIUM이다. 담당자/마감일/수정·칸반/댓글·이력은 후속이다. 부모 불일치/미소속·미존재는 동일 404, 올바른 부모의 VIEWER 생성은 403이다.
- 구현 기본값: 제목 1~200 코드 포인트/동일 제목 허용, 설명 선택 일반 텍스트/최대 2,000, 스칼라 project_id/FK·중첩 부모 확인/문자열 ID·Slice, V5와 tasks SELECT/INSERT만 추가하는 TASK_CREATE 상태를 설계했다. 기존 Project 관리 역할/부모 버전·이전 ACL은 유지한다.
- 대안: 전체 MVP 필드 동시 도입, OWNER/ADMIN만 Task 생성, 임의 초기 상태 선택·우선순위까지 고정, workspace_id 중복/미사용 version, Task별 membership·새 인증/범용 상태 프레임워크.
- 이유: 사용자가 선택한 단계와 실제 참여자의 작업 생성 권한을 지키면서 Project 관리 권한은 넓히지 않는다. TODO 생성과 상태 변경/Activity 트랜잭션을 분리하고 부모/Service/DB 권한의 책임을 각각 유지한다.
- 결과: task-model-and-api-v1.md와 관련 문서만 갱신했다. 현재 POST 필터가 Task 경로를 포함하지 않는 점을 후속 확장/실제 Vite 검증 대상으로 명시했다. V5/실행 코드/실제 로컬 DB·권한은 바꾸지 않았으며 기능 테스트/빌드는 실행하지 않는다. 다음은 V5·Task 매핑·생성/조회 API·최소 권한 도구 구현과 격리 검증이다.


## ADR-045 — Task 생성/조회 구현과 TASK_CREATE 전환 (2026-11-07)

- 맥락: ADR-044의 네 필드/세 역할 생성/네 역할 조회/TODO·우선순위 정책을 V5·API·최소 권한 도구로 구현한다. 실제 로컬 적용과 Vue는 별도다.
- 선택: Task는 스칼라 projectId와 DB FK/문자열 enum을 사용한다. Service가 Workspace membership→Project id+workspaceId→세 역할 또는 Task id+projectId를 확인한다. 생성은 mandatory persist/flush/commit 이후 201이고 부모 버전/시간을 바꾸지 않는다. 기존 strict JSON·개발 경계를 재사용하며 Task POST를 출처 필터에 명시적으로 포함한다.
- 선택: V5는 테이블/제약/인덱스만, 별도 Task 권한 도구는 PROJECT_UPDATE→TASK_CREATE로 tasks SELECT/INSERT만 추가한다. 기존 모든 도구가 완전한 새 상태를 보존하고 partial/excess/PUBLIC/grant option은 자동 수선하지 않는다. 이력/seed/기동 검사와 최신 fixture를 V5로 전환하고 과거 V4는 명시적 target 4로 검증한다.
- 대안: URL/프론트 역할만 신뢰, Task에 workspaceId 중복/부모 cascade, Project 변경으로 Task 생성 처리, 일반 save/delete·Task UPDATE 권한 추가, 앱 부팅 자동 migrate/GRANT, 모든 과거 테스트를 latest 가정으로 유지.
- 이유: 사용자 확정 정책과 기존 모듈형 모놀리스·Service 트랜잭션/최소 권한을 유지한다. 새 깊은 POST 경로가 기존 필터에서 누락되는 것을 방지하고, 새 migration이 이전 단계 검증의 의미를 바꾸지 않게 한다. 새 의존성은 필요하지 않다.
- 결과: V5/Task 계층·권한 도구·기존 실행기/기동 검사/필터·격리 테스트와 문서를 변경했다. 실제 검증 명령/결과/실패는 local-setup.md에 기록한다. 실제 로컬 V4/PROJECT_UPDATE와 사용자 데이터는 별도 적용 전까지 유지한다.

- 검증 완료: Java 396개·실제 Vite 프록시 1개·PowerShell 92개와 빌드 성공. 실제 로컬 V4/5/3/6/1·Project 1 version 1/보호 파일 7개는 유지했고 고유 격리 자원·임시 자격증명을 정리했다. 집중 56개는 전체 Java에 포함되며 프론트 기존 246개는 이번에 반복하지 않았다.


## ADR-046 — 실제 로컬 V5·첫 Task 생성의 검증 경계 (2026-11-08)

- 맥락: 사용자가 V5·최소 Task 권한 실제 적용과 새 앱 생성/조회 확인을 명시적으로 요청했다. 격리 구현 검증은 ADR-045에서 완료했다.
- 선택: 전용 migrator V5/Validate/반복과 별도 Task 권한 도구 최초·반복을 실행하고 임시 loopback 새 JAR를 검증했다. 단일 POST는 MEMBER Carol로 수행해 Task 생성과 Project 관리 역할 차이를 확인했다. 나머지 역할은 조회/거부 검사로 확인하며 POST 전 영수증으로 결과 불명 자동 재전송을 막았다.
- 대안: 관리자 SQL로 업무 INSERT, 모든 생성 가능 역할로 실제 행 추가, 앱 부팅 자동 migration/GRANT, 영구 쓰기 활성화, 예제 seed 초기화.
- 이유: 기존 분리 실행기/최소 권한과 실제 Controller→Service→JPA→commit 흐름을 함께 확인하고 불필요한 업무 데이터/설정 변경을 줄인다. OWNER/ADMIN 생성 허용은 앞선 격리 테스트와 이번 상속 조회를 구분해 기록한다.
- 결과: 로컬 V5/TASK_CREATE·5/3/6/1/1, Task 1 TODO/MEDIUM을 확인했다. 기존 Project version 1/다른 데이터·이력/ACL·시퀀스와 자격증명/V1~V5/JAR 해시 9개는 유지했다. SQL 최소 권한·HTTP/역할·부모/출처/쓰기 off·health 검증이 통과했고 검증 앱은 종료했다. 소유자 ACL 고정 문자열 기대값 수정과 실제 명령/결과는 local-setup.md에 기록했다. Vue Task는 후속이다.


## ADR-047 — Vue Task 목록·상세 조회 연결 (2026-11-08)

- 맥락: V5/TASK_CREATE와 첫 로컬 Task 생성·역할별 조회는 완료했다. 이번 범위는 선택한 Project의 Task 조회 화면이다.
- 선택: 기존 fetch·Composition API 지역 상태로 tasks.ts/TaskBrowser를 추가한다. Project 상세가 유효할 때만 Project ID key로 등록하며 상위 Workspace key와 함께 두 부모 수명을 보장한다. 20개/ID 오름차순 Slice·별도 상세 GET, 문자열 ID/부모/enum/텍스트 런타임 검사와 안전한 오류를 사용한다.
- 선택: 목록/상세의 AbortController를 분리하고 선택·페이지/새로고침·부모 종료 시 이전 요청을 취소한다. 응답 적용 때 취소 여부를 검사해 늦은 응답을 무시한다. 설명은 Vue 보간과 pre-wrap으로 표시하고 상태/우선순위는 한국어 라벨로 보여준다. 네 역할 모두 조회하며 최종 인가는 기존 Service에 둔다.
- 대안: ProjectBrowser에 Task 상태까지 넣기, 전역 store/Router/캐시 추가, 목록에 상세/설명을 임의 보충하기, 실패를 빈 목록으로 표시하기.
- 이유: 부모 전환과 서버 데이터/UI 상태의 경계를 작게 유지한다. TypeScript 타입만으로 HTTP 응답을 검증할 수 없고 브라우저의 취소만으로 오래된 결과 적용까지 막을 수 없다. 새 의존성은 필요하지 않다.
- 결과: 프론트 297개/타입 검사·빌드, 실제 Chrome/Vite/읽기 전용 새 JAR의 Alice/Dave 조회·Eve 격리·503/재시도·부모 전환/이전 상세 종료·1280/390px를 확인했다. 29개 API 요청 모두 GET이고 브라우저 예외 0건이다. 실제 로컬 5/3/6/1/1·전체 스냅샷과 보호 파일 9개를 유지했다. 생성 폼/Task 수정·상태 변경·인증은 후속이다.


## ADR-048 — Vue Task 생성 폼과 POST 결과 구분 (2026-11-08)

- 맥락: Task 생성/조회 API·로컬 V5/TASK_CREATE와 Vue 조회가 준비됐다. 이번은 확정된 제목/설명/우선순위의 생성 UI 연결이다.
- 선택: ProjectBrowser가 Workspace myRole을 TaskBrowser까지 전달하고 OWNER/ADMIN/MEMBER에 TaskCreateForm을 표시한다. UI 역할은 표시 힌트이고 서버의 부모/멤버십/역할 검사가 최종 인가다. MEMBER의 Project 생성/수정 폼은 계속 숨긴다. 새 의존성/백엔드/권한 변경은 없다.
- 선택: 제목 1~200/선택 설명 2,000 코드 포인트·서버와 같은 정규화, LOW/MEDIUM/HIGH 선택과 기본 MEDIUM을 사용한다. POST는 title/description/priority만 보내며 두 부모는 URL에 고정한다. status는 보내지 않고 201 DTO에서 TODO·요청 필드/부모·상대 Location을 확인한다.
- 선택: 진행 중 중복 제출을 막고 성공 뒤 입력/우선순위를 초기화한다. 201 성공과 후속 목록 0페이지/새 ID 상세 조회 실패를 분리한다. 탐색 순번과 취소로 이전 생성의 후속 조회가 새 선택을 덮어쓰지 않게 한다. 400/403/404/405/413/415는 입력 유지/확정 거부, 네트워크/시간 초과/5xx/잘못된 201은 결과 불명으로 입력/잠금을 유지한다.
- 대안: 모든 역할에 폼 표시, 클라이언트 status/행위자/역할 입력, 자동 POST 재시도, 같은 제목을 GET으로 찾으면 성공 처리, 실패한 후속 GET을 생성 실패로 변경.
- 이유: 제목 중복을 허용하므로 재시도는 독립 Task를 만들 수 있다. GET 목록 확인은 상태를 보여주지만 이전 요청의 성공 영수증이 아니다. 결과 불명 뒤 새 입력은 사용자가 명시적으로 초기화하고 별도 수동 제출하도록 한다. 컴포넌트 종료의 abort는 이미 처리된 서버 트랜잭션을 되돌리지 않는다.
- 결과: TaskCreateForm/createTask·TaskBrowser 후속 조회/ProjectBrowser 역할 전달·CSS와 테스트를 추가했다. 실제 실행 명령·결과와 격리 DB/로컬 보존은 local-setup.md에 구분한다. Task 수정/상태 변경·인증은 후속이다.


ADR-048 검증 결과: 프론트 17파일/355개·타입 검사/Vite build와 실제 Chrome/Vite/고유 PostgreSQL에서 MEMBER 정상 생성·HIGH/TODO·중복 차단·201 후 조회, commit된 201 유실/GET 확인 동안 미재전송·VIEWER/405·데스크톱/모바일을 확인했다. Task POST 3회 중 2건 commit/1건 405이며 격리 5/2/5/1/2와 부모 Project/Workspace 불변을 확인했다. 임시 자원을 정리하고 실제 로컬 5/3/6/1/1·전체 스냅샷과 보호 파일 9개를 보존했다. 상세 명령/테스트 기대값 수정은 local-setup.md에 기록했다.


## ADR-049 — Task 수정 필드·세 역할·Task 버전 충돌 정책 (2026-11-08)

- 맥락: Task 생성/조회와 Vue 연결은 완료했다. 사용자가 첫 수정 범위·역할·동시 수정 정책 확정을 요청했고 세 질문에 답했다.
- 선택(사용자 확정): 제목·설명·우선순위 수정, 설명 null/빈 값 삭제, 해당 Workspace OWNER/ADMIN/MEMBER가 공간 내 모든 Task 수정 가능, VIEWER 403·미소속/미존재/부모 불일치 동일 404, Task 자체 버전/409·서로 다른 필드도 같은 Task면 충돌이다.
- 선택(구현 기본값): 기존 정규화/길이·중복 제목 허용 유지, PATCH 필수 title/description/priority/version 네 필드·32 KiB strict JSON, 정규화 no-op은 시각/버전 유지하되 stale/commit 낙관적 검증, V6 version bigint 기본 0·비음수 CHECK, Task UPDATE(title,description,priority,updated_at,version) 다섯 컬럼의 별도 도구로 설계한다. 상세/생성/수정은 version 문자열 포함 여덟 필드로 전환하고 목록 여섯 필드는 유지한다.
- 대안: 제목/설명만, 상태 변경까지 포함, OWNER/ADMIN만 수정, 작성자/담당자 기준 인가 추가, 마지막 저장 덮어쓰기/필드별 자동 병합, 앱 자동 migration/GRANT·테이블 전체 UPDATE.
- 이유: 사용자 선택에 따라 작업 참여자인 MEMBER의 Task 편집을 허용하면서 Project 관리 권한은 유지한다. 한 Task의 앞선 변경 유실은 기존 JPA @Version/JSON 문자열 계약으로 검출한다. 상태 전환/Activity·담당자·날짜·이동/보관 정책은 독립 후속으로 둔다. 권한 확대는 승인 필드로 제한한다.
- 결과: task-update-api-v1.md와 요구사항/아키텍처/기술 스택/로드맵·학습/실행 안내를 문서화했다. PATCH/V6/권한 도구·Vue 편집 코드는 아직 없다. 현재 로컬 V5/TASK_CREATE·5/3/6/1/1은 그대로이며 구현·격리 검증/실제 적용·Vue 폼을 구분한다. 다음은 Task 수정 API·V6·최소 UPDATE 권한 도구 구현과 격리 검증이다.


## ADR-050 — Task 수정 API·V6·TASK_UPDATE 구현 (2026-11-08)

- 맥락: ADR-049의 세 필드/세 역할·Task 자체 버전/409를 구현한다. 실제 로컬 적용·Vue 편집 폼은 후속이다.
- 선택: TaskUpdateController가 필수 title/description/priority/version·32 KiB strict JSON을 검사한다. TaskCommandService.update가 서버 사용자/Workspace membership→Project 부모→Task 부모→세 역할→요청 버전을 검사한다. mandatory TaskUpdateRepository의 OPTIMISTIC 검증/도메인 변경/flush와 트랜잭션 프록시 commit 후 DTO 응답을 사용한다.
- 선택: V6는 Task version bigint 기본 0·비음수 제약만 추가한다. title/description/priority/updated_at·JPA version만 수정하고 status/id/project_id/created_at·부모는 유지한다. no-op은 버전/시각을 유지하되 stale/트랜잭션 종료 시 경합을 검출한다.
- 선택: 별도 AppTaskUpdateAccessRunner/Gradle/PowerShell/정책 SQL은 TASK_CREATE→TASK_UPDATE 및 반복 보존만 수행한다. 정확한 다섯 컬럼 UPDATE와 기존 최소 권한을 검사하고 부분/과도/PUBLIC/GRANT OPTION은 자동 수선하지 않는다. 기존 모든 도구가 새 상태를 보존하고 이력/seed/current fixture는 1~6, 과거 V5는 target 5·미래 seed fixture는 V7로 분리한다.
- 선택: Task 상세/생성에 문자열 version을 함께 전환하고 Vue 파서는 검증·보존/생성 0을 확인한다. 목록 여섯 필드와 기존 화면/생성 계약은 유지하고 편집 폼은 별도다.
- 대안: 요청 버전 비교만 하기, no-op 무조건 성공, status 포함 전체 UPDATE/범용 save·merge, 앱 부팅 자동 GRANT/migration, 과거 migration을 항상 latest로 검증, Vue 파서 전환 미루기.
- 이유: 확정한 업무/보안 경계를 유지하면서 실제 SQL/commit 경합·권한·클라이언트 호환까지 검증한다. 기존 JPA/Flyway/관리자 실행기 패턴을 사용하므로 새 의존성은 필요하지 않다.
- 결과: API/도메인/DTO·V6·최소 권한 도구/기존 경로와 기동 검사·이력/격리 fixture·Vue 파서/테스트를 변경했다. 실제 검증 결과는 local-setup.md에 기록한다. 실제 로컬 V5/TASK_CREATE는 이번 작업에서 변경하지 않는다.

ADR-050 검증 완료: Java 446개/25클래스·실제 Vite 프록시 1개와 전체 빌드, PowerShell 105개, Vue 364개/타입 검사·빌드가 통과했다. 첫 전체의 기존 오류 문구 기대값 1건을 수정한 뒤 전체를 다시 검증했다. 실제 로컬 V5/TASK_CREATE·5/3/6/1/1·전후 스냅샷과 자격증명/V1~V5 해시 8개를 보존하고 격리 자원/임시 자격증명을 정리했다. 다음은 실제 로컬 V6·최소 Task UPDATE 적용과 새 앱 수정 확인이다.


## ADR-051 — 실제 로컬 V6·Task UPDATE 적용과 단일 행 수정 검증 (2026-11-08)

- 맥락: ADR-050에서 Task 수정 API/V6/권한 도구·응답 전환의 격리 검증을 완료했고 사용자가 실제 로컬 적용과 새 앱 수정 확인을 요청했다.
- 선택: 기존 전용 migrator의 V6/Validate/반복과 Task UPDATE 권한 도구 최초/반복을 실행했다. 기존 Task 1만 MEMBER Carol의 실제 PATCH로 변경하고 OWNER/ADMIN은 no-op, VIEWER/미소속/부모·출처·쓰기 off는 거부 검사로 확인했다. 첫 PATCH 시도/결과를 영수증으로 기록해 불명 시 자동 재전송을 막았다.
- 대안: 관리자 SQL로 업무 행 수정, 역할마다 실제 변경/추가 행 생성, 앱 부팅 자동 migration/GRANT, 영구 쓰기 설정 활성화, 테스트 데이터 초기화.
- 이유: 기존 최소 권한/분리 실행기를 유지하면서 실제 HTTP→Service→JPA/commit을 확인하고 불필요한 로컬 데이터 변경을 줄인다. 전체 동시성/설명 삭제/rollback 회귀는 앞선 격리 결과와 구분한다.
- 결과: V6/TASK_UPDATE·Task 1 version 1/HIGH/TODO, 데이터 5/3/6/1/1과 부모 Project version 1 유지다. Task 다섯 필드만 바뀌었고 기존 데이터/이력/ACL/시퀀스·보호 파일 10개를 보존했다. SQL 최소 권한/HTTP·DB 검증이 통과했고 검증 앱은 종료했다. 확인 스크립트 로그 출력 오류 수정/재개와 실제 명령은 local-setup.md에 기록했다. Vue 수정 폼은 후속이다.


## ADR-052 — Vue Task 세 필드 편집·버전 보존·명시적 충돌 복구 (2026-11-08)

- 맥락: Task 수정 API/V6/TASK_UPDATE와 실제 로컬 검증을 완료했다. 이번은 기존 Vue 조회/생성 화면에 제목/설명/우선순위 편집을 연결하는 작업이다.
- 선택: TaskUpdateForm의 편집 시작 Task/문자열 version과 title/description/priority draft를 분리한다. OWNER/ADMIN/MEMBER에게만 표시하고 최종 인가는 서버에 둔다. 같은 Task ID의 props/재조회는 진행 중 draft/기준을 덮어쓰지 않는다.
- 선택: updateTask는 두 부모 하위 PATCH에 title/description/priority/version만 보내고, 200의 두 부모/ID·정규화 세 필드·기존 status·변경 시 version+1/no-op 시 같은 version을 검사한다. 400/403/404/405/409/413/415는 확정 거부, 네트워크/시간 초과/5xx/잘못된 200은 결과 불명이다.
- 선택: 409/결과 불명은 입력/시작 기준을 보존하고 저장을 잠근다. 최신 Task + Workspace 역할 GET이 모두 성공해야 비교를 표시하고, 세 필드 비교→명시적 기준 갱신(입력 유지)→별도 수동 저장을 요구한다. GET 내용 일치는 이전 PATCH 성공 증명이 아니며 자동 병합/재전송하지 않는다.
- 선택: 성공/확인된 최신 DTO는 현재 상세와 같은 ID 목록의 제목/상태/우선순위만 갱신한다. TaskBrowser의 부모/선택/수명 가드와 Task ID key·언마운트 취소로 이전 결과를 다른 Task에 적용하지 않는다. 새 의존성·백엔드/SQL/권한 변경은 없다.
- 대안: TaskBrowser에 편집/복구 상태까지 합치기, 최신 GET으로 입력/버전을 자동 교체, 우선순위를 충돌 검사에서 제외, 자동 PATCH 재시도·클라이언트 역할을 최종 인가로 사용, 새 전역 상태 계층 추가.
- 이유: 사용자 확정 세 필드/한 Task 충돌 정책과 기존 Project 편집 패턴을 유지한다. 우선순위만 바뀌어도 독립적인 Task 버전 변경이고, 응답 유실은 commit 여부를 단정할 수 없다.
- 결과: TaskUpdateForm/updateTask·TaskBrowser 연결·CSS/테스트를 추가했다. 실제 테스트·빌드·격리 Chrome/DB/로컬 보존 결과는 local-setup.md에 구분한다. Task 상태 변경·담당자/마감일·이력은 후속이다.


ADR-052 검증 완료: 프론트 19파일/415개·타입 검사/Vite build, 실제 Chrome/Vite/격리 PostgreSQL에서 MEMBER 저장/중복 차단·목록/상세, 다른 편집자의 409/세 입력·우선순위 비교·명시적 기준/수동 저장, commit된 200 유실/미재전송·VIEWER/405·1280/390px가 통과했다. 실제 로컬 V6/5/3/6/1/1·Task/Project version 1·전체 스냅샷/보호 파일 10개를 보존하고 임시 자원/자격증명을 정리했다. 제목 input/heading ID 충돌과 테스트 fixture/문구 기대값 수정은 local-setup.md에 기록했다.


## ADR-053 — Task 전체 상태 전이·세 역할·최소 Activity 원자성 (2026-11-08)

- 맥락: Task 생성/일반 수정·Vue 버전/409 복구까지 완료했다. 사용자가 상태 변경 API의 허용 전이·권한 범위를 요청했고 세 질문에 답했다. architecture.md의 상태/Activity 동일 트랜잭션 원칙도 적용한다.
- 선택(사용자 확정): TODO/IN_PROGRESS/DONE의 서로 다른 상태 간 모든 전이(바로 완료/완료 취소·재개 포함), 해당 Workspace OWNER/ADMIN/MEMBER의 공간 내 모든 Task 변경, 실제 상태 변경마다 서버 행위자·이전/이후 상태·시간·새 Task 버전의 최소 이력 동시 저장이다. VIEWER 403·미소속/미존재/부모 불일치 동일 404를 유지한다.
- 선택(구현 기본값): 별도 nested PATCH /status의 필수 status/version·기존 strict JSON/개발 쓰기 경계, 기존 Task version 공유/409·현재 버전 동일 상태 no-op(시각/버전/이력 유지), 기존 여덟/여섯 필드 DTO를 사용한다. V7은 빈 task_status_activities·FK/상태 CHECK/from<>to/version>0/Task+version UNIQUE만 추가하고 과거 이력을 backfill하지 않는다.
- 선택(구현 기본값): 일반 Task.status updatable=false 경계를 유지하고 상태 전용 mandatory 어댑터의 조건부 native UPDATE(id/version)·영향 행 수 검사/refresh·이력 고정 여섯 컬럼 INSERT·최종 commit으로 설계한다. no-op은 기존 OPTIMISTIC 최종 검증, 일반 수정은 기존 JPA @Version을 유지한다. 이력 INSERT는 generated keys/RETURNING/SELECT 없이 수행해 조회 권한을 요구하지 않는다.
- 선택(최소 권한): 별도 TASK_STATUS_CHANGE는 기존 TASK_UPDATE + tasks UPDATE(status) + 이력 여섯 입력 컬럼 INSERT만 허용한다. 이력 SELECT/id INSERT/전체 INSERT/UPDATE/DELETE·시퀀스/DDL 등은 제외하고 부분/과도/PUBLIC/GRANT OPTION은 미수선 거부한다. 기존 도구/기동 검사·현재 이력/seed·과거 target/미래 fixture 전환을 후속 범위에 포함한다.
- 대안: 순방향/인접 전이만, OWNER/ADMIN만, Activity 후속, 상태·이력 비동기 분리, 범용 이벤트 JSON/이력 피드 선행, 상태 버전 분리, JPA status 매핑 확대/자동 버전 검증 가정, 이력 ID 반환을 위한 추가 SELECT/sequence 권한.
- 이유: 사용자 선택과 기존 최소 권한/한 Task 충돌·원자성을 유지한다. 이력 실패는 상태도 rollback하고, 일반 필드 수정이 상태를 함께 쓰거나 이력으로 오인되지 않게 경계를 분리한다. 상태는 실제 이력만 기록하며 조회/실시간 기술은 필요 시 후속으로 둔다.
- 결과: task-status-api-v1.md와 관련 문서만 확정했다. API/V7/권한 도구·Vue 상태 UI는 아직 없다. 코드/DB/자격증명은 변경하지 않았고 기능 테스트·빌드는 실행하지 않는다. 다음은 상태 API·V7·최소 권한·이력 원자성 구현/격리 검증이다.

## ADR-054 — 상태 전용 조건부 UPDATE와 최소 이력 INSERT 구현 (2026-11-10)

- 맥락: ADR-053의 모든 전이·세 역할·공유 버전과 최소 이력 동시 저장 정책을 구현하고 격리 PostgreSQL에서 검증한다.
- 선택: strict 두 필드 상태 PATCH → 기존 서버 행위자/부모/역할 검사 → READ_COMMITTED Service → mandatory 상태 Repository로 연결했다. status의 JPA updatable=false를 유지하고 native UPDATE의 조건/버전 증가/행 수 검사·refresh를 명시적으로 수행한다. 이력 고정 컬럼 INSERT도 1건인지 확인하고 commit까지 같은 트랜잭션에 둔다.
- 선택: V7은 빈 전용 이력 테이블/FK/CHECK/UNIQUE만 추가한다. 별도 상태 권한 도구가 기존 TASK_UPDATE에서 status UPDATE·이력 여섯 컬럼 INSERT만 원자적으로 부여하며, 기존 도구/기동 검사/seed/history·과거 target/미래 V8 fixture를 전환한다. 이력 조회·generated keys·sequence 권한은 추가하지 않는다.
- 대안: status JPA 수정 매핑 확대, 상태와 이력의 비동기 저장, 이력 ID 반환을 위한 SELECT, 앱 부팅 자동 migration/GRANT, 쓰기 off의 기본 404 수용.
- 이유: 일반 수정 컬럼 범위와 기존 한 Task 충돌을 유지하면서 이력 저장 실패도 전체 rollback한다. 쓰기 off의 `/status`는 GET 대응 경로가 없으므로 개발 모드 전용 거부 Controller로 확정된 405를 유지한다.
- 결과: 구현 흐름/재현 명령은 [구현 기록](task-status-implementation.md), 실제 테스트·빌드·실패 수정과 자원 정리 결과는 [실행 기록](local-setup.md)에 구분한다. 실제 로컬 적용과 Vue 상태 UI는 이번 범위에 포함하지 않는다.

ADR-054 검증 완료: 최초 전체 516개에서 기존 JPA 누락 fixture 1건을 수정한 후 JPA 7개/새 GRANT 원자성 1개를 재검증했다. 중복을 교체한 현재 백엔드 고유 검증 517개·실제 Vite 프록시 1개·Gradle build, PowerShell 118개, Vue 415개/타입 검사·빌드가 통과했다. 실제 로컬 V6 전체 스냅샷/보호 파일 9개를 유지하고 두 고유 Compose 프로젝트·임시 자격증명 6개를 정리했다. 단계별 실행 결과와 실패 수정은 local-setup.md에 구분했다. 다음은 실제 로컬 V7/최소 상태 권한 적용이다.

## ADR-055 — 실제 로컬 V7·상태 최소 권한과 단일 상태 이력 확인 (2026-11-10)

- 맥락: ADR-054의 구현·격리 검증이 완료됐고 사용자가 실제 로컬 V7/최소 상태 권한 적용과 새 앱의 상태·이력 확인을 요청했다.
- 선택: 기존 로컬 V6/Task 1 version 1/TODO와 검증된 JAR을 확인한 뒤 전용 migrator로 V7/Validate/반복, 전용 상태 권한 도구 최초/반복을 실행했다. 데이터·이전 이력/ACL/시퀀스·11개 보호 파일을 단계별 대조했다.
- 선택: MEMBER Carol로 기존 Task 1을 TODO→IN_PROGRESS/version 1→2 한 번만 변경하고 서버 행위자·이전/다음 상태·새 버전·Task와 같은 시각의 이력 1건을 확인했다. OWNER/ADMIN은 최신 버전 no-op으로 허용을 확인했다. stale 상태/일반 수정 409, VIEWER 403·미소속/부모 404·Origin/입력·쓰기 off 405·건강 상태도 검증했다.
- 대안: 관리자 SQL로 업무 상태 변경, 역할마다 실제 전환/복원하며 여러 이력 생성, 앱에 이력 SELECT 권한 추가, 실제 DB에 장애/동시성 fixture 삽입, 앱 부팅 자동 migration/GRANT.
- 이유: 승인된 실제 HTTP→Service/native UPDATE/이력 INSERT/commit을 확인하면서 기존 업무 데이터 변경을 한 전환으로 제한한다. 이력 검증용 관리자 읽기와 앱의 최소 쓰기 권한을 구분하고 실패/경합 검증은 기존 격리 결과와 구분한다.
- 결과: 실제 로컬 V7/TASK_STATUS_CHANGE·5/3/6/1/1 + 상태 이력 1건이다. Task 1은 HIGH/IN_PROGRESS/version 2이고 일반 필드·부모/기존 데이터는 유지했다. 검증 앱 6개를 종료해 8080/18081은 비어 있고 DB는 정상 실행 중이다. 실제 명령과 영수증은 local-setup.md에 기록했다. 다음은 Vue 상태 UI·공유 버전/409 복구다.

## ADR-056 — Vue 상태 전용 폼·교차 편집 기준과 명시적 복구 (2026-11-10)

- 맥락: V7/TASK_STATUS_CHANGE와 실제 로컬 상태/이력 확인을 마쳤고 사용자가 Vue 상태 UI·버전/409 연결을 요청했다. 일반 수정과 상태 변경은 서버의 한 Task version을 공유한다.
- 선택: TaskStatusForm/changeTaskStatus에 status/version 전용 PATCH를 연결하고 일반 폼과 독립된 시작 Task/문자열 version·선택을 보존한다. 성공 DTO는 현재 상세/동일 ID 목록에만 반영하며 다른 폼의 입력/버전은 자동 교체하지 않는다.
- 선택: 409/결과 불명은 선택 보존/잠금, Task/Workspace 역할 공동 GET·상태/일반 필드 비교·명시적 기준 갱신·별도 수동 저장으로 처리한다. 오래된 GET/표시 버전 역행·조회 실패/권한 상실·언마운트 후 응답을 막는다. 일반 폼에도 시작/서버 상태 비교와 오래된 복구 GET 거부를 적용했다.
- 대안: 일반 편집과 상태 입력을 하나의 PATCH/폼으로 합치기, props/GET으로 두 폼의 버전을 자동 덮어쓰기, 서로 다른 상태 버전, GET 내용 일치로 성공 간주/자동 재전송, 공통 변경 프레임워크·전역 상태 계층 추가.
- 이유: 확정된 API/공유 버전과 기존 사용자 입력을 보존한다. 상태 전용 성공은 일반 필드 불변까지 확인해야 하고, 응답 유실은 서버 commit 여부를 단정할 수 없다. 작고 명시적인 컴포넌트와 기존 복구 패턴으로 범위를 유지한다.
- 결과: Vue 21파일/479개·타입 검사/Vite build와 실제 Chrome/Vite/고유 PostgreSQL에서 교차 폼 양방향/상태끼리 409·수동 저장·커밋 응답 유실/미재전송·VIEWER/405·데스크톱/모바일을 확인했다. 실제 로컬 V7/Task version 2·이력 1건과 백엔드/infra/JAR/package 등 167개 파일을 보존하고 임시 자원/자격증명을 정리했다. 명령과 로그는 local-setup.md, 설계/흐름은 task-status-ui.md에 기록한다. Activity 조회는 후속이다.

## ADR-057 — Task 상태 이력 전체 조회·현재 이름·최신순 버전 커서 (2026-11-10)

- 맥락: 상태 변경/최소 이력 저장·실제 로컬 V7·Vue 상태 UI까지 완료했다. 사용자가 이력 조회 API의 계약/권한 범위를 요청했고 세 정책 질문에 답했다.
- 선택(사용자 확정): 현재 Workspace OWNER/ADMIN/MEMBER/VIEWER 모두 Task 전체 과거 이력을 읽는다. 가입 전/현재 탈퇴한 행위자 기록도 포함한다. 행위자는 ID·현재 표시 이름만 제공하고 이메일은 제외한다. Task 버전 내림차순·배타적 beforeVersion 커서, 기본 20/최대 100건을 사용한다.
- 선택(구현 기본값): Task별 status-activities GET·부모/Task 포함 8필드 envelope/이력 6필드/actor 2필드, 문자열 ID/버전·UTC ISO 시각, 입력/서버 행위자/현재 소속·두 부모 검사 후 readOnly projection을 사용한다. 미소속/미존재/부모 불일치는 동일 404이며 VIEWER도 조회 200이다. 쓰기 off GET도 허용한다.
- 선택(최소 권한/범위): V7/기존 인덱스를 재사용하고 새 migration·전체 count·다른 종류 이력/통합 피드/이력 단건·실시간은 추가하지 않는다. TASK_STATUS_HISTORY_READ는 기존 상태 + audit SELECT 7개 컬럼만 추가한다. 전용 관리자 도구·원시/유효 ACL·기존 모든 도구 보존·읽기/쓰기 기동 전환을 후속 구현 범위로 정한다. 실제 로컬 GRANT는 구현/격리 검증 후 별도 요청으로 수행한다.
- 대안: OWNER/ADMIN만 조회, 행위자 ID만 제공, 변경 당시 이름/역할 스냅샷 추가, offset 페이지·시간/전역 ID 커서, table-wide SELECT/앱 자동 GRANT, 범용 Activity 프레임워크/전체 피드 선행.
- 이유: 사용자 선택에 따라 기존 Task 읽기 권한과 전체 상태 맥락을 유지하면서 이메일을 노출하지 않는다. 현재 이름의 의미를 명시하고 append-only 상태 이력/Task 버전 경계로 새 기록의 페이지 밀림을 피한다. 고정 컬럼 권한과 단일 projection으로 범위를 좁히며 기존 쓰기/이력을 보존한다.
- 결과: task-status-history-read-api-v1.md와 관련 문서를 확정했다. 코드/SQL/DB는 변경하지 않았고 조회 API·권한 도구·Vue 이력 화면은 아직 없다. 문서 예제/링크·파일 보존 검증은 local-setup.md에 기록한다. 다음은 상태 이력 조회 API·최소 SELECT 도구 구현/격리 PostgreSQL 검증이다.
## ADR-058 — 상태 이력 projection·컬럼 SELECT와 기동 경계 구현 (2026-11-10)

- 맥락: ADR-057의 전체 과거 이력/현재 표시 이름/최신순 버전 커서를 구현하고 격리 PostgreSQL에서 검증한다. 실제 로컬 권한 적용과 Vue 이력 화면은 별도 요청이다.
- 선택: dev-actor GET → strict 입력 → 현재 서버 사용자/Workspace 소속·Project/Task 부모 → READ_COMMITTED/readOnly Service → mandatory native projection Repository로 연결했다. 이력과 현재 users.display_name만 선택하고 actor membership/User Entity 직렬화를 사용하지 않는다. UTC to_char/Instant 변환으로 native 시각 타입 차이를 명시하고 마이크로초를 보존한다.
- 선택: 기존 UNIQUE(task_id,task_version)와 배타적 beforeVersion·DESC·size+1로 페이지를 구성한다. 문자열 ID/버전과 8/6/2필드 DTO, 마지막 페이지 null 커서를 유지하며 count/새 migration·인덱스·Activity Entity·의존성을 추가하지 않는다.
- 선택: TASK_STATUS_HISTORY_READ는 기존 전체 상태 + audit 고정 7개 컬럼 SELECT다. 전용 관리자 실행기·SQL·Gradle/PowerShell과 원시/유효 ACL 검증, 이전 모든 도구의 완전 상태 보존을 구현했다. 부분/과도/PUBLIC/GRANT OPTION은 미수선 거부한다. 새 dev-actor 앱은 쓰기 on/off 모두 정확한 읽기 권한을 요구하고 기존 쓰기 검사도 전환한다.
- 대안: Activity Entity/지연 로딩·항목별 사용자 조회, actor의 현재 membership JOIN, native 시각 Java 타입/세션 timezone 가정, offset/시간/전역 ID 커서, table-wide SELECT·앱 자동 GRANT, 과거 상태를 새 도구가 임의 복구하기.
- 이유: 조회자의 현재 인가와 행위자의 과거 기록을 분리하고 이메일/추가 쿼리를 피한다. 기존 버전/인덱스와 최소 범위를 재사용하며 미래 컬럼에 권한이 자동 확장되지 않게 한다. 부분 정책을 은폐하거나 앱 시작으로 관리자 변경을 수행하지 않는다.
- 결과: [구현 기록](task-status-history-implementation.md)에 흐름/변경 파일/재현 명령을, [실행 기록](local-setup.md)에 실제 결과와 실패 수정을 구분한다. 실제 로컬 V7/TASK_STATUS_CHANGE·Task version 2/이력 1건은 보존하며 다음은 로컬 SELECT 적용과 새 앱 이력 조회 확인이다. Vue 이력 화면·전체 Task MVP/Phase 1은 후속이다.

**최종 검증: Java 30클래스/565개·실제 Vite 프록시 1개·Gradle build, PowerShell 전체 134개, Vue 21파일/479개·타입 검사/Vite build가 모두 통과했다.** 새 이력 API 35개/권한 정책 13개를 포함하며 전체 회귀는 실패/오류/스킵 0이다. 최초 집중 실행의 기존 오류 문구 기대값 1건은 수정 후 이 전체 실행으로 통과를 확인했다. 실제 로컬 권한/데이터·보호 파일 52개를 유지하고 임시 자원/격리 자격증명 3개를 정리했다. 다음은 **로컬 상태 이력 SELECT 권한 적용과 새 앱의 이력 조회 확인**이다.


## ADR-059 — 로컬 Task 상태 이력 최소 SELECT 적용 및 GET 확인 (2026-11-10)

- 맥락: ADR-058의 API/도구 구현과 격리 PostgreSQL 전체 검증을 마쳤고 사용자가 로컬 상태 이력 SELECT 적용·새 앱 GET을 요청했다.
- 선택: 사전 read-only full snapshot/현재 ACL/V7을 점검한 뒤 기존 전용 `provision-task-status-history-read.ps1` 경로로 TASK_STATUS_HISTORY_READ를 적용했다. 새 권한은 audit 7컬럼 SELECT만 추가하고 기존 6컬럼 INSERT와 Task 상태 UPDATE를 유지했다. 별도 비밀번호 변경은 없었다.
- 선택: 새 검증 JAR을 18081/127.0.0.1·dev-actor Carol·쓰기 off로 실행했다. Actuator UP, 상태 이력 GET/응답 shape·현재 표시 이름/이메일 제외·UTC 마이크로초·마지막 커서·strict 입력을 확인하고 임시 서버만 종료했다.
- 이유: 요청된 업무 기능을 실제 앱 DB 계정·실제 PostgreSQL에서 확인하면서 범위가 한 번의 SELECT grant로 제한되는지 전후 snapshot으로 증명한다.
- 결과: 실제 데이터 5/3/6/1/1과 기존 Activity 1건, Flyway V1~V7·다른 ACL·시퀀스가 보존됐다. 새 앱 조회 영수증은 `backend/build/task-status-history-verification/local-http-receipt.json`, 전체 명령/결과는 [local-setup.md](local-setup.md)다. 다음은 Vue 이력 화면 연결이며 로그인은 여전히 실제 인증이 아니다.
## ADR-060 — Vue Task 상태 이력 패널·version cursor 연결 (2026-11-10)

- 맥락: TASK_STATUS_HISTORY_READ 계약, 격리·로컬 최소 SELECT/HTTP 검증을 완료했고 사용자가 Vue Task 상태 이력 화면 연결을 요청했다.
- 선택: Task 상세에 기본 접힌 이력 패널을 둔다. 펼칠 때 Task별 최신순 20건을 fetch하고 서버 `nextBeforeVersion` 커서로 이전 페이지를 이어 붙인다. 응답 부모/필드/actor/status/UTC time/버전·정렬·커서를 API client에서 검사한다.
- 선택: 로딩/초기 실패/빈 결과/추가 페이지 실패를 구분한다. 더 보기 실패 시 표시된 row/cursor를 보존해 같은 경계에서 재시도한다. 열린 패널은 상태 전이 뒤 최신 페이지를 다시 읽고 Task 교체/새로고침/컴포넌트 해제 시 요청을 취소하고 세대를 검사한다. 현재 actor 이름/전이/시각/Task version만 표기하고 이메일은 사용하지 않는다.
- 대안: Task 상세를 선택할 때마다 자동 조회, offset 페이지, 페이지마다 기존 목록을 교체, 현재 작업 version을 페이지 경계로 사용.
- 이유: 접힌 시작은 불필요한 fetch를 줄이고 이전 cursor 경계는 새 최신 기록이 이미 받은 과거 페이지를 밀지 않는다. Task version은 일반 수정에서도 증가하므로 client가 연속성을 가정하지 않는다. 취소/generation은 기존 Task detail parent-change 패턴을 재사용한다.
- 검증: `npm.cmd run build`에서 vue-tsc와 Vite production build 성공. 첫 타입 검사에서 대상 lib에 `Array.at`이 없어 배열 마지막 인덱스로 교체했다. Vitest/브라우저 수동 검증은 실행하지 않았다. 상세는 [task-status-history-ui.md](task-status-history-ui.md).
- 결과: 프론트 UI/API/스타일과 화면 흐름을 구현했다. 실제 로그인/전체 Task MVP/Phase 1은 범위 밖이다. 다음 한 작업은 **Task 담당자·마감일 필드의 모델·권한 범위 확정**이다.

## ADR-061 — Task 담당자·마감일 모델 및 권한 범위 (2026-11-10)

- 맥락: 첫 Task 단계에서 담당자·마감일을 미뤘다. 이름/설명/우선순위 수정, 상태 변경과 상태 이력은 별도 계약으로 구현했다.
- 선택: nullable 단일 assignee와 nullable 달력 날짜를 둔다. 할당 시점의 현재 Workspace 멤버만 대상으로 하며 OWNER·ADMIN·MEMBER·VIEWER 모두 배정 대상이 될 수 있다. Workspace에서 제거된 사용자에게 이미 지정된 assignee는 유지하고, 이후 재지정은 현재 멤버로 제한한다.
- 선택: 마감일은 Java `LocalDate`/PostgreSQL `date`, strict `YYYY-MM-DD` 문자열이다. 과거 날짜도 허용하며 시간대/시각을 두지 않는다.
- 선택: 두 필드를 생성·전체 스냅샷 수정 요청과 Task 목록·상세 응답에 포함한다. 기존 쓰기 역할 OWNER·ADMIN·MEMBER, 읽기 네 역할, Task 단일 version/409를 유지한다. 변경 Activity, 필터/정렬, 선택 사용자 이름/email, 알림은 미포함이다.
- 대안: ProjectMembership/TaskMembership 추가, assignee membership FK, 제거 전 재할당 강제, due timestamp, 별도 assignment version/Activity.
- 이유: 기존 모듈형 Workspace 소속 관계를 재사용한다. User FK는 참조 무결성을 보장하고 Service membership 검사는 현재 배정 자격을 보장한다. membership FK는 제거 후 기존 할당 보존 정책과 맞지 않는다. 달력 날짜에 timezone timestamp를 쓰면 client timezone에서 날짜가 이동할 수 있다.
- 영향: V8 nullable 컬럼, Task Service/DTO/API와 최소 컬럼 ACL·기동 검사 변경이 필요하다. Membership 제거와 재할당의 동시 경합은 제거 기능 설계 시 잠금/직렬화 규칙으로 확정한다. 현재 제거 API는 없다. 구현/격리 검증은 다음 작업이며 실제 로컬 적용은 별도다.
- 상세: [Task 담당자·마감일 결정 문서](task-assignee-due-date-v1.md).
- 구현 결과: V8·API·Task 멤버십 검증·column ACL 도구와 Vue API parser 호환 변경을 완료했다. 격리 백엔드 89개/프론트 484개 테스트와 빌드가 통과했다. 상세는 [구현 기록](task-assignee-due-date-implementation.md)이다.

### 실제 로컬 V8·권한·HTTP 검증 — 2026-11-11

사용자 요청으로 전용 migrator가 로컬 V8 1건을 적용했고 Validate 성공/재실행 0건을 확인했다. 전용 권한 도구 최초·반복 실행 후 Task SELECT 11/INSERT 8/UPDATE 8 열, 기존 Activity SELECT 7/INSERT 6 열과 금지 권한을 확인했다. 새 loopback 앱에서 Task 1에 Dave와 날짜를 지정하고 GET으로 확인한 뒤 둘 다 해제했다. HTTP 10점검 통과, Task 1 공유 version 2→4/`updated_at`만 바뀌었고 나머지 업무 데이터·Activity/ACL/시퀀스는 보존됐다. 상세 실행 기록은 [local-setup.md](local-setup.md)다.

## ADR-062 — Workspace 현재 멤버 목록 조회 계약 (2026-11-11)

- 맥락: Task 담당자 후보를 사람이 식별할 수 있도록 현재 Workspace 멤버 조회가 필요하다. 기존 Task Service의 current membership 검증은 계속 유지해야 한다.
- 선택: 읽기 전용 `GET /api/workspaces/{workspaceId}/members`를 정의한다. 현재 Workspace 멤버인 OWNER·ADMIN·MEMBER·VIEWER 모두 조회할 수 있고, ID·표시 이름·역할만 노출한다. 이메일은 제외한다. 0-based page/size(기본 20·최대 100), 사용자 ID 오름차순 slice를 기존 Workspace 목록 형식과 맞춘다.
- 선택: 초대·수락·역할 변경·제거는 이 범위에 넣지 않고 별도 결정으로 둔다. 미소속 요청자와 미존재 Workspace는 동일 404이며 actor는 서버에서 가져온다.
- 대안: 이메일 포함, 관리자 전용 조회, cursor pagination, 멤버 쓰기 관리까지 동시 구현.
- 이유: Task 지정에 필요한 최소 식별 정보만 제공하고 읽기 ACL/API와 DML 권한·OWNER 불변식·assignment 보존 정책을 분리한다. 서버의 Task command membership 검증은 후보 GET으로 대체할 수 없다.
- 결과: [멤버 목록 계약](workspace-member-list-api-v1.md)에 오류·DTO·페이지 정책을 기록했다. 이번은 정책/계약 확정이며 구현·격리 PostgreSQL·실제 로컬 권한 적용은 후속이다.

## ADR-063 — Workspace 멤버 목록 API와 exact-column SELECT 구현 (2026-11-12)

- 맥락: ADR-062 계약에 따라 멤버 후보 GET과 개인정보 최소 응답을 구현한다. 기존 app role은 users/workspaces/workspace_memberships에 table-wide SELECT가 있어 컬럼 GRANT만 추가하는 방식은 최소 권한이 아니다.
- 선택: 현재 Workspace membership 확인 후 사용자 ID 오름차순 native Slice projection으로 id/display_name/role을 읽는다. 현재 네 역할 허용, 현재 멤버만 노출, 이메일 제외 및 동일 404 규칙을 사용한다.
- 선택: 명시적 관리자 실행기가 세 테이블의 table-level SELECT를 회수하고 현재 JPA 매핑에 필요한 16개 컬럼 SELECT를 한 트랜잭션으로 부여한다. AppReadPolicy는 legacy broad state 또는 정확한 column state만 허용한다. 부분/과도 권한은 실패하고 자동 수선하지 않는다.
- 대안: broad SELECT 유지, 컬럼 권한만 덧붙이기, 멤버 조회와 함께 초대/제거 DML 구현.
- 이유: 후보 API가 필요한 열만 반환하고 DB 권한도 future schema column으로 자동 확대되지 않게 한다. 기존 workspace/project/task 읽기를 위해 현재 매핑된 열은 그대로 허용하고, 쓰기/초대 정책은 별도 결정으로 둔다.
- 결과: [구현 기록](workspace-member-list-implementation.md)에 targeted 76-test PostgreSQL result와 exact grants를 기록했다. 로컬 적용은 아직 하지 않았다. 다음은 Vue 담당자 선택 UI 연결이다.

## ADR-064 — Vue Task 담당자 선택 및 former-member assignment 보존 (2026-11-13)

- 맥락: ADR-062 멤버 목록 API를 Task 작성/수정에 연결한다. 확정된 기존 정책은 멤버 제거 후 기존 할당은 유지하고, 이후 신규 지정/재지정은 현재 멤버로 제한한다.
- 선택: Vue는 select focus 시 현재 멤버 page를 로드해 표시 이름·역할·문자열 ID로 후보를 보여 준다. 모든 page strict parse/중복 검사, abort, 오류/명시 재시도를 사용한다. 생성/수정 API에는 ID/null을 전달하고 Task Service의 membership 검사도 유지한다.
- 선택: 수정 대상의 기존 assignee가 후보 목록에 없으면 기존 ID를 “현재 멤버 아님”으로 표시해 유지 또는 해제만 허용한다. Service는 기존 Task assignee와 요청 ID가 같으면 보존하고, 변경된 non-null ID에 대해서만 현재 Workspace membership을 요구한다.
- 대안: ID 직접 입력 유지, 제거된 멤버 할당을 강제로 지우기, unrelated edit를 포함한 모든 PATCH에서 과거 assignee를 현재 멤버로 재검증하기.
- 이유: UI에서 임의 ID 입력을 없애고, membership 목록이 갱신된 시점과 저장 사이의 변경은 서버가 최종 검증한다. 기존 assignment 보존 계약을 유지하면서 새 할당만 현재 membership에 묶는다.
- 결과: Frontend 496개 테스트·type-check/Vite build, 격리 PostgreSQL `TaskUpdateApiTest` 40개 통과. 실제 로컬 멤버 목록 ACL 전환/HTTP는 별도다. [구현 기록](workspace-member-list-implementation.md).

## ADR-065 — Workspace 멤버 초대·역할·제거 정책 (2026-11-13)

- 맥락: 현재 멤버 목록 API와 Task 담당자 선택이 완료되어 멤버 수명주기 및 OWNER 불변식, 과거 Task 배정 보존 정책을 정해야 한다.
- 결정: 기존 계정에만 초대 코드/링크를 발급하고 외부 메일은 보내지 않는다. 초대는 target user에 묶으며 7일 만료·1회 사용이다. 재발급은 기존 pending 코드를 폐기한다. OWNER·ADMIN은 초대 발급/취소와 non-OWNER 역할 변경·제거를 할 수 있다. OWNER는 기존 non-OWNER 멤버에게만 소유권을 이전하고 같은 트랜잭션에서 자신의 역할을 ADMIN으로 바꾼다. OWNER 강등/제거는 이전 절차 없이 허용하지 않는다.
- 결정: invite code는 암호학적 난수 256비트 이상으로 만들고 digest만 저장한다. 수락 actor는 invite target과 일치해야 한다. 멤버 제거는 Workspace 단위 잠금 경계에서 수행하고 기존 Task assignee 및 Task 상태 이력은 보존한다. 삭제 후 새 배정은 현재 멤버만 가능하다.
- 대안: 이메일 발송/미등록 계정 생성, 즉시 멤버 삽입, 만료 없는 재사용 코드, 관리자도 OWNER 변경, 멤버 제거 때 과거 Task 배정 cascade/clear, 범용 CRUD 권한 부여.
- 이유: 초대 대기와 가입된 membership을 구분하고, 초대 코드 유출·재사용을 제한하며, DB의 최대 OWNER 제약과 Service의 정확히 한 OWNER 불변식을 함께 유지한다. 기존 Task의 할당 이력은 관계 접근 권한과 별도로 보존한다.
- 결과: [멤버십 관리 계약](workspace-membership-management-v1.md)에 초대/권한/이전/보존/격리 수용 기준을 기록했다. 구현 및 격리 검증 결과는 ADR-066에 기록한다. 실제 로컬 적용은 별도 요청이다.

## ADR-066 — Workspace 멤버십 관리 API·V9·최소 권한 구현 (2026-11-14)

- 맥락: ADR-065/멤버십 관리 계약을 기존 dev-actor 개발 API에 구현하고, 앱 부팅과 분리된 V9 migration/권한 실행기를 검증한다.
- 선택: invitation은 target User ID, 256-bit code SHA-256 digest, 7일 만료, pending 부분 UNIQUE로 저장한다. 발급·취소·수락·역할 변경·제거·OWNER 이전은 Workspace row 잠금 뒤 한 트랜잭션에서 처리한다. 이전 OWNER를 ADMIN으로 먼저 flush한 뒤 새 OWNER를 승격하며 마지막 OWNER 수를 검증한다. Task 생성·수정·상태 변경도 공유 잠금을 획득해 제거/재할당을 직렬화한다.
- 선택: 앱 ACL에는 membership DELETE, `role/updated_at` UPDATE, invitation의 현재 매핑 컬럼 SELECT·필요 INSERT·`status/accepted_at` UPDATE만 추가한다. PostgreSQL에 column DELETE가 없으므로 membership의 relation DELETE만 허용한다. 전용 PowerShell/Gradle 실행기와 기존 권한 보존을 제공한다.
- 검증: V9 migration constraint, 최초/반복 ACL, 과도·부분 ACL 거부와 이전 도구 보존, 실제 app role로 HTTP 발급/재발급/수락 actor·만료·replay/취소/역할 변경/제거/동시 OWNER 이전/Task assignee·상태 이력 보존을 Testcontainers PostgreSQL에서 확인했다(6 targeted tests). Workspace/Task 회귀 221 tests와 전용 Compose 경로 153 checks도 통과했다. 전체 `gradle test`는 완주 확인까지 실행하지 않았고 범위별 회귀로 검증했다.
- 결과: 로컬 DB에는 적용하지 않았다. [구현 기록](workspace-membership-management-implementation.md)에 검증 범위를 남겼다. 다음 단계는 전용 migrator로 로컬 V9 적용 및 관리 권한 적용, 새 앱 HTTP 검증이다.

- 후속 실제 로컬 적용(2026-11-19): 전용 migrator로 V9 1건/Validate 성공을 확인했고 관리 ACL은 최초·반복 모두 성공했다. Windows host port 5432 예약으로 Compose/JDBC host port를 15432로 맞췄다. 새 dev-actor 앱 HTTP 초대/수락·역할 변경·멤버 제거·소유권 이전과 Alice/Bob 역할 복구를 확인했다. 검증 초대 행은 삭제했고 최초 seed 행 수를 복구했다. 상세 실행 결과는 [local-setup.md](local-setup.md)에 기록했다.

## ADR-067 — Workspace 초대 대기 목록 API 계약 (2026-11-20)

- 맥락: 현재 UI는 발급 응답으로 받은 초대 코드만 화면에 임시 표시한다. 새로고침 이후에는 pending 초대를 확인하거나 취소할 수 없어 Workspace별 관리 목록이 필요하다.
- 선택: `GET /api/workspaces/{workspaceId}/invitations?page=0&size=20`은 해당 Workspace OWNER·ADMIN만 조회한다. 미소속·미존재 Workspace는 동일 404, MEMBER·VIEWER는 403이다. 서버가 dev-actor를 결정하며 클라이언트 user ID/role은 신뢰하지 않는다.
- 선택: DB status가 PENDING인 초대 전체를 반환한다. 이미 만료된 PENDING도 포함하고 서버가 `expired`를 계산한다. ACCEPTED·REVOKED는 제외한다. 대상 표시 이름과 이메일, 역할, 생성/만료 시각, 문자열 ID를 반환하며 code·digest·target user ID·inviter 정보는 노출하지 않는다.
- 선택: 최신 invitation ID 내림차순, 기존 멤버 목록과 같은 0-based page/size, 기본 20·최대 100과 envelope를 사용한다. 응답은 전용 projection/DTO로 만든다. 기존 V9/ACL을 변경하지 않는다.
- 대안: 모든 Workspace 멤버에게 노출, 이메일 제외, 만료되지 않은 행만 반환, 수락·취소 이력 포함, cursor pagination.
- 이유: 이메일은 초대를 관리하는 OWNER·ADMIN에게 대상 식별에 필요하지만 접근자를 제한한다. 만료 PENDING을 포함하면 관리자가 취소·재발급할 수 있고 DB status 전환이나 batch가 필요 없다. 이 API는 초대 코드 복구/재발급을 하지 않는다.
- 결과: [계약](workspace-invitation-list-api-v1.md)에 DTO·오류·페이지·수용 기준을 기록했다. 구현/격리 PostgreSQL 검증은 다음 작업이며 실제 로컬 DB/ACL 변경은 포함하지 않는다.
## ADR-068 — Project 보관·복구 정책 (2026-11-23)

- 맥락: Project 생성/조회/수정은 있으나 보관 정책은 결정되지 않았다. 아키텍처는 엔티티별 보관 규칙, 서버 인가, Task/Project 경계 및 최소 DB 권한을 요구한다.
- 결정: 사용자 동의로 Project를 복구 가능한 soft archive로 둔다. Project/Task 데이터와 기존 Task 상태·할당·이력은 보존한다. 보관 Project는 상세와 Task/상태 이력을 계속 읽을 수 있고 기본 목록에서는 숨긴다. 모든 현재 Workspace 멤버는 `includeArchived=true`로 보관 항목을 포함해 볼 수 있다.
- 결정: 현재 Workspace OWNER·ADMIN만 보관/복구한다. Project의 기존 version을 재사용해 stale 요청은 409로 거부한다. 최신 version의 동일 상태 요청은 no-op이며 version을 증가시키지 않는다. Project 이름/설명 수정과 Task 생성·수정·상태 변경은 archived Project에서 409로 차단하고 복구 뒤 다시 허용한다. Task를 cascade 보관하거나 status Activity를 만들지 않는다.
- 모델/권한 영향: V10 `projects.archived_at TIMESTAMPTZ NULL`; 기존 `projects` table-level SELECT가 읽기를 포함한다. 별도 `PROJECT_ARCHIVE` 권한은 기존 `PROJECT_UPDATE`에 `archived_at` column UPDATE만 더하며 table-wide UPDATE/DELETE/DDL/sequence/history는 제외한다. API는 `flowdesk.dev.project-archive-enabled=true`로 별도 켠다.
- 대안: hard delete, Task 상태/행 cascade 변경, 숨김과 함께 읽기도 차단, OWNER 단독, Project/Task version 분리, stale 상태 요청의 자동 성공.
- 이유: 되돌릴 수 있고 연결된 Task·이력 무결성을 유지한다. 일반 멤버도 기록을 열람하면서 잘못된 Task 변경은 막는다. 기존 Project version/409 패턴과 column ACL 원칙을 재사용한다.
- 상세 계약: [Project 보관·복구 API v1](project-archive-api-v1.md). 2026-11-23 V10/API/권한 runner와 격리 PostgreSQL 검증을 구현했다. 실제 로컬 적용은 별도 승인 범위다.

## ADR-069 — Task 보관·복구 정책 (2026-11-23)

- 맥락: MVP는 Task 보관을 요구하지만 현재 Task API에는 삭제/보관 규칙이 없다. Project soft archive가 구현됐으며 Task 상태 이력과 version을 보존해야 한다.
- 결정: Task는 삭제 대신 복구 가능한 soft archive로 둔다. 보관 중 Task 상세/status history는 읽기 가능하고 기본 목록에서 숨긴다. 모든 현재 Workspace 멤버는 `includeArchived=true`로 포함 조회할 수 있다.
- 결정: 현재 Workspace OWNER·ADMIN만 보관/복구한다. Task 자신의 기존 version을 재사용해 stale 요청은 409, 현재 version의 동일 상태 요청은 no-op이다. archived Task의 일반 수정/상태 변경은 409 `TASK_ARCHIVED`; 보관 Project 아래 Task 보관/복구는 409 `PROJECT_ARCHIVED`다.
- 결정: 상태·담당자·마감일·Task history는 유지한다. 보관/복구는 status Activity 또는 별도 archive audit history를 만들지 않는다.
- 모델/권한 영향: V11 `tasks.archived_at TIMESTAMPTZ NULL`; 제한된 Task SELECT 정책에 새 column SELECT를 포함하고, Task archive capability는 기존 앱 Task UPDATE를 유지한 채 `archived_at` column UPDATE만 추가한다. table-wide UPDATE/DELETE/DDL 권한은 제외한다. API는 `flowdesk.dev.task-archive-enabled=true`로 별도 활성화한다.
- 대안: hard delete, cascaded Task/Activity 변경, MEMBER 허용, 보관 시 상세/이력 접근 차단, 별도 version/history, 상태 Activity 재사용.
- 이유: 잘못된 삭제를 되돌릴 수 있고 기존 Task/할당/날짜/상태 기록을 보존한다. 상태 Activity는 상태 변화를 위한 기록으로 유지하며 Project 보관·Task version/최소 column ACL 패턴과 일관되게 만든다.
- 결과: [Task 보관·복구 계약 v1](task-archive-api-v1.md)에 HTTP·데이터·권한·트랜잭션·최소 권한·격리 기준을 기록했다. V11/API/권한 구현과 격리 검증 결과는 [구현 기록](task-archive-implementation.md)에 남겼다. 실제 로컬 적용은 별도다.
