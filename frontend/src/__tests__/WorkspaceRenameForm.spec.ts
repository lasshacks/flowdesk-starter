import { stubWorkspaceFetch } from './support/workspace-fetch'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, ref, type App as VueApp } from 'vue'
import WorkspaceRenameForm from '../components/WorkspaceRenameForm.vue'
import WorkspaceBrowser from '../components/WorkspaceBrowser.vue'
import type { Workspace } from '../api/workspaces'

const original: Workspace = { id: '3', name: 'Original', myRole: 'OWNER', version: '0' }
const changed: Workspace = { ...original, name: 'My draft', version: '1' }
let app: VueApp | undefined
afterEach(() => { app?.unmount(); app = undefined; document.body.replaceChildren(); vi.unstubAllGlobals() })
function json(value: unknown, status = 200) { return new Response(JSON.stringify(value), { status }) }
function conflict() { return json({ status: 409, code: 'WORKSPACE_VERSION_CONFLICT', detail: 'secret SQL' }, 409) }
function list(items: Workspace[] = [original]) { return json({ items, page: 0, size: 20, hasNext: false }) }
function render(workspace = original, onRenamed = vi.fn(), onRefreshed = vi.fn(), integrated = false) {
  const host = document.createElement('div'); document.body.append(host)
  app = integrated ? createApp(WorkspaceBrowser) : createApp(WorkspaceRenameForm, { workspace, onRenamed, onRefreshed })
  app.mount(host); return host
}
function button(host: HTMLElement, text: string) { const b = Array.from(host.querySelectorAll('button')).find(b => b.textContent?.includes(text)); expect(b,text).toBeDefined(); return b! }
async function enter(host: HTMLElement, value: string) { const input = host.querySelector<HTMLInputElement>('#workspace-rename-name')!; input.value = value; input.dispatchEvent(new Event('input', { bubbles: true })); await nextTick() }
function submit(host: HTMLElement) { host.querySelector('.workspace-rename')!.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true })) }
function patches(fetch: ReturnType<typeof vi.fn<typeof globalThis.fetch>>) { return fetch.mock.calls.filter(call => call[1]?.method === 'PATCH') }

describe('이름 수정 폼과 명시적 충돌 복구', () => {
  it.each(['MEMBER', 'VIEWER'] as const)('%s에는 수정 폼을 제공하지 않는다', async myRole => {
    const fetch = vi.fn(); stubWorkspaceFetch(fetch)
    const host = render({ ...original, myRole }); await nextTick()
    expect(host.querySelector('form')).toBeNull(); expect(host.textContent).toContain('소유자와 관리자만')
    expect(fetch).not.toHaveBeenCalled()
  })
  it('유효성·중복 제출을 검사하고 성공 이벤트를 한 번 보낸다', async () => {
    let done!: (response: Response) => void
    const fetch = vi.fn<typeof globalThis.fetch>(() => new Promise(resolve => { done = resolve })); stubWorkspaceFetch(fetch)
    const seen = vi.fn(); const host = render(original, seen)
    await enter(host, ' '); submit(host); await nextTick(); expect(fetch).not.toHaveBeenCalled(); expect(host.textContent).toContain('1~100자')
    await enter(host, ' My draft '); submit(host); submit(host); await nextTick()
    expect(patches(fetch)).toHaveLength(1); expect(button(host, '저장 중').disabled).toBe(true)
    done(json(changed)); await vi.waitFor(() => expect(seen).toHaveBeenCalledExactlyOnceWith(changed))
    expect(host.querySelector<HTMLInputElement>('input')!.value).toBe('My draft')
    expect(host.textContent).toContain('이름 저장을 확인했습니다')
  })
  it('편집 도중 같은 ID의 props가 바뀌어도 시작 버전을 몰래 바꾸지 않는다', async () => {
    const workspace = ref(original); const host = document.createElement('div'); document.body.append(host)
    app = createApp({ setup: () => () => h(WorkspaceRenameForm, { workspace: workspace.value }) }); app.mount(host)
    const fetch = vi.fn<typeof globalThis.fetch>().mockResolvedValue(conflict()); stubWorkspaceFetch(fetch)
    await enter(host, 'My draft'); workspace.value = { ...original, name: 'Elsewhere', version: '8' }; await nextTick()
    submit(host); await vi.waitFor(() => expect(host.textContent).toContain('다른 변경이 먼저'))
    expect(JSON.parse(patches(fetch)[0]![1]!.body as string)).toEqual({ name: 'My draft', version: '0' })
  })
  it('409는 입력 유지·저장 잠금, GET·명시적 기준 갱신·수동 저장으로 복구한다', async () => {
    const latest = { ...original, name: 'Someone else', version: '2' }
    const fetch = vi.fn<typeof globalThis.fetch>().mockResolvedValueOnce(conflict()).mockResolvedValueOnce(json(latest))
      .mockResolvedValueOnce(json({ ...changed, version: '3' }))
    stubWorkspaceFetch(fetch); const refreshed = vi.fn(); const host = render(original, vi.fn(), refreshed)
    await enter(host, 'My draft'); submit(host)
    await vi.waitFor(() => expect(host.textContent).toContain('다른 변경이 먼저'))
    expect(host.textContent).not.toContain('secret SQL'); expect(button(host, '이름 저장').disabled).toBe(true)
    submit(host); await nextTick(); expect(patches(fetch)).toHaveLength(1)
    button(host, '최신 정보 확인').click()
    await vi.waitFor(() => expect(host.textContent).toContain('Someone else'))
    expect(refreshed).toHaveBeenCalledExactlyOnceWith(latest)
    expect(host.querySelector<HTMLInputElement>('input')!.value).toBe('My draft')
    expect(button(host, '이름 저장').disabled).toBe(true); expect(patches(fetch)).toHaveLength(1)
    button(host, '최신 정보 기준').click(); await nextTick()
    expect(patches(fetch)).toHaveLength(1); expect(button(host, '이름 저장').disabled).toBe(false)
    submit(host); await vi.waitFor(() => expect(host.textContent).toContain('이름 저장을 확인했습니다'))
    expect(JSON.parse(patches(fetch)[1]![1]!.body as string)).toEqual({ name: 'My draft', version: '2' })
  })
  it('최신 GET이 실패하면 입력과 잠금을 유지하고 이전 조회 버전을 사용하지 않는다', async () => {
    const fetch = vi.fn<typeof globalThis.fetch>().mockResolvedValueOnce(conflict())
      .mockResolvedValueOnce(json({ ...original, name: 'Latest', version: '1' })).mockRejectedValueOnce(new TypeError('offline'))
    stubWorkspaceFetch(fetch); const host = render(); await enter(host, 'My draft'); submit(host)
    await vi.waitFor(() => expect(host.textContent).toContain('다른 변경이 먼저'))
    button(host, '최신 정보 확인').click(); await vi.waitFor(() => expect(host.textContent).toContain('Latest'))
    button(host, '최신 정보 확인').click(); await vi.waitFor(() => expect(host.textContent).toContain('최신 정보를 불러오지 못했습니다'))
    expect(host.querySelector<HTMLInputElement>('input')!.value).toBe('My draft')
    expect(button(host, '이름 저장').disabled).toBe(true)
    expect(host.textContent).not.toContain('최신 정보 기준으로 다시 편집'); expect(patches(fetch)).toHaveLength(1)
  })
  it('최신 정보에서 수정 역할을 잃으면 다시 편집으로 전환하지 않는다', async () => {
    const fetch = vi.fn<typeof globalThis.fetch>().mockResolvedValueOnce(conflict()).mockResolvedValueOnce(json({ ...original, myRole: 'VIEWER', version: '1' }))
    stubWorkspaceFetch(fetch); const host = render(); await enter(host, 'My draft'); submit(host)
    await vi.waitFor(() => expect(host.textContent).toContain('다른 변경이 먼저'))
    button(host, '최신 정보 확인').click(); await vi.waitFor(() => expect(host.textContent).toContain('현재는 이름을 수정할 권한이 없습니다'))
    expect(button(host, '이름 저장').disabled).toBe(true); submit(host); await nextTick(); expect(patches(fetch)).toHaveLength(1)
  })
  it('응답 유실은 결과 불명으로 잠그고 GET 확인만으로 성공이나 재전송을 만들지 않는다', async () => {
    const fetch = vi.fn<typeof globalThis.fetch>().mockRejectedValueOnce(new TypeError('offline')).mockResolvedValueOnce(json(changed))
    stubWorkspaceFetch(fetch); const renamed = vi.fn(); const host = render(original, renamed)
    await enter(host, 'My draft'); submit(host)
    await vi.waitFor(() => expect(host.textContent).toContain('이미 저장됐을 수'))
    submit(host); await nextTick(); expect(patches(fetch)).toHaveLength(1)
    button(host, '최신 정보 확인').click(); await vi.waitFor(() => expect(host.querySelector('.latest-name')?.textContent).toContain('My draft'))
    expect(renamed).not.toHaveBeenCalled(); expect(host.textContent).not.toContain('이름 저장을 확인했습니다')
    expect(button(host, '이름 저장').disabled).toBe(true); expect(patches(fetch)).toHaveLength(1)
  })
  it('명확한 405 거부는 입력을 유지하고 결과 불명과 구분한다', async () => {
    stubWorkspaceFetch(vi.fn().mockResolvedValue(json({ status: 405, detail: 'secret SQL' }, 405)))
    const host = render(); await enter(host, 'My draft'); submit(host)
    await vi.waitFor(() => expect(host.textContent).toContain('현재는 이름을 수정할 수 없습니다'))
    expect(host.querySelector<HTMLInputElement>('input')!.value).toBe('My draft')
    expect(host.textContent).not.toContain('이미 저장됐을 수'); expect(host.textContent).not.toContain('secret SQL')
    expect(button(host, '이름 저장').disabled).toBe(false)
  })
  it('성공 DTO로 선택 상세와 기존 목록 행을 함께 갱신하며 추가 GET은 하지 않는다', async () => {
    const fetch = vi.fn<typeof globalThis.fetch>().mockResolvedValueOnce(list()).mockResolvedValueOnce(json(original)).mockResolvedValueOnce(json(changed))
    stubWorkspaceFetch(fetch); const host = render(original, vi.fn(), vi.fn(), true)
    await vi.waitFor(() => expect(host.querySelector('.workspace-choice')).not.toBeNull())
    button(host, 'Original').click(); await vi.waitFor(() => expect(host.querySelector('.workspace-rename')).not.toBeNull())
    await enter(host, 'My draft'); submit(host)
    await vi.waitFor(() => expect(host.querySelector('.workspace-detail h4')?.textContent).toBe('My draft'))
    expect(host.querySelector('.workspace-choice')?.textContent).toContain('My draft')
    expect(host.querySelector('.workspace-choice')?.getAttribute('aria-pressed')).toBe('true')
    expect(fetch).toHaveBeenCalledTimes(3)
  })
  it('선택 변경으로 언마운트되면 진행 중 요청을 취소하고 늦은 응답을 반영하지 않는다', async () => {
    let done!: (response: Response) => void
    const fetch = vi.fn<typeof globalThis.fetch>(() => new Promise(resolve => { done = resolve })); stubWorkspaceFetch(fetch)
    const seen = vi.fn(); const host = render(original, seen); await enter(host, 'My draft'); submit(host)
    await nextTick(); const signal = fetch.mock.calls[0]![1]!.signal!
    app!.unmount(); app = undefined; expect(signal.aborted).toBe(true)
    done(json(changed)); await new Promise(resolve => setTimeout(resolve, 0)); expect(seen).not.toHaveBeenCalled()
  })
})
