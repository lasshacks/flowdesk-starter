# FlowDesk 프론트엔드

> 2026-11-04 실제 로컬에 V2(version 컬럼)와 WORKSPACE_RENAME 최소 컬럼 UPDATE를 적용하고 새 JAR의 조회·수정·버전 충돌을 확인했다. 데이터는 5·3·6이며 기본 쓰기는 계속 비활성화다. 아래 개발 쓰기·Origin 설정을 명시한 실행에서만 생성/수정이 활성화된다. [수정 계약 및 적용 기록](../docs/workspace-rename-api-v1.md)을 따른다.


Vue 3 Composition API·TypeScript·Vite로 워크스페이스 목록·상세·생성과 이름 수정 폼을 연결했다. 응답 version을 문자열로 보존하고 409 충돌은 입력 유지·최신 정보 확인·명시적 편집 기준 갱신으로 복구한다. 기존 서버 연결 카드는 보조 영역에 둔다. 조회 상태와 생성 요청의 진행/거부/성공/결과 불명을 구분한다.

## 실행

먼저 [백엔드의 개발 전용 실행](../backend/README.md)을 따라 127.0.0.1:8080에 dev-actor 프로필과 서버 지정 seed 사용자로 실행한다. 기본 프로필은 업무 API가 404이므로 health UP만으로 목록 조회가 가능하다고 판단하지 않는다. 다른 PowerShell에서:

```powershell
cd D:\flowdesk-starter\frontend
npm ci
npm run dev
```

이번 작업에서 의존성 설치를 마쳤다면 `npm ci`는 생략해도 된다. 브라우저에서 `http://127.0.0.1:5173/`을 연다. 종료는 실행한 터미널에서 Ctrl+C다.

Volta가 package.json의 Node 24.22.2를 선택하며 package-lock.json이 의존성 버전을 고정한다. 별도 전역 Vue CLI는 필요하지 않다. IDE에서는 생성된 확장 추천 목록의 Vue (Official)를 사용할 수 있다.

## 요청 처리 흐름

생성 폼을 사용하려면 백엔드에도 별도 개발 쓰기 설정이 필요하다. 현재 로컬 DB에는 V2와 최소 INSERT/수정용 세 컬럼 UPDATE가 적용돼 있다. 일반 Run/조회 모드는 쓰기를 켜지 않는다. 기존 8080 서버를 해당 IDE/터미널에서 중지한 뒤 backend의 새 PowerShell에서 다음처럼 실행한다. 아래는 사용자가 직접 선택하는 개발 실행 예시이며 실제 인증이 아니다.

```powershell
$keys = @('FLOWDESK_DEV_ACTOR_EMAIL', 'FLOWDESK_DEV_WORKSPACE_WRITE_ENABLED', 'FLOWDESK_DEV_WRITE_ALLOWED_ORIGINS')
$previous = @{}
foreach ($key in $keys) { $previous[$key] = [Environment]::GetEnvironmentVariable($key, 'Process') }
try {
    $env:FLOWDESK_DEV_ACTOR_EMAIL = 'alice@example.test'
    $env:FLOWDESK_DEV_WORKSPACE_WRITE_ENABLED = 'true'
    $env:FLOWDESK_DEV_WRITE_ALLOWED_ORIGINS = 'http://127.0.0.1:5173,http://127.0.0.1:8080'
    .\gradlew.bat bootRun --args='--spring.profiles.active=dev-actor'
} finally {
    foreach ($key in $keys) { [Environment]::SetEnvironmentVariable($key, $previous[$key], 'Process') }
}
```

전역 환경변수/자격증명 파일에 개발 쓰기나 관리자 설정을 저장하지 않는다. 프론트는 127.0.0.1:5173에서 실행한다. 다른 포트라면 서버의 허용 Origin도 명시적으로 맞춰야 한다.

```text
이름 입력/제출 → WorkspaceCreateForm: 검증·진행 중 중복 차단
→ createWorkspace(): 이름만 POST /api/workspaces, 브라우저가 Origin 설정
→ Vite 프록시 → 개발 쓰기/출처 검사 → Controller
→ CommandService 단일 트랜잭션 → 공간+OWNER membership → 커밋
→ 201/Location/DTO 확인 → 생성 완료 표시 → 목록 0페이지 GET → 새 ID 상세 GET
```

- `WorkspaceCreateForm.vue`는 이름·진행/오류/성공 상태만 관리하고 부모에게 created/checkList를 전달한다. `WorkspaceBrowser.vue`가 목록 갱신과 새 ID 선택을 담당한다. 생성 뒤 목록 조회가 실패해도 생성 실패나 재전송으로 처리하지 않는다.
- 이름은 서버와 같은 공백 제거/Unicode 코드 포인트 1~100/제어 문자 검사로 검증한다. UTF-16 maxlength=100으로 이모지를 50개에서 자르지 않는다. 최종 검증은 서버가 담당한다.
- POST는 10초 제한·credentials=omit·no-store·redirect=error로 한 번만 전송한다. 사용자/역할/Origin 헤더를 직접 지정하지 않는다. 정확한 201·Location·이름/문자열 ID/OWNER 응답을 확인한다.
- 400/403/404/405/413/415는 거부 안내를 보이고 입력을 유지한다. 네트워크/시간 초과·5xx·잘못된 201은 생성됐을 가능성이 있어 결과 불명으로 처리한다. 자동 재시도하지 않고 재제출을 잠근다. 목록 확인은 GET만 실행하며 명시적 입력 초기화도 POST를 보내지 않는다.
- 목록에 이름이 안 보여도 미생성이라고 확정하지 않는다. 동일 이름을 허용하며 새 공간이 0페이지 밖에 있을 수 있다. 목록을 변조해 새 항목을 끼워 넣지 않고 새 ID 상세를 선택한다. 페이지를 닫거나 요청을 취소해도 서버 롤백을 보장하지 않는다. 서버 멱등키 지원은 아직 없다.

기존 조회 흐름은 다음과 같다.

```text
WorkspaceBrowser 마운트/페이지 이동/목록 새로고침
→ getWorkspaces(): GET /api/workspaces?page=0&size=20
→ Vite /api 프록시 → Spring dev-actor 접속 필터/입력 검사
→ Service 트랜잭션: 서버 지정 사용자 → 사용자 조건 membership 조회
→ JPA/Hikari/JDBC → PostgreSQL → DTO JSON → 런타임 형식 검사 → 목록

목록 선택 → getWorkspace(id): GET /api/workspaces/{id}
→ Service의 공간+사용자 membership 검사 → DTO → 상세 화면
```

- `src/api/workspaces.ts`: ID를 문자열로 유지하고 역할/필드/페이지/선택한 상세 ID를 런타임 검사한다. 5초 제한, 요청 취소, cache=no-store/credentials=omit를 적용한다. 사용자 ID/역할/토큰을 보내지 않으며 서버 오류 원문은 화면에 노출하지 않는다.
- `src/components/WorkspaceBrowser.vue`: 서버 데이터(page/detail)와 UI 상태(선택·로딩·오류)를 분리한다. 최초 목록만 자동 조회하고 상세는 사용자가 선택할 때 읽는다. 페이지 크기는 20이며 이전/다음 페이지와 현재 페이지 새로고침을 제공한다.
- 선택/페이지/새로고침이 바뀌면 이전 상세를 지운다. 이전 요청을 AbortController로 취소하고 응답을 적용하기 전에도 취소 여부를 확인해 늦은 응답을 막는다. 언마운트 때 진행 중인 요청을 취소한다.
- 상세 404는 미소속/미존재를 구분하지 않고 안내한다. 목록 오류(기본 모드 404 포함)는 빈 소속으로 바꾸지 않는다. 서버 오류 코드/HTTP 상태를 안전한 한국어 안내로 변환한다.
- 하나의 목록/상세 화면이므로 Router·Pinia·Axios·UI 라이브러리는 추가하지 않았다. 상세 URL 공유/브라우저 뒤로가기와 전역 캐시는 이번 범위가 아니다. 브라우저 사용자 선택 UI나 실제 인증도 추가하지 않는다.

기존 health 보조 영역의 흐름은 다음과 같다.

```text
HealthStatusCard 마운트 또는 다시 확인 버튼
→ getHealth(): fetch('/api/health'), Accept: application/json
→ Vite 127.0.0.1:5173의 /api 개발 프록시
→ Spring 127.0.0.1:8080/api/health
→ HealthController → HealthResponse → Jackson JSON
→ response.ok 및 실제 JSON 구조 확인
→ health / isLoading / error 상태 변경 → Vue 화면 갱신
```

- `src/api/health.ts`: HTTP 호출, 5초 제한, HTTP 오류 및 실제 응답 형식 검사.
- `src/components/HealthStatusCard.vue`: 조회 중·성공·실패·재시도 화면. 서버 응답과 UI 상태를 각각 관리하며 조회 중 중복 클릭을 막는다.
- `src/App.vue`: 워크스페이스 화면과 접이식 health 보조 영역 구성. 각 영역이 자신의 조회 상태를 관리한다.
- `vite.config.ts`: 루프백 바인딩, 5173 고정 포트와 /api 프록시. 이미 사용 중인 포트라면 자동 변경하지 않고 오류로 알려준다.
- 검증용 브라우저 프로필이 있는 `.cache`는 Git과 Vite 파일 감시에서 제외한다.

브라우저가 요청하는 출처는 프론트엔드 자신이다. Vite가 백엔드로 전달하므로 이번 개발 연결에는 백엔드 CORS 변경이 필요 없다. 이는 개발용 전달 경로이며, 배포 시 별도의 동일 출처 라우팅 또는 확정된 CORS 정책이 필요하다. `vite preview`는 배포용 API 라우팅 검증 수단으로 사용하지 않는다.

## 검증

```powershell
npm test
npm run build
```

- `npm test`: Vitest와 jsdom에서 Vue의 createApp으로 화면을 마운트한다. fetch는 모의 응답으로 대체하며 테스트만으로 실제 Spring 연결을 검증했다고 보지 않는다.
- 생성 폼 단계 테스트 58개: 기존 health 5/조회 API 17/조회 화면 9개와 생성 API 19/생성 폼·연결 8개다. 입력·진행 중 중복·201 계약·거부/결과 불명·응답 역전·취소·생성 성공 후 조회 실패/페이지 밖 선택을 확인한다.
- `npm run build`: vue-tsc 타입 검사 후 Vite가 dist를 생성한다. Vite의 TypeScript 변환과 타입 검사는 별개다.

실제 프록시 응답도 확인할 수 있다.

```powershell
Invoke-RestMethod -Uri 'http://127.0.0.1:5173/api/health'
```

2026-11-02에 테스트 5개, 타입 검사·빌드가 통과했다. 프론트 HTML과 프록시 API의 HTTP 200을 확인했고, 설치된 Chrome 헤드리스 브라우저에서 실제 UP 및 연결 정상 문구가 렌더링되는 것까지 확인했다. 스크린샷과 DOM은 무시되는 `.cache/browser-check`에 저장했다. 마지막 npm 설치 감사 결과는 취약점 0건이었다.

2026-11-03에는 npm test 31개와 npm run build(타입 검사 포함)가 통과했다. 실제 Chrome·Vite·로컬 Spring/PostgreSQL에서도 Alice/A, Eve/B 목록 렌더링과 클릭 후 상세 200, 브라우저 요청 차단에 따른 오류/재시도 복구를 확인했다. 390px 모바일 폭에서 가로 넘침이 없으며 desktop/mobile 스크린샷과 DOM은 `.cache/workspace-check`에 저장했다. 임시 검증 스크립트는 `.cache/workspace-browser-check.mjs`이며 제품 의존성이나 번들에 포함하지 않는다. 데이터·이력·relation ACL·시퀀스와 자격증명/V1 파일은 유지됐고 검증 서버/브라우저는 종료했다.

## 오류와 범위

2026-11-04 생성 폼 연결은 npm test 58개·npm run build 통과 후 실제 Chrome/Vite와 고유 Compose PostgreSQL에서도 검증했다. 정상 201/새 상세 선택과 모바일 배치, 중복 제출 한 번 전송, 커밋한 201 응답만 유실시킨 결과 불명 잠금/GET 확인/미재전송, 쓰기 비활성 405 처리를 확인했다. 격리 DB의 최종 5/4/7과 두 Alice OWNER 관계가 정상 생성+응답 유실 생성 두 건을 증명한다. 해당 DB/네트워크/볼륨과 검증 앱/Chrome은 정리했고 실제 로컬 5/3/6 데이터와 자격증명/V1은 유지했다. 스크린샷/DOM은 .cache/workspace-create-check, 임시 검증기는 backend/build/form-browser-verification/verify.ps1과 .cache/workspace-create-browser.mjs다. 백엔드/의존성 변경이 없어 기존 Java 199개/프록시 1개·PowerShell 전체 경로 52개는 반복하지 않았다.

- 백엔드가 꺼져 있으면 오류가 표시된다. 서버를 실행하고 다시 확인을 누른다. 이전 UP은 오류 후 화면에 남기지 않는다.
- TypeScript interface는 서버 응답을 런타임에 검증하지 않으므로 status가 비어 있지 않은 문자열인지 별도로 검사한다.
- 현재 health UP은 서버가 응답했다는 뜻이며 DB 연결이나 모든 업무 기능을 보장하지 않는다.
- Node·Java의 Windows 네트워크 허용 창이 조직 정책으로 비활성화되어 있어도, 이번 루프백 연결은 실제로 동작함을 확인했다. 다른 기기의 접속을 위해 호스트를 변경하거나 방화벽 정책을 수정하지 않았다.
- Router·Pinia·Axios·UI 라이브러리와 실제 인증은 추가하지 않았다. Vue는 기존 HTTP API를 호출하며 DB 연결·인가 책임은 서버에 둔다.


## 이름 수정·버전 보존 — 2026-11-05

목록/상세/생성의 Workspace는 이제 version을 포함한다. ID와 같이 정규 십진 문자열이며 0~Long.MAX_VALUE 범위를 검사한다. JSON number·누락·음수·선행 0·범위 초과는 잘못된 응답으로 거부한다. 화면에는 내부 버전 숫자를 입력시키지 않는다.

```text
목록 선택 → 상세 GET → WorkspaceRenameForm: 편집 시작 name/version + 사용자 draft
→ 이름 검증·진행 가드 → name/version만 PATCH
→ Vite/개발 쓰기·Origin → Controller → Service 소속/역할/버전 → DB @Version → commit
→ 정확한 200 DTO 확인 → 선택 상세·같은 ID의 현재 목록 행 갱신
```

- OWNER/ADMIN에만 수정 폼을 보여 준다. MEMBER/VIEWER는 읽기 안내만 보며 최종 권한 검사는 계속 서버다. 실제 로그인/사용자 선택은 추가하지 않았다.
- 성공 응답의 id/name/수정 가능 역할과 version을 검사한다. 이름 변경이면 시작 버전+1, no-op이면 같은 버전이다. 증가 계산은 BigInt로 하며 JSON은 문자열을 유지한다. 성공 시 이미 받은 DTO로 같은 ID의 목록 행을 교체한다. 서버가 ID로 정렬하므로 페이지/순서를 바꾸거나 새 행을 삽입하지 않는다.
- 409에서는 입력과 시작 버전을 유지하고 저장을 잠근다. ‘최신 정보 확인’은 GET만 한다. 현재 서버 이름을 확인한 후 ‘최신 정보 기준으로 다시 편집’을 눌러야 편집 기준이 갱신되며, 그 뒤에도 사용자가 ‘이름 저장’을 눌러야 PATCH한다. draft는 유지하며 자동 덮어쓰기/재전송하지 않는다.
- 최신 조회 실패면 입력·잠금을 유지하고 이전 조회값을 대체 버전으로 쓰지 않는다. 최신 역할이 MEMBER/VIEWER이면 다시 편집을 허용하지 않는다.
- 응답 유실/시간초과·5xx/잘못된 200은 ‘결과 불명’으로 잠그고 같은 GET·명시적 편집 절차로 확인한다. GET 이름이 입력과 같아도 특정 요청의 성공 영수증으로 단정하지 않는다. 400/403/404/405/409/413/415 거부는 안전한 안내로 구분한다.
- 같은 ID의 상세 DTO가 갱신돼도 폼을 version으로 remount하지 않는다. 편집 기준은 사용자 확인 전까지 바뀌지 않는다. 다른 공간 선택·페이지 이동·명시적 목록 새로고침으로 상세가 제거되면 draft도 종료하고 진행 요청을 취소한다. 취소/탭 종료는 DB 롤백을 보장하지 않는다.
- PATCH는 10초 제한, credentials=omit/no-store/redirect=error다. 사용자 ID/역할/Origin을 직접 보내지 않는다. 기존 생성·조회 계약도 version 필드를 보존하며 새 라이브러리는 추가하지 않았다.

이번 `npm test`는 7개 파일/101개 통과(기존 58+버전/수정 API 32+수정 폼/연결 11), `npm run build`는 타입 검사·Vite 성공이다. 실제 Chrome/Vite/격리 PostgreSQL에서도 단일 제출·실제 409/명시적 복구·커밋된 200 유실/미재전송/GET 확인·VIEWER 폼 미노출·쓰기 off 405·모바일 배치를 확인했다. 스크린샷/DOM은 .cache/workspace-rename-check, 임시 검증기는 backend/build/rename-browser-verification/verify.ps1과 .cache/workspace-rename-browser.mjs다. 실제 로컬 5/3/6·ID 3 version 1·전체 ACL/시퀀스/자격증명/V1/V2/JAR는 유지했다.


## Project 목록·상세 조회 — 2026-11-05

워크스페이스의 상세 조회가 성공하면 그 아래에 해당 공간의 Project 목록·상세가 표시된다. 네 역할 모두 조회하며 브라우저에서 행위자/사용자 ID/역할을 선택하지 않는다. 이후 아래 Project 생성 폼 단계에서 POST도 연결했다.

```text
WorkspaceBrowser의 공간 선택·상세 GET 성공
→ 공간 ID를 key로 가진 ProjectBrowser 생성/기존 요청 종료
→ projects.ts의 /api/workspaces/{workspaceId}/projects?page=0&size=20 GET
→ Vite /api 프록시 → 개발 접속 필터 → Controller
→ ProjectQueryService readOnly 트랜잭션/서버 행위자·부모 membership
→ workspace_id 조건 Repository/DB → 문자열 ID 요약 DTO → 목록
→ 프로젝트 선택 → 부모+Project ID 상세 GET → nullable 일반 텍스트 설명
```

- `api/projects.ts`: 문자열 ID/Long 범위, 부모 일치, 이름·설명 Unicode 길이/제어 문자, 페이지 echo/크기/정렬·중복 ID와 상세 ID를 런타임 검사한다. DTO 필드만 반환하며 서버 detail 원문은 사용하지 않는다. Java 공백 기준과 JavaScript trim의 차이도 고려한다.
- GET은 5초 제한·credentials=omit/no-store/redirect=error다. 프로젝트 이름으로 요청하지 않으며 고정 URL의 부모/ID를 검증한다. 실패를 빈 배열로 바꾸지 않는다.
- `ProjectBrowser.vue`: 서버 page/detail과 로딩/오류/선택 UI 상태를 분리한다. 목록 새로고침·페이지 이동은 상세 선택을 지우고, 목록과 상세는 각각 AbortController로 취소한다. 취소 후 늦은 성공/실패도 적용하지 않는다.
- `WorkspaceBrowser.vue`: 부모 상세가 유효할 때만 ProjectBrowser를 등록한다. 공간 ID key를 바꿔 공간 전환 시 페이지/선택/요청을 종료한다. 같은 공간의 이름 수정으로는 Project 목록을 다시 만들지 않고 제목만 갱신한다.
- 빈 첫 페이지/뒤 페이지, 설명 null, 목록·상세 오류/수동 재시도와 이전/다음 페이지를 구분한다. 404는 없는/접근 불가 자원을 같은 안내로 표시하며 최종 인가는 서버다.
- 설명은 `{{ }}` 텍스트 바인딩과 pre-wrap으로 줄바꿈을 유지한다. HTML/Markdown을 실행하지 않는다. 모바일에서는 목록과 상세를 세로로 배치하고 페이지 버튼에 명확한 접근성 이름을 유지한다.

기존 실행 방법을 사용한다. 실제 로컬은 V3/PROJECT_CREATE가 준비됐으며 조회에는 쓰기 플래그가 필요하지 않다. backend에서 서버 지정 행위자와 dev-actor 프로필로 실행한 다음 frontend에서 `npm run dev`를 사용한다. 예를 들어 backend PowerShell에서 `$env:FLOWDESK_DEV_ACTOR_EMAIL='alice@example.test'`를 설정하고 `./gradlew.bat bootRun --args='--spring.profiles.active=dev-actor'`로 실행할 수 있다. 앱 비밀번호는 기존 Git 제외 설정을 사용하며 브라우저에 전달하지 않는다.

검증 명령은 `npm test`, `npm run build`다. 결과/실제 브라우저 범위는 [작업 기록](../docs/local-setup.md)의 Project Vue 항목을 따른다. Router/Pinia/Axios·새 의존성·백엔드/DB 변경은 없다. 다음은 Project 생성 폼·POST 연결이다.


최종 조회 연결 검증: `npm test` 9파일/144개, `npm run build` 타입 검사/Vite 성공. 실제 Chrome/Vite/로컬 읽기 앱으로 Alice의 목록/상세·공간 전환/이전 응답 무시, 제어한 503·수동 복구, VIEWER 조회·Eve의 빈 공간, 1280/390 px 배치를 확인했다. API 22건 모두 GET이고 DB 전체 스냅샷·보호 파일 7개는 같았다. 검증용 앱/Vite/Chrome은 종료했으며 실제 로컬 데이터는 5/3/6/1을 유지했다. 스크린샷은 .cache/project-read-check에 보관한다.


## Project 생성 폼·POST — 2026-11-05

선택 공간의 OWNER/ADMIN에게 ProjectCreateForm을 표시하고, MEMBER/VIEWER는 조회와 생성 권한 안내만 제공한다. UI의 역할은 안내이며 Service의 매 요청 membership/역할 검사가 최종 권한이다. 기존 Workspace 생성/수정과 같은 개발 쓰기 플래그를 사용한다.

```text
ProjectCreateForm의 이름/선택 설명·진행 가드
→ Java 공백/Unicode 길이/제어 문자·CRLF 정규화
→ createProject: 부모 ID는 URL, JSON은 name/description 두 필드만 POST
→ Vite의 Origin 전달 → 개발 쓰기 필터/strict JSON Controller
→ Service의 서버 행위자·부모 OWNER/ADMIN → persist/flush → commit
→ 201/상대 Location/문자열 ID·부모·이름·설명 검증
→ 성공 안내 → 목록 0페이지 GET → 새 ID 상세 GET
```

- 이름은 1~100 코드 포인트, 설명은 선택/최대 2,000 코드 포인트다. CRLF/CR은 LF, 공백 설명은 null이다. HTML input maxlength의 UTF-16 길이를 업무 길이로 사용하지 않는다. 폼은 이름/설명 오류를 구분하며 서버도 같은 계약으로 검사한다.
- 진행 중 필드/버튼을 잠그고 submit 핸들러에도 가드를 둔다. POST는 10초 제한·credentials=omit/no-store/redirect=error이며 행위자/역할/JSON 부모·Origin을 직접 지정하지 않는다.
- 400/403/404/405/413/415 거부는 입력을 유지하고 안전하게 안내한다. 부모의 OWNER 역할로 폼이 보여도 서버 쓰기 설정이 off면 405가 될 수 있다.
- 네트워크·시간 초과·5xx·잘못된 201/Location/DTO는 결과 불명이다. 입력/잠금을 유지하고 자동 재전송하지 않는다. ‘프로젝트 목록 확인’은 GET만 하며 이름이 보여도 폼을 성공 처리하거나 잠금을 해제하지 않는다. 사용자가 명시적으로 입력을 초기화해야 새 입력/요청을 시작할 수 있다.
- 201 성공과 후속 GET 실패를 분리한다. 목록 실패 후에도 생성 성공 안내를 유지하고 새 상세를 별도 조회한다. 새 ID가 첫 페이지에 없으면 목록에 임의로 삽입하지 않는다. 연속된 새 생성/페이지 이동/종료가 발생하면 이전 후속 GET의 늦은 자동 상세 선택을 막는다.
- 공간 전환/언마운트는 진행 요청과 늦은 이벤트를 종료한다. 요청 취소가 서버의 이미 진행/완료한 트랜잭션을 롤백한다는 뜻은 아니다.

실제 로컬은 이미 V3/PROJECT_CREATE가 준비됐다. 생성 테스트를 직접 할 때는 backend PowerShell에서 개발 행위자와 쓰기를 명시적으로 활성화하고, frontend는 별도 터미널에서 `npm run dev`로 실행한다.

```powershell
$env:FLOWDESK_DEV_ACTOR_EMAIL='alice@example.test'
$env:FLOWDESK_DEV_WORKSPACE_WRITE_ENABLED='true'
$env:FLOWDESK_DEV_WRITE_ALLOWED_ORIGINS='http://127.0.0.1:5173'
./gradlew.bat bootRun --args='--spring.profiles.active=dev-actor'
```

`http://127.0.0.1:5173`에서 공간을 선택해 사용한다. 비밀번호는 기존 Git 제외 설정을 사용한다. 이번 구현 검증은 고유 격리 DB에서 수행했고 실제 로컬에 Project를 추가하지 않았다.

검증: `npm test` **11파일/194개**, `npm run build` 타입 검사/Vite 성공. 실제 Chrome/Vite/격리 PostgreSQL에서 한 번의 POST/목록·상세, 커밋된 201 유실·잠금/GET 확인 동안 미재전송, VIEWER 폼 미노출/조회·쓰기 off 405/입력 유지·모바일을 확인했다. 임시 검증기는 backend/build/project-create-browser-verification/verify.ps1과 .cache/project-create-browser.mjs, 캡처는 .cache/project-create-check다. 정리/보존/미실행 항목은 [실행 기록](../docs/local-setup.md)을 따른다. 다음은 Project 수정 API의 권한·충돌 정책 확정이다.


## 후속 Project 수정 계약 확정 — 2026-11-05

[수정 계약](../docs/project-update-api-v1.md)에서 이름·설명/OWNER·ADMIN/Project 자체 버전·409를 확정했다. 후속 백엔드 전환 시 Project 상세/생성 파서·타입은 version 문자열을 보존하고 생성 "0"을 검증하며 목록 요약은 유지해야 한다. 수정 폼은 시작 스냅샷과 draft를 분리하고 409/결과 불명 뒤 최신 GET·두 필드 비교·명시적 기준 갱신·별도 수동 저장을 사용한다. 이번에는 계약만 기록했으며 실제 Vue 타입/폼/동작은 바꾸지 않았다.


## Project version 응답 전환 — 2026-11-05

수정 API 도입과 함께 Project 상세/생성 DTO의 version을 0~Long.MAX_VALUE 정규 십진 문자열로 검증·보존한다. 생성 201은 version "0"까지 확인하고, 숫자/누락/음수/선행 0/범위 초과 버전은 거부한다. 조회의 잘못된 버전은 INVALID_RESPONSE, 생성의 잘못된 201은 결과 불명이다. 목록 ProjectSummary는 기존 id/workspaceId/name 세 필드를 유지한다. 수정 폼·PATCH client는 아직 추가하지 않았다.

백엔드 V4/권한 도구와 함께 격리 검증한다. 실제 로컬은 별도 V4/PROJECT_UPDATE 적용 후 새 앱을 기동해야 한다. 새 라이브러리/환경 파일 변경은 없다. 검증 명령은 npm test/npm run build이며 결과/실제 로컬 보존은 [작업 기록](../docs/local-setup.md)을 따른다.


## 실제 로컬 Project V4 준비 완료 — 2026-11-07

로컬 V4/최소 UPDATE와 새 백엔드의 수정·409/역할 경계는 실제 HTTP로 확인했다. 기존 조회/생성 파서는 version 문자열을 보존하며 현재 Project 1 version은 "1"이다. Vue 수정 폼/PATCH client와 충돌·결과 불명 복구 UI는 아직 없고 다음 작업에서 연결한다. 이번에는 프론트 소스를 변경하지 않았으므로 기존 203개 테스트/빌드는 반복하지 않았다. [실제 실행 기록](../docs/local-setup.md).


## Project 이름·설명 수정 — 2026-11-07

선택 상세 아래에 OWNER/ADMIN용 ProjectUpdateForm을 연결했다. 시작 Project name/description/version과 두 입력을 분리하고 updateProject가 PATCH의 두 필드/문자열 버전과 성공 DTO를 검증한다. 성공하면 현재 상세/같은 ID 목록 행만 갱신한다. 빈 설명은 삭제/null, no-op은 같은 버전이다.

409 또는 결과 불명(응답 유실·5xx·잘못된 200)은 입력을 보존하고 저장을 잠근다. ‘프로젝트 최신 정보 확인’은 Project/Workspace 역할 GET만 수행한다. 두 필드 비교 후 ‘프로젝트 최신 기준으로 다시 편집’을 명시적으로 선택해도 자동 저장하지 않으며 별도 저장을 요구한다. 조회 실패/권한 상실은 잠금을 유지한다. GET 값 일치는 이전 PATCH 성공 증명이 아니다. 다른 Project/공간 선택과 언마운트는 진행 요청/늦은 이벤트를 종료한다.

검증 명령은 `npm test -- --reporter=dot`, `npm run build`이며 실제 격리 Chrome/Vite/PostgreSQL 결과와 로컬 보존은 [실행 기록](../docs/local-setup.md)에 구분한다. 백엔드/SQL/권한·영구 .env는 변경하지 않았다.


최종 검증: **13개 파일/246개 테스트·타입 검사/Vite build 통과**. 실제 격리 Chrome에서 정상 저장/목록·상세 갱신, 별도 편집자와의 409/두 필드 비교·명시적 기준 갱신·수동 저장, commit된 200 응답 유실/미재전송, VIEWER 폼 미노출·쓰기 off 405/입력 유지와 1280/390 px 배치를 확인했다. 실제 로컬 전체 스냅샷과 자격증명/V1~V4/JAR 해시 8개를 보존했고 임시 자원도 정리했다. 캡처/요약은 Git 제외 .cache/project-update-check, 명령/상세는 local-setup.md를 따른다. 다음은 Task 데이터 모델·생성/조회 권한 범위 확정이다.


## Task 목록·상세 조회 — 2026-11-08

공간 → Project 선택 후 아래 작업 영역에서 제목/상태/우선순위 목록과 작업 상세/설명을 조회한다. 네 역할 모두 사용할 수 있다. `TaskBrowser.vue`는 목록/상세 요청과 지역 UI 상태, `api/tasks.ts`는 중첩 GET/문자열 ID·두 부모/enum/페이지·텍스트 검사·5초 제한을 담당한다. 페이지 크기는 20개다. 목록/상세 오류는 빈 목록과 구분해 재시도하고, 설명은 일반 텍스트로 표시한다. Project/공간 전환·목록 새로고침/페이지 이동은 이전 선택/요청을 종료한다.

현재 로컬 V5/TASK_CREATE를 사용한다. 기존 앱을 dev-actor 프로필/서버 지정 사용자로 실행하고 frontend에서 `npm run dev` 후 `http://127.0.0.1:5173`에 접속한다. 조회에는 개발 쓰기 flag가 필요하지 않다. 실제 로그인은 Phase 2이며 Task 생성 폼/수정·상태 변경은 후속이다.

검증: `npm test -- --reporter=dot` **15파일/297개**, `npm run build` 타입 검사·Vite 성공. 실제 Chrome/Vite/쓰기 off 새 JAR에서 Alice/VIEWER Task 조회·설명/한글 라벨, Eve의 다른 공간에서 Task 미노출, 503/재시도·부모 전환의 늦은 상세 종료와 1280/390px를 확인했다. 모든 API 29개는 GET이며 로컬 데이터/이력/ACL/시퀀스와 보호 파일 9개를 보존했다. Git 제외 검증기는 `backend/build/task-read-browser-verification/verify.ps1`/`.cache/task-read-browser.mjs`, 캡처/요약은 `.cache/task-read-check`다. [실행 기록](../docs/local-setup.md).


## Task 생성 폼·POST — 2026-11-08

선택한 Project의 작업 영역에서 OWNER/ADMIN/MEMBER에게 제목·선택 설명·우선순위 폼을 제공한다. 제목 1~200/설명 2,000 코드 포인트, 앞뒤 공백/설명 줄바꿈 정규화를 적용한다. 우선순위는 LOW/MEDIUM/HIGH·기본 MEDIUM이고 초기 상태는 서버 TODO다. status/사용자/역할/부모는 본문에 넣지 않는다. MEMBER의 Project 관리 권한은 유지한다.

TaskCreateForm → tasks.ts/createTask POST → 서버 기존 소속·부모/역할·트랜잭션 → 201/DTO·Location 검사 → 성공 안내/입력 초기화 → TaskBrowser의 목록 0페이지·새 ID 상세 조회 순서다. POST 성공은 후속 GET 실패와 구분한다. 중복 제출은 차단하며 네트워크/시간 초과·5xx/잘못된 201은 입력 유지/결과 불명 잠금이다. 목록 확인은 GET만 하며 명시적 입력 초기화 없이 저장을 다시 보내지 않는다.

실제 로컬에서 직접 생성하려면 기존 dev-actor 앱에 서버 사용자/쓰기 flag·Vite Origin을 명시한다. frontend는 `npm run dev`로 실행한다. 이번 자동 검증은 고유 격리 PostgreSQL에서만 POST했고 실제 로컬 Task 1은 보존했다.

```powershell
# backend; 기존 Git 제외 DB 설정 사용
$env:FLOWDESK_DEV_ACTOR_EMAIL='carol@example.test'
$env:FLOWDESK_DEV_WORKSPACE_WRITE_ENABLED='true'
$env:FLOWDESK_DEV_WRITE_ALLOWED_ORIGINS='http://127.0.0.1:5173'
./gradlew.bat bootRun --args='--spring.profiles.active=dev-actor'
```

검증: `npm test -- --reporter=dot` **17파일/355개**, `npm run build` 타입 검사/Vite 성공. 실제 Chrome/Vite/격리 DB에서 MEMBER 생성/우선순위·중복 차단/201 후 조회·commit된 응답 유실/잠금·GET 확인 중 미재전송·VIEWER 폼 미노출/조회·쓰기 off 405/입력 유지·1280/390px를 확인했다. 임시 자원/자격증명을 정리했고 로컬 전체 스냅샷·보호 파일 9개를 보존했다. Git 제외 `.cache/task-create-check`의 캡처/요약과 [실행 기록](../docs/local-setup.md)을 따른다. 다음은 Task 수정 API의 필드·권한·충돌 정책 확정이다.


## 후속 Task 수정 계약 확정 — 2026-11-08

[Task 수정 계약](../docs/task-update-api-v1.md)에서 제목/설명/우선순위·OWNER/ADMIN/MEMBER·Task 자체 version/409를 확정했다. 후속 백엔드 전환 시 Task 상세/생성 파서는 version 문자열을 검증·보존하고 생성 "0"을 확인해야 한다. 목록 요약 여섯 필드는 유지한다. 수정 폼은 편집 시작 세 필드/버전과 draft를 분리하고 409/결과 불명 시 최신 Task/Workspace 역할 조회·비교·명시적 기준 갱신·별도 수동 저장을 사용한다. 이번에는 문서만 변경했고 현재 타입/파서/폼·API 구현은 아직 바꾸지 않았다.


## Task version 응답 전환 — 2026-11-08

Task 수정 API/V6와 함께 Task 상세·생성 타입/파서는 version을 0~Long.MAX_VALUE 정규 십진 문자열로 검증·보존한다. 숫자/누락/음수/선행 0/범위 초과는 거부한다. 생성 201은 초기 "0"을 확인하며 잘못된 201은 결과 불명으로 처리한다. 목록 TaskSummary는 기존 여섯 필드 그대로다. 수정 폼/PATCH 클라이언트는 아직 추가하지 않았다.

새 백엔드와 프론트는 V6/필요 권한을 적용한 앱을 사용해야 한다. 실제 로컬 V6 적용은 이번 범위에서 실행하지 않으며 기존 V5를 유지한다. 새 라이브러리·영구 .env 변경은 없다. [수정 계약](../docs/task-update-api-v1.md), [검증 결과](../docs/local-setup.md).

Task 버전 응답 전환 검증: **17파일/364개·타입 검사/Vite build 통과**. 기존 생성/조회 화면 회귀와 새 문자열 버전 검사를 확인했다. 백엔드 446개/실제 Vite 프록시 1개·PowerShell 105개도 통과했고 실제 로컬 V5는 보존했다. 새 앱은 후속 V6/필요 권한 적용 뒤 기동한다. Vue 편집 폼은 별도다.


## 실제 로컬 Task V6 준비 완료 — 2026-11-08

로컬 V6/최소 UPDATE와 새 백엔드 Task 수정·409/역할 경계가 실제 HTTP로 확인됐다. 기존 Task 상세/생성 파서는 version 문자열을 보존하며 현재 Task 1 version은 "1"이고 priority HIGH/status TODO다. Vue 수정 폼/PATCH 클라이언트와 충돌·결과 불명 복구 UI는 다음 작업에서 연결한다. 이번은 로컬 실행 검증이라 프론트 소스를 바꾸지 않았고 기존 364개/빌드는 반복하지 않았다. [실행 기록](../docs/local-setup.md).


## Task 제목·설명·우선순위 수정 — 2026-11-08

Task 상세 아래에 OWNER/ADMIN/MEMBER용 TaskUpdateForm을 연결했다. 편집 시작 Task/문자열 version과 제목/설명/우선순위 입력을 분리하고 updateTask가 nested PATCH의 세 필드/버전과 200 DTO를 검증한다. 성공하면 현재 상세와 같은 ID 목록만 갱신한다. 설명 비움은 삭제/null, 우선순위만 바꿔도 다음 버전, 정규화 no-op은 같은 버전이다. 상태 선택은 제공하지 않는다.

409 또는 결과 불명은 draft/시작 버전을 보존하고 저장을 잠근다. ‘작업 최신 정보 확인’은 Task + Workspace 역할 GET만 수행한다. 서버/입력의 세 필드를 비교하고 ‘작업 최신 기준으로 다시 편집’을 명시적으로 선택해도 저장하지 않으며 별도 수동 저장을 요구한다. GET 값 일치는 이전 PATCH 성공 증명이 아니다. 조회 실패/VIEWER 전환은 잠금을 유지하고 선택/부모 종료는 진행 요청과 늦은 이벤트를 종료한다.

검증: `npm test -- --reporter=dot` **19파일/415개**, `npm run build` 타입 검사/Vite 성공. 실제 Chrome/Vite/격리 PostgreSQL에서 MEMBER 정상 저장·목록/상세/우선순위, 다른 편집자와 409·세 입력/비교·명시적 기준/수동 저장, commit된 200 유실/미재전송·VIEWER/405·1280/390px를 확인했다. 실제 로컬 V6/Task 1 version 1·HIGH/TODO와 보호 파일 10개를 보존했고 임시 자원/자격증명을 정리했다. 캡처/요약은 Git 제외 .cache/task-update-check, [명령·실제 결과](../docs/local-setup.md)를 따른다.


## 후속 Task 상태 변경 정책 — 2026-11-08

[상태 계약](../docs/task-status-api-v1.md)에서 세 상태 간 모든 전이·세 역할·최소 Activity 동시 저장을 확정했다. 예정 상태 PATCH는 별도 /status의 status/version만 받으며 일반 편집과 같은 Task version을 공유한다. 같은 상태의 현재 버전은 no-op이고 stale은 409다. 기존 여덟 필드 Task/여섯 필드 목록을 유지한다. 향후 상태 UI도 편집 기준 자동 교체/자동 재전송 없이 최신 Task/역할 조회·명시적 복구를 사용한다. 이번에는 문서만 변경했고 상태 UI·요청 함수와 이력 조회/화면은 아직 없다.

### 상태 API 구현 이후의 Vue 범위 — 2026-11-10

백엔드 상태 변경 API·V7·최소 권한 도구를 구현했다. 이번에는 Vue 소스/화면을 변경하지 않았다. 기존 일반 수정은 title/description/priority/version만 보내고 상태 변경과 같은 Task version을 공유한다. 상태 UI/POST가 아닌 전용 PATCH 연결은 실제 로컬 V7/권한 적용 후 별도 작업이다. [구현·검증 기록](../docs/task-status-implementation.md)을 따른다.

### 실제 로컬 상태 API 준비 완료 — 2026-11-10

V7/최소 권한과 새 JAR의 실제 상태 변경 검증을 완료했다. 현재 Task 1은 IN_PROGRESS/version "2"다. 기존 일반 편집에서 version "1"을 유지한 요청은 409가 되어 최신 Task/역할 확인과 명시적 기준 갱신이 필요하다. 이번 작업은 프론트 소스/화면을 변경하지 않았다. 다음은 상태 UI와 전용 PATCH·공유 버전/409 복구 연결이다. [기록](../docs/local-setup.md).

### Task 상태 선택·공유 버전/409 복구 — 2026-11-10

TaskStatusForm/changeTaskStatus를 상세에 연결했다. OWNER/ADMIN/MEMBER에게 할 일/진행 중/완료 선택을 제공하고 status/version 두 필드만 전용 PATCH에 보낸다. 일반 폼과 각자 시작 Task/문자열 version을 유지해 한 폼의 저장이 다른 폼의 입력/버전을 자동 갱신하지 않는다. 성공 DTO는 목록/상세만 갱신하고 이전 버전의 늦은 갱신은 버린다.

409/결과 불명은 입력/선택 유지·잠금 → 최신 Task + Workspace 역할 공동 확인/비교 → 명시적 기준 갱신(선택 유지, PATCH 없음) → 별도 수동 저장이다. 시작/이미 표시된 버전보다 오래된 GET은 채택하지 않고, VIEWER 전환/조회 실패는 잠금을 유지한다. 같은 상태가 GET에 보여도 이전 요청의 성공으로 처리하거나 자동 재전송하지 않는다. 일반 폼의 비교에도 시작/서버 상태를 추가했다.

`npm.cmd test`: 21파일/479개 통과. `npm.cmd run build`: 타입 검사/Vite build 성공. 실제 Chrome/Vite/고유 PostgreSQL에서 정상 저장/목록/상세·양방향 교차 폼 409·상태끼리 충돌·수동 복구·커밋 응답 유실·VIEWER/405·1280/390px를 확인했다. 실제 로컬 DB·백엔드/SQL/JAR/패키지는 보존했다. [설계/파일](../docs/task-status-ui.md), [실행 기록](../docs/local-setup.md).

### 상태 이력 조회 계약 확정 — 2026-11-10

Task별 최신순 버전 커서·기본 20/최대 100·actor ID/현재 표시 이름·네 역할의 과거 전체 조회를 확정했다. 현재 프론트 이력 화면/API 호출은 없다. 후속 응답은 부모/Task ID·items·beforeVersion/size·hasNext/nextBeforeVersion이며 버전은 문자열이다. 일반 Task 목록의 page 번호와 별도 계약이다. [상태 이력 계약](../docs/task-status-history-read-api-v1.md)을 따른다.

## Workspace 초대·멤버 관리 UI

선택한 Workspace의 ‘멤버 목록 열기’는 필요할 때만 paged member GET을 실행한다. OWNER/ADMIN에게 기존 계정 초대 발급·현재 화면에서 받은 초대 취소, 일반 멤버 역할 변경·제거를 표시하고 OWNER만 소유권 이전을 사용할 수 있다. 모든 실제 권한 검사는 API Service가 수행한다.

초대 코드는 발급의 유효한 201 응답에서 한 번만 메모리에 표시한다. OWNER/ADMIN은 초대 대기 목록을 새로 조회해 발급 뒤 화면을 벗어난 초대도 확인·취소할 수 있다. 만료된 PENDING은 만료로 표시하며 ACCEPTED/REVOKED는 목록에 나오지 않는다. 목록 API와 client parser는 보안상 코드 원문·digest·내부 대상 User ID를 반환/허용하지 않는다. 발급 응답 유실/잘못된 응답은 결과 불명으로 잠그고 자동 재전송하지 않는다.

초대 수락 입력은 Workspace 선택 여부와 무관하게 표시한다. 현재 seed actor가 초대 대상이어야 하므로 수락할 사용자의 `FLOWDESK_DEV_ACTOR_EMAIL`로 백엔드를 실행해야 한다. 수락 201이면 목록을 다시 불러와 대상 Workspace 상세로 이동한다. 실제 로그인·이메일 발송·다중 actor 전환 UI는 구현하지 않았다.

요청 흐름:

```text
초대/역할/제거/이전 → WorkspaceMembershipManager → workspace-membership.ts
→ Vite /api 프록시 → dev-actor 쓰기·Origin filter → MembershipController
→ Service 권한/row lock/트랜잭션 → Repository → PostgreSQL
→ 엄격한 응답 확인 → 현재 멤버 상태 갱신

초대 목록/취소 → WorkspaceMembershipManager → workspace-membership.ts
→ Vite /api 프록시 → dev-actor Controller/Service의 OWNER·ADMIN 검사
→ PENDING projection 또는 기존 DELETE → PostgreSQL
→ 엄격한 필드 검사 → 초대 목록 갱신

수락 코드 → WorkspaceInvitationAcceptForm → POST accept
→ 서버 지정 target actor 확인 → membership+초대 소비 트랜잭션
→ 201 확인 → Workspace 목록 GET → 새 멤버십 상세 GET
```

각 write는 한 번만 전송한다. 4xx는 서버의 확정 거부로 표시한다. 통신 실패/5xx/성공 응답 파손은 결과 불명 잠금이며, 사용자가 상태를 확인했다고 명시할 때만 다시 입력할 수 있다. 이 기능은 실제 인증이 아니라 로컬 개발 도구다.

이번 연결의 `npm.cmd test -- --run`은 24파일/507개 통과, `npm.cmd run build`는 vue-tsc·Vite 성공이다. 테스트에서 대상/만료 표시·취소·목록 재조회와 VIEWER 목록 미요청을 확인했다. 2026-11-19 실제 Chrome/Vite/로컬 DB에서 초대 수명주기와 Alice→Bob→Alice 소유권 이전을 확인했고 390px에서 가로 overflow가 없었다. 2026-11-20 새 목록의 실제 Vite proxy HTTP GET/POST/DELETE/GET도 확인했다. Chrome DOM 자동 확인은 DevTools 연결 종료와 headless dump timeout으로 마치지 못했다. 다음 한 작업은 **Chrome에서 목록·만료 표시·취소 버튼을 눈으로 확인**하는 것이다. 상세는 [초대 목록 구현 기록](../docs/workspace-invitation-list-implementation.md)과 [로컬 실행 기록](../docs/local-setup.md)을 따른다.
