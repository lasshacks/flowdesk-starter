# Task 담당자·마감일 모델 및 API 범위

- 날짜: 2026-11-10
- 상태: 정책·V8/API·로컬 권한 적용·Vue 입력 구현 완료
- 기준: [요구사항](requirements.md), [아키텍처](architecture.md), [기술 스택](tech-stack.md), [Task 생성/조회](task-model-and-api-v1.md), [Task 수정](task-update-api-v1.md), [로드맵](roadmap.md)

## 사용자와 확정한 정책

| 항목 | 결정 |
|---|---|
| 담당자 수 | Task당 최대 한 명. 미지정은 허용 |
| 지정 대상 | 지정 시점에 해당 Workspace의 현재 멤버인 사용자만. 네 역할 모두 대상이 될 수 있고, 본인 지정도 허용 |
| 멤버 제거 | 기존 assignee를 유지한다. 제거된 사용자는 다시 지정할 수 없고, 재지정은 현재 멤버에게만 가능하다. null로 해제할 수 있다 |
| 마감일 | 시간대가 없는 달력 날짜 `YYYY-MM-DD`; optional/null |
| 생성·수정 권한 | 현재 정책 유지: Workspace OWNER·ADMIN·MEMBER |
| 조회 권한 | 현재 정책 유지: Workspace OWNER·ADMIN·MEMBER·VIEWER |
| API 반영 | 생성·수정 요청과 프로젝트 Task 목록·상세 응답에 `assigneeId`, `dueDate` 포함 |
| 충돌 | 기존 Task 단일 version을 공유. 내용·우선순위·담당자·마감일 변경은 같은 Task version을 증가시킴 |
| 변경 이력 | 담당자·마감일 변경 Activity는 이번 범위 밖. 기존 상태 전용 이력은 그대로 둔다 |

날짜는 ISO calendar date로 파싱하고 과거 날짜도 허용한다. 마감일에 시간/시간대나 자동 알림을 포함하지 않는다. due date는 업무 의미상 날짜이므로 Java `LocalDate`와 PostgreSQL `date`를 사용한다.

## 데이터 모델 초안

V8에서 `flowdesk.tasks`에 nullable 컬럼 두 개를 추가한다.

| 컬럼 | 형식/제약 | 의미 |
|---|---|---|
| `assignee_id` | nullable `bigint`, `users(id)` FK, `ON DELETE RESTRICT` | 선택된 담당자. FK는 계정 참조를 보장하며 membership 참조는 아니다 |
| `due_date` | nullable `date` | 시간대 없는 마감일 |

Task의 Workspace는 Project를 통해 계산한다. assignee를 지정/변경할 때 서버는 Task의 실제 부모 Project→Workspace 안에서 대상 사용자 membership을 검사한다. `workspace_id` 중복 컬럼이나 ProjectMembership/TaskMembership은 추가하지 않는다.

membership FK를 만들지 않는 이유는 Workspace에서 제거된 멤버의 과거 할당을 보존하는 확정 정책 때문이다. 사용자 계정 삭제는 `ON DELETE RESTRICT`로 참조 중인 Task를 고아로 만들지 않는다. 향후 계정 비활성화/익명화 정책은 별도 결정한다. membership 제거와 할당 변경이 동시에 발생할 때의 잠금/직렬화는 membership 제거 기능 설계 시 함께 확정한다. 현재 membership 제거 API는 없다.

## 입력·출력 계약 범위

- Create: `assigneeId`와 `dueDate`는 생략 또는 명시적 null이면 미지정. 담당자 ID는 기존 정규 양의 십진 문자열 규칙을 따른다. `dueDate`는 실제로 존재하는 날짜의 정확한 `YYYY-MM-DD` 문자열만 받는다. 초기 상태 TODO, 우선순위 기본 MEDIUM 등 기존 계약은 유지한다.
- Update: 현재의 전체 스냅샷 PATCH에 두 필드를 더한다. 필드는 모두 명시해야 하며 null은 해당 필드 해제를 뜻한다. 나머지 수정 규칙과 `version` 문자열 전달·409 충돌 처리는 유지한다.
- List/detail/create/update 응답: Task 요약과 상세에 nullable `assigneeId` 문자열 및 nullable `dueDate` 문자열을 추가한다. 사용자 이름/이메일/사용자 DTO를 내장하지 않는다.
- 목록 조회의 필터/정렬 UI와 due-date 알림은 포함하지 않는다. 멤버 선택 API는 [Workspace 멤버 목록 계약](workspace-member-list-api-v1.md)에서 별도 확정했으며, 이메일 없이 ID·현재 표시 이름·역할만 제공한다.
- 잘못된/미소속 assignee는 요청 오류로 거부한다. 미존재 또는 다른 Workspace 사용자 ID를 구별하는 오류 세부정보는 노출하지 않는다.

기존 역할 검증이 먼저 이뤄진다. 비인가 VIEWER에게 담당자 membership 탐색으로 정보를 노출하지 않는다. 적법한 작성 요청은 URL 부모 경로를 검증하고 현재 Workspace membership을 확인한 뒤 대상 assignee의 동일 Workspace membership을 검증하여 저장한다. null은 해제이며 membership 조회가 필요 없다.

## 권한·마이그레이션 영향

V8은 새 nullable 컬럼만 추가하고 기존 Task 행은 두 값 모두 null로 유지한다. 상태·버전·기존 이력과 데이터는 바뀌지 않는다. Task list/detail projection 및 DTO를 늘린다. 생성/수정/조회 권한 도구와 앱 기동 ACL 검증은 새 컬럼의 최소 INSERT/UPDATE/SELECT 권한을 반영해야 하며 table-wide 권한으로 넓히지 않는다. 격리 PostgreSQL 검증과 실제 로컬 적용은 구현 후 별도 작업으로 나눈다.

담당자/날짜 필터는 이 결정의 범위가 아니다. 해당 필터 인덱스는 기능과 사용 쿼리가 결정될 때 추가 여부를 검토한다. Activity INSERT/이력 조회 범위도 확대하지 않는다.

## 다음 한 작업

V8, 멤버십 검증을 포함한 Task 생성·수정/응답 변경, 최소 컬럼 권한 도구 및 격리 PostgreSQL 검증을 구현한다. 실제 로컬 V8/권한 적용과 새 앱 확인은 별도 단계다.
