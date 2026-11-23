import { afterEach, describe, expect, it, vi } from 'vitest'
import { createWorkspace, normalizeWorkspaceName, WorkspaceCreateUncertainError } from '../api/workspaces'

afterEach(() => vi.unstubAllGlobals())
const item = { id: '9223372036854775807', name: '새 공간', myRole: 'OWNER', version: '0' }
function response(value: unknown = item, status = 201, location = `/api/workspaces/${item.id}`) {
  return new Response(JSON.stringify(value), { status, headers: { Location: location } })
}

describe('생성 요청 계약', () => {
  it('정규화한 이름만 POST하고 사용자·Origin·쿠키를 직접 지정하지 않는다', async () => {
    const fetch = vi.fn().mockResolvedValue(response()); vi.stubGlobal('fetch', fetch)
    expect(await createWorkspace(' \u00a0 새 공간\u2003 ')).toEqual(item)
    expect(fetch).toHaveBeenCalledExactlyOnceWith('/api/workspaces', expect.objectContaining({
      method: 'POST', headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
      body: JSON.stringify({ name: '새 공간' }), credentials: 'omit', cache: 'no-store', redirect: 'error', signal: expect.any(AbortSignal),
    }))
  })

  it('100개 Unicode 코드 포인트를 허용하고 잘못된 이름은 네트워크 전에 거부한다', async () => {
    expect(normalizeWorkspaceName('🚀'.repeat(100))).toBe('🚀'.repeat(100))
    const fetch = vi.fn(); vi.stubGlobal('fetch', fetch)
    for (const name of ['', ' \u00a0 ', '🚀'.repeat(101), 'a\u0000b', 'a\nb', '\ud800']) {
      await expect(createWorkspace(name)).rejects.toMatchObject({ code: 'INVALID_NAME' })
    }
    expect(fetch).not.toHaveBeenCalled()
  })

  it.each([400, 403, 404, 405, 413, 415])('확정된 입력/접속 거부 %i는 한 번만 요청한다', async status => {
    const fetch = vi.fn().mockResolvedValue(response({ status, code: 'INVALID_REQUEST', detail: 'secret SQL' }, status))
    vi.stubGlobal('fetch', fetch)
    await expect(createWorkspace('새 공간')).rejects.toMatchObject({ status })
    expect(fetch).toHaveBeenCalledTimes(1)
  })

  it.each([500, 503, 502, 200])('생성 확인이 아닌 상태 %i는 결과 불명으로 다룬다', async status => {
    const fetch = vi.fn().mockResolvedValue(response(item, status)); vi.stubGlobal('fetch', fetch)
    await expect(createWorkspace('새 공간')).rejects.toBeInstanceOf(WorkspaceCreateUncertainError)
    expect(fetch).toHaveBeenCalledTimes(1)
  })

  it.each([new TypeError('Failed to fetch'), new DOMException('timeout', 'TimeoutError')])('네트워크/시간 초과는 재전송하지 않는다: %s', async error => {
    const fetch = vi.fn().mockRejectedValue(error); vi.stubGlobal('fetch', fetch)
    await expect(createWorkspace('새 공간')).rejects.toBeInstanceOf(WorkspaceCreateUncertainError)
    expect(fetch).toHaveBeenCalledTimes(1)
  })

  it.each([
    [{ ...item, myRole: 'VIEWER', version: '0' }, `/api/workspaces/${item.id}`],
    [{ ...item, id: 123 }, '/api/workspaces/123'],
    [{ ...item, name: '다른 이름' }, `/api/workspaces/${item.id}`],
    [item, '/api/workspaces/123'],
  ])('잘못된 201 본문/Location도 미생성으로 단정하지 않는다', async (value, location) => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response(value, 201, location as string)))
    await expect(createWorkspace('새 공간')).rejects.toBeInstanceOf(WorkspaceCreateUncertainError)
  })

  it('201의 잘못된 JSON도 결과 불명이며 AbortSignal을 전달한다', async () => {
    const fetch = vi.fn().mockResolvedValue(new Response('broken', { status: 201 })); vi.stubGlobal('fetch', fetch)
    const controller = new AbortController()
    await expect(createWorkspace('새 공간', controller.signal)).rejects.toBeInstanceOf(WorkspaceCreateUncertainError)
    const sent = fetch.mock.calls[0]![1].signal as AbortSignal
    controller.abort(); expect(sent.aborted).toBe(true)
  })
})
