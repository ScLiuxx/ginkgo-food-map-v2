<script setup lang="ts">
import './admin-theme.css'
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { getAccessToken } from '@/shared/api/authSession'
import { fetchRequestPage } from '../api/restaurantRequestsApi'

/** 所有管理页面共用导航；菜单、图标、字号及活动状态保持一致。 */
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
  { name: '举报处理', path: '/admin/reports', icon: 'report' },
  { name: '商户管理', path: '/admin/restaurants', icon: 'shop' },
  { name: '公告管理', path: '/admin/announcements', icon: 'announcement' },
]
</script>

<template>
  <nav class="admin-sidebar" aria-label="管理导航">
    <RouterLink
      v-for="item in items"
      :key="item.icon"
      :to="item.path"
      class="sidebar-item"
      :class="{
        selected: route.path === item.path || route.path.startsWith(item.path + '/'),
      }"
      :aria-current="
        route.path === item.path || route.path.startsWith(item.path + '/') ? 'page' : undefined
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
          v-else-if="item.icon === 'announcement'"
          d="m4 10 15-6v16L4 14ZM4 10H2v4h2M7 15l2 6h3l-2-5"
        />
        <path v-else-if="item.icon === 'report'" d="M12 3 2 21h20L12 3Zm0 6v5M12 17h.01" />
        <path
          v-else-if="item.icon === 'shop'"
          d="M4 10v10h16V10M3 10l2-6h14l2 6M3 10c0 4 5 4 5 0 0 4 8 4 8 0 0 4 5 4 5 0M9 20v-6h6v6"
        />
      </svg>
      <span>{{ item.name }}</span>
      <span v-if="item.icon === 'review' && badge !== undefined" class="sidebar-count">{{
        badge
      }}</span>
    </RouterLink>
  </nav>
</template>

<style scoped>
.admin-sidebar {
  box-sizing: border-box;
  border-right: 1px solid var(--admin-divider);
  padding: 28px 16px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  font-family: Inter, 'PingFang SC', 'Microsoft YaHei', sans-serif;
  color: var(--admin-text);
  background: var(--admin-surface);
  min-height: 0;
}
.sidebar-item {
  box-sizing: border-box;
  display: flex;
  align-items: center;
  gap: 13px;
  width: 100%;
  padding: 15px;
  border: 0;
  border-radius: 9px;
  font: inherit;
  font-size: 14px;
  text-align: left;
  text-decoration: none;
  background: transparent;
  color: var(--admin-secondary);
  cursor: pointer;
  white-space: nowrap;
}
.sidebar-item svg {
  width: 18px;
  height: 18px;
  flex-shrink: 0;
}
.sidebar-item.selected {
  background: var(--admin-accent-soft);
  color: var(--admin-accent-text);
  font-weight: 600;
}
.sidebar-count {
  margin-left: auto;
  font-size: 11px;
}
.sidebar-item:focus-visible {
  outline: 2px solid var(--admin-focus);
  outline-offset: 3px;
}
@media (max-width: 800px) {
  .admin-sidebar {
    flex-direction: row;
    overflow: auto;
    padding: 9px 16px;
    border-right: 0;
    border-bottom: 1px solid var(--admin-divider);
    gap: 0;
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
