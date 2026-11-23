import { afterEach, describe, expect, it, vi } from 'vitest'
import { getProjects, ProjectApiError, ProjectArchiveUncertainError, setProjectArchived, type Project } from '../api/projects'

afterEach(() => vi.unstubAllGlobals())
const base: Project = { id: '21', workspaceId: '3', name: 'Archive candidate', description: null, version: '4', archived: false, archivedAt: null }
function json(value: unknown, status = 200) { return new Response(JSON.stringify(value), { status }) }

describe('Project 보관 API', () => {
  it('보관 목록 query를 명시하고 archived 요약 상태를 보존한다', async () => {
    const fetch = vi.fn().mockResolvedValue(json({ items: [{ id: '21', workspaceId: '3', name: base.name, archived: true }], page: 0, size: 20, hasNext: false }))
    vi.stubGlobal('fetch', fetch)
    expect(await getProjects('3', 0, undefined, true)).toMatchObject({ items: [{ id: '21', archived: true }] })
    expect(fetch.mock.calls[0]![0]).toBe('/api/workspaces/3/projects?page=0&size=20&includeArchived=true')
  })

  it('현재 Project 버전으로 보관 PATCH를 보내고 증가된 DTO를 검증한다', async () => {
    const archived = { ...base, archived: true, archivedAt: '2026-11-23T12:30:00Z', version: '5' }
    const fetch = vi.fn().mockResolvedValue(json(archived)); vi.stubGlobal('fetch', fetch)
    expect(await setProjectArchived(base, true)).toEqual(archived)
    const [path, init] = fetch.mock.calls[0]!
    expect(path).toBe('/api/workspaces/3/projects/21/archive')
    expect(init).toMatchObject({ method: 'PATCH', credentials: 'omit', cache: 'no-store', redirect: 'error' })
    expect(JSON.parse(init.body)).toEqual({ archived: true, version: '4' })
  })

  it('복구는 archivedAt을 지우고 버전을 한 번 증가시킨다', async () => {
    const archived: Project = { ...base, archived: true, archivedAt: '2026-11-23T12:30:00Z' }
    const restored = { ...base, version: '5' }
    const fetch = vi.fn().mockResolvedValue(json(restored)); vi.stubGlobal('fetch', fetch)
    expect(await setProjectArchived(archived, false)).toEqual(restored)
    expect(JSON.parse(fetch.mock.calls[0]![1].body)).toEqual({ archived: false, version: '4' })
  })

  it('409는 자동 재요청 없이 버전 충돌로 전달한다', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json({ status: 409, code: 'PROJECT_VERSION_CONFLICT' }, 409)))
    await expect(setProjectArchived(base, true)).rejects.toMatchObject({ code: 'PROJECT_VERSION_CONFLICT', status: 409 })
  })

  it('성공 코드여도 상태·버전 DTO가 맞지 않으면 결과 불명으로 잠근다', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json({ ...base, archived: true, archivedAt: '2026-11-23T12:30:00Z', version: '4' })))
    await expect(setProjectArchived(base, true)).rejects.toBeInstanceOf(ProjectArchiveUncertainError)
  })

  it('잘못된 archive flag는 요청 전에 거부한다', async () => {
    const fetch = vi.fn(); vi.stubGlobal('fetch', fetch)
    await expect(setProjectArchived(base, 'yes' as unknown as boolean)).rejects.toMatchObject({ code: 'INVALID_REQUEST' })
    expect(fetch).not.toHaveBeenCalled()
  })
})
