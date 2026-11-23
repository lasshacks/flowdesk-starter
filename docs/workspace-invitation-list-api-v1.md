# Workspace 초대 대기 목록 API 계약 v1

- 날짜: 2026-11-20
- 상태: API·Vue 구현, Vite proxy HTTP 및 Chrome 화면/취소 상호작용 확인 완료
- 선행 계약: [Workspace 멤버십 관리](workspace-membership-management-v1.md), [현재 멤버 목록](workspace-member-list-api-v1.md)
- 관련 결정: [ADR-065](decisions.md#adr-065-workspace-멤버-초대역할제거-정책-2026-11-13), [ADR-066](decisions.md#adr-066-workspace-멤버십-관리-apiv9최소-권한-구현-2026-11-14), [ADR-067](decisions.md#adr-067-workspace-초대-대기-목록-api-계약-2026-11-20)

## 목적

현재 UI는 발급 성공 응답에 포함된 코드를 한 번만 화면에 표시한다. 화면을 닫거나 새로고침하면 코드 원문을 다시 볼 수 없고, 발급자가 관리할 수 있는 초대도 화면 내 현재 초대 하나뿐이다. 이 API는 Workspace별 미수락 초대의 현재 상태를 읽어 새로고침 뒤에도 취소 대상과 만료 시각을 확인하게 한다. 원문 코드나 digest 복구 기능은 제공하지 않는다.

## 확정 계약

| 항목 | 결정 |
|---|---|
| 경로 | `GET /api/workspaces/{workspaceId}/invitations?page=0&size=20` |
| 행위자 | 현재 dev-actor 설정에서 서버가 식별. 클라이언트 actor ID/역할은 무시 |
| 권한 | 해당 Workspace의 OWNER·ADMIN만 허용 |
| 대상 상태 | DB status가 PENDING인 항목 전체. 이미 만료된 PENDING도 포함하고 `expired`로 표시 |
| 제외 상태 | ACCEPTED·REVOKED는 제외. 이 API는 이력 조회가 아님 |
| 식별정보 | 대상 표시 이름과 정규화 이메일. 발급자 ID/이름과 대상 내부 User ID는 제외 |
| 보안 필드 | 초대 원문 코드와 token digest는 응답에 포함하지 않음 |
| 항목 필드 | `id`, `targetDisplayName`, `targetEmail`, `role`, `createdAt`, `expiresAt`, `expired` |
| 정렬/페이지 | invitation ID 내림차순(최신 먼저), 0-based slice, 기본 20·최대 100. 현재 Workspace 멤버 목록과 query/error envelope를 재사용 |
| 오류 | 미소속·미존재 Workspace 동일 404, 해당 공간의 MEMBER/VIEWER 403, 잘못되거나 중복된 page/size 400 |
| 실행 경계 | 현재 멤버십 관리 API와 같은 `dev-actor` 프로파일 및 명시적 workspace-write 설정 안에서만 활성화 |

사용자가 확정한 범위는 표시 이름·이메일 식별, 만료된 PENDING 포함, OWNER·ADMIN 한정 접근이다. `expired`는 응답 생성 시 서버 시각 기준으로 `!now.isBefore(expiresAt)`를 계산한다. 저장 상태를 바꾸거나 만료 행을 삭제하지 않는다. 따라서 만료된 초대도 취소 또는 같은 대상 재발급의 기준으로 관리할 수 있으며, 기존 수락 경계는 계속 404를 반환한다.

이메일은 이미 초대 발급·취소 권한이 있는 OWNER·ADMIN에게 초대 대상을 식별하는 용도로만 노출한다. 응답은 Entity를 직렬화하지 않고 전용 projection/DTO를 사용한다.

## 응답 예시

```json
{
  "items": [
    {
      "id": "42",
      "targetDisplayName": "Eve Example",
      "targetEmail": "eve@example.test",
      "role": "MEMBER",
      "createdAt": "2026-11-19T10:00:00Z",
      "expiresAt": "2026-11-26T10:00:00Z",
      "expired": false
    }
  ],
  "page": 0,
  "size": 20,
  "hasNext": false
}
```

ID는 기존 API 규칙과 같이 JSON 문자열이다. `code`, `tokenDigest`, `targetUserId`, 내부 Entity는 반환하지 않는다. 취소는 기존 `DELETE /api/workspaces/{workspaceId}/invitations/{invitationId}`를 사용하고, 상태가 바뀌었거나 다른 공간 소속 ID면 기존 오류 계약을 따른다.

## 구현 흐름·검증 결과

Controller strict query parsing → dev-actor Service의 서버 행위자 및 Workspace membership/OWNER·ADMIN 검사 → 현재 Workspace의 PENDING invitation + target User projection → DTO의 문자열 ID·서버 만료 판정 → Slice 응답 흐름이다. Controller/Service는 `dev-actor`와 기존 명시적 workspace-write 활성화 조건을 공유한다. 조회는 read-only이고 V9/ACL 변경은 없다. 격리 DB에서 기존 `WORKSPACE_MEMBERSHIP_MANAGEMENT` 컬럼 SELECT 범위만으로 동작함을 검증했다.

`WorkspaceMembershipManagementApiTest` 5개와 Vue 테스트 507개(24 files)가 통과했고 vue-tsc/Vite build도 성공했다. 목록 수용 검증은 page 경계/ID 내림차순, Workspace 격리, OWNER·ADMIN 허용 및 MEMBER·VIEWER 거부, 미소속·미존재 동일 404, 만료된 PENDING 포함·`expired=true`, ACCEPTED/REVOKED 제외, 코드/digest/User ID 누출 없음, 잘못되거나 중복된 query 400을 확인했다. Vue 테스트는 만료 항목 표시·취소·재조회 및 VIEWER 목록 미요청도 검증한다. Testcontainers `@AfterEach`는 invitation 행과 임시 Eve membership을 제거하고 seed 역할을 복구한다. 로컬 새 앱과 Vite proxy의 실제 GET/POST/DELETE/GET도 성공했고 생성한 초대 행을 제거했다. Chrome DOM/시각 확인만 남았다.

## 확정 결정과 구현 경계

사용자는 대상 표시 이름·이메일, 만료된 PENDING 포함 및 만료 표시, OWNER·ADMIN만 조회를 선택했다. ID 내림차순과 현재 멤버 목록에 맞춘 페이지 규칙은 일관성을 위한 구현 기본값이다.

이번 단계는 기존 `DELETE`에 Vue를 연결했고, 별도 로컬 GRANT 또는 migration은 필요하지 않아 수행하지 않았다. 실제 브라우저 검증은 DB 변경 없이 Vite middleware의 메모리 fixture로 초대 목록 GET/DELETE만 대체했다. Chrome에서 만료된 Eve 항목과 `초대 취소` 버튼을 확인하고 사용자가 버튼을 눌렀으며, 이어진 fixture GET은 빈 목록을 반환했다. 검증용 앱·Vite 서버는 종료했다. 실제 백엔드/Vite proxy GET/POST/DELETE/GET은 별도 로컬 초대 행을 이용한 앞선 검증 결과이며 서로 구분한다.
