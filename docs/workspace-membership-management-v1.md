# Workspace 멤버 초대·역할·제거 계약 v1

- 날짜: 2026-11-13
- 상태: 계약·V9·API·최소 권한 도구·Vue 연결, 격리 및 실제 로컬 검증 완료 (2026-11-19)
- 근거: 사용자 선택 및 [ADR-065](decisions.md)
- 선행 계약: [Workspace 조회](workspace-read-api-v1.md), [멤버 목록](workspace-member-list-api-v1.md), [Task 담당자·마감일](task-assignee-due-date-v1.md)

## 사용자 확정 정책

| 항목 | 결정 |
|---|---|
| 초대 대상/채널 | 이미 존재하는 FlowDesk 계정에 초대 코드/링크를 발급한다. 메일을 보내지 않는다. 대상 계정이 서버에서 식별한 dev-actor로 수락한다. |
| 초대 수명 | 발급 뒤 7일, 한 번만 수락 가능. 같은 공간/대상의 재발급은 기존 미사용 초대를 무효화하고 새 코드를 발급한다. |
| 관리 권한 | 해당 Workspace의 OWNER·ADMIN이 초대 발급/취소, 일반 멤버의 역할 변경과 제거를 수행한다. 다른 공간에서 가진 역할은 적용하지 않는다. |
| OWNER 경계 | 정확히 한 명을 유지한다. OWNER 이전은 현재 OWNER만 수행하며, 대상은 이미 그 공간의 멤버여야 한다. 이전과 동시에 이전 OWNER는 ADMIN이 된다. ADMIN은 OWNER를 강등·제거·이전할 수 없다. |
| Task 배정 보존 | 멤버 제거 뒤 `tasks.assignee_id`와 상태 이력은 보존한다. 제거된 사용자는 새 담당자로 지정할 수 없고, 해제 또는 현재 멤버 재지정만 허용한다. |

## 기존 계정 초대

- 초대 발급자는 해당 Workspace의 현재 OWNER 또는 ADMIN이어야 한다. 입력 이메일은 앞뒤 공백 제거·소문자 정규화하고 기존 User 한 명으로 해석한다. 초대는 `user_id`에 묶이며, 존재하지 않는 계정을 대신해 User를 만들지 않는다.
- 현재 멤버에게 초대할 수 없다. 초대 역할은 ADMIN, MEMBER, VIEWER다. OWNER 역할의 초대는 없다. 소유권은 기존 멤버 사이의 명시적 이전으로만 바꾼다.
- 수락 전에는 `WorkspaceMembership`을 생성하지 않는다. 대상 사용자가 수락 요청을 보낼 때 서버 행위자와 저장된 target user가 같은지 확인한다. 코드만 안다고 다른 계정이 가입할 수 없다. 수락 시 Workspace와 초대 상태를 잠그고 대상이 아직 비멤버인지 다시 검사한 뒤 초대에 기록된 역할로 membership 추가와 초대 소비를 한 트랜잭션에서 처리한다.
- 초대 코드는 암호학적으로 안전한 난수 256비트 이상으로 생성하고, DB에는 SHA-256 digest만 저장한다. 원문 코드는 발급 성공 응답에서 한 번만 돌려준다. 외부 메일 전송·계정 가입·실제 인증은 포함하지 않는다.
- 같은 Workspace/대상에는 미사용 pending 초대를 하나만 둔다. 재발급은 이전 코드를 폐기하고 새 코드/만료 시각으로 교체한다. 취소는 즉시 수락 불가 상태로 만든다. 만료는 별도 batch 없이 요청 시각 비교로 판정한다.

## 역할 변경·제거·소유권 이전

- OWNER·ADMIN은 non-OWNER 멤버의 역할을 ADMIN/MEMBER/VIEWER 사이에서 변경하고 non-OWNER membership을 제거할 수 있다. 자기 자신도 non-OWNER라면 같은 관리 규칙으로 제거할 수 있다. 일반 멤버의 별도 self-leave endpoint는 이 범위에 추가하지 않는다.
- OWNER·ADMIN은 OWNER를 대상으로 한 일반 역할 변경이나 제거를 할 수 없다. 현재 OWNER가 현재 non-OWNER 멤버를 대상 삼아 소유권을 이전한다. 한 트랜잭션에서 새 OWNER 승격과 기존 OWNER의 ADMIN 강등을 함께 완료한다. DB의 부분 UNIQUE는 최대 한 OWNER를 보장하고, Service 트랜잭션은 전환 도중에도 커밋 시점에 정확히 한 명이 남도록 한다.
- OWNER의 일반 강등/제거는 금지한다. 먼저 소유권을 이전해 기존 OWNER를 ADMIN으로 만든 뒤, 별도 요청으로 제거할 수 있다. 따라서 마지막 OWNER는 제거·강등될 수 없다.
- 각 권한 변경은 대상 Workspace의 row lock을 먼저 획득한 다음 행위자 membership/역할, 대상 membership, 현재 상태를 검사하고 같은 트랜잭션에서 적용한다. 초대 수락·역할 변경·제거·소유권 이전을 같은 잠금 경계로 직렬화한다.
- Task 생성/수정에서 비-null 담당자를 새로 지정하거나 바꿀 때도 Workspace 잠금 후 현재 membership을 확인한다. 그 공간에서 Task 배정 변경과 멤버 제거가 경합하면 한 작업이 먼저 완료되고 뒤 작업은 새 상태를 본다. 이미 저장된 담당자 값은 멤버 제거로 바꾸거나 지우지 않는다.

## API/오류 경계의 구현 기본값

### HTTP 계약

| 동작 | 요청 | 성공 응답 |
|---|---|---|
| 초대 발급 | `POST /api/workspaces/{workspaceId}/invitations` `{email, role}` | `201`, `{id, role, expiresAt, code}` (코드는 이 응답에만 포함) |
| 초대 취소 | `DELETE /api/workspaces/{workspaceId}/invitations/{invitationId}` | `204` |
| 초대 수락 | `POST /api/workspace-invitations/accept` `{code}` | `201`, `{workspaceId, id, displayName, role}` |
| 역할 변경 | `PATCH /api/workspaces/{workspaceId}/members/{userId}` `{role}` | `200`, 현재 멤버 projection |
| 멤버 제거 | `DELETE /api/workspaces/{workspaceId}/members/{userId}` | `204` |
| 소유권 이전 | `POST /api/workspaces/{workspaceId}/ownership-transfer` `{targetUserId}` | `200`, `{workspaceId, ownerUserId}` |

삭제 요청에는 query/body를 받지 않는다. 모든 개발 쓰기는 기존 명시적 설정, loopback Origin 및 JSON 경계를 통과한다.

- 미소속/미존재 Workspace 및 다른 Workspace의 대상은 기존 공간 API와 같이 같은 404로 처리한다. 클라이언트가 user ID/role을 보내도 행위자를 바꾸거나 권한을 부여하지 않는다.
- 알 수 없는/취소/만료/사용된 코드와 초대 대상이 아닌 행위자는 모두 동일한 404로 응답해 유효 코드 여부를 드러내지 않는다.
- 다른 역할의 멤버 관리 시도는 403, 현재 멤버에게 중복 초대·이미 처리된 역할 변경 등 상태 충돌은 409, 잘못된 입력은 400으로 한다. 코드는 idempotency key나 자동 재시도를 의미하지 않는다.
- 기존 개발 쓰기 설정·loopback/Origin/JSON 검사 경계를 그대로 적용한다. 서버 지정 actor는 편의용 개발 경계이며 인증으로 취급하지 않는다. Phase 2 인증 전 운영·외부 접근에 사용할 수 없다.
- V9 `workspace_invitations`, 앱 최소 DML/SELECT ACL, actor/role/Origin 경계, 초대 재발급·1회 수락·취소, 역할 변경·제거·소유권 이전 및 제거 후 Task assignee 보존을 격리 PostgreSQL에서 검증했다. 로컬 DB 적용은 별도 요청에서 수행한다. 앱 부팅은 migration이나 GRANT를 실행하지 않는다.

## 보존·트랜잭션 수용 기준

1. 초대 발급은 pending invite 하나와 원문 코드를 한 번만 반환하고 DB에는 digest를 저장한다. 동일 대상 재발급 후 이전 코드 수락은 404이며 새 코드만 7일 안에 한 번 수락할 수 있다.
2. 대상 actor 일치, 만료 경계, 재사용, 취소, 이미 멤버인 대상, 동시에 발급/수락하는 요청을 검사한다. membership 생성 실패면 초대도 사용 완료로 남지 않는다.
3. OWNER/ADMIN 권한과 역할 조합을 확인한다. OWNER는 정확히 한 명이고, 소유권 이전 후 새 OWNER/기존 ADMIN, OWNER 제거 거부를 확인한다. 동시 이전/강등/제거 뒤에도 OWNER가 0명 또는 2명이 되지 않는다.
4. membership 제거 후 Task의 기존 assignee ID·상태 이력은 동일하다. 같은 사용자를 새/변경 assignee로 제출하면 거부하고, 기존 ID 유지·null 해제·현재 멤버 재지정은 기존 Task 권한/버전 규칙대로 동작한다.
5. 격리 DB에서 data/membership/Task/history/sequence와 migration checksum을 비교한다. 권한 도구는 필요한 컬럼 INSERT/UPDATE/DELETE만 허용하고 table-wide DML, DDL, sequence 및 history 접근을 넓히지 않는다.

## 제외

실제 로그인/JWT/MFA, 사용자 가입·삭제, 이메일 전송, 임의 이메일을 가진 미등록 사용자 초대, 일반 멤버 self-leave, Workspace 삭제, 멤버 변경의 범용 audit feed/알림, bulk 초대는 제외한다. 제품에 필요한 audit/알림은 후속 별도 계약으로 다룬다.
