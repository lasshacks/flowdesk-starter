<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { getProject, getProjects, ProjectApiError, type Project, type ProjectPage } from '../api/projects'
import type { WorkspaceRole } from '../api/workspaces'
import ProjectCreateForm from './ProjectCreateForm.vue'
import ProjectUpdateForm from './ProjectUpdateForm.vue'
import ProjectArchiveControls from './ProjectArchiveControls.vue'
import TaskBrowser from './TaskBrowser.vue'

const props = defineProps<{ workspaceId: string; workspaceName: string; myRole?: WorkspaceRole }>()
const canCreate = computed(() => props.myRole === 'OWNER' || props.myRole === 'ADMIN')
const includeArchived = ref(false)
const page = ref<ProjectPage | null>(null)
const requestedPage = ref(0)
const listLoading = ref(false)
const listError = ref<string | null>(null)
const selectedId = ref<string | null>(null)
const detail = ref<Project | null>(null)
const detailLoading = ref(false)
const detailError = ref<string | null>(null)
let listRequest: AbortController | undefined
let detailRequest: AbortController | undefined
let navigationVersion = 0
let alive = true

function message(cause: unknown): string {
  if (!(cause instanceof ProjectApiError)) return '서버에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요.'
  if (cause.status === 404) return '프로젝트 또는 공간을 찾을 수 없거나 접근할 수 없습니다.'
  if (cause.status === 503 || cause.code === 'SERVICE_UNAVAILABLE') return '일시적으로 서비스를 이용할 수 없습니다. 잠시 후 다시 시도해 주세요.'
  if (cause.code === 'INVALID_RESPONSE') return '서버 응답을 확인할 수 없습니다. 다시 시도해 주세요.'
  return '프로젝트를 불러오지 못했습니다. 다시 시도해 주세요.'
}

function clearSelection() {
  detailRequest?.abort()
  selectedId.value = null
  detail.value = null
  detailError.value = null
  detailLoading.value = false
}

async function loadPage(number = requestedPage.value, preserveSelection = false) {
  const version = ++navigationVersion
  listRequest?.abort()
  if (!preserveSelection) clearSelection()
  const controller = new AbortController()
  listRequest = controller
  requestedPage.value = number
  page.value = null
  listError.value = null
  listLoading.value = true
  try {
    const result = await getProjects(props.workspaceId, number, controller.signal, includeArchived.value)
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
    const result = await getProject(props.workspaceId, id, controller.signal)
    if (!controller.signal.aborted) detail.value = result
  } catch (cause) {
    if (!controller.signal.aborted) detailError.value = message(cause)
  } finally {
    if (!controller.signal.aborted) detailLoading.value = false
  }
}

async function created(project: Project) {
  if (project.workspaceId !== props.workspaceId) return
  // A confirmed POST remains successful even if this following GET fails.
  if (await loadPage(0)) await select(project.id)
}

async function updated(project: Project) {
  if (!alive || project.workspaceId !== props.workspaceId || project.id !== selectedId.value) return
  const archiveChanged = detail.value?.archived !== project.archived
  detail.value = project
  if (archiveChanged) {
    await loadPage(requestedPage.value, true)
  } else if (page.value) {
    page.value = { ...page.value, items: page.value.items.map(item => item.id === project.id
      ? { ...item, name: project.name, archived: project.archived } : item) }
  }
}

// Parent keys this component by workspace ID: changing spaces resets pages, selection and requests.
onMounted(() => loadPage(0))
onUnmounted(() => { alive = false; navigationVersion++; listRequest?.abort(); detailRequest?.abort() })
</script>

<template>
  <section class="project-section" aria-labelledby="projects-title">
    <div class="card-heading project-heading">
      <div>
        <p class="eyebrow">{{ workspaceName }}의 프로젝트</p>
        <h3 id="projects-title">프로젝트</h3>
      </div>
      <button type="button" :disabled="listLoading" @click="loadPage()">프로젝트 새로고침</button>
    </div>
    <ProjectCreateForm v-if="canCreate" :workspace-id="workspaceId" @created="created" @check-list="loadPage(0)" />
    <p v-else class="project-form-help">프로젝트 생성은 이 공간의 소유자와 관리자만 할 수 있습니다.</p>
    <div class="workspace-grid project-grid">
      <section class="workspace-panel" aria-label="프로젝트 목록" :aria-busy="listLoading">
        <label class="archive-list-filter">
          <input v-model="includeArchived" type="checkbox" :disabled="listLoading" @change="loadPage(0)" />
          보관된 프로젝트 포함
        </label>
        <p v-if="listLoading" role="status">프로젝트를 불러오고 있어요.</p>
        <div v-else-if="listError" role="alert">
          <p>{{ listError }}</p>
          <button type="button" @click="loadPage()">프로젝트 목록 다시 시도</button>
        </div>
        <template v-else-if="page">
          <p v-if="page.items.length === 0" role="status">
            {{ page.page === 0 ? '이 공간에는 아직 프로젝트가 없습니다.' : '이 페이지에는 프로젝트가 없습니다.' }}
          </p>
          <ul v-else class="workspace-list project-list">
            <li v-for="item in page.items" :key="item.id">
              <button type="button" class="project-choice" :aria-pressed="selectedId === item.id" @click="select(item.id)">{{ item.name }}<span v-if="item.archived" class="archive-badge">보관됨</span></button>
            </li>
          </ul>
          <nav class="workspace-pagination" aria-label="프로젝트 페이지 이동">
            <button type="button" aria-label="이전 프로젝트 페이지" :disabled="page.page === 0" @click="loadPage(page.page - 1)">이전 페이지</button>
            <span aria-live="polite">{{ page.page + 1 }}페이지</span>
            <button type="button" aria-label="다음 프로젝트 페이지" :disabled="!page.hasNext || page.page >= 10000" @click="loadPage(page.page + 1)">다음 페이지</button>
          </nav>
        </template>
      </section>
      <section class="workspace-panel project-detail" aria-labelledby="project-detail-title" :aria-busy="detailLoading">
        <h4 id="project-detail-title">프로젝트 정보</h4>
        <p v-if="detailLoading" role="status">프로젝트 정보를 불러오고 있어요.</p>
        <div v-else-if="detailError" role="alert">
          <p>{{ detailError }}</p>
          <button v-if="selectedId" type="button" @click="select(selectedId)">프로젝트 상세 다시 시도</button>
        </div>
        <template v-else-if="detail">
          <h5>{{ detail.name }}</h5>
          <p v-if="detail.archived" class="archive-status" role="status">보관된 프로젝트입니다. Task와 이력은 조회할 수 있고 변경은 잠겨 있습니다.</p>
          <p v-if="detail.description !== null" class="project-description">{{ detail.description }}</p>
          <p v-else>등록된 설명이 없습니다.</p>
          <ProjectUpdateForm v-if="canCreate && !detail.archived" :key="detail.id" :project="detail" :my-role="myRole" @updated="updated" @refreshed="updated" />
          <ProjectArchiveControls v-if="canCreate" :key="'archive:' + detail.id" :project="detail" :my-role="myRole" @updated="updated" />
        </template>
        <p v-else class="workspace-hint">목록에서 프로젝트를 선택해 주세요.</p>
      </section>
    </div>
    <TaskBrowser v-if="detail" :key="detail.id" :workspace-id="workspaceId" :project-id="detail.id" :project-name="detail.name" :my-role="myRole" :project-archived="detail.archived" />
  </section>
</template>
