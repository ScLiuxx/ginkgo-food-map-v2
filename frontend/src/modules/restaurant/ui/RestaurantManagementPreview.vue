<script setup lang="ts">
import ResourceLink from '@/shared/ui/ResourceLink.vue'
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { authToken, getAccessToken } from '@/shared/api/authSession'
import { toErrorMessage } from '@/shared/lib/toErrorMessage'
import { navigationUrl } from '@/modules/map'
import {
  fetchRestaurantManagement,
  toRestaurantModel,
  type RestaurantManagement,
} from '../api/restaurantApi'
import { priceLabel } from '../model/catalog.logic'
import RestaurantAlbum from './RestaurantAlbum.vue'

const props = defineProps<{ id: string }>()
const data = ref<RestaurantManagement | null>(null)
const loading = ref(false)
const error = ref('')
const restaurant = computed(() => (data.value ? toRestaurantModel(data.value.restaurant) : null))
const navigation = computed(() =>
  restaurant.value && !data.value?.deleted ? navigationUrl(restaurant.value) : null,
)
const support = (value: boolean | null | undefined) =>
  value == null ? '未知' : value ? '支持' : '不支持'
let controller: globalThis.AbortController | undefined
let generation = 0

/** 只读取管理员授权资料；切换店铺或凭证时清空旧内容，迟到结果不能覆盖当前会话。 */
async function load() {
  const ticket = ++generation
  const token = getAccessToken()
  const id = props.id
  controller?.abort()
  data.value = null
  error.value = ''
  loading.value = false
  if (!token) {
    return
  }
  controller = new globalThis.AbortController()
  loading.value = true
  try {
    const result = await fetchRestaurantManagement(id, token, controller.signal)
    if (ticket === generation && token === getAccessToken()) {
      data.value = result
    }
  } catch (reason) {
    if (ticket === generation && token === getAccessToken()) {
      error.value = toErrorMessage(reason)
    }
  } finally {
    if (ticket === generation) {
      loading.value = false
    }
  }
}
watch(
  () => [props.id, authToken.value],
  () => void load(),
  { immediate: true },
)
onBeforeUnmount(() => {
  generation++
  controller?.abort()
})
</script>
<template>
  <section class="restaurant-preview">
    <p v-if="loading" role="status">正在读取店铺资料…</p>
    <div v-else-if="error" role="alert">
      {{ error }} <button type="button" @click="load">重试</button>
    </div>
    <template v-else-if="data && restaurant">
      <header>
        <div>
          <h1>{{ restaurant.name }}</h1>
        </div>
        <span class="preview-status">{{
          data.deleted ? '已删除' : restaurant.closed ? '停业' : '正常经营'
        }}</span>
      </header>
      <dl>
        <div>
          <dt>类型</dt>
          <dd>{{ restaurant.restaurantType === 'CANTEEN' ? '食堂' : '普通餐厅' }}</dd>
        </div>
        <div v-if="restaurant.restaurantType !== 'CANTEEN'">
          <dt>分类</dt>
          <dd>{{ restaurant.category || '待补充' }}</dd>
        </div>
        <div>
          <dt>地址</dt>
          <dd>
            {{ restaurant.address || '待补充' }}
            <ResourceLink
              v-if="navigation"
              :href="navigation"
              target="_blank"
              rel="noopener noreferrer"
              >查看位置 ↗</ResourceLink
            >
          </dd>
        </div>
        <div>
          <dt>人均</dt>
          <dd>{{ priceLabel(restaurant) }}</dd>
        </div>
        <div>
          <dt>堂食</dt>
          <dd>{{ support(restaurant.supportsDineIn) }}</dd>
        </div>
        <div>
          <dt>外卖</dt>
          <dd>{{ support(restaurant.supportsDelivery) }}</dd>
        </div>
        <div v-if="restaurant.tags.length">
          <dt>标签</dt>
          <dd>{{ restaurant.tags.join(' · ') }}</dd>
        </div>
      </dl>
      <RestaurantAlbum
        v-if="!data.deleted"
        :key="restaurant.id"
        :restaurant-id="restaurant.id"
        :name="restaurant.name"
        compact
      />
    </template>
  </section>
</template>
<style scoped>
.restaurant-preview {
  padding-top: 4px;
}
header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 18px;
}
dt {
  color: var(--admin-muted);
  font-size: 13px;
}
h1 {
  margin: 0;
  font-size: 20px;
  line-height: 1.5;
  overflow-wrap: anywhere;
}
.preview-status {
  flex-shrink: 0;
  padding: 5px 9px;
  border-radius: 5px;
  background: var(--admin-surface-muted);
  color: var(--admin-secondary);
  font-size: 12px;
}
dl {
  margin: 24px 0;
}
dl > div {
  display: grid;
  grid-template-columns: 68px minmax(0, 1fr);
  gap: 16px;
  padding: 15px 0;
  border-top: 1px solid var(--admin-divider);
}
dd {
  margin: 0;
  line-height: 1.7;
  overflow-wrap: anywhere;
}
a {
  display: inline-block;
  margin-left: 10px;
  color: var(--admin-accent-text);
  font-size: 13px;
}
button {
  padding: 8px 14px;
  border: 1px solid var(--admin-border);
  border-radius: 6px;
  background: var(--admin-surface);
  color: inherit;
  cursor: pointer;
}
@media (max-width: 600px) {
  h1 {
    font-size: 18px;
  }
}
</style>
