<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { searchExistingRestaurants, type ExistingRestaurant } from '../api/restaurantRequestsApi'
import { toErrorMessage } from '@/shared/lib/toErrorMessage'

/** 每次打开独立核对目标；取消和迟到结果不能复用上一次申请的选择。 */
const props = defineProps<{
  restaurantName: string
  restaurantType: ExistingRestaurant['restaurantType']
  submitting: boolean
  error: string
}>()
const emit = defineEmits<{ close: []; confirm: [restaurantId: string] }>()
const dialog = ref<globalThis.HTMLDialogElement>()
const query = ref(props.restaurantName),
  page = ref(1),
  totalPages = ref(0)
const rows = ref<ExistingRestaurant[]>([]),
  selected = ref<ExistingRestaurant | null>(null)
const loading = ref(false),
  searchError = ref('')
let abort: globalThis.AbortController | undefined,
  version = 0,
  timer: ReturnType<typeof globalThis.setTimeout>

/** 改关键字立即清空选择；版本号与取消请求共同阻止旧结果覆盖当前核对对象。 */
async function search() {
  const current = ++version
  abort?.abort()
  selected.value = null
  rows.value = []
  totalPages.value = 0
  searchError.value = ''
  if (!query.value.trim()) {
    loading.value = false
    return
  }
  loading.value = true
  abort = new globalThis.AbortController()
  try {
    const result = await searchExistingRestaurants(
      query.value.trim(),
      props.restaurantType,
      page.value,
      abort.signal,
    )
    if (current !== version) return
    rows.value = result.records
    totalPages.value = result.accessiblePages ?? result.totalPages
  } catch (error) {
    if (current === version) searchError.value = toErrorMessage(error)
  } finally {
    if (current === version) loading.value = false
  }
}
watch(query, () => {
  ++version
  abort?.abort()
  selected.value = null
  rows.value = []
  loading.value = !!query.value.trim()
  totalPages.value = 0
  searchError.value = ''
  page.value = 1
  globalThis.clearTimeout(timer)
  timer = globalThis.setTimeout(() => void search(), 250)
})
function changePage(value: number) {
  page.value = value
  void search()
}
function close() {
  if (!props.submitting) emit('close')
}
onMounted(async () => {
  await nextTick()
  dialog.value?.showModal()
  void search()
})
onBeforeUnmount(() => {
  ++version
  globalThis.clearTimeout(timer)
  abort?.abort()
  dialog.value?.close()
})
</script>

<template>
  <dialog
    ref="dialog"
    class="link-existing-dialog"
    aria-labelledby="link-existing-title"
    @cancel.prevent="close"
  >
    <header>
      <div>
        <span>新店申请处理</span>
        <h2 id="link-existing-title">店铺已收录</h2>
      </div>
      <button type="button" aria-label="关闭店铺选择" :disabled="submitting" @click="close">
        ×
      </button>
    </header>
    <p class="intro">为“{{ restaurantName }}”选择对应店铺，请核对地址和位置。</p>
    <label class="search-label"
      >搜索已收录的{{ restaurantType === 'CANTEEN' ? '食堂' : '店铺'
      }}<input
        v-model="query"
        type="search"
        maxlength="80"
        placeholder="输入店铺名称或地址"
        :disabled="submitting"
    /></label>
    <div class="results" :aria-busy="loading">
      <p v-if="loading" role="status">正在查找店铺…</p>
      <div v-else-if="searchError" role="alert">
        <p>{{ searchError }}</p>
        <button type="button" @click="search">重试搜索</button>
      </div>
      <p v-else-if="!query.trim()">输入名称或地址后查找。</p>
      <p v-else-if="!rows.length">没有找到对应店铺，请调整关键词。</p>
      <label
        v-for="row in rows"
        v-else
        :key="row.id"
        class="candidate"
        :class="{ chosen: selected?.id === row.id }"
      >
        <input
          v-model="selected"
          type="radio"
          name="existing-restaurant"
          :value="row"
          :disabled="submitting"
        />
        <span
          ><strong>{{ row.name }}</strong
          ><span>{{ row.address || '地址未填写' }}</span
          ><small
            >编号 {{ row.id }} · {{ row.businessStatus === 'CLOSED' ? '停业' : '正常经营' }} ·
            {{
              row.latitude == null || row.longitude == null
                ? '位置待补充'
                : `${row.latitude}, ${row.longitude}`
            }}</small
          ></span
        >
        <a :href="`/restaurants/${row.id}`" target="_blank" rel="noopener noreferrer">查看 ↗</a>
      </label>
    </div>
    <nav v-if="totalPages > 1" aria-label="已有店铺分页">
      <button
        type="button"
        :disabled="submitting || loading || page <= 1"
        @click="changePage(page - 1)"
      >
        上一页</button
      ><span>{{ page }} / {{ totalPages }}</span
      ><button
        type="button"
        :disabled="submitting || loading || page >= totalPages"
        @click="changePage(page + 1)"
      >
        下一页
      </button>
    </nav>
    <p class="effect">
      确认后结束本次申请，附带点评自动发布到所选店铺。原投稿资料和店铺附图保留供核对。
    </p>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <footer>
      <span>{{ selected ? `已选择：${selected.name}` : '请选择对应店铺' }}</span
      ><button type="button" :disabled="submitting" @click="close">取消</button
      ><button
        type="button"
        class="confirm"
        :disabled="submitting || loading || !selected"
        @click="selected && emit('confirm', selected.id)"
      >
        {{ submitting ? '正在处理…' : '确认已收录并结束申请' }}
      </button>
    </footer>
  </dialog>
</template>

<style scoped>
.link-existing-dialog {
  width: min(680px, calc(100vw - 32px));
  max-height: 90dvh;
  box-sizing: border-box;
  border: 1px solid var(--admin-border);
  border-radius: 18px;
  padding: 26px;
  color: var(--admin-text);
  background: var(--admin-surface);
  box-shadow: 0 24px 90px var(--admin-shadow);
}
dialog::backdrop {
  background: var(--admin-backdrop);
}
header,
footer,
nav {
  display: flex;
  align-items: center;
  gap: 12px;
}
header {
  justify-content: space-between;
}
header span {
  font-size: 12px;
  color: var(--admin-muted);
}
h2 {
  margin: 5px 0 0;
  font-size: 24px;
}
button {
  cursor: pointer;
  border: 1px solid var(--admin-border);
  border-radius: 8px;
  background: var(--admin-surface);
  color: inherit;
  padding: 9px 14px;
  font: inherit;
}
button:disabled {
  opacity: 0.5;
  cursor: default;
}
header button {
  border: 0;
  font-size: 24px;
}
.intro,
.effect {
  font-size: 14px;
  line-height: 1.7;
  color: var(--admin-secondary);
}
.search-label {
  display: grid;
  gap: 8px;
  font-size: 13px;
}
.search-label input {
  border: 1px solid var(--admin-border);
  border-radius: 9px;
  padding: 12px;
  font: inherit;
}
.results {
  min-height: 150px;
  max-height: 36dvh;
  overflow: auto;
  margin: 16px 0;
}
.candidate {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 12px;
  margin: 8px 0;
  border: 1px solid var(--admin-border);
  border-radius: 10px;
  cursor: pointer;
}
.candidate.chosen {
  border-color: var(--admin-primary);
  background: var(--admin-accent-soft);
}
.candidate > span {
  display: grid;
  gap: 6px;
  flex: 1;
  min-width: 0;
}
.candidate span span,
small {
  font-size: 12px;
  color: var(--admin-secondary);
  overflow-wrap: anywhere;
}
.candidate a {
  font-size: 12px;
  color: var(--admin-accent-text);
  white-space: nowrap;
}
nav {
  justify-content: center;
  font-size: 13px;
}
.effect {
  background: var(--admin-surface-muted);
  padding: 12px;
  border-radius: 9px;
}
footer {
  flex-wrap: wrap;
}
footer > span {
  flex: 1;
  font-size: 13px;
  min-width: 130px;
  overflow-wrap: anywhere;
}
.confirm {
  background: var(--admin-primary);
  color: var(--admin-surface);
  border-color: var(--admin-primary);
}
.error {
  color: var(--admin-danger);
  font-size: 14px;
}
button:focus-visible,
a:focus-visible {
  outline: 2px solid var(--admin-focus);
  outline-offset: 3px;
}
</style>
