# Task 상태 변경 이력 조회 계약

- 날짜: 2026-11-10
- 상태: **세 정책 확정 및 API·최소 SELECT 도구 구현**. 격리 검증 결과는 [구현 기록](task-status-history-implementation.md)과 [실행 기록](local-setup.md)을 따른다. 실제 로컬 GRANT와 새 앱 GET도 확인했다. Vue 이력 화면은 별도다.
- 기준: [요구사항](requirements.md), [아키텍처](architecture.md), [기술 스택](tech-stack.md), [로드맵](roadmap.md), [상태 변경 계약](task-status-api-v1.md), [상태 UI](task-status-ui.md).
- 현재: V7을 유지하고 TASK_STATUS_HISTORY_READ 도구·조회 API를 구현했다. 실제 로컬에 TASK_STATUS_HISTORY_READ 7컬럼 SELECT를 적용했다. Task 1 IN_PROGRESS/version 2·상태 이력 1건을 새 dev-actor 앱의 GET으로 확인했다.
- 완료 기준: ADR-057의 조회 대상/행위자 노출/페이지 방식·HTTP·인가·최소 권한을 구현하고 격리 PostgreSQL에서 검증한다. 구현 선택은 ADR-058, 로컬 적용은 ADR-059에 기록했으며 Vue 이력 화면은 후속이다.

## 사용자와 확정한 정책

| 항목 | 선택 | 검토한 대안 | 상태 |
|---|---|---|---|
| 조회 권한/과거 범위 | 현재 소속 OWNER/ADMIN/MEMBER/VIEWER 모두, 가입 전/현재 탈퇴한 행위자 기록도 포함 | OWNER/ADMIN만 | 확정 |
| 행위자 노출 | ID·현재 표시 이름, 이메일 제외 | ID만 | 확정 |
| 페이지 | Task 버전 최신순·이전 버전 커서, 기본 20/최대 100 | 기존 page 번호 | 확정 |

표시 이름은 V7에 저장된 과거 이름이 아니다. 현재 users.display_name을 읽으므로 이름 변경 시 과거 이력의 표시도 바뀐다. 기록 당시 행위자의 Workspace 소속/역할이나 이름을 증명하는 응답이 아니다. V7의 사용자/Task FK RESTRICT와 기존 이력을 유지하며 이름·역할 스냅샷/사용자 삭제 정책은 이번에 추가하지 않는다.

## 공통 조회 범위와 처리 흐름

- GET: `/api/workspaces/{workspaceId}/projects/{projectId}/tasks/{taskId}/status-activities`.
- Task 한 건의 실제 상태 전이 목록만 제공한다. 생성/일반 수정/no-op/실패 이력·공간 통합 피드·이력 단건 API·전체 건수·검색/필터·실시간/알림은 포함하지 않는다.
- 기본 프로필 업무 경로는 기존 404, dev-actor의 서버 지정 사용자/loopback·Cache-Control no-store를 유지한다. GET은 개발 쓰기 설정과 독립적이며 클라이언트 사용자/역할을 신뢰하지 않는다.
- Controller 입력 검사 → 서버 행위자 → 현재 Workspace 소속 → Project id+workspaceId → Task id+projectId → 네 역할 조회 → 이력 조회 → DTO/200 순서다. 미소속/미존재/부모 불일치는 기존 TASK_NOT_FOUND의 같은 404다. 양쪽 Workspace 소속이어도 URL 부모가 틀리면 404다.
- 조회자의 현재 소속으로 판단하므로 신규 가입자는 가입 전 이력도 읽을 수 있고 조회자 본인이 탈퇴하면 읽을 수 없다. 이력 행위자의 현재 membership 유무로 기록을 필터링하지 않는다. 현재 소속 VIEWER도 200이며 역할에 따른 403은 추가하지 않는다. 기존 개발 접속 거부 403은 유지한다.
- 허용된 존재 Task의 이력이 없으면 200/빈 items다. 이력이 있는지 먼저 조회해 리소스 접근 가능성을 판단하지 않는다. 새로운 이력 단건 조회나 다른 Task의 cursor 원본 존재 여부 확인도 수행하지 않는다.
- 기존 400/403/404/503/500·안전한 오류를 사용한다. 조회 자체는 수정 충돌 409를 만들지 않고 Task/부모 version·시간·이력을 변경하지 않는다. 기존 READ_COMMITTED/readOnly Service 트랜잭션에서 인가와 조회를 묶는다. 리소스 인가 전에 이력/사용자 표시 이름을 조회하지 않는다.

## 확정한 HTTP 응답

다음은 확정 계약의 예시다. 예시 시각은 실제 DB 조회 결과가 아니다. 응답은 application/json·Cache-Control no-store를 사용한다.

```http
GET /api/workspaces/1/projects/1/tasks/1/status-activities?size=20
Accept: application/json
```

```json
{
  "workspaceId": "1",
  "projectId": "1",
  "taskId": "1",
  "items": [{
    "id": "1",
    "actor": { "id": "3", "displayName": "Carol" },
    "fromStatus": "TODO",
    "toStatus": "IN_PROGRESS",
    "taskVersion": "2",
    "occurredAt": "2026-11-10T03:00:00Z"
  }],
  "beforeVersion": null,
  "size": 20,
  "hasNext": false,
  "nextBeforeVersion": null
}
```

- 응답은 부모/Task ID·items·beforeVersion·size·hasNext·nextBeforeVersion의 8필드, 각 항목은 id·actor·fromStatus·toStatus·taskVersion·occurredAt의 6필드다. actor는 id/displayName 2필드이며 User Entity를 직렬화하지 않는다. 이름은 현재 DB 문자열을 반환하며 조회에서 정규화/변경하지 않는다.
- ID·taskVersion은 JS 정밀도 손실을 피하도록 양의 정규 십진 문자열/Long 범위다. 이력 taskVersion은 양수이고 일반 수정 때문에 연속일 필요가 없다. Activity id·현재 Task version과 구분한다. fromStatus/toStatus는 기존 세 상태이며 서로 달라야 한다.
- occurredAt은 서버가 저장한 시간의 UTC ISO 8601 문자열이다. 상태/시간/역할·이름 스냅샷을 클라이언트가 만들지 않는다. 이메일/작성 당시 역할·현재 다른 사용자의 membership은 응답하지 않는다.
- 커서는 `WHERE task_id=:taskId AND task_version<:beforeVersion ORDER BY task_version DESC`, size+1건으로 hasNext를 계산한다. 첫 요청은 beforeVersion을 생략하고 조건 없이 해당 Task의 최신순 기록을 읽는다. 응답의 beforeVersion은 요청값 또는 첫 요청의 null이다. hasNext=true일 때만 nextBeforeVersion은 마지막 반환 항목의 taskVersion이며, hasNext=false면 null이다. 빈 목록도 beforeVersion/size를 그대로 응답하고 hasNext=false/nextBeforeVersion=null이다.
- 경로 ID와 beforeVersion은 1~Long.MAX_VALUE의 정규 십진 문자열, size는 1~100/기본 20이다. 허용 쿼리는 beforeVersion/size뿐이며 page/sort/사용자/상태 필터·알 수 없는/중복 쿼리, 빈 값/부호/공백/선행 0/범위 초과는 400이다. 크기/커서를 자동 보정하지 않는다.
- 커서는 현재 URL Task 내 조회 경계이며 권한 증명이나 다른 Task 이력 참조가 아니다. 해당 버전의 기록이 실제 존재할 필요가 없고, 현재 버전보다 크거나 간격 사이인 값도 형식이 유효하면 그 경계로 조회한다. beforeVersion=1은 양수 상태 이력이 없어 빈 결과가 된다. Task 상세의 수정용 version을 요청에 추가하지 않는다.
- 새 기록은 첫 페이지 새로고침으로 확인한다. 이전 페이지는 cursor보다 작은 버전만 조회해 새 기록 때문에 기존 페이지가 밀리지 않게 한다. 여러 요청에 걸친 전체 DB 스냅샷을 약속하지 않는다.
- 기존 UNIQUE(task_id,task_version) 인덱스를 활용하므로 V8/새 인덱스를 계획하지 않는다. 구현 단계에서 격리 DB의 실제 조회 계획·결과를 확인한다. 날짜/전역 identity 순서를 pagination 경계로 사용하지 않는다.

커서 동작 예시(실제 로컬 데이터와 무관): 이력 버전이 14/10/8/3/1이면 size=2의 첫 결과는 14/10, 다음 cursor는 10이다. 이후 새 버전 16이 추가돼도 beforeVersion=10은 8/3, beforeVersion=3은 1을 반환한다. 새 16은 첫 페이지 새로고침에서 확인한다. 임의 페이지 번호로 바로 이동하지 않는 대가로 추가 기록에 의한 offset 이동을 피한다.

## 구현의 최소 권한·영향 범위

- 승인 상태 `TASK_STATUS_HISTORY_READ`는 기존 TASK_STATUS_CHANGE + 이력 7개 컬럼 `SELECT(id,task_id,actor_user_id,from_status,to_status,task_version,occurred_at)`만 추가한다. 테이블 전체 SELECT ACL 대신 고정 컬럼으로 미래 컬럼의 자동 허용을 막는다. 현재 테이블은 7개 컬럼뿐이므로 이 권한으로 SELECT * 문장 자체는 성공할 수 있다. SQL 문법의 별표 금지와 컬럼 권한 경계를 혼동하지 않는다.
- 기존 INSERT 6개 컬럼·Task status UPDATE를 유지한다. Activity id INSERT/전체 INSERT·UPDATE/DELETE/TRUNCATE/REFERENCES/TRIGGER·직접 시퀀스/DDL·Flyway 이력 접근 권한은 늘리지 않는다. users 조회 권한은 이미 있으며 이메일을 새 DTO로 직렬화하지 않는다.
- `infra/provision-task-status-history-read.ps1` → `runAppTaskStatusHistoryReadAccess`/`AppTaskStatusHistoryReadAccessRunner`·전용 정책 SQL은 정확한 TASK_STATUS_CHANGE→TASK_STATUS_HISTORY_READ 또는 완전 상태의 반복만 허용한다. 부분 SELECT/과도/table-wide/PUBLIC/GRANT OPTION은 자동 수선하지 않는다. 비밀번호와 기존 권한을 보존한다.
- AppReadPolicy의 원시/유효 컬럼 SELECT 집합·새 상태, 기존 모든 관리자 도구의 새 완전 상태 보존, dev-actor 이력 읽기 기동 검사와 DevWorkspaceWriteSettings의 기존 SELECT 금지 부분을 함께 전환한다. 원시 ACL 집계는 기존 INSERT 6개와 새 SELECT 7개를 구분해야 한다. has_table_privilege(SELECT)=false, 승인 컬럼 7개의 SELECT=true 조합이 정상이다.
- dev-actor의 새 조회 API를 활성화한 앱은 쓰기 on/off 모두 이력 SELECT 7개가 필요하며 누락/부분 권한은 기동 검사에서 실패한다. 기존 TASK_STATUS_CHANGE 로컬 환경에는 새 코드만 먼저 기동하지 않고 후속 별도 로컬 요청에서 권한 도구 적용 후 새 앱을 확인한다. 기본 프로필은 새 GET이 비활성이고 앱 부팅 자동 GRANT는 없다. 읽기 HTTP 모드는 앱 계정의 기존 쓰기 ACL을 철회한다는 뜻이 아니다.
- 구현은 기존 EntityManager native projection에서 이력과 users.display_name만 한 번에 읽는 것이다. actor membership과 JOIN해 탈퇴한 행위자 이력을 숨기지 않는다. 이메일 포함 Entity 직렬화·항목당 사용자 조회/N+1·이력 save/delete API를 추가하지 않는다. 조회 전용 Repository/DTO와 READ_COMMITTED readOnly Service 경계를 분리하며 이력을 변경 가능한 JPA Entity로 추가할 필요는 없다.
- V7 SQL/기존 데이터·이력·identity를 변경하지 않는다. ACL은 승인된 컬럼 SELECT만 추가하고 기존 INSERT와 다른 권한을 보존한다. migration history 1~7·seed의 업무/이력 불일치 거부·과거 target/미래 V8 fixture는 유지한다. 실제 로컬은 구현/격리 검증 후 사용자 요청에 따라 별도 Apply를 완료했다.

## 격리 검증 기준

1. 네 역할의 전체 과거 이력 200·가입 전/행위자 탈퇴 후 이력 포함, 조회자 탈퇴 후 404, 미소속/미존재/다른 부모·양쪽 소속·위조 사용자 헤더의 동일 404와 개발 접속 403을 실제 HTTP로 확인한다.
2. 기존 V7 상태 이력/현재 이름·이메일 비노출·큰 ID/버전 문자열·UTC 시간과 빈 목록을 검증한다. 사용자 이름 변경 시 현재 이름 계약을 따르며 일반 수정/no-op은 목록을 늘리지 않는다.
3. 커서 첫/중간/마지막·빈 결과·size 경계·큰/간격 사이 커서·버전 간격·새 전이 추가 중 다음 페이지·다른 Task 격리를 검증한다. 중복/누락과 요청 beforeVersion/size echo·hasNext/nextBeforeVersion·최신순 및 기존 인덱스 활용을 확인한다.
4. readOnly/GET이 업무 행·이력/버전/시간·시퀀스를 바꾸지 않고 기본/쓰기 off·입력/오류·실제 Vite 경계를 유지하는지 확인한다.
5. SELECT 7개 컬럼의 최초/반복·기존 모든 도구 보존·부분/과도/PUBLIC/GRANT OPTION·잘못된 자격증명 거부와 실제 최소 DB 계정 조회를 확인한다. 새 권한의 읽기/쓰기 기동 검사·기존 상태 이력 INSERT/원자성·전체 회귀도 확인한다.
6. 모든 쓰기/이름 변경·권한 검증은 고유 PostgreSQL에서만 수행한다. 실제 로컬 Task version 2·상태 이력 1건과 자격증명/기존 SQL을 보존하고 새 JAR의 관리자 코드/비밀정보 제외를 확인하며 임시 자원을 정리한다.

## 결정·범위와 다음 한 작업

사용자 선택은 ADR-057에 기록했다. OWNER/ADMIN 제한·행위자 ID만·page 번호 방식 대신 현재 소속 네 역할/현재 표시 이름/최신순 커서를 선택했다. 처리 순서/strict 입력·DTO/시간 형식·고정 컬럼 권한·native projection은 기존 패턴과 최소 범위를 유지하는 구현 기본값이다. API·도구/격리·실제 로컬 결과는 [구현 기록](task-status-history-implementation.md)과 [실행 기록](local-setup.md)에 구분한다. 다음은 Vue 이력 화면 연결이다.

실제 로컬 SELECT와 새 앱 HTTP를 확인했다. 다음 한 작업은 **Vue Task 상태 이력 조회 화면 연결**이다.
