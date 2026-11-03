<script setup lang="ts">
import type { PlaceResult } from '../model/placeSearch'
import { SubmitRestaurantButton } from '@/modules/restaurant'
defineProps<{
  records: PlaceResult[]
  hiddenCount: number
  loading: boolean
  error: string | null
  total: number
  page: number
  pages: number
  selected: string | null
  keyword?: string
  canSubmit?: boolean
}>()
defineEmits<{ select: [id: string]; retry: []; pageChange: [page: number] }>()
</script>
<template>
  <section class="place-results" aria-label="高德地点搜索结果">
    <div class="map-result-heading">
      <h2>高德地点</h2>
      <span role="status">{{
        loading ? '正在搜索…' : error ? '暂不可用' : `共 ${total} 个匹配地点`
      }}</span>
    </div>
    <p class="place-scope">优先成都，不限当前地图范围。分类、排序及消费筛选仅作用于本站结果。</p>
    <p v-if="error" class="place-state" role="alert">
      {{ error }} <button @click="$emit('retry')">重试</button>
    </p>
    <div v-else class="place-list">
      <button
        v-for="place in records"
        :key="place.id"
        class="place-row"
        :class="{ selected: selected === `amap:${place.id}` }"
        :aria-pressed="selected === `amap:${place.id}`"
        @click="$emit('select', `amap:${place.id}`)"
      >
        <span class="place-icon" aria-hidden="true"
          ><svg viewBox="0 0 24 24" fill="none">
            <path
              d="M19 10c0 5-7 11-7 11S5 15 5 10a7 7 0 1 1 14 0Z"
              stroke="currentColor"
              stroke-width="1.4"
            />
            <circle cx="12" cy="10" r="2.3" stroke="currentColor" stroke-width="1.4" /></svg
        ></span>
        <span class="place-copy"
          ><strong>{{ place.name }}</strong
          ><span>{{ place.address || '地址待补充' }}</span
          ><small v-if="place.district">{{ place.district }}</small
          ><small class="place-match">{{
            place.matchState === 'matched'
              ? '本站已收录，可查看本站详情'
              : place.matchState === 'checking'
                ? '正在核对本站收录状态…'
                : place.matchState === 'unavailable'
                  ? '收录状态暂无法核实'
                  : '暂未匹配本站餐厅'
          }}</small></span
        >
      </button>
      <p v-if="!loading && !records.length" class="place-state">
        {{
          hiddenCount
            ? '本页地点已在上方本站结果中列出。'
            : total
              ? '本页没有可定位的地点，请尝试其他页或关键词。'
              : '高德未返回符合条件的餐饮地点。'
        }}
      </p>
    </div>
    <nav v-if="!error && pages > 1" class="place-pagination" aria-label="高德地点分页">
      <button :disabled="loading || page <= 1" @click="$emit('pageChange', page - 1)">上一页</button
      ><span>{{ page }} / {{ pages }}</span
      ><button :disabled="loading || page >= pages" @click="$emit('pageChange', page + 1)">
        下一页
      </button>
    </nav>
    <p class="place-scope">
      来源：高德地图 · 结果临时展示，不自动加入本站。{{ total > 1000 ? '最多查看前100页。' : '' }}
    </p>
    <div v-if="canSubmit && keyword?.trim() && !loading && !error" class="place-placeholder">
      <span>没有找到对应店铺？</span><SubmitRestaurantButton :keyword="keyword" />
    </div>
  </section>
</template>
<style scoped>
.place-results {
  margin-top: 24px;
  border-top: 1px solid #eceeee;
  padding-top: 16px;
}
.map-result-heading {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  gap: 8px;
  padding: 0 8px;
}
h2 {
  font-size: 13px;
  font-weight: 550;
  color: #4e5954;
  margin: 0;
}
.map-result-heading > span {
  color: #67716b;
  font-size: 10px;
}
.place-scope {
  font-size: 10px;
  line-height: 1.7;
  color: #6a746e;
  margin: 10px 8px;
}
.place-row {
  display: flex;
  gap: 12px;
  width: 100%;
  padding: 16px 8px;
  border: 0;
  border-radius: 7px;
  background: transparent;
  color: #28332c;
  text-align: left;
  cursor: pointer;
}
.place-row:hover {
  background: #f7f8f7;
}
.place-row.selected {
  background: #f0f3f1;
}
.place-icon {
  display: grid;
  place-items: center;
  width: 36px;
  height: 42px;
  flex: 0 0 36px;
  color: #687c6e;
}
svg {
  width: 22px;
  height: 22px;
}
.place-copy {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 5px;
}
strong {
  font-size: 15px;
  font-weight: 600;
  line-height: 1.45;
  overflow-wrap: anywhere;
}
.place-copy > span {
  font-size: 12px;
  color: #5f6a63;
  line-height: 1.6;
}
small {
  color: #6c766f;
  font-size: 10px;
  line-height: 1.6;
}
.place-match {
  margin-top: 2px;
}
.place-state {
  padding: 12px 8px;
  color: #647068;
  font-size: 12px;
  line-height: 1.7;
}
.place-state button {
  border: 0;
  background: transparent;
  color: inherit;
  text-decoration: underline;
  cursor: pointer;
}
.place-pagination {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  color: #657068;
}
.place-pagination button {
  min-height: 36px;
  border: 0;
  border-radius: 6px;
  background: transparent;
  padding: 6px 10px;
  color: #3f4d44;
  cursor: pointer;
}
.place-placeholder {
  margin: 20px 8px;
  display: grid;
  gap: 8px;
  font-size: 11px;
  color: #68726b;
}
.place-placeholder button {
  min-height: 36px;
  width: fit-content;
  padding: 8px 12px;
  border: 0;
  border-radius: 6px;
  background: #f2f4f2;
  color: #677168;
}
button:focus-visible {
  outline: 2px solid #9b713b;
  outline-offset: 2px;
}
</style>
