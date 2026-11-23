import { afterEach, describe, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App } from 'vue'
import TaskCreateForm from '../components/TaskCreateForm.vue'
import TaskBrowser from '../components/TaskBrowser.vue'

let app: App | undefined
afterEach(() => { app?.unmount(); app = undefined; document.body.replaceChildren(); vi.unstubAllGlobals() })
const dto = { version: '0', id: '21', workspaceId: '1', projectId: '7', status: 'TODO', priority: 'MEDIUM', title: '새 작업', description: '설명', assigneeId: null, dueDate: null, archived: false, archivedAt: null }
function json(value: unknown, status = 200) { return new Response(JSON.stringify(value), { status, headers: status === 201 ? { Location: `/api/workspaces/1/projects/7/tasks/${(value as typeof dto).id}` } : {} }) }
function list(items: unknown[] = [], page = 0, hasNext = false) { return json({ items, page, size: 20, hasNext }) }
function memberList(items: unknown[] = [{ id: '4', displayName: 'Dave', role: 'VIEWER' }], page = 0, hasNext = false) { return json({ items, page, size: 100, hasNext }) }
function render(integrated = false, role = 'OWNER', onCreated = vi.fn(), onCheckList = vi.fn()) {
  const host = document.createElement('div'); document.body.append(host)
  app = integrated ? createApp(TaskBrowser, { projectId: '7', workspaceId: '1', projectName: 'Project A', myRole: role })
    : createApp(TaskCreateForm, { projectId: '7', workspaceId: '1', onCreated, onCheckList })
  app.mount(host); return host
}
async function enter(host: HTMLElement, title = dto.title, description = dto.description) {
  const input = host.querySelector<HTMLInputElement>('#task-title')!
  input.value = title; input.dispatchEvent(new Event('input', { bubbles: true }))
  const area = host.querySelector<HTMLTextAreaElement>('#task-description')!
  area.value = description; area.dispatchEvent(new Event('input', { bubbles: true })); await nextTick()
}
async function assign(host: HTMLElement, id: string, date: string) {
  const assignee = host.querySelector<HTMLSelectElement>('#task-assignee-id')!
  assignee.focus()
  await vi.waitFor(() => expect(Array.from(assignee.options).some(option => option.value === id)).toBe(true))
  assignee.value = id; assignee.dispatchEvent(new Event('change', { bubbles: true }))
  const dueDate = host.querySelector<HTMLInputElement>('#task-due-date')!
  dueDate.value = date; dueDate.dispatchEvent(new Event('input', { bubbles: true })); await nextTick()
}
function submit(host: HTMLElement) { host.querySelector('.task-create')!.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true })) }
function button(host: HTMLElement, text: string) {
  const found = Array.from(host.querySelectorAll('button')).find(item => item.textContent?.includes(text))
  expect(found, text).toBeDefined(); return found!
}

describe('Task 생성 폼', () => {
  it.each(['OWNER', 'ADMIN', 'MEMBER', 'VIEWER'])('%s 역할에 맞게 생성 폼을 표시하고 조회를 유지한다', async role => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(list()))
    const host = render(true, role)
    await vi.waitFor(() => expect(host.textContent).toContain('아직 작업이 없습니다'))
    expect(!!host.querySelector('.task-create')).toBe(['OWNER', 'ADMIN', 'MEMBER'].includes(role))
  })
  it('제목/설명 오류는 전송 전에 표시하고 Unicode 최대 길이는 허용한다', async () => {
    const fetch = vi.fn().mockResolvedValue(json({ ...dto, title: '🚀'.repeat(200), description: '🚀'.repeat(2000) }, 201))
    vi.stubGlobal('fetch', fetch); const host = render()
    await enter(host, ' ', ''); submit(host); await nextTick(); expect(host.textContent).toContain('제목은 1~200자'); expect(fetch).not.toHaveBeenCalled()
    await enter(host, 'Valid', '🚀'.repeat(2001)); submit(host); await nextTick(); expect(host.textContent).toContain('설명은 2,000자 이내'); expect(fetch).not.toHaveBeenCalled()
    await enter(host, '🚀'.repeat(200), '🚀'.repeat(2000)); submit(host)
    await vi.waitFor(() => expect(host.textContent).toContain('작업을 만들었습니다'))
  })
  it('진행 중 중복 제출은 차단하고 성공 이벤트를 한 번만 보낸다', async () => {
    let done!: (value: Response) => void
    const fetch = vi.fn(() => new Promise<Response>(resolve => { done = resolve })); vi.stubGlobal('fetch', fetch)
    const created = vi.fn(); const host = render(false, 'OWNER', created)
    await enter(host); submit(host); submit(host); await nextTick()
    expect(fetch).toHaveBeenCalledOnce(); expect(button(host, '작업 만드는 중').disabled).toBe(true)
    expect(host.querySelector<HTMLInputElement>('input')!.disabled).toBe(true)
    expect(host.querySelector<HTMLTextAreaElement>('textarea')!.disabled).toBe(true)
    expect(host.querySelector<HTMLSelectElement>('select')!.disabled).toBe(true)
    done(json(dto, 201)); await vi.waitFor(() => expect(created).toHaveBeenCalledOnce())
    expect(created).toHaveBeenCalledWith(dto); expect(host.querySelector<HTMLInputElement>('input')!.value).toBe('')
    expect(host.querySelector<HTMLTextAreaElement>('textarea')!.value).toBe('')
  })
  it('현재 Workspace 사용자 ID와 날짜를 POST하고 성공 뒤 선택 항목을 초기화한다', async () => {
    const assigned = { ...dto, assigneeId: '4', dueDate: '2027-04-05' }
    const fetch = vi.fn((input: RequestInfo | URL, init?: RequestInit) => String(input).includes('/members')
      ? Promise.resolve(memberList()) : Promise.resolve(json(assigned, 201))); vi.stubGlobal('fetch', fetch)
    const host = render(); await enter(host); await assign(host, '4', '2027-04-05'); submit(host)
    await vi.waitFor(() => expect(host.textContent).toContain('작업을 만들었습니다'))
    const post = fetch.mock.calls.find(call => call[1]?.method === 'POST')
    expect(post).toBeDefined()
    expect(JSON.parse(post![1]!.body as string)).toMatchObject({ assigneeId: '4', dueDate: '2027-04-05' })
    expect(host.querySelector<HTMLSelectElement>('#task-assignee-id')!.value).toBe('')
    expect(host.querySelector<HTMLInputElement>('#task-due-date')!.value).toBe('')
  })
  it('담당자는 현재 멤버 목록에서만 선택할 수 있다', async () => {
    const fetch = vi.fn().mockResolvedValue(memberList([{ id: '4', displayName: 'Dave', role: 'VIEWER' }])); vi.stubGlobal('fetch', fetch)
    const host = render()
    const select = host.querySelector<HTMLSelectElement>('#task-assignee-id')!
    select.focus(); await vi.waitFor(() => expect(select.options).toHaveLength(2))
    expect(Array.from(select.options).map(option => option.textContent?.trim())).toEqual(['미지정', 'Dave · 뷰어 (ID 4)'])
    expect(host.querySelector('input#task-assignee-id')).toBeNull()
    expect(fetch).toHaveBeenCalledWith('/api/workspaces/1/members?page=0&size=100', expect.any(Object))
  })
  it('멤버가 100명을 넘으면 다음 페이지도 읽어 후보에 포함한다', async () => {
    const firstPage = Array.from({ length: 100 }, (_, index) => ({ id: String(index + 1), displayName: `Member ${index + 1}`, role: 'MEMBER' }))
    const fetch = vi.fn((input: RequestInfo | URL) => {
      const path = String(input)
      if (path.endsWith('page=0&size=100')) return Promise.resolve(memberList(firstPage, 0, true))
      if (path.endsWith('page=1&size=100')) return Promise.resolve(memberList([{ id: '101', displayName: 'Last member', role: 'VIEWER' }], 1))
      return Promise.resolve(json(dto, 201))
    }); vi.stubGlobal('fetch', fetch)
    const host = render(); const select = host.querySelector<HTMLSelectElement>('#task-assignee-id')!
    select.focus(); await vi.waitFor(() => expect(select.options).toHaveLength(102))
    expect(select.options[101]!.value).toBe('101')
    expect(fetch.mock.calls.map(call => String(call[0]))).toEqual([
      '/api/workspaces/1/members?page=0&size=100', '/api/workspaces/1/members?page=1&size=100',
    ])
  })
  it.each([400, 403, 404, 405])('거부 %i는 입력을 유지하고 원문/결과 불명 안내를 표시하지 않는다', async status => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json({ status, code: 'HTTP_ERROR', detail: 'secret SQL' }, status)))
    const host = render(); await enter(host); submit(host)
    await vi.waitFor(() => expect(host.querySelector('.form-error')).not.toBeNull())
    expect(host.querySelector<HTMLInputElement>('input')!.value).toBe(dto.title)
    expect(host.querySelector<HTMLTextAreaElement>('textarea')!.value).toBe(dto.description)
    expect(host.textContent).not.toContain('secret SQL'); expect(host.querySelector('.creation-uncertain')).toBeNull()
    expect(button(host, '작업 만들기').disabled).toBe(false)
  })
  it('결과 불명은 입력과 잠금을 유지하고 목록 확인만으로 재전송/해제하지 않는다', async () => {
    const fetch = vi.fn().mockRejectedValue(new TypeError('response lost')); vi.stubGlobal('fetch', fetch)
    const checkList = vi.fn(); const host = render(false, 'OWNER', vi.fn(), checkList)
    await enter(host)
    const priority = host.querySelector<HTMLSelectElement>('#task-priority')!
    priority.value = 'HIGH'; priority.dispatchEvent(new Event('change', { bubbles: true })); await nextTick()
    submit(host); await vi.waitFor(() => expect(host.querySelector('.creation-uncertain')).not.toBeNull())
    expect(priority.value).toBe('HIGH'); expect(priority.disabled).toBe(true)
    expect(host.querySelector<HTMLInputElement>('input')!.value).toBe(dto.title)
    submit(host); button(host, '작업 목록 확인').click(); await nextTick()
    expect(fetch).toHaveBeenCalledOnce(); expect(checkList).toHaveBeenCalledOnce(); expect(button(host, '작업 만들기').disabled).toBe(true)
    button(host, '작업 입력 초기화').click(); await nextTick()
    expect(host.querySelector<HTMLInputElement>('input')!.value).toBe(''); expect(host.querySelector<HTMLTextAreaElement>('textarea')!.value).toBe('')
    expect(priority.value).toBe('MEDIUM'); expect(priority.disabled).toBe(false)
    expect(button(host, '작업 만들기').disabled).toBe(false); expect(fetch).toHaveBeenCalledOnce()
  })
  it('언마운트는 진행 요청을 취소하고 늦은 성공을 다른 프로젝트로 전달하지 않는다', async () => {
    let done!: (value: Response) => void
    const fetch = vi.fn<typeof globalThis.fetch>(() => new Promise(resolve => { done = resolve })); vi.stubGlobal('fetch', fetch)
    const created = vi.fn(); const host = render(false, 'OWNER', created); await enter(host); submit(host); await nextTick()
    const signal = fetch.mock.calls[0]![1]!.signal as AbortSignal
    app!.unmount(); app = undefined; expect(signal.aborted).toBe(true)
    done(json(dto, 201)); await new Promise(resolve => setTimeout(resolve, 0)); await nextTick(); expect(created).not.toHaveBeenCalled()
  })
  it('성공 후 0페이지/새 상세를 읽고 목록 실패를 생성 실패로 바꾸지 않는다', async () => {
    const fetch = vi.fn().mockResolvedValueOnce(list()).mockResolvedValueOnce(json(dto, 201))
      .mockRejectedValueOnce(new TypeError('list unavailable')).mockResolvedValueOnce(json(dto))
    vi.stubGlobal('fetch', fetch); const host = render(true)
    await vi.waitFor(() => expect(host.textContent).toContain('아직 작업이 없습니다'))
    await enter(host); submit(host)
    await vi.waitFor(() => expect(host.querySelector('.task-detail h5')?.textContent).toBe(dto.title))
    expect(host.textContent).toContain('작업을 만들었습니다'); expect(host.textContent).toContain('서버에 연결하지 못했습니다')
    expect(host.querySelector('.creation-uncertain')).toBeNull()
    expect(fetch).toHaveBeenNthCalledWith(3, '/api/workspaces/1/projects/7/tasks?page=0&size=20', expect.any(Object))
    expect(fetch).toHaveBeenLastCalledWith('/api/workspaces/1/projects/7/tasks/21', expect.any(Object))
  })
  it('새 항목이 첫 페이지에 없어도 페이지를 변조하지 않고 새 상세를 선택한다', async () => {
    const old = { ...dto, id: '1', title: 'Old' }
    const fetch = vi.fn().mockResolvedValueOnce(list([old])).mockResolvedValueOnce(json(dto, 201))
      .mockResolvedValueOnce(list([old], 0, true)).mockResolvedValueOnce(json(dto))
    vi.stubGlobal('fetch', fetch); const host = render(true); await vi.waitFor(() => expect(host.querySelector('.task-choice')).not.toBeNull())
    await enter(host); submit(host); await vi.waitFor(() => expect(host.querySelector('.task-detail h5')?.textContent).toBe(dto.title))
    expect(host.querySelectorAll('.task-choice')).toHaveLength(1); expect(host.querySelector('.task-choice')?.textContent).toBe('Old · 할 일 · 보통')
  })
  it('새 생성의 후속 조회가 시작되면 이전 생성의 늦은 자동 상세 선택을 무시한다', async () => {
    let finish!: (value: Response) => void
    const second = { ...dto, id: '22', title: '두 번째 작업' }
    const fetch = vi.fn().mockResolvedValueOnce(list()).mockResolvedValueOnce(json(dto, 201))
      .mockImplementationOnce(() => new Promise<Response>(resolve => { finish = resolve }))
      .mockResolvedValueOnce(json(second, 201)).mockResolvedValueOnce(list([second])).mockResolvedValueOnce(json(second))
    vi.stubGlobal('fetch', fetch); const host = render(true); await vi.waitFor(() => expect(host.textContent).toContain('아직 작업이 없습니다'))
    await enter(host); submit(host); await vi.waitFor(() => expect(host.textContent).toContain('작업을 만들었습니다'))
    await enter(host, second.title); submit(host)
    await vi.waitFor(() => expect(host.querySelector('.task-detail h5')?.textContent).toBe(second.title))
    finish(list([dto]))
    await new Promise(resolve => setTimeout(resolve, 0)); await nextTick()
    expect(fetch).toHaveBeenCalledTimes(6); expect(host.querySelector('.task-detail h5')?.textContent).toBe(second.title)
  })

  it('우선순위를 선택하고 성공 뒤 보통으로 초기화한다', async () => {
    const fetch = vi.fn().mockResolvedValue(json({ ...dto, priority: 'HIGH' }, 201)); vi.stubGlobal('fetch', fetch)
    const host = render(); await enter(host)
    const select = host.querySelector<HTMLSelectElement>('#task-priority')!
    expect(select.value).toBe('MEDIUM'); select.value = 'HIGH'; select.dispatchEvent(new Event('change', { bubbles: true }))
    await nextTick(); submit(host)
    await vi.waitFor(() => expect(host.textContent).toContain('작업을 만들었습니다'))
    expect(JSON.parse(fetch.mock.calls[0]![1].body).priority).toBe('HIGH'); expect(select.value).toBe('MEDIUM')
    expect(JSON.parse(fetch.mock.calls[0]![1].body)).not.toHaveProperty('status')
  })
})
