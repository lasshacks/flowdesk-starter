import { afterEach, describe, expect, it, vi } from 'vitest'
import { getWorkspace, getWorkspaces, renameWorkspace, WorkspaceApiError, WorkspaceRenameUncertainError, type Workspace } from '../api/workspaces'

const base: Workspace = { id: '3', name: 'Old', myRole: 'OWNER', version: '9007199254740993' }
const updated = { ...base, name: 'New', version: '9007199254740994' }
function json(value: unknown, status = 200) { return new Response(JSON.stringify(value), { status }) }
afterEach(() => vi.unstubAllGlobals())

describe('버전 보존과 이름 수정 HTTP 계약', () => {
  it('큰 버전을 목록·상세에서 문자열 그대로 보존한다', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(json({ items: [base], page: 0, size: 20, hasNext: false })).mockResolvedValueOnce(json(base)))
    expect((await getWorkspaces()).items[0]?.version).toBe(base.version)
    expect((await getWorkspace('3')).version).toBe(base.version)
  })
  it.each([undefined, null, 0, '-1', '00', '01', '1.0', ' 0', '9223372036854775808'])('잘못된 응답 버전 %j를 거부한다', async version => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json({ ...base, version })))
    await expect(getWorkspace('3')).rejects.toMatchObject({ code: 'INVALID_RESPONSE' })
  })
  it('정규화한 이름과 편집 시작 버전만 한 번 전송한다', async () => {
    const fetch = vi.fn<typeof globalThis.fetch>().mockResolvedValue(json(updated)); vi.stubGlobal('fetch', fetch)
    await expect(renameWorkspace(base, '  New  ')).resolves.toEqual(updated)
    expect(fetch).toHaveBeenCalledExactlyOnceWith('/api/workspaces/3', expect.objectContaining({
      method: 'PATCH', body: JSON.stringify({ name: 'New', version: base.version }),
      credentials: 'omit', cache: 'no-store', redirect: 'error',
      headers: { Accept: 'application/json', 'Content-Type': 'application/json' }, signal: expect.any(AbortSignal),
    }))
  })
  it('no-op은 같은 버전을 허용하고 취소 신호를 전달한다', async () => {
    const fetch = vi.fn<typeof globalThis.fetch>().mockResolvedValue(json(base)); vi.stubGlobal('fetch', fetch)
    const controller = new AbortController()
    await expect(renameWorkspace(base, ' Old ', controller.signal)).resolves.toEqual(base)
    controller.abort(); expect(fetch.mock.calls[0]![1]!.signal!.aborted).toBe(true)
  })
  it.each([400, 403, 404, 405, 409, 413, 415])('명확한 거부 %s는 원문 없이 전달하고 재시도하지 않는다', async status => {
    const fetch = vi.fn().mockResolvedValue(json({ status, code: status === 409 ? 'WORKSPACE_VERSION_CONFLICT' : 'INVALID_REQUEST', detail: 'secret SQL' }, status))
    vi.stubGlobal('fetch', fetch)
    try { await renameWorkspace(base, 'New'); expect.fail('must reject') }
    catch (cause) {
      expect(cause).toBeInstanceOf(WorkspaceApiError); expect(cause).toMatchObject({ status })
      expect((cause as Error).message).not.toContain('secret SQL')
    }
    expect(fetch).toHaveBeenCalledTimes(1)
  })
  it.each([new TypeError('secret connection failure'), new DOMException('timeout', 'TimeoutError')])('통신 실패는 결과 불명이고 자동 재전송하지 않는다', async failure => {
    const fetch = vi.fn().mockRejectedValue(failure); vi.stubGlobal('fetch', fetch)
    await expect(renameWorkspace(base, 'New')).rejects.toBeInstanceOf(WorkspaceRenameUncertainError)
    expect(fetch).toHaveBeenCalledTimes(1)
  })
  it.each([500, 503, 201, 204])('서버 오류·예상 밖 성공 %s는 결과 불명이다', async status => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(status === 204 ? null : '{}', { status })))
    await expect(renameWorkspace(base, 'New')).rejects.toBeInstanceOf(WorkspaceRenameUncertainError)
  })
  it.each([
    { ...updated, id: '4' }, { ...updated, name: 'Other' }, { ...updated, version: base.version },
    { ...updated, version: '9007199254740995' }, { ...updated, version: 1 }, { ...updated, myRole: 'VIEWER' },
  ])('잘못된 200 확인 응답은 결과 불명이다: %j', async response => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json(response)))
    await expect(renameWorkspace(base, 'New')).rejects.toBeInstanceOf(WorkspaceRenameUncertainError)
  })
  it('잘못된 JSON 200도 결과 불명이며 로컬 입력 오류는 전송하지 않는다', async () => {
    const fetch = vi.fn().mockResolvedValue(new Response('<html>bad response</html>')); vi.stubGlobal('fetch', fetch)
    await expect(renameWorkspace(base, 'New')).rejects.toBeInstanceOf(WorkspaceRenameUncertainError)
    fetch.mockClear()
    await expect(renameWorkspace(base, ' ')).rejects.toMatchObject({ code: 'INVALID_NAME' })
    await expect(renameWorkspace({ ...base, version: '00' }, 'New')).rejects.toMatchObject({ code: 'INVALID_REQUEST' })
    await expect(renameWorkspace({ ...base, id: '0' }, 'New')).rejects.toMatchObject({ code: 'INVALID_REQUEST' })
    expect(fetch).not.toHaveBeenCalled()
  })
})
