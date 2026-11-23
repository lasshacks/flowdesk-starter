<script setup lang="ts">
import { onUnmounted, ref } from 'vue'
import { acceptWorkspaceInvitation, WorkspaceMembershipUncertainError } from '../api/workspace-membership'
import { WorkspaceApiError } from '../api/workspaces'

const emit = defineEmits<{ accepted: [workspaceId: string] }>()
const code = ref('')
const busy = ref(false)
const uncertain = ref(false)
const error = ref<string | null>(null)
const success = ref<string | null>(null)
let request: AbortController | undefined

function message(cause: unknown): string {
  if (cause instanceof WorkspaceMembershipUncertainError) return '수락 결과를 확인할 수 없습니다. 자동으로 다시 보내지 않았습니다. 멤버 목록을 확인한 뒤 재시도 여부를 결정해 주세요.'
  if (cause instanceof WorkspaceApiError && cause.status === 404) return '초대가 없거나 만료·취소·사용됐거나, 현재 서버 행위자가 초대 대상이 아닙니다.'
  if (cause instanceof WorkspaceApiError && cause.status === 409) return '이미 참여 중이거나 초대 상태가 바뀌었습니다. 워크스페이스 목록을 새로 확인해 주세요.'
  if (cause instanceof WorkspaceApiError && cause.status === 403) return '현재 개발 실행에서 초대 수락 요청이 허용되지 않습니다.'
  if (cause instanceof WorkspaceApiError && cause.status === 400) return '초대 코드는 43자리 영문·숫자·-·_ 형식으로 입력해 주세요.'
  return '초대를 수락하지 못했습니다. 입력과 서버 상태를 확인해 주세요.'
}

async function submit() {
  if (busy.value || uncertain.value) return
  busy.value = true; error.value = null; success.value = null
  const controller = new AbortController(); request = controller
  try {
    const accepted = await acceptWorkspaceInvitation(code.value, controller.signal)
    if (controller.signal.aborted) return
    code.value = ''
    success.value = `${accepted.member.displayName} 계정이 워크스페이스에 참여했습니다.`
    emit('accepted', accepted.workspaceId)
  } catch (cause) {
    if (controller.signal.aborted) return
    if (cause instanceof WorkspaceMembershipUncertainError) uncertain.value = true
    error.value = message(cause)
  } finally { if (!controller.signal.aborted) busy.value = false }
}

onUnmounted(() => request?.abort())
</script>

<template>
  <section class="workspace-invitation-accept" aria-labelledby="invitation-accept-title" :aria-busy="busy">
    <h2 id="invitation-accept-title">초대 코드 수락</h2>
    <p class="workspace-hint">초대받은 계정으로 서버의 개발 행위자를 실행한 뒤 코드를 입력하세요. 코드는 한 번만 사용할 수 있습니다.</p>
    <form novalidate @submit.prevent="submit">
      <label for="workspace-invitation-code">초대 코드</label>
      <div class="workspace-invitation-fields">
        <input id="workspace-invitation-code" v-model="code" type="text" autocomplete="off" spellcheck="false"
          :disabled="busy || uncertain" :aria-invalid="!!error" aria-describedby="invitation-accept-help invitation-accept-error" />
        <button type="submit" :disabled="busy || uncertain || !code.trim()">{{ busy ? '수락 중…' : '초대 수락' }}</button>
      </div>
      <p id="invitation-accept-help" class="workspace-hint">초대 코드는 초대 발급 응답에만 표시됩니다. 실제 로그인 기능은 아직 제공하지 않습니다.</p>
      <p v-if="error" id="invitation-accept-error" class="form-error" role="alert">{{ error }}</p>
      <p v-if="success" class="membership-success" role="status">{{ success }}</p>
      <div v-if="uncertain" class="membership-recovery" role="alert">
        <p>수락이 처리됐는지 확정할 수 없습니다. 코드 자동 재전송은 중복 수락이나 상태 충돌을 만들 수 있습니다.</p>
        <button type="button" class="secondary-button" @click="uncertain = false; error = null">상태를 확인했고 다시 입력하기</button>
      </div>
    </form>
  </section>
</template>
