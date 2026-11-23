import { afterEach, describe, expect, it, vi } from 'vitest'
import { getTask, getTasks, TaskApiError } from '../api/tasks'

afterEach(() => { vi.unstubAllGlobals(); vi.useRealTimers(); vi.restoreAllMocks() })
const item = { id: '9223372036854775807', workspaceId: '9007199254740993', projectId: '9007199254740994', title: '작업', status: 'TODO', priority: 'MEDIUM', assigneeId: null, dueDate: null, archived: false }
const page = { items: [item], page: 0, size: 20, hasNext: false }
function json(value: unknown, status = 200) { return new Response(JSON.stringify(value), { status }) }

describe('Task 조회 계약', () => {
  it('부모/Task ID를 문자열로 보존하고 쿠키·행위자 없이 중첩 GET만 요청한다', async () => {
    const fetch = vi.fn().mockResolvedValueOnce(json(page)).mockResolvedValueOnce(json({ ...item, version: '0', description: 'line1\nline2\t<em>text</em>' }))
    vi.stubGlobal('fetch', fetch)
    expect(await getTasks(item.workspaceId, item.projectId)).toEqual(page)
    expect(await getTask(item.workspaceId, item.projectId, item.id)).toEqual({ ...item, version: '0', description: 'line1\nline2\t<em>text</em>', archivedAt: null })
    expect(fetch).toHaveBeenNthCalledWith(1, `/api/workspaces/${item.workspaceId}/projects/${item.projectId}/tasks?page=0&size=20`, expect.objectContaining({
      credentials: 'omit', cache: 'no-store', redirect: 'error', headers: { Accept: 'application/json' }, signal: expect.any(AbortSignal),
    }))
    expect(fetch).toHaveBeenNthCalledWith(2, `/api/workspaces/${item.workspaceId}/projects/${item.projectId}/tasks/${item.id}`, expect.any(Object))
    expect(fetch.mock.calls.every(call => call[1].method === undefined && call[1].body === undefined)).toBe(true)
  })
  it.each([
    null, [], { ...page, items: [{ ...item, version: '0', projectId: '1' }] },
    { ...page, items: [{ ...item, version: '0', projectId: Number(item.projectId) }] },
    { ...page, items: [{ ...item, status: 'todo' }] }, { ...page, items: [{ ...item, priority: null }] }, { ...page, items: [{ ...item, archived: 'yes' }] }, { ...page, items: [{}] }, { ...page, items: [{ ...item, version: '0', id: 123 }] },
    { ...page, items: [{ ...item, version: '0', id: '01' }] }, { ...page, items: [{ ...item, version: '0', id: '9223372036854775808' }] },
    { ...page, items: [{ ...item, version: '0', workspaceId: '1' }] }, { ...page, items: [{ ...item, version: '0', workspaceId: Number(item.workspaceId) }] },
    { ...page, items: [{ ...item, version: '0', title: ' ' }] }, { ...page, items: [{ ...item, version: '0', title: 'x'.repeat(201) }] },
    { ...page, items: [{ ...item, version: '0', title: '\uD800' }] }, { ...page, items: [{ ...item, version: '0', title: 'a\nb' }] },
    { ...page, items: [item, item] }, { ...page, items: [item, { ...item, version: '0', id: '1' }] },
    { ...page, page: 1 }, { ...page, size: 100 }, { ...page, hasNext: 'false' }, { ...page, items: Array(21).fill(item) },
  ])('부모·ID·정렬·페이지·이름이 다른 목록은 표시하지 않는다: %j', async invalid => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json(invalid)))
    await expect(getTasks(item.workspaceId, item.projectId)).rejects.toMatchObject({ code: 'INVALID_RESPONSE' })
  })
  it.each([undefined, 123, '', ' ', 'x'.repeat(2001), 'a\u0000b', '\uD800', 'a\rb'])('잘못된 상세 설명을 거부한다: %j', async description => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json({ ...item, description })))
    await expect(getTask(item.workspaceId, item.projectId, item.id)).rejects.toMatchObject({ code: 'INVALID_RESPONSE' })
  })
  it('nullable 설명·최대 Unicode 길이와 안전한 DTO 필드만 반환한다', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(json({ ...item, version: '0', description: null, userId: 'private' }))
      .mockResolvedValueOnce(json({ ...item, version: '0', title: '🚀'.repeat(200), description: '🚀'.repeat(2000) })))
    expect(await getTask(item.workspaceId, item.projectId, item.id)).toEqual({ ...item, version: '0', description: null, archivedAt: null })
    expect((await getTask(item.workspaceId, item.projectId, item.id)).description).toHaveLength(4000)
  })
  it('상세 archive metadata의 timestamp와 상태 관계를 검증한다', async () => {
    vi.stubGlobal('fetch', vi.fn()
      .mockResolvedValueOnce(json({ ...item, version: '1', description: null, archived: true, archivedAt: '2026-11-23T12:30:00Z' }))
      .mockResolvedValueOnce(json({ ...item, version: '1', description: null, archived: true, archivedAt: null }))
      .mockResolvedValueOnce(json({ ...item, version: '1', description: null, archived: false, archivedAt: '2026-11-23T12:30:00Z' })))
    expect(await getTask(item.workspaceId, item.projectId, item.id)).toMatchObject({ archived: true, archivedAt: '2026-11-23T12:30:00Z' })
    await expect(getTask(item.workspaceId, item.projectId, item.id)).rejects.toMatchObject({ code: 'INVALID_RESPONSE' })
    await expect(getTask(item.workspaceId, item.projectId, item.id)).rejects.toMatchObject({ code: 'INVALID_RESPONSE' })
  })
  it.each([{ assigneeId: '01' }, { assigneeId: 1 }, { dueDate: '2027-02-29' }, { dueDate: '0000-01-01' }, { dueDate: 123 }])('담당자 ID/달력 날짜가 잘못된 응답을 거부한다: %j', async fields => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json({ ...item, ...fields })))
    await expect(getTasks(item.workspaceId, item.projectId)).rejects.toMatchObject({ code: 'INVALID_RESPONSE' })
  })
  it('서버의 Java 공백 기준을 따르고 JS trim으로 정상 응답을 지우지 않는다', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json({ ...item, version: '0', title: '\uFEFF', description: '\uFEFF' })))
    expect((await getTask(item.workspaceId, item.projectId, item.id)).title).toBe('\uFEFF')
  })
  it('다른 상세 ID·부모와 JSON 아닌 성공 응답·잘못된 성공 상태를 거부한다', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(json({ ...item, version: '0', id: '1', description: null }))
      .mockResolvedValueOnce(json({ ...item, version: '0', workspaceId: '1', description: null }))
      .mockResolvedValueOnce(new Response('<html>proxy</html>')).mockResolvedValueOnce(json(page, 201)))
    await expect(getTask(item.workspaceId, item.projectId, item.id)).rejects.toMatchObject({ code: 'INVALID_RESPONSE' })
    await expect(getTask(item.workspaceId, item.projectId, item.id)).rejects.toMatchObject({ code: 'INVALID_RESPONSE' })
    await expect(getTasks(item.workspaceId, item.projectId)).rejects.toMatchObject({ code: 'INVALID_RESPONSE' })
    await expect(getTasks(item.workspaceId, item.projectId)).rejects.toMatchObject({ code: 'INVALID_RESPONSE' })
  })
  it('안전한 오류 코드만 사용하고 404/HTML 오류를 빈 목록으로 바꾸지 않는다', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(json({ status: 404, code: 'TASK_NOT_FOUND', detail: 'secret SQL' }, 404))
      .mockResolvedValueOnce(new Response('<html>secret proxy</html>', { status: 503 }))
      .mockResolvedValueOnce(json({ status: 503, code: 'secret detail' }, 503)))
    try { await getTasks(item.workspaceId, item.projectId); expect.fail('must reject') }
    catch (cause) { expect(cause).toBeInstanceOf(TaskApiError); expect(cause).toMatchObject({ code: 'TASK_NOT_FOUND', status: 404 }); expect((cause as Error).message).not.toContain('secret') }
    await expect(getTasks(item.workspaceId, item.projectId)).rejects.toMatchObject({ code: 'HTTP_ERROR', status: 503 })
    await expect(getTasks(item.workspaceId, item.projectId)).rejects.toMatchObject({ code: 'HTTP_ERROR', status: 503 })
  })
  it('잘못된 입력은 통신 전에 차단하고 외부 취소를 전달한다', async () => {
    const fetch = vi.fn().mockResolvedValue(json(page)); vi.stubGlobal('fetch', fetch)
    for (const id of ['0', '01', '-1', '1/../../health', '9223372036854775808']) {
      await expect(getTasks(item.workspaceId, id)).rejects.toMatchObject({ code: 'INVALID_REQUEST' })
      await expect(getTask(id, item.projectId, item.id)).rejects.toMatchObject({ code: 'INVALID_REQUEST' })
      await expect(getTask(item.workspaceId, id, item.id)).rejects.toMatchObject({ code: 'INVALID_REQUEST' })
      await expect(getTasks(id, item.projectId)).rejects.toMatchObject({ code: 'INVALID_REQUEST' })
      await expect(getTask(item.workspaceId, item.projectId, id)).rejects.toMatchObject({ code: 'INVALID_REQUEST' })
    }
    for (const number of [-1, 10001, 1.5, NaN]) await expect(getTasks(item.workspaceId, item.projectId, number)).rejects.toMatchObject({ code: 'INVALID_REQUEST' })
    expect(fetch).not.toHaveBeenCalled()
    const controller = new AbortController(); await getTasks(item.workspaceId, item.projectId, 0, controller.signal)
    const signal = fetch.mock.calls[0]![1].signal as AbortSignal; controller.abort(); expect(signal.aborted).toBe(true)
  })
  it('5초 제한 신호가 전달되고 시간 초과를 성공으로 처리하지 않는다', async () => {
    let fire!: () => void
    vi.spyOn(AbortSignal, 'timeout').mockImplementation(() => { const controller = new AbortController(); fire = () => controller.abort(new DOMException('Timeout', 'TimeoutError')); return controller.signal })
    vi.stubGlobal('fetch', vi.fn((_url, options: RequestInit) => new Promise((_resolve, reject) => {
      options.signal!.addEventListener('abort', () => reject(options.signal!.reason), { once: true })
    })))
    const pending = getTasks(item.workspaceId, item.projectId)
    const assertion = expect(pending).rejects.toMatchObject({ name: 'TimeoutError' })
    fire(); await assertion
    vi.restoreAllMocks()
  })

  it.each(['TODO', 'IN_PROGRESS', 'DONE'])('상태 %s와 세 우선순위를 그대로 보존한다', async status => {
    for (const priority of ['LOW', 'MEDIUM', 'HIGH']) {
      vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json({ ...item, version: '0', status, priority, description: null })))
      expect(await getTask(item.workspaceId, item.projectId, item.id)).toMatchObject({ status, priority })
    }
  })
  it('다른 프로젝트의 상세는 동일한 ID여도 거부한다', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json({ ...item, version: '0', projectId: '1', description: null })))
    await expect(getTask(item.workspaceId, item.projectId, item.id)).rejects.toMatchObject({ code: 'INVALID_RESPONSE' })
  })

  it.each([undefined, 0, '-1', '01', '9223372036854775808'])('잘못된 Task 버전은 거부한다: %j', async version => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json({ ...item, description: null, version })))
    await expect(getTask(item.workspaceId, item.projectId, item.id)).rejects.toMatchObject({ code: 'INVALID_RESPONSE' })
  })
  it('큰 Task 버전은 문자열로 보존하며 목록에는 버전을 추가하지 않는다', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(json({ ...item, description: null, version: '9007199254740993' }))
      .mockResolvedValueOnce(json(page)))
    expect((await getTask(item.workspaceId, item.projectId, item.id)).version).toBe('9007199254740993')
    expect((await getTasks(item.workspaceId, item.projectId)).items[0]).toEqual(item)
  })
})
