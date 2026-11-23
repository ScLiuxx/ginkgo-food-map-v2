<script setup lang="ts">
import { computed, ref, useTemplateRef } from 'vue'
import AdminHeader from './AdminHeader.vue'
import AdminSidebar from './AdminSidebar.vue'
import ActivityTrendChart from './ActivityTrendChart.vue'
import { useAdminActivityStatistics } from '../model/useAdminActivityStatistics'
import {
  activityDateLabel,
  activityMetrics,
  activityNumber,
  activityTime,
  activityTrend,
  beijingDate,
  shiftActivityDate,
  type ActivityMetric,
} from '../model/activityStatistics'
import './admin-activity.css'

defineProps<{ username: string; loggingOut: boolean; sessionError: string | null }>()
defineEmits<{ logout: [] }>()
const { data, loading, error, unavailable, load } = useAdminActivityStatistics()
const metric = ref<ActivityMetric>('active')
const tablist = useTemplateRef<globalThis.HTMLDivElement>('tablist')
const currentMetric = computed(() => activityMetrics.find((item) => item.key === metric.value)!)
const today = computed(() => data.value?.days.find((day) => day.date === data.value?.currentDate))
const points = computed(() => (data.value ? activityTrend(data.value, metric.value) : []))
const summaries = computed(() => [
  {
    label: '今日活跃',
    value: today.value?.activeUserCount,
  },
  {
    label: '近7日活跃',
    value: data.value?.sevenDayActiveUserCount,
  },
  {
    label: '今日新增用户',
    value: today.value?.newUserCount,
  },
  {
    label: '今日发布点评',
    value: today.value?.publishedReviewCount,
  },
])
const range = computed(() =>
  data.value
    ? `${shiftActivityDate(data.value.currentDate, -29).slice(5).replace('-', '.')} — ${data.value.currentDate.slice(5).replace('-', '.')}`
    : '',
)
function changeTab(event: globalThis.KeyboardEvent) {
  if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return
  event.preventDefault()
  const index = activityMetrics.findIndex((item) => item.key === metric.value)
  const next =
    event.key === 'Home'
      ? 0
      : event.key === 'End'
        ? 2
        : (index + (event.key === 'ArrowLeft' ? 2 : 1)) % 3
  metric.value = activityMetrics[next]!.key
  tablist.value?.querySelectorAll<globalThis.HTMLButtonElement>('button')[next]?.focus()
}
</script>

<template>
  <div class="activity-workspace admin-theme">
    <AdminHeader :username="username" :logging-out="loggingOut" @logout="$emit('logout')" />
    <div class="activity-shell">
      <AdminSidebar />
      <main class="activity-main">
        <div class="activity-inner">
          <div class="activity-heading">
            <h1>使用统计</h1>
            <span v-if="data" class="activity-updated"
              >{{ activityDateLabel(data.currentDate) }} · 更新于
              {{ activityTime(data.fetchedAt) }}</span
            >
          </div>
          <p v-if="sessionError" class="activity-error" role="alert">{{ sessionError }}</p>
          <div v-if="error && data" class="activity-update-error" role="status">
            更新失败，当前显示上次数据。<button type="button" :disabled="loading" @click="load">
              重试
            </button>
          </div>
          <section class="activity-stats" aria-label="使用情况概览" :aria-busy="loading && !data">
            <div v-for="(item, index) in summaries" :key="item.label" class="activity-metric">
              <div class="activity-metric-label">{{ item.label }}</div>
              <div class="activity-metric-number">
                {{ activityNumber(item.value)
                }}<span
                  v-if="index === 0 && data?.enabled && item.value != null"
                  class="activity-current-note"
                  >截至当前</span
                >
              </div>
            </div>
          </section>
          <section class="activity-chart-card" aria-label="最近30天趋势">
            <div class="activity-chart-header">
              <div class="activity-chart-title">
                <h2>最近30天</h2>
                <span v-if="range" class="activity-date-range">{{ range }}</span>
              </div>
              <div
                ref="tablist"
                class="activity-tabs"
                role="tablist"
                aria-label="趋势指标"
                @keydown="changeTab"
              >
                <button
                  v-for="item in activityMetrics"
                  :id="`activity-tab-${item.key}`"
                  :key="item.key"
                  type="button"
                  role="tab"
                  :aria-selected="metric === item.key"
                  aria-controls="activity-chart-panel"
                  :tabindex="metric === item.key ? 0 : -1"
                  :class="{ active: metric === item.key }"
                  @click="metric = item.key"
                >
                  {{ item.label }}
                </button>
              </div>
            </div>
            <div
              id="activity-chart-panel"
              role="tabpanel"
              :aria-labelledby="`activity-tab-${metric}`"
              :aria-busy="loading && !data"
            >
              <div v-if="!data" class="activity-state" :role="error ? 'alert' : 'status'">
                <span v-if="loading">正在读取统计</span>
                <template v-else-if="error"
                  ><span>{{ unavailable ? '使用统计暂未开放' : error }}</span
                  ><button type="button" @click="load">重试</button></template
                >
                <span v-else>等待读取统计</span>
              </div>
              <div v-else-if="!data.enabled" class="activity-state" role="status">统计尚未启用</div>
              <ActivityTrendChart
                v-else
                :points="points"
                :label="currentMetric.detail"
                :unit="currentMetric.unit"
                :today="data.currentDate"
              />
            </div>
            <div v-if="data?.enabled" class="activity-chart-foot">
              <span>北京时间</span><span>今日数据截至当前</span>
            </div>
          </section>
          <div v-if="data?.startedAt" class="activity-data-origin">
            采集起点 {{ beijingDate(data.startedAt).replaceAll('-', '.') }}
            {{ activityTime(data.startedAt) }}
          </div>
        </div>
      </main>
    </div>
  </div>
</template>
