import { afterEach, describe, expect, it, vi } from 'vitest'
import { updateProject, ProjectApiError, ProjectUpdateUncertainError, type Project } from '../api/projects'

const base: Project = { id: '10', workspaceId: '1', name: 'Original', archived: false, archivedAt: null, description: 'Old', version: '9007199254740993' }
function json(value: unknown, status = 200) { return new Response(JSON.stringify(value), { status }) }
afterEach(() => vi.unstubAllGlobals())

describe('Project PATCH와 전체 행 버전', () => {
  it('부모 하위 PATCH에 정규화한 두 필드와 손실 없는 문자열 버전만 보낸다', async () => {
    const result = { ...base, name: 'Changed', description: 'First\nSecond', version: '9007199254740994' }
    const fetch = vi.fn().mockResolvedValue(json(result)); vi.stubGlobal('fetch', fetch)
    expect(await updateProject(base, ' Changed ', ' First\r\nSecond ')).toEqual(result)
    const [url, init] = fetch.mock.calls[0]!
    expect(url).toBe('/api/workspaces/1/projects/10')
    expect(init).toMatchObject({ method: 'PATCH', credentials: 'omit', cache: 'no-store', redirect: 'error' })
    expect(JSON.parse(init.body)).toEqual({ name: 'Changed', description: 'First\nSecond', version: base.version })
  })
  it('정규화한 no-op은 같은 버전이고 설명만 삭제해도 다음 버전이다', async () => {
    const fetch = vi.fn().mockResolvedValueOnce(json(base)).mockResolvedValueOnce(json({ ...base, description: null, version: '9007199254740994' }))
    vi.stubGlobal('fetch', fetch)
    expect((await updateProject(base, ' Original ', ' Old ')).version).toBe(base.version)
    expect((await updateProject(base, 'Original', ' \r\n ')).description).toBeNull()
    expect(JSON.parse(fetch.mock.calls[1]![1].body).description).toBeNull()
  })
  it.each([
    { ...base, workspaceId: '../1' }, { ...base, id: '0' }, { ...base, version: '01' },
    { ...base, version: '9223372036854775808' },
  ])('잘못된 경로·버전은 전송 전에 거부한다: %j', async input => {
    const fetch = vi.fn(); vi.stubGlobal('fetch', fetch)
    await expect(updateProject(input, 'Valid', null)).rejects.toBeInstanceOf(ProjectApiError)
    expect(fetch).not.toHaveBeenCalled()
  })
  it.each([[' ', null], ['Valid', 'x'.repeat(2001)], ['Bad\u0000', null]])('잘못된 입력은 전송하지 않는다', async (name, description) => {
    const fetch = vi.fn(); vi.stubGlobal('fetch', fetch)
    await expect(updateProject(base, name!, description)).rejects.toBeInstanceOf(ProjectApiError)
    expect(fetch).not.toHaveBeenCalled()
  })
  it.each([400, 403, 404, 405, 409, 413, 415])('%i 확정 거부는 결과 불명과 구분한다', async status => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json({ status, code: 'PROJECT_VERSION_CONFLICT', detail: 'secret SQL' }, status)))
    await expect(updateProject(base, 'Changed', null)).rejects.toMatchObject({ status })
  })
  it.each([
    { id: '11' }, { workspaceId: '2' }, { name: 'Unexpected' }, { description: 'Other' },
    { version: base.version }, { version: 1 }, { version: '01' },
  ])('잘못된 200은 성공으로 확정하지 않는다: %j', async invalid => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json({ ...base, name: 'Changed', description: null, version: '9007199254740994', ...invalid })))
    await expect(updateProject(base, 'Changed', null)).rejects.toBeInstanceOf(ProjectUpdateUncertainError)
  })
  it.each([201, 500, 503])('%i는 결과 불명이며 재시도하지 않는다', async status => {
    const fetch = vi.fn().mockResolvedValue(json({}, status)); vi.stubGlobal('fetch', fetch)
    await expect(updateProject(base, 'Changed', null)).rejects.toBeInstanceOf(ProjectUpdateUncertainError)
    expect(fetch).toHaveBeenCalledOnce()
  })
  it('응답 유실·깨진 JSON과 요청 취소를 결과 불명으로 처리한다', async () => {
    const fetch = vi.fn().mockRejectedValueOnce(new TypeError('offline')).mockResolvedValueOnce(new Response('{'))
      .mockRejectedValueOnce(new DOMException('aborted', 'AbortError'))
    vi.stubGlobal('fetch', fetch)
    await expect(updateProject(base, 'Changed', null)).rejects.toBeInstanceOf(ProjectUpdateUncertainError)
    await expect(updateProject(base, 'Changed', null)).rejects.toBeInstanceOf(ProjectUpdateUncertainError)
    const controller = new AbortController(); controller.abort()
    await expect(updateProject(base, 'Changed', null, controller.signal)).rejects.toBeInstanceOf(ProjectUpdateUncertainError)
    expect(fetch.mock.calls[2]![1].signal.aborted).toBe(true)
  })
})
