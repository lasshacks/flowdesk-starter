<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { getTask, getTasks, TaskApiError, type Task, type TaskPage } from '../api/tasks'
import TaskCreateForm from './TaskCreateForm.vue'
import TaskUpdateForm from './TaskUpdateForm.vue'
import TaskStatusForm from './TaskStatusForm.vue'
import TaskStatusHistory from './TaskStatusHistory.vue'
import type { WorkspaceRole } from '../api/workspaces'

const props = defineProps<{ workspaceId: string; projectId: string; projectName: string; myRole?: WorkspaceRole; projectArchived?: boolean }>()
const canCreate = computed(() => ['OWNER', 'ADMIN', 'MEMBER'].includes(props.myRole ?? ''))
const canWrite = computed(() => canCreate.value && !props.projectArchived)
const page = ref<TaskPage | null>(null)
const requestedPage = ref(0)
const listLoading = ref(false)
const listError = ref<string | null>(null)
const selectedId = ref<string | null>(null)
const detail = ref<Task | null>(null)
const detailLoading = ref(false)
const detailError = ref<string | null>(null)
let listRequest: AbortController | undefined
let detailRequest: AbortController | undefined
let navigationVersion = 0
let alive = true

function message(cause: unknown): string {
  if (!(cause instanceof TaskApiError)) return '서버에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요.'
  if (cause.status === 404) return '작업 또는 프로젝트를 찾을 수 없거나 접근할 수 없습니다.'
  if (cause.status === 503 || cause.code === 'SERVICE_UNAVAILABLE') return '일시적으로 서비스를 이용할 수 없습니다. 잠시 후 다시 시도해 주세요.'
  if (cause.code === 'INVALID_RESPONSE') return '서버 응답을 확인할 수 없습니다. 다시 시도해 주세요.'
  return '작업을 불러오지 못했습니다. 다시 시도해 주세요.'
}

function clearSelection() {
  detailRequest?.abort()
  selectedId.value = null
  detail.value = null
  detailError.value = null
  detailLoading.value = false
}

async function loadPage(number = requestedPage.value) {
  const version = ++navigationVersion
  listRequest?.abort()
  clearSelection()
  const controller = new AbortController()
  listRequest = controller
  requestedPage.value = number
  page.value = null
  listError.value = null
  listLoading.value = true
  try {
    const result = await getTasks(props.workspaceId, props.projectId, number, controller.signal)
    if (!controller.signal.aborted) page.value = result
  } catch (cause) {
    if (!controller.signal.aborted) listError.value = message(cause)
  } finally {
    if (!controller.signal.aborted) listLoading.value = false
  }
  return alive && !controller.signal.aborted && version === navigationVersion
}

async function select(id: string) {
  navigationVersion++
  detailRequest?.abort()
  const controller = new AbortController()
  detailRequest = controller
  selectedId.value = id
  detail.value = null
  detailError.value = null
  detailLoading.value = true
  try {
    const result = await getTask(props.workspaceId, props.projectId, id, controller.signal)
    if (!controller.signal.aborted) detail.value = result
  } catch (cause) {
    if (!controller.signal.aborted) detailError.value = message(cause)
  } finally {
    if (!controller.signal.aborted) detailLoading.value = false
  }
}

async function created(task: Task) {
  if (!alive || task.workspaceId !== props.workspaceId || task.projectId !== props.projectId) return
  // A confirmed POST stays successful even if the following list request fails.
  if (await loadPage(0)) await select(task.id)
}

function updated(task: Task) {
  if (!alive || task.workspaceId !== props.workspaceId || task.projectId !== props.projectId || task.id !== selectedId.value) return
  if (detail.value && BigInt(task.version) < BigInt(detail.value.version)) return
  detail.value = task
  if (page.value) page.value = { ...page.value, items: page.value.items.map(item => item.id === task.id
    ? { ...item, title: task.title, status: task.status, priority: task.priority, assigneeId: task.assigneeId, dueDate: task.dueDate } : item) }
}

// Parent keys this component by project ID; unmount cancels both requests.
onMounted(() => loadPage(0))
onUnmounted(() => { alive = false; navigationVersion++; listRequest?.abort(); detailRequest?.abort() })
const statusLabels = { TODO: '할 일', IN_PROGRESS: '진행 중', DONE: '완료' }
const priorityLabels = { LOW: '낮음', MEDIUM: '보통', HIGH: '높음' }
</script>

<template>
  <section class="project-section task-section" aria-labelledby="tasks-title">
    <div class="card-heading project-heading task-heading">
      <div>
        <p class="eyebrow">{{ projectName }}의 작업</p>
        <h3 id="tasks-title">작업</h3>
      </div>
      <button type="button" :disabled="listLoading" @click="loadPage()">작업 새로고침</button>
    </div>
    <TaskCreateForm v-if="canWrite" :workspace-id="workspaceId" :project-id="projectId" @created="created" @check-list="loadPage(0)" />
    <p v-else-if="projectArchived" class="project-form-help" role="status">보관된 프로젝트에서는 Task를 만들거나 수정할 수 없습니다.</p>
    <p v-else class="project-form-help">작업 생성은 이 공간의 소유자·관리자·멤버만 할 수 있습니다.</p>
    <div class="workspace-grid task-grid">
      <section class="workspace-panel" aria-label="작업 목록" :aria-busy="listLoading">
        <p v-if="listLoading" role="status">작업을 불러오고 있어요.</p>
        <div v-else-if="listError" role="alert">
          <p>{{ listError }}</p>
          <button type="button" @click="loadPage()">작업 목록 다시 시도</button>
        </div>
        <template v-else-if="page">
          <p v-if="page.items.length === 0" role="status">
            {{ page.page === 0 ? '이 프로젝트에는 아직 작업이 없습니다.' : '이 페이지에는 작업이 없습니다.' }}
          </p>
          <ul v-else class="workspace-list task-list">
            <li v-for="item in page.items" :key="item.id">
              <button type="button" class="task-choice" :aria-pressed="selectedId === item.id" @click="select(item.id)">{{ item.title }} · {{ statusLabels[item.status] }} · {{ priorityLabels[item.priority] }}</button>
            </li>
          </ul>
          <nav class="workspace-pagination" aria-label="작업 페이지 이동">
            <button type="button" aria-label="이전 작업 페이지" :disabled="page.page === 0" @click="loadPage(page.page - 1)">이전 페이지</button>
            <span aria-live="polite">{{ page.page + 1 }}페이지</span>
            <button type="button" aria-label="다음 작업 페이지" :disabled="!page.hasNext || page.page >= 10000" @click="loadPage(page.page + 1)">다음 페이지</button>
          </nav>
        </template>
      </section>
      <section class="workspace-panel project-detail task-detail" aria-labelledby="task-detail-title" :aria-busy="detailLoading">
        <h4 id="task-detail-title">작업 정보</h4>
        <p v-if="detailLoading" role="status">작업 정보를 불러오고 있어요.</p>
        <div v-else-if="detailError" role="alert">
          <p>{{ detailError }}</p>
          <button v-if="selectedId" type="button" @click="select(selectedId)">작업 상세 다시 시도</button>
        </div>
        <template v-else-if="detail">
          <h5>{{ detail.title }}</h5>
          <p>상태: {{ statusLabels[detail.status] }} · 우선순위: {{ priorityLabels[detail.priority] }}</p>
          <p>담당자 ID: {{ detail.assigneeId ?? '미지정' }} · 마감일: {{ detail.dueDate ?? '미지정' }}</p>
          <p v-if="detail.description !== null" class="project-description task-description">{{ detail.description }}</p>
          <p v-else>등록된 설명이 없습니다.</p>
          <TaskUpdateForm v-if="canWrite" :key="detail.id" :task="detail" :my-role="myRole" @updated="updated" @refreshed="updated" />
          <TaskStatusForm v-if="canWrite" :key="'status:' + detail.id" :task="detail" :my-role="myRole" @updated="updated" @refreshed="updated" />
          <TaskStatusHistory :key="'history:' + detail.id" :workspace-id="detail.workspaceId" :project-id="detail.projectId" :task-id="detail.id" :task-status="detail.status" />
        </template>
        <p v-else class="workspace-hint">목록에서 작업을 선택해 주세요.</p>
      </section>
    </div>
  </section>
</template>
