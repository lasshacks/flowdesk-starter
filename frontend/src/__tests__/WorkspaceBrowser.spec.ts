import { stubWorkspaceFetch } from './support/workspace-fetch'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App as VueApp } from 'vue'
import WorkspaceBrowser from '../components/WorkspaceBrowser.vue'
import App from '../App.vue'

let app: VueApp | undefined
afterEach(() => { app?.unmount(); app = undefined; document.body.replaceChildren(); vi.unstubAllGlobals() })
const a = { id: '101', name: '나의 공간 A', myRole: 'OWNER', version: '0' }
const b = { id: '102', name: '나의 공간 B', myRole: 'VIEWER', version: '0' }
function json(value: unknown, status = 200) { return new Response(JSON.stringify(value), { status }) }
function list(items: unknown[] = [a], page = 0, hasNext = false) { return json({ items, page, size: 20, hasNext }) }
function render(fullApp = false) {
  const host = document.createElement('div'); document.body.append(host)
  app = createApp(fullApp ? App : WorkspaceBrowser); app.mount(host)
  return host
}
function button(host: HTMLElement, text: string) {
  const found = Array.from(host.querySelectorAll('button')).find(el => el.textContent?.includes(text))
  expect(found, text).toBeDefined(); return found!
}
function deferred() {
  let resolve!: (response: Response) => void
  return { promise: new Promise<Response>(complete => { resolve = complete }), resolve: (response: Response) => resolve(response) }
}

describe('워크스페이스 목록과 상세', () => {
  it('초기 로딩·선택 안내 후 클릭한 공간의 상세와 역할을 표시한다', async () => {
    const pending = deferred()
    const fetch = vi.fn().mockReturnValueOnce(pending.promise).mockResolvedValueOnce(json(a))
    stubWorkspaceFetch(fetch)
    const host = render(); await nextTick()
    expect(host.textContent).toContain('워크스페이스를 불러오고 있어요')
    expect(button(host, '목록 새로고침').disabled).toBe(true)
    pending.resolve(list())
    await vi.waitFor(() => expect(host.textContent).toContain(a.name))
    expect(host.textContent).toContain('목록에서 워크스페이스를 선택')
    button(host, a.name).click()
    await vi.waitFor(() => expect(host.querySelector('.workspace-detail h4')?.textContent).toBe(a.name))
    expect(host.querySelector('.workspace-detail')?.textContent).toContain('소유자')
    expect(button(host, a.name).getAttribute('aria-pressed')).toBe('true')
    expect(fetch).toHaveBeenLastCalledWith('/api/workspaces/101', expect.any(Object))
  })

  it('빈 목록을 오류와 구별하고 상세 요청을 만들지 않는다', async () => {
    const fetch = vi.fn().mockResolvedValue(list([])); stubWorkspaceFetch(fetch)
    const host = render()
    await vi.waitFor(() => expect(host.textContent).toContain('아직 참여한 워크스페이스가 없습니다'))
    expect(host.querySelector('[role="alert"]')).toBeNull()
    expect(button(host, '다음 페이지').disabled).toBe(true)
    expect(fetch).toHaveBeenCalledTimes(1)
  })

  it('목록 실패는 안전한 안내를 보이고 재시도로 복구한다', async () => {
    stubWorkspaceFetch(vi.fn().mockResolvedValueOnce(json({ status: 503, code: 'SERVICE_UNAVAILABLE', detail: 'secret SQL' }, 503))
      .mockResolvedValueOnce(list()))
    const host = render()
    await vi.waitFor(() => expect(host.textContent).toContain('일시적으로 서비스를 이용할 수 없습니다'))
    expect(host.textContent).not.toContain('secret SQL')
    button(host, '목록 다시 시도').click()
    await vi.waitFor(() => expect(host.textContent).toContain(a.name))
    expect(host.querySelector('[role="alert"]')).toBeNull()
  })

  it('기본 실행의 404를 소속 없음으로 표시하지 않는다', async () => {
    stubWorkspaceFetch(vi.fn().mockResolvedValue(json({ status: 404 }, 404)))
    const host = render()
    await vi.waitFor(() => expect(host.querySelector('[role="alert"]')).not.toBeNull())
    expect(host.textContent).not.toContain('아직 참여한 워크스페이스가 없습니다')
  })

  it('선택 변경 시 이전 상세를 지우고 늦은 응답이 새 선택을 덮지 못한다', async () => {
    const old = deferred(), current = deferred()
    const fetch = vi.fn().mockResolvedValueOnce(list([a, b])).mockReturnValueOnce(old.promise).mockReturnValueOnce(current.promise)
    stubWorkspaceFetch(fetch)
    const host = render()
    await vi.waitFor(() => expect(host.textContent).toContain(b.name))
    button(host, a.name).click(); await nextTick()
    const oldSignal = fetch.mock.calls[1]![1].signal as AbortSignal
    button(host, b.name).click(); await nextTick()
    expect(oldSignal.aborted).toBe(true)
    expect(host.querySelector('.workspace-detail h4')).toBeNull()
    current.resolve(json(b))
    await vi.waitFor(() => expect(host.querySelector('.workspace-detail h4')?.textContent).toBe(b.name))
    old.resolve(json(a)); await new Promise(resolve => setTimeout(resolve, 0)); await nextTick()
    expect(host.querySelector('.workspace-detail h4')?.textContent).toBe(b.name)
  })

  it('상세 404와 연결 실패 시 이전 상세를 지우고 재시도할 수 있다', async () => {
    stubWorkspaceFetch(vi.fn().mockResolvedValueOnce(list()).mockResolvedValueOnce(json(a))
      .mockResolvedValueOnce(json({ status: 404, code: 'WORKSPACE_NOT_FOUND' }, 404))
      .mockRejectedValueOnce(new TypeError('Failed to fetch')).mockResolvedValueOnce(json(a)))
    const host = render()
    await vi.waitFor(() => expect(host.textContent).toContain(a.name))
    button(host, a.name).click()
    await vi.waitFor(() => expect(host.querySelector('.workspace-detail h4')).not.toBeNull())
    button(host, a.name).click()
    await vi.waitFor(() => expect(host.textContent).toContain('찾을 수 없거나 접근할 수 없습니다'))
    expect(host.querySelector('.workspace-detail h4')).toBeNull()
    button(host, '상세 다시 시도').click()
    await vi.waitFor(() => expect(host.textContent).toContain('서버에 연결하지 못했습니다'))
    button(host, '상세 다시 시도').click()
    await vi.waitFor(() => expect(host.querySelector('.workspace-detail h4')?.textContent).toBe(a.name))
  })

  it('페이지 이동과 새로고침 시 상세를 초기화하고 현재 페이지를 재조회한다', async () => {
    const fetch = vi.fn().mockResolvedValueOnce(list([a], 0, true)).mockResolvedValueOnce(json(a))
      .mockResolvedValueOnce(list([], 1)).mockResolvedValueOnce(list([], 1)).mockResolvedValueOnce(list())
    stubWorkspaceFetch(fetch); const host = render()
    await vi.waitFor(() => expect(host.textContent).toContain(a.name))
    button(host, a.name).click()
    await vi.waitFor(() => expect(host.querySelector('.workspace-detail h4')).not.toBeNull())
    button(host, '다음 페이지').click()
    await vi.waitFor(() => expect(host.textContent).toContain('이 페이지에는 워크스페이스가 없습니다'))
    expect(host.querySelector('.workspace-detail h4')).toBeNull()
    expect(fetch).toHaveBeenLastCalledWith('/api/workspaces?page=1&size=20', expect.any(Object))
    button(host, '목록 새로고침').click()
    await vi.waitFor(() => expect(fetch).toHaveBeenCalledTimes(4))
    await vi.waitFor(() => expect(button(host, '이전 페이지').disabled).toBe(false))
    button(host, '이전 페이지').click()
    await vi.waitFor(() => expect(host.textContent).toContain(a.name))
    expect(fetch).toHaveBeenLastCalledWith('/api/workspaces?page=0&size=20', expect.any(Object))
  })

  it('컴포넌트 종료 시 진행 중인 요청을 취소한다', async () => {
    const fetch = vi.fn().mockImplementation(() => new Promise(() => {})); stubWorkspaceFetch(fetch)
    render(); await nextTick()
    const signal = fetch.mock.calls[0]![1].signal as AbortSignal
    app!.unmount(); app = undefined
    expect(signal.aborted).toBe(true)
  })

  it('전체 앱에서 health 성공과 업무 조회 실패를 독립적으로 표시한다', async () => {
    stubWorkspaceFetch(vi.fn((path: string) => Promise.resolve(path === '/api/health'
      ? json({ status: 'UP' }) : json({ status: 404 }, 404))))
    const host = render(true)
    await vi.waitFor(() => expect(host.querySelector('.server-status')?.textContent).toBe('UP'))
    await vi.waitFor(() => expect(host.querySelector('[role="alert"]')).not.toBeNull())
    expect(host.querySelector('.workspace-detail h4')).toBeNull()
  })
})
