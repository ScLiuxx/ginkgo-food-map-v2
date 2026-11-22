<script setup lang="ts">
import AppLink from '@/shared/ui/AppLink.vue'
import LeaveConfirmation from '@/shared/ui/LeaveConfirmation.vue'
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRouter } from 'vue-router'
import { isAxiosError } from 'axios'
import { authToken, getAccessToken } from '@/shared/api/authSession'
import { createRequestId } from '@/shared/lib/createRequestId'
import { toErrorMessage } from '@/shared/lib/toErrorMessage'
import {
  fetchManagedWindows,
  saveWindow,
  type CanteenWindow,
  type WindowInput,
} from '@/modules/restaurant/api/canteenWindowApi'
import { fetchRestaurantManagement } from '@/modules/restaurant/api/restaurantApi'
import { useRestaurantCatalog } from '@/modules/restaurant/model/catalog.store'
import { useWindowManagementBrowsing } from '../model/windowManagementBrowsing'
import WindowPhotoManagement from './WindowPhotoManagement.vue'

const props = defineProps<{ id: string; windowId?: string }>()
const router = useRouter()
const catalog = useRestaurantCatalog()
const context = useWindowManagementBrowsing().enter(props.id)
const token = getAccessToken()
const controller = new globalThis.AbortController()
const row = ref<CanteenWindow>()
const canteenName = ref('')
const loading = ref(true),
  loadError = ref(''),
  error = ref(''),
  notice = ref('')
const busy = ref(false),
  photoBusy = ref(false),
  photoPending = ref(false),
  uncertainCreate = ref(false),
  changed = ref(false)
const input = ref<WindowInput>({
  name: '',
  floorNumber: 1,
  description: null,
  priceKind: 'UNKNOWN',
  pricePerPerson: null,
  businessStatus: 'OPERATING',
})
const normalized = () => ({
  ...input.value,
  name: input.value.name.trim(),
  description: input.value.description?.trim() || null,
  pricePerPerson: input.value.priceKind === 'UNKNOWN' ? null : input.value.pricePerPerson,
})
const baseline = ref(JSON.stringify(normalized()))
const dirty = computed(() => JSON.stringify(normalized()) !== baseline.value)
const locked = computed(() => busy.value || photoBusy.value)
const leaveConfirmation = ref<InstanceType<typeof LeaveConfirmation>>()
const returnPath = `/admin/restaurants/${props.id}/windows`
let active = true,
  saved = false,
  requestId = createRequestId(),
  submitted = ''
const valid = () => active && !!token && token === getAccessToken() && !changed.value

/** 编辑仅从授权列表确认归属；删除或跨食堂窗口不能加载照片。 */
async function load() {
  if (!valid()) return
  loading.value = true
  loadError.value = ''
  try {
    const items = await fetchManagedWindows(props.id, token!, controller.signal)
    if (!valid()) return
    if (props.windowId) {
      const target = items.find(
        (item) => item.id === props.windowId && item.canteenId === props.id && !item.deleted,
      )
      if (!target) throw new Error('该窗口已删除或不属于当前食堂，请返回列表核对。')
      row.value = target
      const { name, floorNumber, description, priceKind, pricePerPerson, businessStatus } = target
      input.value = { name, floorNumber, description, priceKind, pricePerPerson, businessStatus }
      canteenName.value = target.canteenName
    } else {
      canteenName.value = items[0]?.canteenName ?? ''
      if (!canteenName.value) {
        const parent = await fetchRestaurantManagement(props.id, token!, controller.signal)
        if (!valid()) return
        if (parent.deleted || parent.restaurant.restaurantType !== 'CANTEEN')
          throw new Error('当前食堂不可维护窗口，请返回商户管理。')
        canteenName.value = parent.restaurant.name
      }
    }
    baseline.value = JSON.stringify(normalized())
  } catch (reason) {
    if (valid()) loadError.value = toErrorMessage(reason)
  } finally {
    if (valid()) loading.value = false
  }
}
async function save() {
  if (!valid() || locked.value || loading.value || loadError.value || photoPending.value) return
  const payload = normalized()
  const fingerprint = JSON.stringify(payload)
  if (submitted && submitted !== fingerprint) requestId = createRequestId()
  submitted = fingerprint
  busy.value = true
  error.value = ''
  try {
    const result = await saveWindow(
      props.id,
      props.windowId ?? null,
      { ...payload, ...(!props.windowId ? { clientRequestId: requestId } : {}) },
      token!,
    )
    if (!valid()) return
    uncertainCreate.value = false
    saved = true
    baseline.value = fingerprint
    context.notice = `窗口已保存：${result.name}`
    context.focusId = result.id
    catalog.invalidate()
    // 新建后进入编辑页即可维护照片，不保留可再次创建的历史页面。
    await router.replace(props.windowId ? returnPath : `${returnPath}/${result.id}/edit`)
  } catch (reason) {
    const status = isAxiosError(reason) ? reason.response?.status : undefined
    if (valid() && !props.windowId && (!status || status >= 500)) uncertainCreate.value = true
    if (valid()) error.value = `${toErrorMessage(reason)}。相同内容可安全重试。`
  } finally {
    busy.value = false
  }
}
function photosChanged() {
  catalog.invalidate()
  notice.value = '照片已保存。'
}
function canLeave() {
  if (changed.value || saved) return true
  if (locked.value || uncertainCreate.value) return false
  return (!dirty.value && !photoPending.value) || leaveConfirmation.value?.ask() || false
}
function beforeUnload(event: globalThis.BeforeUnloadEvent) {
  if (
    saved ||
    changed.value ||
    (!dirty.value && !locked.value && !uncertainCreate.value && !photoPending.value)
  )
    return
  event.preventDefault()
  event.returnValue = ''
}
watch(authToken, () => {
  if (token !== getAccessToken()) {
    changed.value = true
    controller.abort()
    row.value = undefined
    canteenName.value = ''
    input.value = {
      name: '',
      floorNumber: 1,
      description: null,
      priceKind: 'UNKNOWN',
      pricePerPerson: null,
      businessStatus: 'OPERATING',
    }
  }
})
onBeforeRouteLeave(canLeave)
onBeforeRouteUpdate(
  (to, from) =>
    (to.params.id === from.params.id && to.params.windowId === from.params.windowId) || canLeave(),
)
onMounted(() => globalThis.addEventListener('beforeunload', beforeUnload))
onBeforeUnmount(() => {
  active = false
  controller.abort()
  globalThis.removeEventListener('beforeunload', beforeUnload)
})
void load()
</script>

<template>
  <section class="window-editor window-page" :aria-busy="loading">
    <LeaveConfirmation
      ref="leaveConfirmation"
      :message="photoPending ? '照片保存尚未确认，确定离开？' : '窗口资料尚未保存，确定离开？'"
    />
    <AppLink class="window-back" :to="returnPath" :aria-disabled="locked || uncertainCreate"
      >‹ 返回窗口管理</AppLink
    >
    <div class="window-heading">
      <div>
        <h1>{{ windowId ? '编辑窗口' : '新增窗口' }}</h1>
        <p v-if="canteenName" class="window-context">
          {{ windowId && row ? row.name + ' · ' : '' }}{{ canteenName }}
        </p>
      </div>
    </div>
    <p v-if="changed" role="alert">登录身份已变化，请重新进入窗口管理。</p>
    <p v-else-if="loading" role="status">正在读取窗口资料…</p>
    <div v-else-if="loadError" class="window-error" role="alert">
      {{ loadError }} <button type="button" @click="load">重试读取</button>
    </div>
    <form v-else class="window-surface" @submit.prevent="save">
      <div class="window-form-layout" :class="{ creating: !windowId }">
        <fieldset :disabled="locked || uncertainCreate || photoPending">
          <legend>窗口资料</legend>
          <label>窗口名称<input v-model="input.name" required maxlength="40" /></label>
          <div class="window-field-pair">
            <label
              >楼层<input
                v-model.number="input.floorNumber"
                type="number"
                min="-10"
                max="100"
                step="1"
                required
            /></label>
            <label
              >人均价格<select v-model="input.priceKind">
                <option value="UNKNOWN">未知</option>
                <option value="EXACT">准确价格</option>
                <option value="ESTIMATE">估计价格</option>
              </select></label
            >
          </div>
          <label v-if="input.priceKind !== 'UNKNOWN'"
            >人均金额<input
              v-model.number="input.pricePerPerson"
              type="number"
              min="1"
              max="5000"
              step="1"
              required
          /></label>
          <label
            ><span>窗口介绍 <small>选填</small></span
            ><textarea v-model="input.description" maxlength="500" rows="4" />
          </label>
          <div class="window-field">
            <span id="window-status-label">经营状态</span>
            <div class="window-status-options" role="group" aria-labelledby="window-status-label">
              <button
                type="button"
                :aria-pressed="input.businessStatus === 'OPERATING'"
                @click="input.businessStatus = 'OPERATING'"
              >
                营业</button
              ><button
                type="button"
                :aria-pressed="input.businessStatus === 'CLOSED'"
                @click="input.businessStatus = 'CLOSED'"
              >
                停业
              </button>
            </div>
          </div>
        </fieldset>
        <WindowPhotoManagement
          v-if="windowId && row"
          :id="windowId"
          :key="windowId"
          :name="row.name"
          :disabled="busy"
          @busy="photoBusy = $event"
          @pending="photoPending = $event"
          @changed="photosChanged"
        />
      </div>
      <div class="window-form-feedback">
        <p v-if="uncertainCreate" role="status">
          上次创建结果尚未确认，资料已锁定。请重试原请求确认结果。
        </p>
        <p v-if="error" role="alert">{{ error }}</p>
        <p v-if="notice" role="status">{{ notice }}</p>
      </div>
      <footer class="window-editor-footer">
        <AppLink
          class="window-secondary"
          :to="returnPath"
          :aria-disabled="locked || uncertainCreate"
          >取消</AppLink
        ><button class="window-primary" type="submit" :disabled="locked || photoPending">
          {{ busy ? '正在保存…' : uncertainCreate ? '重试确认创建结果' : '保存窗口' }}
        </button>
      </footer>
    </form>
  </section>
</template>

<style src="./window-management.css"></style>
