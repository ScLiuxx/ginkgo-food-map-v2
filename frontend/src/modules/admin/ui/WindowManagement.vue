<script setup lang="ts">
import AppLink from '@/shared/ui/AppLink.vue'
import { nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate } from 'vue-router'
import { authToken, getAccessToken } from '@/shared/api/authSession'
import {
  fetchManagedWindows,
  setWindowDeleted,
  type CanteenWindow,
} from '@/modules/restaurant/api/canteenWindowApi'
import { fetchRestaurantManagement } from '@/modules/restaurant/api/restaurantApi'
import { useRestaurantCatalog } from '@/modules/restaurant/model/catalog.store'
import { useWindowManagementBrowsing } from '../model/windowManagementBrowsing'
import { toErrorMessage } from '@/shared/lib/toErrorMessage'
import WindowManagementPreview from './WindowManagementPreview.vue'

const props = defineProps<{ id: string }>()
const token = getAccessToken()
const catalog = useRestaurantCatalog()
const context = useWindowManagementBrowsing().enter(props.id)
const rows = ref<CanteenWindow[]>([])
const canteenName = ref('')
const error = ref(''),
  busy = ref(false),
  loading = ref(true),
  changed = ref(false)
const confirming = ref<CanteenWindow | null>(null)
const viewing = ref<string | null>(null)
const confirmation = ref<globalThis.HTMLDialogElement>()
const page = ref<globalThis.HTMLElement>()
const controller = new globalThis.AbortController()
let active = true,
  generation = 0
const valid = () => active && !!token && token === getAccessToken() && !changed.value
const basePath = `/admin/restaurants/${props.id}/windows`

async function restorePosition() {
  await nextTick()
  if (!valid()) return
  const button = [
    ...(page.value?.querySelectorAll<globalThis.HTMLElement>('[data-window]') ?? []),
  ].find((element) => element.dataset.window === context.focusId)
  button?.focus({ preventScroll: true })
}
/** 返回时重新读取授权集合，等待内容就绪后恢复位置与原编辑按钮焦点。 */
async function load() {
  if (!valid()) return
  const ticket = ++generation
  loading.value = true
  error.value = ''
  try {
    const items = await fetchManagedWindows(props.id, token!, controller.signal)
    if (ticket !== generation || !valid()) return
    rows.value = items
    canteenName.value = items[0]?.canteenName ?? ''
    if (!canteenName.value) {
      const parent = await fetchRestaurantManagement(props.id, token!, controller.signal)
      if (ticket !== generation || !valid()) return
      canteenName.value = parent.restaurant.name
    }
  } catch (reason) {
    if (ticket === generation && valid()) error.value = toErrorMessage(reason)
  } finally {
    if (ticket === generation && valid()) {
      loading.value = false
      void restorePosition()
    }
  }
}
function remember(id = '') {
  context.scrollTop = globalThis.scrollY ?? 0
  context.focusId = id
}
function confirm(row: CanteenWindow) {
  if (busy.value || !valid()) return
  confirming.value = row
  error.value = ''
  confirmation.value?.showModal()
}
function closeConfirmation() {
  if (busy.value) return
  confirmation.value?.close()
  confirming.value = null
}
async function applyDelete() {
  const row = confirming.value
  if (!row || busy.value || !valid()) return
  busy.value = true
  error.value = ''
  try {
    await setWindowDeleted(row.id, !row.deleted, token!)
    if (!valid()) return
    confirmation.value?.close()
    confirming.value = null
    context.notice = row.deleted ? '窗口已恢复。' : '窗口已删除，历史点评保留。'
    catalog.invalidate()
    await load()
  } catch (reason) {
    if (valid()) error.value = `${toErrorMessage(reason)}。可重试或重新读取列表核对。`
  } finally {
    busy.value = false
  }
}
watch(authToken, () => {
  if (token !== getAccessToken()) {
    changed.value = true
    generation++
    controller.abort()
    rows.value = []
    canteenName.value = ''
    viewing.value = null
    confirmation.value?.close()
    confirming.value = null
  }
})
onBeforeRouteLeave(() => !busy.value)
onBeforeRouteUpdate(() => !busy.value)
onBeforeUnmount(() => {
  active = false
  generation++
  controller.abort()
  confirmation.value?.close()
})
void load()
</script>

<template>
  <section ref="page" class="window-management window-page" :aria-busy="loading">
    <AppLink class="window-back" to="/admin/restaurants">‹ 返回商户管理</AppLink>
    <div class="window-heading">
      <div>
        <h1>窗口管理</h1>
        <p v-if="canteenName" class="window-context">
          {{ canteenName }} · {{ rows.length }} 个窗口
        </p>
      </div>
      <AppLink
        v-if="!changed"
        class="window-primary"
        :to="`${basePath}/new`"
        :aria-disabled="busy || loading"
        @click="remember()"
        >＋ 新增窗口</AppLink
      >
    </div>
    <p v-if="changed" role="alert">登录身份已变化，请重新进入窗口管理。</p>
    <template v-else>
      <p v-if="context.notice" class="window-notice" role="status">{{ context.notice }}</p>
      <div v-if="error && !confirming" class="window-error" role="alert">
        {{ error }}
        <button type="button" :disabled="busy || loading" @click="load">重试读取列表</button>
      </div>
      <p v-if="loading" role="status">正在加载窗口…</p>
      <div v-else class="window-list window-surface">
        <table v-if="rows.length">
          <thead>
            <tr>
              <th>窗口名称</th>
              <th>楼层</th>
              <th>经营状态</th>
              <th class="operation-heading">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in rows" :key="row.id" :class="{ deleted: row.deleted }">
              <td class="window-name">{{ row.name }}</td>
              <td class="window-floor">{{ row.floorNumber }} 楼</td>
              <td class="window-status-cell">
                <span
                  class="window-status"
                  :class="{ muted: row.deleted || row.businessStatus === 'CLOSED' }"
                  >{{
                    row.deleted ? '已删除' : row.businessStatus === 'CLOSED' ? '停业' : '营业'
                  }}</span
                >
              </td>
              <td>
                <div class="window-actions">
                  <template v-if="!row.deleted">
                    <AppLink
                      :to="`${basePath}/${row.id}/edit`"
                      :data-window="row.id"
                      :aria-disabled="busy"
                      @click="remember(row.id)"
                      >编辑</AppLink
                    >
                    <button type="button" :disabled="busy" @click="viewing = row.id">查看</button>
                  </template>
                  <button
                    type="button"
                    :class="{ 'window-danger-text': !row.deleted }"
                    :disabled="busy"
                    @click="confirm(row)"
                  >
                    {{ row.deleted ? '恢复' : '删除' }}
                  </button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
        <p v-else-if="!error" class="window-empty">暂无窗口，点击“新增窗口”录入。</p>
      </div>
    </template>
    <WindowManagementPreview
      v-if="viewing && !changed"
      :id="viewing"
      :canteen-id="id"
      @close="viewing = null"
    />
    <dialog
      ref="confirmation"
      class="window-confirm"
      aria-labelledby="window-confirm-title"
      @cancel.prevent="closeConfirmation"
      @close="confirming = null"
    >
      <template v-if="confirming">
        <h2 id="window-confirm-title">
          {{ confirming.deleted ? '恢复' : '删除' }}“{{ confirming.name }}”？
        </h2>
        <p>
          {{
            confirming.deleted
              ? '恢复后重新展示该窗口，原有点评保留。'
              : '删除后不再展示该窗口，历史点评保留。你可以在窗口列表中恢复。'
          }}
        </p>
        <p v-if="error" role="alert">{{ error }}</p>
        <footer>
          <button class="window-secondary" :disabled="busy" @click="closeConfirmation">取消</button
          ><button
            :class="confirming.deleted ? 'window-primary' : 'window-danger'"
            :disabled="busy"
            @click="applyDelete"
          >
            {{ busy ? '正在处理…' : confirming.deleted ? '恢复窗口' : '删除窗口' }}
          </button>
        </footer>
      </template>
    </dialog>
  </section>
</template>

<style src="./window-management.css"></style>
