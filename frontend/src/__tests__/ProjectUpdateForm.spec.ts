import { afterEach, describe, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, ref, type App } from 'vue'
import ProjectUpdateForm from '../components/ProjectUpdateForm.vue'
import ProjectBrowser from '../components/ProjectBrowser.vue'
import type { Project } from '../api/projects'
import type { WorkspaceRole } from '../api/workspaces'

const original: Project = { id: '10', workspaceId: '1', name: 'Original', archived: false, archivedAt: null, description: 'Old description', version: '0' }
const changed: Project = { ...original, name: 'Draft', description: 'My description', version: '1' }
vi.mock('../components/TaskBrowser.vue', () => ({ default: { template: '<div class="task-stub" />' } }))

let app: App | undefined
afterEach(() => { app?.unmount(); app = undefined; document.body.replaceChildren(); vi.unstubAllGlobals() })
function json(value: unknown, status = 200) { return new Response(JSON.stringify(value), { status }) }
function conflict() { return json({ status: 409, code: 'PROJECT_VERSION_CONFLICT', detail: 'secret SQL' }, 409) }
function role(myRole: WorkspaceRole = 'OWNER') { return json({ id: '1', name: 'Parent', myRole, version: '0' }) }
function render(myRole: WorkspaceRole = 'OWNER', onUpdated = vi.fn(), onRefreshed = vi.fn()) {
  const host = document.createElement('div'); document.body.append(host)
  app = createApp(ProjectUpdateForm, { project: original, myRole, onUpdated, onRefreshed }); app.mount(host); return host
}
function button(host: HTMLElement, text: string) { const b = Array.from(host.querySelectorAll('button')).find(b => b.textContent?.includes(text)); expect(b, text).toBeDefined(); return b! }
async function enter(host: HTMLElement, name = 'Draft', description = 'My description') {
  for (const [selector, value] of [['#project-update-name', name], ['#project-update-description', description]]) {
    const input = host.querySelector<HTMLInputElement | HTMLTextAreaElement>(selector!)!
    input.value = value!; input.dispatchEvent(new Event('input', { bubbles: true }))
  }
  await nextTick()
}
function submit(host: HTMLElement) { host.querySelector('form.project-update')!.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true })) }
function patches(fetch: ReturnType<typeof vi.fn<typeof globalThis.fetch>>) { return fetch.mock.calls.filter(call => call[1]?.method === 'PATCH') }
function fetchRecovery(latest: Project, myRole: WorkspaceRole = 'OWNER') {
  return vi.fn<typeof globalThis.fetch>().mockResolvedValueOnce(conflict()).mockResolvedValueOnce(json(latest)).mockResolvedValueOnce(role(myRole))
}

describe('Project 두 필드 편집·명시적 충돌 복구', () => {
  it.each(['MEMBER', 'VIEWER'] as const)('%s에게 폼을 제공하지 않는다', async myRole => {
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
    expect(host.textContent).toContain('프로젝트 변경 저장을 확인했습니다')
  })
  it('같은 ID의 props 갱신이 편집 기준·두 입력을 덮어쓰지 않는다', async () => {
    const project = ref(original); const host = document.createElement('div'); document.body.append(host)
    app = createApp({ setup: () => () => h(ProjectUpdateForm, { project: project.value, myRole: 'OWNER' }) }); app.mount(host)
    const fetch = vi.fn<typeof globalThis.fetch>().mockResolvedValue(conflict()); vi.stubGlobal('fetch', fetch)
    await enter(host); project.value = { ...original, name: 'Other', description: 'Other description', version: '8' }; await nextTick()
    submit(host); await vi.waitFor(() => expect(host.textContent).toContain('다른 변경이 먼저'))
    expect(JSON.parse(patches(fetch)[0]![1]!.body as string)).toEqual({ name: 'Draft', description: 'My description', version: '0' })
  })
  it('409 뒤 GET만으로 잠금/입력을 바꾸지 않고 명시적 기준 갱신 뒤 별도 수동 저장한다', async () => {
    const latest = { ...original, name: 'Server name', description: 'Server description', version: '2' }
    const fetch = fetchRecovery(latest).mockResolvedValueOnce(json({ ...changed, version: '3' })); vi.stubGlobal('fetch', fetch)
    const refreshed = vi.fn(); const host = render('OWNER', vi.fn(), refreshed); await enter(host); submit(host)
    await vi.waitFor(() => expect(host.textContent).toContain('다른 변경이 먼저'))
    submit(host); expect(patches(fetch)).toHaveLength(1); expect(host.textContent).not.toContain('secret SQL')
    button(host, '프로젝트 최신 정보 확인').click(); await vi.waitFor(() => expect(host.textContent).toContain('Server description'))
    expect(refreshed).toHaveBeenCalledExactlyOnceWith(latest)
    expect(host.querySelector<HTMLInputElement>('input')!.value).toBe('Draft')
    expect(host.querySelector<HTMLTextAreaElement>('textarea')!.value).toBe('My description')
    expect(button(host, '프로젝트 변경 저장').disabled).toBe(true)
    expect(patches(fetch)).toHaveLength(1)
    button(host, '최신 기준으로').click(); await nextTick(); expect(patches(fetch)).toHaveLength(1)
    submit(host); await vi.waitFor(() => expect(host.textContent).toContain('프로젝트 변경 저장을 확인했습니다'))
    expect(JSON.parse(patches(fetch)[1]![1]!.body as string).version).toBe('2')
  })
  it.each(['MEMBER', 'VIEWER'] as const)('최신 역할이 %s면 기준 갱신과 재저장을 막는다', async myRole => {
    const fetch = fetchRecovery({ ...original, version: '1' }, myRole); vi.stubGlobal('fetch', fetch)
    const host = render(); await enter(host); submit(host); await vi.waitFor(() => expect(host.textContent).toContain('다른 변경이 먼저'))
    button(host, '프로젝트 최신 정보 확인').click(); await vi.waitFor(() => expect(host.textContent).toContain('수정할 권한이 없습니다'))
    expect(host.textContent).not.toContain('최신 기준으로 다시 편집'); expect(button(host, '프로젝트 변경 저장').disabled).toBe(true)
    submit(host); expect(patches(fetch)).toHaveLength(1)
  })
  it('최근 재조회 실패는 이전 성공 스냅샷을 지우고 잠금과 입력을 유지한다', async () => {
    const fetch = fetchRecovery({ ...original, name: 'Latest', version: '1' })
      .mockRejectedValueOnce(new TypeError('offline')).mockResolvedValueOnce(role())
    vi.stubGlobal('fetch', fetch); const host = render(); await enter(host); submit(host)
    await vi.waitFor(() => expect(host.textContent).toContain('다른 변경이 먼저'))
    button(host, '프로젝트 최신 정보 확인').click(); await vi.waitFor(() => expect(host.textContent).toContain('Latest'))
    button(host, '프로젝트 최신 정보 확인').click(); await vi.waitFor(() => expect(host.textContent).toContain('수정 권한을 확인하지 못했습니다'))
    expect(host.querySelector('.project-comparison')).toBeNull(); expect(button(host, '프로젝트 변경 저장').disabled).toBe(true)
    expect(host.querySelector<HTMLInputElement>('input')!.value).toBe('Draft')
  })
  it.each(['offline', 'bad200', '500'])('%s 결과 불명은 GET의 내용 일치로 성공 처리하거나 자동 재저장하지 않는다', async kind => {
    const fetch = vi.fn<typeof globalThis.fetch>()
    if (kind === 'offline') fetch.mockRejectedValueOnce(new TypeError('offline'))
    else fetch.mockResolvedValueOnce(json(kind === 'bad200' ? { ...changed, version: '9' } : {}, kind === '500' ? 500 : 200))
    fetch.mockResolvedValueOnce(json(changed)).mockResolvedValueOnce(role()); vi.stubGlobal('fetch', fetch)
    const updated = vi.fn(); const host = render('OWNER', updated); await enter(host); submit(host)
    await vi.waitFor(() => expect(host.textContent).toContain('수정 결과를 확인하지 못했습니다'))
    button(host, '프로젝트 최신 정보 확인').click(); await vi.waitFor(() => expect(host.querySelector('.project-comparison')).not.toBeNull())
    expect(updated).not.toHaveBeenCalled(); expect(button(host, '프로젝트 변경 저장').disabled).toBe(true)
    submit(host); expect(patches(fetch)).toHaveLength(1)
  })
  it('쓰기 off 405는 안전한 확정 거부로 입력을 유지한다', async () => {
    const fetch = vi.fn().mockResolvedValue(json({ status: 405, detail: 'secret SQL' }, 405)); vi.stubGlobal('fetch', fetch)
    const host = render(); await enter(host); submit(host)
    await vi.waitFor(() => expect(host.textContent).toContain('현재는 프로젝트를 수정할 수 없습니다'))
    expect(host.textContent).not.toContain('secret SQL'); expect(host.textContent).not.toContain('이미 저장됐을 수')
    expect(host.querySelector<HTMLTextAreaElement>('textarea')!.value).toBe('My description')
    expect(button(host, '프로젝트 변경 저장').disabled).toBe(false)
  })
  it('현재 선택 상세와 같은 ID 목록만 갱신하고 추가 GET을 하지 않는다', async () => {
    const fetch = vi.fn().mockResolvedValueOnce(json({ items: [original, { ...original, id: '11', name: 'Other' }], page: 0, size: 20, hasNext: false }))
      .mockResolvedValueOnce(json(original)).mockResolvedValueOnce(json(changed)); vi.stubGlobal('fetch', fetch)
    const host = document.createElement('div'); document.body.append(host)
    app = createApp(ProjectBrowser, { workspaceId: '1', workspaceName: 'Parent', myRole: 'OWNER' }); app.mount(host)
    await vi.waitFor(() => expect(host.querySelector('.project-choice')).not.toBeNull()); button(host, 'Original').click()
    await vi.waitFor(() => expect(host.querySelector('.project-update')).not.toBeNull()); await enter(host); submit(host)
    await vi.waitFor(() => expect(host.querySelector('.project-detail h5')?.textContent).toBe('Draft'))
    expect(Array.from(host.querySelectorAll('.project-choice')).map(b => b.textContent)).toEqual(['Draft', 'Other'])
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
  it('Project 조회가 성공해도 역할 재조회 실패 시 기준을 갱신하지 않는다', async () => {
    const fetch = vi.fn<typeof globalThis.fetch>().mockResolvedValueOnce(conflict())
      .mockResolvedValueOnce(json(changed)).mockResolvedValueOnce(json({ status: 404 }, 404))
    vi.stubGlobal('fetch', fetch); const refreshed = vi.fn(); const host = render('OWNER', vi.fn(), refreshed)
    await enter(host); submit(host); await vi.waitFor(() => expect(host.textContent).toContain('다른 변경이 먼저'))
    button(host, '프로젝트 최신 정보 확인').click()
    await vi.waitFor(() => expect(host.textContent).toContain('수정 권한을 확인하지 못했습니다'))
    expect(refreshed).not.toHaveBeenCalled(); expect(host.querySelector('.project-comparison')).toBeNull()
    expect(button(host, '프로젝트 변경 저장').disabled).toBe(true)
  })
  it('충돌 복구 중 언마운트하면 두 GET을 취소하고 늦은 조회를 반영하지 않는다', async () => {
    let projectDone!: (response: Response) => void, roleDone!: (response: Response) => void
    const fetch = vi.fn<typeof globalThis.fetch>().mockResolvedValueOnce(conflict())
      .mockImplementationOnce(() => new Promise(resolve => { projectDone = resolve }))
      .mockImplementationOnce(() => new Promise(resolve => { roleDone = resolve }))
    vi.stubGlobal('fetch', fetch); const refreshed = vi.fn(); const host = render('OWNER', vi.fn(), refreshed)
    await enter(host); submit(host); await vi.waitFor(() => expect(host.textContent).toContain('다른 변경이 먼저'))
    button(host, '프로젝트 최신 정보 확인').click(); await nextTick()
    const signals = fetch.mock.calls.slice(1).map(call => call[1]!.signal!)
    app!.unmount(); app = undefined; expect(signals.every(signal => signal.aborted)).toBe(true)
    projectDone(json(changed)); roleDone(role()); await new Promise(resolve => setTimeout(resolve, 0))
    expect(refreshed).not.toHaveBeenCalled()
  })
})
