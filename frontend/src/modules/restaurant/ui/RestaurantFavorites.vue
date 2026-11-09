<script setup lang="ts">
import { computed, ref, watch, onBeforeUnmount } from 'vue'
import { useLoginStore } from '@/modules/auth'
import { authToken } from '@/shared/api/authSession'
import { toErrorMessage } from '@/shared/lib/toErrorMessage'
import { favoritePage, setFavorite, type FavoriteItem } from '../api/favoriteApi'
const props = defineProps<{ userId?: string; profileLayout?: boolean }>()
const emit = defineEmits<{ total: [value: number | null] }>()
const auth = useLoginStore(),
  own = computed(() => !props.userId || props.userId === String(auth.user?.id))
const records = ref<FavoriteItem[]>([]),
  page = ref(1),
  total = ref(0),
  totalPages = ref(0),
  loading = ref(false),
  busy = ref(false),
  loaded = ref(false),
  error = ref(''),
  notice = ref('')
let context = 0,
  generation = 0,
  controller: globalThis.AbortController | undefined,
  requested = 1
/** 列表读取严格绑定主体和会话；私密拒绝保留错误语义，不伪装为零收藏。 */
async function load(target = page.value) {
  const ticket = ++generation
  controller?.abort()
  records.value = []
  error.value = ''
  loaded.value = false
  emit('total', null)
  loading.value = false
  const token = authToken.value,
    user = props.userId || String(auth.user?.id ?? '')
  if (!auth.authenticated || !token || !user) return
  requested = target
  controller = new globalThis.AbortController()
  loading.value = true
  try {
    const result = await favoritePage(
      target,
      user,
      own.value,
      token,
      controller.signal,
      !!props.userId,
    )
    if (ticket === generation) {
      records.value = result.records
      page.value = result.page
      total.value = result.total
      emit('total', result.total)
      totalPages.value = result.totalPages
      loaded.value = true
    }
  } catch (reason) {
    if (ticket === generation) error.value = toErrorMessage(reason)
  } finally {
    if (ticket === generation) loading.value = false
  }
}
/** 使用明确的取消请求，可在失效项上操作；失败后刷新事实，重试仍为取消而非翻转。 */
async function remove(item: FavoriteItem) {
  if (!own.value || busy.value || loading.value) return
  const token = authToken.value,
    ticket = generation,
    owner = context
  if (!token) return
  busy.value = true
  notice.value = ''
  try {
    await setFavorite(item.restaurantId, false, token)
    if (ticket === generation) {
      notice.value = '已取消收藏'
      await load(1)
    }
  } catch (reason) {
    if (ticket === generation) {
      notice.value = `取消结果未确认：${toErrorMessage(reason)}。请刷新列表核对。`
      await load(page.value)
    }
  } finally {
    if (owner === context) busy.value = false
  }
}
watch(
  () => [props.userId, auth.authenticated, auth.user?.id, authToken.value],
  () => {
    context++
    generation++
    controller?.abort()
    records.value = []
    total.value = totalPages.value = 0
    page.value = 1
    busy.value = false
    notice.value = ''
    void load(1)
  },
  { immediate: true, flush: 'sync' },
)
onBeforeUnmount(() => {
  context++
  generation++
  controller?.abort()
})
/** 主页工具栏复用列表原有刷新流程，不绕过隐私检查或取消收藏期间的禁用状态。 */
defineExpose({ refresh: () => load(1), refreshing: computed(() => loading.value || busy.value) })
</script>
<template>
  <section
    class="favorites-list"
    :class="{ 'profile-favorites': profileLayout }"
    aria-label="店铺收藏列表"
    :aria-busy="loading"
  >
    <header v-if="!profileLayout">
      <h2>{{ own ? '我的收藏' : '收藏店铺' }}</h2>
      <button type="button" :disabled="loading || busy" @click="load(1)">刷新列表</button>
    </header>
    <p v-if="notice" role="status">{{ notice }}</p>
    <p v-if="loading" role="status">正在加载收藏…</p>
    <div v-else-if="error" role="alert">
      <p>{{ error }}</p>
      <button type="button" @click="load(requested)">重新加载</button>
    </div>
    <template v-else-if="loaded">
      <p v-if="!profileLayout">共 {{ total }} 家店铺</p>
      <p v-if="!records.length">
        {{ total ? '当前页内容已变化，请返回第一页。' : '暂无可展示的收藏店铺。' }}
      </p>
      <button v-if="total && !records.length" @click="load(1)">返回第一页</button>
      <article v-for="item in records" :key="item.restaurantId">
        <span v-if="profileLayout" class="favorite-store-icon" aria-hidden="true"
          ><svg viewBox="0 0 24 24">
            <path
              d="M4 10v11h16V10M3 10l2-7h14l2 7M3 10c0 3 4 3 4 0 0 3 5 3 5 0 0 3 5 3 5 0 0 3 4 3 4 0M9 21v-7h6v7"
            /></svg
        ></span>
        <div>
          <RouterLink v-if="item.available" :to="`/restaurants/${item.restaurantId}`"
            >{{ item.name
            }}<span v-if="profileLayout" class="favorite-store-chevron" aria-hidden="true">
              ›</span
            ></RouterLink
          ><strong v-else>店铺已失效</strong>
          <p v-if="item.available" class="favorite-type">
            {{ item.restaurantType === 'CANTEEN' ? '食堂' : '餐厅'
            }}<span v-if="item.businessStatus === 'CLOSED'"> · 暂停营业</span>
          </p>
          <p v-else>店铺恢复后收藏会重新可用；取消后不会自动恢复。</p>
          <time :datetime="item.createdAt"
            >收藏于 {{ new Date(item.createdAt).toLocaleDateString('zh-CN') }}</time
          >
        </div>
        <button v-if="own" type="button" :disabled="busy || loading" @click="remove(item)">
          取消收藏
        </button>
      </article>
      <nav v-if="totalPages > 1" aria-label="收藏分页">
        <button :disabled="page <= 1 || busy" @click="load(page - 1)">上一页</button
        ><span>第 {{ page }} / {{ totalPages }} 页</span
        ><button :disabled="page >= totalPages || busy" @click="load(page + 1)">下一页</button>
      </nav>
    </template>
  </section>
</template>
<style scoped>
.favorites-list {
  padding: 16px 0;
  color: #374151;
}
.favorites-list header,
.favorites-list article,
.favorites-list nav {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  flex-wrap: wrap;
}
.favorites-list h2 {
  font-size: 18px;
}
.favorites-list article {
  padding: 20px 0;
  border-bottom: 1px solid #eceff2;
}
.favorites-list article > div {
  flex: 1;
  min-width: 150px;
  overflow-wrap: anywhere;
}
.favorites-list p,
.favorites-list time {
  font-size: 13px;
  color: #7b8794;
}
.favorites-list a {
  font-weight: 600;
  color: #705626;
}
.favorites-list button {
  min-height: 44px;
  border: 1px solid #ded7c8;
  border-radius: 6px;
  padding: 7px 12px;
  background: white;
  color: #876324;
  cursor: pointer;
}
.favorites-list button:disabled {
  opacity: 0.5;
  cursor: default;
}
.favorites-list nav {
  justify-content: center;
  margin-top: 20px;
}
/* 个人主页使用紧凑店铺行；独立收藏页面沿用原布局，失效项和重试仍完整保留。 */
.profile-favorites {
  padding: 0;
}
.profile-favorites article {
  display: grid;
  grid-template-columns: 48px minmax(0, 1fr) auto;
  padding: 24px 0;
  gap: 15px;
}
.profile-favorites article:last-of-type {
  border-bottom: 0;
}
.profile-favorites article > div {
  min-width: 0;
}
.favorite-store-icon {
  display: grid;
  place-items: center;
  width: 48px;
  height: 48px;
  border: 1px solid #edf0f3;
  border-radius: 10px;
  background: #f6f7f9;
  color: #a4adb7;
}
.favorite-store-icon svg {
  width: 23px;
  height: 23px;
  fill: none;
  stroke: currentColor;
  stroke-width: 1.5;
  stroke-linecap: round;
  stroke-linejoin: round;
}
.profile-favorites a {
  text-decoration: none;
  color: #26313e;
  font-size: 16px;
  line-height: 1.6;
  font-weight: 650;
}
.favorite-store-chevron {
  color: #a4adb7;
  margin-left: 8px;
  font-weight: 400;
}
.profile-favorites .favorite-type {
  display: inline;
  font-size: 12px;
}
.profile-favorites .favorite-type::after {
  content: ' · ';
}
.profile-favorites time {
  display: inline;
  color: #939ca7;
  font-size: 12px;
}
.profile-favorites article > div > a,
.profile-favorites article > div > strong {
  display: block;
  margin-bottom: 7px;
}
.profile-favorites article > button {
  border: 0;
  padding: 8px 0;
  background: transparent;
  color: #939ca7;
  font-size: 12px;
  white-space: nowrap;
}
.favorites-list :is(a, button):focus-visible {
  outline: 2px solid #cf9f53;
  outline-offset: 3px;
}
@media (max-width: 600px) {
  .profile-favorites article {
    grid-template-columns: 40px minmax(0, 1fr);
    gap: 10px 12px;
    padding: 20px 0;
  }
  .profile-favorites article > button {
    grid-column: 2;
    justify-self: end;
    min-height: 32px;
  }
  .favorite-store-icon {
    width: 40px;
    height: 40px;
  }
  .profile-favorites a {
    font-size: 15px;
  }
}
</style>
