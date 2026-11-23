# Workspace 현재 멤버 목록 API 계약

- 날짜: 2026-11-11
- 상태: 계약·API·column SELECT 실행기·격리 PostgreSQL 검증·Vue 담당자 선택 UI·실제 로컬 ACL/HTTP 확인 완료
- 관련: [요구사항](requirements.md), [아키텍처](architecture.md), [Task 담당자·마감일 결정](task-assignee-due-date-v1.md)

## 확정 범위

이번 기능은 현재 Workspace 멤버의 읽기 전용 목록과 Task 담당자 선택 지원이다. 초대·수락·역할 변경·멤버 제거는 포함하지 않으며 별도 정책 결정으로 둔다.

| 항목 | 결정 |
|---|---|
| 경로 | `GET /api/workspaces/{workspaceId}/members?page=0&size=20` |
| 행위자 | 현재 실행 설정이 제공하는 서버 행위자. 클라이언트가 user ID를 지정하지 않는다 |
| 조회 권한 | 요청자가 해당 Workspace의 현재 멤버이면 OWNER·ADMIN·MEMBER·VIEWER 모두 허용 |
| 응답 항목 | `id`, `displayName`, `role`만 반환. 이메일·계정 메타데이터 제외 |
| 포함 대상 | 요청 시점에 해당 Workspace에 현재 소속된 사용자만. 과거 제거 멤버 제외 |
| 정렬/페이지 | 사용자 ID 오름차순, 기존 목록 API와 같은 0 기반 `page`, 기본 size 20·최대 100의 slice (`items`, `page`, `size`, `hasNext`) |
| 오류 | 미소속 요청자와 존재하지 않는 Workspace는 같은 404. 잘못된/중복 query parameter는 400 |
| 재사용 | 결과는 담당자 선택 UI의 후보 목록. 서버의 Task 생성/수정 membership 검증은 계속 최종 권한 경계 |

목록은 해당 Workspace의 membership으로 사용자에 조인해 필요한 `user.id`, `display_name`, `role`만 projection한다. User Entity나 이메일을 응답에 직렬화하지 않는다. 접근 확인 후 조회하여 비멤버에게 후보 사용자 목록을 노출하지 않는다. API는 기존 개발 행위자 프로파일의 경계 안에서 먼저 구현하며, 실제 로그인 연결은 인증 Phase에서 다룬다.

페이지 offset은 사용자가 페이지 사이에서 멤버가 추가/제거되면 경계가 이동할 수 있다. 현재 멤버 관리 변경 API가 없고 선택 UI가 작은 후보 목록을 순회하는 범위여서 기존 API와 일관된 slice를 선택했다. 멤버 관리가 추가될 때 cursor 전환 필요성을 재검토한다.

## 예상 흐름

Vue 담당자 입력/선택 → API client strict parse → GET endpoint → 서버 행위자와 현재 Workspace 소속 검사 → projection query → ID·표시 이름·역할 DTO → 멤버 후보 UI. 사용자가 후보를 선택한 뒤 Task POST/PATCH를 보내면 기존 Task Service가 현재 membership을 다시 검사한다.

Repository는 workspace ID와 user ID 오름차순 slice를 반환하고 Service가 후보 목록 query 전에 행위자의 membership을 확인한다. 전용 권한 도구는 DB 권한을 자동으로 추가하지 않으며, 격리 검증 뒤 실제 로컬에 적용하는 단계는 분리한다.

구현 완료 상태: native DTO projection, dev-actor GET, strict 입력/404, exact 16-column 전환 runner와 PowerShell wrapper를 추가했다. 테스트·실제 격리 DB·로컬 적용·새 앱 HTTP 결과는 [구현 기록](workspace-member-list-implementation.md)과 [로컬 실행 기록](local-setup.md)에 있다.

Vue 담당자 선택 UI도 생성·수정 폼에 연결했다. API page를 모두 읽고 이름·역할·ID로 표시하며, 저장 때 서버가 membership을 다시 검사한다. 전 로컬 멤버에 대한 기존 할당은 수정 화면에서 보존할 수 있고 신규 재할당은 거부된다.

## 대안과 이유

- 이메일 반환은 선택 UI에 필요하지 않고 개인정보 노출을 늘리므로 제외한다.
- 클라이언트 제공 actor ID는 신뢰하지 않고 기존 서버 행위자 경계를 따른다.
- 초대·제거를 함께 구현하면 DML 권한과 역할 변경, 단일 OWNER 불변식 및 할당 보존/경합 규칙까지 추가 결정이 필요하므로 읽기 전용 목록과 분리한다.
- 기존 Workspace 목록과 다른 cursor 규약을 지금 도입하지 않고 page/size/hasNext 형식을 재사용한다.
