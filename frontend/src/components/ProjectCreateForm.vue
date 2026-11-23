<script setup lang="ts">
import { computed, nextTick, onUnmounted, ref } from 'vue'
import { createProject, normalizeProjectName, normalizeProjectDescription, ProjectApiError, type Project } from '../api/projects'

const props = defineProps<{ workspaceId: string }>()
const emit = defineEmits<{ created: [project: Project]; checkList: [] }>()
const name = ref('')
const description = ref('')
const nameInput = ref<HTMLInputElement | null>(null)
const descriptionInput = ref<HTMLTextAreaElement | null>(null)
const checked = ref(false)
const submitting = ref(false)
const uncertain = ref(false)
const error = ref<string | null>(null)
const createdName = ref<string | null>(null)
let request: AbortController | undefined

const validName = computed(() => {
  try { return normalizeProjectName(name.value) } catch { return null }
})
const validDescription = computed(() => {
  try { return { value: normalizeProjectDescription(description.value) } } catch { return null }
})

function rejectedMessage(cause: ProjectApiError): string {
  if (cause.status === 400) return '프로젝트 이름과 설명을 확인한 뒤 다시 요청해 주세요.'
  if ([403, 404, 405].includes(cause.status)) return '현재는 이 공간에 프로젝트를 만들 수 없습니다. 목록 조회는 계속 이용할 수 있어요.'
  return '생성 요청을 처리할 수 없습니다. 입력 내용을 확인해 주세요.'
}

async function submit() {
  if (submitting.value || uncertain.value) return
  checked.value = true
  error.value = null
  createdName.value = null
  if (validName.value === null) { nameInput.value?.focus(); return }
  if (validDescription.value === null) { descriptionInput.value?.focus(); return }
  submitting.value = true
  const controller = new AbortController()
  request = controller
  try {
    const result = await createProject(props.workspaceId, validName.value, validDescription.value.value, controller.signal)
    if (controller.signal.aborted) return
    createdName.value = result.name
    name.value = ''; description.value = ''; checked.value = false
    emit('created', result)
  } catch (cause) {
    if (controller.signal.aborted) return
    if (cause instanceof ProjectApiError) error.value = rejectedMessage(cause)
    else uncertain.value = true
  } finally {
    if (!controller.signal.aborted) submitting.value = false
  }
}

async function reset() {
  name.value = ''; description.value = ''; checked.value = false
  uncertain.value = false; error.value = null; createdName.value = null
  await nextTick(); nameInput.value?.focus()
}
onUnmounted(() => request?.abort())
</script>

<template>
  <form class="project-create" aria-labelledby="project-create-title" :aria-busy="submitting" novalidate @submit.prevent="submit">
    <h4 id="project-create-title">새 프로젝트</h4>
    <p class="project-form-help">이 공간에 프로젝트를 만듭니다. 이름은 중복될 수 있어요.</p>
    <label for="project-name">프로젝트 이름</label>
    <input id="project-name" ref="nameInput" v-model="name" type="text" required autocomplete="off"
      :disabled="submitting || uncertain" :aria-invalid="checked && validName === null" aria-describedby="project-name-help project-name-error"
      placeholder="예: 첫 번째 출시" />
    <p id="project-name-help" class="project-form-help">앞뒤 공백을 제외한 1~100자로 입력해 주세요.</p>
    <p v-if="checked && validName === null" id="project-name-error" class="form-error" role="alert">프로젝트 이름은 1~100자로 입력하고 사용할 수 없는 문자는 제외해 주세요.</p>
    <label for="project-description">설명 <span class="project-form-help">(선택)</span></label>
    <textarea id="project-description" ref="descriptionInput" v-model="description" rows="4"
      :disabled="submitting || uncertain" :aria-invalid="checked && validDescription === null" aria-describedby="project-description-help project-description-error"
      placeholder="프로젝트의 목표를 적어 주세요." />
    <p id="project-description-help" class="project-form-help">최대 2,000자. 줄바꿈을 포함한 일반 텍스트로 저장합니다.</p>
    <p v-if="checked && validDescription === null" id="project-description-error" class="form-error" role="alert">설명은 2,000자 이내로 입력하고 사용할 수 없는 문자는 제외해 주세요.</p>
    <button type="submit" :disabled="submitting || uncertain">{{ submitting ? '프로젝트 만드는 중…' : '프로젝트 만들기' }}</button>
    <p v-if="error" class="form-error" role="alert">{{ error }}</p>
    <div v-if="uncertain" class="creation-uncertain" role="alert">
      <p>생성 결과를 확인하지 못했습니다. 이미 생성됐을 수 있으니 목록을 확인해 주세요. 다시 보내면 같은 이름의 프로젝트가 추가될 수 있습니다.</p>
      <div class="creation-recovery">
        <button type="button" @click="emit('checkList')">프로젝트 목록 확인</button>
        <button type="button" class="secondary-button" @click="reset">프로젝트 입력 초기화</button>
      </div>
    </div>
    <p v-if="createdName" class="creation-success" role="status">“{{ createdName }}” 프로젝트를 만들었습니다.</p>
  </form>
</template>
