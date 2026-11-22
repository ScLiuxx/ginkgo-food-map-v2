<script setup lang="ts">
import AppLink from '@/shared/ui/AppLink.vue'
import { computed, ref } from 'vue'
import RestaurantDeleteDialog from '@/modules/restaurant/ui/RestaurantDeleteDialog.vue'
import type { RestaurantManagement } from '@/modules/restaurant/api/restaurantApi'
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
  busy,
  sessionChanged,
  reload,
  loadCategories,
  search,
} = useAdminRestaurants(enabled)

const deleteTarget = ref<{ id: string; name: string } | null>(null)
function removed(result: RestaurantManagement) {
  notice.value = `「${result.restaurant.name}」已删除。`
  void reload()
}
function closeDelete() {
  deleteTarget.value = null
  busy.value = false
}
function clearSearch() {
  keyword.value = ''
  search()
}

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
            <div class="search-field">
              <input
                v-model="keyword"
                aria-label="搜索店铺"
                placeholder="搜索店名、地址或标签"
                type="search"
                :disabled="busy"
              />
              <button
                v-if="keyword"
                type="button"
                class="mobile-only search-clear"
                aria-label="清空搜索"
                :disabled="busy"
                @click="clearSearch"
              >
                ×
              </button>
              <button class="mobile-only search-submit" aria-label="查询店铺" :disabled="busy">
                <svg
                  viewBox="0 0 24 24"
                  fill="none"
                  stroke="currentColor"
                  stroke-width="1.6"
                  aria-hidden="true"
                >
                  <circle cx="10.5" cy="10.5" r="6.5" />
                  <path d="m16 16 4.5 4.5" />
                </svg>
              </button>
            </div>
            <select v-model="category" class="desktop-only" aria-label="美食分类" :disabled="busy">
              <option value="">全部美食</option>
              <option v-for="item in categories" :key="item.code" :value="item.code">
                {{ item.name }}
              </option>
            </select>
            <button class="primary desktop-only" :disabled="busy">查询</button
            ><button type="button" class="desktop-only" :disabled="busy || loading" @click="reload">
              刷新
            </button>
          </form>
          <p v-if="categoryError" class="alert" role="alert">
            分类加载失败：{{ categoryError }}
            <button @click="loadCategories">重试</button>
          </p>
          <div class="list-summary">
            <span class="desktop-only">{{ loading ? '正在加载…' : `共 ${total} 家公开店铺` }}</span>
            <span class="mobile-only mobile-total" role="status">{{
              loading ? '正在加载…' : `共 ${total.toLocaleString('zh-CN')} 家店铺`
            }}</span>
            <label class="mobile-only mobile-category"
              ><select
                v-model="category"
                aria-label="筛选美食分类"
                :disabled="busy"
                @change="search"
              >
                <option value="">全部分类</option>
                <option v-for="item in categories" :key="item.code" :value="item.code">
                  {{ item.name }}
                </option></select
              ><svg
                viewBox="0 0 12 12"
                fill="none"
                stroke="currentColor"
                stroke-width="1.5"
                aria-hidden="true"
              >
                <path d="m3 4.5 3 3 3-3" /></svg
            ></label>
            <span class="muted desktop-only">包含停业店铺 · 每页 {{ pageSize }} 家</span>
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
                  <td class="merchant-summary">
                    <strong class="merchant-name">{{ item.name }}</strong
                    ><small class="merchant-address" :title="item.address">{{
                      item.address || '地址待补充'
                    }}</small
                    ><small class="merchant-id">ID：{{ item.id }}</small>
                    <span class="mobile-only mobile-meta"
                      ><span>{{
                        item.restaurantType === 'CANTEEN' ? '食堂' : item.category || '分类未知'
                      }}</span
                      ><template v-if="item.price !== null"
                        ><span class="meta-separator" aria-hidden="true">·</span
                        ><span>¥{{ item.price }} / 人</span></template
                      ></span
                    >
                  </td>
                  <td class="desktop-only">
                    {{ item.restaurantType === 'CANTEEN' ? '食堂' : item.category || '分类未知' }}
                  </td>
                  <td class="desktop-only">
                    {{ item.price === null ? '未知' : `¥${item.price}` }}
                  </td>
                  <td class="merchant-status">
                    <span class="badge" :class="{ closed: item.closed }"
                      ><span class="desktop-only">{{ item.closed ? '停业' : '正常经营' }}</span
                      ><span class="mobile-only mobile-status-label">{{
                        item.closed ? '停业' : '营业'
                      }}</span></span
                    >
                  </td>
                  <td class="actions">
                    <AppLink :to="`/admin/restaurants/${item.id}/edit`">编辑</AppLink
                    ><button
                      :disabled="busy"
                      class="danger"
                      @click="deleteTarget = { id: item.id, name: item.name }"
                    >
                      删除
                    </button>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
          <nav class="pagination desktop-only" aria-label="店铺分页">
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
          <nav class="mobile-only mobile-pagination" aria-label="店铺分页">
            <select v-model="pageSize" aria-label="每页数量" :disabled="loading || busy">
              <option v-for="size in [10, 20, 50, 100]" :key="size" :value="size">
                {{ size }} 条 / 页
              </option>
            </select>
            <div class="mobile-pager">
              <button :disabled="page <= 1 || loading || busy" aria-label="上一页" @click="page--">
                <svg
                  viewBox="0 0 16 16"
                  fill="none"
                  stroke="currentColor"
                  stroke-width="1.4"
                  aria-hidden="true"
                >
                  <path d="m10 4-4 4 4 4" />
                </svg></button
              ><span class="mobile-page-number"
                ><b>{{ page }}</b> / {{ Math.max(1, totalPages) }}</span
              ><button
                :disabled="page >= totalPages || loading || busy"
                aria-label="下一页"
                @click="page++"
              >
                <svg
                  viewBox="0 0 16 16"
                  fill="none"
                  stroke="currentColor"
                  stroke-width="1.4"
                  aria-hidden="true"
                >
                  <path d="m6 4 4 4-4 4" />
                </svg>
              </button>
            </div>
          </nav>
          <RestaurantDeleteDialog
            v-if="deleteTarget"
            :id="deleteTarget.id"
            :name="deleteTarget.name"
            @busy="busy = $event"
            @deleted="removed"
            @close="closeDelete"
          />
        </template>
        <p v-else class="alert" role="alert">登录状态已变化，请重新进入商户管理。</p>
      </main>
    </div>
  </div>
</template>

<style scoped>
/* 电脑保留表格；手机将同一批资料排成紧凑列表，不增加另一套查询状态。 */
.mobile-only {
  display: none;
}
.search-field {
  display: contents;
}
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
/* 手机搜索、分类和分页复用原查询；窄屏内完整展示操作，不依赖横向滚动。 */
@media (max-width: 600px) {
  .desktop-only,
  .merchant-id {
    display: none;
  }
  main {
    padding: 22px 16px 28px;
  }
  .heading {
    align-items: center;
    gap: 12px;
    margin-bottom: 19px;
  }
  .heading h1 {
    font-size: 23px;
  }
  .heading .primary {
    padding: 9px 13px;
    font-size: 13px;
    border-radius: 6px;
  }
  .toolbar {
    display: block;
  }
  .search-field {
    display: flex;
    align-items: center;
    height: 45px;
    overflow: hidden;
    border: 1px solid var(--admin-border);
    border-radius: 7px;
    background: var(--admin-surface);
  }
  .toolbar input {
    width: 0;
    min-width: 0;
    padding: 12px 0 12px 14px;
    border: 0;
    border-radius: 0;
    background: transparent;
    font-size: 13px;
  }
  .toolbar input::-webkit-search-cancel-button {
    -webkit-appearance: none;
  }
  .toolbar input::placeholder {
    color: #9099a5;
  }
  .search-field .search-clear,
  .search-field .search-submit {
    display: grid;
    flex: none;
    place-items: center;
    width: 44px;
    height: 43px;
    padding: 0;
    border: 0;
    border-radius: 0;
    color: #7e8996;
  }
  .search-clear {
    font-size: 21px;
  }
  .search-submit svg {
    width: 18px;
    height: 18px;
  }
  .list-summary {
    align-items: center;
    gap: 8px;
    height: 54px;
    margin: 0;
    color: #818b98;
    font-size: 12px;
  }
  .mobile-total {
    display: inline;
    font-variant-numeric: tabular-nums;
  }
  .mobile-category {
    position: relative;
    display: flex;
    align-items: center;
    flex: none;
  }
  .mobile-category select {
    appearance: none;
    max-width: 135px;
    min-height: 44px;
    padding: 10px 19px 10px 6px;
    border: 0;
    background: transparent;
    color: #6b7785;
    font-size: 12px;
  }
  .mobile-category svg {
    position: absolute;
    right: 0;
    width: 12px;
    height: 12px;
    pointer-events: none;
  }
  .table-wrap {
    overflow: hidden;
    border-radius: 8px;
  }
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
    position: relative;
    display: grid;
    grid-template-columns: minmax(0, 1fr) auto;
    align-items: center;
    gap: 6px 9px;
    border-bottom: 1px solid var(--admin-divider);
    padding: 17px 16px 10px;
  }
  tr:last-child {
    border-bottom: 0;
  }
  td {
    border: 0;
    padding: 0;
  }
  td.merchant-summary {
    display: contents;
    width: auto;
  }
  .merchant-name {
    grid-column: 1 / -1;
    grid-row: 1;
    padding-right: 42px;
    min-width: 0;
    font-size: 14px;
    line-height: 1.65;
    overflow-wrap: anywhere;
  }
  .merchant-address {
    grid-column: 1 / -1;
    grid-row: 2;
    margin: 0;
    overflow: hidden;
    color: #818c9a;
    font-size: 11px;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  .mobile-meta {
    display: flex;
    grid-column: 1;
    grid-row: 3;
    align-items: center;
    min-width: 0;
    gap: 6px;
    overflow: hidden;
    color: #788695;
    font-size: 11px;
    white-space: nowrap;
  }
  .mobile-meta > span:first-child {
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .meta-separator {
    color: #b1b8c1;
  }
  .merchant-status {
    position: absolute;
    top: 22px;
    right: 16px;
  }
  .merchant-status .badge {
    padding: 0;
    background: none;
    color: #718879;
    font-size: 10px;
  }
  .merchant-status .closed {
    color: #8b939d;
  }
  .mobile-status-label {
    display: inline-flex;
    align-items: center;
    gap: 4px;
  }
  .mobile-status-label::before {
    content: '';
    width: 4px;
    height: 4px;
    border-radius: 50%;
    background: currentColor;
  }
  td.actions {
    display: flex;
    grid-column: 2;
    grid-row: 3;
    align-items: center;
    gap: 2px;
    margin: 0 -4px 0 0;
  }
  .actions > a,
  .actions > button {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    min-width: 42px;
    min-height: 42px;
    margin: 0;
    padding: 8px 4px;
    border: 0;
    background: transparent;
    font-size: 12px;
  }
  .mobile-pagination {
    display: flex;
    justify-content: space-between;
    align-items: center;
    gap: 8px;
    padding: 18px 0 4px;
    color: #7e8996;
    font-size: 11px;
  }
  .mobile-pagination select {
    max-width: 115px;
    padding: 8px 0;
    border: 0;
    background: transparent;
    color: inherit;
    font-size: inherit;
  }
  .mobile-pager {
    display: flex;
    align-items: center;
    gap: 8px;
  }
  .mobile-pager button {
    display: grid;
    place-items: center;
    width: 34px;
    height: 34px;
    padding: 0;
  }
  .mobile-pager svg {
    width: 14px;
    height: 14px;
  }
  .mobile-page-number {
    min-width: 55px;
    text-align: center;
    font-variant-numeric: tabular-nums;
  }
  .mobile-page-number b {
    font-weight: 500;
    color: #596777;
  }
}
@media (max-width: 350px) {
  main {
    padding-right: 13px;
    padding-left: 13px;
  }
  tr {
    padding-right: 13px;
    padding-left: 13px;
    column-gap: 6px;
  }
  .merchant-name {
    font-size: 13px;
  }
  .merchant-status {
    right: 13px;
  }
  .mobile-meta {
    gap: 4px;
  }
}
</style>
