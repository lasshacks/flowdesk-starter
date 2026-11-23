<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { getHealth, type HealthResponse } from '../api/health'

const health = ref<HealthResponse | null>(null)
const isLoading = ref(false)
const error = ref<string | null>(null)
const checkedAt = ref<string | null>(null)

const isUp = computed(() => health.value?.status === 'UP')
const statusLabel = computed(() => {
  if (isLoading.value) return '연결 확인 중'
  if (error.value) return '연결 확인 실패'
  return isUp.value ? '서버 연결 정상' : '서버 상태 확인 필요'
})

async function checkHealth() {
  if (isLoading.value) return

  isLoading.value = true
  health.value = null
  error.value = null
  checkedAt.value = null

  try {
    health.value = await getHealth()
    checkedAt.value = new Date().toLocaleTimeString('ko-KR')
  } catch (cause) {
    error.value = cause instanceof Error && !['TypeError', 'TimeoutError', 'AbortError'].includes(cause.name)
      ? cause.message
      : '서버에 연결하지 못했습니다. 서버 실행 상태를 확인한 뒤 다시 시도해 주세요.'
  } finally {
    isLoading.value = false
  }
}

onMounted(checkHealth)
</script>

<template>
  <section class="health-card" aria-labelledby="health-title" :aria-busy="isLoading">
    <div class="card-heading">
      <div>
        <p class="eyebrow">연결 상태</p>
        <h2 id="health-title">서버 상태 확인</h2>
      </div>
      <span class="status-dot" :class="{ online: isUp, failed: error }" aria-hidden="true"></span>
    </div>

    <div class="status-panel" role="status" aria-live="polite" aria-atomic="true">
      <p class="status-label">{{ statusLabel }}</p>
      <p v-if="isLoading" class="status-description">서버의 응답을 기다리고 있어요.</p>
      <p v-else-if="error" class="status-description">{{ error }}</p>
      <template v-else-if="health">
        <p class="server-status" :class="{ 'is-up': isUp }">{{ health.status }}</p>
        <p class="status-description">{{ isUp ? '서버가 요청에 정상적으로 응답했어요.' : '서버가 반환한 상태를 확인해 주세요.' }}</p>
      </template>
    </div>

    <div class="card-actions">
      <span class="checked-at">{{ checkedAt ? `마지막 확인 ${checkedAt}` : '연결 상태를 확인해 주세요.' }}</span>
      <button type="button" :disabled="isLoading" @click="checkHealth">
        {{ isLoading ? '확인 중…' : '다시 확인' }}
      </button>
    </div>
  </section>
</template>
