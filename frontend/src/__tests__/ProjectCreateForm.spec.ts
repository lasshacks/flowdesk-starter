import { afterEach, describe, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App } from 'vue'
import ProjectCreateForm from '../components/ProjectCreateForm.vue'
import ProjectBrowser from '../components/ProjectBrowser.vue'

vi.mock('../components/TaskBrowser.vue', () => ({ default: { template: '<div class="task-stub" />' } }))

let app: App | undefined
afterEach(() => { app?.unmount(); app = undefined; document.body.replaceChildren(); vi.unstubAllGlobals() })
const dto = { version: '0', id: '21', workspaceId: '1', name: '새 프로젝트', archived: false, archivedAt: null, description: '설명' }
function json(value: unknown, status = 200) { return new Response(JSON.stringify(value), { status, headers: status === 201 ? { Location: `/api/workspaces/1/projects/${(value as typeof dto).id}` } : {} }) }
function list(items: unknown[] = [], page = 0, hasNext = false) { return json({ items, page, size: 20, hasNext }) }
function render(integrated = false, role = 'OWNER', onCreated = vi.fn(), onCheckList = vi.fn()) {
  const host = document.createElement('div'); document.body.append(host)
  app = integrated ? createApp(ProjectBrowser, { workspaceId: '1', workspaceName: '공간 A', myRole: role })
    : createApp(ProjectCreateForm, { workspaceId: '1', onCreated, onCheckList })
  app.mount(host); return host
}
async function enter(host: HTMLElement, name = dto.name, description = dto.description) {
  const input = host.querySelector<HTMLInputElement>('#project-name')!
  input.value = name; input.dispatchEvent(new Event('input', { bubbles: true }))
  const area = host.querySelector<HTMLTextAreaElement>('#project-description')!
  area.value = description; area.dispatchEvent(new Event('input', { bubbles: true })); await nextTick()
}
function submit(host: HTMLElement) { host.querySelector('.project-create')!.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true })) }
function button(host: HTMLElement, text: string) {
  const found = Array.from(host.querySelectorAll('button')).find(item => item.textContent?.includes(text))
  expect(found, text).toBeDefined(); return found!
}

describe('Project 생성 폼', () => {
  it.each(['OWNER', 'ADMIN', 'MEMBER', 'VIEWER'])('%s 역할에 맞게 생성 폼을 표시하고 조회를 유지한다', async role => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(list()))
    const host = render(true, role)
    await vi.waitFor(() => expect(host.textContent).toContain('아직 프로젝트가 없습니다'))
    expect(!!host.querySelector('.project-create')).toBe(['OWNER', 'ADMIN'].includes(role))
  })
  it('이름/설명 오류는 전송 전에 표시하고 Unicode 최대 길이는 허용한다', async () => {
    const fetch = vi.fn().mockResolvedValue(json({ ...dto, name: '🚀'.repeat(100), description: '🚀'.repeat(2000) }, 201))
    vi.stubGlobal('fetch', fetch); const host = render()
    await enter(host, ' ', ''); submit(host); await nextTick(); expect(host.textContent).toContain('이름은 1~100자'); expect(fetch).not.toHaveBeenCalled()
    await enter(host, 'Valid', '🚀'.repeat(2001)); submit(host); await nextTick(); expect(host.textContent).toContain('설명은 2,000자 이내'); expect(fetch).not.toHaveBeenCalled()
    await enter(host, '🚀'.repeat(100), '🚀'.repeat(2000)); submit(host)
    await vi.waitFor(() => expect(host.textContent).toContain('프로젝트를 만들었습니다'))
  })
  it('진행 중 중복 제출은 차단하고 성공 이벤트를 한 번만 보낸다', async () => {
    let done!: (value: Response) => void
    const fetch = vi.fn(() => new Promise<Response>(resolve => { done = resolve })); vi.stubGlobal('fetch', fetch)
    const created = vi.fn(); const host = render(false, 'OWNER', created)
    await enter(host); submit(host); submit(host); await nextTick()
    expect(fetch).toHaveBeenCalledOnce(); expect(button(host, '프로젝트 만드는 중').disabled).toBe(true)
    expect(host.querySelector<HTMLInputElement>('input')!.disabled).toBe(true)
    expect(host.querySelector<HTMLTextAreaElement>('textarea')!.disabled).toBe(true)
    done(json(dto, 201)); await vi.waitFor(() => expect(created).toHaveBeenCalledOnce())
    expect(created).toHaveBeenCalledWith(dto); expect(host.querySelector<HTMLInputElement>('input')!.value).toBe('')
    expect(host.querySelector<HTMLTextAreaElement>('textarea')!.value).toBe('')
  })
  it.each([400, 403, 404, 405])('거부 %i는 입력을 유지하고 원문/결과 불명 안내를 표시하지 않는다', async status => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json({ status, code: 'HTTP_ERROR', detail: 'secret SQL' }, status)))
    const host = render(); await enter(host); submit(host)
    await vi.waitFor(() => expect(host.querySelector('.form-error')).not.toBeNull())
    expect(host.querySelector<HTMLInputElement>('input')!.value).toBe(dto.name)
    expect(host.querySelector<HTMLTextAreaElement>('textarea')!.value).toBe(dto.description)
    expect(host.textContent).not.toContain('secret SQL'); expect(host.querySelector('.creation-uncertain')).toBeNull()
    expect(button(host, '프로젝트 만들기').disabled).toBe(false)
  })
  it('결과 불명은 입력과 잠금을 유지하고 목록 확인만으로 재전송/해제하지 않는다', async () => {
    const fetch = vi.fn().mockRejectedValue(new TypeError('response lost')); vi.stubGlobal('fetch', fetch)
    const checkList = vi.fn(); const host = render(false, 'OWNER', vi.fn(), checkList)
    await enter(host); submit(host); await vi.waitFor(() => expect(host.querySelector('.creation-uncertain')).not.toBeNull())
    expect(host.querySelector<HTMLInputElement>('input')!.value).toBe(dto.name)
    submit(host); button(host, '프로젝트 목록 확인').click(); await nextTick()
    expect(fetch).toHaveBeenCalledOnce(); expect(checkList).toHaveBeenCalledOnce(); expect(button(host, '프로젝트 만들기').disabled).toBe(true)
    button(host, '프로젝트 입력 초기화').click(); await nextTick()
    expect(host.querySelector<HTMLInputElement>('input')!.value).toBe(''); expect(host.querySelector<HTMLTextAreaElement>('textarea')!.value).toBe('')
    expect(button(host, '프로젝트 만들기').disabled).toBe(false); expect(fetch).toHaveBeenCalledOnce()
  })
  it('언마운트는 진행 요청을 취소하고 늦은 성공을 다른 공간으로 전달하지 않는다', async () => {
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
    await vi.waitFor(() => expect(host.textContent).toContain('아직 프로젝트가 없습니다'))
    await enter(host); submit(host)
    await vi.waitFor(() => expect(host.querySelector('.project-detail h5')?.textContent).toBe(dto.name))
    expect(host.textContent).toContain('프로젝트를 만들었습니다'); expect(host.textContent).toContain('서버에 연결하지 못했습니다')
    expect(host.querySelector('.creation-uncertain')).toBeNull()
    expect(fetch).toHaveBeenNthCalledWith(3, '/api/workspaces/1/projects?page=0&size=20', expect.any(Object))
    expect(fetch).toHaveBeenLastCalledWith('/api/workspaces/1/projects/21', expect.any(Object))
  })
  it('새 항목이 첫 페이지에 없어도 페이지를 변조하지 않고 새 상세를 선택한다', async () => {
    const old = { id: '1', workspaceId: '1', name: 'Old', archived: false }
    const fetch = vi.fn().mockResolvedValueOnce(list([old])).mockResolvedValueOnce(json(dto, 201))
      .mockResolvedValueOnce(list([old], 0, true)).mockResolvedValueOnce(json(dto))
    vi.stubGlobal('fetch', fetch); const host = render(true); await vi.waitFor(() => expect(host.querySelector('.project-choice')).not.toBeNull())
    await enter(host); submit(host); await vi.waitFor(() => expect(host.querySelector('.project-detail h5')?.textContent).toBe(dto.name))
    expect(host.querySelectorAll('.project-choice')).toHaveLength(1); expect(host.querySelector('.project-choice')?.textContent).toBe('Old')
  })
  it('새 생성의 후속 조회가 시작되면 이전 생성의 늦은 자동 상세 선택을 무시한다', async () => {
    let finish!: (value: Response) => void
    const second = { ...dto, id: '22', name: '두 번째 프로젝트' }
    const fetch = vi.fn().mockResolvedValueOnce(list()).mockResolvedValueOnce(json(dto, 201))
      .mockImplementationOnce(() => new Promise<Response>(resolve => { finish = resolve }))
      .mockResolvedValueOnce(json(second, 201)).mockResolvedValueOnce(list([second])).mockResolvedValueOnce(json(second))
    vi.stubGlobal('fetch', fetch); const host = render(true); await vi.waitFor(() => expect(host.textContent).toContain('아직 프로젝트가 없습니다'))
    await enter(host); submit(host); await vi.waitFor(() => expect(host.textContent).toContain('프로젝트를 만들었습니다'))
    await enter(host, second.name); submit(host)
    await vi.waitFor(() => expect(host.querySelector('.project-detail h5')?.textContent).toBe(second.name))
    finish(list([{ id: dto.id, workspaceId: '1', name: dto.name, archived: false }]))
    await new Promise(resolve => setTimeout(resolve, 0)); await nextTick()
    expect(fetch).toHaveBeenCalledTimes(6); expect(host.querySelector('.project-detail h5')?.textContent).toBe(second.name)
  })
})
