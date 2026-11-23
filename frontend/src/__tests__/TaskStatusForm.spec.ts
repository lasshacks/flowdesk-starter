import { afterEach, describe, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, ref, type App } from 'vue'
import TaskStatusForm from '../components/TaskStatusForm.vue'
import TaskBrowser from '../components/TaskBrowser.vue'
import type { Task, TaskStatus } from '../api/tasks'
import type { WorkspaceRole } from '../api/workspaces'

const original: Task = { id: '10', workspaceId: '1', projectId: '7', title: 'Original', description: 'Old description', priority: 'MEDIUM', status: 'TODO', assigneeId: null, dueDate: null, version: '0', archived: false, archivedAt: null }
const changed: Task = { ...original, status: 'DONE', version: '1' }
let app: App | undefined
afterEach(() => { app?.unmount(); app = undefined; document.body.replaceChildren(); vi.unstubAllGlobals() })
function json(value: unknown, status = 200) { return new Response(JSON.stringify(value), { status }) }
function conflict() { return json({ status: 409, code: 'TASK_VERSION_CONFLICT', detail: 'secret SQL' }, 409) }
function role(myRole: WorkspaceRole = 'MEMBER') { return json({ id: '1', name: 'Parent', myRole, version: '0' }) }
function hostElement() { const host = document.createElement('div'); document.body.append(host); return host }
function render(myRole?: WorkspaceRole, updated = vi.fn(), refreshed = vi.fn()) {
  const host = hostElement(); app = createApp(TaskStatusForm, { task: original, myRole, onUpdated: updated, onRefreshed: refreshed }); app.mount(host); return host
}
function button(host: HTMLElement, text: string) { const b = Array.from(host.querySelectorAll('button')).find(b => b.textContent?.includes(text)); expect(b, text).toBeDefined(); return b! }
async function select(host: HTMLElement, target: TaskStatus = 'DONE') {
  const input = host.querySelector<HTMLSelectElement>('#task-status-target')!
  input.value = target; input.dispatchEvent(new Event('change', { bubbles: true })); await nextTick()
}
function submit(host: HTMLElement) { host.querySelector('form.task-status')!.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true })) }
function patchCalls(fetch: ReturnType<typeof vi.fn<typeof globalThis.fetch>>) { return fetch.mock.calls.filter(call => call[1]?.method === 'PATCH') }
function payload(fetch: ReturnType<typeof vi.fn<typeof globalThis.fetch>>, index = 0) { return JSON.parse(patchCalls(fetch)[index]![1]!.body as string) }
async function renderBrowser(fetch: ReturnType<typeof vi.fn<typeof globalThis.fetch>>) {
  vi.stubGlobal('fetch', fetch); const host = hostElement()
  app = createApp(TaskBrowser, { workspaceId: '1', projectId: '7', projectName: 'Parent', myRole: 'MEMBER' }); app.mount(host)
  await vi.waitFor(() => expect(host.querySelector('.task-choice')).not.toBeNull()); button(host, 'Original').click()
  await vi.waitFor(() => expect(host.querySelector('.task-status')).not.toBeNull()); return host
}
const list = { items: [original, { ...original, id: '11', title: 'Other' }], page: 0, size: 20, hasNext: false }

describe('Task 상태 선택·공유 버전·명시적 복구', () => {
  it.each(['VIEWER', undefined] as const)('%s는 상태 폼/요청이 없다', async myRole => {
    const fetch = vi.fn(); vi.stubGlobal('fetch', fetch); const host = render(myRole); await nextTick()
    expect(host.querySelector('form')).toBeNull(); expect(fetch).not.toHaveBeenCalled()
  })
  it.each(['OWNER', 'ADMIN', 'MEMBER'] as const)('%s는 상태를 선택해 저장한다', async myRole => {
    const fetch = vi.fn<typeof globalThis.fetch>().mockResolvedValue(json(changed)); vi.stubGlobal('fetch', fetch)
    const updated = vi.fn(); const host = render(myRole, updated); await select(host); submit(host)
    await vi.waitFor(() => expect(updated).toHaveBeenCalledExactlyOnceWith(changed))
    expect(payload(fetch)).toEqual({ status: 'DONE', version: '0' })
    expect(host.textContent).toContain('작업 상태 저장을 확인했습니다')
  })
  it('중복 submit을 막고 같은 상태 no-op도 같은 버전으로 성공한다', async () => {
    let done!: (response: Response) => void
    const fetch = vi.fn<typeof globalThis.fetch>(() => new Promise(resolve => { done = resolve })); vi.stubGlobal('fetch', fetch)
    const host = render('MEMBER'); submit(host); submit(host); await nextTick()
    expect(fetch).toHaveBeenCalledOnce(); expect(button(host, '상태 저장 중').disabled).toBe(true)
    done(json(original)); await vi.waitFor(() => expect(host.textContent).toContain('작업 상태 저장을 확인했습니다'))
    expect(payload(fetch)).toEqual({ status: 'TODO', version: '0' })
  })
  it('같은 Task props가 바뀌어도 선택/편집 시작 버전을 바꾸지 않는다', async () => {
    const task = ref(original); const host = hostElement()
    app = createApp({ setup: () => () => h(TaskStatusForm, { task: task.value, myRole: 'MEMBER' }) }); app.mount(host)
    const fetch = vi.fn<typeof globalThis.fetch>().mockResolvedValue(conflict()); vi.stubGlobal('fetch', fetch)
    await select(host, 'IN_PROGRESS'); task.value = { ...original, status: 'DONE', version: '8' }; await nextTick(); submit(host)
    await vi.waitFor(() => expect(host.textContent).toContain('다른 변경이 먼저'))
    expect(payload(fetch)).toEqual({ status: 'IN_PROGRESS', version: '0' })
    expect(host.querySelector<HTMLSelectElement>('select')!.value).toBe('IN_PROGRESS')
  })
  it('409 후 상태·일반 필드를 비교하고 명시적 기준 갱신 뒤 별도 저장한다', async () => {
    const latest: Task = { ...original, title: 'Server title', description: 'Server description', priority: 'LOW', status: 'IN_PROGRESS', version: '2' }
    const result = { ...latest, status: 'DONE', version: '3' }
    const fetch = vi.fn<typeof globalThis.fetch>().mockResolvedValueOnce(conflict()).mockResolvedValueOnce(json(latest)).mockResolvedValueOnce(role())
      .mockResolvedValueOnce(json(result)); vi.stubGlobal('fetch', fetch)
    const refreshed = vi.fn(); const host = render('MEMBER', vi.fn(), refreshed); await select(host); submit(host)
    await vi.waitFor(() => expect(host.textContent).toContain('다른 변경이 먼저'))
    submit(host); expect(patchCalls(fetch)).toHaveLength(1); expect(host.textContent).not.toContain('secret SQL')
    button(host, '상태 최신 정보 확인').click(); await vi.waitFor(() => expect(host.textContent).toContain('Server description'))
    expect(refreshed).toHaveBeenCalledExactlyOnceWith(latest)
    expect(host.querySelector('.task-status-comparison')!.textContent).toContain('진행 중')
    expect(host.querySelector('.task-status-comparison')!.textContent).toContain('완료')
    expect(host.querySelector('.task-status-comparison')!.textContent).toContain('낮음')
    expect(button(host, '작업 상태 저장').disabled).toBe(true)
    button(host, '상태 최신 기준으로').click(); await nextTick(); expect(patchCalls(fetch)).toHaveLength(1)
    expect(host.querySelector<HTMLSelectElement>('select')!.value).toBe('DONE')
    submit(host); await vi.waitFor(() => expect(host.textContent).toContain('작업 상태 저장을 확인했습니다'))
    expect(payload(fetch, 1)).toEqual({ status: 'DONE', version: '2' })
  })
  it('최신 VIEWER 역할이면 기준 갱신을 허용하지 않는다', async () => {
    const fetch = vi.fn<typeof globalThis.fetch>().mockResolvedValueOnce(conflict()).mockResolvedValueOnce(json(changed)).mockResolvedValueOnce(role('VIEWER'))
    vi.stubGlobal('fetch', fetch); const host = render('MEMBER'); await select(host); submit(host)
    await vi.waitFor(() => expect(host.textContent).toContain('다른 변경이 먼저'))
    button(host, '상태 최신 정보 확인').click(); await vi.waitFor(() => expect(host.textContent).toContain('변경할 권한이 없습니다'))
    expect(host.textContent).not.toContain('상태 최신 기준으로 다시 편집'); expect(button(host, '작업 상태 저장').disabled).toBe(true)
  })
  it.each(['task', 'role'])('%s GET 실패는 비교/기준 갱신 없이 선택·잠금을 유지한다', async failing => {
    const fetch = vi.fn<typeof globalThis.fetch>().mockResolvedValueOnce(conflict())
      .mockResolvedValueOnce(failing === 'task' ? json({}, 404) : json(changed)).mockResolvedValueOnce(failing === 'role' ? json({}, 404) : role())
    vi.stubGlobal('fetch', fetch); const refreshed = vi.fn(); const host = render('MEMBER', vi.fn(), refreshed); await select(host); submit(host)
    await vi.waitFor(() => expect(host.textContent).toContain('다른 변경이 먼저')); button(host, '상태 최신 정보 확인').click()
    await vi.waitFor(() => expect(host.textContent).toContain('변경 권한을 확인하지 못했습니다'))
    expect(refreshed).not.toHaveBeenCalled(); expect(host.querySelector('.task-status-comparison')).toBeNull()
    expect(host.querySelector<HTMLSelectElement>('select')!.value).toBe('DONE'); expect(button(host, '작업 상태 저장').disabled).toBe(true)
  })
  it.each(['offline', 'bad200', '500'])('%s 결과 불명은 GET 내용 일치로 성공/자동 재전송하지 않는다', async kind => {
    const fetch = vi.fn<typeof globalThis.fetch>()
    if (kind === 'offline') fetch.mockRejectedValueOnce(new TypeError('offline'))
    else fetch.mockResolvedValueOnce(json(kind === 'bad200' ? { ...changed, version: '9' } : {}, kind === '500' ? 500 : 200))
    fetch.mockResolvedValueOnce(json(changed)).mockResolvedValueOnce(role()); vi.stubGlobal('fetch', fetch)
    const updated = vi.fn(); const host = render('MEMBER', updated); await select(host); submit(host)
    await vi.waitFor(() => expect(host.textContent).toContain('상태 변경 결과를 확인하지 못했습니다'))
    button(host, '상태 최신 정보 확인').click(); await vi.waitFor(() => expect(host.querySelector('.task-status-comparison')).not.toBeNull())
    expect(updated).not.toHaveBeenCalled(); expect(button(host, '작업 상태 저장').disabled).toBe(true)
    submit(host); expect(patchCalls(fetch)).toHaveLength(1); expect(host.querySelector('.rename-success')).toBeNull()
  })
  it('재확인이 실패하면 이전 비교 결과도 지우고 선택·잠금을 유지한다', async () => {
    const fetch = vi.fn<typeof globalThis.fetch>().mockResolvedValueOnce(conflict()).mockResolvedValueOnce(json(changed)).mockResolvedValueOnce(role())
      .mockRejectedValueOnce(new TypeError('offline')).mockResolvedValueOnce(role())
    vi.stubGlobal('fetch', fetch); const host = render('MEMBER'); await select(host); submit(host)
    await vi.waitFor(() => expect(host.querySelector('.task-status-recovery')).not.toBeNull())
    button(host, '상태 최신 정보 확인').click(); await vi.waitFor(() => expect(host.querySelector('.task-status-comparison')).not.toBeNull())
    button(host, '상태 최신 정보 확인').click(); await vi.waitFor(() => expect(host.textContent).toContain('변경 권한을 확인하지 못했습니다'))
    expect(host.querySelector('.task-status-comparison')).toBeNull(); expect(host.textContent).not.toContain('상태 최신 기준으로 다시 편집')
    expect(host.querySelector<HTMLSelectElement>('select')!.value).toBe('DONE'); expect(button(host, '작업 상태 저장').disabled).toBe(true)
  })
  it('405 확정 거부는 선택을 보존하고 내부 오류 내용을 노출하지 않는다', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json({ status: 405, detail: 'secret SQL' }, 405)))
    const host = render('MEMBER'); await select(host); submit(host)
    await vi.waitFor(() => expect(host.textContent).toContain('현재는 작업 상태를 변경할 수 없습니다'))
    expect(host.textContent).not.toContain('secret SQL'); expect(host.querySelector('.task-status-recovery')).toBeNull()
    expect(host.querySelector<HTMLSelectElement>('select')!.value).toBe('DONE'); expect(button(host, '작업 상태 저장').disabled).toBe(false)
  })
  it('언마운트는 PATCH와 늦은 성공 이벤트를 취소한다', async () => {
    let done!: (response: Response) => void
    const fetch = vi.fn<typeof globalThis.fetch>(() => new Promise(resolve => { done = resolve })); vi.stubGlobal('fetch', fetch)
    const updated = vi.fn(); const host = render('MEMBER', updated); await select(host); submit(host); await nextTick()
    const signal = fetch.mock.calls[0]![1]!.signal!; app!.unmount(); app = undefined
    expect(signal.aborted).toBe(true); done(json(changed)); await new Promise(resolve => setTimeout(resolve, 0)); expect(updated).not.toHaveBeenCalled()
  })
  it('복구 중 언마운트는 두 GET과 늦은 비교 결과를 취소한다', async () => {
    let taskDone!: (response: Response) => void, roleDone!: (response: Response) => void
    const fetch = vi.fn<typeof globalThis.fetch>().mockResolvedValueOnce(conflict())
      .mockImplementationOnce(() => new Promise(resolve => { taskDone = resolve })).mockImplementationOnce(() => new Promise(resolve => { roleDone = resolve }))
    vi.stubGlobal('fetch', fetch); const refreshed = vi.fn(); const host = render('MEMBER', vi.fn(), refreshed); await select(host); submit(host)
    await vi.waitFor(() => expect(host.textContent).toContain('다른 변경이 먼저')); button(host, '상태 최신 정보 확인').click(); await nextTick()
    const signals = fetch.mock.calls.slice(1).map(call => call[1]!.signal!)
    app!.unmount(); app = undefined; expect(signals.every(signal => signal.aborted)).toBe(true)
    taskDone(json(changed)); roleDone(role()); await new Promise(resolve => setTimeout(resolve, 0)); expect(refreshed).not.toHaveBeenCalled()
  })

  it('상태 성공은 목록·상세만 갱신하고 일반 편집 draft/시작 버전을 바꾸지 않는다', async () => {
    const fetch = vi.fn<typeof globalThis.fetch>().mockResolvedValueOnce(json(list)).mockResolvedValueOnce(json(original))
      .mockResolvedValueOnce(json(changed)).mockResolvedValueOnce(conflict())
    const host = await renderBrowser(fetch)
    const title = host.querySelector<HTMLInputElement>('#task-update-title')!; title.value = 'Unsaved title'; title.dispatchEvent(new Event('input', { bubbles: true }))
    await select(host); submit(host)
    await vi.waitFor(() => expect(host.querySelector('.task-choice')!.textContent).toContain('완료'))
    expect(host.querySelector('.task-detail > p')!.textContent).toContain('완료'); expect(title.value).toBe('Unsaved title')
    expect(host.querySelectorAll('.task-choice')[1]!.textContent).toContain('Other · 할 일')
    expect(fetch).toHaveBeenCalledTimes(3)
    host.querySelector('form.task-update')!.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
    await vi.waitFor(() => expect(host.querySelector('.task-update-recovery')).not.toBeNull())
    expect(payload(fetch, 1).version).toBe('0'); expect(title.value).toBe('Unsaved title')
  })
  it('일반 수정 성공 뒤에도 상태 선택/이전 버전을 유지해 충돌을 확인한다', async () => {
    const edited: Task = { ...original, title: 'Edited title', version: '1' }
    const fetch = vi.fn<typeof globalThis.fetch>().mockResolvedValueOnce(json(list)).mockResolvedValueOnce(json(original))
      .mockResolvedValueOnce(json(edited)).mockResolvedValueOnce(conflict())
    const host = await renderBrowser(fetch); await select(host)
    const title = host.querySelector<HTMLInputElement>('#task-update-title')!; title.value = 'Edited title'; title.dispatchEvent(new Event('input', { bubbles: true })); await nextTick()
    host.querySelector('form.task-update')!.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
    await vi.waitFor(() => expect(host.querySelector('.task-detail > h5')!.textContent).toBe('Edited title'))
    expect(host.querySelector<HTMLSelectElement>('#task-status-target')!.value).toBe('DONE'); submit(host)
    await vi.waitFor(() => expect(host.querySelector('.task-status-recovery')).not.toBeNull())
    expect(payload(fetch, 1)).toEqual({ status: 'DONE', version: '0' })
  })
  it('늦은 복구 GET의 이전 버전은 새 일반 수정 결과를 화면에서 되돌리지 않는다', async () => {
    let done!: (response: Response) => void
    const edited = { ...original, title: 'Newer title', version: '1' }
    const fetch = vi.fn<typeof globalThis.fetch>().mockResolvedValueOnce(json(list)).mockResolvedValueOnce(json(original)).mockResolvedValueOnce(conflict())
      .mockImplementationOnce(() => new Promise(resolve => { done = resolve })).mockResolvedValueOnce(role()).mockResolvedValueOnce(json(edited))
    const host = await renderBrowser(fetch); await select(host); submit(host)
    await vi.waitFor(() => expect(host.querySelector('.task-status-recovery')).not.toBeNull()); button(host, '상태 최신 정보 확인').click(); await nextTick()
    const title = host.querySelector<HTMLInputElement>('#task-update-title')!; title.value = 'Newer title'; title.dispatchEvent(new Event('input', { bubbles: true })); await nextTick()
    host.querySelector('form.task-update')!.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }))
    await vi.waitFor(() => expect(host.querySelector('.task-detail > h5')!.textContent).toBe('Newer title'))
    done(json(original)); await vi.waitFor(() => expect(host.querySelector('.task-status-recovery')!.textContent).toContain('변경 권한을 확인하지 못했습니다'))
    expect(host.querySelector('.task-status-comparison')).toBeNull()
    expect(button(host, '작업 상태 저장').disabled).toBe(true)
    expect(host.querySelector('.task-detail > h5')!.textContent).toBe('Newer title')
    expect(host.querySelector('.task-choice')!.textContent).toContain('Newer title')
  })
  it('다른 Task 선택은 진행 중 상태 요청을 취소하고 늦은 응답을 버린다', async () => {
    let done!: (response: Response) => void
    const other = { ...original, id: '11', title: 'Other' }
    const fetch = vi.fn<typeof globalThis.fetch>().mockResolvedValueOnce(json(list)).mockResolvedValueOnce(json(original))
      .mockImplementationOnce(() => new Promise(resolve => { done = resolve })).mockResolvedValueOnce(json(other))
    const host = await renderBrowser(fetch); await select(host); submit(host); await nextTick()
    const signal = patchCalls(fetch)[0]![1]!.signal!; button(host, 'Other').click()
    await vi.waitFor(() => expect(host.querySelector('.task-detail > h5')!.textContent).toBe('Other'))
    expect(signal.aborted).toBe(true); done(json(changed)); await new Promise(resolve => setTimeout(resolve, 0)); await nextTick()
    expect(host.querySelector('.task-detail > h5')!.textContent).toBe('Other')
    expect(host.querySelector<HTMLSelectElement>('#task-status-target')!.value).toBe('TODO')
  })
})
