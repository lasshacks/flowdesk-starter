<script setup lang="ts">
import { computed, nextTick, onUnmounted, ref } from 'vue'
import { createWorkspace, normalizeWorkspaceName, WorkspaceApiError, type Workspace } from '../api/workspaces'

const emit = defineEmits<{ created: [workspace: Workspace]; checkList: [] }>()
const name = ref('')
const input = ref<HTMLInputElement | null>(null)
const checked = ref(false)
const submitting = ref(false)
const uncertain = ref(false)
const error = ref<string | null>(null)
const createdName = ref<string | null>(null)
let request: AbortController | undefined

const validName = computed(() => {
  try { return normalizeWorkspaceName(name.value) } catch { return null }
})
const invalidName = computed(() => checked.value && validName.value === null)

function rejectedMessage(cause: WorkspaceApiError): string {
  if (cause.status === 400) return '공간 이름을 확인한 뒤 다시 요청해 주세요.'
  if ([403, 404, 405].includes(cause.status)) return '현재는 공간을 만들 수 없습니다. 목록 조회는 계속 이용할 수 있어요.'
  return '생성 요청을 처리할 수 없습니다. 입력 내용을 확인해 주세요.'
}

async function submit() {
  if (submitting.value || uncertain.value) return
  checked.value = true
  error.value = null
  createdName.value = null
  if (validName.value === null) { input.value?.focus(); return }
  submitting.value = true
  const controller = new AbortController()
  request = controller
  try {
    const result = await createWorkspace(validName.value, controller.signal)
    if (controller.signal.aborted) return
    createdName.value = result.name
    name.value = ''
    checked.value = false
    emit('created', result)
  } catch (cause) {
    if (controller.signal.aborted) return
    if (cause instanceof WorkspaceApiError) error.value = rejectedMessage(cause)
    else uncertain.value = true
  } finally {
    if (!controller.signal.aborted) submitting.value = false
  }
}

async function reset() {
  name.value = ''; checked.value = false; uncertain.value = false; error.value = null; createdName.value = null
  await nextTick(); input.value?.focus()
}
onUnmounted(() => request?.abort())
</script>

<template>
  <form class="workspace-create" aria-labelledby="workspace-create-title" :aria-busy="submitting" novalidate @submit.prevent="submit">
    <div>
      <h3 id="workspace-create-title">새 워크스페이스</h3>
      <p id="workspace-name-help" class="workspace-hint">이름을 입력하면 내가 소유자인 공간이 만들어집니다. 이름은 중복될 수 있어요.</p>
    </div>
    <label for="workspace-name">공간 이름</label>
    <div class="workspace-create-fields">
      <input id="workspace-name" ref="input" v-model="name" type="text" required autocomplete="off"
        :disabled="submitting || uncertain" :aria-invalid="invalidName" aria-describedby="workspace-name-help workspace-name-error"
        placeholder="예: 디자인 팀" />
      <button type="submit" :disabled="submitting || uncertain">{{ submitting ? '만드는 중…' : '공간 만들기' }}</button>
    </div>
    <p v-if="invalidName" id="workspace-name-error" class="form-error" role="alert">이름은 앞뒤 공백을 제외한 1~100자로 입력해 주세요. 사용할 수 없는 문자는 제외해 주세요.</p>
    <p v-if="error" class="form-error" role="alert">{{ error }}</p>
    <div v-if="uncertain" class="creation-uncertain" role="alert">
      <p>생성 결과를 확인하지 못했습니다. 이미 생성됐을 수 있으니 목록을 확인해 주세요. 다시 보내면 같은 이름의 공간이 추가될 수 있습니다.</p>
      <div class="creation-recovery">
        <button type="button" @click="emit('checkList')">목록 확인</button>
        <button type="button" class="secondary-button" @click="reset">입력 초기화</button>
      </div>
    </div>
    <p v-if="createdName" class="creation-success" role="status">“{{ createdName }}” 워크스페이스를 만들었습니다.</p>
  </form>
</template>
