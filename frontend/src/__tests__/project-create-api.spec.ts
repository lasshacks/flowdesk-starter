import { afterEach, describe, expect, it, vi } from 'vitest'
import { createProject, ProjectCreateUncertainError, normalizeProjectDescription } from '../api/projects'

afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks() })
const dto = { version: '0', id: '9223372036854775807', workspaceId: '9007199254740993', name: 'Launch', archived: false, archivedAt: null, description: 'line1\nline2\ttext' }
function response(value: unknown = dto, status = 201, location: string | null = `/api/workspaces/${dto.workspaceId}/projects/${dto.id}`) {
  return new Response(JSON.stringify(value), { status, headers: location ? { Location: location } : {} })
}
describe('Project 생성 요청', () => {
  it('부모는 URL에만 두고 이름/설명을 정규화해 보내며 201/Location/DTO를 확인한다', async () => {
    const fetch = vi.fn().mockResolvedValue(response()); vi.stubGlobal('fetch', fetch)
    expect(await createProject(dto.workspaceId, ' \u00a0Launch\u2003 ', ' line1\r\nline2\ttext  ')).toEqual(dto)
    expect(fetch).toHaveBeenCalledOnce()
    const [path, options] = fetch.mock.calls[0]!
    expect(path).toBe(`/api/workspaces/${dto.workspaceId}/projects`)
    expect(options).toMatchObject({ method: 'POST', credentials: 'omit', cache: 'no-store', redirect: 'error', headers: { Accept: 'application/json', 'Content-Type': 'application/json' } })
    expect(JSON.parse(options.body)).toEqual({ name: 'Launch', description: 'line1\nline2\ttext' })
  })
  it('빈 설명은 null, 최대 길이는 Unicode 코드 포인트로 처리한다', async () => {
    const fetch = vi.fn().mockResolvedValueOnce(response({ ...dto, description: null }))
      .mockResolvedValueOnce(response({ ...dto, name: '🚀'.repeat(100), description: '🚀'.repeat(2000) }))
    vi.stubGlobal('fetch', fetch)
    expect((await createProject(dto.workspaceId, 'Launch', ' \r\n\t\u00a0 ')).description).toBeNull()
    expect((await createProject(dto.workspaceId, '🚀'.repeat(100), '🚀'.repeat(2000))).description).toHaveLength(4000)
    expect(normalizeProjectDescription(null)).toBeNull()
  })
  it.each([
    ['0', 'x', ''], ['01', 'x', ''], ['9223372036854775808', 'x', ''], ['1/../../api', 'x', ''],
    ['1', '', ''], ['1', '\u00a0\u2003', ''], ['1', '🚀'.repeat(101), ''], ['1', 'a\nb', ''], ['1', '\uD800', ''],
    ['1', 'x', '🚀'.repeat(2001)], ['1', 'x', 'a\u0000b'], ['1', 'x', '\uDFFF'],
  ])('잘못된 부모/이름/설명은 요청 전에 거부한다', async (id, name, description) => {
    const fetch = vi.fn(); vi.stubGlobal('fetch', fetch)
    await expect(createProject(id!, name!, description!)).rejects.toMatchObject({ status: 0 })
    expect(fetch).not.toHaveBeenCalled()
  })
  it.each([400, 403, 404, 405, 413, 415])('확정 거부 %i는 결과 불명과 구분하고 원문을 숨긴다', async status => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response({ status, code: status === 403 ? 'PROJECT_FORBIDDEN' : 'INVALID_REQUEST', detail: 'secret SQL' }, status)))
    await expect(createProject(dto.workspaceId, dto.name, dto.description)).rejects.toMatchObject({ status })
  })
  it.each([
    { ...dto, workspaceId: '1' }, { ...dto, id: 123 }, { ...dto, id: '0' }, { ...dto, name: 'Other' },
    { ...dto, version: undefined }, { ...dto, version: 0 }, { ...dto, version: '1' },
    { ...dto, description: null }, { ...dto, description: 123 }, { ...dto, description: undefined },
  ])('잘못된 201은 재시도 가능한 실패로 오인하지 않는다: %j', async body => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response(body)))
    await expect(createProject(dto.workspaceId, dto.name, dto.description)).rejects.toBeInstanceOf(ProjectCreateUncertainError)
  })
  it.each([null, '/api/workspaces/1/projects/1', `https://evil.test/api/workspaces/${dto.workspaceId}/projects/${dto.id}`])('Location 불일치는 결과 불명이다', async location => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response(dto, 201, location)))
    await expect(createProject(dto.workspaceId, dto.name, dto.description)).rejects.toBeInstanceOf(ProjectCreateUncertainError)
  })
  it.each([200, 500, 503])('미확정 상태 %i는 자동 재시도하지 않는다', async status => {
    const fetch = vi.fn().mockResolvedValue(response(dto, status)); vi.stubGlobal('fetch', fetch)
    await expect(createProject(dto.workspaceId, dto.name, dto.description)).rejects.toBeInstanceOf(ProjectCreateUncertainError)
    expect(fetch).toHaveBeenCalledOnce()
  })
  it('JSON 아닌 201·네트워크 장애·취소도 성공 여부를 단정하지 않는다', async () => {
    const fetch = vi.fn().mockResolvedValueOnce(new Response('<html>proxy</html>', { status: 201 }))
      .mockRejectedValueOnce(new TypeError('offline')).mockRejectedValueOnce(new DOMException('Aborted', 'AbortError'))
    vi.stubGlobal('fetch', fetch)
    for (let i = 0; i < 3; i++) await expect(createProject(dto.workspaceId, dto.name, dto.description)).rejects.toBeInstanceOf(ProjectCreateUncertainError)
    expect(fetch).toHaveBeenCalledTimes(3)
  })
  it('10초 제한·외부 취소 신호를 전달한다', async () => {
    const timeout = vi.spyOn(AbortSignal, 'timeout')
    const fetch = vi.fn().mockResolvedValue(response()); vi.stubGlobal('fetch', fetch)
    const controller = new AbortController(); await createProject(dto.workspaceId, dto.name, dto.description, controller.signal)
    expect(timeout).toHaveBeenCalledWith(10000)
    controller.abort(); expect((fetch.mock.calls[0]![1].signal as AbortSignal).aborted).toBe(true)
  })
})
