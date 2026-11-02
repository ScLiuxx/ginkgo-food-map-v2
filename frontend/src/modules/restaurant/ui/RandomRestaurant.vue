<script setup lang="ts">
import { ref, watch, onUnmounted } from 'vue'
import { useRestaurantCatalog } from '../model/catalog.store'
import { fetchRestaurants } from '../api/restaurantApi'
import { eligibleRestaurants, pickRestaurant } from '../model/random.logic'
import { priceLabel } from '../model/catalog.logic'
import type { RestaurantReadModel } from '../model/restaurant.types'
const catalog = useRestaurantCatalog()
const dialog = ref<globalThis.HTMLDialogElement>(),
  loading = ref(false),
  error = ref(''),
  budget = ref<number | null>(null)
const selected = ref<RestaurantReadModel | null>(null),
  count = ref(0)
let controller: globalThis.AbortController | undefined,
  generation = 0
/** 换条件/关窗使旧请求失效；跨所有服务端页抓取后抽取，绝不只抽当前页。 */
function invalidate() {
  generation++
  controller?.abort()
  loading.value = false
  selected.value = null
  count.value = 0
  error.value = ''
}
function close() {
  invalidate()
  dialog.value?.close()
}
async function choose() {
  const previousId = selected.value?.id
  invalidate()
  const ticket = generation
  loading.value = true
  controller = new globalThis.AbortController()
  const filters = { ...catalog.filters }
  try {
    const all: RestaurantReadModel[] = []
    for (let page = 1; ; page++) {
      const result = await fetchRestaurants(filters, page, 100, controller.signal)
      if (ticket !== generation) return
      all.push(...result.records)
      if (page >= result.totalPages) break
    }
    const candidates = eligibleRestaurants(
      [...new Map(all.map((r) => [r.id, r])).values()],
      budget.value,
    )
    count.value = candidates.length
    selected.value = pickRestaurant(candidates, previousId)
  } catch {
    if (ticket === generation) error.value = '暂时无法获取候选餐厅，请重试。'
  } finally {
    if (ticket === generation) loading.value = false
  }
}
function open() {
  dialog.value?.showModal()
  void choose()
}
watch(() => JSON.stringify(catalog.filters), invalidate)
watch(budget, () => {
  invalidate()
  if (dialog.value?.open) void choose()
})
onUnmounted(invalidate)
</script>
<template>
  <span class="random-banner"
    ><button type="button" @click="open">
      <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
        <path
          d="M3 6h3c5 0 7 12 12 12h3m-4-4 4 4-4 4M3 18h3c2 0 4-3 6-6m2-4c1-1 2-2 4-2h3m-4-4 4 4-4 4"
          stroke="currentColor"
          stroke-width="1.5"
          stroke-linecap="round"
          stroke-linejoin="round"
        /></svg
      >帮我选一家
    </button></span
  >
  <dialog ref="dialog" class="random-dialog" aria-labelledby="random-title" @cancel.prevent="close">
    <header>
      <h2 id="random-title">⚄ 今天吃什么</h2>
      <button type="button" aria-label="关闭随机推荐" @click="close">×</button>
    </header>
    <label class="budget"
      >调整人均预算<select v-model="budget">
        <option :value="null">不限预算</option>
        <option :value="15">¥15以内</option>
        <option :value="30">¥30以内</option>
        <option :value="50">¥50以内</option>
        <option :value="100">¥100以内</option>
      </select></label
    >
    <p class="note">沿用当前搜索、分类和经营状态条件，排除标注停业的店；不会自动放宽条件。</p>
    <p v-if="loading" role="status">正在从全站符合条件的餐厅中选择…</p>
    <p v-else-if="error" role="alert">{{ error }}</p>
    <template v-else-if="selected">
      <p class="candidate-count">从符合条件的 {{ count }} 家店中随机选出</p>
      <div class="random-result">
        <div class="photo-placeholder" aria-label="暂无同学上传照片">
          <svg viewBox="0 0 64 64" width="52" height="52" fill="none" aria-hidden="true">
            <path
              d="M12 28h40c-2 17-9 22-20 22S14 45 12 28Zm7 27h26M25 10v10m9-12v12m9-10v10"
              stroke="currentColor"
              stroke-width="3"
              stroke-linecap="round"
            /></svg
          ><small>暂无同学上传照片</small>
        </div>
        <div>
          <h3>{{ selected.name }}</h3>
          <p>{{ selected.category }}</p>
          <strong>{{ priceLabel(selected) }}</strong>
          <p>
            {{ selected.reviewCount }} 条评价 ·
            {{ selected.score === null ? '暂无评分' : `${selected.score.toFixed(1)}分` }}
          </p>
        </div>
      </div>
      <p class="note">
        {{ budget === null ? '未限制人均预算。' : '人均符合所选预算，估值请以实际消费为准。'
        }}{{ selected.needsLocation ? '位置待补，请先核实地址。' : '距离以目录标注为准。' }}
      </p>
      <RouterLink class="result-link" :to="`/restaurants/${selected.id}`" @click="close"
        >查看餐厅 →</RouterLink
      >
    </template>
    <p v-else class="no-candidates">没有符合条件的餐厅。请调整预算或关闭弹层修改首页条件。</p>
    <button type="button" class="reroll" :disabled="loading" @click="choose">
      {{ error ? '重试' : '换一家' }}
    </button>
  </dialog>
</template>
<style scoped src="./random-restaurant.css"></style>
