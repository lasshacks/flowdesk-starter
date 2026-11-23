<script setup lang="ts">
import { computed, onUnmounted, ref } from 'vue'
import { changeTaskStatus, getTask, TaskApiError, type Task, type TaskStatus } from '../api/tasks'
import { getWorkspace, type WorkspaceRole } from '../api/workspaces'

const props = defineProps<{ task: Task; myRole?: WorkspaceRole }>()
const emit = defineEmits<{ updated: [task: Task]; refreshed: [task: Task] }>()
// The ordinary edit and status form share a server version, but keep independent drafts/bases.
const base = ref({ ...props.task })
const target = ref<TaskStatus>(base.value.status)
const checked = ref(false)
const submitting = ref(false)
const refreshing = ref(false)
const blocked = ref<'conflict' | 'uncertain' | null>(null)
const latest = ref<Task | null>(null)
const latestRole = ref<WorkspaceRole | null>(null)
const error = ref<string | null>(null)
const recoveryError = ref<string | null>(null)
const saved = ref(false)
let writeRequest: AbortController | undefined
let readRequest: AbortController | undefined
const canEdit = computed(() => ['OWNER', 'ADMIN', 'MEMBER'].includes(props.myRole ?? ''))
const validTarget = computed(() => ['TODO', 'IN_PROGRESS', 'DONE'].includes(target.value))
const canUseLatest = computed(() => canEdit.value && latest.value !== null && ['OWNER', 'ADMIN', 'MEMBER'].includes(latestRole.value ?? ''))
const statusLabels = { TODO: '할 일', IN_PROGRESS: '진행 중', DONE: '완료' }
const priorityLabels = { LOW: '낮음', MEDIUM: '보통', HIGH: '높음' }

function rejectedMessage(cause: TaskApiError) {
  if (cause.status === 403) return '작업 상태를 변경할 권한이 없거나 현재 요청이 허용되지 않습니다.'
  if (cause.status === 404) return '작업 또는 공간을 찾을 수 없거나 접근할 수 없습니다.'
  if (cause.status === 405) return '현재는 작업 상태를 변경할 수 없습니다. 조회는 계속 이용할 수 있어요.'
  return '상태 변경 요청을 처리할 수 없습니다. 선택한 상태를 확인해 주세요.'
}
async function submit() {
  if (!canEdit.value || submitting.value || refreshing.value || blocked.value) return
  checked.value = true; error.value = null; saved.value = false
  if (!validTarget.value) return
  submitting.value = true
  const controller = new AbortController(); writeRequest = controller
  try {
    const result = await changeTaskStatus(base.value, target.value, controller.signal)
    if (controller.signal.aborted) return
    base.value = result; target.value = result.status; checked.value = false; saved.value = true
    emit('updated', result)
  } catch (cause) {
    if (controller.signal.aborted) return
    if (cause instanceof TaskApiError && cause.status === 409) blocked.value = 'conflict'
    else if (cause instanceof TaskApiError) error.value = rejectedMessage(cause)
    else blocked.value = 'uncertain'
  } finally { if (!controller.signal.aborted) submitting.value = false }
}
async function refresh() {
  if (submitting.value || refreshing.value || !blocked.value) return
  refreshing.value = true; recoveryError.value = null; latest.value = null; latestRole.value = null
  const controller = new AbortController(); readRequest = controller
  try {
    const [task, workspace] = await Promise.all([
      getTask(base.value.workspaceId, base.value.projectId, base.value.id, controller.signal),
      getWorkspace(base.value.workspaceId, controller.signal),
    ])
    if (controller.signal.aborted) return
    if (BigInt(task.version) < BigInt(base.value.version) || BigInt(task.version) < BigInt(props.task.version)) {
      throw new TaskApiError('INVALID_RESPONSE')
    }
    latest.value = task; latestRole.value = workspace.myRole
    emit('refreshed', task)
  } catch {
    if (controller.signal.aborted) return
    recoveryError.value = '최신 정보와 변경 권한을 확인하지 못했습니다. 선택과 저장 잠금은 유지됩니다. 다시 확인해 주세요.'
  } finally { if (!controller.signal.aborted) refreshing.value = false }
}
function useLatest() {
  if (submitting.value || refreshing.value || !blocked.value || !canUseLatest.value) return
  // Explicitly adopt the compared snapshot, preserving the target; this never sends PATCH.
  base.value = { ...latest.value! }
  latest.value = null; latestRole.value = null; blocked.value = null
  recoveryError.value = null; error.value = null; saved.value = false
}
onUnmounted(() => { writeRequest?.abort(); readRequest?.abort() })
</script>

<template>
  <form v-if="canEdit" class="project-update task-status" aria-labelledby="task-status-heading" :aria-busy="submitting || refreshing" novalidate @submit.prevent="submit">
    <h5 id="task-status-heading">작업 상태 변경</h5>
    <label for="task-status-target">변경할 작업 상태</label>
    <select id="task-status-target" v-model="target" :disabled="submitting || refreshing" :aria-invalid="checked && !validTarget" aria-describedby="task-status-help" @change="saved = false">
      <option value="TODO">할 일</option><option value="IN_PROGRESS">진행 중</option><option value="DONE">완료</option>
    </select>
    <p id="task-status-help" class="project-form-help">완료한 작업도 다시 시작할 수 있어요. 제목·설명·우선순위 변경과 겹치면 최신 정보를 다시 확인해야 합니다.</p>
    <p v-if="checked && !validTarget" class="form-error" role="alert">변경할 상태를 선택해 주세요.</p>
    <button type="submit" :disabled="submitting || refreshing || !!blocked">{{ submitting ? '상태 저장 중…' : '작업 상태 저장' }}</button>
    <p v-if="error" class="form-error" role="alert">{{ error }}</p>
    <div v-if="blocked" class="rename-recovery task-status-recovery" role="alert">
      <p v-if="blocked === 'conflict'">다른 변경이 먼저 저장됐습니다. 선택한 상태는 유지됩니다. 최신 작업과 비교한 뒤 다시 변경해 주세요.</p>
      <p v-else>상태 변경 결과를 확인하지 못했습니다. 이미 저장됐을 수 있습니다. 자동으로 다시 보내지 않습니다. 최신 작업과 선택한 상태를 비교해 주세요.</p>
      <button type="button" :disabled="refreshing" @click="refresh">{{ refreshing ? '상태 확인 중…' : '상태 최신 정보 확인' }}</button>
      <p v-if="recoveryError" class="form-error">{{ recoveryError }}</p>
      <template v-if="latest">
        <dl class="project-comparison task-status-comparison">
          <dt>편집 시작 상태</dt><dd>{{ statusLabels[base.status] }}</dd>
          <dt>현재 서버 상태</dt><dd>{{ statusLabels[latest.status] }}</dd>
          <dt>내가 선택한 상태</dt><dd>{{ statusLabels[target] }}</dd>
          <dt>현재 서버 제목</dt><dd>{{ latest.title }}</dd>
          <dt>현재 서버 설명</dt><dd>{{ latest.description ?? '등록된 설명이 없습니다.' }}</dd>
          <dt>현재 서버 우선순위</dt><dd>{{ priorityLabels[latest.priority] }}</dd>
        </dl>
        <p v-if="!canUseLatest">현재는 작업 상태를 변경할 권한이 없습니다. 목록에서 다시 확인해 주세요.</p>
        <template v-else>
          <p>최신 작업을 비교해 주세요. 최신 기준을 선택해도 상태 선택은 유지되며, 별도로 저장해야 합니다. 같은 상태가 보여도 이전 요청의 성공이 확인된 것은 아닙니다.</p>
          <button type="button" :disabled="refreshing" @click="useLatest">상태 최신 기준으로 다시 편집</button>
        </template>
      </template>
    </div>
    <p v-if="saved" class="rename-success" role="status">작업 상태 저장을 확인했습니다.</p>
  </form>
</template>
