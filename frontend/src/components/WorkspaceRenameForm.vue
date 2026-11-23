<script setup lang="ts">
import { computed, onUnmounted, ref } from 'vue'
import { getWorkspace, normalizeWorkspaceName, renameWorkspace, WorkspaceApiError, type Workspace } from '../api/workspaces'

const props = defineProps<{ workspace: Workspace }>()
const emit = defineEmits<{ renamed: [workspace: Workspace]; refreshed: [workspace: Workspace] }>()
// Freeze the edit basis: reading a newer list must not silently replace the request version.
const base = ref({ ...props.workspace })
const name = ref(base.value.name)
const checked = ref(false)
const submitting = ref(false)
const refreshing = ref(false)
const blocked = ref<'conflict' | 'uncertain' | null>(null)
const latest = ref<Workspace | null>(null)
const error = ref<string | null>(null)
const recoveryError = ref<string | null>(null)
const saved = ref(false)
let writeRequest: AbortController | undefined
let readRequest: AbortController | undefined
const canEdit = computed(() => ['OWNER', 'ADMIN'].includes(base.value.myRole))
const canUseLatest = computed(() => latest.value !== null && ['OWNER', 'ADMIN'].includes(latest.value.myRole))
const validName = computed(() => { try { return normalizeWorkspaceName(name.value) } catch { return null } })
const invalidName = computed(() => checked.value && validName.value === null)

function rejectedMessage(cause: WorkspaceApiError) {
  if (cause.status === 403) return '이름을 수정할 권한이 없거나 현재 요청이 허용되지 않습니다.'
  if (cause.status === 404) return '워크스페이스를 찾을 수 없거나 접근할 수 없습니다.'
  if (cause.status === 405) return '현재는 이름을 수정할 수 없습니다. 조회는 계속 이용할 수 있어요.'
  if (cause.status === 413) return '입력 내용이 너무 큽니다. 이름을 짧게 입력해 주세요.'
  return '수정 요청을 처리할 수 없습니다. 입력 내용을 확인해 주세요.'
}
async function submit() {
  if (!canEdit.value || submitting.value || refreshing.value || blocked.value) return
  checked.value = true; error.value = null; saved.value = false
  if (validName.value === null) return
  submitting.value = true
  const controller = new AbortController(); writeRequest = controller
  try {
    const result = await renameWorkspace(base.value, validName.value, controller.signal)
    if (controller.signal.aborted) return
    base.value = result; name.value = result.name; checked.value = false; saved.value = true
    emit('renamed', result)
  } catch (cause) {
    if (controller.signal.aborted) return
    if (cause instanceof WorkspaceApiError && cause.status === 409) blocked.value = 'conflict'
    else if (cause instanceof WorkspaceApiError) error.value = rejectedMessage(cause)
    else blocked.value = 'uncertain'
  } finally { if (!controller.signal.aborted) submitting.value = false }
}
async function refresh() {
  if (submitting.value || refreshing.value || !blocked.value) return
  refreshing.value = true; recoveryError.value = null
  const controller = new AbortController(); readRequest = controller
  try {
    const result = await getWorkspace(base.value.id, controller.signal)
    if (controller.signal.aborted) return
    latest.value = result
    emit('refreshed', result)
  } catch (cause) {
    if (controller.signal.aborted) return
    // An earlier successful read must not become a fallback after a newer read failed.
    latest.value = null
    recoveryError.value = cause instanceof WorkspaceApiError && cause.status === 404
      ? '현재 정보를 찾을 수 없거나 접근할 수 없습니다. 목록에서 다시 확인해 주세요.'
      : '최신 정보를 불러오지 못했습니다. 입력은 유지됩니다. 다시 확인해 주세요.'
  } finally { if (!controller.signal.aborted) refreshing.value = false }
}
function useLatest() {
  if (submitting.value || refreshing.value || !blocked.value || !canUseLatest.value) return
  base.value = { ...latest.value! }
  // Keep the user's draft; adopting a version never sends a write request.
  latest.value = null; blocked.value = null; recoveryError.value = null; error.value = null; saved.value = false
}
onUnmounted(() => { writeRequest?.abort(); readRequest?.abort() })
</script>

<template>
  <form v-if="canEdit" class="workspace-rename" aria-labelledby="workspace-rename-title" :aria-busy="submitting || refreshing" novalidate @submit.prevent="submit">
    <h5 id="workspace-rename-title">공간 이름 수정</h5>
    <label for="workspace-rename-name">새 이름</label>
    <div class="workspace-rename-fields">
      <input id="workspace-rename-name" v-model="name" type="text" required autocomplete="off"
        :disabled="submitting || refreshing" :aria-invalid="invalidName" aria-describedby="workspace-rename-help" @input="saved = false" />
      <button type="submit" :disabled="submitting || refreshing || !!blocked">{{ submitting ? '저장 중…' : '이름 저장' }}</button>
    </div>
    <p id="workspace-rename-help" class="rename-help">앞뒤 공백을 제외한 1~100자로 입력해 주세요.</p>
    <p v-if="invalidName" class="form-error" role="alert">이름은 1~100자로 입력하고 사용할 수 없는 문자는 제외해 주세요.</p>
    <p v-if="error" class="form-error" role="alert">{{ error }}</p>
    <div v-if="blocked" class="rename-recovery" role="alert">
      <p v-if="blocked === 'conflict'">다른 변경이 먼저 저장됐습니다. 입력은 유지됩니다. 최신 정보를 확인한 뒤 다시 편집해 주세요.</p>
      <p v-else>수정 결과를 확인하지 못했습니다. 이미 저장됐을 수 있으니 최신 정보를 확인해 주세요. 자동으로 다시 보내지 않습니다.</p>
      <button type="button" class="secondary-button" :disabled="refreshing" @click="refresh">{{ refreshing ? '확인 중…' : '최신 정보 확인' }}</button>
      <p v-if="recoveryError" class="form-error">{{ recoveryError }}</p>
      <template v-if="latest">
        <p class="latest-name">현재 서버 이름: <strong>{{ latest.name }}</strong></p>
        <p v-if="!canUseLatest">현재는 이름을 수정할 권한이 없습니다. 목록에서 다시 확인해 주세요.</p>
        <button v-else type="button" :disabled="refreshing" @click="useLatest">최신 정보 기준으로 다시 편집</button>
      </template>
    </div>
    <p v-if="saved" class="rename-success" role="status">이름 저장을 확인했습니다.</p>
  </form>
  <p v-else class="rename-help">이름 수정은 소유자와 관리자만 할 수 있습니다.</p>
</template>
