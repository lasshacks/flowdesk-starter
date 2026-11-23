export interface ProjectSummary {
  id: string
  workspaceId: string
  name: string
  archived: boolean
}

export interface Project extends ProjectSummary {
  version: string
  description: string | null
  archivedAt: string | null
}

export interface ProjectPage {
  items: ProjectSummary[]
  page: number
  size: number
  hasNext: boolean
}

export const PROJECT_PAGE_SIZE = 20

export class ProjectApiError extends Error {
  constructor(public readonly code: string, public readonly status = 0) {
    super('Project request failed')
  }
}

export class ProjectCreateUncertainError extends Error {
  constructor() { super('Project creation outcome is unknown') }
}

export class ProjectUpdateUncertainError extends Error {
  constructor() { super('Project update outcome is unknown') }
}

export class ProjectArchiveUncertainError extends Error {
  constructor() { super('Project archive outcome is unknown') }
}

function stripSpaces(value: string): string {
  const spaces = '[\\u0009-\\u000d\\u001c-\\u0020\\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000]'
  return value.replace(new RegExp(`^${spaces}+|${spaces}+$`, 'gu'), '')
}

export function normalizeProjectName(value: string): string {
  const result = stripSpaces(value)
  if (!text(result, 100)) throw new ProjectApiError('INVALID_NAME')
  return result
}

export function normalizeProjectDescription(value: string | null): string | null {
  if (value === null) return null
  const result = stripSpaces(value.replace(/\r\n?/g, '\n'))
  if (result === '') return null
  if (!text(result, 2000, true)) throw new ProjectApiError('INVALID_DESCRIPTION')
  return result
}

function record(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function validId(value: unknown): value is string {
  return typeof value === 'string' && /^[1-9][0-9]{0,18}$/.test(value)
    && BigInt(value) <= 9223372036854775807n
}

function text(value: unknown, max: number, multiline = false): value is string {
  if (typeof value !== 'string') return false
  const points = Array.from(value)
  // Match the server's Character.isWhitespace/isSpaceChar, which differs from JS trim (e.g. FEFF).
  const nonblank = stripSpaces(value)
  return points.length > 0 && points.length <= max && nonblank.length > 0
    && !points.some(point => {
      const cp = point.codePointAt(0)!
      return (cp >= 0xd800 && cp <= 0xdfff)
        || ((cp < 32 || (cp >= 127 && cp <= 159)) && !(multiline && (cp === 9 || cp === 10)))
    })
}

function summary(value: unknown, workspaceId: string): ProjectSummary {
  if (!record(value) || !validId(value.id) || value.workspaceId !== workspaceId || !text(value.name, 100)
    || typeof value.archived !== 'boolean') {
    throw new ProjectApiError('INVALID_RESPONSE')
  }
  return { id: value.id, workspaceId, name: value.name, archived: value.archived }
}

function validVersion(value: unknown): value is string {
  return typeof value === 'string' && /^(0|[1-9][0-9]{0,18})$/.test(value)
    && BigInt(value) <= 9223372036854775807n
}

function project(value: unknown, workspaceId: string): Project {
  const item = summary(value, workspaceId)
  if (!record(value) || !validVersion(value.version) || !(value.description === null || text(value.description, 2000, true))
    || !(value.archivedAt === null || (typeof value.archivedAt === 'string' && /^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d{1,9})?Z$/.test(value.archivedAt)))
    || value.archived !== (value.archivedAt !== null)) {
    throw new ProjectApiError('INVALID_RESPONSE')
  }
  return { ...item, description: value.description, archivedAt: value.archivedAt, version: value.version }
}

async function responseError(response: Response): Promise<ProjectApiError> {
  let code = 'HTTP_ERROR'
  try {
    const problem: unknown = await response.json()
    if (record(problem) && problem.status === response.status && typeof problem.code === 'string'
      && ['PROJECT_NOT_FOUND', 'PROJECT_FORBIDDEN', 'SERVICE_UNAVAILABLE', 'INVALID_REQUEST', 'INTERNAL_ERROR',
        'PROJECT_VERSION_CONFLICT', 'PROJECT_ARCHIVED', 'DEV_ACCESS_DENIED', 'REQUEST_TOO_LARGE', 'UNSUPPORTED_MEDIA_TYPE'].includes(problem.code)) {
      code = problem.code
    }
  } catch { /* Disabled APIs and proxies may return HTML instead of ProblemDetail. */ }
  return new ProjectApiError(code, response.status)
}

async function request(path: string, signal?: AbortSignal): Promise<unknown> {
  const timeout = AbortSignal.timeout(5000)
  const response = await fetch(path, {
    headers: { Accept: 'application/json' }, credentials: 'omit', cache: 'no-store', redirect: 'error',
    signal: signal ? AbortSignal.any([signal, timeout]) : timeout,
  })
  if (!response.ok) throw await responseError(response)
  if (response.status !== 200) throw new ProjectApiError('INVALID_RESPONSE')
  try { return await response.json() }
  catch { throw new ProjectApiError('INVALID_RESPONSE') }
}

export async function getProjects(workspaceId: string, page = 0, signal?: AbortSignal, includeArchived = false): Promise<ProjectPage> {
  if (!validId(workspaceId) || !Number.isInteger(page) || page < 0 || page > 10000) {
    throw new ProjectApiError('INVALID_REQUEST')
  }
  const archiveQuery = includeArchived ? '&includeArchived=true' : ''
  const data = await request(`/api/workspaces/${workspaceId}/projects?page=${page}&size=${PROJECT_PAGE_SIZE}${archiveQuery}`, signal)
  if (!record(data) || !Array.isArray(data.items) || data.items.length > PROJECT_PAGE_SIZE
    || data.page !== page || data.size !== PROJECT_PAGE_SIZE || typeof data.hasNext !== 'boolean') {
    throw new ProjectApiError('INVALID_RESPONSE')
  }
  const items = data.items.map(item => summary(item, workspaceId))
  if (items.some((item, index) => index > 0 && BigInt(items[index - 1]!.id) >= BigInt(item.id))) {
    throw new ProjectApiError('INVALID_RESPONSE')
  }
  return { items, page, size: PROJECT_PAGE_SIZE, hasNext: data.hasNext }
}

export async function setProjectArchived(base: Project, archived: boolean, signal?: AbortSignal): Promise<Project> {
  if (!validId(base.workspaceId) || !validId(base.id) || !validVersion(base.version)
    || typeof archived !== 'boolean') throw new ProjectApiError('INVALID_REQUEST')
  const rejected = [400, 403, 404, 405, 409, 413, 415]
  const timeout = AbortSignal.timeout(10000)
  try {
    const response = await fetch(`/api/workspaces/${base.workspaceId}/projects/${base.id}/archive`, {
      method: 'PATCH', headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
      body: JSON.stringify({ archived, version: base.version }),
      credentials: 'omit', cache: 'no-store', redirect: 'error',
      signal: signal ? AbortSignal.any([signal, timeout]) : timeout,
    })
    if (rejected.includes(response.status)) throw await responseError(response)
    if (response.status !== 200) throw new ProjectArchiveUncertainError()
    const result = project(await response.json(), base.workspaceId)
    const changed = archived !== base.archived
    const expectedVersion = changed ? (BigInt(base.version) + 1n).toString() : base.version
    if (result.id !== base.id || result.archived !== archived || result.version !== expectedVersion) {
      throw new ProjectArchiveUncertainError()
    }
    return result
  } catch (cause) {
    if (cause instanceof ProjectApiError && rejected.includes(cause.status)) throw cause
    throw new ProjectArchiveUncertainError()
  }
}

export async function getProject(workspaceId: string, projectId: string, signal?: AbortSignal): Promise<Project> {
  if (!validId(workspaceId) || !validId(projectId)) throw new ProjectApiError('INVALID_REQUEST')
  const data = await request(`/api/workspaces/${workspaceId}/projects/${projectId}`, signal)
  const item = project(data, workspaceId)
  if (item.id !== projectId) throw new ProjectApiError('INVALID_RESPONSE')
  return item
}

export async function createProject(workspaceId: string, rawName: string, rawDescription: string | null,
  signal?: AbortSignal): Promise<Project> {
  if (!validId(workspaceId)) throw new ProjectApiError('INVALID_REQUEST')
  const name = normalizeProjectName(rawName)
  const description = normalizeProjectDescription(rawDescription)
  const rejected = [400, 403, 404, 405, 413, 415]
  const timeout = AbortSignal.timeout(10000)
  try {
    const response = await fetch(`/api/workspaces/${workspaceId}/projects`, {
      method: 'POST', headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
      body: JSON.stringify({ name, description }), credentials: 'omit', cache: 'no-store', redirect: 'error',
      signal: signal ? AbortSignal.any([signal, timeout]) : timeout,
    })
    if (rejected.includes(response.status)) throw await responseError(response)
    if (response.status !== 201) throw new ProjectCreateUncertainError()
    const result = project(await response.json(), workspaceId)
    if (result.version !== '0' || result.name !== name || result.description !== description
      || response.headers.get('Location') !== `/api/workspaces/${workspaceId}/projects/${result.id}`) {
      throw new ProjectCreateUncertainError()
    }
    return result
  } catch (cause) {
    if (cause instanceof ProjectApiError && rejected.includes(cause.status)) throw cause
    throw new ProjectCreateUncertainError()
  }
}

export async function updateProject(base: Project, rawName: string, rawDescription: string | null,
  signal?: AbortSignal): Promise<Project> {
  if (!validId(base.workspaceId) || !validId(base.id) || !validVersion(base.version)) {
    throw new ProjectApiError('INVALID_REQUEST')
  }
  const name = normalizeProjectName(rawName)
  const description = normalizeProjectDescription(rawDescription)
  const rejected = [400, 403, 404, 405, 409, 413, 415]
  const timeout = AbortSignal.timeout(10000)
  try {
    const response = await fetch(`/api/workspaces/${base.workspaceId}/projects/${base.id}`, {
      method: 'PATCH', headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
      body: JSON.stringify({ name, description, version: base.version }),
      credentials: 'omit', cache: 'no-store', redirect: 'error',
      signal: signal ? AbortSignal.any([signal, timeout]) : timeout,
    })
    if (rejected.includes(response.status)) throw await responseError(response)
    if (response.status !== 200) throw new ProjectUpdateUncertainError()
    const result = project(await response.json(), base.workspaceId)
    const changed = name !== base.name || description !== base.description
    const expected = changed ? (BigInt(base.version) + 1n).toString() : base.version
    if (result.id !== base.id || result.name !== name || result.description !== description || result.version !== expected) {
      throw new ProjectUpdateUncertainError()
    }
    return result
  } catch (cause) {
    if (cause instanceof ProjectApiError && rejected.includes(cause.status)) throw cause
    throw new ProjectUpdateUncertainError()
  }
}
