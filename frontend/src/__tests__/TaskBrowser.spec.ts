import { afterEach, describe, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App } from 'vue'
import TaskBrowser from '../components/TaskBrowser.vue'
import ProjectBrowser from '../components/ProjectBrowser.vue'

let app: App | undefined
afterEach(() => { app?.unmount(); app = undefined; document.body.replaceChildren(); vi.unstubAllGlobals() })
const one = { id: '10', workspaceId: '1', projectId: '7', status: 'TODO', priority: 'MEDIUM', title: '첫 작업', assigneeId: null, dueDate: null }
const two = { id: '11', workspaceId: '1', projectId: '7', status: 'TODO', priority: 'MEDIUM', title: '다음 작업', assigneeId: null, dueDate: null }
function json(value: unknown, status = 200) { return new Response(JSON.stringify(value), { status }) }
function list(items: unknown[] = [one], page = 0, hasNext = false) { return json({ items, page, size: 20, hasNext }) }
function render() {
  const host = document.createElement('div'); document.body.append(host)
  app = createApp(TaskBrowser, { workspaceId: '1', projectId: '7', projectName: '내 프로젝트' })
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

describe('Task 목록·상세 화면', () => {
  it('목록 로딩 후 선택한 상세와 줄바꿈을 일반 텍스트로 표시한다', async () => {
    const pending = deferred()
    const assigned = { ...one, assigneeId: '4', dueDate: '2027-04-05' }
    const fetch = vi.fn().mockReturnValueOnce(pending.promise).mockResolvedValueOnce(json({ ...assigned, version: '0', description: '첫 줄\n<script>alert(1)</script>' }))
    vi.stubGlobal('fetch', fetch); const host = render(); await nextTick()
    expect(host.textContent).toContain('작업을 불러오고 있어요')
    expect(button(host, '작업 새로고침').disabled).toBe(true)
    pending.resolve(list([assigned])); await vi.waitFor(() => expect(host.querySelector('.task-choice')).not.toBeNull())
    expect(host.textContent).toContain('목록에서 작업을 선택')
    button(host, one.title).click()
    await vi.waitFor(() => expect(host.querySelector('.task-detail h5')?.textContent).toBe(one.title))
    expect(host.querySelector('.task-description')?.textContent).toBe('첫 줄\n<script>alert(1)</script>')
    expect(host.textContent).toContain('담당자 ID: 4 · 마감일: 2027-04-05')
    expect(host.querySelector('script')).toBeNull(); expect(button(host, one.title).getAttribute('aria-pressed')).toBe('true')
    expect(fetch).toHaveBeenLastCalledWith('/api/workspaces/1/projects/7/tasks/10', expect.any(Object))
  })
  it('빈 목록과 nullable 설명을 구별해서 안내한다', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(list([])).mockResolvedValueOnce(list())
      .mockResolvedValueOnce(json({ ...one, version: '0', description: null })))
    const host = render(); await vi.waitFor(() => expect(host.textContent).toContain('이 프로젝트에는 아직 작업이 없습니다'))
    expect(host.querySelector('[role="alert"]')).toBeNull(); expect(button(host, '다음 작업 페이지').disabled).toBe(true)
    button(host, '작업 새로고침').click(); await vi.waitFor(() => expect(host.querySelector('.task-choice')).not.toBeNull())
    button(host, one.title).click(); await vi.waitFor(() => expect(host.textContent).toContain('등록된 설명이 없습니다'))
  })
  it.each([404, 503])('목록 %i를 빈 목록으로 숨기지 않고 안전한 안내·재시도로 복구한다', async status => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(json({ status, code: status === 404 ? 'PROJECT_NOT_FOUND' : 'SERVICE_UNAVAILABLE', detail: 'secret SQL' }, status))
      .mockResolvedValueOnce(list()))
    const host = render(); await vi.waitFor(() => expect(host.querySelector('[role="alert"]')).not.toBeNull())
    expect(host.textContent).not.toContain('아직 작업이 없습니다'); expect(host.textContent).not.toContain('secret SQL')
    button(host, '작업 목록 다시 시도').click(); await vi.waitFor(() => expect(host.querySelector('.task-choice')).not.toBeNull())
  })
  it('상세 404·연결 실패는 선택한 이전 내용을 지우고 재시도한다', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(list()).mockResolvedValueOnce(json({ ...one, version: '0', description: 'Old' }))
      .mockResolvedValueOnce(json({ status: 404, code: 'PROJECT_NOT_FOUND' }, 404))
      .mockRejectedValueOnce(new TypeError('offline')).mockResolvedValueOnce(json({ ...one, version: '0', description: 'Recovered' })))
    const host = render(); await vi.waitFor(() => expect(host.querySelector('.task-choice')).not.toBeNull())
    button(host, one.title).click(); await vi.waitFor(() => expect(host.textContent).toContain('Old'))
    button(host, one.title).click(); await vi.waitFor(() => expect(host.textContent).toContain('찾을 수 없거나 접근할 수 없습니다'))
    expect(host.querySelector('.task-detail h5')).toBeNull()
    button(host, '작업 상세 다시 시도').click(); await vi.waitFor(() => expect(host.textContent).toContain('서버에 연결하지 못했습니다'))
    button(host, '작업 상세 다시 시도').click(); await vi.waitFor(() => expect(host.textContent).toContain('Recovered'))
  })
  it('다른 Task 선택 시 이전 요청을 취소하고 늦은 응답을 무시한다', async () => {
    const old = deferred(), current = deferred()
    const fetch = vi.fn().mockResolvedValueOnce(list([one, two])).mockReturnValueOnce(old.promise).mockReturnValueOnce(current.promise)
    vi.stubGlobal('fetch', fetch); const host = render(); await vi.waitFor(() => expect(host.textContent).toContain(two.title))
    button(host, one.title).click(); await nextTick(); const signal = fetch.mock.calls[1]![1].signal as AbortSignal
    button(host, two.title).click(); await nextTick(); expect(signal.aborted).toBe(true)
    expect(host.querySelector('.task-detail h5')).toBeNull()
    current.resolve(json({ ...two, version: '0', description: 'Current' })); await vi.waitFor(() => expect(host.querySelector('.task-detail h5')?.textContent).toBe(two.title))
    old.resolve(json({ ...one, version: '0', description: 'Late' })); await new Promise(done => setTimeout(done, 0)); await nextTick()
    expect(host.querySelector('.task-detail h5')?.textContent).toBe(two.title); expect(host.textContent).not.toContain('Late')
  })
  it('페이지 이동·새로고침은 상세를 초기화하고 현재 페이지·다음 여부를 유지한다', async () => {
    const fetch = vi.fn().mockResolvedValueOnce(list([one], 0, true)).mockResolvedValueOnce(json({ ...one, version: '0', description: null }))
      .mockResolvedValueOnce(list([], 1)).mockResolvedValueOnce(list([], 1)).mockResolvedValueOnce(list())
    vi.stubGlobal('fetch', fetch); const host = render(); await vi.waitFor(() => expect(host.querySelector('.task-choice')).not.toBeNull())
    button(host, one.title).click(); await vi.waitFor(() => expect(host.querySelector('.task-detail h5')).not.toBeNull())
    button(host, '다음 작업 페이지').click(); await vi.waitFor(() => expect(host.textContent).toContain('이 페이지에는 작업이 없습니다'))
    expect(host.querySelector('.task-detail h5')).toBeNull()
    expect(fetch).toHaveBeenLastCalledWith('/api/workspaces/1/projects/7/tasks?page=1&size=20', expect.any(Object))
    button(host, '작업 새로고침').click(); await vi.waitFor(() => expect(fetch).toHaveBeenCalledTimes(4))
    await vi.waitFor(() => expect(button(host, '이전 작업 페이지').disabled).toBe(false))
    button(host, '이전 작업 페이지').click(); await vi.waitFor(() => expect(host.querySelector('.task-choice')).not.toBeNull())
  })
  it('목록 새로고침 중 오래된 상세·목록 응답을 반영하지 않는다', async () => {
    const oldDetail = deferred(), oldList = deferred()
    const fetch = vi.fn().mockResolvedValueOnce(list()).mockReturnValueOnce(oldDetail.promise)
      .mockReturnValueOnce(oldList.promise)
    vi.stubGlobal('fetch', fetch); const host = render(); await vi.waitFor(() => expect(host.querySelector('.task-choice')).not.toBeNull())
    button(host, one.title).click(); await nextTick()
    button(host, '작업 새로고침').click(); await nextTick()
    expect((fetch.mock.calls[1]![1].signal as AbortSignal).aborted).toBe(true)
    app!.unmount(); app = undefined
    expect((fetch.mock.calls[2]![1].signal as AbortSignal).aborted).toBe(true)
    oldDetail.resolve(json({ ...one, version: '0', description: 'Late' })); oldList.resolve(list([two])); await nextTick()
    expect(host.textContent).not.toContain('Late'); expect(host.querySelector('.task-choice')).toBeNull()
  })

  it.each(['list', 'detail'])('Project 전환은 진행 중인 Task %s 요청을 취소하고 새 부모만 표시한다', async stage => {
    const pending = deferred(); let signal: AbortSignal | undefined
    const fetch = vi.fn((path: string, options: RequestInit) => {
      if (path.endsWith('/projects?page=0&size=20')) return Promise.resolve(list([
        { id: '7', workspaceId: '1', name: 'Project A', archived: false }, { id: '8', workspaceId: '1', name: 'Project B', archived: false },
      ]))
      if (path === '/api/workspaces/1/projects/7' || path === '/api/workspaces/1/projects/8') {
        const id = path.substring(path.lastIndexOf('/') + 1); return Promise.resolve(json({ id, workspaceId: '1', name: `Project ${id === '7' ? 'A' : 'B'}`, archived: false, archivedAt: null, description: null, version: '0' }))
      }
      if (path.includes('/projects/8/tasks')) return Promise.resolve(list([]))
      if (stage === 'list' || path.endsWith('/tasks/10')) { signal = options.signal!; return pending.promise }
      return Promise.resolve(list())
    })
    vi.stubGlobal('fetch', fetch)
    const host = document.createElement('div'); document.body.append(host)
    app = createApp(ProjectBrowser, { workspaceId: '1', workspaceName: '공간', myRole: 'VIEWER' }); app.mount(host)
    await vi.waitFor(() => expect(host.querySelectorAll('.project-choice')).toHaveLength(2))
    expect(host.querySelector('.task-section')).toBeNull()
    button(host, 'Project A').click(); await vi.waitFor(() => expect(host.querySelector('.task-section')).not.toBeNull())
    if (stage === 'detail') {
      await vi.waitFor(() => expect(host.querySelector('.task-choice')).not.toBeNull())
      button(host, one.title).click(); await nextTick()
    }
    button(host, 'Project B').click()
    await vi.waitFor(() => expect(host.querySelector('.task-heading .eyebrow')?.textContent).toContain('Project B'))
    expect(signal!.aborted).toBe(true)
    pending.resolve(stage === 'list' ? list() : json({ ...one, version: '0', description: 'Late parent detail' }))
    await new Promise(done => setTimeout(done, 0)); await nextTick()
    expect(host.querySelector('.task-section')?.textContent).toContain('이 프로젝트에는 아직 작업이 없습니다')
    expect(host.querySelector('.task-choice')).toBeNull()
    expect(host.textContent).not.toContain('Late parent detail')
    expect(fetch.mock.calls.every(call => !call[1].method && !call[1].body)).toBe(true)
  })

  it('보관된 Project에서는 Task를 계속 읽되 생성·수정·상태 변경 폼을 숨긴다', async () => {
    const fetch = vi.fn((path: string, _init?: RequestInit) => {
      if (path === '/api/workspaces/1/projects/7/tasks?page=0&size=20') return Promise.resolve(list())
      if (path === '/api/workspaces/1/projects/7/tasks/10') return Promise.resolve(json({ ...one, version: '0', description: null }))
      return Promise.resolve(list([]))
    })
    vi.stubGlobal('fetch', fetch)
    const host = document.createElement('div'); document.body.append(host)
    app = createApp(TaskBrowser, { workspaceId: '1', projectId: '7', projectName: '보관된 프로젝트', myRole: 'OWNER', projectArchived: true })
    app.mount(host)
    await vi.waitFor(() => expect(host.querySelector('.task-choice')).not.toBeNull())
    expect(host.querySelector('.task-create')).toBeNull()
    button(host, one.title).click()
    await vi.waitFor(() => expect(host.querySelector('.task-detail h5')?.textContent).toBe(one.title))
    expect(host.querySelector('.task-update')).toBeNull()
    expect(host.querySelector('.task-status')).toBeNull()
    expect(host.querySelector('.task-history-toggle')).not.toBeNull()
    expect(fetch.mock.calls.every(call => call[1]?.method === undefined)).toBe(true)
  })
})
