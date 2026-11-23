<script setup lang="ts">
import { onUnmounted, ref, watch } from 'vue'
import { getTaskStatusHistory, TASK_STATUS_HISTORY_PAGE_SIZE, TaskApiError, type TaskStatus, type TaskStatusActivity } from '../api/tasks'

const props = defineProps<{
  workspaceId: string
  projectId: string
  taskId: string
  taskStatus: TaskStatus
}>()

const pageSize = TASK_STATUS_HISTORY_PAGE_SIZE
const expanded = ref(false)
const items = ref<TaskStatusActivity[]>([])
const nextBeforeVersion = ref<string | null>(null)
const hasNext = ref(false)
const loading = ref(false)
const loadingMore = ref(false)
const initialError = ref<string | null>(null)
const moreError = ref<string | null>(null)
const hasRequested = ref(false)
const needsRefresh = ref(false)
let request: AbortController | undefined
let generation = 0

const historyId = `task-status-history-${props.workspaceId}-${props.projectId}-${props.taskId}`
const statusLabels: Record<TaskStatus, string> = { TODO: '할 일', IN_PROGRESS: '진행 중', DONE: '완료' }
const timeFormatter = new Intl.DateTimeFormat('ko-KR', { dateStyle: 'medium', timeStyle: 'short' })

function errorMessage(cause: unknown): string {
  if (!(cause instanceof TaskApiError)) return '서버에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요.'
  if (cause.status === 404) return '작업을 찾을 수 없거나 접근할 수 없습니다. 목록에서 다시 선택해 주세요.'
  if (cause.status === 503 || cause.code === 'SERVICE_UNAVAILABLE') return '일시적으로 서비스를 이용할 수 없습니다. 잠시 후 다시 시도해 주세요.'
  if (cause.code === 'INVALID_RESPONSE') return '이력 응답을 확인할 수 없습니다. 다시 시도해 주세요.'
  return '상태 변경 이력을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.'
}

async function loadLatest() {
  generation++
  const currentGeneration = generation
  request?.abort()
  const controller = new AbortController()
  request = controller
  hasRequested.value = true
  needsRefresh.value = false
  loading.value = true
  loadingMore.value = false
  initialError.value = null
  moreError.value = null
  items.value = []
  nextBeforeVersion.value = null
  hasNext.value = false
  try {
    const result = await getTaskStatusHistory(props.workspaceId, props.projectId, props.taskId, null, pageSize, controller.signal)
    if (!controller.signal.aborted && currentGeneration === generation) {
      items.value = result.items
      nextBeforeVersion.value = result.nextBeforeVersion
      hasNext.value = result.hasNext
    }
  } catch (cause) {
    if (!controller.signal.aborted && currentGeneration === generation) initialError.value = errorMessage(cause)
  } finally {
    if (!controller.signal.aborted && currentGeneration === generation) loading.value = false
  }
}

async function loadMore() {
  const cursor = nextBeforeVersion.value
  if (!hasNext.value || cursor === null || loading.value || loadingMore.value) return
  const currentGeneration = generation
  const controller = new AbortController()
  request?.abort()
  request = controller
  loadingMore.value = true
  moreError.value = null
  try {
    const result = await getTaskStatusHistory(props.workspaceId, props.projectId, props.taskId, cursor, pageSize, controller.signal)
    if (!controller.signal.aborted && currentGeneration === generation) {
      items.value = [...items.value, ...result.items]
      nextBeforeVersion.value = result.nextBeforeVersion
      hasNext.value = result.hasNext
    }
  } catch (cause) {
    if (!controller.signal.aborted && currentGeneration === generation) moreError.value = errorMessage(cause)
  } finally {
    if (!controller.signal.aborted && currentGeneration === generation) loadingMore.value = false
  }
}

function toggle() {
  expanded.value = !expanded.value
  if (expanded.value && (!hasRequested.value || needsRefresh.value)) void loadLatest()
}

function formatTime(value: string): string {
  return timeFormatter.format(new Date(value))
}

watch(() => props.taskStatus, (status, previous) => {
  if (status === previous || !hasRequested.value) return
  if (expanded.value) void loadLatest()
  else needsRefresh.value = true
})

onUnmounted(() => { generation++; request?.abort() })
</script>

<template>
  <div class="task-history">
    <button
      type="button"
      class="secondary-button task-history-toggle"
      :aria-expanded="expanded"
      :aria-controls="historyId"
      @click="toggle"
    >
      {{ expanded ? '상태 변경 이력 숨기기' : '상태 변경 이력 보기' }}
    </button>
    <section :id="historyId" v-show="expanded" class="task-history-content" :aria-labelledby="`${historyId}-title`" :aria-busy="loading || loadingMore">
      <div class="task-history-heading">
        <h6 :id="`${historyId}-title`">상태 변경 이력</h6>
        <button type="button" class="secondary-button task-history-refresh" :disabled="loading || loadingMore" @click="loadLatest">
          {{ loading ? '최신 이력 불러오는 중…' : '최신 이력 새로고침' }}
        </button>
      </div>
      <p v-if="loading" role="status">상태 변경 이력을 불러오고 있어요.</p>
      <div v-else-if="initialError" role="alert">
        <p>{{ initialError }}</p>
        <button type="button" class="secondary-button" @click="loadLatest">이력 다시 시도</button>
      </div>
      <p v-else-if="items.length === 0" role="status">등록된 상태 변경 이력이 없습니다.</p>
      <ol v-else class="task-history-list">
        <li v-for="item in items" :key="item.id" class="task-history-item">
          <p class="task-history-transition">
            {{ item.actor.displayName }} · {{ statusLabels[item.fromStatus] }} → {{ statusLabels[item.toStatus] }}
          </p>
          <p class="task-history-meta">
            <time :datetime="item.occurredAt">{{ formatTime(item.occurredAt) }}</time>
            <span>작업 버전 {{ item.taskVersion }}</span>
          </p>
        </li>
      </ol>
      <p v-if="moreError" class="form-error" role="alert">{{ moreError }}</p>
      <button v-if="hasNext" type="button" class="secondary-button task-history-more" :disabled="loadingMore" @click="loadMore">
        {{ loadingMore ? '이전 이력 불러오는 중…' : moreError ? '이전 이력 다시 시도' : '이전 이력 더 보기' }}
      </button>
    </section>
  </div>
</template>
