<script setup lang="ts">
import { onUnmounted, ref, watch } from 'vue'
import { getWorkspaceMembers, WorkspaceApiError, type WorkspaceMember, type WorkspaceRole } from '../api/workspaces'

const props = defineProps<{
  id: string
  workspaceId: string
  modelValue: string
  existingAssigneeId?: string
  disabled?: boolean
}>()
const emit = defineEmits<{ 'update:modelValue': [value: string] }>()
const members = ref<WorkspaceMember[]>([])
const loading = ref(false)
const loaded = ref(false)
const loadError = ref<string | null>(null)
const roleLabels: Record<WorkspaceRole, string> = { OWNER: '소유자', ADMIN: '관리자', MEMBER: '멤버', VIEWER: '뷰어' }
let request: AbortController | undefined
let generation = 0

function errorMessage(cause: unknown): string {
  if (cause instanceof WorkspaceApiError && cause.status === 404) return 'Workspace 멤버 목록을 볼 수 없습니다.'
  if (cause instanceof WorkspaceApiError && cause.status === 503) return '멤버 목록 서비스가 일시적으로 중단됐습니다.'
  if (cause instanceof WorkspaceApiError && cause.code === 'INVALID_RESPONSE') return '멤버 목록 응답을 확인할 수 없습니다.'
  return '멤버 목록을 불러오지 못했습니다.'
}

async function loadMembers() {
  if (loaded.value || loading.value) return
  request?.abort()
  const controller = new AbortController()
  request = controller
  const current = ++generation
  loading.value = true
  loadError.value = null
  const result: WorkspaceMember[] = []
  try {
    for (let page = 0; page <= 10000; page++) {
      const slice = await getWorkspaceMembers(props.workspaceId, page, controller.signal)
      result.push(...slice.items)
      if (!slice.hasNext) break
      if (page === 10000) throw new WorkspaceApiError('INVALID_RESPONSE')
    }
    if (controller.signal.aborted || current !== generation) return
    if (new Set(result.map(member => member.id)).size !== result.length) throw new WorkspaceApiError('INVALID_RESPONSE')
    members.value = result
    loaded.value = true
  } catch (cause) {
    if (!controller.signal.aborted && current === generation) loadError.value = errorMessage(cause)
  } finally {
    if (!controller.signal.aborted && current === generation) loading.value = false
  }
}

watch(() => props.workspaceId, () => {
  generation++
  request?.abort()
  members.value = []
  loaded.value = false
  loading.value = false
  loadError.value = null
}, { flush: 'sync' })
onUnmounted(() => { generation++; request?.abort() })

function update(event: Event) {
  emit('update:modelValue', (event.target as HTMLSelectElement).value)
}
</script>

<template>
  <div class="task-assignee-field">
    <label :for="id">담당자 <span class="project-form-help">(선택)</span></label>
    <select :id="id" :value="modelValue" :disabled="disabled || loading" :aria-describedby="`${id}-help ${id}-status`" @focus="loadMembers" @change="update">
      <option value="">미지정</option>
      <option v-if="existingAssigneeId && !members.some(member => member.id === existingAssigneeId)" :value="existingAssigneeId">
        {{ loaded ? `기존 담당자 ID ${existingAssigneeId} · 현재 Workspace 멤버 아님 (유지 또는 해제 가능)` : `기존 담당자 ID ${existingAssigneeId} · 멤버 목록 확인 전` }}
      </option>
      <option v-for="member in members" :key="member.id" :value="member.id">
        {{ member.displayName }} · {{ roleLabels[member.role] }} (ID {{ member.id }})
      </option>
    </select>
    <p :id="`${id}-help`" class="project-form-help">선택할 후보를 불러오려면 목록을 열어 주세요. 현재 Workspace 멤버에게만 새로 지정할 수 있습니다.</p>
    <p :id="`${id}-status`" class="project-form-help" aria-live="polite">
      <span v-if="loading" role="status">Workspace 멤버를 불러오는 중…</span>
      <template v-else-if="loadError">
        <span role="alert">{{ loadError }}</span>
        <button type="button" class="task-member-retry" :disabled="disabled" @click="loadMembers">멤버 목록 다시 시도</button>
      </template>
      <span v-else-if="loaded">{{ members.length }}명의 현재 Workspace 멤버</span>
    </p>
  </div>
</template>
