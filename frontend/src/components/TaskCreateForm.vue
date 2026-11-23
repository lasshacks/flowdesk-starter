<script setup lang="ts">
import { computed, nextTick, onUnmounted, ref } from 'vue'
import { createTask, normalizeTaskTitle, normalizeTaskDescription, normalizeTaskDueDateInput, TaskApiError, type Task, type TaskPriority } from '../api/tasks'
import TaskAssigneeSelect from './TaskAssigneeSelect.vue'

const props = defineProps<{ workspaceId: string; projectId: string }>()
const emit = defineEmits<{ created: [task: Task]; checkList: [] }>()
const title = ref('')
const description = ref('')
const priority = ref<TaskPriority>('MEDIUM')
const assigneeId = ref('')
const dueDate = ref('')
const titleInput = ref<HTMLInputElement | null>(null)
const descriptionInput = ref<HTMLTextAreaElement | null>(null)
const dueDateInput = ref<HTMLInputElement | null>(null)
const checked = ref(false)
const submitting = ref(false)
const uncertain = ref(false)
const error = ref<string | null>(null)
const createdTitle = ref<string | null>(null)
let request: AbortController | undefined

const validTitle = computed(() => {
  try { return normalizeTaskTitle(title.value) } catch { return null }
})
const validDescription = computed(() => {
  try { return { value: normalizeTaskDescription(description.value) } } catch { return null }
})
const parsedDueDate = computed(() => {
  try { return { value: normalizeTaskDueDateInput(dueDate.value) } } catch { return null }
})

function rejectedMessage(cause: TaskApiError): string {
  if (cause.status === 400) return '제목·설명·담당자·마감일을 확인해 주세요. 담당자는 현재 Workspace 멤버여야 합니다.'
  if ([403, 404, 405].includes(cause.status)) return '현재는 이 프로젝트에 작업을 만들 수 없습니다. 목록 조회는 계속 이용할 수 있어요.'
  return '생성 요청을 처리할 수 없습니다. 입력 내용을 확인해 주세요.'
}

async function submit() {
  if (submitting.value || uncertain.value) return
  checked.value = true
  error.value = null
  createdTitle.value = null
  if (validTitle.value === null) { titleInput.value?.focus(); return }
  if (validDescription.value === null) { descriptionInput.value?.focus(); return }
  if (parsedDueDate.value === null) { dueDateInput.value?.focus(); return }
  submitting.value = true
  const controller = new AbortController()
  request = controller
  try {
    const result = await createTask(props.workspaceId, props.projectId, validTitle.value, validDescription.value.value,
      priority.value, assigneeId.value || null, parsedDueDate.value.value, controller.signal)
    if (controller.signal.aborted) return
    createdTitle.value = result.title
    title.value = ''; description.value = ''; priority.value = 'MEDIUM'; assigneeId.value = ''; dueDate.value = ''; checked.value = false
    emit('created', result)
  } catch (cause) {
    if (controller.signal.aborted) return
    if (cause instanceof TaskApiError) error.value = rejectedMessage(cause)
    else uncertain.value = true
  } finally {
    if (!controller.signal.aborted) submitting.value = false
  }
}

async function reset() {
  title.value = ''; description.value = ''; priority.value = 'MEDIUM'; assigneeId.value = ''; dueDate.value = ''; checked.value = false
  uncertain.value = false; error.value = null; createdTitle.value = null
  await nextTick(); titleInput.value?.focus()
}
onUnmounted(() => request?.abort())
</script>

<template>
  <form class="project-create task-create" aria-labelledby="task-create-title" :aria-busy="submitting" novalidate @submit.prevent="submit">
    <h4 id="task-create-title">새 작업</h4>
    <p class="project-form-help">이 프로젝트에 작업을 만듭니다. 제목은 중복될 수 있어요.</p>
    <label for="task-title">작업 제목</label>
    <input id="task-title" ref="titleInput" v-model="title" type="text" required autocomplete="off"
      :disabled="submitting || uncertain" :aria-invalid="checked && validTitle === null" aria-describedby="task-title-help task-title-error"
      placeholder="예: 첫 번째 출시" />
    <p id="task-title-help" class="project-form-help">앞뒤 공백을 제외한 1~200자로 입력해 주세요.</p>
    <p v-if="checked && validTitle === null" id="task-title-error" class="form-error" role="alert">작업 제목은 1~200자로 입력하고 사용할 수 없는 문자는 제외해 주세요.</p>
    <label for="task-description">설명 <span class="project-form-help">(선택)</span></label>
    <textarea id="task-description" ref="descriptionInput" v-model="description" rows="4"
      :disabled="submitting || uncertain" :aria-invalid="checked && validDescription === null" aria-describedby="task-description-help task-description-error"
      placeholder="작업 내용을 적어 주세요." />
    <p id="task-description-help" class="project-form-help">최대 2,000자. 줄바꿈을 포함한 일반 텍스트로 저장합니다.</p>
    <p v-if="checked && validDescription === null" id="task-description-error" class="form-error" role="alert">설명은 2,000자 이내로 입력하고 사용할 수 없는 문자는 제외해 주세요.</p>
    <label for="task-priority">우선순위</label>
    <select id="task-priority" v-model="priority" :disabled="submitting || uncertain">
      <option value="LOW">낮음</option><option value="MEDIUM">보통</option><option value="HIGH">높음</option>
    </select>
    <TaskAssigneeSelect id="task-assignee-id" v-model="assigneeId" :workspace-id="workspaceId" :disabled="submitting || uncertain" />
    <label for="task-due-date">마감일 <span class="project-form-help">(선택)</span></label>
    <input id="task-due-date" ref="dueDateInput" v-model="dueDate" type="date" :disabled="submitting || uncertain"
      :aria-invalid="checked && parsedDueDate === null" aria-describedby="task-due-date-help task-due-date-error" />
    <p id="task-due-date-help" class="project-form-help">시간 없이 날짜만 저장합니다. 지난 날짜도 입력할 수 있습니다.</p>
    <p v-if="checked && parsedDueDate === null" id="task-due-date-error" class="form-error" role="alert">실제로 존재하는 날짜를 선택해 주세요.</p>
    <p class="project-form-help">새 작업은 ‘할 일’ 상태로 생성됩니다.</p>
    <button type="submit" :disabled="submitting || uncertain">{{ submitting ? '작업 만드는 중…' : '작업 만들기' }}</button>
    <p v-if="error" class="form-error" role="alert">{{ error }}</p>
    <div v-if="uncertain" class="creation-uncertain" role="alert">
      <p>생성 결과를 확인하지 못했습니다. 이미 생성됐을 수 있으니 목록을 확인해 주세요. 다시 보내면 같은 제목의 작업이 추가될 수 있습니다.</p>
      <div class="creation-recovery">
        <button type="button" @click="emit('checkList')">작업 목록 확인</button>
        <button type="button" class="secondary-button" @click="reset">작업 입력 초기화</button>
      </div>
    </div>
    <p v-if="createdTitle" class="creation-success" role="status">“{{ createdTitle }}” 작업을 만들었습니다.</p>
  </form>
</template>
