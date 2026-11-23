import { afterEach, describe, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, ref, type App } from 'vue'
import TaskUpdateForm from '../components/TaskUpdateForm.vue'
import TaskBrowser from '../components/TaskBrowser.vue'
import type { Task } from '../api/tasks'
import type { WorkspaceRole } from '../api/workspaces'

const original: Task = { id: '10', workspaceId: '1', projectId: '7', status: 'TODO', priority: 'MEDIUM', title: 'Original', description: 'Old description', assigneeId: null, dueDate: null, version: '0', archived: false, archivedAt: null }
const changed: Task = { ...original, title: 'Draft', description: 'My description', priority: 'HIGH', version: '1' }

let app: App | undefined
afterEach(() => { app?.unmount(); app = undefined; document.body.replaceChildren(); vi.unstubAllGlobals() })
function json(value: unknown, status = 200) { return new Response(JSON.stringify(value), { status }) }
function conflict() { return json({ status: 409, code: 'TASK_VERSION_CONFLICT', detail: 'secret SQL' }, 409) }
function role(myRole: WorkspaceRole = 'OWNER') { return json({ id: '1', name: 'Parent', myRole, version: '0' }) }
function memberPage() { return json({ items: [{ id: '2', displayName: 'Bob', role: 'ADMIN' }, { id: '4', displayName: 'Dave', role: 'VIEWER' }], page: 0, size: 100, hasNext: false }) }
function includeMemberLookup(fetch: ReturnType<typeof vi.fn<typeof globalThis.fetch>>) {
  vi.stubGlobal('fetch', vi.fn<typeof globalThis.fetch>((input, init) => String(input).includes('/members') ? Promise.resolve(memberPage()) : fetch(input, init)))
}
function render(myRole: WorkspaceRole = 'OWNER', onUpdated = vi.fn(), onRefreshed = vi.fn(), task: Task = original) {
  const host = document.createElement('div'); document.body.append(host)
  app = createApp(TaskUpdateForm, { task, myRole, onUpdated, onRefreshed }); app.mount(host); return host
}
function button(host: HTMLElement, text: string) { const b = Array.from(host.querySelectorAll('button')).find(b => b.textContent?.includes(text)); expect(b, text).toBeDefined(); return b! }
async function enter(host: HTMLElement, title = 'Draft', description = 'My description', priority = 'HIGH') {
  for (const [selector, value] of [['#task-update-title', title], ['#task-update-description', description]]) {
    const input = host.querySelector<HTMLInputElement | HTMLTextAreaElement>(selector!)!
    input.value = value!; input.dispatchEvent(new Event('input', { bubbles: true }))
  }
  const select = host.querySelector<HTMLSelectElement>('#task-update-priority')!
  select.value = priority; select.dispatchEvent(new Event('change', { bubbles: true }))
  await nextTick()
}
async function assign(host: HTMLElement, id: string, date: string) {
  const assignee = host.querySelector<HTMLSelectElement>('#task-update-assignee-id')!
  assignee.focus(); await vi.waitFor(() => expect(Array.from(assignee.options).some(option => option.value === id)).toBe(true))
  assignee.value = id; assignee.dispatchEvent(new Event('change', { bubbles: true }))
  const dueDate = host.querySelector<HTMLInputElement>('#task-update-due-date')!
  dueDate.value = date; dueDate.dispatchEvent(new Event('input', { bubbles: true })); await nextTick()
}
function submit(host: HTMLElement) { host.querySelector('form.task-update')!.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true })) }
function patches(fetch: ReturnType<typeof vi.fn<typeof globalThis.fetch>>) { return fetch.mock.calls.filter(call => call[1]?.method === 'PATCH') }
function fetchRecovery(latest: Task, myRole: WorkspaceRole = 'OWNER') {
  return vi.fn<typeof globalThis.fetch>().mockResolvedValueOnce(conflict()).mockResolvedValueOnce(json(latest)).mockResolvedValueOnce(role(myRole))
}

describe('Task 세 필드 편집·명시적 충돌 복구', () => {
  it('직접 저장한 버전보다 오래된 복구 응답은 최신 기준으로 채택하지 않는다', async () => {
    const fetch = vi.fn<typeof globalThis.fetch>().mockResolvedValueOnce(json(changed)).mockResolvedValueOnce(conflict())
      .mockResolvedValueOnce(json(original)).mockResolvedValueOnce(role())
    vi.stubGlobal('fetch', fetch); const refreshed = vi.fn(); const host = render('OWNER', vi.fn(), refreshed)
    await enter(host); submit(host); await vi.waitFor(() => expect(host.textContent).toContain('작업 변경 저장을 확인했습니다'))
    await enter(host, 'Second draft'); submit(host); await vi.waitFor(() => expect(host.textContent).toContain('다른 변경이 먼저'))
    button(host, '작업 최신 정보 확인').click(); await vi.waitFor(() => expect(host.textContent).toContain('수정 권한을 확인하지 못했습니다'))
    expect(refreshed).not.toHaveBeenCalled(); expect(host.querySelector('.task-comparison')).toBeNull()
    expect(host.querySelector<HTMLInputElement>('#task-update-title')!.value).toBe('Second draft')
    expect(button(host, '작업 변경 저장').disabled).toBe(true)
    expect(JSON.parse(patches(fetch)[1]![1]!.body as string).version).toBe('1')
  })
  it.each(['VIEWER'] as const)('%s에게 폼을 제공하지 않는다', async myRole => {
    const fetch = vi.fn(); vi.stubGlobal('fetch', fetch); const host = render(myRole); await nextTick()
    expect(host.querySelector('form')).toBeNull(); expect(fetch).not.toHaveBeenCalled()
  })
  it('유효성 검사·중복 제출 차단·정규화한 성공 이벤트를 확인한다', async () => {
    let done!: (response: Response) => void
    const fetch = vi.fn<typeof globalThis.fetch>(() => new Promise(resolve => { done = resolve })); vi.stubGlobal('fetch', fetch)
    const updated = vi.fn(); const host = render('ADMIN', updated)
    await enter(host, ' ', 'ok'); submit(host); await nextTick(); expect(fetch).not.toHaveBeenCalled()
    await enter(host, 'Draft', 'x'.repeat(2001)); submit(host); await nextTick(); expect(fetch).not.toHaveBeenCalled()
    await enter(host, ' Draft ', ' My description '); submit(host); submit(host); await nextTick()
    expect(patches(fetch)).toHaveLength(1); expect(button(host, '저장 중').disabled).toBe(true)
    done(json(changed)); await vi.waitFor(() => expect(updated).toHaveBeenCalledExactlyOnceWith(changed))
    expect(host.querySelector<HTMLInputElement>('input')!.value).toBe('Draft')
    expect(host.querySelector<HTMLTextAreaElement>('textarea')!.value).toBe('My description')
    expect(host.textContent).toContain('작업 변경 저장을 확인했습니다')
  })
  it('같은 ID의 props 갱신이 편집 기준·두 입력을 덮어쓰지 않는다', async () => {
    const task = ref(original); const host = document.createElement('div'); document.body.append(host)
    app = createApp({ setup: () => () => h(TaskUpdateForm, { task: task.value, myRole: 'OWNER' }) }); app.mount(host)
    const fetch = vi.fn<typeof globalThis.fetch>().mockResolvedValue(conflict()); vi.stubGlobal('fetch', fetch)
    await enter(host); task.value = { ...original, title: 'Other', description: 'Other description', version: '8' }; await nextTick()
    submit(host); await vi.waitFor(() => expect(host.textContent).toContain('다른 변경이 먼저'))
    expect(JSON.parse(patches(fetch)[0]![1]!.body as string)).toEqual({ title: 'Draft', description: 'My description', priority: 'HIGH', assigneeId: null, dueDate: null, version: '0' })
  })
  it('409 뒤 GET만으로 잠금/입력을 바꾸지 않고 명시적 기준 갱신 뒤 별도 수동 저장한다', async () => {
    const latest = { ...original, title: 'Server title', description: 'Server description', version: '2' }
    const fetch = fetchRecovery(latest).mockResolvedValueOnce(json({ ...changed, version: '3' })); vi.stubGlobal('fetch', fetch)
    const refreshed = vi.fn(); const host = render('OWNER', vi.fn(), refreshed); await enter(host); submit(host)
    await vi.waitFor(() => expect(host.textContent).toContain('다른 변경이 먼저'))
    submit(host); expect(patches(fetch)).toHaveLength(1); expect(host.textContent).not.toContain('secret SQL')
    button(host, '작업 최신 정보 확인').click(); await vi.waitFor(() => expect(host.textContent).toContain('Server description'))
    expect(refreshed).toHaveBeenCalledExactlyOnceWith(latest)
    expect(host.querySelector<HTMLInputElement>('input')!.value).toBe('Draft')
    expect(host.querySelector<HTMLTextAreaElement>('textarea')!.value).toBe('My description')
    expect(button(host, '작업 변경 저장').disabled).toBe(true)
    expect(patches(fetch)).toHaveLength(1)
    button(host, '최신 기준으로').click(); await nextTick(); expect(patches(fetch)).toHaveLength(1)
    submit(host); await vi.waitFor(() => expect(host.textContent).toContain('작업 변경 저장을 확인했습니다'))
    expect(JSON.parse(patches(fetch)[1]![1]!.body as string).version).toBe('2')
  })
  it.each(['VIEWER'] as const)('최신 역할이 %s면 기준 갱신과 재저장을 막는다', async myRole => {
    const fetch = fetchRecovery({ ...original, version: '1' }, myRole); vi.stubGlobal('fetch', fetch)
    const host = render(); await enter(host); submit(host); await vi.waitFor(() => expect(host.textContent).toContain('다른 변경이 먼저'))
    button(host, '작업 최신 정보 확인').click(); await vi.waitFor(() => expect(host.textContent).toContain('수정할 권한이 없습니다'))
    expect(host.textContent).not.toContain('최신 기준으로 다시 편집'); expect(button(host, '작업 변경 저장').disabled).toBe(true)
    submit(host); expect(patches(fetch)).toHaveLength(1)
  })
  it('최근 재조회 실패는 이전 성공 스냅샷을 지우고 잠금과 입력을 유지한다', async () => {
    const fetch = fetchRecovery({ ...original, title: 'Latest', version: '1' })
      .mockRejectedValueOnce(new TypeError('offline')).mockResolvedValueOnce(role())
    vi.stubGlobal('fetch', fetch); const host = render(); await enter(host); submit(host)
    await vi.waitFor(() => expect(host.textContent).toContain('다른 변경이 먼저'))
    button(host, '작업 최신 정보 확인').click(); await vi.waitFor(() => expect(host.textContent).toContain('Latest'))
    button(host, '작업 최신 정보 확인').click(); await vi.waitFor(() => expect(host.textContent).toContain('수정 권한을 확인하지 못했습니다'))
    expect(host.querySelector('.task-comparison')).toBeNull(); expect(button(host, '작업 변경 저장').disabled).toBe(true)
    expect(host.querySelector<HTMLInputElement>('input')!.value).toBe('Draft')
  })
  it.each(['offline', 'bad200', '500'])('%s 결과 불명은 GET의 내용 일치로 성공 처리하거나 자동 재저장하지 않는다', async kind => {
    const fetch = vi.fn<typeof globalThis.fetch>()
    if (kind === 'offline') fetch.mockRejectedValueOnce(new TypeError('offline'))
    else fetch.mockResolvedValueOnce(json(kind === 'bad200' ? { ...changed, version: '9' } : {}, kind === '500' ? 500 : 200))
    fetch.mockResolvedValueOnce(json(changed)).mockResolvedValueOnce(role()); vi.stubGlobal('fetch', fetch)
    const updated = vi.fn(); const host = render('OWNER', updated); await enter(host); submit(host)
    await vi.waitFor(() => expect(host.textContent).toContain('수정 결과를 확인하지 못했습니다'))
    button(host, '작업 최신 정보 확인').click(); await vi.waitFor(() => expect(host.querySelector('.task-comparison')).not.toBeNull())
    expect(updated).not.toHaveBeenCalled(); expect(button(host, '작업 변경 저장').disabled).toBe(true)
    submit(host); expect(patches(fetch)).toHaveLength(1)
  })
  it('쓰기 off 405는 안전한 확정 거부로 입력을 유지한다', async () => {
    const fetch = vi.fn().mockResolvedValue(json({ status: 405, detail: 'secret SQL' }, 405)); vi.stubGlobal('fetch', fetch)
    const host = render(); await enter(host); submit(host)
    await vi.waitFor(() => expect(host.textContent).toContain('현재는 작업을 수정할 수 없습니다'))
    expect(host.textContent).not.toContain('secret SQL'); expect(host.textContent).not.toContain('이미 저장됐을 수')
    expect(host.querySelector<HTMLTextAreaElement>('textarea')!.value).toBe('My description')
    expect(button(host, '작업 변경 저장').disabled).toBe(false)
  })
  it('현재 선택 상세와 같은 ID 목록만 갱신하고 추가 GET을 하지 않는다', async () => {
    const fetch = vi.fn().mockResolvedValueOnce(json({ items: [original, { ...original, id: '11', title: 'Other' }], page: 0, size: 20, hasNext: false }))
      .mockResolvedValueOnce(json(original)).mockResolvedValueOnce(json(changed)); vi.stubGlobal('fetch', fetch)
    const host = document.createElement('div'); document.body.append(host)
    app = createApp(TaskBrowser, { workspaceId: '1', projectId: '7', projectName: 'Parent', myRole: 'OWNER' }); app.mount(host)
    await vi.waitFor(() => expect(host.querySelector('.task-choice')).not.toBeNull()); button(host, 'Original').click()
    await vi.waitFor(() => expect(host.querySelector('.task-update')).not.toBeNull()); await enter(host); submit(host)
    await vi.waitFor(() => expect(host.querySelector('.task-detail h5')?.textContent).toBe('Draft'))
    expect(Array.from(host.querySelectorAll('.task-choice')).map(b => b.textContent)).toEqual(['Draft · 할 일 · 높음', 'Other · 할 일 · 보통'])
    expect(fetch).toHaveBeenCalledTimes(3)
  })
  it('언마운트는 진행 중 PATCH를 취소하고 늦은 성공 이벤트를 버린다', async () => {
    let done!: (response: Response) => void
    const fetch = vi.fn<typeof globalThis.fetch>(() => new Promise(resolve => { done = resolve })); vi.stubGlobal('fetch', fetch)
    const updated = vi.fn(); const host = render('OWNER', updated); await enter(host); submit(host); await nextTick()
    const signal = fetch.mock.calls[0]![1]!.signal!; app!.unmount(); app = undefined
    expect(signal.aborted).toBe(true); done(json(changed)); await new Promise(resolve => setTimeout(resolve, 0))
    expect(updated).not.toHaveBeenCalled()
  })
  it('Task 조회가 성공해도 역할 재조회 실패 시 기준을 갱신하지 않는다', async () => {
    const fetch = vi.fn<typeof globalThis.fetch>().mockResolvedValueOnce(conflict())
      .mockResolvedValueOnce(json(changed)).mockResolvedValueOnce(json({ status: 404 }, 404))
    vi.stubGlobal('fetch', fetch); const refreshed = vi.fn(); const host = render('OWNER', vi.fn(), refreshed)
    await enter(host); submit(host); await vi.waitFor(() => expect(host.textContent).toContain('다른 변경이 먼저'))
    button(host, '작업 최신 정보 확인').click()
    await vi.waitFor(() => expect(host.textContent).toContain('수정 권한을 확인하지 못했습니다'))
    expect(refreshed).not.toHaveBeenCalled(); expect(host.querySelector('.task-comparison')).toBeNull()
    expect(button(host, '작업 변경 저장').disabled).toBe(true)
  })
  it('충돌 복구 중 언마운트하면 두 GET을 취소하고 늦은 조회를 반영하지 않는다', async () => {
    let taskDone!: (response: Response) => void, roleDone!: (response: Response) => void
    const fetch = vi.fn<typeof globalThis.fetch>().mockResolvedValueOnce(conflict())
      .mockImplementationOnce(() => new Promise(resolve => { taskDone = resolve }))
      .mockImplementationOnce(() => new Promise(resolve => { roleDone = resolve }))
    vi.stubGlobal('fetch', fetch); const refreshed = vi.fn(); const host = render('OWNER', vi.fn(), refreshed)
    await enter(host); submit(host); await vi.waitFor(() => expect(host.textContent).toContain('다른 변경이 먼저'))
    button(host, '작업 최신 정보 확인').click(); await nextTick()
    const signals = fetch.mock.calls.slice(1).map(call => call[1]!.signal!)
    app!.unmount(); app = undefined; expect(signals.every(signal => signal.aborted)).toBe(true)
    taskDone(json(changed)); roleDone(role()); await new Promise(resolve => setTimeout(resolve, 0))
    expect(refreshed).not.toHaveBeenCalled()
  })

  it.each(['OWNER', 'ADMIN', 'MEMBER'] as const)('%s에게 세 필드 편집을 제공한다', async myRole => {
    const fetch = vi.fn().mockResolvedValue(json(changed)); vi.stubGlobal('fetch', fetch)
    const host = render(myRole); await enter(host); submit(host)
    await vi.waitFor(() => expect(host.textContent).toContain('작업 변경 저장을 확인했습니다'))
    expect(JSON.parse(patches(fetch)[0]![1]!.body as string).priority).toBe('HIGH')
  })
  it('수정 PATCH에 담당자/날짜를 포함하고 같은 version을 확인한다', async () => {
    const assigned = { ...changed, assigneeId: '4', dueDate: '2027-04-05' }
    const fetch = vi.fn().mockResolvedValue(json(assigned)); includeMemberLookup(fetch)
    const host = render(); await enter(host); await assign(host, '4', '2027-04-05'); submit(host)
    await vi.waitFor(() => expect(host.textContent).toContain('작업 변경 저장을 확인했습니다'))
    expect(JSON.parse(patches(fetch)[0]![1]!.body as string)).toEqual({
      title: 'Draft', description: 'My description', priority: 'HIGH', assigneeId: '4', dueDate: '2027-04-05', version: '0',
    })
    expect(host.querySelector<HTMLSelectElement>('#task-update-assignee-id')!.value).toBe('4')
    expect(host.querySelector<HTMLInputElement>('#task-update-due-date')!.value).toBe('2027-04-05')
  })
  it('현재 Workspace 멤버가 아닌 기존 담당자는 유지할 수 있지만 새 후보로 노출하지 않는다', async () => {
    const former = { ...original, assigneeId: '99' }
    const saved = { ...changed, assigneeId: '99' }
    const fetch = vi.fn().mockResolvedValueOnce(json({ items: [{ id: '4', displayName: 'Dave', role: 'VIEWER' }], page: 0, size: 100, hasNext: false }))
      .mockResolvedValueOnce(json(saved))
    vi.stubGlobal('fetch', fetch); const host = render('OWNER', vi.fn(), vi.fn(), former)
    const select = host.querySelector<HTMLSelectElement>('#task-update-assignee-id')!
    select.focus(); await vi.waitFor(() => expect(select.options.length).toBe(3))
    expect(select.value).toBe('99')
    expect(select.options[1]!.textContent).toContain('현재 Workspace 멤버 아님')
    expect(Array.from(select.options).some(option => option.value === '99' && option.textContent?.includes('Dave'))).toBe(false)
    await enter(host); submit(host)
    await vi.waitFor(() => expect(host.textContent).toContain('작업 변경 저장을 확인했습니다'))
    expect(JSON.parse(patches(fetch)[0]![1]!.body as string).assigneeId).toBe('99')
  })
  it('409 뒤 최신 담당자/날짜를 비교하고 draft를 보존해 새 version으로 수동 재저장한다', async () => {
    const latest = { ...original, assigneeId: '2', dueDate: '2028-02-29', version: '1' }
    const result = { ...changed, assigneeId: '4', dueDate: '2027-04-05', version: '2' }
    const fetch = fetchRecovery(latest, 'MEMBER').mockResolvedValueOnce(json(result))
    includeMemberLookup(fetch); const host = render('MEMBER'); await enter(host); await assign(host, '4', '2027-04-05'); submit(host)
    await vi.waitFor(() => expect(host.textContent).toContain('다른 변경이 먼저'))
    button(host, '작업 최신 정보 확인').click(); await vi.waitFor(() => expect(host.querySelector('.task-comparison')?.textContent).toContain('2028-02-29'))
    expect(host.querySelector('.task-comparison')?.textContent).toContain('2027-04-05')
    expect(host.querySelector<HTMLSelectElement>('#task-update-assignee-id')!.value).toBe('4')
    expect(host.querySelector<HTMLInputElement>('#task-update-due-date')!.value).toBe('2027-04-05')
    button(host, '최신 기준으로').click(); await nextTick(); submit(host)
    await vi.waitFor(() => expect(host.textContent).toContain('작업 변경 저장을 확인했습니다'))
    expect(JSON.parse(patches(fetch)[1]![1]!.body as string)).toEqual({ title: 'Draft', description: 'My description', priority: 'HIGH', assigneeId: '4', dueDate: '2027-04-05', version: '1' })
  })
  it('409 복구에서 우선순위 draft를 보존하고 서버와 비교한다', async () => {
    const latest = { ...original, priority: 'LOW' as const, version: '1' }
    const fetch = fetchRecovery(latest, 'MEMBER').mockResolvedValueOnce(json({ ...changed, version: '2' }))
    vi.stubGlobal('fetch', fetch); const host = render('MEMBER'); await enter(host); submit(host)
    await vi.waitFor(() => expect(host.textContent).toContain('다른 변경이 먼저'))
    button(host, '작업 최신 정보 확인').click()
    await vi.waitFor(() => expect(host.querySelector('.task-comparison')?.textContent).toContain('낮음'))
    expect(host.querySelector<HTMLSelectElement>('select')!.value).toBe('HIGH')
    expect(host.querySelector('.task-comparison')?.textContent).toContain('높음')
    button(host, '최신 기준으로').click(); await nextTick()
    expect(patches(fetch)).toHaveLength(1); expect(host.querySelector<HTMLSelectElement>('select')!.value).toBe('HIGH')
    submit(host); await vi.waitFor(() => expect(patches(fetch)).toHaveLength(2))
    expect(JSON.parse(patches(fetch)[1]![1]!.body as string)).toEqual({ title: 'Draft', description: 'My description', priority: 'HIGH', assigneeId: null, dueDate: null, version: '1' })
  })
})
