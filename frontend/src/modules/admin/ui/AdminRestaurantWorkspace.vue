<script setup lang="ts">
import { computed } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRoute } from 'vue-router'
import { useAdminRestaurants } from '../model/useAdminRestaurants'
import AdminSidebar from './AdminSidebar.vue'

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
  lookupId,
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
  <div class="management">
    <header>
      <RouterLink class="brand" to="/admin/reviews"
        ><em>泥电</em>美食地图 <small>管理工作台</small></RouterLink
      >
      <div class="account">
        <span>{{ username }}</span
        ><button :disabled="loggingOut" @click="$emit('logout')">
          {{ loggingOut ? '正在退出…' : '退出登录' }}
        </button>
      </div>
    </header>
    <p v-if="sessionError" class="alert" role="alert">{{ sessionError }}</p>
    <div class="shell">
      <AdminSidebar />
      <main>
        <div class="breadcrumb">管理工作台 / 商户管理{{ editing ? ' / 维护资料' : '' }}</div>
        <slot v-if="editing" />
        <template v-else-if="!sessionChanged">
          <div class="heading">
            <div>
              <h1>商户管理</h1>
              <p class="muted">维护公开店铺资料，让每一次发现都有准确的信息。</p>
            </div>
            <RouterLink class="primary" to="/admin/restaurants/new">＋ 新增店铺</RouterLink>
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
                    {{
                      item.price === null
                        ? '未知'
                        : `¥${item.price}${item.priceKind === 'estimate' ? '（估）' : ''}`
                    }}
                  </td>
                  <td>
                    <span class="badge" :class="{ closed: item.closed }">{{
                      item.closed ? '停业' : '正常经营'
                    }}</span>
                  </td>
                  <td class="actions">
                    <RouterLink
                      v-if="item.restaurantType === 'CANTEEN'"
                      :to="`/admin/restaurants/${item.id}/windows`"
                      >窗口管理</RouterLink
                    >
                    <RouterLink :to="`/admin/restaurants/${item.id}/edit`">编辑</RouterLink
                    ><button :disabled="busy" @click="inspect(item.id)">管理 / 删除</button>
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
          <section class="lookup">
            <h2>按 ID 管理 / 恢复店铺</h2>
            <p class="muted">
              已删除店铺不在公开列表中。输入删除时保留的店铺 ID，可查询状态并恢复。
            </p>
            <form class="toolbar" @submit.prevent="inspect()">
              <input
                v-model="lookupId"
                aria-label="店铺 ID"
                placeholder="输入完整店铺 ID"
                inputmode="numeric"
                :disabled="busy"
              /><button :disabled="busy || inspecting">
                {{ inspecting ? '正在查询…' : '查询店铺' }}
              </button>
            </form>
          </section>
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
            <p v-if="selected.deleted">恢复后将重新出现在公开目录，原经营状态及历史资料保留。</p>
            <p v-else>删除后从公开目录和地图隐藏，历史点评及资料保留，可按 ID 恢复。</p>
            <div class="actions">
              <RouterLink
                v-if="!selected.deleted && !busy"
                :to="`/admin/restaurants/${selected.restaurant.id}/edit`"
                >编辑资料</RouterLink
              ><button
                v-if="!confirming"
                class="danger"
                :disabled="busy"
                @click="confirming = true"
              >
                {{ selected.deleted ? '恢复店铺' : '删除店铺' }}
              </button>
            </div>
            <div v-if="confirming" class="confirm" role="group" aria-label="确认店铺操作">
              <p>
                确认{{ selected.deleted ? '恢复' : '删除' }}「{{ selected.restaurant.name }}」？
              </p>
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
/* 沿用审核页的橙色品牌、白底和细分隔线；窄屏表格独立滚动，不挤压操作按钮。 */
.management {
  min-height: 100vh;
  background: #fff;
  color: #25313d;
  font-family: Inter, 'PingFang SC', 'Microsoft YaHei', sans-serif;
}
header {
  height: 84px;
  padding: 0 40px;
  border-bottom: 1px solid #e9ecef;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
}
.brand {
  display: flex;
  align-items: baseline;
  gap: 14px;
  font-size: 23px;
  font-weight: 650;
}
.brand em {
  font-style: normal;
  color: #e97913;
  font-size: 35px;
}
.brand small {
  font-size: 13px;
  color: #78818b;
  font-weight: 400;
  border-left: 1px solid #e1e5e8;
  padding-left: 20px;
}
.account {
  display: flex;
  align-items: center;
  gap: 20px;
  font-size: 13px;
}
.account span {
  max-width: 140px;
  overflow: hidden;
  text-overflow: ellipsis;
}
.shell {
  display: grid;
  grid-template-columns: 204px minmax(0, 1fr);
  min-height: calc(100vh - 84px);
}
main {
  min-width: 0;
  padding: 30px 40px 60px;
}
.breadcrumb {
  font-size: 12px;
  color: #89919a;
  margin-bottom: 28px;
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
  margin: 0 0 10px;
}
h2 {
  font-size: 18px;
  margin: 0 0 12px;
}
.muted {
  color: #89919a;
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
  border: 1px solid #e1e5e8;
  border-radius: 5px;
  background: white;
  padding: 9px 14px;
  color: inherit;
}
button:disabled {
  opacity: 0.5;
  cursor: default;
}
.primary {
  display: inline-block;
  border: 1px solid #df821e;
  border-radius: 5px;
  background: #e98a20;
  color: white;
  padding: 10px 17px;
  white-space: nowrap;
}
input,
select {
  border: 1px solid #dfe3e7;
  border-radius: 5px;
  padding: 10px 12px;
  min-width: 0;
  background: white;
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
  color: #89919a;
  background: #fafbfc;
  padding: 14px;
}
td {
  padding: 18px 14px;
  border-bottom: 1px solid #edf0f2;
}
td:first-child {
  width: 42%;
}
td small {
  display: block;
  color: #89919a;
  font-size: 12px;
  line-height: 1.8;
  overflow-wrap: anywhere;
}
strong {
  font-weight: 600;
}
.badge {
  display: inline-block;
  background: #edf7ef;
  color: #458458;
  padding: 5px 8px;
  border-radius: 4px;
  font-size: 12px;
  white-space: nowrap;
}
.closed {
  background: #f2f3f5;
  color: #818a94;
}
.actions {
  white-space: nowrap;
}
.actions > a,
.actions > button {
  display: inline-block;
  color: #b16c22;
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
  background: #e98a20;
  color: white;
  border-color: #e98a20;
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
.lookup {
  border-top: 1px solid #e9ecef;
  padding-top: 30px;
  margin-top: 20px;
}
.lookup .toolbar {
  max-width: 560px;
}
.detail {
  margin-top: 22px;
  border: 1px solid #e5e8ec;
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
  background: #edf7ef;
  color: #458458;
}
.alert,
.confirm {
  background: #fff3e8;
  color: #9a5022;
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
  color: #ab493e !important;
}
.empty {
  padding: 60px 15px;
  text-align: center;
  color: #89919a;
}
button:focus-visible,
a:focus-visible,
input:focus-visible,
select:focus-visible {
  outline: 2px solid #b57932;
  outline-offset: 3px;
}
@media (max-width: 800px) {
  header {
    padding: 0 18px;
  }
  .brand {
    font-size: 18px;
    gap: 8px;
  }
  .brand small {
    display: none;
  }
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
  .account {
    gap: 10px;
  }
  .account span {
    max-width: 90px;
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
    border-bottom: 1px solid #edf0f2;
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
