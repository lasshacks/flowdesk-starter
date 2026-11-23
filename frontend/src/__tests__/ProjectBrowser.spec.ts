import { afterEach, describe, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App } from 'vue'
import ProjectBrowser from '../components/ProjectBrowser.vue'
import WorkspaceBrowser from '../components/WorkspaceBrowser.vue'

vi.mock('../components/TaskBrowser.vue', () => ({ default: { template: '<div class="task-stub" />' } }))

let app: App | undefined
afterEach(() => { app?.unmount(); app = undefined; document.body.replaceChildren(); vi.unstubAllGlobals() })
const one = { id: '10', workspaceId: '1', name: '첫 프로젝트', archived: false }
const two = { id: '11', workspaceId: '1', name: '다음 프로젝트', archived: false }
function json(value: unknown, status = 200) { return new Response(JSON.stringify(value), { status }) }
function list(items: unknown[] = [one], page = 0, hasNext = false) { return json({ items, page, size: 20, hasNext }) }
function render(integrated = false, myRole?: 'OWNER' | 'ADMIN' | 'MEMBER' | 'VIEWER') {
  const host = document.createElement('div'); document.body.append(host)
  app = integrated ? createApp(WorkspaceBrowser) : createApp(ProjectBrowser, { workspaceId: '1', workspaceName: '내 공간', myRole })
  app.mount(host); return host
}
function button(host: HTMLElement, label: string) {
  const result = Array.from(host.querySelectorAll('button')).find(element => element.textContent?.includes(label) || element.getAttribute('aria-label') === label)
  expect(result, label).toBeDefined(); return result!
}
function deferred() {
  let resolve!: (value: Response) => void
  return { promise: new Promise<Response>(done => { resolve = done }), resolve: (value: Response) => resolve(value) }
}

describe('Project 목록·상세 화면', () => {
  it('보관 포함 필터로 목록을 조회하고 보관 배지와 상세 상태를 표시한다', async () => {
    const archivedItem = { ...one, archived: true }
    const fetch = vi.fn().mockResolvedValueOnce(list()).mockResolvedValueOnce(list([archivedItem]))
      .mockResolvedValueOnce(json({ ...archivedItem, version: '2', archivedAt: '2026-11-23T12:30:00Z', description: null }))
    vi.stubGlobal('fetch', fetch); const host = render(false, 'VIEWER')
    await vi.waitFor(() => expect(host.querySelector('.project-choice')).not.toBeNull())
    const filter = host.querySelector<HTMLInputElement>('.archive-list-filter input')!
    filter.checked = true; filter.dispatchEvent(new Event('change', { bubbles: true }))
    await vi.waitFor(() => expect(button(host, one.name).textContent).toContain('보관됨'))
    expect(fetch.mock.calls[1]![0]).toBe('/api/workspaces/1/projects?page=0&size=20&includeArchived=true')
    button(host, one.name).click()
    await vi.waitFor(() => expect(host.textContent).toContain('보관된 프로젝트입니다'))
    expect(host.querySelector('.project-archive-controls')).toBeNull()
  })

  it('목록 로딩 후 선택한 상세와 줄바꿈을 일반 텍스트로 표시한다', async () => {
    const pending = deferred()
    const fetch = vi.fn().mockReturnValueOnce(pending.promise).mockResolvedValueOnce(json({ ...one, version: '0', archivedAt: null, description: '첫 줄\n<script>alert(1)</script>' }))
    vi.stubGlobal('fetch', fetch); const host = render(); await nextTick()
    expect(host.textContent).toContain('프로젝트를 불러오고 있어요')
    expect(button(host, '프로젝트 새로고침').disabled).toBe(true)
    pending.resolve(list()); await vi.waitFor(() => expect(host.querySelector('.project-choice')).not.toBeNull())
    expect(host.textContent).toContain('목록에서 프로젝트를 선택')
    button(host, one.name).click()
    await vi.waitFor(() => expect(host.querySelector('.project-detail h5')?.textContent).toBe(one.name))
    expect(host.querySelector('.project-description')?.textContent).toBe('첫 줄\n<script>alert(1)</script>')
    expect(host.querySelector('script')).toBeNull(); expect(button(host, one.name).getAttribute('aria-pressed')).toBe('true')
    expect(fetch).toHaveBeenLastCalledWith('/api/workspaces/1/projects/10', expect.any(Object))
  })
  it('빈 목록과 nullable 설명을 구별해서 안내한다', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(list([])).mockResolvedValueOnce(list())
      .mockResolvedValueOnce(json({ ...one, version: '0', archivedAt: null, description: null })))
    const host = render(); await vi.waitFor(() => expect(host.textContent).toContain('이 공간에는 아직 프로젝트가 없습니다'))
    expect(host.querySelector('[role="alert"]')).toBeNull(); expect(button(host, '다음 프로젝트 페이지').disabled).toBe(true)
    button(host, '프로젝트 새로고침').click(); await vi.waitFor(() => expect(host.querySelector('.project-choice')).not.toBeNull())
    button(host, one.name).click(); await vi.waitFor(() => expect(host.textContent).toContain('등록된 설명이 없습니다'))
  })
  it.each([404, 503])('목록 %i를 빈 목록으로 숨기지 않고 안전한 안내·재시도로 복구한다', async status => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(json({ status, code: status === 404 ? 'PROJECT_NOT_FOUND' : 'SERVICE_UNAVAILABLE', detail: 'secret SQL' }, status))
      .mockResolvedValueOnce(list()))
    const host = render(); await vi.waitFor(() => expect(host.querySelector('[role="alert"]')).not.toBeNull())
    expect(host.textContent).not.toContain('아직 프로젝트가 없습니다'); expect(host.textContent).not.toContain('secret SQL')
    button(host, '프로젝트 목록 다시 시도').click(); await vi.waitFor(() => expect(host.querySelector('.project-choice')).not.toBeNull())
  })
  it('상세 404·연결 실패는 선택한 이전 내용을 지우고 재시도한다', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(list()).mockResolvedValueOnce(json({ ...one, version: '0', archivedAt: null, description: 'Old' }))
      .mockResolvedValueOnce(json({ status: 404, code: 'PROJECT_NOT_FOUND' }, 404))
      .mockRejectedValueOnce(new TypeError('offline')).mockResolvedValueOnce(json({ ...one, version: '0', archivedAt: null, description: 'Recovered' })))
    const host = render(); await vi.waitFor(() => expect(host.querySelector('.project-choice')).not.toBeNull())
    button(host, one.name).click(); await vi.waitFor(() => expect(host.textContent).toContain('Old'))
    button(host, one.name).click(); await vi.waitFor(() => expect(host.textContent).toContain('찾을 수 없거나 접근할 수 없습니다'))
    expect(host.querySelector('.project-detail h5')).toBeNull()
    button(host, '프로젝트 상세 다시 시도').click(); await vi.waitFor(() => expect(host.textContent).toContain('서버에 연결하지 못했습니다'))
    button(host, '프로젝트 상세 다시 시도').click(); await vi.waitFor(() => expect(host.textContent).toContain('Recovered'))
  })
  it('다른 Project 선택 시 이전 요청을 취소하고 늦은 응답을 무시한다', async () => {
    const old = deferred(), current = deferred()
    const fetch = vi.fn().mockResolvedValueOnce(list([one, two])).mockReturnValueOnce(old.promise).mockReturnValueOnce(current.promise)
    vi.stubGlobal('fetch', fetch); const host = render(); await vi.waitFor(() => expect(host.textContent).toContain(two.name))
    button(host, one.name).click(); await nextTick(); const signal = fetch.mock.calls[1]![1].signal as AbortSignal
    button(host, two.name).click(); await nextTick(); expect(signal.aborted).toBe(true)
    expect(host.querySelector('.project-detail h5')).toBeNull()
    current.resolve(json({ ...two, version: '0', archivedAt: null, description: 'Current' })); await vi.waitFor(() => expect(host.querySelector('.project-detail h5')?.textContent).toBe(two.name))
    old.resolve(json({ ...one, version: '0', archivedAt: null, description: 'Late' })); await new Promise(done => setTimeout(done, 0)); await nextTick()
    expect(host.querySelector('.project-detail h5')?.textContent).toBe(two.name); expect(host.textContent).not.toContain('Late')
  })
  it('페이지 이동·새로고침은 상세를 초기화하고 현재 페이지·다음 여부를 유지한다', async () => {
    const fetch = vi.fn().mockResolvedValueOnce(list([one], 0, true)).mockResolvedValueOnce(json({ ...one, version: '0', archivedAt: null, description: null }))
      .mockResolvedValueOnce(list([], 1)).mockResolvedValueOnce(list([], 1)).mockResolvedValueOnce(list())
    vi.stubGlobal('fetch', fetch); const host = render(); await vi.waitFor(() => expect(host.querySelector('.project-choice')).not.toBeNull())
    button(host, one.name).click(); await vi.waitFor(() => expect(host.querySelector('.project-detail h5')).not.toBeNull())
    button(host, '다음 프로젝트 페이지').click(); await vi.waitFor(() => expect(host.textContent).toContain('이 페이지에는 프로젝트가 없습니다'))
    expect(host.querySelector('.project-detail h5')).toBeNull()
    expect(fetch).toHaveBeenLastCalledWith('/api/workspaces/1/projects?page=1&size=20', expect.any(Object))
    button(host, '프로젝트 새로고침').click(); await vi.waitFor(() => expect(fetch).toHaveBeenCalledTimes(4))
    await vi.waitFor(() => expect(button(host, '이전 프로젝트 페이지').disabled).toBe(false))
    button(host, '이전 프로젝트 페이지').click(); await vi.waitFor(() => expect(host.querySelector('.project-choice')).not.toBeNull())
  })
  it('목록 새로고침 중 오래된 상세·목록 응답을 반영하지 않는다', async () => {
    const oldDetail = deferred(), oldList = deferred()
    const fetch = vi.fn().mockResolvedValueOnce(list()).mockReturnValueOnce(oldDetail.promise)
      .mockReturnValueOnce(oldList.promise)
    vi.stubGlobal('fetch', fetch); const host = render(); await vi.waitFor(() => expect(host.querySelector('.project-choice')).not.toBeNull())
    button(host, one.name).click(); await nextTick()
    button(host, '프로젝트 새로고침').click(); await nextTick()
    expect((fetch.mock.calls[1]![1].signal as AbortSignal).aborted).toBe(true)
    app!.unmount(); app = undefined
    expect((fetch.mock.calls[2]![1].signal as AbortSignal).aborted).toBe(true)
    oldDetail.resolve(json({ ...one, version: '0', archivedAt: null, description: 'Late' })); oldList.resolve(list([two])); await nextTick()
    expect(host.textContent).not.toContain('Late'); expect(host.querySelector('.project-choice')).toBeNull()
  })
  it.each(['list', 'detail'])('공간 전환은 진행 중인 Project %s 요청과 페이지·선택을 종료한다', async stage => {
    const a = { id: '1', name: '공간 A', myRole: 'OWNER', version: '0' }
    const b = { id: '3', name: '공간 B', myRole: 'OWNER', version: '1' }
    const pending = deferred(); let signal: AbortSignal | undefined
    const fetch = vi.fn((path: string, options: RequestInit) => {
      if (path === '/api/workspaces?page=0&size=20') return Promise.resolve(json({ items: [a, b], page: 0, size: 20, hasNext: false }))
      if (path === '/api/workspaces/1') return Promise.resolve(json(a))
      if (path === '/api/workspaces/3') return Promise.resolve(json(b))
      if (path === '/api/workspaces/3/projects?page=0&size=20') return Promise.resolve(list([]))
      if (stage === 'list' || path === '/api/workspaces/1/projects/10') { signal = options.signal!; return pending.promise }
      return Promise.resolve(list())
    })
    vi.stubGlobal('fetch', fetch); const host = render(true)
    await vi.waitFor(() => expect(host.textContent).toContain(a.name)); expect(host.querySelector('.project-section')).toBeNull()
    button(host, a.name).click(); await vi.waitFor(() => expect(host.querySelector('.project-section')).not.toBeNull())
    if (stage === 'detail') { await vi.waitFor(() => expect(host.querySelector('.project-choice')).not.toBeNull()); button(host, one.name).click(); await nextTick() }
    button(host, b.name).click(); await vi.waitFor(() => expect(host.querySelector('.project-section')?.textContent).toContain('이 공간에는 아직 프로젝트가 없습니다'))
    expect(signal!.aborted).toBe(true)
    pending.resolve(stage === 'list' ? list() : json({ ...one, version: '0', archivedAt: null, description: 'Wrong space' }))
    await new Promise(done => setTimeout(done, 0)); await nextTick()
    expect(host.querySelector('.project-section')?.textContent).not.toContain(one.name)
    expect(host.querySelector('.project-detail h5')).toBeNull()
    expect(host.querySelector('.project-heading .eyebrow')?.textContent).toContain(b.name)
    expect(fetch.mock.calls.every(call => !call[1]?.method)).toBe(true)
  })
})
