import { afterEach, describe, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App } from 'vue'
import ProjectArchiveControls from '../components/ProjectArchiveControls.vue'
import type { Project } from '../api/projects'

let app: App | undefined
afterEach(() => { app?.unmount(); app = undefined; document.body.replaceChildren(); vi.unstubAllGlobals() })
const active: Project = { id: '21', workspaceId: '3', name: 'Project', description: null, version: '4', archived: false, archivedAt: null }
const archived: Project = { ...active, version: '5', archived: true, archivedAt: '2026-11-23T12:30:00Z' }
function json(value: unknown, status = 200) { return new Response(JSON.stringify(value), { status }) }
function render(project = active, myRole: 'OWNER' | 'ADMIN' | 'MEMBER' | 'VIEWER' = 'OWNER') {
  const host = document.createElement('div'); document.body.append(host)
  const onUpdated = vi.fn(); app = createApp(ProjectArchiveControls, { project, myRole, onUpdated }); app.mount(host)
  return { host, onUpdated }
}
function button(host: HTMLElement, text: string) {
  const found = Array.from(host.querySelectorAll('button')).find(item => item.textContent?.includes(text))
  expect(found, text).toBeDefined(); return found!
}

describe('Project 보관 조작', () => {
  it.each(['MEMBER', 'VIEWER'] as const)('%s에게 보관·복구 조작을 표시하지 않는다', role => {
    const fetch = vi.fn(); vi.stubGlobal('fetch', fetch); const { host } = render(active, role)
    expect(host.querySelector('.project-archive-controls')).toBeNull(); expect(fetch).not.toHaveBeenCalled()
  })

  it('OWNER는 보관 확인 뒤 서버 DTO를 emit한다', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json(archived)))
    const { host, onUpdated } = render(); button(host, '프로젝트 보관').click(); await nextTick()
    expect(host.textContent).toContain('Task와 이력은 계속 조회할 수 있습니다')
    button(host, '취소').click(); expect(onUpdated).not.toHaveBeenCalled()
    button(host, '프로젝트 보관').click(); button(host, '프로젝트 보관 확인').click()
    await vi.waitFor(() => expect(onUpdated).toHaveBeenCalledWith(archived))
  })

  it('409 뒤 최신 상태와 역할을 확인해도 자동 재요청하지 않는다', async () => {
    const latest = { ...active, version: '6', name: 'Other edit' }
    const fetch = vi.fn().mockResolvedValueOnce(json({ status: 409, code: 'PROJECT_VERSION_CONFLICT' }, 409))
      .mockResolvedValueOnce(json(latest)).mockResolvedValueOnce(json({ id: '3', name: 'Space', myRole: 'OWNER', version: '0' }))
    vi.stubGlobal('fetch', fetch); const { host, onUpdated } = render()
    button(host, '프로젝트 보관').click(); await nextTick(); button(host, '프로젝트 보관 확인').click()
    await vi.waitFor(() => expect(host.textContent).toContain('다른 변경이 먼저 저장됐습니다'))
    button(host, '최신 프로젝트 상태 확인').click()
    await vi.waitFor(() => expect(host.textContent).toContain('현재 서버 상태: 활성 · 버전 6'))
    button(host, '최신 상태를 기준으로 다시 확인').click()
    expect(onUpdated).toHaveBeenCalledWith(latest)
    expect(fetch.mock.calls.filter(call => call[1]?.method === 'PATCH')).toHaveLength(1)
  })

  it('보관된 프로젝트에는 복구 확인을 제공한다', async () => {
    const restored = { ...active, version: '6' }
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json(restored)))
    const { host, onUpdated } = render(archived, 'ADMIN')
    button(host, '프로젝트 복구').click(); await nextTick(); expect(host.textContent).toContain('기존 Task 변경도 다시 허용됩니다')
    button(host, '프로젝트 복구 확인').click()
    await vi.waitFor(() => expect(onUpdated).toHaveBeenCalledWith(restored))
  })
})
