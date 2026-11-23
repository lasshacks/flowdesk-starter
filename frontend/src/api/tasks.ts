export type TaskStatus = 'TODO' | 'IN_PROGRESS' | 'DONE'
export type TaskPriority = 'LOW' | 'MEDIUM' | 'HIGH'
export interface TaskSummary {
  id: string
  workspaceId: string
  projectId: string
  title: string
  status: TaskStatus
  priority: TaskPriority
  assigneeId: string | null
  dueDate: string | null
  archived: boolean
}
export interface Task extends TaskSummary { description: string | null; version: string; archivedAt: string | null }
export interface TaskPage { items: TaskSummary[]; page: number; size: number; hasNext: boolean }
export interface TaskStatusActivity {
  id: string
  actor: { id: string; displayName: string }
  fromStatus: TaskStatus
  toStatus: TaskStatus
  taskVersion: string
  occurredAt: string
}
export interface TaskStatusHistoryPage {
  items: TaskStatusActivity[]
  beforeVersion: string | null
  size: number
  hasNext: boolean
  nextBeforeVersion: string | null
}
export const TASK_PAGE_SIZE = 20
export const TASK_STATUS_HISTORY_PAGE_SIZE = 20
export class TaskApiError extends Error {
  constructor(public readonly code: string, public readonly status = 0) { super('Task request failed') }
}

function stripSpaces(value: string): string {
  const spaces = '[\\u0009-\\u000d\\u001c-\\u0020\\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000]'
  return value.replace(new RegExp(`^${spaces}+|${spaces}+$`, 'gu'), '')
}

function record(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function validId(value: unknown): value is string {
  return typeof value === 'string' && /^[1-9][0-9]{0,18}$/.test(value)
    && BigInt(value) <= 9223372036854775807n
}

function validAssigneeId(value: unknown): value is string | null {
  return value === null || validId(value)
}

function validDueDate(value: unknown): value is string | null {
  if (value === null) return true
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(value) || value.startsWith('0000-')) return false
  const date = new Date(`${value}T00:00:00.000Z`)
  return Number.isFinite(date.valueOf()) && date.toISOString().slice(0, 10) === value
}

function validTimestamp(value: unknown): value is string | null {
  return value === null || (typeof value === 'string' && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z$/.test(value)
    && Number.isFinite(Date.parse(value)))
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

function summary(value: unknown, workspaceId: string, projectId: string): TaskSummary {
  if (!record(value) || !validId(value.id) || value.workspaceId !== workspaceId || value.projectId !== projectId || !text(value.title, 200)
    || !['TODO', 'IN_PROGRESS', 'DONE'].includes(value.status as string)
    || !['LOW', 'MEDIUM', 'HIGH'].includes(value.priority as string)
    || !validAssigneeId(value.assigneeId) || !validDueDate(value.dueDate)
    || (value.archived !== undefined && typeof value.archived !== 'boolean')) {
    throw new TaskApiError('INVALID_RESPONSE')
  }
  return { id: value.id, workspaceId, projectId, title: value.title as string, status: value.status as TaskStatus,
    priority: value.priority as TaskPriority, assigneeId: value.assigneeId, dueDate: value.dueDate, archived: value.archived === true }
}

function validVersion(value: unknown): value is string {
  return typeof value === 'string' && /^(0|[1-9][0-9]{0,18})$/.test(value)
    && BigInt(value) <= 9223372036854775807n
}

function exactKeys(value: Record<string, unknown>, expected: string[]): boolean {
  return Object.keys(value).sort().join(',') === [...expected].sort().join(',')
}

function statusActivity(value: unknown): TaskStatusActivity {
  if (!record(value) || !exactKeys(value, ['id', 'actor', 'fromStatus', 'toStatus', 'taskVersion', 'occurredAt'])
    || !validId(value.id) || !record(value.actor) || !exactKeys(value.actor, ['id', 'displayName'])
    || !validId(value.actor.id) || !text(value.actor.displayName, 80)
    || !['TODO', 'IN_PROGRESS', 'DONE'].includes(value.fromStatus as string)
    || !['TODO', 'IN_PROGRESS', 'DONE'].includes(value.toStatus as string)
    || value.fromStatus === value.toStatus || !validVersion(value.taskVersion) || value.taskVersion === '0'
    || typeof value.occurredAt !== 'string'
    || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z$/.test(value.occurredAt)
    || !Number.isFinite(Date.parse(value.occurredAt))) {
    throw new TaskApiError('INVALID_RESPONSE')
  }
  return {
    id: value.id,
    actor: { id: value.actor.id, displayName: value.actor.displayName },
    fromStatus: value.fromStatus as TaskStatus,
    toStatus: value.toStatus as TaskStatus,
    taskVersion: value.taskVersion,
    occurredAt: value.occurredAt,
  }
}

function task(value: unknown, workspaceId: string, projectId: string): Task {
  const item = summary(value, workspaceId, projectId)
  if (!record(value) || !validVersion(value.version) || !(value.description === null || text(value.description, 2000, true))
    || (value.archivedAt !== undefined && !validTimestamp(value.archivedAt))
    || ((value.archived === true) !== (value.archivedAt != null))) {
    throw new TaskApiError('INVALID_RESPONSE')
  }
  return { ...item, description: value.description, version: value.version, archivedAt: value.archivedAt === undefined ? null : value.archivedAt as string | null }
}

async function responseError(response: Response): Promise<TaskApiError> {
  let code = 'HTTP_ERROR'
  try {
    const problem: unknown = await response.json()
    if (record(problem) && problem.status === response.status && typeof problem.code === 'string'
      && ['TASK_NOT_FOUND', 'TASK_FORBIDDEN', 'SERVICE_UNAVAILABLE', 'INVALID_REQUEST', 'INTERNAL_ERROR',
        'TASK_VERSION_CONFLICT', 'TASK_ARCHIVED', 'PROJECT_ARCHIVED', 'DEV_ACCESS_DENIED', 'REQUEST_TOO_LARGE', 'UNSUPPORTED_MEDIA_TYPE'].includes(problem.code)) {
      code = problem.code
    }
  } catch { /* Disabled APIs and proxies may return HTML instead of ProblemDetail. */ }
  return new TaskApiError(code, response.status)
}

async function request(path: string, signal?: AbortSignal): Promise<unknown> {
  const timeout = AbortSignal.timeout(5000)
  const response = await fetch(path, {
    headers: { Accept: 'application/json' }, credentials: 'omit', cache: 'no-store', redirect: 'error',
    signal: signal ? AbortSignal.any([signal, timeout]) : timeout,
  })
  if (!response.ok) throw await responseError(response)
  if (response.status !== 200) throw new TaskApiError('INVALID_RESPONSE')
  try { return await response.json() }
  catch { throw new TaskApiError('INVALID_RESPONSE') }
}

export async function getTasks(workspaceId: string, projectId: string, page = 0, signal?: AbortSignal): Promise<TaskPage> {
  if (!validId(workspaceId) || !validId(projectId) || !Number.isInteger(page) || page < 0 || page > 10000) {
    throw new TaskApiError('INVALID_REQUEST')
  }
  const data = await request(`/api/workspaces/${workspaceId}/projects/${projectId}/tasks?page=${page}&size=${TASK_PAGE_SIZE}`, signal)
  if (!record(data) || !Array.isArray(data.items) || data.items.length > TASK_PAGE_SIZE
    || data.page !== page || data.size !== TASK_PAGE_SIZE || typeof data.hasNext !== 'boolean') {
    throw new TaskApiError('INVALID_RESPONSE')
  }
  const items = data.items.map(item => summary(item, workspaceId, projectId))
  if (items.some((item, index) => index > 0 && BigInt(items[index - 1]!.id) >= BigInt(item.id))) {
    throw new TaskApiError('INVALID_RESPONSE')
  }
  return { items, page, size: TASK_PAGE_SIZE, hasNext: data.hasNext }
}

export async function getTask(workspaceId: string, projectId: string, taskId: string, signal?: AbortSignal): Promise<Task> {
  if (!validId(workspaceId) || !validId(projectId) || !validId(taskId)) throw new TaskApiError('INVALID_REQUEST')
  const data = await request(`/api/workspaces/${workspaceId}/projects/${projectId}/tasks/${taskId}`, signal)
  const item = task(data, workspaceId, projectId)
  if (item.id !== taskId) throw new TaskApiError('INVALID_RESPONSE')
  return item
}

export async function getTaskStatusHistory(workspaceId: string, projectId: string, taskId: string,
  beforeVersion: string | null = null, size = TASK_STATUS_HISTORY_PAGE_SIZE, signal?: AbortSignal): Promise<TaskStatusHistoryPage> {
  if (!validId(workspaceId) || !validId(projectId) || !validId(taskId)
    || (beforeVersion !== null && (!validVersion(beforeVersion) || beforeVersion === '0'))
    || !Number.isInteger(size) || size < 1 || size > 100) throw new TaskApiError('INVALID_REQUEST')
  const query = new URLSearchParams({ size: String(size) })
  if (beforeVersion !== null) query.set('beforeVersion', beforeVersion)
  const data = await request(`/api/workspaces/${workspaceId}/projects/${projectId}/tasks/${taskId}/status-activities?${query}`, signal)
  if (!record(data) || !exactKeys(data, ['workspaceId', 'projectId', 'taskId', 'items', 'beforeVersion', 'size', 'hasNext', 'nextBeforeVersion'])
    || data.workspaceId !== workspaceId || data.projectId !== projectId || data.taskId !== taskId
    || data.beforeVersion !== beforeVersion || data.size !== size || typeof data.hasNext !== 'boolean'
    || !Array.isArray(data.items) || data.items.length > size
    || (data.hasNext && (data.items.length !== size || typeof data.nextBeforeVersion !== 'string'))
    || (!data.hasNext && data.nextBeforeVersion !== null)) {
    throw new TaskApiError('INVALID_RESPONSE')
  }
  const items = data.items.map(statusActivity)
  if (items.some((item, index) => {
    const previous = items[index - 1]
    return (beforeVersion !== null && BigInt(item.taskVersion) >= BigInt(beforeVersion))
      || (previous !== undefined && BigInt(previous.taskVersion) <= BigInt(item.taskVersion))
  })) throw new TaskApiError('INVALID_RESPONSE')
  if (data.hasNext && (items.length === 0 || data.nextBeforeVersion !== items[items.length - 1]!.taskVersion)) {
    throw new TaskApiError('INVALID_RESPONSE')
  }
  return {
    items,
    beforeVersion,
    size,
    hasNext: data.hasNext,
    nextBeforeVersion: data.nextBeforeVersion as string | null,
  }
}


export function normalizeTaskTitle(value: string): string {
  const result = stripSpaces(value)
  if (!text(result, 200)) throw new TaskApiError('INVALID_TITLE')
  return result
}

export function normalizeTaskDescription(value: string | null): string | null {
  if (value === null) return null
  const result = stripSpaces(value.replace(/\r\n?/g, '\n'))
  if (result === '') return null
  if (!text(result, 2000, true)) throw new TaskApiError('INVALID_DESCRIPTION')
  return result
}

export function normalizeTaskAssigneeInput(value: string): string | null {
  const result = value.trim()
  if (result === '') return null
  if (!validId(result)) throw new TaskApiError('INVALID_ASSIGNEE_ID')
  return result
}

export function normalizeTaskDueDateInput(value: string): string | null {
  const result = value.trim()
  if (result === '') return null
  if (!validDueDate(result)) throw new TaskApiError('INVALID_DUE_DATE')
  return result
}


export class TaskCreateUncertainError extends Error {
  constructor() { super('Task creation outcome is unknown') }
}

export async function createTask(workspaceId: string, projectId: string, rawTitle: string,
  rawDescription: string | null, priority: TaskPriority = 'MEDIUM', assigneeId: string | null = null,
  dueDate: string | null = null, signal?: AbortSignal): Promise<Task> {
  if (!validId(workspaceId) || !validId(projectId) || !['LOW', 'MEDIUM', 'HIGH'].includes(priority)
    || !validAssigneeId(assigneeId) || !validDueDate(dueDate)) {
    throw new TaskApiError('INVALID_REQUEST')
  }
  const title = normalizeTaskTitle(rawTitle)
  const description = normalizeTaskDescription(rawDescription)
  const rejected = [400, 403, 404, 405, 413, 415]
  const timeout = AbortSignal.timeout(10000)
  try {
    const response = await fetch(`/api/workspaces/${workspaceId}/projects/${projectId}/tasks`, {
      method: 'POST', headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
      body: JSON.stringify({ title, description, priority, assigneeId, dueDate }), credentials: 'omit', cache: 'no-store', redirect: 'error',
      signal: signal ? AbortSignal.any([signal, timeout]) : timeout,
    })
    if (rejected.includes(response.status)) throw await responseError(response)
    if (response.status !== 201) throw new TaskCreateUncertainError()
    const result = task(await response.json(), workspaceId, projectId)
    if (result.version !== '0' || result.title !== title || result.description !== description || result.status !== 'TODO' || result.priority !== priority
      || result.assigneeId !== assigneeId || result.dueDate !== dueDate
      || response.headers.get('Location') !== `/api/workspaces/${workspaceId}/projects/${projectId}/tasks/${result.id}`) {
      throw new TaskCreateUncertainError()
    }
    return result
  } catch (cause) {
    if (cause instanceof TaskApiError && rejected.includes(cause.status)) throw cause
    throw new TaskCreateUncertainError()
  }
}

export class TaskUpdateUncertainError extends Error {
  constructor() { super('Task update outcome is unknown') }
}

export async function updateTask(base: Task, rawTitle: string, rawDescription: string | null,
  priority: TaskPriority, assigneeId: string | null = base.assigneeId, dueDate: string | null = base.dueDate,
  signal?: AbortSignal): Promise<Task> {
  if (!validId(base.workspaceId) || !validId(base.projectId) || !validId(base.id) || !validVersion(base.version)
    || !['LOW', 'MEDIUM', 'HIGH'].includes(priority) || !validAssigneeId(assigneeId) || !validDueDate(dueDate)) throw new TaskApiError('INVALID_REQUEST')
  const title = normalizeTaskTitle(rawTitle)
  const description = normalizeTaskDescription(rawDescription)
  const rejected = [400, 403, 404, 405, 409, 413, 415]
  const timeout = AbortSignal.timeout(10000)
  try {
    const response = await fetch(`/api/workspaces/${base.workspaceId}/projects/${base.projectId}/tasks/${base.id}`, {
      method: 'PATCH', headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
      body: JSON.stringify({ title, description, priority, assigneeId, dueDate, version: base.version }),
      credentials: 'omit', cache: 'no-store', redirect: 'error',
      signal: signal ? AbortSignal.any([signal, timeout]) : timeout,
    })
    if (rejected.includes(response.status)) throw await responseError(response)
    if (response.status !== 200) throw new TaskUpdateUncertainError()
    const result = task(await response.json(), base.workspaceId, base.projectId)
    const changed = title !== base.title || description !== base.description || priority !== base.priority
      || assigneeId !== base.assigneeId || dueDate !== base.dueDate
    const expected = changed ? (BigInt(base.version) + 1n).toString() : base.version
    if (result.id !== base.id || result.title !== title || result.description !== description
      || result.priority !== priority || result.status !== base.status || result.assigneeId !== assigneeId
      || result.dueDate !== dueDate || result.version !== expected) throw new TaskUpdateUncertainError()
    return result
  } catch (cause) {
    if (cause instanceof TaskApiError && rejected.includes(cause.status)) throw cause
    throw new TaskUpdateUncertainError()
  }
}

export class TaskStatusUncertainError extends Error {
  constructor() { super('Task status change outcome is unknown') }
}

export async function changeTaskStatus(base: Task, status: TaskStatus, signal?: AbortSignal): Promise<Task> {
  const statuses = ['TODO', 'IN_PROGRESS', 'DONE']
  if (!validId(base.workspaceId) || !validId(base.projectId) || !validId(base.id) || !validVersion(base.version)
    || !statuses.includes(base.status) || !statuses.includes(status)) throw new TaskApiError('INVALID_REQUEST')
  const rejected = [400, 403, 404, 405, 409, 413, 415]
  const timeout = AbortSignal.timeout(10000)
  try {
    const response = await fetch(`/api/workspaces/${base.workspaceId}/projects/${base.projectId}/tasks/${base.id}/status`, {
      method: 'PATCH', headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
      body: JSON.stringify({ status, version: base.version }),
      credentials: 'omit', cache: 'no-store', redirect: 'error',
      signal: signal ? AbortSignal.any([signal, timeout]) : timeout,
    })
    if (rejected.includes(response.status)) throw await responseError(response)
    if (response.status !== 200) throw new TaskStatusUncertainError()
    const result = task(await response.json(), base.workspaceId, base.projectId)
    const expected = status === base.status ? base.version : (BigInt(base.version) + 1n).toString()
    if (result.id !== base.id || result.status !== status || result.version !== expected
      || result.title !== base.title || result.description !== base.description || result.priority !== base.priority
      || result.assigneeId !== base.assigneeId || result.dueDate !== base.dueDate) {
      throw new TaskStatusUncertainError()
    }
    return result
  } catch (cause) {
    if (cause instanceof TaskApiError && rejected.includes(cause.status)) throw cause
    throw new TaskStatusUncertainError()
  }
}
