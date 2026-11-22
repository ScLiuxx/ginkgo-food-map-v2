<script setup lang="ts">
import AppLink from '@/shared/ui/AppLink.vue'
import { computed } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRoute } from 'vue-router'
import { useAdminRestaurants } from '../model/useAdminRestaurants'
import AdminSidebar from './AdminSidebar.vue'
import AdminHeader from './AdminHeader.vue'

const props = defineProps<{
  editing?: boolean
  username: string
  loggingOut: boolean
  sessionError: string | null
}>()
defineEmits<{ logout: [] }>()
const route = useRoute()
const enabled = computed(() => !props.editing)
const {
  keyword,
  category,
  page,
  pageSize,
  pageNumbers,
  jumpPage,
  jump,
  items,
  categories,
  total,
  totalPages,
  loading,
  error,
  categoryError,
  notice,
  selected,
  inspecting,
  busy,
  actionError,
  confirming,
  sessionChanged,
  reload,
  loadCategories,
  inspect,
  applyAction,
  close,
  search,
} = useAdminRestaurants(enabled)

// 写入结果确认前留在当前页面，避免导航销毁状态后误以为操作未提交。
onBeforeRouteLeave(() => !busy.value)
onBeforeRouteUpdate(() => !busy.value)
</script>

<template>
  <div class="management admin-theme">
    <AdminHeader :username="username" :logging-out="loggingOut" @logout="$emit('logout')" />
    <p v-if="sessionError" class="alert" role="alert">{{ sessionError }}</p>
    <div class="shell">
      <AdminSidebar />
      <main>
        <slot v-if="editing" />
        <template v-else-if="!sessionChanged">
          <div class="heading">
            <div>
              <h1>商户管理</h1>
            </div>
            <AppLink class="primary" to="/admin/restaurants/new">＋ 新增店铺</AppLink>
          </div>
          <p v-if="notice || route.query.saved" class="notice" role="status">
            {{ notice || `店铺资料已保存，ID：${route.query.saved}` }}
          </p>
          <form class="toolbar" @submit.prevent="search">
            <input
              v-model="keyword"
              aria-label="搜索店铺"
              placeholder="搜索店名、地址或标签"
              type="search"
              :disabled="busy"
            />
            <select v-model="category" aria-label="美食分类" :disabled="busy">
              <option value="">全部美食</option>
              <option v-for="item in categories" :key="item.code" :value="item.code">
                {{ item.name }}
              </option>
            </select>
            <button class="primary" :disabled="busy">查询</button
            ><button type="button" :disabled="busy || loading" @click="reload">刷新</button>
          </form>
          <p v-if="categoryError" class="alert" role="alert">
            分类加载失败：{{ categoryError }}
            <button @click="loadCategories">重试</button>
          </p>
          <div class="list-summary">
            <span>{{ loading ? '正在加载…' : `共 ${total} 家公开店铺` }}</span
            ><span class="muted">包含停业店铺 · 每页 {{ pageSize }} 家</span>
          </div>
          <p v-if="error" class="alert" role="alert">
            {{ error }} <button @click="reload">重新加载</button>
          </p>
          <p v-else-if="loading" class="empty" role="status">正在读取店铺资料…</p>
          <p v-else-if="!items.length" class="empty">没有匹配的店铺，可调整条件或新增店铺。</p>
          <div v-else class="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>店铺</th>
                  <th>分类 / 类型</th>
                  <th>人均</th>
                  <th>经营状态</th>
                  <th>操作</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="item in items" :key="item.id">
                  <td>
                    <strong>{{ item.name }}</strong
                    ><small>{{ item.address || '地址待补充' }}</small
                    ><small>ID：{{ item.id }}</small>
                  </td>
                  <td>
                    {{ item.restaurantType === 'CANTEEN' ? '食堂' : item.category || '分类未知' }}
                  </td>
                  <td>
                    {{ item.price === null ? '未知' : `¥${item.price}` }}
                  </td>
                  <td>
                    <span class="badge" :class="{ closed: item.closed }">{{
                      item.closed ? '停业' : '正常经营'
                    }}</span>
                  </td>
                  <td class="actions">
                    <AppLink :to="`/admin/restaurants/${item.id}/edit`">编辑</AppLink
                    ><button :disabled="busy || inspecting" @click="inspect(item.id)">删除</button>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
          <nav class="pagination" aria-label="店铺分页">
            <label
              >每页
              <select v-model="pageSize" aria-label="每页数量" :disabled="loading || busy">
                <option v-for="size in [10, 20, 50, 100]" :key="size" :value="size">
                  {{ size }} 条
                </option>
              </select></label
            >
            <button :disabled="page <= 1 || loading || busy" @click="page = 1">首页</button>
            <button :disabled="page <= 1 || loading || busy" @click="page--">上一页</button
            ><button
              v-for="number in pageNumbers"
              :key="number"
              :aria-current="number === page ? 'page' : undefined"
              :class="{ 'current-page': number === page }"
              :disabled="loading || busy"
              @click="page = number"
            >
              {{ number }}
            </button>
            <button :disabled="page >= totalPages || loading || busy" @click="page++">
              下一页
            </button>
            <button :disabled="page >= totalPages || loading || busy" @click="page = totalPages">
              末页
            </button>
            <span>共 {{ total }} 条 / {{ Math.max(1, totalPages) }} 页</span>
            <form class="page-jump" @submit.prevent="jump">
              <label
                >前往
                <input
                  v-model="jumpPage"
                  aria-label="跳转页码"
                  type="number"
                  min="1"
                  :max="totalPages"
                  step="1"
                  :disabled="loading || busy"
                />
                页</label
              ><button :disabled="loading || busy || !totalPages">跳转</button>
            </form>
          </nav>
          <p v-if="inspecting" role="status">正在读取店铺资料…</p>
          <section v-if="selected" class="detail" aria-labelledby="management-title">
            <div class="heading">
              <h2 id="management-title">{{ selected.restaurant.name }}</h2>
              <button :disabled="busy" @click="close">关闭</button>
            </div>
            <p>
              ID：{{ selected.restaurant.id }} ·
              {{ selected.deleted ? '已删除' : '公开中' }}
            </p>
            <p class="muted">
              {{ selected.restaurant.address || '地址待补充' }}
            </p>
            <p v-if="selected.deleted">该店铺已删除，请刷新列表。</p>
            <p v-else>删除后从列表、地图和搜索中下架，历史点评及资料保留。</p>
            <div class="actions">
              <AppLink
                v-if="!selected.deleted && !busy"
                :to="`/admin/restaurants/${selected.restaurant.id}/edit`"
                >编辑资料</AppLink
              ><button
                v-if="!selected.deleted && !confirming"
                class="danger"
                :disabled="busy"
                @click="confirming = true"
              >
                删除店铺
              </button>
            </div>
            <div
              v-if="confirming && !selected.deleted"
              class="confirm"
              role="group"
              aria-label="确认店铺操作"
            >
              <p>确认删除「{{ selected.restaurant.name }}」？</p>
              <button class="primary" :disabled="busy" @click="applyAction">
                {{ busy ? '正在处理…' : '确认操作' }}</button
              ><button :disabled="busy" @click="confirming = false">取消</button>
            </div>
          </section>
          <p v-if="actionError" class="alert" role="alert">{{ actionError }}</p>
        </template>
        <p v-else class="alert" role="alert">登录状态已变化，请重新进入商户管理。</p>
      </main>
    </div>
  </div>
</template>

<style scoped>
/* 四个管理入口共用灰底、白色内容区与橙色操作；窄屏表格仍独立滚动。 */
.management {
  min-height: 100vh;
  background: var(--admin-canvas);
  color: var(--admin-text);
  font-family: Inter, 'PingFang SC', 'Microsoft YaHei', sans-serif;
}
.shell {
  display: grid;
  grid-template-columns: var(--admin-sidebar-width) minmax(0, 1fr);
  min-height: calc(100vh - var(--admin-header-height));
  min-height: calc(100dvh - var(--admin-header-height));
}
main {
  min-width: 0;
  padding: 30px 40px 60px;
}
.heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  margin-bottom: 25px;
}
h1 {
  font-size: 28px;
  margin: 0;
}
h2 {
  font-size: 18px;
  margin: 0 0 12px;
}
.muted {
  color: var(--admin-muted);
  font-size: 13px;
  line-height: 1.8;
}
a {
  color: inherit;
  text-decoration: none;
}
button,
input,
select {
  font: inherit;
}
button,
a.primary {
  cursor: pointer;
}
button {
  border: 1px solid var(--admin-border);
  border-radius: 5px;
  background: var(--admin-surface);
  padding: 9px 14px;
  color: inherit;
}
button:disabled {
  opacity: 0.5;
  cursor: default;
}
.primary {
  display: inline-block;
  border: 1px solid var(--admin-primary);
  border-radius: 5px;
  background: var(--admin-primary);
  color: var(--admin-surface);
  padding: 10px 17px;
  white-space: nowrap;
}
input,
select {
  border: 1px solid var(--admin-border);
  border-radius: 5px;
  padding: 10px 12px;
  min-width: 0;
  background: var(--admin-surface);
  color: inherit;
}
input {
  flex: 1;
}
.toolbar {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
}
.toolbar input {
  min-width: 180px;
}
.list-summary {
  display: flex;
  justify-content: space-between;
  margin: 25px 0 15px;
  font-size: 13px;
}
.table-wrap {
  background: var(--admin-surface);
  overflow: auto;
}
table {
  width: 100%;
  border-collapse: collapse;
  text-align: left;
  font-size: 13px;
  min-width: 650px;
}
th {
  font-weight: 500;
  color: var(--admin-muted);
  background: var(--admin-surface-muted);
  padding: 14px;
}
td {
  padding: 18px 14px;
  border-bottom: 1px solid var(--admin-divider);
}
td:first-child {
  width: 42%;
}
td small {
  display: block;
  color: var(--admin-muted);
  font-size: 12px;
  line-height: 1.8;
  overflow-wrap: anywhere;
}
strong {
  font-weight: 600;
}
.badge {
  display: inline-block;
  background: var(--admin-success-soft);
  color: var(--admin-success);
  padding: 5px 8px;
  border-radius: 4px;
  font-size: 12px;
  white-space: nowrap;
}
.closed {
  background: var(--admin-surface-hover);
  color: var(--admin-muted);
}
.actions {
  white-space: nowrap;
}
.actions > a,
.actions > button {
  display: inline-block;
  color: var(--admin-accent-text);
  margin-right: 12px;
  font-size: 13px;
}
.actions > button {
  padding: 6px 8px;
}
.pagination {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  align-items: center;
  justify-content: flex-end;
  padding: 22px 0;
  font-size: 13px;
}
.current-page {
  background: var(--admin-primary);
  color: var(--admin-surface);
  border-color: var(--admin-primary);
}
.page-jump {
  display: flex;
  align-items: center;
  gap: 8px;
}
.page-jump input {
  width: 64px;
  padding: 8px;
}
.pagination label {
  white-space: nowrap;
}
.detail {
  background: var(--admin-surface);
  margin-top: 22px;
  border: 1px solid var(--admin-border);
  border-radius: 8px;
  padding: 24px;
  overflow-wrap: anywhere;
}
.detail .heading {
  margin-bottom: 10px;
}
.detail p {
  font-size: 13px;
  line-height: 1.8;
}
.notice,
.alert {
  padding: 13px 16px;
  border-radius: 5px;
  font-size: 13px;
  line-height: 1.8;
}
.notice {
  background: var(--admin-success-soft);
  color: var(--admin-success);
}
.alert {
  background: var(--admin-danger-soft);
  color: var(--admin-danger);
}
.confirm {
  background: var(--admin-accent-soft);
  color: var(--admin-accent-text);
}
.confirm {
  padding: 16px;
  margin-top: 16px;
  border-radius: 5px;
}
.confirm button {
  margin-right: 10px;
}
.danger {
  color: var(--admin-danger) !important;
}
.empty {
  padding: 60px 15px;
  text-align: center;
  color: var(--admin-muted);
}
button:focus-visible,
a:focus-visible,
select:focus-visible {
  outline: 2px solid var(--admin-focus);
  outline-offset: 3px;
}
@media (max-width: 800px) {
  .shell {
    grid-template-columns: 1fr;
  }
  aside > p,
  main {
    padding: 22px 18px;
  }
  .heading {
    align-items: flex-start;
  }
  .heading h1 {
    font-size: 24px;
  }
  .list-summary {
    gap: 12px;
  }
  .toolbar select {
    max-width: 150px;
  }
}
/* 手机端按店铺分组展示，编辑及删除入口无需横向滚动即可操作。 */
@media (max-width: 600px) {
  table {
    min-width: 0;
  }
  thead {
    display: none;
  }
  tbody {
    display: block;
  }
  tr {
    display: grid;
    grid-template-columns: 1fr 1fr;
    border-bottom: 1px solid var(--admin-divider);
    padding: 12px 0;
  }
  td {
    border: 0;
    padding: 8px 12px;
  }
  td:first-child {
    width: auto;
    grid-column: 1 / -1;
  }
  td.actions {
    grid-column: 1 / -1;
  }
}
</style>
