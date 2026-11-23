<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { getWorkspace, getWorkspaces, WorkspaceApiError, type Workspace, type WorkspacePage, type WorkspaceRole } from '../api/workspaces'
import WorkspaceCreateForm from './WorkspaceCreateForm.vue'
import WorkspaceRenameForm from './WorkspaceRenameForm.vue'
import WorkspaceMembershipManager from './WorkspaceMembershipManager.vue'
import WorkspaceInvitationAcceptForm from './WorkspaceInvitationAcceptForm.vue'
import ProjectBrowser from './ProjectBrowser.vue'

const page = ref<WorkspacePage | null>(null)
const requestedPage = ref(0)
const listLoading = ref(false)
const listError = ref<string | null>(null)
const selectedId = ref<string | null>(null)
const detail = ref<Workspace | null>(null)
const detailLoading = ref(false)
const detailError = ref<string | null>(null)
let listRequest: AbortController | undefined
let detailRequest: AbortController | undefined
let creationVersion = 0

const roles: Record<WorkspaceRole, string> = { OWNER: '소유자', ADMIN: '관리자', MEMBER: '멤버', VIEWER: '뷰어' }

function message(cause: unknown, isDetail = false): string {
  if (!(cause instanceof WorkspaceApiError)) return '서버에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요.'
  if (isDetail && cause.status === 404) return '워크스페이스를 찾을 수 없거나 접근할 수 없습니다.'
  if (cause.code === 'SERVICE_UNAVAILABLE' || cause.status === 503) return '일시적으로 서비스를 이용할 수 없습니다. 잠시 후 다시 시도해 주세요.'
  if (cause.code === 'INVALID_RESPONSE') return '서버 응답을 확인할 수 없습니다. 다시 시도해 주세요.'
  return '워크스페이스를 불러오지 못했습니다. 다시 시도해 주세요.'
}

function clearSelection() {
  detailRequest?.abort()
  selectedId.value = null
  detail.value = null
  detailError.value = null
  detailLoading.value = false
}

async function loadPage(number = requestedPage.value) {
  listRequest?.abort()
  clearSelection()
  const controller = new AbortController()
  listRequest = controller
  requestedPage.value = number
  page.value = null
  listError.value = null
  listLoading.value = true
  try {
    const result = await getWorkspaces(number, controller.signal)
    if (!controller.signal.aborted) page.value = result
  } catch (cause) {
    if (!controller.signal.aborted) listError.value = message(cause)
  } finally {
    if (!controller.signal.aborted) listLoading.value = false
  }
}

async function select(id: string) {
  detailRequest?.abort()
  const controller = new AbortController()
  detailRequest = controller
  selectedId.value = id
  detail.value = null
  detailError.value = null
  detailLoading.value = true
  try {
    const result = await getWorkspace(id, controller.signal)
    if (!controller.signal.aborted) detail.value = result
  } catch (cause) {
    if (!controller.signal.aborted) detailError.value = message(cause, true)
  } finally {
    if (!controller.signal.aborted) detailLoading.value = false
  }
}

async function created(workspace: Workspace) {
  const version = ++creationVersion
  await loadPage(0)
  if (version === creationVersion) await select(workspace.id)
}

function applyWorkspace(workspace: Workspace) {
  if (selectedId.value !== workspace.id) return
  detail.value = workspace
  // Names do not determine page order (server orders by ID); replace only an existing row.
  if (page.value) page.value = { ...page.value, items: page.value.items.map(item => item.id === workspace.id ? workspace : item) }
}

function actorRoleChanged(role: WorkspaceRole) {
  if (!detail.value) return
  detail.value = { ...detail.value, myRole: role }
  if (page.value) page.value = { ...page.value, items: page.value.items.map(item => item.id === detail.value?.id ? { ...item, myRole: role } : item) }
}

async function invitationAccepted(workspaceId: string) {
  await loadPage(0)
  await select(workspaceId)
}

onMounted(() => loadPage(0))
onUnmounted(() => { creationVersion++; listRequest?.abort(); detailRequest?.abort() })
</script>

<template>
  <section class="workspace-section" aria-labelledby="workspaces-title">
    <div class="card-heading workspace-heading">
      <div>
        <p class="eyebrow">내가 속한 공간</p>
        <h2 id="workspaces-title">워크스페이스</h2>
      </div>
      <button type="button" :disabled="listLoading" @click="loadPage()">목록 새로고침</button>
    </div>

    <WorkspaceCreateForm @created="created" @check-list="loadPage(0)" />

    <div class="workspace-grid">
      <section class="workspace-panel" aria-label="워크스페이스 목록" :aria-busy="listLoading">
        <p v-if="listLoading" role="status">워크스페이스를 불러오고 있어요.</p>
        <div v-else-if="listError" role="alert">
          <p>{{ listError }}</p>
          <button type="button" @click="loadPage()">목록 다시 시도</button>
        </div>
        <template v-else-if="page">
          <p v-if="page.items.length === 0" role="status">
            {{ page.page === 0 ? '아직 참여한 워크스페이스가 없습니다.' : '이 페이지에는 워크스페이스가 없습니다.' }}
          </p>
          <ul v-else class="workspace-list">
            <li v-for="item in page.items" :key="item.id">
              <button class="workspace-choice" type="button" :aria-pressed="selectedId === item.id" @click="select(item.id)">
                <span>{{ item.name }}</span>
                <span class="role-badge">{{ roles[item.myRole] }}</span>
              </button>
            </li>
          </ul>
          <nav class="workspace-pagination" aria-label="워크스페이스 페이지 이동">
            <button type="button" :disabled="page.page === 0" @click="loadPage(page.page - 1)">이전 페이지</button>
            <span aria-live="polite">{{ page.page + 1 }}페이지</span>
            <button type="button" :disabled="!page.hasNext || page.page >= 10000" @click="loadPage(page.page + 1)">다음 페이지</button>
          </nav>
        </template>
      </section>

      <section class="workspace-panel workspace-detail" aria-labelledby="workspace-detail-title" :aria-busy="detailLoading">
        <h3 id="workspace-detail-title">공간 정보</h3>
        <p v-if="detailLoading" role="status">공간 정보를 불러오고 있어요.</p>
        <div v-else-if="detailError" role="alert">
          <p>{{ detailError }}</p>
          <button v-if="selectedId" type="button" @click="select(selectedId)">상세 다시 시도</button>
        </div>
        <template v-else-if="detail">
          <h4>{{ detail.name }}</h4>
          <p v-if="page && !page.items.some(item => item.id === detail?.id)" class="workspace-hint">현재 목록 페이지에 없는 공간을 보고 있어요.</p>
          <dl>
            <dt>내 역할</dt><dd>{{ roles[detail.myRole] }}</dd>
          </dl>
          <WorkspaceRenameForm :key="detail.id" :workspace="detail" @renamed="applyWorkspace" @refreshed="applyWorkspace" />
          <WorkspaceMembershipManager :key="`members-${detail.id}`" :workspace="detail" @actor-role-changed="actorRoleChanged" />
        </template>
        <p v-else class="workspace-hint">목록에서 워크스페이스를 선택해 주세요.</p>
      </section>
    </div>
    <WorkspaceInvitationAcceptForm @accepted="invitationAccepted" />
    <ProjectBrowser v-if="detail" :key="detail.id" :workspace-id="detail.id" :workspace-name="detail.name" :my-role="detail.myRole" />
  </section>
</template>
