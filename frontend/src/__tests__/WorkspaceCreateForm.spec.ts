import { stubWorkspaceFetch } from './support/workspace-fetch'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App as VueApp } from 'vue'
import WorkspaceCreateForm from '../components/WorkspaceCreateForm.vue'
import WorkspaceBrowser from '../components/WorkspaceBrowser.vue'

let app: VueApp | undefined
afterEach(() => { app?.unmount(); app = undefined; document.body.replaceChildren(); vi.unstubAllGlobals() })
const existing = { id: '1', name: '기존 공간', myRole: 'OWNER', version: '0' }
const created = { id: '99', name: '새 공간', myRole: 'OWNER', version: '0' }
function json(value: unknown, status = 200) {
  return new Response(JSON.stringify(value), { status, headers: status === 201 ? { Location: '/api/workspaces/99' } : {} })
}
function list(items: unknown[] = [existing], hasNext = false) { return json({ items, page: 0, size: 20, hasNext }) }
function render(integrated = false, onCreated = vi.fn(), onCheckList = vi.fn()) {
  const host = document.createElement('div'); document.body.append(host)
  app = integrated ? createApp(WorkspaceBrowser) : createApp(WorkspaceCreateForm, { onCreated, onCheckList })
  app.mount(host); return host
}
async function enter(host: HTMLElement, value: string) {
  const input = host.querySelector('input')!; input.value = value; input.dispatchEvent(new Event('input', { bubbles: true })); await nextTick()
}
function submit(host: HTMLElement) { host.querySelector('form')!.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true })) }
function button(host: HTMLElement, text: string) { return Array.from(host.querySelectorAll('button')).find(b => b.textContent?.includes(text))! }

describe('생성 폼과 조회 연결', () => {
  it('잘못된 입력은 표시하고 보조 문자 100개를 UTF-16 maxlength로 자르지 않는다', async () => {
    const fetch = vi.fn().mockResolvedValue(json({ ...created, name: '🚀'.repeat(100) }, 201)); stubWorkspaceFetch(fetch)
    const host = render(); submit(host)
    await vi.waitFor(() => expect(host.textContent).toContain('1~100자'))
    expect(fetch).not.toHaveBeenCalled()
    await enter(host, '🚀'.repeat(101)); submit(host); await nextTick(); expect(fetch).not.toHaveBeenCalled()
    await enter(host, '🚀'.repeat(100)); submit(host)
    await vi.waitFor(() => expect(fetch).toHaveBeenCalledTimes(1))
  })

  it('중복 클릭/Enter를 막고 성공 후 입력을 비우고 생성 이벤트를 한 번 보낸다', async () => {
    let complete!: (value: Response) => void
    const fetch = vi.fn<typeof globalThis.fetch>(() => new Promise<Response>(resolve => { complete = resolve })); stubWorkspaceFetch(fetch)
    const seen = vi.fn(); const host = render(false, seen)
    await enter(host, ' 새 공간 '); submit(host); submit(host); await nextTick()
    expect(fetch).toHaveBeenCalledTimes(1)
    expect(host.querySelector('input')!.disabled).toBe(true)
    expect(button(host, '만드는 중').disabled).toBe(true)
    complete(json(created, 201))
    await vi.waitFor(() => expect(seen).toHaveBeenCalledExactlyOnceWith(created))
    expect(host.querySelector('input')!.value).toBe('')
    expect(host.textContent).toContain('워크스페이스를 만들었습니다')
  })

  it('405/403은 결과 불명과 구별하고 원문을 노출하지 않는다', async () => {
    stubWorkspaceFetch(vi.fn().mockResolvedValue(json({ status: 405, detail: 'secret SQL' }, 405)))
    const host = render(); await enter(host, '새 공간'); submit(host)
    await vi.waitFor(() => expect(host.textContent).toContain('현재는 공간을 만들 수 없습니다'))
    expect(button(host, '공간 만들기').disabled).toBe(false)
    expect(host.querySelector('input')!.value).toBe('새 공간')
    expect(host.textContent).not.toContain('secret SQL')
    expect(host.textContent).not.toContain('이미 생성됐을 수')
  })

  it('응답 유실 후 재전송을 잠그고 목록 확인과 명시적 초기화만 제공한다', async () => {
    const fetch = vi.fn().mockRejectedValue(new TypeError('Failed to fetch')); stubWorkspaceFetch(fetch)
    const checkList = vi.fn(); const host = render(false, vi.fn(), checkList)
    await enter(host, '새 공간'); submit(host)
    await vi.waitFor(() => expect(host.textContent).toContain('이미 생성됐을 수'))
    expect(button(host, '공간 만들기').disabled).toBe(true)
    submit(host); await nextTick(); expect(fetch).toHaveBeenCalledTimes(1)
    button(host, '목록 확인').click(); await nextTick()
    expect(checkList).toHaveBeenCalledTimes(1); expect(fetch).toHaveBeenCalledTimes(1)
    button(host, '입력 초기화').click(); await nextTick()
    expect(host.querySelector('input')!.value).toBe('')
    expect(button(host, '공간 만들기').disabled).toBe(false)
    expect(fetch).toHaveBeenCalledTimes(1)
  })

  it('성공 후 목록 0페이지를 다시 읽고 새 ID의 상세를 선택한다', async () => {
    const fetch = vi.fn().mockResolvedValueOnce(list()).mockResolvedValueOnce(json(created, 201))
      .mockResolvedValueOnce(list([existing, created])).mockResolvedValueOnce(json(created))
    stubWorkspaceFetch(fetch); const host = render(true)
    await vi.waitFor(() => expect(host.textContent).toContain(existing.name))
    await enter(host, '새 공간'); submit(host)
    await vi.waitFor(() => expect(host.querySelector('.workspace-detail h4')?.textContent).toBe('새 공간'))
    expect(fetch).toHaveBeenNthCalledWith(3, '/api/workspaces?page=0&size=20', expect.any(Object))
    expect(fetch).toHaveBeenNthCalledWith(4, '/api/workspaces/99', expect.any(Object))
    expect(button(host, '새 공간').getAttribute('aria-pressed')).toBe('true')
  })

  it('생성 후 목록 갱신 실패를 POST 실패로 표시하거나 재전송하지 않는다', async () => {
    const fetch = vi.fn().mockResolvedValueOnce(list()).mockResolvedValueOnce(json(created, 201))
      .mockRejectedValueOnce(new TypeError('Failed to fetch')).mockResolvedValueOnce(json(created))
    stubWorkspaceFetch(fetch); const host = render(true)
    await vi.waitFor(() => expect(host.textContent).toContain(existing.name))
    await enter(host, '새 공간'); submit(host)
    await vi.waitFor(() => expect(host.querySelector('.workspace-detail h4')?.textContent).toBe('새 공간'))
    expect(host.textContent).toContain('워크스페이스를 만들었습니다')
    expect(host.textContent).toContain('서버에 연결하지 못했습니다')
    expect(host.textContent).not.toContain('이미 생성됐을 수')
    expect(fetch.mock.calls.filter(call => call[1]?.method === 'POST')).toHaveLength(1)
  })

  it('새 공간이 0페이지에 없어도 페이지 데이터를 변조하지 않고 상세를 표시한다', async () => {
    const fetch = vi.fn().mockResolvedValueOnce(list()).mockResolvedValueOnce(json(created, 201))
      .mockResolvedValueOnce(list([existing], true)).mockResolvedValueOnce(json(created))
    stubWorkspaceFetch(fetch); const host = render(true)
    await vi.waitFor(() => expect(host.textContent).toContain(existing.name))
    await enter(host, '새 공간'); submit(host)
    await vi.waitFor(() => expect(host.querySelector('.workspace-detail h4')?.textContent).toBe('새 공간'))
    expect(host.querySelectorAll('.workspace-choice')).toHaveLength(1)
    expect(host.textContent).toContain('현재 목록 페이지에 없는 공간')
  })

  it('언마운트 시 생성 요청을 취소하고 늦은 성공 이벤트를 보내지 않는다', async () => {
    let complete!: (value: Response) => void
    const fetch = vi.fn<typeof globalThis.fetch>(() => new Promise<Response>(resolve => { complete = resolve })); stubWorkspaceFetch(fetch)
    const seen = vi.fn(); const host = render(false, seen)
    await enter(host, '새 공간'); submit(host); await nextTick()
    const signal = fetch.mock.calls[0]![1]!.signal as AbortSignal
    app!.unmount(); app = undefined
    expect(signal.aborted).toBe(true)
    complete(json(created, 201)); await new Promise(resolve => setTimeout(resolve, 0))
    expect(seen).not.toHaveBeenCalled()
  })
})
