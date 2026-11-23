<script setup lang="ts">
import { computed, onUnmounted, ref } from 'vue'
import { getTask, normalizeTaskTitle, normalizeTaskDescription, normalizeTaskDueDateInput, updateTask, TaskApiError, type Task, type TaskPriority } from '../api/tasks'
import { getWorkspace, type WorkspaceRole } from '../api/workspaces'
import TaskAssigneeSelect from './TaskAssigneeSelect.vue'

const props = defineProps<{ task: Task; myRole?: WorkspaceRole }>()
const emit = defineEmits<{ updated: [task: Task]; refreshed: [task: Task] }>()
// Same-ID prop refreshes must never silently change an in-progress edit's version.
const base = ref({ ...props.task })
const title = ref(base.value.title)
const description = ref(base.value.description ?? '')
const priority = ref<TaskPriority>(base.value.priority)
const assigneeId = ref(base.value.assigneeId ?? '')
const dueDate = ref(base.value.dueDate ?? '')
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
const canUseLatest = computed(() => canEdit.value && latest.value !== null && ['OWNER', 'ADMIN', 'MEMBER'].includes(latestRole.value ?? ''))
const validPriority = computed(() => ['LOW', 'MEDIUM', 'HIGH'].includes(priority.value))
const priorityLabels = { LOW: '낮음', MEDIUM: '보통', HIGH: '높음' }
const statusLabels = { TODO: '할 일', IN_PROGRESS: '진행 중', DONE: '완료' }
const validTitle = computed(() => { try { return normalizeTaskTitle(title.value) } catch { return null } })
const validDescription = computed(() => {
  try { return { value: normalizeTaskDescription(description.value) } } catch { return null }
})
const parsedDueDate = computed(() => {
  try { return { value: normalizeTaskDueDateInput(dueDate.value) } } catch { return null }
})

function rejectedMessage(cause: TaskApiError) {
  if (cause.status === 403) return '작업을 수정할 권한이 없거나 현재 요청이 허용되지 않습니다.'
  if (cause.status === 404) return '작업 또는 공간을 찾을 수 없거나 접근할 수 없습니다.'
  if (cause.status === 405) return '현재는 작업을 수정할 수 없습니다. 조회는 계속 이용할 수 있어요.'
  if (cause.status === 400) return '제목·설명·담당자·마감일을 확인해 주세요. 담당자는 현재 Workspace 멤버여야 합니다.'
  return '수정 요청을 처리할 수 없습니다.'
}
async function submit() {
  if (!canEdit.value || submitting.value || refreshing.value || blocked.value) return
  checked.value = true; error.value = null; saved.value = false
  if (validTitle.value === null || validDescription.value === null || !validPriority.value
    || parsedDueDate.value === null) return
  submitting.value = true
  const controller = new AbortController(); writeRequest = controller
  try {
    const result = await updateTask(base.value, validTitle.value, validDescription.value.value, priority.value,
      assigneeId.value || null, parsedDueDate.value.value, controller.signal)
    if (controller.signal.aborted) return
    base.value = result; title.value = result.title; description.value = result.description ?? ''; priority.value = result.priority
    assigneeId.value = result.assigneeId ?? ''; dueDate.value = result.dueDate ?? ''
    checked.value = false; saved.value = true
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
    recoveryError.value = '최신 정보와 수정 권한을 확인하지 못했습니다. 입력과 저장 잠금은 유지됩니다. 다시 확인해 주세요.'
  } finally { if (!controller.signal.aborted) refreshing.value = false }
}
function useLatest() {
  if (submitting.value || refreshing.value || !blocked.value || !canUseLatest.value) return
  // Adopt the latest full Task version while keeping all editable draft fields.
  base.value = { ...latest.value! }
  latest.value = null; latestRole.value = null; blocked.value = null
  recoveryError.value = null; error.value = null; saved.value = false
}
onUnmounted(() => { writeRequest?.abort(); readRequest?.abort() })
</script>

<template>
  <form v-if="canEdit" class="project-update task-update" aria-labelledby="task-update-heading" :aria-busy="submitting || refreshing" novalidate @submit.prevent="submit">
    <h5 id="task-update-heading">작업 수정</h5>
    <label for="task-update-title">작업 제목 수정</label>
    <input id="task-update-title" v-model="title" type="text" required autocomplete="off"
      :disabled="submitting || refreshing" :aria-invalid="checked && validTitle === null" aria-describedby="task-update-title-help" @input="saved = false" />
    <p id="task-update-title-help" class="project-form-help">앞뒤 공백을 제외한 1~200자로 입력해 주세요.</p>
    <p v-if="checked && validTitle === null" class="form-error" role="alert">제목은 1~200자로 입력하고 사용할 수 없는 문자는 제외해 주세요.</p>
    <label for="task-update-description">작업 설명 수정 <span class="project-form-help">(선택)</span></label>
    <textarea id="task-update-description" v-model="description" rows="4" :disabled="submitting || refreshing"
      :aria-invalid="checked && validDescription === null" aria-describedby="task-update-description-help" @input="saved = false" />
    <p id="task-update-description-help" class="project-form-help">최대 2,000자. 비우면 기존 설명을 삭제합니다. 제목·설명·우선순위를 함께 저장합니다.</p>
    <p v-if="checked && validDescription === null" class="form-error" role="alert">설명은 2,000자 이내로 입력하고 사용할 수 없는 문자는 제외해 주세요.</p>
    <label for="task-update-priority">작업 우선순위 수정</label>
    <select id="task-update-priority" v-model="priority" :disabled="submitting || refreshing"
      :aria-invalid="checked && !validPriority" @change="saved = false">
      <option value="LOW">낮음</option><option value="MEDIUM">보통</option><option value="HIGH">높음</option>
    </select>
    <p v-if="checked && !validPriority" class="form-error" role="alert">우선순위를 선택해 주세요.</p>
    <TaskAssigneeSelect id="task-update-assignee-id" v-model="assigneeId" :workspace-id="base.workspaceId"
      :existing-assignee-id="base.assigneeId ?? ''" :disabled="submitting || refreshing" @update:model-value="saved = false" />
    <label for="task-update-due-date">마감일 수정 <span class="project-form-help">(선택)</span></label>
    <input id="task-update-due-date" v-model="dueDate" type="date" :disabled="submitting || refreshing"
      :aria-invalid="checked && parsedDueDate === null" aria-describedby="task-update-due-date-help task-update-due-date-error" @change="saved = false" />
    <p id="task-update-due-date-help" class="project-form-help">시간 없이 날짜만 저장합니다. 비우면 마감일을 지웁니다.</p>
    <p v-if="checked && parsedDueDate === null" id="task-update-due-date-error" class="form-error" role="alert">실제로 존재하는 날짜를 선택해 주세요.</p>
    <button type="submit" :disabled="submitting || refreshing || !!blocked">{{ submitting ? '작업 저장 중…' : '작업 변경 저장' }}</button>
    <p v-if="error" class="form-error" role="alert">{{ error }}</p>
    <div v-if="blocked" class="rename-recovery task-update-recovery" role="alert">
      <p v-if="blocked === 'conflict'">다른 변경이 먼저 저장됐습니다. 입력은 유지됩니다. 최신 항목과 비교한 뒤 다시 편집해 주세요.</p>
      <p v-else>수정 결과를 확인하지 못했습니다. 이미 저장됐을 수 있습니다. 자동으로 다시 보내지 않습니다. 최신 정보와 입력을 비교해 주세요.</p>
      <button type="button" :disabled="refreshing" @click="refresh">{{ refreshing ? '작업 확인 중…' : '작업 최신 정보 확인' }}</button>
      <p v-if="recoveryError" class="form-error">{{ recoveryError }}</p>
      <template v-if="latest">
        <dl class="project-comparison task-comparison">
          <dt>편집 시작 상태</dt><dd>{{ statusLabels[base.status] }}</dd>
          <dt>현재 서버 상태</dt><dd>{{ statusLabels[latest.status] }}</dd>
          <dt>현재 서버 제목</dt><dd>{{ latest.title }}</dd>
          <dt>내가 입력한 제목</dt><dd>{{ title }}</dd>
          <dt>현재 서버 설명</dt><dd>{{ latest.description ?? '등록된 설명이 없습니다.' }}</dd>
          <dt>내가 입력한 설명</dt><dd>{{ description || '설명 삭제' }}</dd>
          <dt>현재 서버 우선순위</dt><dd>{{ priorityLabels[latest.priority] }}</dd>
          <dt>내가 선택한 우선순위</dt><dd>{{ priorityLabels[priority] }}</dd>
          <dt>현재 서버 담당자 ID</dt><dd>{{ latest.assigneeId ?? '미지정' }}</dd>
          <dt>내가 입력한 담당자 ID</dt><dd>{{ assigneeId || '미지정' }}</dd>
          <dt>현재 서버 마감일</dt><dd>{{ latest.dueDate ?? '미지정' }}</dd>
          <dt>내가 입력한 마감일</dt><dd>{{ dueDate || '미지정' }}</dd>
        </dl>
        <p v-if="!canUseLatest">현재는 작업을 수정할 권한이 없습니다. 목록에서 다시 확인해 주세요.</p>
        <template v-else>
          <p>수정 항목을 비교해 주세요. 최신 기준을 선택해도 입력은 유지되며, 별도로 저장해야 합니다.</p>
          <button type="button" :disabled="refreshing" @click="useLatest">작업 최신 기준으로 다시 편집</button>
        </template>
      </template>
    </div>
    <p v-if="saved" class="rename-success" role="status">작업 변경 저장을 확인했습니다.</p>
  </form>
</template>
