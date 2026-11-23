export type WorkspaceRole = 'OWNER' | 'ADMIN' | 'MEMBER' | 'VIEWER'

export interface Workspace {
  id: string
  name: string
  myRole: WorkspaceRole
  version: string
}

export interface WorkspacePage {
  items: Workspace[]
  page: number
  size: number
  hasNext: boolean
}

export interface WorkspaceMember {
  id: string
  displayName: string
  role: WorkspaceRole
}

export interface WorkspaceMemberPage {
  items: WorkspaceMember[]
  page: number
  size: number
  hasNext: boolean
}

export const PAGE_SIZE = 20
export const MEMBER_PAGE_SIZE = 100

export class WorkspaceApiError extends Error {
  constructor(public readonly code: string, public readonly status = 0) {
    super('Workspace request failed')
  }
}

/** The server may have committed even though the client did not receive a valid confirmation. */
export class WorkspaceCreateUncertainError extends Error {
  constructor() { super('Workspace creation outcome is unknown') }
}

export class WorkspaceRenameUncertainError extends Error {
  constructor() { super('Workspace rename outcome is unknown') }
}

export function normalizeWorkspaceName(raw: string): string {
  // Match Character.isWhitespace/isSpaceChar rather than UTF-16 input maxlength.
  const spaces = '[\\u0009-\\u000d\\u001c-\\u0020\\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000]'
  const name = raw.replace(new RegExp(`^${spaces}+|${spaces}+$`, 'gu'), '')
  const points = Array.from(name)
  if (points.length < 1 || points.length > 100 || /[\u0000-\u001f\u007f-\u009f]/u.test(name)
    || points.some(point => { const cp = point.codePointAt(0)!; return cp >= 0xd800 && cp <= 0xdfff })) {
    throw new WorkspaceApiError('INVALID_NAME')
  }
  return name
}

function record(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function validId(value: unknown): value is string {
  return typeof value === 'string' && /^[1-9][0-9]{0,18}$/.test(value)
    && BigInt(value) <= 9223372036854775807n
}

function validVersion(value: unknown): value is string {
  return typeof value === 'string' && /^(0|[1-9][0-9]{0,18})$/.test(value)
    && BigInt(value) <= 9223372036854775807n
}

function workspace(value: unknown): Workspace {
  if (!record(value) || !validId(value.id) || typeof value.name !== 'string'
    || value.name.trim().length === 0 || typeof value.myRole !== 'string'
    || !['OWNER', 'ADMIN', 'MEMBER', 'VIEWER'].includes(value.myRole) || !validVersion(value.version)) {
    throw new WorkspaceApiError('INVALID_RESPONSE')
  }
  return { id: value.id, name: value.name, myRole: value.myRole as WorkspaceRole, version: value.version }
}

function workspaceMember(value: unknown): WorkspaceMember {
  if (!record(value) || Object.keys(value).length !== 3 || !validId(value.id)
    || typeof value.displayName !== 'string' || value.displayName.trim().length === 0
    || Array.from(value.displayName).length > 80 || typeof value.role !== 'string'
    || !['OWNER', 'ADMIN', 'MEMBER', 'VIEWER'].includes(value.role)) {
    throw new WorkspaceApiError('INVALID_RESPONSE')
  }
  return { id: value.id, displayName: value.displayName, role: value.role as WorkspaceRole }
}

async function request(path: string, signal?: AbortSignal): Promise<unknown> {
  const timeout = AbortSignal.timeout(5000)
  const response = await fetch(path, {
    headers: { Accept: 'application/json' },
    credentials: 'omit',
    cache: 'no-store',
    signal: signal ? AbortSignal.any([signal, timeout]) : timeout,
  })
  if (!response.ok) throw await responseError(response)
  try { return await response.json() }
  catch { throw new WorkspaceApiError('INVALID_RESPONSE') }
}

async function responseError(response: Response): Promise<WorkspaceApiError> {
  let code = 'HTTP_ERROR'
  try {
    const problem: unknown = await response.json()
    if (record(problem) && problem.status === response.status && typeof problem.code === 'string'
      && ['WORKSPACE_NOT_FOUND', 'SERVICE_UNAVAILABLE', 'INVALID_REQUEST', 'INTERNAL_ERROR',
        'DEV_ACCESS_DENIED', 'REQUEST_TOO_LARGE', 'UNSUPPORTED_MEDIA_TYPE', 'WORKSPACE_FORBIDDEN', 'WORKSPACE_VERSION_CONFLICT'].includes(problem.code)) {
      code = problem.code
    }
  } catch { /* A proxy or disabled endpoint can return a non-ProblemDetail body. */ }
  return new WorkspaceApiError(code, response.status)
}

export async function createWorkspace(rawName: string, signal?: AbortSignal): Promise<Workspace> {
  const name = normalizeWorkspaceName(rawName)
  const rejectedStatuses = [400, 403, 404, 405, 413, 415]
  const timeout = AbortSignal.timeout(10000)
  try {
    const response = await fetch('/api/workspaces', {
      method: 'POST',
      headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
      body: JSON.stringify({ name }),
      credentials: 'omit', cache: 'no-store', redirect: 'error',
      signal: signal ? AbortSignal.any([signal, timeout]) : timeout,
    })
    if (rejectedStatuses.includes(response.status)) throw await responseError(response)
    if (response.status !== 201) throw new WorkspaceCreateUncertainError()
    const result = workspace(await response.json())
    if (result.myRole !== 'OWNER' || result.version !== '0' || result.name !== name
      || response.headers.get('Location') !== `/api/workspaces/${result.id}`) {
      throw new WorkspaceCreateUncertainError()
    }
    return result
  } catch (cause) {
    if (cause instanceof WorkspaceApiError && rejectedStatuses.includes(cause.status)) throw cause
    throw new WorkspaceCreateUncertainError()
  }
}

export async function renameWorkspace(base: Workspace, rawName: string, signal?: AbortSignal): Promise<Workspace> {
  const name = normalizeWorkspaceName(rawName)
  if (!validId(base.id) || !validVersion(base.version)) throw new WorkspaceApiError('INVALID_REQUEST')
  const rejectedStatuses = [400, 403, 404, 405, 409, 413, 415]
  const timeout = AbortSignal.timeout(10000)
  try {
    const response = await fetch(`/api/workspaces/${base.id}`, {
      method: 'PATCH',
      headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
      body: JSON.stringify({ name, version: base.version }),
      credentials: 'omit', cache: 'no-store', redirect: 'error',
      signal: signal ? AbortSignal.any([signal, timeout]) : timeout,
    })
    if (rejectedStatuses.includes(response.status)) throw await responseError(response)
    if (response.status !== 200) throw new WorkspaceRenameUncertainError()
    const result = workspace(await response.json())
    const expectedVersion = name === base.name ? base.version : (BigInt(base.version) + 1n).toString()
    if (result.id !== base.id || result.name !== name || result.version !== expectedVersion
      || !['OWNER', 'ADMIN'].includes(result.myRole)) throw new WorkspaceRenameUncertainError()
    return result
  } catch (cause) {
    if (cause instanceof WorkspaceApiError && rejectedStatuses.includes(cause.status)) throw cause
    throw new WorkspaceRenameUncertainError()
  }
}

export async function getWorkspaces(page = 0, signal?: AbortSignal): Promise<WorkspacePage> {
  if (!Number.isInteger(page) || page < 0 || page > 10000) throw new WorkspaceApiError('INVALID_REQUEST')
  const data = await request(`/api/workspaces?page=${page}&size=${PAGE_SIZE}`, signal)
  if (!record(data) || !Array.isArray(data.items) || data.items.length > PAGE_SIZE
    || data.page !== page || data.size !== PAGE_SIZE || typeof data.hasNext !== 'boolean') {
    throw new WorkspaceApiError('INVALID_RESPONSE')
  }
  const items = data.items.map(workspace)
  if (new Set(items.map(item => item.id)).size !== items.length) throw new WorkspaceApiError('INVALID_RESPONSE')
  return { items, page, size: PAGE_SIZE, hasNext: data.hasNext }
}

export async function getWorkspace(id: string, signal?: AbortSignal): Promise<Workspace> {
  if (!validId(id)) throw new WorkspaceApiError('INVALID_REQUEST')
  const item = workspace(await request(`/api/workspaces/${encodeURIComponent(id)}`, signal))
  if (item.id !== id) throw new WorkspaceApiError('INVALID_RESPONSE')
  return item
}

export async function getWorkspaceMembers(id: string, page = 0, signal?: AbortSignal): Promise<WorkspaceMemberPage> {
  if (!validId(id) || !Number.isInteger(page) || page < 0 || page > 10000) {
    throw new WorkspaceApiError('INVALID_REQUEST')
  }
  const data = await request(`/api/workspaces/${encodeURIComponent(id)}/members?page=${page}&size=${MEMBER_PAGE_SIZE}`, signal)
  if (!record(data) || Object.keys(data).length !== 4 || !Array.isArray(data.items)
    || data.items.length > MEMBER_PAGE_SIZE || data.page !== page || data.size !== MEMBER_PAGE_SIZE
    || typeof data.hasNext !== 'boolean') throw new WorkspaceApiError('INVALID_RESPONSE')
  const items = data.items.map(workspaceMember)
  if (new Set(items.map(item => item.id)).size !== items.length
    || (data.hasNext && items.length !== MEMBER_PAGE_SIZE)) throw new WorkspaceApiError('INVALID_RESPONSE')
  return { items, page, size: MEMBER_PAGE_SIZE, hasNext: data.hasNext }
}
