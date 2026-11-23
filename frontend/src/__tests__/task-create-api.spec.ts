import { afterEach, describe, expect, it, vi } from 'vitest'
import { createTask, TaskCreateUncertainError, normalizeTaskDescription } from '../api/tasks'

afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks() })
const dto = { version: '0', id: '9223372036854775807', workspaceId: '9007199254740993', projectId: '9007199254740994', status: 'TODO', priority: 'MEDIUM', title: 'Launch', description: 'line1\nline2\ttext', assigneeId: null, dueDate: null, archived: false, archivedAt: null }
function response(value: unknown = dto, status = 201, location: string | null = `/api/workspaces/${dto.workspaceId}/projects/${dto.projectId}/tasks/${dto.id}`) {
  return new Response(JSON.stringify(value), { status, headers: location ? { Location: location } : {} })
}
describe('Task 생성 요청', () => {
  it('부모는 URL에만 두고 제목/설명을 정규화해 보내며 201/Location/DTO를 확인한다', async () => {
    const fetch = vi.fn().mockResolvedValue(response()); vi.stubGlobal('fetch', fetch)
    expect(await createTask(dto.workspaceId, dto.projectId, ' \u00a0Launch\u2003 ', ' line1\r\nline2\ttext  ')).toEqual(dto)
    expect(fetch).toHaveBeenCalledOnce()
    const [path, options] = fetch.mock.calls[0]!
    expect(path).toBe(`/api/workspaces/${dto.workspaceId}/projects/${dto.projectId}/tasks`)
    expect(options).toMatchObject({ method: 'POST', credentials: 'omit', cache: 'no-store', redirect: 'error', headers: { Accept: 'application/json', 'Content-Type': 'application/json' } })
    expect(JSON.parse(options.body)).toEqual({ title: 'Launch', description: 'line1\nline2\ttext', priority: 'MEDIUM', assigneeId: null, dueDate: null })
  })
  it('빈 설명은 null, 최대 길이는 Unicode 코드 포인트로 처리한다', async () => {
    const fetch = vi.fn().mockResolvedValueOnce(response({ ...dto, description: null }))
      .mockResolvedValueOnce(response({ ...dto, title: '🚀'.repeat(200), description: '🚀'.repeat(2000) }))
    vi.stubGlobal('fetch', fetch)
    expect((await createTask(dto.workspaceId, dto.projectId, 'Launch', ' \r\n\t\u00a0 ')).description).toBeNull()
    expect((await createTask(dto.workspaceId, dto.projectId, '🚀'.repeat(200), '🚀'.repeat(2000))).description).toHaveLength(4000)
    expect(normalizeTaskDescription(null)).toBeNull()
  })
  it.each([
    ['0', 'x', ''], ['01', 'x', ''], ['9223372036854775808', 'x', ''], ['1/../../api', 'x', ''],
    ['1', '', ''], ['1', '\u00a0\u2003', ''], ['1', '🚀'.repeat(201), ''], ['1', 'a\nb', ''], ['1', '\uD800', ''],
    ['1', 'x', '🚀'.repeat(2001)], ['1', 'x', 'a\u0000b'], ['1', 'x', '\uDFFF'],
  ])('잘못된 부모/제목/설명은 요청 전에 거부한다', async (id, title, description) => {
    const fetch = vi.fn(); vi.stubGlobal('fetch', fetch)
    await expect(createTask(id!, dto.projectId, title!, description!)).rejects.toMatchObject({ status: 0 })
    expect(fetch).not.toHaveBeenCalled()
  })
  it.each([400, 403, 404, 405, 413, 415])('확정 거부 %i는 결과 불명과 구분하고 원문을 숨긴다', async status => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response({ status, code: status === 403 ? 'PROJECT_FORBIDDEN' : 'INVALID_REQUEST', detail: 'secret SQL' }, status)))
    await expect(createTask(dto.workspaceId, dto.projectId, dto.title, dto.description)).rejects.toMatchObject({ status })
  })
  it.each([
    { ...dto, workspaceId: '1' }, { ...dto, id: 123 }, { ...dto, id: '0' }, { ...dto, title: 'Other' },
    { ...dto, version: undefined }, { ...dto, version: 0 }, { ...dto, version: '1' },
    { ...dto, projectId: '1' }, { ...dto, status: 'DONE' }, { ...dto, priority: 'LOW' },
    { ...dto, description: null }, { ...dto, description: 123 }, { ...dto, description: undefined },
  ])('잘못된 201은 재시도 가능한 실패로 오인하지 않는다: %j', async body => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response(body)))
    await expect(createTask(dto.workspaceId, dto.projectId, dto.title, dto.description)).rejects.toBeInstanceOf(TaskCreateUncertainError)
  })
  it.each([null, '/api/workspaces/1/projects/1/tasks/1', `https://evil.test/api/workspaces/${dto.workspaceId}/projects/${dto.projectId}/tasks/${dto.id}`])('Location 불일치는 결과 불명이다', async location => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response(dto, 201, location)))
    await expect(createTask(dto.workspaceId, dto.projectId, dto.title, dto.description)).rejects.toBeInstanceOf(TaskCreateUncertainError)
  })
  it.each([200, 500, 503])('미확정 상태 %i는 자동 재시도하지 않는다', async status => {
    const fetch = vi.fn().mockResolvedValue(response(dto, status)); vi.stubGlobal('fetch', fetch)
    await expect(createTask(dto.workspaceId, dto.projectId, dto.title, dto.description)).rejects.toBeInstanceOf(TaskCreateUncertainError)
    expect(fetch).toHaveBeenCalledOnce()
  })
  it('JSON 아닌 201·네트워크 장애·취소도 성공 여부를 단정하지 않는다', async () => {
    const fetch = vi.fn().mockResolvedValueOnce(new Response('<html>proxy</html>', { status: 201 }))
      .mockRejectedValueOnce(new TypeError('offline')).mockRejectedValueOnce(new DOMException('Aborted', 'AbortError'))
    vi.stubGlobal('fetch', fetch)
    for (let i = 0; i < 3; i++) await expect(createTask(dto.workspaceId, dto.projectId, dto.title, dto.description)).rejects.toBeInstanceOf(TaskCreateUncertainError)
    expect(fetch).toHaveBeenCalledTimes(3)
  })
  it('10초 제한·외부 취소 신호를 전달한다', async () => {
    const timeout = vi.spyOn(AbortSignal, 'timeout')
    const fetch = vi.fn().mockResolvedValue(response()); vi.stubGlobal('fetch', fetch)
    const controller = new AbortController(); await createTask(dto.workspaceId, dto.projectId, dto.title, dto.description, 'MEDIUM', null, null, controller.signal)
    expect(timeout).toHaveBeenCalledWith(10000)
    controller.abort(); expect((fetch.mock.calls[0]![1].signal as AbortSignal).aborted).toBe(true)
  })

  it.each(['LOW', 'MEDIUM', 'HIGH'] as const)('선택 우선순위 %s만 전송하고 TODO는 서버 응답에서 검사한다', async priority => {
    const fetch = vi.fn().mockResolvedValue(response({ ...dto, priority })); vi.stubGlobal('fetch', fetch)
    expect((await createTask(dto.workspaceId, dto.projectId, dto.title, dto.description, priority)).priority).toBe(priority)
    expect(JSON.parse(fetch.mock.calls[0]![1].body)).toEqual({ title: dto.title, description: dto.description, priority, assigneeId: null, dueDate: null })
  })
  it('선택 담당자 ID와 달력 날짜를 보내고 응답을 확인한다', async () => {
    const assigned = { ...dto, priority: 'HIGH', assigneeId: '9223372036854775807', dueDate: '2028-02-29' }
    const fetch = vi.fn().mockResolvedValue(response(assigned)); vi.stubGlobal('fetch', fetch)
    expect(await createTask(dto.workspaceId, dto.projectId, dto.title, dto.description, 'HIGH', assigned.assigneeId, assigned.dueDate)).toEqual(assigned)
    expect(JSON.parse(fetch.mock.calls[0]![1].body)).toEqual({ title: dto.title, description: dto.description, priority: 'HIGH', assigneeId: assigned.assigneeId, dueDate: assigned.dueDate })
  })
  it.each([['01', '2028-02-29'], ['9223372036854775808', null], [null, '2027-02-29']])('잘못된 담당자/날짜는 전송 전에 거부한다: %s %s', async (assigneeId, dueDate) => {
    const fetch = vi.fn(); vi.stubGlobal('fetch', fetch)
    await expect(createTask(dto.workspaceId, dto.projectId, dto.title, dto.description, 'MEDIUM', assigneeId as string | null, dueDate as string | null)).rejects.toMatchObject({ code: 'INVALID_REQUEST' })
    expect(fetch).not.toHaveBeenCalled()
  })
  it('잘못된 Project와 우선순위를 통신 전에 거부한다', async () => {
    const fetch = vi.fn(); vi.stubGlobal('fetch', fetch)
    for (const project of ['0', '01', '../tasks', '9223372036854775808']) {
      await expect(createTask(dto.workspaceId, project, dto.title, null)).rejects.toMatchObject({ code: 'INVALID_REQUEST' })
    }
    await expect(createTask(dto.workspaceId, dto.projectId, dto.title, null, null as never)).rejects.toMatchObject({ code: 'INVALID_REQUEST' })
    await expect(createTask(dto.workspaceId, dto.projectId, dto.title, null, 'low' as never)).rejects.toMatchObject({ code: 'INVALID_REQUEST' })
    expect(fetch).not.toHaveBeenCalled()
  })
})
