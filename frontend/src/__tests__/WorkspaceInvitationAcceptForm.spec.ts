import { afterEach, describe, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App as VueApp } from 'vue'
import WorkspaceInvitationAcceptForm from '../components/WorkspaceInvitationAcceptForm.vue'

let app: VueApp | undefined
afterEach(() => { app?.unmount(); app = undefined; document.body.replaceChildren(); vi.unstubAllGlobals() })
const code = 'z'.repeat(43)
function json(value: unknown, status = 200, headers: HeadersInit = {}) { return new Response(JSON.stringify(value), { status, headers }) }
function button(host: HTMLElement, text: string) {
  const found = Array.from(host.querySelectorAll('button')).find(item => item.textContent?.trim() === text)
  expect(found, text).toBeDefined(); return found!
}
function setInput(element: HTMLInputElement, value: string) {
  Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!.set!.call(element, value)
  element.dispatchEvent(new Event('input', { bubbles: true }))
}

describe('Workspace 초대 수락 폼', () => {
  it('코드를 한 번 전송하고 확정된 공간에 진입하도록 상위에 알린다', async () => {
    const accepted = { workspaceId: '7', id: '8', displayName: 'Eve', role: 'MEMBER' }
    const fetch = vi.fn().mockResolvedValue(json(accepted, 201, { Location: '/api/workspaces/7/members/8' }))
    const onAccepted = vi.fn()
    vi.stubGlobal('fetch', fetch)
    const host = document.createElement('div'); document.body.append(host)
    app = createApp(WorkspaceInvitationAcceptForm, { onAccepted }); app.mount(host)
    const input = host.querySelector<HTMLInputElement>('#workspace-invitation-code')!
    setInput(input, code); await nextTick(); button(host, '초대 수락').click()
    await vi.waitFor(() => expect(onAccepted).toHaveBeenCalledWith('7'))
    expect(input.value).toBe('')
    expect(host.textContent).toContain('Eve 계정이 워크스페이스에 참여했습니다')
    expect(fetch).toHaveBeenCalledExactlyOnceWith('/api/workspace-invitations/accept', expect.objectContaining({
      method: 'POST', body: JSON.stringify({ code }), credentials: 'omit', cache: 'no-store', redirect: 'error',
    }))
  })

  it('대상 actor 불일치/없는 코드 404를 구분 없이 안전하게 안내한다', async () => {
    const fetch = vi.fn().mockResolvedValue(json({ status: 404, code: 'WORKSPACE_INVITATION_UNAVAILABLE', detail: 'secret' }, 404))
    vi.stubGlobal('fetch', fetch)
    const host = document.createElement('div'); document.body.append(host)
    app = createApp(WorkspaceInvitationAcceptForm); app.mount(host)
    const input = host.querySelector<HTMLInputElement>('#workspace-invitation-code')!
    setInput(input, code); await nextTick(); button(host, '초대 수락').click()
    await vi.waitFor(() => expect(host.textContent).toContain('현재 서버 행위자가 초대 대상이 아닙니다'))
    expect(host.textContent).not.toContain('secret')
    expect(fetch).toHaveBeenCalledOnce()
  })
})
