<script setup lang="ts">
import HealthStatusCard from './components/HealthStatusCard.vue'
import WorkspaceBrowser from './components/WorkspaceBrowser.vue'
</script>

<template>
  <main class="page">
    <header class="page-header">
      <a class="brand" href="/" aria-label="FlowDesk 홈">FlowDesk<span>.</span></a>
      <span class="environment">로컬 개발환경</span>
    </header>

    <section class="intro" aria-labelledby="page-title">
      <p class="eyebrow">함께 만드는 작업 공간</p>
      <h1 id="page-title">우리의 작업 공간을 만나보세요.</h1>
      <p class="description">참여한 워크스페이스를 선택해 공간 정보와 내 역할을 확인하세요.</p>
    </section>

    <WorkspaceBrowser />
    <details class="connection-details">
      <summary>서버 연결 상태</summary>
      <HealthStatusCard />
    </details>
    <footer>FlowDesk · 팀의 일을 한곳에서</footer>
  </main>
</template>
