# 첫 워크스페이스 조회 API 계약과 행위자·인가 경계

- 날짜: 2026-11-03
- 상태: 조회 API·개발 경계 구현, 신규 격리 검증 42개/전체 빌드·JUnit 138개 통과. 실제 로컬 Alice/Eve 개발 모드 기동·HTTP 조회와 접근 차단 확인 완료.
- 목표: 기존 조회 Repository로 첫 HTTP 조회를 구성할 응답·오류·행위자·소속 검사·검증 범위를 정한다.
- 완료 기준: 보안 정책의 사용자 선택과 API/Service 계약, 구현 후 수용 테스트를 기록한다.
- 기존 기준: [요구사항](requirements.md), [V1 모델](domain-model-v1.md), [JPA 조회](jpa-read-model-v1.md), [seed](dev-seed.md).

## 사용자와 확정한 정책

| 항목 | 선택 | 상태 |
|---|---|---|
| 첫 범위/역할 | 내 공간 목록과 소속 공간 상세. OWNER/ADMIN/MEMBER/VIEWER 모두 기본 정보 조회 | 사용자 확인 완료 |
| 행위자 식별 | 명시적으로 켠 로컬 개발 모드에서 서버 설정의 seed 사용자 1명 사용. 실제 인증은 Phase 2 | 사용자 확인 완료 |
| 상세 접근 실패 | 미소속과 미존재 모두 동일한 404 | 사용자 확인 완료 |

세 정책은 사용자 답변으로 확정했다. 페이지 범위·DTO 필드·오류 표현 등 구현 기본값은 아래 계약을 따른다. 전체 역할별 쓰기/초대/프로젝트 권한 매트릭스를 확정한 것은 아니다. 결정 근거는 [ADR-022](decisions.md#adr-022--첫-워크스페이스-조회-api와-개발-행위자인가-경계)에 기록한다.

## 포함 범위와 대안

- 구현 범위: GET /api/workspaces, GET /api/workspaces/{workspaceId}, 응답 DTO, 조회 Service, 개발 행위자 경계, 해당 경로의 오류 응답, 격리 DB/API 테스트.
- 제외: 회원가입/로그인/JWT/MFA, 공간 생성·수정·탈퇴, 멤버 목록/초대, 사용자 목록, Vue 화면, 새 테이블/V2, 앱 DB 권한 확대.
- 목록만 먼저 구현하면 변경량은 줄지만, 타 공간 ID를 직접 요청하는 상세 접근 차단은 다음 작업으로 남는다.
- 실제 인증을 앞당기는 대안은 안전한 다중 사용자 식별을 제공하지만 Phase 2의 로그인·자격증명·Security 설계를 먼저 해야 한다.
- 선택 이유: 목록과 상세를 함께 설계해 사용자 조건 조회와 직접 ID 접근의 소속 검사를 모두 검증한다. 로그인 단계는 유지하면서 개발 모드의 서버 지정 행위자로 검증하고, 미소속 404로 공간 존재 여부의 구분을 줄인다.

## HTTP 계약

성공은 application/json이다. JPA Entity나 Spring의 Slice 객체를 그대로 직렬화하지 않고 전용 DTO로 변환한다.

### GET /api/workspaces?page=0&size=20

서버가 식별한 행위자의 멤버십에 연결된 공간만 workspace ID 오름차순으로 반환한다.

```json
{
  "items": [{"id": "101", "name": "FlowDesk Demo A", "myRole": "OWNER"}],
  "page": 0,
  "size": 20,
  "hasNext": false
}
```

- ID 101은 형식 예시이며 실제 seed ID를 가정하지 않는다. DB/JPA는 Long, JSON의 id는 양의 십진 문자열로 정한다. Vue에서도 문자열로 취급해 숫자 정밀도 변환을 피한다.
- page 기본 0, 허용 0~10000. size 기본 20, 허용 1~100. 범위를 벗어나거나 정수가 아니면 400이며 자동 보정하지 않는다. page 상한은 첫 offset 조회의 비용을 제한하는 구현 기본값이다.
- 소속 없음/범위 안의 마지막 페이지 이후는 200, items 빈 배열, hasNext=false다. totalElements/totalPages는 제공하지 않는다.
- userId/actorId/role/sort 등 계약에 없는 쿼리 키와 중복 키는 400으로 거부한다. 클라이언트가 조회 대상 사용자를 지정하지 않는다.
- 목록 요소는 id/name/myRole만 포함한다. 이메일·다른 멤버·membership ID는 노출하지 않는다.

### GET /api/workspaces/{workspaceId}

```json
{"id": "101", "name": "FlowDesk Demo A", "myRole": "OWNER"}
```

- workspaceId는 1~9223372036854775807의 정수 ID다. 잘못된 형식/범위는 400이다. 상세 경로에는 쿼리 파라미터를 받지 않는다.
- 해당 공간과 행위자의 membership을 조회해 허용된 역할인지 확인한 뒤 공간 기본 정보를 DTO로 만든다.
- 미소속과 미존재는 같은 상태/코드/안내 문구로 응답한다. 공간을 먼저 조회해 존재 여부에 따라 다른 오류를 만들지 않는다. 응답 시간의 완전한 동일성까지 보장하는 정책은 아니다.

### 오류

새 업무 경로는 Spring ProblemDetail 기반 application/problem+json을 사용한다. 기존 health 응답은 해당 업무 오류 계약의 대상이 아니다.

```json
{
  "type": "about:blank",
  "title": "Not Found",
  "status": 404,
  "detail": "Workspace is unavailable.",
  "instance": "/api/workspaces/101",
  "code": "WORKSPACE_NOT_FOUND"
}
```

| 상황 | 상태/코드 |
|---|---|
| 잘못된 ID/페이지/크기/미지원 또는 중복 쿼리 | 400 / INVALID_REQUEST |
| 미소속 또는 미존재 | 404 / WORKSPACE_NOT_FOUND |
| DB 연결/일시적 사용 불가 | 503 / SERVICE_UNAVAILABLE |
| 예기치 않은 서버 오류 | 500 / INTERNAL_ERROR |

detail에는 SQL·스택·DB 계정/주소·행위자 이메일·설정값·입력 원문을 포함하지 않는다. DB 장애를 소속 없음이나 빈 목록으로 바꾸지 않는다. HTTP의 지원하지 않는 메서드/Accept 처리는 표준 405/406을 따르며 새 오류 코드 전체 체계는 만들지 않는다.

근거: [Spring MVC 오류 응답](https://docs.spring.io/spring-framework/reference/6.2/web/webmvc/mvc-ann-rest-exceptions.html), [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457.html). 표준 필드에 code를 확장해 화면이 안내 문구를 파싱하지 않고 분기하게 한다.

## 로컬 개발 행위자 경계

- 기본 프로필에는 업무 Controller/개발 행위자 구성요소를 등록하지 않는다. health만 기존대로 두며, 업무 API를 켜기 위해 명시적인 dev-actor 프로필과 FLOWDESK_DEV_ACTOR_EMAIL 설정을 모두 요구한다. 자동 활성화나 기본 Alice 값은 없다.
- 개발 모드 시작 시 server.address가 127.0.0.1인지 확인한다. 미지정/와일드카드/다른 주소는 시작을 실패시킨다. 실제 수신 요청도 직접 연결 peer가 loopback인지 검사하며 전달 헤더를 신뢰하지 않는다.
- application-dev-actor.properties에서 server.forward-headers-strategy=none을 설정하며, 다른 전략이나 Tomcat remote-ip-header 설정이 있으면 시작을 거부한다. 개발 경계 필터가 비loopback peer를 차단하면 403 / DEV_ACCESS_DENIED로 응답한다. 공간의 미소속/미존재 404와 구분되는 접속 경계 오류다.
- 서버 설정 이메일은 trim/소문자 정규화 후 기존 users에서 찾는다. 비어 있거나 존재하지 않으면 개발 모드 시작을 실패시킨다. 생성·seed·비밀번호 발급을 자동 실행하지 않는다.
- 서버는 행위자 ID만 가진 불변 값으로 Service에 전달한다. 클라이언트 헤더/쿠키/쿼리/body의 사용자 ID·역할·Bearer 토큰으로 행위자를 바꾸지 않는다. 개발 모드는 인증 토큰을 검증한 것으로 간주하지 않는다.
- 요청마다 서버 설정에 해당하는 사용자 존재를 다시 조회하고, 사라졌으면 503 SERVICE_UNAVAILABLE로 실패시킨다. JPA Entity/멤버십/역할을 전역 캐시하지 않는다. 사용자를 바꾸려면 설정을 바꾸고 재시작한다.
- 개발 모드의 업무 응답에는 Cache-Control: no-store를 사용한다. 브라우저 사용자별 로그인 세션은 없고 모든 요청이 지정 행위자를 공유한다.
- 별도 CORS 허용을 추가하지 않는다. Vue 연결은 기존 Vite /api 프록시를 사용한다. 쿠키 인증·브라우저 자격증명 전송은 추가하지 않고 GET 조회만 제공한다. Phase 2의 인증 저장 방식/CORS/CSRF 결정이나 전역 CSRF 비활성화를 이 단계에 포함하지 않는다.
- loopback과 프로필은 사용자 인증을 대신하지 않는다. 같은 컴퓨터의 다른 프로세스도 지정 행위자로 접근할 수 있다. 외부 공개/터널/리버스 프록시를 통해 제공하는 모드로 사용하지 않는다.
- Phase 2에서는 검증된 인증 주체로 행위자 공급 부분을 교체한다. Service의 소속 검사는 계속 필요하다. 개발 프로필을 배포에 사용하는 방식을 승인한 것으로 해석하지 않는다.

## 요청과 트랜잭션 흐름

HTTP → 개발 모드/접속 경계 → Controller의 입력 검증 → 조회 Application Service의 readOnly 트랜잭션 → 서버 설정 기반 행위자 조회 → 소속 Repository → PostgreSQL → 트랜잭션 안에서 DTO 변환 → JSON.

- 목록: UserRepository.findByEmail → findByUser_IdOrderByWorkspace_IdAsc(Pageable). 기존 workspace EntityGraph를 사용하고 반환 Slice를 전용 응답으로 변환한다.
- 상세: UserRepository.findByEmail → findByWorkspace_IdAndUser_Id → 역할 확인 → membership.workspace 기본 필드 → DTO. LAZY 접근은 Service 트랜잭션 안에서 마친다.
- 인가의 최종 경계는 Service다. Controller가 임의 userId를 받아 넘기는 메서드는 만들지 않는다. 행위자 값의 출처를 HTTP 입력과 분리하되, 별도 범용 권한 프레임워크는 추가하지 않는다.
- readOnly와 DB SELECT 권한은 사용자별 인가를 보장하지 않는다. 목록은 사용자 조건이 있는 쿼리, 상세는 공간+사용자 조건이 있는 쿼리를 필수로 사용한다. WorkspaceRepository.findById만 호출해 반환하지 않는다.
- 세 DB 테이블의 SELECT 권한/기존 V1·seed는 유지한다. 조회 Service가 DDL/DML·마이그레이션·seed 작업을 호출하지 않는다.

## 구현 파일과 수용 기준

com.flowdesk 아래 실제 파일은 다음과 같다.

| 위치 | 책임 |
|---|---|
| identity/application/Actor, DevActorProvider, ActorUnavailableException | 서버 설정 검증·정규화, 요청마다 사용자 해석, 사라진 행위자 실패 |
| workspace/application/WorkspaceQueryService | readOnly 트랜잭션과 사용자 조건/소속 검사 |
| workspace/application/WorkspaceView, WorkspaceList | Entity/Slice와 분리된 불변 조회 DTO |
| workspace/application/WorkspaceNotFoundException | HTTP에 의존하지 않는 소속/존재 실패 |
| workspace/api/WorkspaceController | 입력/중복·미지원 쿼리 검사와 두 GET 경로 |
| workspace/api/WorkspaceErrors | 안전한 ProblemDetail와 DB 장애/예기치 않은 실패 구분 |
| workspace/api/DevWorkspaceAccessFilter | 직접 peer 확인, 전달 헤더 미신뢰, no-store |
| resources/application-dev-actor.properties | 명시적 프로필에서 전달 헤더 해석 비활성화 |
| test/.../workspace/WorkspaceReadApiTest | Testcontainers DB+실제 HTTP/Service/접속 경계 검증 |

추가 라이브러리·Repository 메서드·DB 마이그레이션 없이 기존 Spring MVC/JPA로 구현했다. DTO는 Application Service의 결과 타입으로 두고 Controller가 그대로 응답한다. Service의 업무 예외를 API 예외 처리기가 HTTP로 변환한다.

1. 기본 모드에서 새 업무 API가 활성화되지 않고 기존 health는 유지된다. dev-actor만 켜거나 사용자 설정만 주는 것으로는 안전 경계를 우회할 수 없다.
2. 개발 모드에서 이메일 누락/없는 사용자/잘못된 bind 주소는 시작 실패다. 행위자 설정이 정상인 경우에만 업무 API를 사용한다.
3. 격리 DB의 Alice는 A만, Eve는 B만 조회한다. 서버 행위자는 설정/테스트 구성으로 바꾸며 클라이언트의 userId 헤더로 전환하지 않는다.
4. Bob/Carol/Dave도 A의 기본 정보 조회가 가능하고 응답 myRole이 각각 ADMIN/MEMBER/VIEWER다. 이 세 사용자와 Alice는 B를 조회할 수 없다. 역할은 해당 공간의 membership에서 판단한다.
5. 상대 공간 ID와 없는 ID가 같은 404 코드/문구를 반환하고, 사용자 이메일/공간 이름이 오류로 새지 않는다.
6. 빈 소속/빈 페이지, size/page 경계, ID 범위/문자열 오류, 미지원·중복 쿼리, 입력으로 행위자/역할을 바꾸려는 요청을 확인한다.
7. OSIV=false 상태에서 DTO 직렬화 성공, Entity 내부 필드 미노출, 목록 workspace N+1 미발생, DB 장애 시 안전한 503을 확인한다.
8. 격리 DB에서 실제 flowdesk_app 권한으로 검증하며 기존 테스트/빌드를 실행한다. 업무 데이터/V1/권한/자격증명을 바꾸지 않았음을 확인한다. 실제 로컬 HTTP 적용 확인은 구현·격리 검증 뒤 진행한다.

## 실행 검증

- backend에서 .\gradlew.bat test --tests 'com.flowdesk.workspace.*' --no-daemon --no-configuration-cache --console=plain: 신규 42개, 실패/오류/skip 0.
- .\gradlew.bat build --no-daemon --no-configuration-cache --console=plain: BUILD SUCCESSFUL (3분 15초). 기존 96개+신규 42개=138개, 실패/오류/skip 0. 실행 JAR에 API/개발 프로필이 있고 privileged runner/정책 SQL/Flyway/자격증명 파일은 없다.
- 실제 Testcontainers PostgreSQL에 기존 V1·seed·읽기 권한 도구를 적용한 뒤 flowdesk_app으로 앱을 시작한다. 테스트는 config import를 초기 단계부터 비워 로컬 .env를 가져오지 않고 메모리의 테스트 자격증명을 주입한다.
- 서버 지정 행위자별 HTTP 조회/역할·미소속/미존재·잘못된 입력·행위자 위조 헤더 무시·기본 모드 비활성·시작 설정 거부를 확인했다. 비loopback peer 차단은 mock servlet 요청으로 직접 필터를 검증한다.
- 목록의 공간 수를 늘린 상태에서도 actor 조회+membership/workspace Slice의 SQL 2개다. DTO 직렬화는 OSIV=false에서 성공했다. 테스트 변경 데이터를 정리한 뒤 전체 사용자/공간/멤버십/V1 이력 스냅샷이 원본과 같다.
- 격리 컨테이너만 pause/unpause해 DB 장애 503·복구를 검증했다. 닫힌 풀 연결은 Hibernate 트랜잭션 예외로 감싸질 수 있어 해당 JDBC 트랜잭션 실패도 503으로 변환한다. 격리 DB SELECT 회수에 의한 권한 오류는 안전한 500이며 권한 복원 뒤 200으로 회복한다.
- 위 구현/격리 단계에서는 실제 로컬 서버에 적용하지 않았다. 이후 아래 별도 요청으로 실제 로컬 HTTP 조회까지 확인했다. 기본 실행은 health만 제공하며 업무 API 사용에는 [명시적 개발 실행](../backend/README.md)이 필요하다. Vue 화면은 이번 범위가 아니다.

## 실제 로컬 개발 모드 확인 — 2026-11-03

이후 [Vue 목록·선택 상세 화면](../frontend/README.md)도 이 계약으로 연결했다. 프론트 31개 테스트·타입 검사/빌드와 실제 Chrome의 Alice/A·Eve/B 조회/오류 복구·모바일 배치를 확인했다. API/DB 계약과 서버 인가 정책은 변경하지 않았다.

- 사용자 요청으로 새 실행 JAR를 java -jar + dev-actor 프로필로 127.0.0.1:18081에 기동했다. 서버 설정의 사용자를 Alice/Eve로 바꿔 각각 별도 프로세스를 실행했고 검증 후 각 프로세스를 종료했다. 8080/기존 IDE 서버는 이 검증 대상이 아니다.
- Alice의 목록은 A(id 문자열 "1") 한 건, Eve는 B(id 문자열 "2") 한 건이며 myRole=OWNER다. ID는 DB/HTTP 실제 응답에서 읽었고 코드에 고정하지 않는다. 목록·본인 소속 상세는 모두 200, JSON DTO와 no-store를 확인했다.
- 상대 공간/없는 ID는 같은 404 코드·제목·문구, userId 쿼리/size 초과는 400, 위조 사용자/토큰/전달 헤더로도 지정 행위자는 유지됐다. 빈 다음 페이지는 200/빈 목록이고 두 health 경로는 각각 200/UP이다.
- 자식 앱은 flowdesk_app용 backend/.env만 읽고 상속된 관리자/migrator/Spring/서버 설정 환경은 제거했다. 부모 환경은 바꾸지 않았다. PostgreSQL의 앱 계정 세션과 실제 loopback 수신 프로세스도 확인했다.
- 전후 사용자/공간/멤버십·Flyway 이력·DB/schema/relation ACL/소유자·역할 속성·시퀀스 상태 스냅샷과 자격증명 3개/V1 파일 SHA-256이 같았다. 데이터 5/2/5를 유지했다. 별도 프로비저닝/마이그레이션/seed/쓰기 작업은 실행하지 않았다.
- 명령: backend에서 .\gradlew.bat bootJar --no-daemon --no-configuration-cache --console=plain 성공(7초, UP-TO-DATE). 루트에서 & ./backend/build/local-api-verification/verify-local-api.ps1 성공. 후자는 Git 제외 build 아래의 임시 검증물이며 제품용 실행기나 앱 JAR에 포함하지 않는다.
- PowerShell의 application/problem+json 본문이 byte[]일 수 있어 임시 검증기는 UTF-8로 디코딩한 뒤 JSON을 검사한다. 실제 API 코드는 수정하지 않았다. 새 코드 변경이 없어 기존 전체 138개/PowerShell 41개/Vue 검증은 반복하지 않았다.
