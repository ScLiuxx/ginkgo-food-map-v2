<script setup lang="ts">
import AppLink from '@/shared/ui/AppLink.vue'
import axios from 'axios'
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { onBeforeRouteLeave, useRouter } from 'vue-router'
import { authToken, getAccessToken } from '@/shared/api/authSession'
import { toErrorMessage } from '@/shared/lib/toErrorMessage'
import { deleteRestaurant, fetchRestaurantManagement } from '../api/restaurantApi'
import type { RestaurantManagement } from '../api/restaurantApi'
import { useRestaurantCatalog } from '../model/catalog.store'

const props = defineProps<{ id: string }>()
const router = useRouter()
const management = ref<RestaurantManagement | null>(null)
const error = ref('')
const checking = ref(false)
const deleting = ref(false)
const confirming = ref(false)
const dialog = ref<globalThis.HTMLDialogElement>()
const deleteButton = ref<globalThis.HTMLButtonElement>()
let controller: globalThis.AbortController | undefined
let generation = 0
let loadedToken: string | null = null
let disposed = false
const canManage = computed(
  () => management.value && !management.value.deleted && authToken.value === loadedToken,
)

/** 仅登录用户查询私有管理接口；403是正常的权限边界，不影响公开详情阅读。 */
async function loadPermissions(): Promise<void> {
  const current = ++generation
  controller?.abort()
  controller = new globalThis.AbortController()
  const token = getAccessToken()
  const id = props.id
  management.value = null
  error.value = ''
  closeDialog()
  checking.value = false
  if (!token) return
  checking.value = true
  try {
    const result = await fetchRestaurantManagement(id, token, controller.signal)
    if (current !== generation || getAccessToken() !== token) return
    management.value = result
    loadedToken = token
  } catch (reason) {
    if (current !== generation || controller.signal.aborted) return
    const status = axios.isAxiosError(reason) ? reason.response?.status : undefined
    if (status !== 401 && status !== 403 && status !== 404)
      error.value = '暂时无法确认管理权限，请重试。'
  } finally {
    if (current === generation) checking.value = false
  }
}
watch(
  () => [props.id, authToken.value],
  () => {
    void loadPermissions()
  },
  { immediate: true, flush: 'sync' },
)

/** 原生模态对话框限制焦点范围；先聚焦取消，明确显示对象及当前评价数量。 */
async function confirmDelete(): Promise<void> {
  if (!canManage.value || deleting.value) return
  error.value = ''
  confirming.value = true
  await nextTick()
  if (dialog.value?.showModal) dialog.value.showModal()
  else if (dialog.value) dialog.value.open = true
  dialog.value?.querySelector<globalThis.HTMLButtonElement>('.cancel-delete')?.focus()
}

function closeDialog(): void {
  dialog.value?.close?.()
  confirming.value = false
}
function cancelDelete(): void {
  if (deleting.value) return
  closeDialog()
  deleteButton.value?.focus()
}

/** 成功才离开详情并清理目录缓存；失败保留确认界面，身份变化时丢弃旧响应。 */
async function remove(): Promise<void> {
  const token = getAccessToken()
  if (deleting.value || !confirming.value || !canManage.value || !token || token !== loadedToken)
    return
  const current = generation
  const id = props.id
  deleting.value = true
  error.value = ''
  try {
    await deleteRestaurant(id, token)
    if (disposed || current !== generation || getAccessToken() !== token) return
    useRestaurantCatalog().invalidate()
    closeDialog()
    deleting.value = false
    await router.replace('/')
  } catch (reason) {
    if (disposed || current !== generation || getAccessToken() !== token) return
    const status = axios.isAxiosError(reason) ? reason.response?.status : undefined
    if (status === 403 || status === 404 || status === 409) {
      management.value = null
      closeDialog()
      error.value =
        status === 403
          ? '管理权限已变化，无法删除这家餐厅。'
          : '餐厅已不可用或状态发生变化，请重新加载详情。'
    } else {
      error.value =
        axios.isAxiosError(reason) && !reason.response
          ? '尚未确认删除结果，请先重新加载详情核对，再决定是否重试。'
          : toErrorMessage(reason)
    }
  } finally {
    deleting.value = false
  }
}
onBeforeRouteLeave(() => !deleting.value)
onBeforeUnmount(() => {
  disposed = true
  generation++
  controller?.abort()
  closeDialog()
})
</script>

<template>
  <section v-if="authToken" class="restaurant-management" aria-label="餐厅维护">
    <p v-if="checking" role="status">正在确认管理权限…</p>
    <div v-if="canManage" class="management-buttons">
      <AppLink :to="`/restaurants/${encodeURIComponent(id)}/edit`">编辑餐厅</AppLink>
      <button ref="deleteButton" type="button" :disabled="deleting" @click="confirmDelete">
        删除餐厅
      </button>
    </div>
    <p v-if="error && !confirming" role="alert">
      {{ error }}
      <button v-if="!canManage" type="button" @click="loadPermissions">重新确认权限</button>
    </p>
    <dialog
      v-if="confirming && management"
      ref="dialog"
      role="alertdialog"
      aria-modal="true"
      aria-labelledby="delete-title"
      aria-describedby="delete-description"
      @cancel.prevent="cancelDelete"
    >
      <h2 id="delete-title">确认删除“{{ management.restaurant.name }}”？</h2>
      <p id="delete-description">
        当前有
        {{ management.restaurant.reviewCount }}
        条评价。删除后，这家餐厅会从公开目录、搜索和详情中隐藏；资料及标签会保留，仅管理员可以恢复。停业请使用编辑中的经营状态。
      </p>
      <p v-if="error" role="alert">{{ error }}</p>
      <div class="dialog-actions">
        <button type="button" class="cancel-delete" :disabled="deleting" @click="cancelDelete">
          取消
        </button>
        <button type="button" class="confirm-delete" :disabled="deleting" @click="remove">
          {{ deleting ? '正在删除…' : '确认删除' }}
        </button>
      </div>
    </dialog>
  </section>
</template>

<style scoped>
.restaurant-management {
  margin: -12px 0 28px;
  font-size: 13px;
  color: #78674e;
}
.management-buttons,
.dialog-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: center;
}
.management-buttons a,
button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-height: 44px;
  padding: 10px 16px;
  border: 1px solid #d8ccba;
  border-radius: 10px;
  background: #fffdf8;
  font: inherit;
  color: #745d38;
  text-decoration: none;
  cursor: pointer;
}
.management-buttons button,
.confirm-delete {
  color: #943e32;
  border-color: #d8b6ac;
}
button:disabled {
  opacity: 0.6;
  cursor: wait;
}
button:focus-visible,
a:focus-visible {
  outline: 2px solid #a77829;
  outline-offset: 3px;
}
dialog {
  width: min(520px, calc(100vw - 32px));
  max-height: calc(100dvh - 40px);
  overflow-y: auto;
  padding: 26px;
  border: 1px solid #d9cab2;
  border-radius: 18px;
  background: #fffdf8;
  color: #514b3c;
  box-shadow: 0 20px 80px #30281633;
}
dialog::backdrop {
  background: #29241b66;
}
dialog h2 {
  margin: 0 0 18px;
  font-size: 20px;
  line-height: 1.6;
  overflow-wrap: anywhere;
}
dialog p {
  line-height: 1.9;
}
.dialog-actions {
  justify-content: flex-end;
  margin-top: 24px;
}
[role='alert'] {
  color: #943e32;
  line-height: 1.8;
}
</style>
