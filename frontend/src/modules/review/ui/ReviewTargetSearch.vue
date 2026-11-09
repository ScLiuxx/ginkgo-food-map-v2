<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { fetchRestaurants } from '@/modules/restaurant/api/restaurantApi'
import type { RestaurantReadModel } from '@/modules/restaurant/model/restaurant.types'

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
function choose(item: RestaurantReadModel) {
  if (props.disabled) return
  emit('select', item)
  expanded.value = false
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
    <div class="target-search-field" role="search">
      <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
        <circle cx="10.5" cy="10.5" r="6.5" stroke="currentColor" stroke-width="1.6" />
        <path d="m16 16 4 4" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" />
      </svg>
      <input
        v-model="keyword"
        type="search"
        maxlength="100"
        aria-label="搜索评价店铺"
        placeholder="搜索餐厅、食堂或窗口…"
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
      <button
        v-for="item in records"
        :key="item.id"
        type="button"
        class="target-search-row"
        :aria-pressed="selected?.id === item.id"
        :disabled="disabled"
        @click="choose(item)"
      >
        <span
          ><strong>{{ item.name }}</strong
          ><small
            >{{ item.address || '地址待补充'
            }}<template v-if="item.closed"> · 暂停营业</template></small
          ></span
        ><span aria-hidden="true">{{ selected?.id === item.id ? '✓' : '›' }}</span>
      </button>
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
    <div v-if="selected" class="target-search-selected">
      <span
        >已选择 <strong>{{ selected.name }}</strong></span
      ><button v-if="!expanded" type="button" :disabled="disabled" @click="expanded = true">
        更换
      </button>
    </div>
  </section>
</template>
<style scoped src="./review-target-search.css"></style>
