import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  acceptWorkspaceInvitation, cancelWorkspaceInvitation, changeWorkspaceMemberRole,
  getWorkspaceInvitations, issueWorkspaceInvitation, removeWorkspaceMember, transferWorkspaceOwnership,
  WorkspaceMembershipUncertainError,
} from '../api/workspace-membership'
import { WorkspaceApiError } from '../api/workspaces'

afterEach(() => vi.unstubAllGlobals())
const code = 'a'.repeat(43)
const invitation = { id: '21', role: 'MEMBER', expiresAt: '2026-11-26T10:00:00Z', code }
const member = { workspaceId: '7', id: '8', displayName: 'Eve', role: 'MEMBER' }
function json(value: unknown, status = 200, headers: HeadersInit = {}) {
  return new Response(JSON.stringify(value), { status, headers })
}

describe('Workspace 멤버십 HTTP client', () => {
  it('초대 목록은 안전한 필드와 페이지 계약만 허용한다', async () => {
    const item = { id: '22', targetDisplayName: 'Bob', targetEmail: 'bob@example.test', role: 'MEMBER',
      createdAt: '2026-11-19T10:00:00Z', expiresAt: '2026-11-26T10:00:00Z', expired: true }
    const fetch = vi.fn().mockResolvedValue(json({ items: [item], page: 0, size: 20, hasNext: false }))
    vi.stubGlobal('fetch', fetch)
    await expect(getWorkspaceInvitations('7')).resolves.toEqual({ items: [item], page: 0, size: 20, hasNext: false })
    expect(fetch).toHaveBeenCalledExactlyOnceWith('/api/workspaces/7/invitations?page=0&size=20', expect.objectContaining({
      headers: { Accept: 'application/json' }, credentials: 'omit', cache: 'no-store', signal: expect.any(AbortSignal),
    }))
    fetch.mockResolvedValueOnce(json({ items: [{ ...item, code: 'must-not-leak' }], page: 0, size: 20, hasNext: false }))
    await expect(getWorkspaceInvitations('7')).rejects.toMatchObject({ code: 'INVALID_RESPONSE' })
  })

  it('초대 이메일/역할을 정규화해 한 번만 보내고 코드 응답을 엄격히 검사한다', async () => {
    const fetch = vi.fn().mockResolvedValue(json(invitation, 201, { Location: '/api/workspaces/7/invitations/21' }))
    vi.stubGlobal('fetch', fetch)
    await expect(issueWorkspaceInvitation('7', ' Eve@Example.Test ', 'MEMBER')).resolves.toEqual(invitation)
    expect(fetch).toHaveBeenCalledExactlyOnceWith('/api/workspaces/7/invitations', expect.objectContaining({
      method: 'POST', body: JSON.stringify({ email: 'eve@example.test', role: 'MEMBER' }),
      credentials: 'omit', cache: 'no-store', redirect: 'error', signal: expect.any(AbortSignal),
    }))
    fetch.mockResolvedValueOnce(json({ ...invitation, code: 'too-short' }, 201))
    await expect(issueWorkspaceInvitation('7', 'eve@example.test', 'MEMBER')).rejects.toBeInstanceOf(WorkspaceMembershipUncertainError)
    expect(fetch).toHaveBeenCalledTimes(2)
  })

  it('수락 응답의 Location과 actor-safe membership DTO를 확인한다', async () => {
    const fetch = vi.fn().mockResolvedValue(json(member, 201, { Location: '/api/workspaces/7/members/8' }))
    vi.stubGlobal('fetch', fetch)
    await expect(acceptWorkspaceInvitation(` ${code} `)).resolves.toEqual({ workspaceId: '7', member })
    expect(fetch).toHaveBeenCalledWith('/api/workspace-invitations/accept', expect.objectContaining({
      method: 'POST', body: JSON.stringify({ code }), credentials: 'omit',
    }))
  })

  it('역할 변경·삭제·초대 취소·소유권 이전의 method와 body를 계약대로 보낸다', async () => {
    const fetch = vi.fn()
      .mockResolvedValueOnce(json({ ...member, role: 'VIEWER' }))
      .mockResolvedValueOnce(new Response(null, { status: 204 }))
      .mockResolvedValueOnce(new Response(null, { status: 204 }))
      .mockResolvedValueOnce(json({ workspaceId: '7', ownerUserId: '8' }))
    vi.stubGlobal('fetch', fetch)
    await expect(changeWorkspaceMemberRole('7', '8', 'VIEWER')).resolves.toMatchObject({ id: '8', role: 'VIEWER' })
    await expect(removeWorkspaceMember('7', '8')).resolves.toBeUndefined()
    await expect(cancelWorkspaceInvitation('7', '21')).resolves.toBeUndefined()
    await expect(transferWorkspaceOwnership('7', '8')).resolves.toEqual({ workspaceId: '7', ownerUserId: '8' })
    expect(fetch.mock.calls.map(([path, init]) => [path, init?.method, init?.body])).toEqual([
      ['/api/workspaces/7/members/8', 'PATCH', JSON.stringify({ role: 'VIEWER' })],
      ['/api/workspaces/7/members/8', 'DELETE', undefined],
      ['/api/workspaces/7/invitations/21', 'DELETE', undefined],
      ['/api/workspaces/7/ownership-transfer', 'POST', JSON.stringify({ targetUserId: '8' })],
    ])
  })

  it('4xx 거부는 확정 오류지만 5xx와 통신 실패는 결과 불명이며 재전송하지 않는다', async () => {
    const fetch = vi.fn().mockResolvedValueOnce(json({}, 409)).mockResolvedValueOnce(json({}, 503))
    vi.stubGlobal('fetch', fetch)
    await expect(issueWorkspaceInvitation('7', 'eve@example.test', 'MEMBER')).rejects.toMatchObject({ status: 409 })
    await expect(issueWorkspaceInvitation('7', 'eve@example.test', 'MEMBER')).rejects.toBeInstanceOf(WorkspaceMembershipUncertainError)
    fetch.mockRejectedValueOnce(new TypeError('secret connection data'))
    await expect(acceptWorkspaceInvitation(code)).rejects.toBeInstanceOf(WorkspaceMembershipUncertainError)
    expect(fetch).toHaveBeenCalledTimes(3)
  })

  it('잘못된 로컬 ID·역할·코드는 네트워크 요청 전에 거부한다', async () => {
    const fetch = vi.fn(); vi.stubGlobal('fetch', fetch)
    await expect(issueWorkspaceInvitation('0', 'eve@example.test', 'MEMBER')).rejects.toBeInstanceOf(WorkspaceApiError)
    await expect(changeWorkspaceMemberRole('7', '8', 'OWNER' as never)).rejects.toBeInstanceOf(WorkspaceApiError)
    await expect(acceptWorkspaceInvitation('bad')).rejects.toBeInstanceOf(WorkspaceApiError)
    expect(fetch).not.toHaveBeenCalled()
  })
})
