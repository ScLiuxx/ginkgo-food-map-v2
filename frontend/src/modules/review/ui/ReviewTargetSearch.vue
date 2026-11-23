<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, shallowRef, watch } from 'vue'
import { useRouter } from 'vue-router'
import { fetchRestaurants } from '@/modules/restaurant/api/restaurantApi'
import type { RestaurantReadModel } from '@/modules/restaurant/model/restaurant.types'
import RestaurantLocationPreview from '@/modules/map/ui/RestaurantLocationPreview.vue'
import {
  toRestaurantMapLocation,
  type RestaurantMapLocation,
} from '@/modules/map/model/restaurantLocation'

/** 全站点评按服务端搜索选店；分页结果与已选对象分离，不因搜索或失败偷偷更换评价对象。 */
const props = defineProps<{ selected?: RestaurantReadModel; disabled?: boolean }>()
const emit = defineEmits<{ select: [restaurant: RestaurantReadModel]; leave: [] }>()
const router = useRouter()
const keyword = ref(''),
  records = ref<RestaurantReadModel[]>([]),
  loading = ref(false),
  error = ref('')
const page = ref(0),
  pages = ref(0),
  searched = ref(false),
  expanded = ref(!props.selected)
const resultList = ref<globalThis.HTMLDivElement>()
const searchInput = ref<globalThis.HTMLInputElement>()
const changeButton = ref<globalThis.HTMLButtonElement>()
const preview = shallowRef<{ location: RestaurantMapLocation; opener: globalThis.HTMLElement }>()
const candidates = computed(() =>
  records.value.map((item) => ({ item, location: toRestaurantMapLocation(item) })),
)
let revision = 0,
  controller: globalThis.AbortController | undefined,
  timer: ReturnType<typeof globalThis.setTimeout> | undefined
const query = computed(() => keyword.value.trim())
const dirty = ref(false)
function cancel() {
  revision++
  controller?.abort()
  globalThis.clearTimeout(timer)
  loading.value = false
}
/** 每次首屏搜索取消旧词请求，加载更多只追加当前词；关闭、换号及卸载后拒绝迟到响应。 */
async function search(more = false) {
  if (props.disabled || (more && (loading.value || page.value >= pages.value || dirty.value)))
    return
  cancel()
  const ticket = revision,
    target = more ? page.value + 1 : 1,
    term = query.value
  if (!more) {
    records.value = []
    page.value = pages.value = 0
    searched.value = false
    if (resultList.value) resultList.value.scrollTop = 0
  }
  dirty.value = false
  error.value = ''
  expanded.value = true
  // 空关键词不读取店铺，清空后也不加载默认列表。
  if (!term) return
  loading.value = true
  controller = new globalThis.AbortController()
  try {
    const result = await fetchRestaurants(
      {
        keyword: term,
        category: '',
        sort: 'relevance',
        hideClosed: false,
        maxPrice: null,
        maxDistanceMeters: null,
      },
      target,
      8,
      controller.signal,
    )
    if (ticket !== revision) return
    records.value = more ? [...records.value, ...result.records] : result.records
    page.value = result.page
    pages.value = result.accessiblePages ?? result.totalPages
    searched.value = true
  } catch {
    if (ticket === revision) error.value = '店铺搜索失败，请重试。'
  } finally {
    if (ticket === revision) loading.value = false
  }
}
watch(keyword, () => {
  cancel()
  dirty.value = true
  records.value = []
  searched.value = false
  error.value = ''
  expanded.value = true
  page.value = pages.value = 0
  if (!query.value) return
  timer = globalThis.setTimeout(() => void search(), 300)
})
watch(
  () => props.disabled,
  (disabled) => {
    if (disabled) cancel()
  },
)
async function choose(item: RestaurantReadModel) {
  if (props.disabled) return
  emit('select', item)
  expanded.value = false
  await nextTick()
  changeButton.value?.focus({ preventScroll: true })
}
async function change() {
  expanded.value = !expanded.value
  if (!expanded.value) return
  await nextTick()
  searchInput.value?.focus({ preventScroll: true })
}
function showLocation(location: RestaurantMapLocation | null, event: globalThis.MouseEvent) {
  if (!location || props.disabled) return
  const opener = event.currentTarget as globalThis.HTMLElement
  preview.value = { location, opener }
}
function addStore() {
  if (props.disabled) return
  const term = query.value
  cancel()
  emit('leave')
  void router.push({ path: '/restaurants/submit', query: { keyword: term || undefined } })
}
onBeforeUnmount(cancel)
</script>
<template>
  <section class="review-target-search" aria-label="搜索评价对象">
    <div v-if="selected" class="target-search-field target-chosen-field">
      <span class="target-chosen-mark" aria-hidden="true">✓</span>
      <input
        type="text"
        readonly
        :value="selected.name"
        aria-label="已选评价店铺"
        :title="selected.name"
        @keydown.enter.prevent
      />
      <button
        ref="changeButton"
        type="button"
        class="target-change"
        :disabled="disabled"
        :aria-expanded="expanded"
        @click="change"
      >
        {{ expanded ? '取消更换' : '更换' }}
      </button>
    </div>
    <div v-show="!selected || expanded" class="target-search-field" role="search">
      <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
        <circle cx="10.5" cy="10.5" r="6.5" stroke="currentColor" stroke-width="1.6" />
        <path d="m16 16 4 4" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" />
      </svg>
      <input
        ref="searchInput"
        v-model="keyword"
        type="search"
        maxlength="100"
        aria-label="搜索评价店铺"
        :disabled="disabled"
        @focus="expanded = true"
        @keydown.enter.prevent="!$event.isComposing && search()"
      />
      <button type="button" class="target-search-submit" :disabled="disabled" @click="search()">
        搜索
      </button>
    </div>
    <div
      v-if="expanded && (loading || error || searched)"
      ref="resultList"
      class="target-search-results"
      aria-label="可评价店铺"
    >
      <div v-for="{ item, location } in candidates" :key="item.id" class="target-result">
        <button
          type="button"
          class="target-search-row"
          :aria-pressed="selected?.id === item.id"
          :disabled="disabled"
          @click="choose(item)"
        >
          <strong>{{ item.name }}</strong>
          <small
            >{{ item.address || '地址待补充'
            }}<template v-if="item.closed"> · 暂停营业</template></small
          >
        </button>
        <button
          v-if="location"
          type="button"
          class="target-locate"
          :aria-label="'在地图上查看' + item.name"
          :disabled="disabled"
          @click="showLocation(location, $event)"
        >
          在地图上查看 <span aria-hidden="true">›</span>
        </button>
        <span v-else class="target-location-missing">位置待补充</span>
      </div>
      <p v-if="loading" role="status" class="target-search-state">正在搜索店铺…</p>
      <div v-if="error" role="alert" class="target-search-state">
        {{ error }}
        <button type="button" :disabled="disabled" @click="search(page > 0)">重试</button>
      </div>
      <template v-else-if="searched && !loading && !dirty">
        <button
          v-if="page < pages"
          type="button"
          class="target-load-more"
          :disabled="disabled"
          @click="search(true)"
        >
          加载更多店铺
        </button>
        <div class="target-search-missing">
          <span>{{ records.length ? '没找到要评价的店铺？' : '没有找到匹配的店铺' }}</span>
          <button type="button" :disabled="disabled" @click="addStore">
            添加餐厅 <span aria-hidden="true">›</span>
          </button>
        </div>
      </template>
    </div>
    <RestaurantLocationPreview
      v-if="preview"
      :location="preview.location"
      :opener="preview.opener"
      back-label="返回选店"
      @close="preview = undefined"
    />
  </section>
</template>
<style scoped src="./review-target-search.css"></style>
