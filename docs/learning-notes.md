# 학습 노트

기능별로 아래 템플릿을 사용한다. 단순 API 사용법이 아니라 처리 흐름과 원리를 기록한다.

## 템플릿 — [기능/기술]
- **왜 필요한가:**
- **입력/요청 시작점:**
- **객체/레이어 흐름:**
- **핵심 내부 원리:**
- **대안과 선택 이유:**
- **오류/장애/보안 시나리오:**
- **실제로 검증한 방법과 결과:**
- **아직 모르는 점:**

## Phase 0 — 개발 도구와 실행 환경 (2026-11-02)
- **왜 필요한가:** 동일한 소스도 JDK·Node·빌드 도구 버전이 다르면 실행이나 빌드에 실패할 수 있다.
- **입력/요청 시작점:** 셸에서 `java`, `node`, `npm`, `docker` 실행.
- **객체/레이어 흐름:** 셸의 PATH 탐색 → Java 실행 파일 또는 Volta shim → 선택된 런타임. Docker CLI → 로컬 Docker Desktop 엔진. 아직 Vue/HTTP/Controller/DB 흐름은 없다.
- **핵심 내부 원리:** (1) OpenJDK는 Java의 오픈소스 구현이며 Temurin은 이를 빌드·테스트한 배포판이다. (2) JAVA_HOME은 Java 위치를 지정하지만 `java` 명령 자체는 PATH 순서로 해석되며 기존 프로세스는 옛 환경을 유지할 수 있다. (3) Docker CLI 설치와 엔진 실행은 별개여서 CLI 버전 출력만으로 컨테이너 실행 가능 여부를 판단할 수 없다.
- **대안과 선택 이유:** 다른 OpenJDK 배포판 대신 Temurin 공식 패키지를 사용했다. Node MSI 대신 기존 Volta를 재사용했다. Gradle 전역 설치 대신 향후 프로젝트 Wrapper를 사용한다.
- **오류/장애/보안 시나리오:** 기존 터미널에서 Java 17이 계속 선택됐다. 환경 갱신 후 21이 선택됐다. Docker 엔진 미실행으로 파이프 연결에 실패했지만 Docker Desktop 시작 후 성공했다. 샌드박스 접근 거부는 설치 실패와 구분한다. 전역 실행 버전 변경은 다른 프로젝트에도 영향을 줄 수 있다.
- **실제로 검증한 방법과 결과:** `java -version`, `javac -version`은 21.0.13, `node --version`은 24.22.2, `npm --version`은 11.19.1. `docker info --format 'Server={{.ServerVersion}} OSType={{.OSType}}'`는 `Server=27.4.0 OSType=linux`. 상세 명령은 `local-setup.md` 참고.
- **아직 모르는 점:** 실제 Boot/Gradle 빌드, Vue/Vite 빌드, PostgreSQL 연결은 프로젝트 생성 후 검증해야 한다.

## Phase 0 — 첫 HTTP API (2026-11-02)
- **왜 필요한가:** DB나 인증 장애를 섞지 않고 Java 빌드부터 HTTP 응답까지의 기준선을 확보한다.
- **입력/요청 시작점:** 브라우저 또는 TestRestTemplate의 `GET /api/health`.
- **객체/레이어 흐름:** HTTP → 내장 Tomcat → DispatcherServlet → HealthController.health() → HealthResponse → Jackson JSON 직렬화 → HTTP 200. 업무 규칙과 DB 접근이 없어 Service·Transaction·Repository는 없다.
- **핵심 내부 원리:** (1) `@SpringBootApplication`이 자동 설정과 컴포넌트 스캔을 시작하고 Controller를 등록한다. (2) `@GetMapping`이 경로와 HTTP 메서드를 연결하며 `@RestController`의 반환 객체는 메시지 변환기를 통해 JSON으로 직렬화된다. (3) `@SpringBootTest(RANDOM_PORT)`는 실제 임의 포트에 서버를 띄워 HTTP 왕복을 검사한다. 메서드만 직접 호출하는 단위 테스트와 검증 범위가 다르다.
- **대안과 선택 이유:** Initializr로 생성하는 것이 일반적인 출발점이다. 이번에는 반환된 버전 목록에 맞춰 Boot 4로 바꾸지 않고 기존 3.x 방향을 유지해 직접 구성했다. Controller는 요청 매핑·DTO 학습용이며 Actuator는 관리용 상태 점검에 적합하다.
- **오류/장애/보안 시나리오:** POST는 405로 거부한다. 포트 8080을 다른 앱이 쓰면 기동에 실패하므로 테스트는 임의 포트를 쓴다. 현재 응답은 고정값이라 DB 실패를 감지하지 않는다. 로컬 바인딩만 사용하며 인증·CORS 정책은 업무 API 단계에서 결정한다.
- **실제로 검증한 방법과 결과:** `gradlew.bat build --no-daemon --console=plain` 성공. HTTP 통합 테스트 2개, 실패·오류·건너뜀 0개. `Invoke-WebRequest http://127.0.0.1:8080/api/health`는 200, application/json, `{"status":"UP"}`. 사용자의 브라우저에서도 같은 응답을 확인했다.
- **아직 모르는 점:** Vue 호출, DB 연결, 인증 및 운영 상태 점검. 테스트 중 Mockito 동적 에이전트/CDS 경고는 나왔으나 테스트 실패는 없었다. 향후 mocking이나 JDK 업그레이드 시 에이전트 설정을 검토한다.

### Controller health와 Actuator health

- 둘 다 HTTP GET으로 호출할 수 있으므로 요청 도구 자체는 같다. Controller 방식은 직접 작성한 메서드를, Actuator는 관리용 엔드포인트를 호출한다.
- `/api/health`의 고정 `UP`은 HTTP 경로가 응답한다는 뜻이다. 프로젝트 전체의 기능이나 DB 정상 동작을 보장하지 않는다.
- Actuator의 기본 경로는 `/actuator/health`이며 등록된 HealthIndicator 결과를 종합한다. DataSource 관련 자동 설정과 상태 검사가 활성화된 경우 DB 연결 상태를 반영할 수 있다. 기본 상태 매핑에서 DOWN과 OUT_OF_SERVICE는 HTTP 503이다.
- 두 방식 모두 상태 코드와 JSON 응답을 자동 테스트할 수 있다. 운영 상태 검사에는 정상 응답 외에 의존 시스템 실패 시 응답도 검증해야 한다.
- 참고: [Spring Boot Actuator endpoints](https://docs.spring.io/spring-boot/3.5/reference/actuator/endpoints.html).

## Phase 0 — Actuator와 JSON 자동 변환 (2026-11-02)
- **왜 필요한가:** Controller의 고정 응답과 Actuator의 상태 종합을 직접 비교하고, 변환 설정을 작성하지 않아도 객체가 JSON이 되는 이유를 이해한다.
- **입력/요청 시작점:** `GET /api/health` 또는 `GET /actuator/health`, `Accept: application/json`.
- **객체/레이어 흐름:** Controller 경로는 HTTP → DispatcherServlet → HealthController → HealthResponse → MappingJackson2HttpMessageConverter → ObjectMapper → JSON. Actuator 경로는 HTTP → DispatcherServlet → Actuator 웹 엔드포인트 → HealthContributor 결과 종합 → JSON. Actuator는 HealthController/HealthResponse를 사용하지 않는다.
- **핵심 내부 원리:** (1) Web starter가 Jackson을 의존성으로 제공하고 Boot·MVC 자동 설정이 ObjectMapper와 JSON 메시지 변환기를 준비한다. (2) RestController의 ResponseBody 의미에 따라 반환 객체를 응답 본문으로 처리하며, 객체 타입·Accept·사용 가능한 변환기·produces 조건으로 응답 미디어 타입을 결정한다. Jackson은 record 구성요소를 지원해 status 이름과 값을 직렬화한다. DTO라는 역할이나 클래스 이름으로 변환기를 고르는 것이 아니다. (3) Actuator는 등록된 검사 결과를 종합하며 HTTP 노출 대상과 세부 상태 표시를 별도로 설정한다.
- **대안과 선택 이유:** 기존 Controller와 Actuator health를 함께 두어 비교한다. 사용자 정의 변환기나 HealthIndicator는 추가하지 않는다. health만 HTTP에 노출하고 세부 상태는 숨긴다.
- **오류/장애/보안 시나리오:** produces 생략은 모든 반환값이 JSON이라는 뜻이 아니다. String 반환은 문자열 변환기로 처리될 수 있으며, 지원할 수 없는 Accept에는 406이 발생할 수 있다. 현재 DB 검사는 없으므로 UP이 DB 정상임을 보장하지 않는다. 기존 서버는 의존성 변경 후 재시작해야 한다.
- **실제로 검증한 방법과 결과:** `gradlew.bat build --no-daemon --console=plain` 성공(34초). HTTP 통합 테스트 4개, 실패·오류·건너뜀 0개. 별도 포트 18081의 패키징 JAR에서 두 health 경로 모두 200/application/json/`{"status":"UP"}` 확인. Actuator 전용 Accept에는 200 및 application/vnd.spring-boot.actuator.v3+json 응답 확인. metrics는 404. 확인용 프로세스만 종료했고 기존 8080 서버는 변경하지 않았다.
- **아직 모르는 점:** DB 장애에 대한 DOWN·503, 사용자 정의 HealthIndicator와 인증된 관리 엔드포인트 검증은 후속 단계다.

### 애너테이션 생략 표기

`@GetMapping("/api/health")`와 `@GetMapping(value = "/api/health")`는 같은 경로 지정이다. Java 애너테이션에서 value 인수 하나만 줄 때 이름을 생략할 수 있다. produces는 별개의 응답 미디어 타입 조건이며, 지정해도 직렬화 코드를 생성하지 않는다.

## Phase 0 — IDE 클래스패스 갱신 조사 (2026-11-02)
- **왜 필요한가:** JAR 실행으로 정상화해도 IDE 실행 의존성이 오래된 상태라면 같은 문제가 재발한다.
- **입력/요청 시작점:** build.gradle 의존성 변경 → VS Code Java 프로젝트 가져오기 → Run Java.
- **객체/레이어 흐름:** Gradle 빌드 모델 → IDE 클래스패스 컨테이너 → Java 실행 인수 파일 → JVM → main → SpringApplication.run → Actuator HTTP 매핑. SpringApplication.run은 build.gradle을 읽어 의존성을 갱신하지 않는다.
- **핵심 내부 원리:** (1) interactive는 빌드 변경마다 갱신 여부를 묻고 automatic은 자동 가져오기를 시작한다. (2) Gradle을 실행하는 JVM과 Java toolchain은 다른 설정이다. IDE 확장에 내장된 Java 25로 Gradle 8.14.3을 실행하면 가져오기가 실패할 수 있다. (3) JAR와 IDE 실행은 다른 클래스패스 출처를 가지므로 양쪽을 각각 검증해야 한다.
- **대안과 선택 이유:** 수동 Reload Projects 대신 작은 프로젝트의 의존성 일관성을 위해 자동 가져오기를 선택한다. Gradle JVM은 머신 경로 하드코딩 대신 JAVA_HOME을 참조한다.
- **오류/장애/보안 시나리오:** 최초 로그에서 Unsupported class file major version 69, Gradle Build Server 시작 실패와 Buildship 폴백, IDE 캐시 저장 오류를 확인했다. 이 과거 오류들이 모두 현재 404의 직접 원인이라고 단정하지 않는다. 직접 증거는 interactive 설정, 기존 실행 클래스패스의 Actuator 누락, 설정 변경 후 모델 갱신 성공이다.
- **실제로 검증한 방법과 결과:** settings.json 구문 및 JAVA_HOME 경로 확인. IDE 언어 서버 로그에서 Updated flowdesk-backend-backend in 2485 ms, Gradle 클래스패스 컨테이너에서 spring-boot-actuator와 spring-boot-actuator-autoconfigure 3.5.16 포함 확인.
- **IDE 실행 최종 검증:** 사용자가 Run Java로 실행한 새 Java 프로세스의 인수 파일 `cp_aqkkq0rcegs518vf9tq0yxrgu.argfile`에서 Actuator·Actuator 자동 설정 3.5.16 JAR와 backend/bin/main 경로를 확인했다. JAR 우회 실행이 아닌 IDE 클래스패스 실행이다. 해당 8080 프로세스에 HTTP 요청을 보내 두 health 경로의 200/application/json/UP과 metrics 404를 확인했다.
- **아직 모르는 점:** 초기 로그의 확장 번들·캐시 저장 오류는 별도 문제로 남지만 이번 동기화와 IDE 실행 검증에는 지장이 없었다. 키보드 자동 실행 시도 자체는 성공으로 기록하지 않는다.
- **참고:** [VS Code Java 설정](https://github.com/redhat-developer/vscode-java), [Gradle Java 호환표](https://docs.gradle.org/current/userguide/compatibility.html).

## Phase 0 — Vue에서 Spring health 호출 (2026-11-02)
- **왜 필요한가:** 백엔드의 JSON 응답이 실제 사용자 화면 상태로 연결되는 전체 경로를 확보한다.
- **입력/요청 시작점:** 상태 카드 마운트 또는 다시 확인 버튼.
- **객체/레이어 흐름:** HealthStatusCard → getHealth/fetch → Vite /api 프록시 → Spring DispatcherServlet → HealthController → HealthResponse → Jackson JSON → 응답 형식 확인 → ref 갱신 → 화면 렌더링. DB·Service·Transaction은 아직 없다.
- **핵심 내부 원리:** (1) Vue ref 변경은 렌더링을 예약하며 DOM은 다음 갱신 주기에 반영된다. 로딩 검증에서도 nextTick 이후 화면을 확인해야 한다. (2) TypeScript interface는 실행 시 사라지므로 response.json()을 unknown으로 받고 실제 status 구조를 검사한다. fetch는 HTTP 503 자체로 reject하지 않아 response.ok를 검사해야 한다. (3) 브라우저의 요청 출처는 5173이며, Vite가 8080으로 전달한다. 이 개발 프록시 덕분에 백엔드 CORS 설정을 추가하지 않고 연결했다.
- **대안과 선택 이유:** 단일 조회에 Axios·Pinia·Router를 추가하지 않는다. Vitest/jsdom에서 Vue createApp으로 화면을 마운트하며, 모의 fetch 테스트와 실제 브라우저 검증의 범위를 구분한다.
- **오류/장애/보안 시나리오:** 네트워크 오류·시간 제한·HTTP 오류·잘못된 응답 구조를 처리하고 실패 시 이전 UP을 지운다. 두 서버는 루프백에만 바인딩한다. Vite 개발 프록시는 운영 배포 설정을 대신하지 않는다. Windows 방화벽 허용 창은 조직 정책으로 비활성화되어 있었지만 이번 로컬 연결은 실제 검증에 성공했고 정책을 변경하지 않았다.
- **실제로 검증한 방법과 결과:** npm run build(타입 검사 포함) 성공, npm test 5개 통과. 프론트 HTML 및 /api/health 프록시 HTTP 200. 설치된 Chrome 헤드리스 모드에서 실제 API 응답의 UP과 서버 연결 정상 문구를 DOM 및 스크린샷으로 확인했다. 마지막 npm install 감사는 취약점 0건.
- **실패 후 수정:** TypeScript 6.0.0은 배포되지 않아 6.0.3으로 고정했다. 최초 테스트는 Vue DOM 반영 전 로딩 상태를 읽어 실패했고 nextTick을 반영했다. 초기 테스트 도구의 감사 결과 7건은 Vitest 수정 버전과 불필요한 테스트 의존성 제거로 해결했다. Chrome의 첫 실행은 PowerShell에서 GUI 프로세스 종료 코드를 바로 얻지 못해 검증 스크립트가 실패했고 Start-Process/WaitForExit로 DOM 검증을 완료했다. 브라우저 프로필을 Vite가 감시하면서 파일 잠금 오류가 발생해 .cache를 감시 대상에서도 제외했다.
- **아직 모르는 점:** PostgreSQL 연결·Flyway·JPA, 운영 프록시와 인증 정책은 후속 단계다. 이번 UP은 DB 상태를 보장하지 않는다.

## Phase 0 — Compose PostgreSQL 기동과 SQL 검증 (2026-11-02)
- **왜 필요한가:** 호스트 설치 대신 프로젝트 설정으로 동일한 DB 실행 환경을 재현하고, 앱 연동 전에 DB 장애와 앱 설정 오류를 분리한다.
- **입력/요청 시작점:** docker compose up, pg_isready, psql SQL 요청.
- **객체/레이어 흐름:** PowerShell 스크립트가 로컬 .env 생성 → Compose가 변수 치환 후 컨테이너 환경에 전달 → PostgreSQL 최초 초기화 → healthcheck → psql TCP 비밀번호 인증 → SQL 처리 → 결과. 아직 Vue/HTTP/Service/Repository에서 DB를 호출하지 않는다.
- **핵심 내부 원리:** (1) Compose의 .env는 변수 치환 입력이고 environment가 실제 컨테이너 프로세스에 전달한다. 시스템 JAVA_HOME이나 systemd 설정과 별개다. (2) 컨테이너와 데이터 볼륨의 생명주기는 다르며 POSTGRES_* 초기화 변수는 빈 데이터 디렉터리에서만 적용된다. (3) pg_isready는 준비 상태를 검사하고 인증 성공을 보장하지 않으므로 비밀번호 기반 TCP SQL과 잘못된 비밀번호 거부를 별도로 확인한다.
- **대안과 선택 이유:** 로컬 PostgreSQL 직접 설치·기존 DB 공유 대신 Compose 프로젝트와 명명 볼륨을 분리한다. 17.11 정확한 버전과 localhost 포트 매핑을 사용한다.
- **오류/장애/보안 시나리오:** 포트 충돌, Docker 엔진 미실행, 이미지 다운로드 실패, 기존 데이터와 .env 비밀번호 불일치. 비밀번호를 명령 인수에 직접 적거나 전체 Compose 해석 결과를 공유하지 않는다. 컨테이너의 Unix 소켓 인증과 TCP 인증은 다를 수 있어 이번 검증은 -h postgres 경로를 사용했다. 초기 관리자 계정과 향후 애플리케이션 계정은 구분한다.
- **실제로 검증한 방법과 결과:** Compose config --quiet 성공, up --wait 후 healthy. PostgreSQL 17.11, current_database=flowdesk, current_user=postgres, connection_ok=1. 틀린 비밀번호는 인증 실패. Windows 호스트의 127.0.0.1:5432 TCP 연결과 Docker 포트/볼륨 매핑 확인. 환경 생성 스크립트 재실행 시 기존 값 유지. 상세 명령과 digest는 infra/README.md 참고.
- **아직 모르는 점:** Windows DB 클라이언트·Spring JDBC를 통한 SQL 실행과 앱 계정 권한, Flyway/JPA 연결, 컨테이너 재생성 시 데이터 유지 실험은 후속이다. 상태 화면의 UP은 여전히 DB 연결을 검사하지 않는다.

## Phase 0 — 최소 권한 DataSource와 Actuator DB 상태 (2026-11-02)
- **왜 필요한가:** 관리자 계정과 앱 계정을 분리하고, HTTP 응답 성공과 DB 연결 성공의 차이를 실제로 검증한다.
- **입력/요청 시작점:** /actuator/health HTTP GET 및 JDBC 연결 요청.
- **객체/레이어 흐름:** HTTP → Actuator → DataSourceHealthIndicator → HikariCP DataSource → PostgreSQL JDBC → flowdesk_app 인증 및 DB 검사 → UP/DOWN 종합 → 200/503 JSON. 기존 /api/health Controller는 이 경로를 거치지 않는다. 업무 Service·Transaction·Repository는 아직 없다.
- **핵심 내부 원리:** (1) 권한은 역할에 직접 부여한 값뿐 아니라 PUBLIC·멤버십·소유권에서 합쳐진다. 이 DB에서 PUBLIC 기본 권한을 제한해야 TEMP·스키마 생성 등이 남지 않는다. SELECT 1에는 테이블 SELECT 권한이 필요 없다. (2) JDBC 드라이버는 PostgreSQL 프로토콜 연결을 담당하고 DataSource는 연결 제공 인터페이스이며 HikariCP는 연결 풀 구현이다. Boot가 설정과 라이브러리로 이를 구성하고 Actuator가 db contributor를 자동 등록한다. (3) Spring은 .env라는 이름을 자동 인식하지 않는다. config import의 [.properties] 힌트로 명시적으로 읽게 했으며 실제 비밀번호 파일은 JAR에 포함하지 않는다.
- **대안과 선택 이유:** 앱 DDL·관리자 공유 대신 CONNECT만 선택한다는 사용자 확인을 받았다. JPA를 먼저 넣지 않고 JDBC를 기준선으로 사용한다. 로컬 DB를 중지하는 대신 Testcontainers의 별도 PostgreSQL을 pause/unpause해 장애를 재현한다.
- **오류/장애/보안 시나리오:** 연결 풀 대기·드라이버 연결/소켓 타임아웃을 명시한다. DB 장애 시 Actuator 503/DOWN이지만 Controller /api/health는 200/UP이다. 상세 상태는 노출하지 않는다. 앱 비밀번호는 난수로 별도 생성하고 SQL 파일이나 프로세스 인수에 실제 값을 넣지 않는다. DDL·TEMP 거부를 실제 SQLSTATE 42501로 확인한다. 다른 DB의 PUBLIC 접속 제한은 이번에 바꾸지 않았다.
- **실제로 검증한 방법과 결과:** gradlew.bat build --no-daemon --console=plain 성공(37초), 기존 HTTP 테스트 4개와 DB 통합 테스트 3개 모두 통과. 후자는 실제 app-role.sql 정책을 적용한다. 별도 포트 JAR 및 사용자가 재시작한 IDE 8080에서 Actuator 200/UP을 확인했고 pg_stat_activity에서 flowdesk_app 세션을 확인했다. IDE 클래스패스의 JDBC·드라이버·Hikari 포함, JAR 내 .env 0개, 프로비저닝 재실행 시 자격증명 파일 유지도 확인했다.
- **아직 모르는 점:** 애플리케이션의 업무 DML·트랜잭션, Flyway 계정/스키마·마이그레이션, JPA 매핑은 후속 단계다. Vue 화면은 계속 /api/health만 호출하므로 DB 상태 화면으로 바뀐 것은 아니다.

## Phase 1 준비 — 첫 업무 모델과 V1 경계 (2026-11-02)
- **왜 필요한가:** 기능을 구현하기 전에 무엇을 저장하고 어느 계층에서 무결성을 보장하는지 정해야 API·JPA·마이그레이션이 같은 모델을 따른다.
- **입력/요청 시작점:** 개발 seed 또는 향후 워크스페이스 생성 요청. 이번에는 문서 설계만 수행했다.
- **객체/레이어 흐름:** 향후 Vue/HTTP DTO → 행위자 확인/Controller → WorkspaceService 트랜잭션 → 사용자 확인 및 workspace·OWNER membership 저장 → DB 제약 → 응답 DTO. 아직 해당 업무 API나 Service는 없다.
- **핵심 내부 원리:** (1) FK·UNIQUE·CHECK는 서로 다른 범위의 무결성을 보장한다. OWNER 부분 UNIQUE는 최대 1명만 보장하므로 0명을 막는 것은 Service 트랜잭션과 일관된 잠금 규칙이 필요하다. (2) Flyway 버전 파일은 적용 이력과 checksum으로 추적한다. 이미 적용한 V1을 수정해 맞추지 않고 새 버전으로 변경한다. (3) 개발 seed·제품 계정 인증·DB 로그인 역할은 서로 다른 개념이다. 초기 사용자는 개발 데이터이며 로그인 권한이 아니고, DB 앱 계정은 업무 OWNER 역할과도 다르다.
- **대안과 선택 이유:** 세 기반 테이블만 먼저 다룬다. membership은 단일 ID와 두 FK의 복합 UNIQUE를 사용해 JPA 복합 PK 부담을 줄인다. 소유자를 membership과 workspace에 중복 저장하지 않는다. 테이블 생성과 개발 데이터 주입은 분리한다.
- **오류/장애/보안 시나리오:** 비정규화·중복 이메일, 중복 가입, 잘못된 FK/역할, 동시 OWNER 추가를 DB에서 검증할 계획이다. OWNER 누락·소유권 이전·요청자 권한은 Service 구현 때 검증한다. 앱에는 V1의 DDL이나 Flyway 이력 수정 권한을 주지 않는다.
- **실제로 검증한 방법과 결과:** 읽기 전용 SQL로 로컬 flowdesk 스키마·업무 테이블·flowdesk_migrator가 아직 없음을 확인했다. 요구사항·설계 문서와 인덱스/Flyway 공식 문서를 대조했다. SQL 마이그레이션·계정 생성·앱 코드 변경은 없어 Gradle/Vue 빌드·테스트는 실행하지 않았다. 문서의 수용 시나리오는 실행 결과가 아닌 다음 구현의 검증 계획이다.
- **아직 모르는 점:** 초대·삭제·역할 매트릭스와 개발용 행위자 식별, Flyway 실행/자격증명 주입 방식은 후속에서 확정한다. Project·Task 모델도 아직 확정하지 않았다.

## Phase 1 — V1 SQL과 격리 PostgreSQL 검증 (2026-11-02)
- **왜 필요한가:** 모델 문서의 무결성 조건이 실제 DB에서도 강제되고, 마이그레이션이 재실행·변경 감지를 올바르게 처리하는지 검증한다.
- **입력/요청 시작점:** Gradle test → V1MigrationTest → Flyway Java API. HTTP나 업무 Service 요청은 아직 없다.
- **객체/레이어 흐름:** Testcontainers PostgreSQL → 관리자 fixture의 역할/DB/schema 준비 → 비관리자 migrator 연결 → Flyway migrate → V1 DDL → schema history → JDBC로 정상/위반 SQL 검증. 각 케이스는 별도 DB를 사용한다.
- **핵심 내부 원리:** (1) Flyway는 이력으로 적용 버전을 추적한다. 같은 V1을 재실행하면 0건이고 내용이 바뀌면 validate가 checksum 불일치를 감지한다. 이번 테스트는 원본 대신 임시 파일의 컬럼 길이를 바꿨다. (2) SQLSTATE 23502/23503/23505/23514로 NOT NULL/FK/UNIQUE/CHECK 위반을 구분하고, 42501로 권한 거부를 확인한다. (3) 테스트 의존성과 런타임 의존성은 다르다. SQL을 main resources에 넣어도 실행 앱에 Flyway가 없으면 Boot 자동 마이그레이션은 시작되지 않는다.
- **대안과 선택 이유:** 로컬 DB 적용이나 관리자 단독 검증 대신 격리 DB·스키마 소유 migrator를 사용한다. 현재 Spring 통합 테스트의 Flyway 자동 실행은 끄고 새 테스트가 명시적으로 실행한다.
- **오류/장애/보안 시나리오:** 앱은 테이블 읽기/쓰기·DDL·이력 변경·Flyway 실행이 거부된다. migrator도 DB 전체 CREATE·관리자 권한은 없다. 임시 SQL의 checksum 오류를 repair로 덮지 않는다. OWNER 0명 방지와 업무 트랜잭션·인가를 이 테스트로 보장했다고 주장하지 않는다.
- **실제로 검증한 방법과 결과:** 최초 전체 build 37초 성공 후 PK 중복 검증을 보강했고, 최종 gradlew.bat build --no-daemon --console=plain은 29초에 성공했다. V1 테스트 24개 + 기존 7개 = 31개 통과, 실패·오류·건너뜀 0. dependencyInsight로 Flyway 11.7.2를 확인했다. JAR의 V1 SQL 포함, Flyway 런타임 라이브러리 0개·실제 .env 0개도 확인했다. 테스트 후 로컬 업무 schema/table/migrator는 각각 0개다.
- **아직 모르는 점:** 로컬 적용 경로·자격증명 주입과 migrator 프로비저닝, 개발 seed, Service의 OWNER 불변식, JPA/API는 후속이다. 실패한 DDL 마이그레이션의 전체 롤백 실험은 이번에 별도로 추가하지 않았다.

## Phase 1 준비 — 로컬 migration 실행/비밀정보 전달 절차 (2026-11-02)
- **왜 필요한가:** 런타임 앱 계정이 DDL 자격증명을 갖지 않아도 스키마 변경을 재현 가능하게 실행하기 위해 실행 책임과 전달 경계를 정한다.
- **입력/요청 시작점:** 향후 전용 PowerShell 실행기에 명시하는 Info/Validate/Migrate 동작. 이번에는 절차만 확정했다.
- **객체/레이어 흐름:** 전용 .env.migrator → 실행기 메모리 → 전용 자식 프로세스 트리의 환경 → Gradle JavaExec → Spring 없는 Java main → Flyway → flowdesk 스키마. 앱의 HTTP·Controller·Service 흐름과는 별개다.
- **핵심 내부 원리:** (1) 환경변수는 영구 시스템 설정만 있는 것이 아니다. 부모 셸을 바꾸지 않고 새 프로세스 환경에 값을 전달할 수 있으며 후손은 이를 물려받을 수 있다. (2) DB 트랜잭션과 파일 기록은 원자적이지 않다. 파일을 먼저 기록하고 DB 실패 시 유지해 같은 값으로 재시도하되 기존 역할의 비밀번호를 자동 재설정하지 않는다. (3) Info의 Pending과 checksum 오류는 다르다. 최초에는 Info → Migrate의 적용 전 검증 → 사후 Validate를 사용하며 독립 Validate 성공을 무조건 최초 적용의 선행 조건으로 삼지 않는다.
- **대안과 선택 이유:** 앱 부팅 자동 마이그레이션 대신 사용자 선택대로 전용 작업으로 분리한다. 기존 Wrapper/Flyway API를 재사용하며 비밀번호를 CLI 인수나 앱 설정에 넣지 않는다.
- **오류/장애/보안 시나리오:** 자격증명 누락·사용자/대상/소유권 불일치 시 중단, 관리자 폴백·비밀번호 회전·clean/repair/baseline 자동 실행 금지. 전용 파일의 Git 제외는 암호화가 아니며 같은 사용자 권한의 다른 프로세스를 막는 샌드박스도 아니다. --no-daemon에서도 일회성 프로세스는 생성될 수 있다.
- **실제로 검증한 방법과 결과:** 현재 Gradle의 Flyway 테스트 범위, Spring config import의 앱 파일 경로, .gitignore의 .env.* 규칙과 migrator 파일 미존재를 확인했다. 문서의 상대 링크와 신규 파일 미존재를 점검했다. 실행기·Gradle 작업·파일·역할을 만들지 않았고 마이그레이션/빌드/테스트는 미실행이다.
- **아직 모르는 점:** 전용 실행기와 프로비저닝의 실제 구현·격리 테스트·로컬 적용, 운영 비밀정보 전달은 후속이다.

## Phase 1 준비 — 전용 migration 실행기 구현 (2026-11-03)
- **요구사항과 흐름:** 명시적 PowerShell Action → 파일/Compose 대상 검증 → 자식 프로세스 환경 → Gradle migration 클래스패스 → Java main → JDBC 권한 검사 → Flyway → PostgreSQL 이력/종료 코드. HTTP·Controller·Service를 거치지 않는 관리 작업이다.
- **핵심 개념 1 — 클래스패스와 환경은 별도 경계:** source set은 실행 코드·의존성을 앱에서 분리하지만 환경변수 상속까지 막지는 않는다. ProcessStartInfo.Environment에서 기존 앱/관리자 설정을 지우고 필요한 키만 추가한다. 일반 앱 JAR에는 전용 실행기와 Flyway runtime이 없다.
- **핵심 개념 2 — 파일과 DB의 실패 단위:** 파일 생성은 CreateNew로 덮어쓰기를 막는다. DB 역할·스키마 변경은 트랜잭션으로 묶는다. DB 실패 시 파일까지 자동 삭제하면 재시도할 비밀번호를 잃으므로 유지한다. 파일이 없는데 역할이 이미 있으면 자동 회전하지 않는다. advisory transaction lock은 같은 DB의 bootstrap을 직렬화하며 파일+DB를 하나의 트랜잭션으로 만들지는 않는다.
- **핵심 개념 3 — 고정 버전과 실제 검증:** 최신 문서의 옵션을 고정된 라이브러리에 그대로 가정하면 안 된다. Flyway 11.7.2에서 지원하지 않는 로깅 이름을 실제 Info 실행 중 발견해 제거했다. 전용 클래스패스의 기본 JUL 로깅은 끄고 비밀 없는 결과/오류 분류만 직접 출력한다. Info는 상태 조회이고 Validate/Migrate가 checksum을 검사한다.
- **대안과 장애:** CLI/플러그인 대신 기존 Java API를 재사용했다. PUBLIC 기본 권한·예상 밖 소유권/역할·history 없는 기존 객체·잘못된 인증은 자동 교정하지 않는다. 이 검사는 현재 앱 CONNECT-only 단계에 맞춰져 있으므로 앱 DML 도입 시 명시적으로 조정한다.
- **실제 검증:** 전체 build와 migrationClasses를 --rerun-tasks로 실행해 31개 JUnit 테스트가 통과했다. 앱 JAR에서 Flyway runtime/runner/.env 제외도 확인했다. 실제 래퍼를 사용하는 격리 Compose 검증의 상세 결과와 명령은 local-setup.md에 기록한다.
- **남은 작업:** 실제 로컬 DB 적용, 개발 seed, JPA/API, 운영 비밀정보 관리. 프론트 변경은 없으며 Vue 테스트는 반복하지 않는다.

## Phase 1 준비 — 실제 로컬 V1 적용 (2026-11-03)
- **요구사항과 흐름:** 로컬 적용 요청 → PowerShell 대상 확인 → 관리자 JDBC로 역할/스키마 준비 → 전용 migrator 환경 → Gradle JavaExec/Flyway → PostgreSQL V1/history → 종료 코드와 SQL 결과. 별도 임시 앱에서는 HTTP → Actuator → HikariCP/JDBC → 같은 DB의 flowdesk_app 접속을 확인했다.
- **핵심 개념 1 — 접속·스키마·테이블 권한:** DB CONNECT와 SELECT 1 성공은 업무 테이블 접근 권한을 의미하지 않는다. 앱은 flowdesk 스키마 USAGE/CREATE가 없고 테이블 SELECT/INSERT/UPDATE/DELETE/TRUNCATE도 없다. 문자열 테이블명 기반 권한 조회도 스키마 이름 해석에서 거부될 수 있어 카탈로그의 객체 OID로 권한을 조회했다.
- **핵심 개념 2 — 이력과 health의 증거 범위:** Actuator UP은 앱의 DB 연결 검사 성공이다. V1 적용은 별도로 성공 이력·installed_by·checksum Validate·소유권·재실행 0건으로 확인한다. V1 적용 후 원본 SQL을 수정하면 다음 검증에서 checksum이 어긋나므로 새 변경은 V2로 추가한다.
- **실제 결과:** flowdesk-local의 기존 볼륨으로 V1 1건 적용, Validate 성공, Info SUCCESS, 재실행 0건. 테이블 4개(업무 3+history)는 migrator 소유, 업무 행 0건. 앱의 TCP 인증/SELECT 1 성공 및 SELECT/DDL 42501 거부, curl로 Actuator와 /api/health 200/UP, pg_stat_activity에서 flowdesk_app 세션을 확인했다. 마이그레이션 전후 3개 자격증명 파일/V1 해시와 부모 환경도 같았다.
- **미실행:** 코드 변경이 없어 전체 빌드/JUnit/Vue 테스트는 반복하지 않았다. 실제 DB에 장애나 checksum 불일치를 만드는 실험도 하지 않았다. 해당 실패 검증은 기존 격리 DB 테스트 결과를 유지한다. 개발 seed·JPA/API는 후속이다.

## Phase 1 준비 — 개발 seed 데이터·실행 범위 설계 (2026-11-03)
- **요구사항과 흐름:** 개발 예제 준비 → 향후 수동 seed PowerShell → 전용 자식 환경/Gradle JavaExec → Spring 없는 main → V1 확인/JDBC 트랜잭션 → 사용자·workspace·membership → 행 수/관계 검증. 이번에는 문서만 작성했다.
- **핵심 개념 1 — 데이터와 인증/DB 권한:** Alice 등의 users 행, membership의 OWNER, PostgreSQL의 migrator는 서로 다른 층위다. seed가 로그인이나 API 권한 검증을 제공하지 않는다. 앱은 CONNECT-only를 유지한다.
- **핵심 개념 2 — 재실행과 일치 정의:** 멱등 실행은 무조건 upsert를 뜻하지 않는다. 빈 테이블에만 삽입하고 정확한 예제면 0건, 다르면 중단해 수정 데이터를 보존한다. workspace 이름은 유일키가 아니므로 일부 이름만으로 기존 데이터를 seed 소유로 판정하지 않는다. ID/시간 대신 전체 email·표시명·workspace·role 관계를 비교한다.
- **핵심 개념 3 — 상태 확인과 삽입 사이의 경합:** 한 트랜잭션이어도 빈 테이블을 읽은 뒤 다른 쓰기가 끼어들 수 있다. Apply는 상태 판정 전에 세 테이블의 SHARE ROW EXCLUSIVE 잠금을 얻도록 설계해 쓰기·다른 seed와의 경합을 제한한다. 일반 조회는 허용하고 잠금/SQL 시간 초과로 장시간 대기를 제한한다. 실제 동시 실행 검증은 다음 구현에서 수행한다.
- **선택과 대안:** 사용자 확인으로 기존 migrator 재사용/수동 작업/불일치 중단을 확정했다. DML 전용 seeder는 권한이 좁지만 계정 관리가 추가되고, 자동 병합·초기화는 기존 개발 데이터 변경 위험이 있어 선택하지 않았다.
- **검증과 한계:** 기존 V1/계정/클래스패스를 읽고 PowerShell로 문서 표를 파싱해 5명·2개 공간·5개 관계와 OWNER/이메일/역할 규칙을 확인했다. 문서의 로컬 링크 19개도 검사했다. 실제 INSERT·재실행·롤백·잠금 테스트는 아직 없으며 DB 조회/변경과 Gradle/JUnit/Vue 빌드도 실행하지 않았다. 상세 명세는 dev-seed.md다.
- **다음 작업:** seed 실행기를 구현하고 격리 PostgreSQL에서 최초/반복/불일치/실패·동시 실행을 검증한다. 실제 로컬 삽입은 별도 요청 범위에서 진행한다.

## Phase 1 준비 — seed 실행기 구현과 격리 검증 (2026-11-03)
- **흐름:** 수동 PowerShell Action → 대상/파일 검증 → 정리한 자식 환경 → runSeed JavaExec → V1/Flyway validate → JDBC 트랜잭션 → 잠금/전체 관계 판정 → 삽입 또는 0건/실패 → 종료 코드. HTTP·Controller·Spring Service는 거치지 않는다.
- **원리 1 — 전체 성공과 중간 실패:** 사용자 5명/공간 2개가 먼저 저장됐더라도 membership INSERT가 실패하면 같은 트랜잭션의 행을 모두 롤백한다. 테스트에서는 VIEWER를 거부하는 임시 CHECK를 격리 DB에만 추가해 저장 중 실패를 만들었다. 시퀀스의 연속성은 보장하지 않으며 실패 후에도 정상 Apply로 재시도할 수 있었다.
- **원리 2 — 잠금과 관찰 시점:** Apply는 SHARE ROW EXCLUSIVE로 같은 seed/업무 쓰기와 경합하고, 상태를 잠금 뒤에 확인한다. 동시 프로세스 두 개의 12행/0건 처리와 다른 쓰기 잠금이 3초 제한을 넘었을 때 안전한 실패를 실제 DB로 확인했다. Verify는 REPEATABLE READ 읽기 전용 스냅샷이므로 여러 SELECT에서 일관된 관계를 확인한다. 이 검증을 모든 미래 API의 동시성 보장으로 확대하지 않는다.
- **원리 3 — entry point와 테스트 환경:** 모의 Repository 대신 실제 Java main을 별도 JVM에서 실행해 환경변수·whitelist·오류 코드까지 검증한다. Testcontainers JDBC URL의 호스트/쿼리 형태가 좁은 로컬 대상 정책과 달라 첫 실행은 입력 단계에서 실패했다. 테스트 URL을 승인 형식으로 맞춘 뒤 정상·오류 경로를 재검증했다. 비밀번호는 명령행/예외 원문에 넣지 않고 출력에 실제 값이 없는지도 확인한다.
- **실행 결과:** seed 전용 JUnit 31개 통과, 전체 build/JUnit 62개 통과. 앱 JAR에 seed/migration runner·Flyway runtime·.env가 없음을 확인했다. 실제 PowerShell/Gradle 전체 경로 결과는 local-setup.md에 기록한다.
- **범위:** 기존 migrator와 앱 CONNECT-only 권한을 유지한다. V1과 실제 로컬 seed 데이터는 변경하지 않았다. 앱용 JPA/API·인증/인가·프론트 화면은 추가하지 않아 Vue 검증은 반복하지 않는다. 다음은 사용자 요청에 따른 실제 로컬 seed 적용·Verify 확인이다.

## Phase 1 준비 — 실제 로컬 seed Apply·Verify (2026-11-03)
- **흐름:** 사용자 적용 요청 → seed PowerShell의 대상/파일 검사 → migrator 전용 자식 환경 → Gradle JavaExec → V1 validate → JDBC 잠금/트랜잭션 → 예제 삽입 → Verify의 읽기 전용 스냅샷 → SQL 결과 확인. HTTP·Controller·Service 요청은 수행하지 않았다.
- **핵심 개념:** (1) 멱등성은 재실행 0건뿐 아니라 ID·timestamps·전체 값 보존으로 확인했다. (2) 개발 데이터가 있어도 앱 계정은 CONNECT-only여서 업무 테이블을 읽지 못한다. 다음 JPA/API 범위에서 필요한 DB 권한과 서버 인가를 각각 정해야 한다. (3) Flyway는 스키마 버전, seed는 예제 데이터 준비다. seed 전후 history 전체가 동일하며 비밀번호나 V1 수정으로 데이터를 준비하지 않았다.
- **실제 검증:** 빈 세 테이블을 확인한 뒤 Apply 12행, Verify 5/2/5 성공, Apply 재실행 0건. A는 Alice OWNER/Bob ADMIN/Carol MEMBER/Dave VIEWER, B는 Eve OWNER만 존재하며 각 OWNER 1명이다. SQL 스냅샷·파일 SHA-256·부모 환경 비교가 통과했다. PostgreSQL은 healthy다.
- **미실행/남은 작업:** 코드 변경이 없어 전체 빌드/JUnit/Vue 검증을 반복하지 않았다. 데이터가 있다는 사실을 로그인·API·JPA 완성으로 해석하지 않는다. 다음 작업은 User/Workspace/Membership의 JPA 매핑과 Repository 범위를 확정하는 것이다.

## Phase 1 준비 — 첫 JPA 조회 범위 설계 (2026-11-03)
- **요구사항과 흐름:** 조회 가능한 첫 영속 모델 → 향후 readOnly 트랜잭션/Repository → EntityManager/Hibernate → 기존 Hikari/JDBC → PostgreSQL. HTTP·Security·DTO·쓰기 Service는 아직 구현하지 않는다. 세 엔티티와 5개 조회 메서드의 범위는 jpa-read-model-v1.md다.
- **핵심 개념 1 — 연관관계와 조회 계획:** Membership의 LAZY ManyToOne은 객체 관계를 표현하고 필요한 목록에서는 workspace EntityGraph로 읽을 범위를 정한다. 양방향 컬렉션·EAGER를 기본으로 두지 않는다. OSIV를 끄고 트랜잭션 안에서 필요한 Entity를 읽은 뒤 후속 Service에서 DTO로 변환한다. 이번에는 그 Service도 아직 없다.
- **핵심 개념 2 — 조회 전용의 두 층:** Repository에 save/delete를 선언하지 않고 readOnly 트랜잭션을 써도 보안 경계가 완성되지는 않는다. 사용자 선택으로 DB에 USAGE+SELECT만 허용하는 목표를 정했다. 이 SELECT는 사용자/팀별 인가가 아니며 로그인/리소스 권한 검증은 서버에서 별도로 구현한다.
- **핵심 개념 3 — 스키마 검증과 권한 전환:** ddl-auto=validate는 Flyway/V1을 대신해 제약·이력을 전부 검사하지 않는다. 또 기존 migration/seed가 앱 schema grant를 전부 거부하므로 읽기 권한만 추가하면 도구가 중단된다. 승인된 두 권한 상태만 허용하도록 함께 설계하고 과다/부분 권한은 자동 교정하지 않는다.
- **선택/대안:** 조회만 먼저 구현하며 전체 CRUD·새 Service·ID-only/양방향 관계·schema update는 이번에 선택하지 않았다. 시간은 DB 값을 Instant로 읽고 enum은 기존 varchar에 맞춘다. 쓰기 갱신 시각/OWNER 불변식은 쓰기 단계에서 설계한다.
- **실제 확인/미실행:** V1·seed·현재 JDBC 설정/Gradle/health 테스트와 캐시된 Boot BOM을 읽었다. Hibernate 6.6.53.Final/Jakarta Persistence 3.1.0/Spring Data BOM 2025.0.13은 BOM 값이며 JPA 의존성을 아직 해결한 결과가 아니다. 이번에는 문서만 변경했고 JPA 코드/DB 조회·GRANT·빌드·JUnit/Vue 테스트는 미실행이다.
- **문서 검증:** PowerShell로 V1과 명세의 3개 테이블/5개 조회 계약·문자열 길이·identity/timestamp/enum을 대조하고 로컬 Markdown 링크 24개를 확인했다. 예정 Java 파일/권한 스크립트·JPA 의존성/설정도 아직 없음을 확인했다. 실제 JPA 쿼리나 DB 권한 전환이 검증된 것은 아니다.
- **다음 작업:** 조회 엔티티/Repository·읽기 권한 정책과 기존 도구 호환을 구현하고 격리 PostgreSQL에서 검증한다. 실제 로컬 권한 적용은 그 뒤의 별도 작업이다.

## Phase 1 — JPA 조회·읽기 권한 정책 구현 (2026-11-03)
- **흐름:** 격리 fixture의 관리자/migrator가 V1·seed·권한을 준비 → 앱 계정의 EntityManagerFactory/Hibernate validate → readOnly Repository → Hikari/JDBC → 기존 3개 테이블. HTTP/Service/DTO는 아직 없으며 권한 도구는 별도 PowerShell/Java 프로세스다.
- **원리 1 — 관계 매핑과 필요한 fetch:** Membership의 User/Workspace는 LAZY이며 단건 조회에서 초기화되지 않음을 실제 트랜잭션으로 확인했다. 소속 Slice만 workspace EntityGraph로 가져오고 다중 workspace fixture에서 순서/hasNext와 SQL 1회 조회를 확인해 ToOne 접근의 N+1을 막았다. 모든 연관관계를 EAGER로 바꾸지 않았다.
- **원리 2 — readOnly와 DB 권한:** Repository는 읽기 메서드만 공개한다. 그러나 readOnly를 사용하지 않는 JDBC와 EntityManager native UPDATE 경로도 검증했고 DB가 쓰기를 거부했다. 앱은 업무 SELECT만 가능하며 sequence/history·DML·DDL은 거부된다. 이 DB 읽기 권한은 팀별 API 인가가 아니다.
- **원리 3 — 권한 전환도 상태 검증:** schema USAGE 하나만 주면 부분 상태다. 정확한 CONNECT-only/READ-only 두 상태만 허용하고 PUBLIC·다른 역할·컬럼/default grant·GRANT OPTION·역할 상속 등은 실패시킨다. 사전 검사 뒤 지정 GRANT를 트랜잭션으로 수행하며 기존 권한을 임의 회수해 맞추지 않는다. 이후 migration/seed 재실행도 실제 경로로 확인했다.
- **실제 검증:** 전체 JUnit 96개(기존 62+신규 34)와 build 성공, Hibernate 6.6.53.Final runtime 확인. PowerShell migration/seed/읽기 권한 경로 41개 성공. 실제 앱 계정으로 V1 validate·Long/Instant/varchar enum을 읽고, schema/컬럼 제거 시 validate가 실패하고 DB를 수정하지 않는 것도 확인했다. 앱 JAR의 privileged 코드/리소스/비밀 제외와 테스트 리소스 정리도 확인했다.
- **범위와 남은 작업:** 실제 로컬 읽기 전용 조회는 데이터 5/2/5, V1 성공 1건, app schema USAGE=false/SELECT 0개였다. 로컬 GRANT는 실행하지 않았다. 새 JPA 앱의 실제 로컬 기동/조회는 권한 적용 후 별도로 확인한다. 업무 API·인증/인가·쓰기·프론트는 추가하지 않아 Vue 검증은 반복하지 않았다.

## Phase 1 — 실제 로컬 READ 권한·새 JPA 앱 확인 (2026-11-03)
- **관리 흐름:** 사용자 요청 → 전용 PowerShell/Java 관리자 작업 → V1/기존 ACL/앱 인증 확인 → schema USAGE+업무 SELECT GRANT → READ_ONLY 검증. 최초와 반복 모두 성공했다.
- **앱 흐름:** 새 JAR의 클래스/의존성 → SpringApplication.run → Hibernate validate/EntityManagerFactory → Repository의 readOnly 조회 → Hikari/JDBC의 flowdesk_app → 로컬 seed. 업무 API가 없으므로 build 아래 임시 main으로 실제 Bean을 호출했다. HTTP health는 별도로 확인했다.
- **핵심 원리:** (1) GRANT는 DB 상태이며 앱 파일·비밀번호를 바꾸지 않아도 새 조회 권한을 사용할 수 있다. (2) health 200/UP과 실제 업무 Repository 조회는 각각 확인해야 한다. (3) 앱의 SELECT 권한은 쓰기나 사용자별 인가를 제공하지 않는다. 동일 앱 DataSource의 DML/DDL/history 조회는 42501로 실패했고 다른 팀 접근 검사는 미래 API/Service 책임이다.
- **실제 결과:** Repository 5개 계약·Alice/A OWNER·Eve/A 미소속/Eve/B OWNER·없는 데이터 empty와 설정 validate/OSIV=false, 실제 계정 flowdesk_app을 확인했다. admin/migrator 환경값은 앱에 전달하지 않았다. health 두 경로 200/UP 후 임시 앱을 종료했고 PostgreSQL은 healthy다. 기존 도구도 READ-only에서 migrate/seed 모두 재실행 0건이었다.
- **보존/미실행:** 전체 데이터/ID/timestamps/history 스냅샷·3개 자격증명/V1 파일 해시·부모 환경이 같았다. 앱/실행기 소스를 바꾸지 않아 전체 빌드/JUnit/Vue 검증은 반복하지 않았다. 임시 verifier는 앱 JAR에 넣지 않았다. 다음은 첫 조회 API의 계약과 행위자 식별/인가 경계 확정이다.

## Phase 1 — 첫 조회 API 계약 검토 (2026-11-03)
- **상태:** [첫 조회 API 계약](workspace-read-api-v1.md)을 사용자 답변으로 확정했다. 목록/상세·네 역할의 소속 공간 조회, 로컬 개발 모드의 서버 지정 행위자, 미소속/미존재 동일 404를 채택했다. 코드는 아직 구현하지 않았다.
- **원리 1 — 식별과 인가:** 서버 설정으로 개발 행위자를 선택하는 것은 실제 사용자 인증이 아니다. 어떤 방식으로 행위자를 얻더라도 Service는 그 사용자와 요청 공간의 membership을 확인해야 한다. 앱 DB 계정의 SELECT는 팀별 접근 허용을 결정하지 않는다.
- **원리 2 — 조회 트랜잭션과 응답:** Service 트랜잭션 안에서 LAZY 연관관계를 읽고 DTO로 변환하면 OSIV 없이 필요한 필드만 응답할 수 있다. Slice의 페이지 상태는 전용 DTO로 옮겨 HTTP 계약을 영속성 구현과 분리한다.
- **원리 3 — 오류 계약:** Spring의 ProblemDetail은 상태·설명 등 표준 필드와 code 같은 확장 필드를 표현할 수 있다. SQL/예외 원문을 보내지 않고, 화면은 안내 문구 대신 code로 처리하게 설계한다. [Spring 6.2 공식 문서](https://docs.spring.io/spring-framework/reference/6.2/web/webmvc/mvc-ann-rest-exceptions.html), [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457.html).
- **이번 검증:** 문서 7개의 로컬 링크 23개, 계약 JSON 예제 3개 파싱, 계약의 정책 대기 문구 제거와 ADR-022 단일 기록을 확인했다. 문서만 변경해 빌드·JUnit·DB/HTTP 실행은 하지 않았다. 사용자 선택/대안/이유는 ADR-022에 기록했다.

## Phase 1 — 첫 조회 API·개발 경계 구현 (2026-11-03)
- **요청 흐름:** HTTP → 개발 peer 필터/no-store → Controller 입력 검사 → Service readOnly 트랜잭션 → 서버 지정 사용자 조회 → 사용자 조건 목록/공간+사용자 membership → DTO → JSON. 공간의 소속 검사는 Service를 직접 호출해도 적용된다.
- **원리 1 — 프로필과 인증:** Profile은 Bean 등록 조건이다. 개발 모드만 Controller를 등록하고 시작 시 행위자/주소/전달 헤더 설정을 검사한다. 같은 컴퓨터의 요청은 지정 행위자를 공유하므로 실제 로그인이 아니며, 클라이언트 헤더나 쿠키를 인증한 것으로 취급하지 않는다.
- **원리 2 — 트랜잭션과 DTO:** OSIV=false에서 LAZY 공간을 읽고 DTO를 만드는 작업을 Service 트랜잭션 안에서 완료했다. 목록은 actor 1쿼리+기존 EntityGraph Slice 1쿼리로 여러 공간도 추가 조회 없이 반환한다. DB SELECT와 readOnly는 팀별 인가를 대신하지 않는다.
- **원리 3 — 장애 예외의 계층:** 닫힌 Hikari 연결은 JpaSystemException → Hibernate TransactionException → SQLException으로 실패할 수 있다. 이를 놓치면 장애가 500으로 응답한다. JDBC 트랜잭션 실패는 503, 권한 오류/예상 밖 실패는 안전한 500으로 구분했다. API 응답에는 SQL·예외 원문·설정값을 넣지 않고 서버 로그도 예외 타입만 남긴다.
- **검증:** 신규 격리 PostgreSQL/실제 HTTP 검증 42개 통과. 프로필/설정 오류, 사용자·역할·미소속 404, 입력/헤더 위조, 비loopback 필터(모의 servlet peer), Service 직접 호출, SQL 수, DB pause/unpause 복구와 안전한 500을 확인했다. 테스트 단계부터 config import를 비우고 전용 테스트 자격증명을 주입한다. 테스트 클래스패스의 Flyway 자동 실행도 꺼 실제 앱의 별도 마이그레이션 경계를 유지했다.
- **범위:** 로컬 서버/데이터/권한은 건드리지 않았다. Vue·실제 인증·쓰기는 추가하지 않았다. 기존 버전/의존성을 유지하고 ADR-023에 구현 선택을 기록했다.
- **전체 회귀:** backend에서 .\gradlew.bat build --no-daemon --no-configuration-cache --console=plain 성공(3분 15초), JUnit 138개 실패/오류/skip 0. 패키징 경계도 확인했다. infra 실행기/프론트 변경이 없어 별도 PowerShell 전체 경로 41개와 Vue 검증은 반복하지 않았다.

## Phase 1 — 실제 로컬 개발 모드 HTTP 확인 (2026-11-03)
- **흐름:** java -jar + dev-actor/서버 설정 사용자 → loopback HTTP → 입력 검사 → Service 트랜잭션에서 사용자·소속 조회 → JPA/Hikari/JDBC의 flowdesk_app → 실제 로컬 seed → DTO/JSON. 실제 Alice/Eve 목록·상세와 다른 공간 차단을 각각 확인했다.
- **원리 1 — 행위자와 DB 계정:** Alice/Eve로 개발 행위자를 바꿔도 DB 계정은 flowdesk_app이다. 팀별 차단은 Service/조회 조건의 책임이며 DB 권한이 사용자를 자동 구분해 주지 않는다. 위조 HTTP 사용자/토큰 헤더로 서버 지정 행위자는 바뀌지 않았다.
- **원리 2 — 재시작과 상태:** 서버 지정 사용자를 바꿀 때 새 프로세스로 시작한다. 자식 프로세스에만 설정을 주면 부모 환경을 바꾸지 않고 검증할 수 있다. 이번에 생성한 프로세스만 종료해 다른 서버에 영향을 주지 않았다.
- **원리 3 — JSON 미디어 타입과 클라이언트:** PowerShell이 application/problem+json 본문을 byte[]로 반환할 수 있었다. 임시 검증기에서 UTF-8 디코딩 후 JSON을 파싱해 404/400 계약을 확인했다. 앱 응답 오류로 오인해 API를 수정하지 않았다.
- **결과/범위:** bootJar 성공, 실제 Alice/A·Eve/B 목록/상세 200, 상대/미존재 동일 404, 잘못된 입력 400, 빈 페이지 200, health 두 경로 200/UP. 전후 데이터/이력/ACL/시퀀스·자격증명/V1 해시·부모 환경이 같았다. 검증 서버는 종료했고 앱 소스 변경이 없어 전체 138개/Vue/PowerShell 전체 경로는 반복하지 않았다. ADR-024와 local-setup.md에 기록했다.

## Phase 1 — Vue 워크스페이스 조회 연결 (2026-11-03)
- **흐름:** WorkspaceBrowser의 목록/선택/페이지 동작 → workspaces.ts fetch → Vite /api → 개발 접속 필터/Controller → Service 트랜잭션·서버 지정 사용자/소속 검사 → JPA/PostgreSQL → DTO/JSON → 응답 검사 → Vue 상태/화면. 브라우저가 사용자 ID나 역할을 지정하지 않는다.
- **원리 1 — 타입과 실제 데이터:** TypeScript interface는 서버 JSON을 검사하지 않는다. ID/역할/페이지/상세 ID를 별도 검사하며 bigint ID를 Number로 바꾸지 않는다. 서버의 상세 오류 원문 대신 코드/상태를 안전한 안내로 변환한다.
- **원리 2 — 요청 경합과 생명주기:** A를 선택한 뒤 B로 바꿀 때 A의 늦은 응답이 B를 덮을 수 있다. AbortController로 이전 요청을 취소하고 응답 적용 전에 취소 여부도 확인한다. AbortSignal.any로 수동 취소/5초 제한을 결합하고 언마운트에서도 요청을 정리한다. [공식 Vue 생명주기](https://vuejs.org/api/composition-api-lifecycle.html), [AbortSignal.any](https://developer.mozilla.org/en-US/docs/Web/API/AbortSignal/any_static).
- **원리 3 — UI 상태와 서버 권한:** 선택·로딩·오류는 지역 UI 상태, 목록/상세는 서버 데이터다. 새 조회 시작 시 이전 상세를 지우고 health UP과 업무 조회 성공을 구분한다. 화면의 역할 배지는 서버 인가를 대신하지 않는다. 단일 화면에서는 Router/Pinia 없이 지역 상태로 시작할 수 있다.
- **검증:** 프론트 31개 테스트·타입 검사/빌드 통과. mock fetch DOM 테스트와 실제 Chrome/Vite/로컬 DB의 Alice/A·Eve/B 목록/상세·요청 차단/재시도·모바일 배치를 각각 확인했다. 데이터/이력/relation ACL/시퀀스·자격증명/V1은 같고 검증 프로세스는 종료했다. 백엔드/DB 변경이 없어 전체 JUnit 138개/PowerShell 41개는 반복하지 않았다. ADR-025에 기록했다.

## Phase 1 — 워크스페이스 생성 설계 (2026-11-03)
- **상태/흐름:** [생성 계약](workspace-create-api-v1.md) 설계만 확정했다. 개발 쓰기/출처 검사 → Controller → 쓰기 Service의 한 트랜잭션 → 행위자·공간/OWNER membership 저장 → 커밋 → 201/DTO다. 앱/DB/권한 구현은 아직 바꾸지 않았다.
- **원리 1 — 원자성과 응답:** IDENTITY INSERT/flush는 커밋이 아니다. membership 실패 시 공간도 롤백해야 한다. 하지만 커밋 후 응답 유실이나 클라이언트 취소는 DB 롤백을 보장하지 않는다. 이름 중복 허용 정책에서는 자동 POST 재시도를 하지 않고 목록을 확인한다.
- **원리 2 — 역할과 생성 자격:** 기존 공간 VIEWER도 새 공간의 OWNER가 될 수 있다. 공간별 역할은 전역 사용자 등급이 아니다. INSERT 권한 자체가 올바른 생성자/OWNER를 강제하지 않으므로 Service에서 행위자와 역할을 정한다.
- **원리 3 — 개발 쓰기/기존 도구:** 로컬 개발 행위자를 공유하는 서버는 쿠키가 없어도 외부 웹 출처의 쓰기를 고려해야 한다. 별도 쓰기 설정·필수 Origin의 정확한 allowlist·JSON 검사를 설계했다. DB 권한을 확대하면 기존 엄격한 ACL 검사가 실패하므로 새 상태와 migration/seed 도구 회귀도 함께 설계해야 한다. 실제 인증/운영용 CSRF를 대체하는 정책은 아니다.
- **검증 범위:** 문서 8개의 로컬 링크 34개와 HTTP JSON 예제 2개, ADR-026 단일 기록을 확인했다. 생성·원자성·쓰기 권한/출처 테스트와 빌드는 구현 후 실행할 사항이며 이번에 통과했다고 주장하지 않는다. 결정/대안/근거는 ADR-026이다.

## Phase 1 — 생성 구현·권한 전환과 실행 오류 검증 (2026-11-03)
- **흐름:** 개발 쓰기/Origin 필터 → bounded UTF-8/JSON 검사 → CommandService 트랜잭션 → 행위자 조회 → Workspace/OWNER persist → flush/커밋 → 201/Location/DTO. readOnly 조회 계약은 유지했다.
- **원리 1 — 커밋과 장애:** 저장 시점과 커밋 시점 모두 실패를 주입해 두 업무 행이 남지 않는 것을 검증했다. JDBC 트랜잭션 예외도 SQLState에 따라 연결 장애와 무결성 오류를 구분해야 한다. 성공 응답은 커밋 뒤에 반환하며, 응답 유실은 별개여서 자동 POST 재시도를 하지 않는다.
- **원리 2 — 쓰기 권한과 출처:** IDENTITY INSERT/키 반환은 별도 sequence GRANT 없이 성공했고 직접 nextval/currval/setval은 거부됐다. 정확한 두 INSERT 상태만 허용하며 부분/과도한 ACL을 자동 보정하지 않는다. 실제 Vite 프록시에서도 허용 Origin이 전달돼 201, 외부 출처는 403/행 추가 없음이다. Origin은 실제 인증이 아니며 로컬 개발 경계를 보완한다.
- **원리 3 — 클래스패스와 구성:** 컴파일 성공은 IDE 리소스/기동 성공을 뜻하지 않는다. 공유 리소스 루트/Gradle Java 25 캐시를 분리하고, IDE에 들어온 Flyway가 앱에서 자동 실행되지 않도록 false를 명시했다. 앱에 DB CREATE 권한을 주는 방식으로 해결하지 않았다. 최신 IDE 클래스패스의 읽기 모드 정상 기동도 직접 확인했다.
- **결과:** Java 199개/별도 Vite 1개·PowerShell 경로 52개 통과. 기본 test는 Node 의존 프록시 검증을 제외하고 verifyWorkspaceProxy가 명시적으로 실행한다. 실제 로컬은 쓰기 미적용이며 읽기 기동만 확인한 뒤 종료했다. ADR-027/028에 구현과 오류 수정을 기록했다.

## Phase 1 — 로컬 최소 INSERT·첫 생성 확인 (2026-11-03)
- **흐름:** 전용 관리자 도구의 최소 INSERT 적용 → 별도 개발 쓰기 앱 → Origin/입력 검사 → CommandService 트랜잭션 → 공간/생성자 OWNER 저장·커밋 → 201/Location → 목록/상세 재조회. 실제 데이터가 5/2/5에서 5/3/6으로 바뀌었다.
- **원리 1 — 권한과 활성화:** DB의 INSERT GRANT만으로 POST가 활성화되지는 않는다. 쓰기 설정/서버 행위자/허용 Origin이 별도로 필요하다. 반대로 쓰기 설정만 켜도 DB 권한이 없으면 시작을 거부한다. 기존 IDE 서버는 그대로 두고 별도 앱에서 확인했다.
- **원리 2 — 재시도와 검증:** 첫 POST는 한 번만 보내고 201과 실제 두 행/OWNER·GET 결과를 확인했다. 임시 작업 영수증은 재실행 때 이미 생성한 공간을 조회하는 용도이며 서버 멱등키가 아니다. 기존 데이터/이력을 새 ID로 제외해 비교하고 파일/부모 환경을 보존했다.
- **원리 3 — seed의 범위:** seed는 예제 전체 일치 검사이지 업무 데이터 리셋 도구가 아니다. 새 공간이 생긴 뒤 Verify mismatch는 정상이다. 원본 예제를 변경하거나 새 공간을 삭제해 맞추지 않았다.
- **결과/검증:** 생성 ID 3/Alice OWNER·201, Alice 목록/상세 200·Eve 404, 입력 400/Origin 403, migration Validate 성공을 확인했다. 기존 IDE PID/health와 DB healthy를 유지했고 검증 서버는 종료했다. 제품 코드 변경이 없어 이전 199+1/52/프론트 31개는 반복하지 않았다. ADR-029에 기록했다.

## Phase 1 — Vue 생성 폼·POST 연결 (2026-11-04)
- **흐름:** v-model 이름 입력 → submit 진행 가드/정규화 → 이름만 POST → Vite/서버 쓰기·Origin 검사 → CommandService/DB 두 행 커밋 → 201 계약 확인 → 생성 완료 → 목록/상세 GET → 화면. 폼과 조회는 각자의 상태/책임을 가진다.
- **원리 1 — 입력과 문자 길이:** UTF-16 maxlength=100이면 보조 문자를 50개에서 자를 수 있다. 서버와 같은 공백 제거/코드 포인트·제어 문자 검사를 하되 최종 검증/행위자/OWNER 지정은 서버에 둔다. [Vue 폼 바인딩](https://vuejs.org/guide/essentials/forms.html).
- **원리 2 — 성공과 결과 불명:** fetch는 HTTP 상태/본문을 별도로 확인해야 한다. 201/Location/DTO를 확인한 성공과 거부, 응답 유실/5xx의 결과 불명을 구분했다. 실제 커밋 뒤 201만 차단한 브라우저에서도 행은 남았고 UI는 재전송하지 않았다. 취소/페이지 종료도 DB 롤백을 보장하지 않는다. [Fetch 응답 처리](https://developer.mozilla.org/en-US/docs/Web/API/Fetch_API/Using_Fetch).
- **원리 3 — mutation과 조회의 분리:** POST 성공 후 GET이 실패해도 공간을 다시 만들지 않는다. 새 ID 상세는 목록 0페이지에 없어도 선택하고 서버 페이지를 변조하지 않는다. 이전 요청 취소/생성 이벤트 버전으로 늦은 응답을 제어한다.
- **검증:** 프론트 58개와 타입 검사/빌드 통과. 실제 Chrome/Vite/고유 PostgreSQL에서 정상·중복 제출·응답 유실/GET 확인·쓰기 off 405·모바일 배치를 확인했다. 격리 5/4/7은 두 생성/OWNER를 증명하고 정리됐으며, 기존 로컬 5/3/6·이력/ACL/시퀀스·자격증명/V1은 유지했다. 백엔드/의존성 변경이 없어 전체 Java 199개/별도 프록시 1개·PowerShell 52개는 반복하지 않았다. ADR-030에 기록했다.


## Phase 1 — 이름 수정 권한·낙관적 충돌 설계 (2026-11-04)
- **흐름:** PATCH name/version → 개발 쓰기/Origin → Controller → Service 소속·역할·요청 버전 검사 → JPA/DB 버전 조건 UPDATE → commit → DTO/200. 아직 구현하지 않았다.
- **원리 1 — 두 시점 검사:** 오래된 화면은 요청 버전 비교로, 조회 후 발생한 동시 수정은 DB UPDATE 버전 조건으로 검출한다. @Version만 추가하고 요청 버전을 무시하면 오래된 화면의 변경을 놓칠 수 있다. [Jakarta Persistence 3.1](https://jakarta.ee/specifications/persistence/3.1/jakarta-persistence-spec-3.1.html).
- **원리 2 — 권한 경계:** 다른 공간의 OWNER는 수정 자격이 아니다. 해당 membership의 OWNER/ADMIN만 허용하며 미소속/미존재는 동일 404다. 앱 DB 계정의 컬럼 UPDATE는 사용자별 인가를 대신하지 않는다. [컬럼 GRANT](https://www.postgresql.org/docs/17/sql-grant.html).
- **원리 3 — 버전과 응답:** 버전은 provider가 관리하고 JSON에는 정밀도 손실 없는 문자열로 전달한다. commit 뒤 응답이 유실되면 저장 결과가 불명확할 수 있어 버전 충돌 검출을 멱등키/자동 재전송 보장으로 해석하지 않는다.
- **검증 범위:** 문서 링크·HTTP JSON·ADR 단일성만 검사했다. API/SQL/권한 검증과 빌드는 구현 후 수행한다. 계약은 workspace-rename-api-v1.md, 결정은 ADR-031이다.


## Phase 1 — 이름 수정 구현과 격리 검증 (2026-11-04)
- **흐름:** PATCH JSON → 개발 접속/쓰기/Origin → Controller → CommandService 트랜잭션 → 서버 행위자/소속/역할/요청 버전 → Entity rename/OPTIMISTIC·flush → commit → version DTO/200.
- **원리 1 — 충돌의 두 시점:** 같은 version을 조회한 두 실제 HTTP 트랜잭션을 DB 행 잠금 뒤에 대기시켜 UPDATE 경합을 만든다. 요청 버전 비교뿐 아니라 DB UPDATE의 version 조건이 있어야 하나만 저장된다. no-op도 트랜잭션 종료 시 OPTIMISTIC 검증을 해 중간 커밋을 검출한다.
- **원리 2 — 컬럼 ACL:** PostgreSQL의 UPDATE(name, updated_at, version)는 테이블 UPDATE와 다르다. 정확한 컬럼 집합/대상/GRANT OPTION/PUBLIC까지 검사해야 한다. 부분 상태를 도구가 임의 보충하면 사용자가 승인한 경계를 확인하지 못한다.
- **원리 3 — 음성 테스트 입력:** GENERATED ALWAYS identity의 id=id는 인가보다 앞서 428C9가 발생하므로 id=DEFAULT WHERE false로 유효한 문장을 검사한다. 잘못된 surrogate도 HTTP 인코딩 중 ?로 바뀌면 다른 입력을 테스트하게 되어 JSON escape로 전송한다.
- **적용 경계:** V2 SQL·엔티티·응답이 함께 바뀌어 새 앱을 V1 DB에 먼저 올리면 validate가 실패한다. 기본값은 데이터를 이전하는 수단이며 updated_at의 INSERT DEFAULT는 UPDATE 시각을 자동 갱신하지 않는다. 실제 로컬 적용은 아직 실행하지 않았다.
- **검증:** 최종 명령·결과는 local-setup.md, 구현 계약은 workspace-rename-api-v1.md, 결정은 ADR-032를 따른다.

- **최종 결과:** Java 245개·실제 Vite POST/PATCH 1개·PowerShell 60개·Vue 58개/타입 검사·빌드 통과. 프록시 테스트는 기존 POST와 같은 HTTP/1.1로 맞춰 통과했다. 로컬 V1/5·3·6/UPDATE 없음·자격증명/V1 해시를 보존했고 격리 자원을 정리했다. 다음 실제 로컬 적용과 Vue 수정 폼은 구분한다.


## Phase 1 — 실제 로컬 V2·권한 적용과 수정 확인 (2026-11-04)
- **흐름:** 앱/연결 없음 확인 → 별도 migrator V2 → 별도 관리자 컬럼 GRANT → 새 dev-actor JAR → PATCH → Service 권한/버전 → commit → 200·목록/상세 GET → DB 보존 대조.
- **원리 1 — 스키마·권한·활성화:** V2가 있어야 새 JPA validate가 성공하고, 추가 컬럼 권한이 있어야 쓰기 앱이 시작한다. DB GRANT만으로 HTTP 쓰기가 켜지지는 않는다. 실제 쓰기 off 앱에서도 조회 200/수정 405를 확인했다.
- **원리 2 — 버전과 no-op:** 실제 수정은 version 0→1, 오래된 요청은 409다. 같은 정규화 이름은 200이지만 버전/updated_at을 유지한다. ADMIN 자격은 seed 공간을 바꾸지 않는 no-op으로 확인하고, VIEWER는 같은 DB 계정의 UPDATE 권한이 있어도 Service에서 403으로 막았다.
- **원리 3 — 관찰과 재전송:** 변이 전 영수증을 저장해 응답 유실 시 자동 재전송을 막고 GET으로 확인한다. 시각의 +00:00/+09:00 직렬화 차이는 DB 값 변화가 아니므로 동일 시각으로 정규화해서 비교했다.
- **결과:** 데이터 5/3/6 유지, 비-seed ID 3 이름/updated_at/version만 변경, 세 컬럼 UPDATE만 허용. V1/기존 seed/멤버십/시퀀스/소유권/relation ACL/자격증명과 검증 JAR를 보존했다. 실제 명령은 local-setup.md, 결정은 ADR-033이다. 제품 코드 변경이 없어 기존 전체 테스트는 반복하지 않았다.


## Phase 1 — Vue 이름 수정·버전 보존·409 복구 (2026-11-05)
- **흐름:** 상세 GET의 name/version → 폼의 편집 기준 + draft → PATCH → 서버 Service/DB 버전 검사 → 200 DTO 반영 또는 409 잠금 → 최신 GET/사용자 확인 → 수동 저장. 최종 인가/원자성은 서버에 유지한다.
- **원리 1 — 문자열 버전과 편집 기준:** JavaScript number로 큰 bigint 버전을 읽으면 정밀도를 잃는다. 문자열로 검증·보존하고 BigInt는 증가 비교에만 쓴다. 최신 조회값을 props/watch로 자동 적용하면 오래된 draft가 새 버전의 의도적 변경처럼 전달될 수 있어 사용자 확인 전에는 시작 버전을 바꾸지 않는다.
- **원리 2 — 충돌과 결과 불명:** 409는 저장 거부이며 입력을 유지해 재검토한다. 응답 유실은 서버 커밋 후에도 가능하므로 자동 재전송하지 않는다. 실제 Chrome에서 커밋된 200만 유실시켜 DB에는 version 4가 남고 UI는 GET만으로 확인하며 저장 잠금을 유지하는 것을 검증했다.
- **원리 3 — 응답 반영과 생명주기:** 이름 수정은 서버 ID 정렬을 바꾸지 않아 검증한 DTO로 기존 행/상세만 갱신할 수 있다. 같은 ID의 상세 갱신은 draft를 유지하고, 선택 변경/unmount는 요청을 취소하고 늦은 응답을 무시한다. 요청 취소를 서버 롤백으로 해석하지 않는다.
- **검증:** 프론트 101개·타입 검사/빌드와 실제 Chrome/고유 DB 통과. 로컬 V2/5·3·6·ID 3 version 1/전체 ACL·시퀀스·자격증명/V1/V2/JAR는 보존했다. 새 기술/백엔드 의존성은 추가하지 않았다. 결정은 ADR-034, 명령은 local-setup.md다.


## Phase 1 — Project 모델·생성/조회 권한 설계 (2026-11-05)
- **흐름:** 공간 하위 Project HTTP → 개발 접속/쓰기·입력 검사 → Service 서버 행위자/부모 membership/생성 역할 → workspace_id 조건 Repository/DB FK → DTO/응답. 아직 구현하지 않았다.
- **원리 1 — 역할과 부모 경계:** 한 공간의 OWNER라는 사실은 다른 공간의 생성 자격이 아니다. 사용자에게 A/B 접근 권한이 모두 있어도 A 경로에서 B 프로젝트 ID를 요청하면 404다. 소속 검사와 Repository의 부모 조건을 모두 유지한다.
- **원리 2 — FK와 인가/인덱스:** DB FK는 부모 존재/참조 무결성을 보장하고 사용자의 소속·역할을 대신하지 않는다. 공간별 목록 필터/정렬에는 workspace_id,id 인덱스를 설계한다. 앱 계정의 SELECT/INSERT는 서비스 전체 SQL 능력이고 개인별 권한은 Service에서 검사한다.
- **원리 3 — 현재 범위와 추가 변경:** 생성/조회에는 아직 Project version/보관 상태가 필요하지 않아 이후 수정/보관 정책과 함께 추가한다. 긴 설명은 상세 DTO에만 두고 목록은 요약/Slice로 제한한다. 예측 가능한 ID와 중복 이름은 식별·인가·멱등성을 대신하지 않는다.
- **검증 범위:** 문서/HTTP JSON/ADR만 확인한다. V3/JPA/API/권한 테스트와 빌드는 구현 후 수행할 사항이다. 결정은 ADR-035, 수용 기준은 project-model-and-api-v1.md다.


## 2026-11-05 — Project 구현의 세 경계

1. **부모 경로는 인가의 일부다.** `/workspaces/A/projects/P`의 A에 소속인지 Service가 확인하고, Repository도 P와 A를 함께 조건으로 조회한다. FK는 존재하는 공간에 속한다는 무결성을 보장하지만 요청한 사용자의 접근 권한을 검사하지 않는다.
2. **flush와 commit은 다르다.** persist/flush가 성공해도 지연 제약은 트랜잭션 종료 시 실패할 수 있다. 생성 Service의 Spring 트랜잭션 프록시가 commit을 마친 다음 Controller가 201을 만든다. DB 행이 남지 않는지와 HTTP가 201이 아닌지를 함께 검증한다.
3. **새 테이블은 최소 권한 상태의 확장이다.** projects SELECT/INSERT를 따로 추가하되 기존 UPDATE 컬럼 제한은 유지한다. 일부 권한만 있으면 도구가 자동 수선하지 않고 중단한다. DB INSERT 권한, HTTP 쓰기 활성화, OWNER/ADMIN 인가는 각각 별도 검사다.


## 2026-11-05 — 실제 Project 적용에서 확인한 원리

1. **마이그레이션·DB 권한·HTTP 쓰기는 서로 다르다.** V3가 테이블을 만들고 별도 관리자 도구가 SELECT/INSERT를 부여한 뒤에도 쓰기 off 서버는 POST 405다. 쓰기 모드에서도 VIEWER는 Service의 부모 공간 역할 검사로 403이다.
2. **201과 저장된 행을 함께 확인한다.** 응답의 문자열 ID/상대 Location으로 GET하고 실제 DB의 같은 부모/ID 행도 확인했다. 생성은 한 번만 시도하고 응답이 불명확하면 자동 POST 재시도를 하지 않는다. 이름 중복 허용이므로 이름 일치만으로 특정 요청 성공을 단정할 수 없다.
3. **보존 검증은 건수만 비교하지 않는다.** 기존 행·버전·V1/V2 이력·소유권·관계/컬럼 ACL·시퀀스와 보호 파일 해시를 비교했다. Project 한 건 추가가 부모 Workspace 수정이나 멤버십 추가로 이어지지 않는 것도 확인했다.


## 2026-11-05 — 부모 공간과 Project 조회 상태

1. **서버 데이터와 화면 상태의 수명이 다르다.** Project 목록/상세는 서버 응답이고 선택 ID/로딩/오류는 UI 상태다. 공간 ID key는 부모 변경 시 이 상태들을 함께 종료하고, 같은 공간 이름 수정에는 Project 상태를 유지한다.
2. **취소와 늦은 응답 가드를 함께 쓴다.** AbortController는 진행 중 I/O 종료를 요청하지만 이미 끝난 응답이나 취소를 무시하는 테스트/후속 콜백까지 되돌리지 않는다. 응답을 반영하기 전에 취소 상태를 다시 검사해야 이전 공간의 결과가 새 화면을 덮지 않는다.
3. **타입 선언은 API 검증이나 인가가 아니다.** 부모/Project ID·페이지·설명은 unknown에서 검증하고 DTO만 반환한다. 404를 빈 목록으로 바꾸지 않으며, 서버가 membership/부모 조건을 검사한다. 설명은 텍스트 바인딩으로 HTML을 실행하지 않는다.


## 2026-11-05 — 생성 성공과 결과 불명

1. **저장 성공과 응답 수신은 다르다.** 실제 격리 DB에서 커밋된 201만 유실시켰다. 클라이언트는 결과 불명으로 잠그고 POST를 자동 재전송하지 않는다. GET의 이름 일치는 특정 요청의 성공 증명이 아니며, 언마운트/HTTP 취소도 이미 커밋한 DB를 롤백하지 않는다.
2. **POST와 후속 GET은 별도 결과다.** 201이 확인됐으면 생성 성공을 유지한다. 목록 GET이 실패해도 새 ID 상세는 별도 시도하고, 새 ID가 첫 페이지에 없어도 페이지를 임의로 변조하지 않는다. 연속 생성/탐색이 시작되면 이전 후속 GET의 늦은 자동 상세 선택을 막는다.
3. **폼 표시와 권한/설정은 다르다.** 부모의 OWNER/ADMIN은 UI 힌트이며 서버가 매 요청 소속/역할을 검사한다. 역할상 생성 가능해 폼이 보여도 개발 쓰기 설정이 off면 405가 된다. 안전한 오류를 보여 주고 입력을 보존한다.


## 2026-11-05 — Project 수정 계약의 세 경계

1. **버전의 단위는 Project 한 행이다.** 이름/설명을 각각 바꿔도 같은 시작 버전이면 충돌이다. Workspace 버전은 별개이고, 필드별 병합은 자동으로 하지 않는다. 뒤의 사람이 최신 두 필드와 draft를 비교해 다시 저장한다.
2. **오류 순서도 계약이다.** 부모 membership 다음에 부모+Project를 조회하고 역할·버전을 검사한다. 자원이 없는데 역할만 보고 403을 반환하거나 미소속 사용자에게 409/현재 버전을 노출하지 않는다. 권한 있는 현재 자원 요청만 버전 충돌을 검사한다.
3. **no-op도 버전을 검증한다.** 값이 같으면 updated_at/version을 유지하지만 오래된 요청을 성공으로 처리하지 않는다. 최초 비교와 트랜잭션 종료 시 낙관적 검증이 필요하고, flush 뒤 commit 실패도 성공 응답 전에 처리해야 한다. 이 계약은 후속 격리 테스트로 검증한다.


## 2026-11-05 — Project 수정 구현에서 검증하는 경계

1. **SELECT 버전 비교 뒤에도 경쟁이 있다.** 둘 다 version 0을 읽고 서로 다른 필드를 바꿀 수 있다. 실제 DB UPDATE의 버전 조건으로 한 트랜잭션만 커밋하고 다른 요청은 409로 처리해야 한다.
2. **no-op의 검증은 UPDATE 발생 여부와 다르다.** 값이 같아 SQL UPDATE가 없어도 읽은 버전의 유효성을 commit 시 검사한다. 명시적 OPTIMISTIC 검증과 바깥 트랜잭션 도중 다른 요청의 실제 commit으로 이 차이를 확인한다.
3. **버전 추가는 소비자와 도구의 전환이다.** 상세/생성 파서는 문자열 버전을 보존하고 새 생성은 0을 확인해야 한다. 최소 컬럼 권한·기동 검사·현재 이력/seed·이전 버전 테스트 target도 함께 전환하며 목록 요약에는 불필요한 편집 버전을 추가하지 않는다.


## 2026-11-07 — 실제 로컬 Project 수정 확인

1. **마이그레이션과 앱 권한은 별도 책임이다.** V4는 스키마/버전 컬럼을 추가하고, 관리자 전용 도구는 앱이 쓸 수 있는 네 컬럼만 추가한다. 앱은 schema validate만 수행하며 개발 쓰기 flag를 끄면 DB 권한이 있어도 HTTP PATCH는 405다.
2. **성공한 버전은 검증 후에도 유지한다.** 실제 HTTP 수정이 commit되면 Project version 0→1이 되고 stale 0은 409다. ADMIN의 동일 값 수정은 200이면서 시간/버전이 그대로다. 검증 완료를 이유로 DB version을 0으로 되돌리지 않는다.
3. **UP·저장·인가를 각각 검증한다.** Actuator UP은 DB 연결 가능성을 확인한다. 별도로 HTTP 수정/GET과 DB 행을 비교하고, MEMBER/VIEWER 403·미소속/부모 불일치 404와 실제 앱 역할의 금지 컬럼 42501을 확인해야 각 경계가 입증된다. GENERATED ALWAYS IDENTITY의 SQL 규칙이 권한 오류보다 먼저 나올 수 있어 검사 문법도 구분한다.


## 2026-11-07 — Vue Project 수정의 편집 기준과 두 필드

1. **서버 표시값과 편집 기준은 다르다.** 최신 GET으로 상세/목록 표시를 바꿔도 사용자의 name/description draft와 시작 version은 그대로다. 두 필드를 비교한 뒤 명시적으로 기준을 바꾸고 별도 저장한다. 같은 ID의 props가 바뀌어도 버전을 자동 교체하지 않는다.
2. **문자열 버전과 저장 확인을 함께 검증한다.** PATCH에는 문자열 version을 그대로 보내고 다음 버전 계산에만 BigInt를 사용한다. 200도 부모/id/정규화된 두 필드/기대 버전이 맞아야 성공이며 no-op은 같은 버전이다. 잘못된 200/응답 유실/5xx는 결과 불명으로 잠근다.
3. **복구 조회와 재저장은 별도다.** Project DTO에 역할이 없으므로 복구 시 Workspace GET으로 현재 역할도 확인한다. 어느 GET이든 실패하거나 수정 권한을 잃으면 잠금을 유지한다. GET 내용이 입력과 같아도 이전 PATCH의 성공으로 단정하거나 자동 재전송하지 않는다. 화면 이탈은 쓰기·복구 조회를 취소하고 늦은 이벤트를 버린다.


## 2026-11-07 — Task 정책과 부모 인가

1. **Task 생성과 Project 관리는 다른 권한이다.** 같은 MEMBER도 기존 Project에 Task를 만들 수 있지만 Project 생성/수정은 할 수 없다. 앱 DB 계정이 INSERT를 할 수 있는지와 요청 사용자가 해당 공간에 소속돼 그 동작을 할 수 있는지는 별도로 검사한다.
2. **깊은 URL은 부모 검사를 대신하지 않는다.** membership 다음 Project의 id+workspaceId, 상세에서는 Task의 id+projectId까지 검증한다. Task에 workspace_id를 중복 저장하지 않아도 이 연결을 확인할 수 있다. 또한 기존 POST 필터가 더 깊은 경로에 자동 적용되는 것은 아니므로 Task 패턴을 명시적으로 추가해야 한다.
3. **상태 값 집합과 상태 변경 기능은 다르다.** DB/JPA에 TODO/IN_PROGRESS/DONE을 정의해도 첫 생성은 서버 TODO만 허용한다. 클라이언트 status를 무시하지 않고 거부하며 실제 상태 전환/충돌·Activity 기록은 별도 계약에서 정한다. 담당자/마감일/버전도 사용하기 전에 정책을 확정한다.


## 2026-11-07 — Task 구현의 세 검증 경계

1. **부모 확인과 역할 확인은 각각 필요하다.** membership만 있거나 Task ID만 맞아도 충분하지 않다. Project의 Workspace와 Task의 Project를 확인한 뒤 세 역할 생성을 허용한다. MEMBER의 Task 생성 201과 같은 사용자의 Project 관리 403을 함께 테스트해 권한이 번지지 않는지 검증한다.
2. **새 URL은 필터 적용도 테스트해야 한다.** Task POST는 기존 Project POST보다 깊어 기존 패턴에 포함되지 않았다. Task 패턴을 명시적으로 추가하고 실제 Vite를 거친 정상 Origin/외부 Origin 요청으로 보호를 확인한다. Service 인가 테스트만으로 출처 경계가 증명되지는 않는다.
3. **마이그레이션 latest와 과거 검증 target은 다르다.** V5 fixture와 현재 이력은 1~5로 전환하지만 V4 자체의 데이터/제약 검증은 target 4로 남긴다. 앱 DB 권한도 Task SELECT/INSERT만 단계적으로 추가하고 기존 도구의 새 상태 보존·이상 권한 거부를 실제 실행기로 확인한다.


## 2026-11-08 — 실제 Task 생성에서 확인한 원리

1. **같은 사용자의 동작별 권한은 다를 수 있다.** MEMBER Carol은 기존 Project의 Task POST 201을 받았지만 Project 생성/수정은 403이었다. DB 접속 계정은 같은 flowdesk_app이며 사용자별 판단은 Service의 실제 membership/역할 검사다.
2. **기본값·commit·조회는 실제 응답과 행으로 확인한다.** status/priority를 보내지 않은 단일 생성에서 서버 TODO·MEDIUM, 201/문자열 ID/상대 Location을 확인하고 후속 GET/DB 행을 비교했다. Task 생성은 부모 Project의 version/updated_at을 바꾸지 않는다. POST는 결과 불명일 때 자동 재시도하지 않는다.
3. **권한은 문자열 전체보다 의미와 주체를 구분한다.** 소유자 ACL 표기 집합을 고정하면 앱 권한과 무관한 차이에도 검사가 실패할 수 있다. 소유권과 앱 SELECT/INSERT·grant option/다른 grantee·금지 유효 권한을 나눠 확인하고, 실제 앱 역할의 0행 INSERT/금지 UPDATE·DELETE/이력 접근을 ROLLBACK 검사로 검증했다.


## 2026-11-08 — Task 조회 화면의 부모 수명과 응답 검증

1. **컴포넌트 key는 데이터의 소유 범위를 정한다.** WorkspaceBrowser가 공간 ID로 ProjectBrowser를, ProjectBrowser가 Project ID로 TaskBrowser를 구분한다. 부모 전환 때 하위 인스턴스와 요청이 종료된다. 같은 Project 이름 변경은 key를 바꾸지 않아 Task 선택을 유지할 수 있다. 대안인 단일 큰 컴포넌트/전역 상태는 부모별 초기화 규칙을 더 많이 관리해야 한다.
2. **취소와 결과 적용 검사는 함께 필요하다.** AbortController는 진행 요청을 중단하지만 이미 도착한 응답/테스트의 취소를 따르지 않는 Promise까지 자동으로 없애지는 않는다. 새 선택/페이지 전에 이전 요청을 abort하고, await 뒤 signal.aborted를 검사한다. 실패한 목록은 빈 목록과 구분하며 재시도는 명시적인 GET이다.
3. **타입 선언은 HTTP 데이터의 검사가 아니다.** response.json()을 unknown으로 받고 문자열 Long ID/두 부모/상태·우선순위/길이·페이지·정렬을 검사한 뒤 DTO 필드만 반환한다. 이는 화면의 데이터 혼입·표시 오류를 막는 경계이며, 접근 권한은 서버의 membership→부모 Project→Task 조건 검사가 보장한다. 설명은 일반 텍스트 보간으로 렌더링해 HTML을 실행하지 않는다.


## 2026-11-08 — Task 생성과 응답 유실

1. **역할은 작업 종류별로 다르다.** 같은 MEMBER가 기존 Project에 Task는 만들 수 있어도 Project를 생성/수정할 수는 없다. 부모 Workspace 응답의 myRole은 각각의 폼 표시 조건으로 전달하고, 브라우저가 역할을 요청 본문/헤더로 지정하지 않는다. 서버 Service가 실제 멤버십과 부모를 다시 검사한다.
2. **POST 성공과 뒤따르는 GET 성공은 별개다.** commit 후 201/DTO/Location을 확인하면 생성은 성공이다. 목록 조회가 실패해도 이를 되돌리거나 POST를 재전송하지 않으며 새 ID 상세는 별도로 조회할 수 있다. 새 탐색/생성이 생기면 이전 후속 조회의 자동 선택을 종료한다.
3. **생성 결과 불명은 서버 실패를 뜻하지 않는다.** 브라우저가 201을 받지 못해도 서버가 이미 commit했을 수 있다. 같은 제목을 허용하므로 GET에서 같은 제목을 찾은 것만으로 이전 POST 성공을 확정하지 않는다. 폼은 제목/설명/우선순위를 보존하고 저장을 잠그며 목록 확인은 GET만 수행한다. 명시적 초기화 뒤 새 수동 요청만 허용한다. 대안인 idempotency key는 별도 서버 계약/저장 정책이 필요해 이번에는 추가하지 않았다.


## 2026-11-08 — Task 수정 정책: 권한·충돌 단위·상태 경계

1. **같은 역할도 자원별 권한이 다르다.** MEMBER는 Task 생성/수정에 참여하지만 Project 관리 권한은 없다. 작성자/담당자 필드가 없는 현재 정책은 공간 내 모든 Task에 적용된다. 서버는 membership→Project 부모→Task 부모→역할 순서로 확인해 잘못된 자원이 403으로 존재를 드러내지 않게 한다.
2. **버전의 단위는 편집하는 Task다.** 부모 Project 버전을 쓰면 서로 다른 Task가 불필요하게 충돌한다. Task 자체 @Version은 한 Task의 세 필드를 함께 보호하고 요청 버전 비교 뒤 실제 UPDATE/commit 검증으로 경합을 검출한다. no-op도 이미 오래된 요청이면 409이며 no-op 검증 없이 성공 처리하면 변경 사실을 놓칠 수 있다.
3. **입력·업무·DB 쓰기 범위를 함께 좁힌다.** 제목/설명/우선순위 수정은 상태 전환과 분리한다. DTO는 status를 거부하고 Entity의 상태는 UPDATE 대상에서 제외하며 DB도 status UPDATE를 허용하지 않는다. 기존 상태를 TODO로 다시 설정하지 않는다. 향후 상태 변경은 허용 전이·이력/이벤트 원자성을 따로 정해야 한다.


## 2026-11-08 — Task 수정 구현: flush·commit과 최소 UPDATE

1. **요청 버전 비교 뒤에도 DB 검증이 필요하다.** 두 요청이 같은 version을 읽을 수 있다. @Version이 UPDATE의 id/version 조건과 증가를 관리하고, 한 트랜잭션만 commit된다. no-op은 UPDATE가 없으므로 OPTIMISTIC 잠금의 트랜잭션 종료 검증으로 중간의 다른 commit을 검출한다. flush 결과 DTO가 있어도 Controller는 트랜잭션 프록시 commit 성공 후에 응답해야 한다.
2. **JPA의 수정 컬럼과 DB 권한이 일치해야 한다.** Task status/project_id/created_at/id는 수정 매핑에서 제외하고 DB는 title/description/priority/updated_at/version만 허용한다. 허용 컬럼 개수만 세지 않고 원시 ACL/PUBLIC/GRANT OPTION과 실제 유효 컬럼 집합을 검사한다. 부분 권한을 자동 보충하면 관리자가 의도한 제한을 덮어쓸 수 있어 실패로 종료한다.
3. **새 migration은 과거 검증의 의미를 바꾸면 안 된다.** 현재 도구는 정확한 V1~V6를 요구하지만 V5 전환 테스트는 target 5를 명시한다. 미래 이력 거부 fixture는 새 정상 V6와 겹치지 않도록 V7로 옮긴다. Task version 응답 전환과 함께 기존 Vue 파서도 바꿔야 생성/조회 회귀를 막을 수 있다.


## 2026-11-08 — 실제 Task 수정 검증과 no-op

1. **DB 권한과 업무 인가는 별개다.** 앱 DB 계정은 승인된 Task 컬럼을 UPDATE할 수 있지만 요청별로 서버가 사용자/Workspace membership·Project/Task 부모·역할을 검사한다. 실제 MEMBER의 수정 200과 VIEWER의 위조 헤더 포함 403을 같은 앱 계정으로 확인했다.
2. **현재 버전의 no-op과 stale 요청을 구분한다.** 같은 내용을 보내도 version 0은 이미 오래돼 409, 최신 version 1은 200/no-op이다. OWNER/ADMIN의 허용 검사를 no-op으로 수행해 추가 데이터 변경 없이 권한을 확인했고, SQL 행 전체 비교로 version/updated_at이 그대로임을 검증했다.
3. **영수증은 재전송 판단을 돕는다.** 실제 변경 전에 PATCH_ATTEMPTED를 기록하고 200/DTO 확인 뒤 PATCH_CONFIRMED로 바꾼다. 응답이 불명확하면 이를 지우거나 무작정 재전송하지 않는다. 검증기의 진행 로그 오류와 migration/GRANT·업무 요청의 결과도 별도로 기록한다.


## 2026-11-08 — Task 편집 기준·우선순위와 충돌 복구

1. **서버 표시와 편집 기준은 다르다.** 최신 Task를 화면에 표시해도 현재 입력과 시작 version을 자동 교체하면 안 된다. Task ID key는 같은 Task의 입력을 유지하고 다른 Task/Project/공간으로 이동할 때 요청을 종료한다. 편집 중 props 갱신과 PATCH 본문 버전을 테스트로 구분한다.
2. **복구에서도 세 필드를 함께 비교한다.** 제목/설명뿐 아니라 우선순위만 바뀌어도 Task version이 증가한다. 409 후 최신 Task와 Workspace 역할을 함께 확인하고 서버 우선순위/내 선택도 비교한다. 명시적 최신 기준 채택은 draft를 유지하며 PATCH를 보내지 않는다. 별도 수동 저장에서만 새 버전을 사용한다.
3. **정상 HTTP 상태만으로 성공을 확정하지 않는다.** 200이어도 부모/ID·세 필드·status/버전이 다르면 결과 불명이다. 요청/응답 유실·5xx도 같은 잠금으로 처리하고 GET 내용 일치를 특정 요청의 성공으로 단정하지 않는다. 새 Role 조회 실패/VIEWER 전환은 기준 갱신과 재저장을 막는다.


## 2026-11-08 — Task 상태 전이와 이력의 원자성 설계

1. **전이 규칙·권한·충돌은 독립적이다.** 모든 상태 전이를 허용해도 VIEWER는 변경할 수 없고 stale Task version도 409다. 상태와 일반 수정은 같은 Task version을 공유한다. 같은 목표 상태라도 이미 오래된 요청이면 no-op으로 성공시키지 않는다.
2. **이력은 저장 결과와 함께 commit해야 한다.** 상태만 성공하고 이력 저장이 실패하면 '누가 어떻게 바꿨는지'가 빠진다. 상태 UPDATE와 최소 이력 INSERT를 같은 Service 트랜잭션에서 수행하고 저장/commit 실패를 모두 rollback한다. 이력 version은 일반 수정 때문에 건너뛸 수 있어 연속 번호가 아니며 과거 Task 상태를 허구 이력으로 채우지 않는다.
3. **명시적 SQL은 버전 검증도 명시적으로 맡는다.** native UPDATE에 JPA @Version 자동 처리를 기대하지 않는다. id/version 조건·증가·영향 행 수 확인과 관리 Entity refresh를 직접 설계한다. 이력 ID가 필요 없는 경로는 INSERT 고정 컬럼만 쓰고 generated keys/SELECT를 피하면 이력 조회/sequence 권한 없이 쓰기 경계를 좁힐 수 있다. 실제 동시성/권한 동작은 구현 단계에서 격리 DB로 검증한다.

## 2026-11-10 — 상태 이력 구현의 실제 경계

1. **native SQL은 @Version을 자동 처리하지 않는다.** 조건부 UPDATE의 영향 행 수 1을 직접 확인한 뒤 refresh로 관리 Entity의 버전/상태를 맞춘다. 일반 수정과 같은 DB version 조건을 사용해야 두 경로의 경합에서 덮어쓰기를 막을 수 있다.
2. **이력 성공은 예외 없음보다 강한 조건이다.** 이력 INSERT 0건도 실패로 처리한다. INSERT/FK/UNIQUE/권한 오류뿐 아니라 지연 트리거의 최종 commit 실패까지 같은 트랜잭션에서 전체 rollback해야 상태만 남지 않는다. 저장된 Task의 마이크로초 정밀도 시각을 이력에 사용한다.
3. **권한 검사와 다른 DB 제약의 실패를 구분한다.** identity 컬럼을 일반 값으로 UPDATE하는 테스트는 권한 검사 전에 SQLState 428C9가 날 수 있다. 허용되지 않은 컬럼 권한을 직접 확인하는 SQL과 no-row 권한 probe를 사용한다. 이력 ID를 조회하지 않는 INSERT는 이력 SELECT/generated keys·직접 sequence 권한 없이도 수행할 수 있다.

## 2026-11-10 — 실제 상태 변경·최소 이력 검증

1. **이력을 쓰는 권한과 읽는 권한은 분리된다.** flowdesk_app은 상태 변경 트랜잭션에서 이력 입력 여섯 컬럼만 INSERT하고 identity ID나 이력을 조회하지 않는다. 실제 요청은 성공했으며 결과 이력은 별도 관리자 읽기로 확인했다. 감사 확인을 위해 앱 SELECT/시퀀스 권한을 늘리지 않았다.
2. **버전은 화면 동작이 아니라 한 Task의 경합 경계다.** 상태가 version 1→2로 바뀌면 이전 일반 편집 version 1도 409다. 현재 버전의 같은 상태·일반 편집 no-op은 200이어도 시각/버전/이력을 늘리지 않는다. OWNER/ADMIN 허용 검증을 no-op으로 수행해 실제 상태 전환과 이력을 한 건으로 유지했다.
3. **실제 DB 검증에서는 수행 범위를 기록한다.** 전환 전에 PATCH_ATTEMPTED를 저장하고 200/DTO 확인 후 PATCH_CONFIRMED로 바꿨다. 불명확한 결과는 자동 재전송하지 않는다. 실제 로컬의 단일 전환/역할·권한 확인과 격리 DB에서 수행한 병렬·장애 주입/rollback 검증은 구분한다.

## 2026-11-10 — 하나의 Task 버전과 두 편집 폼

1. **서버 데이터와 편집 기준은 다르다.** 상태 저장으로 상세가 최신 버전이 돼도 일반 편집의 세 입력/시작 버전을 바꾸면 안 된다. 반대 방향도 같다. 두 폼은 서로 독립된 draft/base를 갖지만 서버의 한 Task version으로 경합해 오래된 요청은 409가 된다.
2. **늦은 조회도 과거 상태를 만들 수 있다.** 먼저 시작한 GET이 최신 저장보다 늦게 도착할 수 있다. 화면에서는 이전 버전 DTO를 버리고, 복구 폼도 자신이 알고 있는 버전보다 오래된 GET을 최신 기준으로 채택하지 않는다. 동시에 진행한 요청과 언마운트/선택 변경을 테스트한다.
3. **같은 상태의 GET은 요청 성공 증명이 아니다.** 실제 서버 commit 후 200 응답만 끊어도 DB에는 상태/이력이 남는다. 화면은 결과 불명 잠금과 선택을 유지하고, 최신 내용이 같아도 성공 처리·자동 재전송하지 않는다. 비교/명시적 기준 갱신은 PATCH를 보내지 않으며 별도 수동 저장에서만 새 버전을 쓴다.

## 2026-11-10 — 상태 이력 조회 계약·버전 커서

1. **변경 이력과 사용자 이름은 서로 다른 시점의 데이터다.** from/to·taskVersion·occurredAt·actor ID는 저장된 사건이고 displayName은 현재 사용자 조회 결과다. 과거 이름 스냅샷이 없으므로 이름이 바뀌면 과거 행의 표시도 바뀐다. 조회자의 현재 소속은 검사하지만 행위자의 현재 membership으로 기록을 숨기지 않는다.
2. **커서는 페이지 위치보다 기록의 경계다.** Task 버전 14/10을 읽은 뒤 새 16이 생겨도 beforeVersion=10은 8/3처럼 더 작은 버전만 읽는다. 일반 수정 때문에 버전이 건너뛰어도 문제없다. 커서는 권한 토큰이 아니며 매번 URL 부모/현재 소속을 검증한다. 임의 페이지 번호 이동·여러 요청의 DB 전체 스냅샷은 제공하지 않는다.
3. **컬럼 SELECT와 SELECT * 문법은 다르다.** 현재 7개 컬럼 모두에 SELECT를 줬다면 별표 조회도 가능할 수 있다. table-wide SELECT ACL을 피하는 목적은 나중에 추가된 컬럼까지 자동 허용하지 않는 것이다. 기존 INSERT 6개와 SELECT 7개의 원시/유효 ACL을 별도로 확인하고 앱 기동의 기존 SELECT 금지도 함께 전환해야 한다.

### Task 상태 이력 조회: projection·커서·컬럼 ACL — 2026-11-10

1. DTO projection은 응답에 필요한 이력/현재 표시 이름만 읽어 Entity/이메일 직렬화와 항목별 사용자 조회를 피한다. 행위자 membership을 JOIN하면 탈퇴자의 과거 이력까지 사라질 수 있으므로 조회자의 현재 소속 검증과 분리한다. native 시각은 드라이버 Java 타입/세션 timezone을 가정하지 않고 UTC 변환·Instant로 명시하며 마이크로초를 검증한다.
2. Task 버전은 이력마다 증가하지만 일반 수정 때문에 간격이 생긴다. 마지막 반환 버전보다 작은 값을 DESC/size+1로 읽으면 새 최신 기록이 추가돼도 다음 페이지 경계가 밀리지 않는다. 날짜/전역 identity나 연속 번호를 가정하지 않으며 여러 요청 전체의 스냅샷을 보장하지는 않는다.
3. 컬럼 SELECT를 현재 모든 컬럼에 줘도 table-wide SELECT ACL은 아니다. 현재 SELECT *는 가능하지만 미래 컬럼에는 권한이 자동 확장되지 않는다. 원시 ACL로 PUBLIC/GRANT OPTION/범위를, 유효 권한으로 실제 접근을 함께 검사하며 readOnly 트랜잭션과 DB 권한 제한도 별개다. 부분 권한은 앱 기동/관리자 도구에서 거부하고 자동 수선하지 않는다.

### Task 상태 이력 화면: 커서 페이지와 이전 응답 — 2026-11-10

1. 이력의 page number를 만들어 내지 않는다. API가 돌려준 `nextBeforeVersion`을 다음 요청의 exclusive boundary로 보내고 결과를 기존 목록에 붙인다. API 계약의 cursor와 UI의 append 경계를 같게 유지하면 새로운 최신 기록이 과거 페이지 사이로 밀리지 않는다.
2. 페이지가 끝났는지는 응답 `hasNext`로 판단한다. next cursor는 opaque range token이 아니며 형식/부모는 매번 검증한다. 이미 읽은 목록은 이전 페이지 추가 실패 때 보존하고 같은 cursor로 재시도한다.
3. Task 전환·status 최신화·component unmount 뒤 완료되는 이전 fetch는 AbortController와 generation 값으로 버린다. abort는 대역폭/작업 취소를 요청하고 generation은 취소가 늦거나 응답이 이미 완료된 경우 잘못된 화면 반영을 막는다. lazy-open은 필요할 때까지 첫 fetch를 미룬다.

### Workspace 멤버십과 과거 Task 담당자 — 2026-11-10

1. `assignee_id → users.id` FK는 계정 참조만 보장한다. 현재 Workspace membership은 할당 쓰기 시 Service가 검증해야 한다. 이를 membership 복합 FK로 대체하면 멤버 제거 시 기존 assignment를 유지할 수 없다.
2. Workspace에서 제거된 사용자의 기존 Task assignment는 기록으로 유지하지만 새로 지정할 수는 없다. 참조 무결성과 현재 접근 자격은 서로 다른 규칙이다. 멤버 제거와 동시 재할당의 경합은 제거 기능이 생길 때 잠금 경계를 정의해야 한다.
3. 마감일은 시각이 아니라 달력상의 날짜이므로 `LocalDate`/SQL `date`로 저장한다. UTC 자정의 timestamp로 가장하면 client timezone 변환에서 하루가 달라질 수 있다.

### Task 담당자·달력 날짜를 현재 멤버십으로 검증 — 2026-11-11

1. Task는 Project를 통해 Workspace를 유도하므로 assignee ID를 저장하기 전에 그 Workspace에 속하는지 확인한다. 요청자의 역할 검증을 먼저 해 조회 권한이 없는 사용자에게 candidate membership 검사를 제공하지 않는다.
2. `assignee_id` 외래 키는 User 참조 무결성을 유지하고, 할당 당시 membership 자격은 Service가 검사한다. membership FK와 달리 User FK는 탈퇴 후 과거 할당을 유지할 수 있다. 계정 삭제는 참조 중이면 RESTRICT된다.
3. DB table-level SELECT/INSERT는 미래 컬럼을 자동 포함한다. 컬럼별 권한으로 바꾸면 schema 확장 뒤 권한 범위가 암묵적으로 넓어지지 않는다. JPA가 실제 읽고 쓰는 모든 mapped/inserted 컬럼과 identity 반환 id를 명시하고 startup 검증으로 누락 권한을 잡는다.
4. 권한 기동 검사는 허용 목록과 금지 목록을 동시에 검사한다. 업무 UPDATE 컬럼은 정확히 허용하면서 `UPDATE,REFERENCES` 전체를 금지하면 정상 상태도 실패한다. 검증 조건을 세분화하고, 격리 DB에서 정상·부분 ACL을 각각 확인한다.

### 담당자 후보 조회와 실제 쓰기 인가 — 2026-11-11

1. 후보 목록은 입력 오류를 줄이는 UI 편의 데이터다. GET 시 membership 확인은 Task POST/PATCH 시점의 권한 확인을 대체하지 않는다. 후보 조회 뒤 멤버십이 바뀔 수 있으므로 쓰기 Service는 반드시 다시 검증한다.
2. DTO projection으로 ID·표시 이름·역할만 읽으면 User Entity 전체를 직렬화하지 않고 이메일 같은 불필요한 필드를 응답에서 제외할 수 있다. 사용 가능한 DB 컬럼 SELECT 권한도 실제 projection에 맞춰 좁힐 수 있다.
3. PostgreSQL column ACL은 JPA가 매핑한 현재 컬럼 전체에 명시적으로 허용할 수 있어 기존 ORM read를 유지하면서 미래 컬럼 권한 확장을 막는다. table-wide SELECT와 column SELECT가 섞인 권한 상태를 자동 수선하지 않고 명확히 거부해야 전환이 넓은 권한을 남기지 않는다.

### 담당자 후보 페이지와 보존된 기존 할당 — 2026-11-13

1. 후보 목록 페이지는 ID 오름차순으로 이어 붙이고 중복 ID를 거부한다. 페이지를 합치는 동안 membership 변경으로 중복이나 누락이 생길 수 있으므로 목록은 UI 힌트이고, 최종 쓰기 membership 검사는 서버에서 다시 수행한다.
2. 현재 멤버가 아닌 기존 assignee는 신규 선택 후보에서 빼되 Task 수정 화면에 기존 할당으로 보존한다. 서버도 동일한 assignee ID가 그대로 제출되면 historical assignment를 유지하고, 다른 ID로 변경될 때만 현재 Workspace membership을 요구한다. 해제는 허용한다.

### Workspace 초대·역할·제거 계약 — 2026-11-13

1. 초대 대기와 실제 membership은 다른 상태다. 초대 생성만으로 Workspace 접근권한을 주지 않고, 저장된 대상 actor가 코드를 수락하는 트랜잭션에서만 `WorkspaceMembership`을 만든다. random code 원문은 한 번 반환하고 DB에는 digest만 두어 DB 읽기 유출이 곧바로 초대 수락 권한이 되지 않게 한다.
2. 부분 UNIQUE 인덱스는 Workspace당 OWNER 최대 한 명만 보장한다. 동시 이전 요청이 0 OWNER 상태를 커밋하지 못하도록 Workspace 행 잠금과 기존 OWNER 강등/새 OWNER 승격을 단일 트랜잭션으로 결합하고 Service에서 정확히 한 명을 검사한다.
3. Membership 삭제는 Task assignee FK가 참조하는 User를 지우는 일이 아니다. 역사적 Task 할당과 상태 이력은 남고, 현재 membership 검사는 이후 재배정에만 적용한다. 삭제와 새 배정의 경합은 같은 Workspace 잠금 경계에 놓아 제거가 끝난 뒤 낡은 membership 조회로 재배정이 성공하지 않게 한다.

### Workspace 멤버십 관리 구현 — 2026-11-14

1. 동시성 제어는 데이터가 바뀌는 membership row만 잠그는 것보다 Workspace 부모 row를 먼저 잠그는 편이 안전하다. 아직 row가 없는 초대 수락과 새 담당자 지정도 같은 잠금 규약에 참여하므로 membership 제거와 stale read 경합을 막는다.
2. OWNER 이전은 한 트랜잭션 안에서도 부분 UNIQUE 인덱스가 중간 UPDATE에 반응한다. 기존 OWNER를 먼저 ADMIN으로 flush해 제약을 만족시킨 뒤 대상 멤버를 OWNER로 승격하고, 최종 OWNER 수가 1인지 확인한다.
3. PostgreSQL column ACL에는 column-level DELETE가 없다. 그래서 membership relation의 DELETE 권한은 테이블 단위로 필요하지만, 같은 테이블의 UPDATE는 `role`, `updated_at` 두 컬럼으로 제한한다. 앱 권한 검증기는 이 불가피한 relation 권한과 invitation의 컬럼별 DML을 구분해 검사한다.

### Windows 로컬 PostgreSQL 포트와 검증 데이터 정리 — 2026-11-19

1. Docker의 host port는 컨테이너 PostgreSQL port와 별개다. Windows가 특정 TCP 범위를 예약하면 컨테이너 안의 5432는 정상이어도 host의 5432 publish가 실패할 수 있다. 이 경우 사용 가능한 loopback host port(이번에는 15432)를 고르고 Compose 설정과 JDBC URL을 함께 맞춘다.
2. PostgreSQL identity/sequence의 `nextval`은 트랜잭션 롤백이나 테스트 행 삭제로 되돌아가지 않는다. 검증 데이터 행을 정리해도 ID sequence가 증가할 수 있으므로, snapshot에 담기지 않은 sequence 값을 임의로 rewind하지 않는다.

### Workspace 초대·멤버 관리 UI — 2026-11-19

1. 초대 코드 원문은 쓰기 응답에서 한 번만 받아 UI 메모리에 둔다. 서버에 listing endpoint가 없으므로 화면에서 벗어난 뒤 코드를 복구할 수 없고, 성공 응답을 못 받았을 때 같은 요청을 자동 재전송하면 기존 초대가 재발급으로 무효화될 수 있다.
2. Vue의 역할별 버튼은 사용자 안내일 뿐이다. 현재 membership/role은 각 HTTP 쓰기 트랜잭션에서 서버가 다시 검사하며, 로컬 dev-actor 설정은 로그인 기능을 대신하지 않는다. 초대를 수락할 때 server actor가 저장된 target account와 같아야 한다.

### 초대 대기 목록 — 2026-11-20

1. 만료는 DB status와 별개인 시간 판정이다. 만료된 row도 PENDING으로 남을 수 있으므로 목록은 PENDING row를 모두 반환하고 서버 시각으로 `expired`를 계산한다. 조회만으로 status를 바꾸거나 행을 청소하지 않는다.
2. 관리 목록에서 식별을 위해 이메일을 반환하더라도 초대 관리 권한을 가진 OWNER·ADMIN으로 접근자를 제한하고, code/digest/내부 User ID 같은 불필요한 자격·관계 값은 DTO projection에서 제외한다.
3. Vue도 HTTP JSON을 `unknown`으로 받아 계약된 7개 필드만 파싱한다. 발급 코드가 한 번만 존재하는 상태와 다시 조회 가능한 대기 목록 상태를 분리해, 재조회가 비밀 코드를 복구하거나 노출하지 않게 한다.

### 실제 UI와 결정적 fixture 검증 — 2026-11-23

1. 브라우저 UI 상호작용을 확인하면서 DB 쓰기를 피하려면 Vite 개발 서버가 테스트 대상 API 경로만 메모리 fixture로 응답하고 나머지 GET은 실제 백엔드로 전달할 수 있다. 이 방식은 Vue 표시·클릭·재조회 흐름을 확인하지만 backend DELETE/트랜잭션은 증명하지 않으므로 실제 HTTP/DB 검증과 구분해 기록한다.
2. iframe about:blank의 첫 load 이벤트에 의존하면 리스너 등록보다 이벤트가 먼저 발생하는 race가 생길 수 있다. 테스트 응답을 앱 부팅 전 서버 middleware 경계에서 제공하면 초기화 순서에 덜 민감하다.

### Project 보관과 동시 Task 쓰기 — 2026-11-23

1. 보관 상태와 Project 이름/설명은 같은 Project row/version에 속하므로 낡은 화면의 상태 변경을 같은 낙관적 version으로 검출할 수 있다. 요청이 읽은 version 검사는 오래된 client 상태를, DB의 @Version 조건은 읽기 이후의 경합을 차단한다.
2. 여러 Task가 같은 Project 아래 놓이므로 Project version만으로 Task 생성·수정과 archive 사이의 경합을 모두 막기는 어렵다. 모든 Task mutation과 archive가 같은 Workspace row lock을 먼저 얻으면 둘을 하나의 직렬 순서로 처리할 수 있다.
3. `projects`의 table-level SELECT에는 V10 신규 컬럼 읽기도 자동 포함된다. 쓰기는 별개라 `archived_at` UPDATE만 추가 grant하고 앱 부팅에서 archive 모드 권한을 확인한다.

4. Vue의 보관 필터는 기본 목록과 별도 query(`includeArchived=true`)로 동작하고, 보관/복구는 Project version을 포함한 명시적 PATCH다. 409 뒤에는 최신 Project와 Workspace 역할을 다시 읽되 자동 재전송하지 않아 오래된 UI가 상태를 덮어쓰지 않는다. 보관된 상세는 Task/이력 GET은 유지하고 변경 폼만 감춘다.

5. 미적용 migration이 있는 상태에서 독립 Flyway Validate는 대기 migration 때문에 실패할 수 있다. 먼저 Info로 적용/대기 상태를 확인하고, Migrate의 `validateOnMigrate`를 통해 기존 이력 검증과 신규 적용을 수행한 뒤 별도 Validate와 반복 Migrate 0건으로 결과를 확인한다. 오류가 났을 때 `repair`로 checksum을 덮어쓰지 않는다.

6. 이미 IDE backend/Vite가 표준 포트를 사용 중이면 브라우저 검증은 별도 loopback 포트와 임시 Vite proxy config를 써서 기존 프로세스를 보존할 수 있다. 브라우저 자동 검증은 UI state를 실제로 바꾸므로 끝에서 자원을 복구하고, 예상되는 version/updated_at 변경과 불변인 Task/activity 행을 나눠 확인한다.

### Task 보관 정책 설계 — 2026-11-23

Task soft archive는 상태 이력과 다른 정보다. `archived_at`/Task version은 자원의 조회·변경 가능 상태를 표현하고, status Activity는 TODO/IN_PROGRESS/DONE 전이만 기록한다. Project와 Task의 부모/자식 archive 동시성은 Project version만으로 처리할 수 없으므로 기존 Workspace row lock으로 Task command와 직렬화한다. [결정 계약](task-archive-api-v1.md).

### Task archive 구현 — V11·JPA·column ACL — 2026-11-23

1. PostgreSQL 컬럼 권한은 Hibernate가 내보내는 실제 DML 컬럼과 맞아야 한다. `archived_at`은 DB default NULL에 맡겨 INSERT 권한 없이 생성하고, archive UPDATE에서는 `@DynamicUpdate`로 변경된 `archived_at`, 기존 허용된 `updated_at`, `@Version`만 SQL에 포함시킨다.
2. JPA가 Entity를 hydrate할 때는 읽기 전용 경로에서도 새 매핑 컬럼을 SELECT한다. 그래서 V11 적용 뒤 column-scoped Task SELECT에는 `archived_at`을 추가하고, 별도 archive capability에서는 UPDATE 권한만 추가한다.
3. PostgreSQL `has_column_privilege`는 스키마 버전에 존재하지 않는 컬럼 이름을 주면 false 대신 `undefined_column` 오류를 낼 수 있다. 단계별 migration verifier는 privilege 검사 전에 `pg_attribute`로 컬럼 존재 여부를 확인해 구버전 상태를 정상 분류해야 한다.
