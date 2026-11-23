<script setup lang="ts">
import { computed, onUnmounted, ref } from 'vue'
import { getProject, normalizeProjectName, normalizeProjectDescription, updateProject, ProjectApiError, type Project } from '../api/projects'
import { getWorkspace, type WorkspaceRole } from '../api/workspaces'

const props = defineProps<{ project: Project; myRole?: WorkspaceRole }>()
const emit = defineEmits<{ updated: [project: Project]; refreshed: [project: Project] }>()
// Same-ID prop refreshes must never silently change an in-progress edit's version.
const base = ref({ ...props.project })
const name = ref(base.value.name)
const description = ref(base.value.description ?? '')
const checked = ref(false)
const submitting = ref(false)
const refreshing = ref(false)
const blocked = ref<'conflict' | 'uncertain' | null>(null)
const latest = ref<Project | null>(null)
const latestRole = ref<WorkspaceRole | null>(null)
const error = ref<string | null>(null)
const recoveryError = ref<string | null>(null)
const saved = ref(false)
let writeRequest: AbortController | undefined
let readRequest: AbortController | undefined
const canEdit = computed(() => props.myRole === 'OWNER' || props.myRole === 'ADMIN')
const canUseLatest = computed(() => canEdit.value && latest.value !== null && ['OWNER', 'ADMIN'].includes(latestRole.value ?? ''))
const validName = computed(() => { try { return normalizeProjectName(name.value) } catch { return null } })
const validDescription = computed(() => {
  try { return { value: normalizeProjectDescription(description.value) } } catch { return null }
})

function rejectedMessage(cause: ProjectApiError) {
  if (cause.status === 403) return '프로젝트를 수정할 권한이 없거나 현재 요청이 허용되지 않습니다.'
  if (cause.status === 404) return '프로젝트 또는 공간을 찾을 수 없거나 접근할 수 없습니다.'
  if (cause.status === 405) return '현재는 프로젝트를 수정할 수 없습니다. 조회는 계속 이용할 수 있어요.'
  return '수정 요청을 처리할 수 없습니다. 이름과 설명을 확인해 주세요.'
}
async function submit() {
  if (!canEdit.value || submitting.value || refreshing.value || blocked.value) return
  checked.value = true; error.value = null; saved.value = false
  if (validName.value === null || validDescription.value === null) return
  submitting.value = true
  const controller = new AbortController(); writeRequest = controller
  try {
    const result = await updateProject(base.value, validName.value, validDescription.value.value, controller.signal)
    if (controller.signal.aborted) return
    base.value = result; name.value = result.name; description.value = result.description ?? ''
    checked.value = false; saved.value = true
    emit('updated', result)
  } catch (cause) {
    if (controller.signal.aborted) return
    if (cause instanceof ProjectApiError && cause.status === 409) blocked.value = 'conflict'
    else if (cause instanceof ProjectApiError) error.value = rejectedMessage(cause)
    else blocked.value = 'uncertain'
  } finally { if (!controller.signal.aborted) submitting.value = false }
}
async function refresh() {
  if (submitting.value || refreshing.value || !blocked.value) return
  refreshing.value = true; recoveryError.value = null; latest.value = null; latestRole.value = null
  const controller = new AbortController(); readRequest = controller
  try {
    const [project, workspace] = await Promise.all([
      getProject(base.value.workspaceId, base.value.id, controller.signal),
      getWorkspace(base.value.workspaceId, controller.signal),
    ])
    if (controller.signal.aborted) return
    latest.value = project; latestRole.value = workspace.myRole
    emit('refreshed', project)
  } catch {
    if (controller.signal.aborted) return
    recoveryError.value = '최신 정보와 수정 권한을 확인하지 못했습니다. 입력과 저장 잠금은 유지됩니다. 다시 확인해 주세요.'
  } finally { if (!controller.signal.aborted) refreshing.value = false }
}
function useLatest() {
  if (submitting.value || refreshing.value || !blocked.value || !canUseLatest.value) return
  // Adopting the displayed two-field snapshot keeps the draft and never sends PATCH.
  base.value = { ...latest.value! }
  latest.value = null; latestRole.value = null; blocked.value = null
  recoveryError.value = null; error.value = null; saved.value = false
}
onUnmounted(() => { writeRequest?.abort(); readRequest?.abort() })
</script>

<template>
  <form v-if="canEdit" class="project-update" aria-labelledby="project-update-title" :aria-busy="submitting || refreshing" novalidate @submit.prevent="submit">
    <h5 id="project-update-title">프로젝트 수정</h5>
    <label for="project-update-name">프로젝트 이름 수정</label>
    <input id="project-update-name" v-model="name" type="text" required autocomplete="off"
      :disabled="submitting || refreshing" :aria-invalid="checked && validName === null" aria-describedby="project-update-name-help" @input="saved = false" />
    <p id="project-update-name-help" class="project-form-help">앞뒤 공백을 제외한 1~100자로 입력해 주세요.</p>
    <p v-if="checked && validName === null" class="form-error" role="alert">이름은 1~100자로 입력하고 사용할 수 없는 문자는 제외해 주세요.</p>
    <label for="project-update-description">프로젝트 설명 수정 <span class="project-form-help">(선택)</span></label>
    <textarea id="project-update-description" v-model="description" rows="4" :disabled="submitting || refreshing"
      :aria-invalid="checked && validDescription === null" aria-describedby="project-update-description-help" @input="saved = false" />
    <p id="project-update-description-help" class="project-form-help">최대 2,000자. 비우면 기존 설명을 삭제합니다. 이름과 설명을 함께 저장합니다.</p>
    <p v-if="checked && validDescription === null" class="form-error" role="alert">설명은 2,000자 이내로 입력하고 사용할 수 없는 문자는 제외해 주세요.</p>
    <button type="submit" :disabled="submitting || refreshing || !!blocked">{{ submitting ? '프로젝트 저장 중…' : '프로젝트 변경 저장' }}</button>
    <p v-if="error" class="form-error" role="alert">{{ error }}</p>
    <div v-if="blocked" class="rename-recovery project-update-recovery" role="alert">
      <p v-if="blocked === 'conflict'">다른 변경이 먼저 저장됐습니다. 입력은 유지됩니다. 최신 이름과 설명을 비교한 뒤 다시 편집해 주세요.</p>
      <p v-else>수정 결과를 확인하지 못했습니다. 이미 저장됐을 수 있습니다. 자동으로 다시 보내지 않습니다. 최신 정보와 입력을 비교해 주세요.</p>
      <button type="button" :disabled="refreshing" @click="refresh">{{ refreshing ? '프로젝트 확인 중…' : '프로젝트 최신 정보 확인' }}</button>
      <p v-if="recoveryError" class="form-error">{{ recoveryError }}</p>
      <template v-if="latest">
        <dl class="project-comparison">
          <dt>현재 서버 이름</dt><dd>{{ latest.name }}</dd>
          <dt>내가 입력한 이름</dt><dd>{{ name }}</dd>
          <dt>현재 서버 설명</dt><dd>{{ latest.description ?? '등록된 설명이 없습니다.' }}</dd>
          <dt>내가 입력한 설명</dt><dd>{{ description || '설명 삭제' }}</dd>
        </dl>
        <p v-if="!canUseLatest">현재는 프로젝트를 수정할 권한이 없습니다. 목록에서 다시 확인해 주세요.</p>
        <template v-else>
          <p>두 필드를 모두 비교해 주세요. 최신 기준을 선택해도 입력은 유지되며, 별도로 저장해야 합니다.</p>
          <button type="button" :disabled="refreshing" @click="useLatest">프로젝트 최신 기준으로 다시 편집</button>
        </template>
      </template>
    </div>
    <p v-if="saved" class="rename-success" role="status">프로젝트 변경 저장을 확인했습니다.</p>
  </form>
</template>
