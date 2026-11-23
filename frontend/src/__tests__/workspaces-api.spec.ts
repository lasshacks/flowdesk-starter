import { afterEach, describe, expect, it, vi } from 'vitest'
import { getWorkspace, getWorkspaceMembers, getWorkspaces, WorkspaceApiError } from '../api/workspaces'

afterEach(() => vi.unstubAllGlobals())
const item = { id: '9223372036854775807', name: '작업 공간', myRole: 'VIEWER', version: '0' }
const page = { items: [item], page: 0, size: 20, hasNext: false }
function respond(value: unknown, status = 200) {
  return new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } })
}

describe('워크스페이스 HTTP 계약', () => {
  it('큰 ID를 문자열로 유지하고 행위자·쿠키 없이 고정 크기로 요청한다', async () => {
    const fetch = vi.fn().mockResolvedValueOnce(respond(page)).mockResolvedValueOnce(respond(item))
    vi.stubGlobal('fetch', fetch)
    expect(await getWorkspaces()).toEqual(page)
    expect(await getWorkspace(item.id)).toEqual(item)
    expect(fetch).toHaveBeenNthCalledWith(1, '/api/workspaces?page=0&size=20', expect.objectContaining({
      credentials: 'omit', cache: 'no-store', headers: { Accept: 'application/json' }, signal: expect.any(AbortSignal),
    }))
    expect(fetch).toHaveBeenNthCalledWith(2, `/api/workspaces/${item.id}`, expect.any(Object))
  })

  it('멤버 후보 페이지는 ID·표시 이름·역할만 받고 이메일 없이 strict parse한다', async () => {
    const member = { id: '9223372036854775807', displayName: 'Alice', role: 'OWNER' }
    const members = { items: [member], page: 1, size: 100, hasNext: false }
    const fetch = vi.fn().mockResolvedValue(respond(members)); vi.stubGlobal('fetch', fetch)
    expect(await getWorkspaceMembers('7', 1)).toEqual(members)
    expect(fetch).toHaveBeenCalledWith('/api/workspaces/7/members?page=1&size=100', expect.objectContaining({
      credentials: 'omit', cache: 'no-store', headers: { Accept: 'application/json' }, signal: expect.any(AbortSignal),
    }))
    for (const invalid of [
      { ...members, items: [{ ...member, email: 'private@example.test' }] },
      { ...members, items: [{ ...member, id: '01' }] },
      { ...members, items: [{ ...member, displayName: ' ' }] },
      { ...members, items: [{ ...member, role: 'ROOT' }] },
      { ...members, items: [member, member] }, { ...members, hasNext: true, items: [] }, null,
    ]) {
      vi.stubGlobal('fetch', vi.fn().mockResolvedValue(respond(invalid)))
      await expect(getWorkspaceMembers('7', 1)).rejects.toMatchObject({ code: 'INVALID_RESPONSE' })
    }
  })

  it.each([
    { ...page, items: [{}] }, { ...page, items: [{ ...item, id: 123 }] },
    { ...page, items: [{ ...item, id: '9223372036854775808' }] },
    { ...page, items: [{ ...item, id: '0' }] }, { ...page, items: [{ ...item, myRole: 'ROOT' }] },
    { ...page, items: [{ ...item, myRole: ['VIEWER'] }] },
    { ...page, items: [{ ...item, name: ' ' }] }, { ...page, page: 1 }, { ...page, size: 100 },
    { ...page, hasNext: 'false' }, { ...page, items: [item, item] }, null,
  ])('타입 선언과 다른 런타임 응답은 거부한다: %j', async (invalid) => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(respond(invalid)))
    await expect(getWorkspaces()).rejects.toMatchObject({ code: 'INVALID_RESPONSE' })
  })

  it('다른 ID의 상세 응답과 JSON이 아닌 성공 응답을 거부한다', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(respond({ ...item, id: '2' }))
      .mockResolvedValueOnce(new Response('<html>Error</html>')))
    await expect(getWorkspace('1')).rejects.toMatchObject({ code: 'INVALID_RESPONSE' })
    await expect(getWorkspaces()).rejects.toMatchObject({ code: 'INVALID_RESPONSE' })
  })

  it('ProblemDetail의 코드만 사용하고 서버 오류 원문은 전달하지 않는다', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(respond({ status: 404, code: 'WORKSPACE_NOT_FOUND', detail: 'secret SQL' }, 404)))
    try { await getWorkspace('1'); expect.fail('must reject') }
    catch (error) {
      expect(error).toBeInstanceOf(WorkspaceApiError)
      expect(error).toMatchObject({ code: 'WORKSPACE_NOT_FOUND', status: 404 })
      expect((error as Error).message).not.toContain('secret SQL')
    }
  })

  it('기본 모드 404/프록시 HTML 오류도 빈 목록으로 바꾸지 않는다', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('<html>Not found</html>', { status: 404 })))
    await expect(getWorkspaces()).rejects.toMatchObject({ code: 'HTTP_ERROR', status: 404 })
  })

  it('로컬에서 잘못된 ID·페이지를 차단하고 요청 취소를 fetch에 전달한다', async () => {
    const fetch = vi.fn().mockResolvedValue(respond(page))
    vi.stubGlobal('fetch', fetch)
    for (const id of ['0', '1/../../health', '9223372036854775808']) {
      await expect(getWorkspace(id)).rejects.toMatchObject({ code: 'INVALID_REQUEST' })
    }
    await expect(getWorkspaces(10001)).rejects.toMatchObject({ code: 'INVALID_REQUEST' })
    expect(fetch).not.toHaveBeenCalled()
    const controller = new AbortController()
    await getWorkspaces(0, controller.signal)
    const signal = fetch.mock.calls[0]![1].signal as AbortSignal
    controller.abort()
    expect(signal.aborted).toBe(true)
  })
})
