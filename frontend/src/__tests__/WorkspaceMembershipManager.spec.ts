import { afterEach, describe, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App as VueApp } from 'vue'
import WorkspaceMembershipManager from '../components/WorkspaceMembershipManager.vue'

let app: VueApp | undefined
afterEach(() => { app?.unmount(); app = undefined; document.body.replaceChildren(); vi.unstubAllGlobals() })
const workspace = { id: '7', name: 'Demo', myRole: 'OWNER' as const, version: '1' }
const code = 'c'.repeat(43)
function json(value: unknown, status = 200, headers: HeadersInit = {}) { return new Response(JSON.stringify(value), { status, headers }) }
function members(items: unknown[]) { return json({ items, page: 0, size: 100, hasNext: false }) }
const pending = { id: '22', targetDisplayName: 'Bob', targetEmail: 'bob@example.test', role: 'MEMBER',
  createdAt: '2026-11-19T10:00:00Z', expiresAt: '2026-11-20T10:00:00Z', expired: true }
function invitations(items: unknown[] = [pending]) { return json({ items, page: 0, size: 20, hasNext: false }) }
function button(host: HTMLElement, text: string) {
  const found = Array.from(host.querySelectorAll('button')).find(item => item.textContent?.trim() === text)
  expect(found, text).toBeDefined(); return found!
}
function input(element: HTMLInputElement | HTMLSelectElement, value: string) {
  Object.getOwnPropertyDescriptor(Object.getPrototypeOf(element), 'value')!.set!.call(element, value)
  element.dispatchEvent(new Event(element instanceof HTMLSelectElement ? 'change' : 'input', { bubbles: true }))
}
function render(fetch: ReturnType<typeof vi.fn>, onRoleChanged = vi.fn()) {
  vi.stubGlobal('fetch', fetch)
  const host = document.createElement('div'); document.body.append(host)
  app = createApp(WorkspaceMembershipManager, { workspace, onActorRoleChanged: onRoleChanged }); app.mount(host)
  return { host, onRoleChanged }
}

describe('Workspace 멤버 관리 화면', () => {
  it('OWNER가 초대를 발급·취소하고 역할·멤버·소유권을 관리한다', async () => {
    const owner = { id: '1', displayName: 'Alice', role: 'OWNER' }
    const bob = { id: '2', displayName: 'Bob', role: 'ADMIN' }
    const carol = { id: '3', displayName: 'Carol', role: 'MEMBER' }
    const page = [owner, bob, carol]
    const fetch = vi.fn(async (path: string, init?: RequestInit) => {
      if (path === '/api/workspaces/7/members?page=0&size=100') return members(page)
      if (path === '/api/workspaces/7/invitations?page=0&size=20') return invitations([])
      if (path === '/api/workspaces/7/invitations' && init?.method === 'POST') return json({ id: '22', role: 'MEMBER', expiresAt: '2026-11-26T10:00:00Z', code }, 201, { Location: '/api/workspaces/7/invitations/22' })
      if (path === '/api/workspaces/7/invitations/22' && init?.method === 'DELETE') return new Response(null, { status: 204 })
      if (path === '/api/workspaces/7/members/2' && init?.method === 'PATCH') return json({ ...bob, workspaceId: '7', role: 'VIEWER' })
      if (path === '/api/workspaces/7/members/2' && init?.method === 'DELETE') return new Response(null, { status: 204 })
      if (path === '/api/workspaces/7/ownership-transfer') return json({ workspaceId: '7', ownerUserId: '3' })
      throw new Error(`Unexpected request ${path} ${init?.method}`)
    })
    const { host, onRoleChanged } = render(fetch)
    button(host, '멤버 목록 열기').click()
    await vi.waitFor(() => expect(host.textContent).toContain('Alice'))
    await vi.waitFor(() => expect(host.textContent).toContain('초대 대기 목록'))
    await vi.waitFor(() => expect(host.textContent).toContain('대기 중인 초대가 없습니다.'))

    const email = host.querySelector<HTMLInputElement>('#workspace-invite-email')!
    input(email, 'bob@example.test')
    await nextTick()
    button(host, '초대 코드 발급').click()
    await vi.waitFor(() => expect(host.querySelector('.invitation-code')?.textContent).toBe(code))
    await vi.waitFor(() => expect(fetch.mock.calls.filter(([path]) => path === '/api/workspaces/7/invitations?page=0&size=20')).toHaveLength(2))
    await vi.waitFor(() => expect(button(host, '이 초대 취소').disabled).toBe(false))
    button(host, '이 초대 취소').click()
    await vi.waitFor(() => expect(host.textContent).toContain('초대를 취소했습니다'))
    input(email, 'alice@example.test'); await nextTick()
    await vi.waitFor(() => expect(button(host, '초대 코드 발급').disabled).toBe(false))

    const role = host.querySelector<HTMLSelectElement>('#member-role-2')!
    input(role, 'VIEWER')
    await nextTick(); button(host, '역할 저장').click()
    const bobRow = Array.from(host.querySelectorAll<HTMLElement>('.membership-item')).find(row => row.textContent?.includes('Bob'))!
    await vi.waitFor(() => expect(bobRow.textContent).toContain('뷰어'))
    await vi.waitFor(() => expect(button(bobRow, '제거').disabled).toBe(false))
    button(bobRow, '제거').click()
    await nextTick()
    button(host, '멤버 제거').click()
    await vi.waitFor(() => expect(fetch.mock.calls.filter(([path, init]) => path === '/api/workspaces/7/members/2' && init?.method === 'DELETE')).toHaveLength(1))
    const findCarolRow = () => Array.from(host.querySelectorAll<HTMLElement>('.membership-item')).find(row => row.textContent?.includes('Carol'))!
    await vi.waitFor(() => expect(button(findCarolRow(), '소유권 이전').disabled).toBe(false))
    button(findCarolRow(), '소유권 이전').click()
    await nextTick()
    button(host, '소유권 이전 확인').click()
    await vi.waitFor(() => expect(onRoleChanged).toHaveBeenCalledWith('ADMIN'))
    expect(fetch.mock.calls.map(([path, init]) => [path, init?.method])).toEqual(expect.arrayContaining([
      ['/api/workspaces/7/members?page=0&size=100', undefined],
      ['/api/workspaces/7/invitations?page=0&size=20', undefined],
      ['/api/workspaces/7/invitations', 'POST'],
      ['/api/workspaces/7/invitations/22', 'DELETE'],
      ['/api/workspaces/7/members/2', 'PATCH'],
      ['/api/workspaces/7/members/2', 'DELETE'],
      ['/api/workspaces/7/ownership-transfer', 'POST'],
    ]))
  })

  it('OWNER는 목록에서 대상·만료를 보고 해당 초대를 취소한다', async () => {
    const fetch = vi.fn()
      .mockResolvedValueOnce(members([{ id: '1', displayName: 'Alice', role: 'OWNER' }]))
      .mockResolvedValueOnce(invitations())
      .mockResolvedValueOnce(new Response(null, { status: 204 }))
      .mockResolvedValueOnce(invitations([]))
    const { host } = render(fetch)
    button(host, '멤버 목록 열기').click()
    await vi.waitFor(() => expect(host.textContent).toContain('bob@example.test'))
    expect(host.textContent).toContain('만료됨')
    button(host, '초대 취소').click()
    await vi.waitFor(() => expect(host.textContent).toContain('초대를 취소했습니다'))
    await vi.waitFor(() => expect(host.textContent).toContain('대기 중인 초대가 없습니다.'))
    expect(fetch.mock.calls.map(([path, init]) => [path, init?.method])).toEqual([
      ['/api/workspaces/7/members?page=0&size=100', undefined],
      ['/api/workspaces/7/invitations?page=0&size=20', undefined],
      ['/api/workspaces/7/invitations/22', 'DELETE'],
      ['/api/workspaces/7/invitations?page=0&size=20', undefined],
    ])
  })

  it('VIEWER에게 관리 DML을 렌더링하지 않는다', async () => {
    const fetch = vi.fn().mockResolvedValue(members([{ id: '4', displayName: 'Dave', role: 'VIEWER' }]))
    const viewer = { ...workspace, myRole: 'VIEWER' as const }
    vi.stubGlobal('fetch', fetch)
    const host = document.createElement('div'); document.body.append(host)
    app = createApp(WorkspaceMembershipManager, { workspace: viewer }); app.mount(host)
    button(host, '멤버 목록 열기').click()
    await vi.waitFor(() => expect(host.textContent).toContain('Dave'))
    expect(host.querySelector('#workspace-invite-email')).toBeNull()
    expect(host.textContent).not.toContain('소유권 이전')
    expect(fetch).toHaveBeenCalledOnce()
  })
})
