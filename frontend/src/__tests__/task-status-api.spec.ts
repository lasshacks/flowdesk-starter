import { afterEach, describe, expect, it, vi } from 'vitest'
import { changeTaskStatus, TaskApiError, TaskStatusUncertainError, type Task, type TaskStatus } from '../api/tasks'

const base: Task = { id: '10', workspaceId: '1', projectId: '7', title: 'Original', description: 'Old', priority: 'HIGH', status: 'TODO', assigneeId: null, dueDate: null, version: '9007199254740993', archived: false, archivedAt: null }
function json(value: unknown, status = 200) { return new Response(JSON.stringify(value), { status }) }
afterEach(() => vi.unstubAllGlobals())

describe('Task status PATCH의 두 필드·공유 버전·결과 확정', () => {
  it('상태와 문자열 버전만 보내고 일반 필드/큰 버전을 보존한다', async () => {
    const result = { ...base, status: 'DONE', version: '9007199254740994' }
    const fetch = vi.fn().mockResolvedValue(json(result)); vi.stubGlobal('fetch', fetch)
    expect(await changeTaskStatus(base, 'DONE')).toEqual(result)
    const [url, init] = fetch.mock.calls[0]!
    expect(url).toBe('/api/workspaces/1/projects/7/tasks/10/status')
    expect(init).toMatchObject({ method: 'PATCH', credentials: 'omit', cache: 'no-store', redirect: 'error' })
    expect(JSON.parse(init.body)).toEqual({ status: 'DONE', version: base.version })
  })
  it.each(['TODO', 'IN_PROGRESS', 'DONE'] as const)('같은 상태 %s는 같은 버전이다', async status => {
    const input = { ...base, status }
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json(input)))
    expect((await changeTaskStatus(input, status)).version).toBe(base.version)
  })
  it.each([
    ['TODO', 'IN_PROGRESS'], ['TODO', 'DONE'], ['IN_PROGRESS', 'TODO'],
    ['IN_PROGRESS', 'DONE'], ['DONE', 'TODO'], ['DONE', 'IN_PROGRESS'],
  ] as [TaskStatus, TaskStatus][])('%s → %s도 별도 상태 버전 없이 다음 Task 버전이다', async (previous, target) => {
    const result = { ...base, status: target, version: '9007199254740994' }
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json(result)))
    expect(await changeTaskStatus({ ...base, status: previous }, target)).toEqual(result)
  })
  it.each([
    { ...base, workspaceId: '../1' }, { ...base, projectId: '0' }, { ...base, id: '01' },
    { ...base, version: '01' }, { ...base, version: '9223372036854775808' },
  ])('경로/버전이 잘못되면 보내지 않는다: %j', async input => {
    const fetch = vi.fn(); vi.stubGlobal('fetch', fetch)
    await expect(changeTaskStatus(input, 'DONE')).rejects.toBeInstanceOf(TaskApiError)
    expect(fetch).not.toHaveBeenCalled()
  })
  it.each(['done', ' DONE', 'DONE ', 'UNKNOWN', ''])('지원하지 않는 상태를 정규화하거나 전송하지 않는다: %s', async target => {
    const fetch = vi.fn(); vi.stubGlobal('fetch', fetch)
    await expect(changeTaskStatus(base, target as TaskStatus)).rejects.toBeInstanceOf(TaskApiError)
    expect(fetch).not.toHaveBeenCalled()
  })
  it.each([400, 403, 404, 405, 409, 413, 415])('%i는 안전한 확정 거부로 분류한다', async status => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json({ status, code: status === 409 ? 'TASK_VERSION_CONFLICT' : 'INVALID_REQUEST', detail: 'secret SQL' }, status)))
    await expect(changeTaskStatus(base, 'DONE')).rejects.toMatchObject({ status })
  })
  it.each([
    { id: '11' }, { workspaceId: '2' }, { projectId: '8' }, { title: 'Different' }, { description: null },
    { priority: 'LOW' }, { status: 'TODO' }, { version: base.version }, { version: 2 }, { version: '01' },
  ])('200의 대상/일반 필드/상태/버전이 다르면 결과 불명이다: %j', async invalid => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json({ ...base, status: 'DONE', version: '9007199254740994', ...invalid })))
    await expect(changeTaskStatus(base, 'DONE')).rejects.toBeInstanceOf(TaskStatusUncertainError)
  })
  it.each([201, 500, 503])('%i는 결과 불명이며 자동 재전송하지 않는다', async status => {
    const fetch = vi.fn().mockResolvedValue(json({}, status)); vi.stubGlobal('fetch', fetch)
    await expect(changeTaskStatus(base, 'DONE')).rejects.toBeInstanceOf(TaskStatusUncertainError)
    expect(fetch).toHaveBeenCalledOnce()
  })
  it('네트워크/깨진 JSON/취소와 10초 제한을 보존한다', async () => {
    const fetch = vi.fn().mockRejectedValueOnce(new TypeError('offline')).mockResolvedValueOnce(new Response('{'))
      .mockRejectedValueOnce(new DOMException('aborted', 'AbortError'))
    vi.stubGlobal('fetch', fetch)
    const timeout = vi.spyOn(AbortSignal, 'timeout')
    try {
      await expect(changeTaskStatus(base, 'DONE')).rejects.toBeInstanceOf(TaskStatusUncertainError)
      await expect(changeTaskStatus(base, 'DONE')).rejects.toBeInstanceOf(TaskStatusUncertainError)
      const controller = new AbortController(); controller.abort()
      await expect(changeTaskStatus(base, 'DONE', controller.signal)).rejects.toBeInstanceOf(TaskStatusUncertainError)
      expect(fetch.mock.calls[2]![1].signal.aborted).toBe(true)
      expect(timeout).toHaveBeenCalledWith(10000)
    } finally { timeout.mockRestore() }
  })
})
