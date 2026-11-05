<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { getAccessToken } from '@/shared/api/authSession'
import { fetchRequestPage } from '../api/restaurantRequestsApi'

/** 审核与商户维护共用导航；图标、尺寸及活动状态只有一份定义。 */
const props = defineProps<{ pendingCount?: number | string }>()
const route = useRoute()
const pending = ref<number>()
const badge = computed(() => props.pendingCount ?? pending.value)
const controller = new globalThis.AbortController()
onMounted(async () => {
  if (props.pendingCount !== undefined) return
  const token = getAccessToken()
  if (!token) return
  try {
    const result = await fetchRequestPage(
      { status: 'PENDING', keyword: '', page: 1, pageSize: 1 },
      token,
      controller.signal,
    )
    if (!controller.signal.aborted && token === getAccessToken())
      pending.value = result.counts.PENDING
  } catch {
    // 数量读取失败不伪造0，也不阻止导航；审核页仍保留完整查询及失败重试。
  }
})
onBeforeUnmount(() => controller.abort())
const items = [
  { name: '商户审核', path: '/admin/reviews', icon: 'review' },
  { name: '商户管理', path: '/admin/restaurants', icon: 'shop' },
  { name: '点评管理', icon: 'chat' },
  { name: '操作记录', icon: 'history' },
]
</script>

<template>
  <nav class="admin-sidebar" aria-label="管理导航">
    <p class="sidebar-label">内容管理</p>
    <component
      :is="item.path ? RouterLink : 'button'"
      v-for="item in items"
      :key="item.icon"
      :to="item.path"
      :disabled="!item.path"
      class="sidebar-item"
      :class="{
        selected: item.path && (route.path === item.path || route.path.startsWith(item.path + '/')),
      }"
      :aria-current="
        item.path && (route.path === item.path || route.path.startsWith(item.path + '/'))
          ? 'page'
          : undefined
      "
    >
      <svg
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        stroke-width="1.5"
        stroke-linecap="round"
        stroke-linejoin="round"
        aria-hidden="true"
      >
        <template v-if="item.icon === 'review'">
          <rect x="5" y="4" width="14" height="17" rx="2" />
          <path d="M9 4V2h6v2M9 10h6M9 14h6M9 18h4" />
        </template>
        <path
          v-else-if="item.icon === 'shop'"
          d="M4 10v10h16V10M3 10l2-6h14l2 6M3 10c0 4 5 4 5 0 0 4 8 4 8 0 0 4 5 4 5 0M9 20v-6h6v6"
        />
        <path
          v-else-if="item.icon === 'chat'"
          d="M5 4h14a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H9l-5 3v-3H3V6a2 2 0 0 1 2-2ZM7 9h10M7 13h7"
        />
        <path v-else d="M4 7a9 9 0 1 1-1 9M3 3v5h5M12 7v6l4 2" />
      </svg>
      <span>{{ item.name }}</span>
      <span v-if="item.icon === 'review' && badge !== undefined" class="sidebar-count">{{
        badge
      }}</span>
      <span v-if="!item.path" class="sidebar-later">后续</span>
    </component>
    <div class="sidebar-bottom">一起维护校园美食地图<br />让每一份分享都有据可循。</div>
  </nav>
</template>

<style scoped>
.admin-sidebar {
  box-sizing: border-box;
  border-right: 1px solid #eceef0;
  padding: 36px 18px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  font-family: Inter, 'PingFang SC', 'Microsoft YaHei', sans-serif;
  color: #25313d;
}
.sidebar-label {
  font-size: 10px;
  color: #929aa3;
  letter-spacing: 2px;
  margin: 0 16px 12px;
}
.sidebar-item {
  box-sizing: border-box;
  display: flex;
  align-items: center;
  gap: 12px;
  width: 100%;
  padding: 13px 15px;
  border: 0;
  border-radius: 6px;
  font: inherit;
  font-size: 13px;
  text-align: left;
  text-decoration: none;
  background: transparent;
  color: inherit;
  cursor: pointer;
  white-space: nowrap;
}
.sidebar-item svg {
  width: 18px;
  height: 18px;
  flex-shrink: 0;
}
.sidebar-item.selected {
  background: #fcf4e8;
  color: #bd6816;
  font-weight: 600;
}
.sidebar-item:disabled {
  color: #b3bac2;
  cursor: default;
}
.sidebar-count {
  margin-left: auto;
  font-size: 11px;
}
.sidebar-later {
  margin-left: auto;
  font-size: 9px;
  color: #b3bac2;
}
.sidebar-bottom {
  margin-top: auto;
  padding: 28px 15px 0;
  font-size: 11px;
  line-height: 1.9;
  color: #959ca4;
}
.sidebar-item:focus-visible {
  outline: 2px solid #ad6f26;
  outline-offset: 3px;
}
@media (max-width: 800px) {
  .admin-sidebar {
    flex-direction: row;
    overflow: auto;
    padding: 9px 16px;
    border-right: 0;
    border-bottom: 1px solid #eceef0;
    gap: 0;
  }
  .sidebar-label,
  .sidebar-bottom,
  .sidebar-later {
    display: none;
  }
  .sidebar-item {
    width: auto;
    padding: 10px;
    font-size: 11px;
    gap: 6px;
  }
  .sidebar-item svg {
    width: 16px;
    height: 16px;
  }
  .sidebar-count {
    margin-left: 5px;
  }
}
</style>
