<script setup lang="ts">
import { onUnmounted, ref } from 'vue'
import { getProject, ProjectApiError, ProjectArchiveUncertainError, setProjectArchived, type Project } from '../api/projects'
import { getWorkspace, type WorkspaceRole } from '../api/workspaces'

const props = defineProps<{ project: Project; myRole?: WorkspaceRole }>()
const emit = defineEmits<{ updated: [project: Project] }>()
const target = ref<boolean | null>(null)
const busy = ref(false)
const blocked = ref<'conflict' | 'uncertain' | null>(null)
const latest = ref<Project | null>(null)
const latestRole = ref<WorkspaceRole | null>(null)
const error = ref<string | null>(null)
const recoveryError = ref<string | null>(null)
let request: AbortController | undefined
const canManage = () => props.myRole === 'OWNER' || props.myRole === 'ADMIN'
const canUseLatest = () => latest.value !== null && ['OWNER', 'ADMIN'].includes(latestRole.value ?? '')

function ask() {
  if (!canManage() || busy.value || blocked.value) return
  error.value = null
  target.value = !props.project.archived
}
function cancel() { if (!busy.value) { target.value = null; error.value = null } }

async function confirm() {
  if (target.value === null || !canManage() || busy.value || blocked.value) return
  busy.value = true; error.value = null
  const controller = new AbortController(); request = controller
  try {
    const updated = await setProjectArchived(props.project, target.value, controller.signal)
    if (!controller.signal.aborted) { target.value = null; emit('updated', updated) }
  } catch (cause) {
    if (controller.signal.aborted) return
    if (cause instanceof ProjectApiError && cause.status === 409) blocked.value = 'conflict'
    else if (cause instanceof ProjectArchiveUncertainError) blocked.value = 'uncertain'
    else if (cause instanceof ProjectApiError && cause.status === 403) error.value = '프로젝트를 보관하거나 복구할 권한이 없습니다.'
    else if (cause instanceof ProjectApiError && cause.status === 404) error.value = '프로젝트 또는 공간을 찾을 수 없거나 접근할 수 없습니다.'
    else if (cause instanceof ProjectApiError && cause.status === 405) error.value = '현재 보관 기능을 사용할 수 없습니다. 조회는 계속 이용할 수 있어요.'
    else error.value = '요청을 처리할 수 없습니다. 잠시 후 다시 확인해 주세요.'
    target.value = null
  } finally { if (!controller.signal.aborted) busy.value = false }
}

async function refresh() {
  if (busy.value || !blocked.value) return
  busy.value = true; recoveryError.value = null; latest.value = null; latestRole.value = null
  const controller = new AbortController(); request = controller
  try {
    const [project, workspace] = await Promise.all([
      getProject(props.project.workspaceId, props.project.id, controller.signal),
      getWorkspace(props.project.workspaceId, controller.signal),
    ])
    if (controller.signal.aborted) return
    latest.value = project; latestRole.value = workspace.myRole
  } catch {
    if (!controller.signal.aborted) recoveryError.value = '최신 상태와 권한을 확인하지 못했습니다. 다시 확인해 주세요.'
  } finally { if (!controller.signal.aborted) busy.value = false }
}

function useLatest() {
  if (busy.value || !blocked.value || !canUseLatest()) return
  emit('updated', latest.value!)
  latest.value = null; latestRole.value = null; blocked.value = null; recoveryError.value = null
}

onUnmounted(() => request?.abort())
</script>

<template>
  <div v-if="canManage()" class="project-archive-controls">
    <button type="button" :disabled="busy || !!blocked" @click="ask">
      {{ busy ? '프로젝트 상태 저장 중…' : project.archived ? '프로젝트 복구' : '프로젝트 보관' }}
    </button>
    <div v-if="target !== null" class="project-archive-confirm" role="group" aria-label="프로젝트 상태 변경 확인">
      <p>{{ target ? '보관하면 목록에서 숨겨지고 이름/설명과 Task 변경이 막힙니다. Task와 이력은 계속 조회할 수 있습니다.' : '이 프로젝트를 활성 상태로 복구할까요? 기존 Task 변경도 다시 허용됩니다.' }}</p>
      <button type="button" :disabled="busy" @click="confirm">{{ target ? '프로젝트 보관 확인' : '프로젝트 복구 확인' }}</button>
      <button type="button" :disabled="busy" @click="cancel">취소</button>
    </div>
    <p v-if="error" class="form-error" role="alert">{{ error }}</p>
    <div v-if="blocked" class="rename-recovery" role="alert">
      <p v-if="blocked === 'conflict'">다른 변경이 먼저 저장됐습니다. 자동 재요청하지 않습니다. 최신 상태를 확인해 주세요.</p>
      <p v-else>보관/복구 결과를 확인하지 못했습니다. 이미 저장됐을 수 있어 자동 재요청하지 않습니다.</p>
      <button type="button" :disabled="busy" @click="refresh">{{ busy ? '최신 상태 확인 중…' : '최신 프로젝트 상태 확인' }}</button>
      <p v-if="recoveryError" class="form-error">{{ recoveryError }}</p>
      <template v-if="latest">
        <p>현재 서버 상태: {{ latest.archived ? '보관됨' : '활성' }} · 버전 {{ latest.version }}</p>
        <p v-if="!canUseLatest()">현재는 프로젝트를 관리할 권한이 없습니다.</p>
        <button v-else type="button" :disabled="busy" @click="useLatest">최신 상태를 기준으로 다시 확인</button>
      </template>
    </div>
  </div>
</template>
