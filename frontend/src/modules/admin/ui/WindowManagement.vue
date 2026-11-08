<script setup lang="ts">
import { onUnmounted, ref, watch } from 'vue'
import { isAxiosError } from 'axios'
import { onBeforeRouteLeave, onBeforeRouteUpdate } from 'vue-router'
import { authToken, getAccessToken } from '@/shared/api/authSession'
import {
  fetchManagedWindows,
  saveWindow,
  setWindowDeleted,
  type CanteenWindow,
  type WindowInput,
} from '@/modules/restaurant/api/canteenWindowApi'
import { useRestaurantCatalog } from '@/modules/restaurant/model/catalog.store'
import { toErrorMessage } from '@/shared/lib/toErrorMessage'
import WindowPhotoManagement from './WindowPhotoManagement.vue'
const props = defineProps<{ id: string }>()
const token = getAccessToken(),
  catalog = useRestaurantCatalog()
const rows = ref<CanteenWindow[]>([])
const error = ref(''),
  notice = ref(''),
  busy = ref(false),
  loading = ref(false),
  uncertainCreate = ref(false),
  changed = ref(false)
const editing = ref<string | null>(null),
  formOpen = ref(false),
  confirming = ref<CanteenWindow | null>(null)
const photoWindow = ref<CanteenWindow | null>(null)
const photoBusy = ref(false)
const blank = (): WindowInput => ({
  name: '',
  floorNumber: 1,

  description: null,
  priceKind: 'UNKNOWN',
  pricePerPerson: null,
  businessStatus: 'OPERATING',
})
const input = ref<WindowInput>(blank())
let requestId = '',
  submitted = '',
  generation = 0
const controller = new globalThis.AbortController()
const valid = () => !!token && token === getAccessToken() && !changed.value
/** 读取包含软删除记录的授权集合；失败后保留明确错误和重试入口。 */
async function load() {
  if (!valid()) return
  const ticket = ++generation
  loading.value = true
  error.value = ''
  try {
    const items = await fetchManagedWindows(props.id, token!, controller.signal)
    if (ticket === generation && valid()) {
      rows.value = items
    }
  } catch (reason) {
    if (ticket === generation && valid()) error.value = toErrorMessage(reason)
  } finally {
    if (ticket === generation) loading.value = false
  }
}
function edit(row?: CanteenWindow) {
  if (busy.value || uncertainCreate.value || !valid()) return
  editing.value = row?.id ?? null
  input.value = row
    ? {
        name: row.name,
        floorNumber: row.floorNumber,

        description: row.description,
        priceKind: row.priceKind,
        pricePerPerson: row.pricePerPerson,
        businessStatus: row.businessStatus,
      }
    : blank()
  requestId = globalThis.crypto.randomUUID()
  submitted = ''
  formOpen.value = true
  error.value = ''
  notice.value = ''
}
/** 创建结果未知时冻结原内容和UUID，先确认原回执，避免改动内容后重试造成重复窗口。 */
async function save() {
  if (busy.value || !valid()) return
  const payload = {
    ...input.value,
    name: input.value.name.trim(),

    description: input.value.description?.trim() || null,
    pricePerPerson: input.value.priceKind === 'UNKNOWN' ? null : input.value.pricePerPerson,
  }
  const fingerprint = JSON.stringify(payload)
  if (submitted && fingerprint !== submitted) requestId = globalThis.crypto.randomUUID()
  submitted = fingerprint
  busy.value = true
  error.value = ''
  try {
    const result = await saveWindow(
      props.id,
      editing.value,
      { ...payload, ...(!editing.value ? { clientRequestId: requestId } : {}) },
      token!,
    )
    if (!valid()) return
    uncertainCreate.value = false
    formOpen.value = false
    notice.value = `窗口已保存：${result.name}`
    catalog.invalidate()
    await load()
  } catch (reason) {
    const status = isAxiosError(reason) ? reason.response?.status : undefined
    if (valid() && !editing.value && (!status || status >= 500)) uncertainCreate.value = true
    if (valid()) error.value = `${toErrorMessage(reason)}。请核对列表；相同内容可安全重试。`
  } finally {
    busy.value = false
  }
}
async function applyDelete() {
  const row = confirming.value
  if (!row || busy.value || !valid()) return
  busy.value = true
  error.value = ''
  try {
    await setWindowDeleted(row.id, !row.deleted, token!)
    if (!valid()) return
    confirming.value = null
    notice.value = row.deleted ? '窗口已恢复。' : '窗口已删除，历史点评保留，可在此恢复。'
    catalog.invalidate()
    await load()
  } catch (reason) {
    if (valid()) error.value = `${toErrorMessage(reason)}。可刷新核对后重试。`
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
    formOpen.value = false
    confirming.value = null
  }
})
onUnmounted(() => {
  generation++
  controller.abort()
})
onBeforeRouteLeave(() => !busy.value && !photoBusy.value)
onBeforeRouteUpdate(() => !busy.value && !photoBusy.value)
void load()
</script>
<template>
  <section class="window-management">
    <RouterLink to="/admin/restaurants">← 返回商户管理</RouterLink>
    <h1>窗口管理</h1>
    <p>所属食堂：{{ rows[0]?.canteenName || id }}。窗口名称和楼层独立维护，点评始终归属原窗口。</p>
    <p v-if="changed" role="alert">登录身份已变化，请重新进入窗口管理。</p>
    <template v-else>
      <button :disabled="busy || loading || uncertainCreate" @click="edit()">＋ 新增窗口</button>
      <button :disabled="busy || loading" @click="load">刷新列表</button>
      <RouterLink :to="`/restaurants/${id}`">查看食堂</RouterLink>
      <p v-if="error" role="alert">{{ error }}</p>
      <p v-if="notice" role="status">{{ notice }}</p>
      <p v-if="loading" role="status">正在加载窗口…</p>
      <div v-else class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>窗口</th>
              <th>楼层</th>
              <th>状态</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in rows" :key="row.id">
              <td data-label="窗口">
                {{ row.name }}<small>ID：{{ row.id }}</small>
              </td>
              <td data-label="楼层">{{ row.floorNumber }}层</td>
              <td data-label="状态">
                {{ row.deleted ? '已删除' : row.businessStatus === 'CLOSED' ? '停业' : '营业' }}
              </td>
              <td data-label="操作">
                <RouterLink v-if="!row.deleted" :to="`/canteen-windows/${row.id}`">查看</RouterLink>
                <button v-if="!row.deleted" :disabled="busy || uncertainCreate" @click="edit(row)">
                  编辑 / 停业
                </button>
                <button
                  v-if="!row.deleted"
                  :disabled="busy || photoBusy"
                  @click="photoWindow = row"
                >
                  照片管理
                </button>
                <button :disabled="busy" @click="confirming = row">
                  {{ row.deleted ? '恢复' : '删除' }}
                </button>
              </td>
            </tr>
          </tbody>
        </table>
        <p v-if="!rows.length">暂无窗口，点击新增窗口录入。</p>
      </div>
      <WindowPhotoManagement
        v-if="photoWindow"
        :id="photoWindow.id"
        :key="photoWindow.id"
        :name="photoWindow.name"
        @close="photoWindow = null"
        @busy="photoBusy = $event"
        @changed="load"
      />
      <section v-if="confirming" class="confirm" aria-label="确认窗口操作">
        <p>
          确认{{ confirming.deleted ? '恢复' : '删除' }}「{{ confirming.name }}」？历史点评将保留。
        </p>
        <button :disabled="busy" @click="applyDelete">确认操作</button
        ><button :disabled="busy" @click="confirming = null">取消</button>
      </section>
      <form v-if="formOpen" @submit.prevent="save">
        <h2>{{ editing ? '编辑窗口' : '新增窗口' }}</h2>
        <p v-if="uncertainCreate" role="status">
          上次创建结果尚未确认，资料已锁定。请重试原请求确认结果后再新增其他窗口。
        </p>
        <fieldset :disabled="busy || uncertainCreate">
          <label>窗口名称<input v-model="input.name" required maxlength="40" /></label>
          <label
            >楼层<input
              v-model.number="input.floorNumber"
              type="number"
              min="-10"
              max="100"
              step="1"
              required
          /></label>
          <label>窗口介绍<textarea v-model="input.description" maxlength="500" rows="3" /></label>
          <label
            >人均价格类型<select v-model="input.priceKind">
              <option value="UNKNOWN">未知</option>
              <option value="EXACT">准确价格</option>
              <option value="ESTIMATE">估计价格</option>
            </select></label
          >
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
            >经营状态<select v-model="input.businessStatus">
              <option value="OPERATING">营业</option>
              <option value="CLOSED">停业</option>
            </select></label
          >
          <p>停业后仍可阅读历史点评，不再接受新点评；重新开业后恢复发布。</p>
        </fieldset>
        <button type="submit" :disabled="busy">
          {{ busy ? '正在保存…' : uncertainCreate ? '重试确认创建结果' : '保存窗口' }}
        </button>
        <button type="button" :disabled="busy || uncertainCreate" @click="formOpen = false">
          取消
        </button>
      </form>
    </template>
  </section>
</template>
<style scoped>
.window-management {
  color: #25313d;
}
h1 {
  font-size: 28px;
}
p,
small {
  color: #7b8791;
  line-height: 1.8;
}
small {
  display: block;
}
a {
  color: #d57a20;
  margin-right: 14px;
}
button {
  cursor: pointer;
  padding: 9px 14px;
  background: white;
  border: 1px solid #dfe3e7;
  border-radius: 5px;
  margin: 4px;
}
button:disabled {
  opacity: 0.5;
}
.table-wrap {
  overflow: auto;
  margin-top: 20px;
}
table {
  border-collapse: collapse;
  width: 100%;
  min-width: 550px;
}
th,
td {
  text-align: left;
  padding: 16px;
  border-bottom: 1px solid #edf0f2;
}
th {
  background: #fafbfc;
}
form,
.confirm {
  border: 1px solid #e5e8ec;
  padding: 24px;
  margin-top: 20px;
  border-radius: 8px;
}
fieldset {
  border: 0;
  padding: 0;
}
label {
  display: grid;
  gap: 8px;
  margin-bottom: 16px;
  max-width: 620px;
}
input,
select,
textarea {
  font: inherit;
  padding: 10px;
  border: 1px solid #dfe3e7;
  border-radius: 5px;
}
[role='alert'] {
  color: #a44d2e;
}
/* 窄屏逐条展示完整操作，避免删除/恢复按钮藏在横向滚动区。 */
@media (max-width: 600px) {
  table,
  tbody,
  tr,
  td {
    display: block;
    min-width: 0;
  }
  thead {
    display: none;
  }
  tr {
    border-bottom: 1px solid #dfe3e7;
    padding: 14px 0;
  }
  td {
    border: 0;
    padding: 6px 0;
    overflow-wrap: anywhere;
  }
  td::before {
    content: attr(data-label);
    display: block;
    font-size: 12px;
    color: #7b8791;
    margin-bottom: 4px;
  }
  form,
  .confirm {
    padding: 16px;
  }
  input,
  select,
  textarea {
    min-width: 0;
    width: 100%;
    box-sizing: border-box;
  }
}
</style>
