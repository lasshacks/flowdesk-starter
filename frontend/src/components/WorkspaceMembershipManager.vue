<script setup lang="ts">
import { computed, onUnmounted, reactive, ref } from 'vue'
import {
  cancelWorkspaceInvitation, changeWorkspaceMemberRole, issueWorkspaceInvitation,
  getWorkspaceInvitations, removeWorkspaceMember, transferWorkspaceOwnership, WorkspaceMembershipUncertainError,
  type InvitationRole, type PendingWorkspaceInvitationPage, type WorkspaceInvitation,
} from '../api/workspace-membership'
import { getWorkspaceMembers, WorkspaceApiError, type Workspace, type WorkspaceMember, type WorkspaceMemberPage } from '../api/workspaces'

const props = defineProps<{ workspace: Workspace }>()
const emit = defineEmits<{ actorRoleChanged: [role: Workspace['myRole']] }>()
const page = ref<WorkspaceMemberPage | null>(null)
const invitationPage = ref<PendingWorkspaceInvitationPage | null>(null)
const opened = ref(false)
const requestedPage = ref(0)
const loading = ref(false)
const loadError = ref<string | null>(null)
const invitationLoadError = ref<string | null>(null)
const invitationLoading = ref(false)
const busy = ref(false)
const uncertain = ref(false)
const actionError = ref<string | null>(null)
const success = ref<string | null>(null)
const roleDrafts = reactive<Record<string, InvitationRole>>({})
const email = ref('')
const inviteRole = ref<InvitationRole>('MEMBER')
const invitation = ref<WorkspaceInvitation | null>(null)
const confirmingRemove = ref<WorkspaceMember | null>(null)
const confirmingTransfer = ref<WorkspaceMember | null>(null)
let readRequest: AbortController | undefined
let invitationReadRequest: AbortController | undefined
let writeRequest: AbortController | undefined

const canManage = computed(() => ['OWNER', 'ADMIN'].includes(props.workspace.myRole))
const canTransfer = computed(() => props.workspace.myRole === 'OWNER')
const roles: Record<InvitationRole | 'OWNER', string> = { OWNER: '소유자', ADMIN: '관리자', MEMBER: '멤버', VIEWER: '뷰어' }
const choices: InvitationRole[] = ['ADMIN', 'MEMBER', 'VIEWER']

function errorMessage(cause: unknown): string {
  if (cause instanceof WorkspaceMembershipUncertainError) return '요청 결과가 불명확합니다. 자동 재전송하지 않았습니다. 멤버 목록을 새로 확인한 뒤 조치를 결정해 주세요.'
  if (cause instanceof WorkspaceApiError && cause.status === 403) return '이 작업을 수행할 권한이 없거나 현재 Origin이 허용되지 않습니다.'
  if (cause instanceof WorkspaceApiError && cause.status === 404) return '워크스페이스 또는 대상 멤버/초대를 찾을 수 없거나 접근할 수 없습니다.'
  if (cause instanceof WorkspaceApiError && cause.status === 409) return '멤버십 상태가 바뀌었습니다. 최신 멤버 목록을 확인해 주세요.'
  if (cause instanceof WorkspaceApiError && cause.status === 400) return '입력값을 확인해 주세요. 초대 대상은 이미 존재하는 계정이어야 합니다.'
  if (cause instanceof WorkspaceApiError && cause.status === 405) return '현재 개발 실행에서는 변경 요청이 비활성화돼 있습니다.'
  return '요청을 처리하지 못했습니다. 입력과 서버 상태를 확인해 주세요.'
}

async function loadMembers(number = requestedPage.value) {
  readRequest?.abort()
  const controller = new AbortController(); readRequest = controller
  requestedPage.value = number; loading.value = true; loadError.value = null
  try {
    const result = await getWorkspaceMembers(props.workspace.id, number, controller.signal)
    if (controller.signal.aborted) return
    page.value = result
    for (const item of result.items) if (!roleDrafts[item.id] || roleDrafts[item.id] === item.role) roleDrafts[item.id] = item.role as InvitationRole
  } catch (cause) {
    if (!controller.signal.aborted) { page.value = null; loadError.value = errorMessage(cause) }
  } finally { if (!controller.signal.aborted) loading.value = false }
}

async function loadInvitations(number = invitationPage.value?.page ?? 0) {
  if (!canManage.value) return
  invitationReadRequest?.abort()
  const controller = new AbortController(); invitationReadRequest = controller
  invitationLoading.value = true; invitationLoadError.value = null
  try {
    const result = await getWorkspaceInvitations(props.workspace.id, number, controller.signal)
    if (!controller.signal.aborted) invitationPage.value = result
  } catch (cause) {
    if (!controller.signal.aborted) { invitationPage.value = null; invitationLoadError.value = errorMessage(cause) }
  } finally { if (!controller.signal.aborted) invitationLoading.value = false }
}

function beginWrite() {
  if (busy.value || uncertain.value) return null
  busy.value = true; actionError.value = null; success.value = null
  const controller = new AbortController(); writeRequest = controller
  return controller
}

function failWrite(cause: unknown) {
  if (cause instanceof WorkspaceMembershipUncertainError) uncertain.value = true
  actionError.value = errorMessage(cause)
}

async function issue() {
  const controller = beginWrite(); if (!controller) return
  try {
    invitation.value = await issueWorkspaceInvitation(props.workspace.id, email.value, inviteRole.value, controller.signal)
    email.value = ''; success.value = '초대를 발급했습니다. 코드는 이 화면에서만 확인할 수 있습니다.'
    void loadInvitations(0)
  } catch (cause) { if (!controller.signal.aborted) failWrite(cause) }
  finally { if (!controller.signal.aborted) busy.value = false }
}

async function cancelInvitation() {
  if (!invitation.value) return
  const current = invitation.value; const controller = beginWrite(); if (!controller) return
  try {
    await cancelWorkspaceInvitation(props.workspace.id, current.id, controller.signal)
    invitation.value = null; success.value = '초대를 취소했습니다. 해당 코드는 더 이상 사용할 수 없습니다.'
    void loadInvitations()
  } catch (cause) { if (!controller.signal.aborted) failWrite(cause) }
  finally { if (!controller.signal.aborted) busy.value = false }
}

async function cancelPendingInvitation(item: PendingWorkspaceInvitationPage['items'][number]) {
  const controller = beginWrite(); if (!controller) return
  try {
    await cancelWorkspaceInvitation(props.workspace.id, item.id, controller.signal)
    if (invitation.value?.id === item.id) invitation.value = null
    success.value = `${item.targetDisplayName}님에게 발급한 초대를 취소했습니다.`
    await loadInvitations(invitationPage.value?.page ?? 0)
  } catch (cause) { if (!controller.signal.aborted) failWrite(cause) }
  finally { if (!controller.signal.aborted) busy.value = false }
}

async function changeRole(item: WorkspaceMember) {
  const role = roleDrafts[item.id]
  if (!role || role === item.role) return
  const controller = beginWrite(); if (!controller) return
  try {
    const updated = await changeWorkspaceMemberRole(props.workspace.id, item.id, role, controller.signal)
    if (!controller.signal.aborted && page.value) {
      page.value = { ...page.value, items: page.value.items.map(value => value.id === updated.id ? updated : value) }
      roleDrafts[item.id] = updated.role as InvitationRole; success.value = `${item.displayName}님의 역할을 변경했습니다.`
    }
  } catch (cause) { if (!controller.signal.aborted) failWrite(cause) }
  finally { if (!controller.signal.aborted) busy.value = false }
}

async function removeMember() {
  const item = confirmingRemove.value; if (!item) return
  const controller = beginWrite(); if (!controller) return
  try {
    await removeWorkspaceMember(props.workspace.id, item.id, controller.signal)
    confirmingRemove.value = null; success.value = `${item.displayName}님의 멤버십을 제거했습니다.`
    await loadMembers(requestedPage.value)
  } catch (cause) { if (!controller.signal.aborted) failWrite(cause) }
  finally { if (!controller.signal.aborted) busy.value = false }
}

async function transferOwnership() {
  const target = confirmingTransfer.value; if (!target || !canTransfer.value) return
  const controller = beginWrite(); if (!controller) return
  try {
    await transferWorkspaceOwnership(props.workspace.id, target.id, controller.signal)
    confirmingTransfer.value = null
    emit('actorRoleChanged', 'ADMIN')
    success.value = `소유권을 ${target.displayName}님에게 이전했습니다. 내 역할은 관리자로 변경됐습니다.`
    await loadMembers(requestedPage.value)
  } catch (cause) { if (!controller.signal.aborted) failWrite(cause) }
  finally { if (!controller.signal.aborted) busy.value = false }
}

onUnmounted(() => { readRequest?.abort(); invitationReadRequest?.abort(); writeRequest?.abort() })

function openManager() {
  opened.value = true
  if (!page.value && !loading.value) void loadMembers(0)
  if (canManage.value && !invitationPage.value && !invitationLoading.value) void loadInvitations(0)
}
</script>

<template>
  <section class="workspace-membership" aria-labelledby="workspace-membership-title" :aria-busy="busy || loading">
    <div class="membership-heading">
      <div><p class="eyebrow">함께 일하는 사람</p><h5 id="workspace-membership-title">멤버 관리</h5></div>
      <div v-if="opened" class="membership-refresh-group">
        <button type="button" class="secondary-button membership-refresh" :disabled="loading || busy" @click="loadMembers()">멤버 새로고침</button>
        <button v-if="canManage" type="button" class="secondary-button membership-refresh" :disabled="invitationLoading || busy" @click="loadInvitations()">초대 새로고침</button>
      </div>
      <button v-else type="button" class="secondary-button membership-refresh" @click="openManager">멤버 목록 열기</button>
    </div>
    <template v-if="opened">
    <p v-if="canManage" class="workspace-hint">초대 발급·취소, 일반 멤버의 역할 변경·제거는 서버에서 다시 권한을 확인합니다.</p>
    <p v-else class="workspace-hint">멤버 목록을 확인할 수 있습니다. 멤버 관리는 소유자와 관리자만 할 수 있습니다.</p>

    <p v-if="loading" role="status">멤버를 불러오고 있어요.</p>
    <div v-else-if="loadError" role="alert"><p class="form-error">{{ loadError }}</p><button type="button" @click="loadMembers()">다시 시도</button></div>
    <template v-else-if="page">
      <ul v-if="page.items.length" class="membership-list">
        <li v-for="item in page.items" :key="item.id" class="membership-item">
          <div class="membership-person"><strong>{{ item.displayName }}</strong><span class="role-badge">{{ roles[item.role] }}</span></div>
          <div v-if="canManage && item.role !== 'OWNER'" class="membership-controls">
            <label :for="`member-role-${item.id}`">역할</label>
            <select :id="`member-role-${item.id}`" v-model="roleDrafts[item.id]" :disabled="busy || uncertain">
              <option v-for="role in choices" :key="role" :value="role">{{ roles[role] }}</option>
            </select>
            <button type="button" class="secondary-button" :disabled="busy || uncertain || roleDrafts[item.id] === item.role" @click="changeRole(item)">역할 저장</button>
            <button type="button" class="danger-button" :disabled="busy || uncertain" @click="confirmingRemove = item">제거</button>
            <button v-if="canTransfer" type="button" class="secondary-button" :disabled="busy || uncertain" @click="confirmingTransfer = item">소유권 이전</button>
          </div>
        </li>
      </ul>
      <p v-else role="status">이 페이지에는 멤버가 없습니다.</p>
      <nav class="workspace-pagination" aria-label="멤버 페이지 이동">
        <button type="button" :disabled="page.page === 0 || loading || busy" @click="loadMembers(page.page - 1)">이전 페이지</button>
        <span aria-live="polite">{{ page.page + 1 }}페이지</span>
        <button type="button" :disabled="!page.hasNext || loading || busy" @click="loadMembers(page.page + 1)">다음 페이지</button>
      </nav>
    </template>

    <section v-if="canManage" class="pending-invitations" aria-labelledby="pending-invitations-title" :aria-busy="invitationLoading">
      <div class="pending-invitations-heading"><h6 id="pending-invitations-title">초대 대기 목록</h6><span>최대 20건씩 표시</span></div>
      <p v-if="invitationLoading" role="status">초대 목록을 불러오고 있어요.</p>
      <div v-else-if="invitationLoadError" role="alert"><p class="form-error">{{ invitationLoadError }}</p><button type="button" @click="loadInvitations(0)">다시 시도</button></div>
      <template v-else-if="invitationPage">
        <ul v-if="invitationPage.items.length" class="invitation-list">
          <li v-for="item in invitationPage.items" :key="item.id" class="invitation-item">
            <div class="invitation-item-details">
              <strong>{{ item.targetDisplayName }}</strong><span>{{ item.targetEmail }}</span>
              <span class="role-badge">{{ roles[item.role] }}</span>
              <span v-if="item.expired" class="invitation-expired">만료됨</span>
              <small>발급 {{ new Date(item.createdAt).toLocaleString() }} · 만료 {{ new Date(item.expiresAt).toLocaleString() }}</small>
            </div>
            <button type="button" class="danger-button" :disabled="busy || uncertain" @click="cancelPendingInvitation(item)">초대 취소</button>
          </li>
        </ul>
        <p v-else role="status">대기 중인 초대가 없습니다.</p>
        <nav class="workspace-pagination" aria-label="초대 페이지 이동">
          <button type="button" :disabled="invitationPage.page === 0 || invitationLoading || busy" @click="loadInvitations(invitationPage.page - 1)">이전 페이지</button>
          <span aria-live="polite">{{ invitationPage.page + 1 }}페이지</span>
          <button type="button" :disabled="!invitationPage.hasNext || invitationLoading || busy" @click="loadInvitations(invitationPage.page + 1)">다음 페이지</button>
        </nav>
      </template>
    </section>

    <form v-if="canManage" class="membership-invite" novalidate @submit.prevent="issue">
      <h6>기존 계정 초대</h6>
      <p class="workspace-hint">이메일 발송은 하지 않습니다. 초대 대상은 이미 존재하는 계정이어야 합니다.</p>
      <label for="workspace-invite-email">계정 이메일</label>
      <input id="workspace-invite-email" v-model="email" type="email" maxlength="254" autocomplete="off" :disabled="busy || uncertain" />
      <label for="workspace-invite-role">초대 역할</label>
      <select id="workspace-invite-role" v-model="inviteRole" :disabled="busy || uncertain">
        <option value="ADMIN">관리자</option><option value="MEMBER">멤버</option><option value="VIEWER">뷰어</option>
      </select>
      <button type="submit" :disabled="busy || uncertain || !email.trim()">{{ busy ? '초대 처리 중…' : '초대 코드 발급' }}</button>
      <div v-if="invitation" class="invitation-code-panel">
        <p role="status">초대 코드 · {{ roles[invitation.role] }} · 만료 {{ new Date(invitation.expiresAt).toLocaleString() }}</p>
        <code class="invitation-code">{{ invitation.code }}</code>
        <p class="workspace-hint">코드를 초대 대상에게 직접 전달하세요. 원문은 다시 조회할 수 없습니다.</p>
        <button type="button" class="danger-button" :disabled="busy || uncertain" @click="cancelInvitation">이 초대 취소</button>
      </div>
    </form>

    <p v-if="actionError" class="form-error" role="alert">{{ actionError }}</p>
    <p v-if="success" class="membership-success" role="status">{{ success }}</p>
    <div v-if="uncertain" class="membership-recovery" role="alert">
      <p>쓰기 결과가 불명확해 추가 변경을 잠갔습니다. 멤버 및 초대 대기 목록을 새로고침해 현재 상태를 확인한 뒤 아래에서 수동으로 잠금을 해제하세요.</p>
      <button type="button" class="secondary-button" :disabled="loading" @click="loadMembers()">멤버 상태 새로고침</button>
      <button v-if="canManage" type="button" class="secondary-button" :disabled="invitationLoading" @click="loadInvitations()">초대 상태 새로고침</button>
      <button type="button" class="secondary-button" :disabled="loading" @click="uncertain = false; actionError = null">상태를 확인했고 쓰기 재개</button>
    </div>

    <div v-if="confirmingRemove" class="membership-confirm" role="alertdialog" aria-modal="false" aria-labelledby="remove-member-title">
      <p id="remove-member-title"><strong>{{ confirmingRemove.displayName }}</strong>님의 멤버십을 제거할까요? 과거 Task 담당자와 이력은 보존됩니다.</p>
      <div><button type="button" class="danger-button" :disabled="busy || uncertain" @click="removeMember">멤버 제거</button>
        <button type="button" class="secondary-button" :disabled="busy" @click="confirmingRemove = null">취소</button></div>
    </div>
    <div v-if="confirmingTransfer" class="membership-confirm" role="alertdialog" aria-modal="false" aria-labelledby="transfer-owner-title">
      <p id="transfer-owner-title"><strong>{{ confirmingTransfer.displayName }}</strong>님에게 소유권을 이전할까요? 기존 소유자인 내 역할은 관리자로 바뀝니다.</p>
      <div><button type="button" :disabled="busy || uncertain" @click="transferOwnership">소유권 이전 확인</button>
        <button type="button" class="secondary-button" :disabled="busy" @click="confirmingTransfer = null">취소</button></div>
    </div>
    </template>
  </section>
</template>
