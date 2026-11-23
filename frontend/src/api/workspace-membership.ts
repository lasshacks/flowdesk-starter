import { WorkspaceApiError, type WorkspaceMember, type WorkspaceRole } from './workspaces'

export type InvitationRole = Exclude<WorkspaceRole, 'OWNER'>

export interface WorkspaceInvitation {
  id: string
  role: InvitationRole
  expiresAt: string
  code: string
}

export interface PendingWorkspaceInvitation {
  id: string
  targetDisplayName: string
  targetEmail: string
  role: InvitationRole
  createdAt: string
  expiresAt: string
  expired: boolean
}

export interface PendingWorkspaceInvitationPage {
  items: PendingWorkspaceInvitation[]
  page: number
  size: number
  hasNext: boolean
}

export const INVITATION_PAGE_SIZE = 20

export interface AcceptedWorkspaceInvitation {
  workspaceId: string
  member: WorkspaceMember
}

export interface WorkspaceOwnership {
  workspaceId: string
  ownerUserId: string
}

/** The write may have committed even when the browser did not receive a valid confirmation. */
export class WorkspaceMembershipUncertainError extends Error {
  constructor(public readonly operation: string) { super('Workspace membership outcome is unknown') }
}

function record(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function validId(value: unknown): value is string {
  return typeof value === 'string' && /^[1-9][0-9]{0,18}$/.test(value)
    && BigInt(value) <= 9223372036854775807n
}

function validRole(value: unknown): value is WorkspaceRole {
  return typeof value === 'string' && ['OWNER', 'ADMIN', 'MEMBER', 'VIEWER'].includes(value)
}

function pendingInvitation(value: unknown): PendingWorkspaceInvitation {
  if (!record(value) || Object.keys(value).length !== 7 || !validId(value.id)
    || typeof value.targetDisplayName !== 'string' || value.targetDisplayName.trim().length === 0
    || Array.from(value.targetDisplayName).length > 80
    || typeof value.targetEmail !== 'string' || value.targetEmail.length > 254
    || !value.targetEmail.includes('@') || /[\s\u0000-\u001f\u007f-\u009f]/u.test(value.targetEmail)
    || !['ADMIN', 'MEMBER', 'VIEWER'].includes(String(value.role))
    || typeof value.createdAt !== 'string' || !Number.isFinite(Date.parse(value.createdAt))
    || typeof value.expiresAt !== 'string' || !Number.isFinite(Date.parse(value.expiresAt))
    || typeof value.expired !== 'boolean') {
    throw new WorkspaceApiError('INVALID_RESPONSE')
  }
  return {
    id: value.id, targetDisplayName: value.targetDisplayName, targetEmail: value.targetEmail,
    role: value.role as InvitationRole, createdAt: value.createdAt, expiresAt: value.expiresAt,
    expired: value.expired,
  }
}

export async function getWorkspaceInvitations(
  workspaceId: string, page = 0, signal?: AbortSignal,
): Promise<PendingWorkspaceInvitationPage> {
  if (!validId(workspaceId) || !Number.isInteger(page) || page < 0 || page > 10000) {
    throw new WorkspaceApiError('INVALID_REQUEST', 400)
  }
  let response: Response
  try {
    const timeout = AbortSignal.timeout(5000)
    response = await fetch(`/api/workspaces/${encodeURIComponent(workspaceId)}/invitations?page=${page}&size=${INVITATION_PAGE_SIZE}`, {
      headers: { Accept: 'application/json' }, credentials: 'omit', cache: 'no-store',
      signal: signal ? AbortSignal.any([signal, timeout]) : timeout,
    })
  } catch {
    throw new WorkspaceApiError('REQUEST_FAILED')
  }
  if (!response.ok) throw new WorkspaceApiError('HTTP_ERROR', response.status)
  let data: unknown
  try { data = await response.json() }
  catch { throw new WorkspaceApiError('INVALID_RESPONSE') }
  if (!record(data) || Object.keys(data).length !== 4 || !Array.isArray(data.items)
    || data.items.length > INVITATION_PAGE_SIZE || data.page !== page || data.size !== INVITATION_PAGE_SIZE
    || typeof data.hasNext !== 'boolean' || (data.hasNext && data.items.length !== INVITATION_PAGE_SIZE)) {
    throw new WorkspaceApiError('INVALID_RESPONSE')
  }
  const items = data.items.map(pendingInvitation)
  if (new Set(items.map(item => item.id)).size !== items.length
    || items.some((item, index) => index > 0 && BigInt(items[index - 1]!.id) <= BigInt(item.id))) {
    throw new WorkspaceApiError('INVALID_RESPONSE')
  }
  return { items, page, size: INVITATION_PAGE_SIZE, hasNext: data.hasNext }
}

function member(value: unknown): WorkspaceMember & { workspaceId: string } {
  if (!record(value) || Object.keys(value).length !== 4 || !validId(value.workspaceId) || !validId(value.id)
    || typeof value.displayName !== 'string' || value.displayName.trim().length === 0
    || Array.from(value.displayName).length > 80 || !validRole(value.role)) {
    throw new WorkspaceMembershipUncertainError('member response')
  }
  return { workspaceId: value.workspaceId, id: value.id, displayName: value.displayName, role: value.role }
}

async function send(path: string, method: string, body: object | undefined, operation: string, signal?: AbortSignal): Promise<Response> {
  let response: Response
  try {
    response = await fetch(path, {
      method,
      headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
      ...(body === undefined ? {} : { body: JSON.stringify(body) }),
      credentials: 'omit', cache: 'no-store', redirect: 'error',
      signal: signal ? AbortSignal.any([signal, AbortSignal.timeout(10000)]) : AbortSignal.timeout(10000),
    })
  } catch {
    throw new WorkspaceMembershipUncertainError(operation)
  }
  if (!response.ok) {
    // A 4xx response confirms rejection; server/proxy failures leave the commit outcome unknown.
    if (response.status >= 400 && response.status < 500) throw new WorkspaceApiError('HTTP_ERROR', response.status)
    throw new WorkspaceMembershipUncertainError(operation)
  }
  return response
}

async function jsonResponse(response: Response, expectedStatus: number, operation: string): Promise<unknown> {
  if (response.status !== expectedStatus) throw new WorkspaceMembershipUncertainError(operation)
  try { return await response.json() }
  catch { throw new WorkspaceMembershipUncertainError(operation) }
}

function normalizedEmail(raw: string): string {
  const email = raw.trim().toLowerCase()
  if (email.length < 3 || email.length > 254 || /[\s\u0000-\u001f\u007f-\u009f]/u.test(email) || !email.includes('@')) {
    throw new WorkspaceApiError('INVALID_REQUEST', 400)
  }
  return email
}

export async function issueWorkspaceInvitation(
  workspaceId: string, rawEmail: string, role: InvitationRole, signal?: AbortSignal,
): Promise<WorkspaceInvitation> {
  if (!validId(workspaceId) || !['ADMIN', 'MEMBER', 'VIEWER'].includes(role)) throw new WorkspaceApiError('INVALID_REQUEST', 400)
  const email = normalizedEmail(rawEmail)
  const response = await send(`/api/workspaces/${workspaceId}/invitations`, 'POST', { email, role }, 'issue invitation', signal)
  const value = await jsonResponse(response, 201, 'issue invitation')
  if (!record(value) || Object.keys(value).length !== 4 || !validId(value.id)
    || value.role !== role || typeof value.expiresAt !== 'string' || !Number.isFinite(Date.parse(value.expiresAt))
    || typeof value.code !== 'string' || !/^[A-Za-z0-9_-]{43}$/.test(value.code)
    || response.headers.get('Location') !== `/api/workspaces/${workspaceId}/invitations/${value.id}`) {
    throw new WorkspaceMembershipUncertainError('issue invitation')
  }
  return { id: value.id, role, expiresAt: value.expiresAt, code: value.code }
}

export async function cancelWorkspaceInvitation(workspaceId: string, invitationId: string, signal?: AbortSignal): Promise<void> {
  if (!validId(workspaceId) || !validId(invitationId)) throw new WorkspaceApiError('INVALID_REQUEST', 400)
  const response = await send(`/api/workspaces/${workspaceId}/invitations/${invitationId}`, 'DELETE', undefined, 'cancel invitation', signal)
  if (response.status !== 204) throw new WorkspaceMembershipUncertainError('cancel invitation')
}

export async function acceptWorkspaceInvitation(rawCode: string, signal?: AbortSignal): Promise<AcceptedWorkspaceInvitation> {
  const code = rawCode.trim()
  if (!/^[A-Za-z0-9_-]{43}$/.test(code)) throw new WorkspaceApiError('INVALID_REQUEST', 400)
  const response = await send('/api/workspace-invitations/accept', 'POST', { code }, 'accept invitation', signal)
  const value = await jsonResponse(response, 201, 'accept invitation')
  const acceptedMember = member(value)
  if (!record(value) || value.role === 'OWNER'
    || response.headers.get('Location') !== `/api/workspaces/${value.workspaceId}/members/${value.id}`) {
    throw new WorkspaceMembershipUncertainError('accept invitation')
  }
  return { workspaceId: acceptedMember.workspaceId, member: acceptedMember }
}

export async function changeWorkspaceMemberRole(
  workspaceId: string, memberId: string, role: InvitationRole, signal?: AbortSignal,
): Promise<WorkspaceMember> {
  if (!validId(workspaceId) || !validId(memberId) || !['ADMIN', 'MEMBER', 'VIEWER'].includes(role)) {
    throw new WorkspaceApiError('INVALID_REQUEST', 400)
  }
  const response = await send(`/api/workspaces/${workspaceId}/members/${memberId}`, 'PATCH', { role }, 'change member role', signal)
  const value = await jsonResponse(response, 200, 'change member role')
  const result = member(value)
  if (result.workspaceId !== workspaceId || result.id !== memberId || result.role !== role) {
    throw new WorkspaceMembershipUncertainError('change member role')
  }
  return result
}

export async function removeWorkspaceMember(workspaceId: string, memberId: string, signal?: AbortSignal): Promise<void> {
  if (!validId(workspaceId) || !validId(memberId)) throw new WorkspaceApiError('INVALID_REQUEST', 400)
  const response = await send(`/api/workspaces/${workspaceId}/members/${memberId}`, 'DELETE', undefined, 'remove member', signal)
  if (response.status !== 204) throw new WorkspaceMembershipUncertainError('remove member')
}

export async function transferWorkspaceOwnership(
  workspaceId: string, targetUserId: string, signal?: AbortSignal,
): Promise<WorkspaceOwnership> {
  if (!validId(workspaceId) || !validId(targetUserId)) throw new WorkspaceApiError('INVALID_REQUEST', 400)
  const response = await send(`/api/workspaces/${workspaceId}/ownership-transfer`, 'POST', { targetUserId }, 'transfer ownership', signal)
  const value = await jsonResponse(response, 200, 'transfer ownership')
  if (!record(value) || Object.keys(value).length !== 2 || value.workspaceId !== workspaceId || value.ownerUserId !== targetUserId) {
    throw new WorkspaceMembershipUncertainError('transfer ownership')
  }
  return { workspaceId, ownerUserId: targetUserId }
}
