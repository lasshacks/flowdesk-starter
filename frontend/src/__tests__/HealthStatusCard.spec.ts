import { afterEach, describe, expect, it, vi } from 'vitest'
import { createApp, nextTick, type App as VueApp } from 'vue'
import HealthStatusCard from '../components/HealthStatusCard.vue'

let app: VueApp | undefined

afterEach(() => {
  app?.unmount()
  app = undefined
  document.body.replaceChildren()
  vi.unstubAllGlobals()
})

function renderApp() {
  const host = document.createElement('div')
  document.body.append(host)
  app = createApp(HealthStatusCard)
  app.mount(host)
  return host
}

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

describe('서버 연결 화면', () => {
  it('응답이 도착하기 전에는 정상 상태를 표시하지 않고 중복 조회를 막는다', async () => {
    let complete!: (response: Response) => void
    const fetchMock = vi.fn(() => new Promise<Response>((resolve) => { complete = resolve }))
    vi.stubGlobal('fetch', fetchMock)
    const host = renderApp()
    await nextTick()

    expect(host.textContent).toContain('연결 확인 중')
    expect(host.querySelector('button')?.disabled).toBe(true)
    expect(host.querySelector('.server-status')).toBeNull()

    complete(jsonResponse({ status: 'UP' }))
    await vi.waitFor(() => expect(host.querySelector('.server-status')?.textContent).toBe('UP'))

    expect(fetchMock).toHaveBeenCalledWith('/api/health', expect.objectContaining({
      headers: { Accept: 'application/json' },
      signal: expect.any(AbortSignal),
    }))
    expect(host.textContent).toContain('서버 연결 정상')
    expect(host.querySelector('button')?.disabled).toBe(false)
  })

  it('정상 조회 후 연결이 끊기면 이전 UP을 지우고 재시도로 복구한다', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(jsonResponse({ status: 'UP' }))
      .mockRejectedValueOnce(new TypeError('Failed to fetch'))
      .mockResolvedValueOnce(jsonResponse({ status: 'UP' }))
    vi.stubGlobal('fetch', fetchMock)
    const host = renderApp()
    await vi.waitFor(() => expect(host.querySelector('.server-status')?.textContent).toBe('UP'))

    host.querySelector('button')!.click()
    await vi.waitFor(() => expect(host.textContent).toContain('서버에 연결하지 못했습니다'))
    expect(host.querySelector('.server-status')).toBeNull()
    expect(host.querySelector('button')?.disabled).toBe(false)

    host.querySelector('button')!.click()
    await vi.waitFor(() => expect(host.querySelector('.server-status')?.textContent).toBe('UP'))
    expect(host.textContent).not.toContain('서버에 연결하지 못했습니다')
    expect(fetchMock).toHaveBeenCalledTimes(3)
  })

  it('HTTP 오류를 정상 응답으로 취급하지 않는다', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({ status: 'UP' }, 503)))
    const host = renderApp()

    await vi.waitFor(() => expect(host.textContent).toContain('HTTP 503'))
    expect(host.querySelector('.server-status')).toBeNull()
  })

  it('TypeScript 타입과 다른 실제 응답을 거부한다', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({ status: 42 })))
    const host = renderApp()

    await vi.waitFor(() => expect(host.textContent).toContain('서버 응답 형식이 올바르지 않습니다'))
    expect(host.querySelector('.server-status')).toBeNull()
  })

  it('UP이 아닌 서버 상태는 정상 연결 상태로 표시하지 않는다', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({ status: 'DOWN' })))
    const host = renderApp()

    await vi.waitFor(() => expect(host.querySelector('.server-status')?.textContent).toBe('DOWN'))
    expect(host.textContent).toContain('서버 상태 확인 필요')
    expect(host.querySelector('.status-dot')?.classList.contains('online')).toBe(false)
  })
})
