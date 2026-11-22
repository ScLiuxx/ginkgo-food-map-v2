<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { authToken, getAccessToken } from '@/shared/api/authSession'
import { toErrorMessage } from '@/shared/lib/toErrorMessage'
import {
  deleteRestaurant,
  fetchRestaurantManagement,
  type RestaurantManagement,
} from '../api/restaurantApi'
import { useRestaurantCatalog } from '../model/catalog.store'

const props = defineProps<{ id: string; name: string; discardChanges?: boolean }>()
const emit = defineEmits<{
  close: []
  deleted: [result: RestaurantManagement]
  busy: [value: boolean]
}>()
const dialog = ref<globalThis.HTMLDialogElement>()
const snapshot = ref<RestaurantManagement | null>(null)
const loading = ref(false),
  busy = ref(false),
  error = ref(''),
  sessionChanged = ref(false)
const token = getAccessToken(),
  catalog = useRestaurantCatalog()
const controller = new globalThis.AbortController()
let disposed = false,
  revision = 0
const active = () => !disposed && !!token && token === getAccessToken() && !sessionChanged.value
watch(busy, (value) => emit('busy', value), { flush: 'sync' })

/** 删除前读取当前授权状态；结果不明时必须由用户重新读取，禁止自动重复写入。 */
async function read() {
  if (!active() || busy.value) return
  const ticket = ++revision
  snapshot.value = null
  error.value = ''
  loading.value = true
  try {
    const result = await fetchRestaurantManagement(props.id, token!, controller.signal)
    if (ticket === revision && active()) snapshot.value = result
  } catch (reason) {
    if (ticket === revision && active()) error.value = toErrorMessage(reason)
  } finally {
    if (ticket === revision) loading.value = false
  }
}
async function confirm() {
  if (!active() || loading.value || busy.value || !snapshot.value || snapshot.value.deleted) return
  busy.value = true
  error.value = ''
  try {
    const result = await deleteRestaurant(snapshot.value.restaurant.id, token!)
    if (!active()) return
    catalog.invalidate()
    emit('deleted', result)
    dialog.value?.close()
    emit('close')
  } catch (reason) {
    if (active()) {
      snapshot.value = null
      catalog.invalidate()
      error.value = `${toErrorMessage(reason)}。请重新读取，核对删除结果后再操作。`
    }
  } finally {
    busy.value = false
  }
}
function close() {
  if (busy.value) return
  dialog.value?.close()
  emit('close')
}
watch(authToken, () => {
  if (getAccessToken() === token) return
  sessionChanged.value = true
  revision++
  controller.abort()
  snapshot.value = null
  loading.value = false
})
onMounted(async () => {
  await nextTick()
  if (!disposed) {
    dialog.value?.showModal()
    void read()
  }
})
onBeforeUnmount(() => {
  disposed = true
  revision++
  controller.abort()
  dialog.value?.close()
})
</script>

<template>
  <dialog
    ref="dialog"
    class="restaurant-delete-dialog"
    aria-labelledby="delete-restaurant-title"
    @cancel.prevent="close"
  >
    <header>
      <h2 id="delete-restaurant-title">删除店铺</h2>
      <button type="button" aria-label="关闭删除确认" :disabled="busy" @click="close">×</button>
    </header>
    <div class="delete-content">
      <p class="target-name">{{ snapshot?.restaurant.name || name }}</p>
      <p v-if="sessionChanged" role="alert">登录状态已变化，请关闭后重新进入。</p>
      <p v-else-if="snapshot?.deleted" role="status">这家店铺已经删除。</p>
      <template v-else
        ><p>删除后将从列表、地图和搜索中下架，历史点评及资料保留。不支持恢复。</p>
        <p v-if="discardChanges" class="discard-note">本页未保存的修改将被丢弃。</p></template
      >
      <p v-if="loading" role="status">正在核对店铺状态…</p>
      <p v-if="error" class="delete-error" role="alert">
        {{ error }}
        <button type="button" :disabled="busy || sessionChanged" @click="read">重新读取</button>
      </p>
    </div>
    <footer>
      <button type="button" autofocus :disabled="busy" @click="close">取消</button
      ><button
        v-if="!snapshot?.deleted"
        type="button"
        class="confirm-delete"
        :disabled="loading || busy || !snapshot || sessionChanged"
        @click="confirm"
      >
        {{ busy ? '正在删除…' : '确认删除' }}
      </button>
    </footer>
  </dialog>
</template>

<style scoped>
.restaurant-delete-dialog {
  width: min(460px, calc(100% - 32px));
  max-height: calc(100dvh - 32px);
  overflow: auto;
  margin: auto;
  padding: 0;
  border: 0;
  border-radius: 10px;
  background: #fff;
  color: #343a43;
  box-shadow: 0 16px 70px #25313d33;
  font:
    14px/1.7 Inter,
    'Microsoft YaHei',
    sans-serif;
}
.restaurant-delete-dialog::backdrop {
  background: #25313d55;
}
.restaurant-delete-dialog header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 19px 24px;
  border-bottom: 1px solid #edf0f3;
}
.restaurant-delete-dialog h2 {
  font-size: 18px;
  margin: 0;
}
.restaurant-delete-dialog header button {
  border: 0;
  background: none;
  color: #87909a;
  font-size: 24px;
  padding: 0;
}
.delete-content {
  padding: 18px 24px;
  color: #78818d;
  font-size: 13px;
}
.delete-content p {
  margin: 0 0 12px;
  overflow-wrap: anywhere;
}
.delete-content p:last-child {
  margin-bottom: 0;
}
.delete-content .target-name {
  font-size: 17px;
  font-weight: 600;
  color: #343a43;
}
.discard-note {
  font-size: 12px;
}
.restaurant-delete-dialog footer {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  padding: 16px 24px;
  border-top: 1px solid #edf0f3;
}
.restaurant-delete-dialog button {
  cursor: pointer;
  font: inherit;
}
.restaurant-delete-dialog footer button {
  border: 1px solid #dfe3e8;
  background: white;
  color: #697481;
  border-radius: 6px;
  padding: 8px 17px;
}
.restaurant-delete-dialog footer .confirm-delete {
  background: #bb4949;
  border-color: #bb4949;
  color: white;
}
.delete-error {
  color: #ad4545;
}
.delete-error button {
  border: 0;
  background: none;
  color: inherit;
  text-decoration: underline;
  padding: 0;
}
.restaurant-delete-dialog button:disabled {
  opacity: 0.5;
  cursor: default;
}
.restaurant-delete-dialog button:focus-visible {
  outline: 2px solid #929aa5;
  outline-offset: 3px;
}
</style>
