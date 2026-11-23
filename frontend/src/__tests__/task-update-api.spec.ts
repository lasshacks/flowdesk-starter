import { afterEach, describe, expect, it, vi } from 'vitest'
import { updateTask, TaskApiError, TaskUpdateUncertainError, type Task } from '../api/tasks'

const base: Task = { id: '10', workspaceId: '1', projectId: '7', status: 'TODO', priority: 'MEDIUM', title: 'Original', description: 'Old', assigneeId: null, dueDate: null, version: '9007199254740993', archived: false, archivedAt: null }
function json(value: unknown, status = 200) { return new Response(JSON.stringify(value), { status }) }
afterEach(() => vi.unstubAllGlobals())

describe('Task PATCH와 전체 행 버전', () => {
  it('부모 하위 PATCH에 정규화한 세 필드와 손실 없는 문자열 버전만 보낸다', async () => {
    const result = { ...base, title: 'Changed', description: 'First\nSecond', priority: 'HIGH', version: '9007199254740994' }
    const fetch = vi.fn().mockResolvedValue(json(result)); vi.stubGlobal('fetch', fetch)
    expect(await updateTask(base, ' Changed ', ' First\r\nSecond ', 'HIGH')).toEqual(result)
    const [url, init] = fetch.mock.calls[0]!
    expect(url).toBe('/api/workspaces/1/projects/7/tasks/10')
    expect(init).toMatchObject({ method: 'PATCH', credentials: 'omit', cache: 'no-store', redirect: 'error' })
    expect(JSON.parse(init.body)).toEqual({ title: 'Changed', description: 'First\nSecond', priority: 'HIGH', assigneeId: null, dueDate: null, version: base.version })
  })
  it('정규화한 no-op은 같은 버전이고 설명만 삭제해도 다음 버전이다', async () => {
    const fetch = vi.fn().mockResolvedValueOnce(json(base)).mockResolvedValueOnce(json({ ...base, description: null, version: '9007199254740994' }))
    vi.stubGlobal('fetch', fetch)
    expect((await updateTask(base, ' Original ', ' Old ', 'MEDIUM')).version).toBe(base.version)
    expect((await updateTask(base, 'Original', ' \r\n ', 'MEDIUM')).description).toBeNull()
    expect(JSON.parse(fetch.mock.calls[1]![1].body).description).toBeNull()
  })
  it.each([
    { ...base, projectId: '../1' }, { ...base, workspaceId: '../1' }, { ...base, id: '0' }, { ...base, version: '01' },
    { ...base, version: '9223372036854775808' },
  ])('잘못된 경로·버전은 전송 전에 거부한다: %j', async input => {
    const fetch = vi.fn(); vi.stubGlobal('fetch', fetch)
    await expect(updateTask(input, 'Valid', null, 'HIGH')).rejects.toBeInstanceOf(TaskApiError)
    expect(fetch).not.toHaveBeenCalled()
  })
  it.each([[' ', null], ['Valid', 'x'.repeat(2001)], ['Bad\u0000', null]])('잘못된 입력은 전송하지 않는다', async (title, description) => {
    const fetch = vi.fn(); vi.stubGlobal('fetch', fetch)
    await expect(updateTask(base, title!, description, 'HIGH')).rejects.toBeInstanceOf(TaskApiError)
    expect(fetch).not.toHaveBeenCalled()
  })
  it.each([400, 403, 404, 405, 409, 413, 415])('%i 확정 거부는 결과 불명과 구분한다', async status => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json({ status, code: 'PROJECT_VERSION_CONFLICT', detail: 'secret SQL' }, status)))
    await expect(updateTask(base, 'Changed', null, 'HIGH')).rejects.toMatchObject({ status })
  })
  it.each([
    { projectId: '8' }, { status: 'DONE' }, { priority: 'LOW' }, { id: '11' }, { workspaceId: '2' }, { title: 'Unexpected' }, { description: 'Other' },
    { version: base.version }, { version: 1 }, { version: '01' },
  ])('잘못된 200은 성공으로 확정하지 않는다: %j', async invalid => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json({ ...base, title: 'Changed', description: null, priority: 'HIGH', version: '9007199254740994', ...invalid })))
    await expect(updateTask(base, 'Changed', null, 'HIGH')).rejects.toBeInstanceOf(TaskUpdateUncertainError)
  })
  it.each([201, 500, 503])('%i는 결과 불명이며 재시도하지 않는다', async status => {
    const fetch = vi.fn().mockResolvedValue(json({}, status)); vi.stubGlobal('fetch', fetch)
    await expect(updateTask(base, 'Changed', null, 'HIGH')).rejects.toBeInstanceOf(TaskUpdateUncertainError)
    expect(fetch).toHaveBeenCalledOnce()
  })
  it('응답 유실·깨진 JSON과 요청 취소를 결과 불명으로 처리한다', async () => {
    const fetch = vi.fn().mockRejectedValueOnce(new TypeError('offline')).mockResolvedValueOnce(new Response('{'))
      .mockRejectedValueOnce(new DOMException('aborted', 'AbortError'))
    vi.stubGlobal('fetch', fetch)
    await expect(updateTask(base, 'Changed', null, 'HIGH')).rejects.toBeInstanceOf(TaskUpdateUncertainError)
    await expect(updateTask(base, 'Changed', null, 'HIGH')).rejects.toBeInstanceOf(TaskUpdateUncertainError)
    const controller = new AbortController(); controller.abort()
    await expect(updateTask(base, 'Changed', null, 'HIGH', base.assigneeId, base.dueDate, controller.signal)).rejects.toBeInstanceOf(TaskUpdateUncertainError)
    expect(fetch.mock.calls[2]![1].signal.aborted).toBe(true)
  })

  it('우선순위만 바뀌어도 다음 버전이며 잘못된 우선순위는 전송하지 않는다', async () => {
    const fetch = vi.fn().mockResolvedValue(json({ ...base, priority: 'HIGH', version: '9007199254740994' })); vi.stubGlobal('fetch', fetch)
    expect((await updateTask(base, base.title, base.description, 'HIGH')).version).toBe('9007199254740994')
    await expect(updateTask(base, base.title, null, 'urgent' as never)).rejects.toBeInstanceOf(TaskApiError)
    expect(fetch).toHaveBeenCalledOnce()
  })
  it('담당자와 날짜 수정도 기존 Task 버전을 공유한다', async () => {
    const result = { ...base, assigneeId: '2', dueDate: '2028-02-29', version: '9007199254740994' }
    const fetch = vi.fn().mockResolvedValue(json(result)); vi.stubGlobal('fetch', fetch)
    expect(await updateTask(base, base.title, base.description, base.priority, '2', '2028-02-29')).toEqual(result)
    expect(JSON.parse(fetch.mock.calls[0]![1].body)).toEqual({ title: base.title, description: base.description, priority: base.priority, assigneeId: '2', dueDate: '2028-02-29', version: base.version })
    const invalid = vi.fn(); vi.stubGlobal('fetch', invalid)
    await expect(updateTask(base, base.title, base.description, base.priority, '01', null)).rejects.toBeInstanceOf(TaskApiError)
    await expect(updateTask(base, base.title, base.description, base.priority, null, '2028-02-30')).rejects.toBeInstanceOf(TaskApiError)
    expect(invalid).not.toHaveBeenCalled()
  })
  it('10초 제한과 외부 취소를 전달한다', async () => {
    const timeout = vi.spyOn(AbortSignal, 'timeout')
    const fetch = vi.fn().mockResolvedValue(json(base)); vi.stubGlobal('fetch', fetch)
    const controller = new AbortController()
    await updateTask(base, base.title, base.description, base.priority, base.assigneeId, base.dueDate, controller.signal)
    expect(timeout).toHaveBeenCalledWith(10000)
    controller.abort(); expect(fetch.mock.calls[0]![1].signal.aborted).toBe(true)
    timeout.mockRestore()
  })
})
