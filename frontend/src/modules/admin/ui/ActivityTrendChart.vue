<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, useId, useTemplateRef, watch } from 'vue'
import {
  activityChart,
  activityDateLabel,
  activityNumber,
  type TrendPoint,
} from '../model/activityStatistics'

const props = defineProps<{ points: TrendPoint[]; label: string; unit: string; today: string }>()
const plot = useTemplateRef<globalThis.SVGSVGElement>('plot')
const width = ref(950),
  height = ref(310),
  selected = ref<number | null>(null)
const gradientId = 'activity-area-' + useId()
const geometry = computed(() => activityChart(props.points, width.value, height.value))
const hasKnown = computed(() => props.points.some((point) => point.value !== null))
const active = computed(() =>
  selected.value === null ? null : (geometry.value.coordinates[selected.value] ?? null),
)
const xTicks = computed(() =>
  (width.value < 500 ? [0, 14, 29] : [0, 5, 10, 15, 20, 25, 29])
    .map((index) => geometry.value.coordinates[index])
    .filter((point) => !!point),
)
const last = computed(() => geometry.value.coordinates.at(-1))
const compact = (value: number) =>
  new Intl.NumberFormat('zh-CN', { notation: 'compact', maximumFractionDigits: 1 }).format(value)
const tooltipStyle = computed(() => ({
  left: Math.max(4, Math.min((active.value?.x ?? 0) + 16, width.value - 172)) + 'px',
  top: Math.max(25, Math.min((active.value?.y ?? 40) + 6, height.value - 64)) + 'px',
}))
const statusText = (point: TrendPoint) =>
  point.status === 'NOT_COLLECTED'
    ? '未采集'
    : point.value === null
      ? '暂无数据'
      : point.status !== 'NORMAL'
        ? '部分数据'
        : ''
function move(event: globalThis.PointerEvent) {
  const rect = plot.value?.getBoundingClientRect()
  if (!rect || !rect.width) return
  const g = geometry.value
  const x = ((event.clientX - rect.left) / rect.width) * width.value
  selected.value = Math.max(
    0,
    Math.min(
      props.points.length - 1,
      Math.round(((x - g.left) / (width.value - g.left - g.right)) * (props.points.length - 1)),
    ),
  )
}
function focus() {
  selected.value ??= props.points.length - 1
}
function leave(event: globalThis.PointerEvent) {
  if (event.pointerType !== 'touch') selected.value = null
}
function keyboard(event: globalThis.KeyboardEvent) {
  if (!['ArrowLeft', 'ArrowRight', 'Home', 'End', 'Escape'].includes(event.key)) return
  event.preventDefault()
  if (event.key === 'Escape') selected.value = null
  else if (event.key === 'Home') selected.value = 0
  else if (event.key === 'End') selected.value = props.points.length - 1
  else
    selected.value = Math.max(
      0,
      Math.min(
        props.points.length - 1,
        (selected.value ?? props.points.length - 1) + (event.key === 'ArrowLeft' ? -1 : 1),
      ),
    )
}
let observer: globalThis.ResizeObserver | undefined
function measure() {
  if (!plot.value) return
  width.value = Math.max(200, plot.value.clientWidth || 950)
  height.value = Math.max(180, plot.value.clientHeight || 310)
}
onMounted(() => {
  measure()
  if (typeof globalThis.ResizeObserver !== 'undefined') {
    observer = new globalThis.ResizeObserver(measure)
    if (plot.value) observer.observe(plot.value)
  }
  globalThis.addEventListener('resize', measure)
})
watch(
  () => props.points,
  () => {
    selected.value = null
  },
)
watch(
  plot,
  (element) => {
    observer?.disconnect()
    if (element) {
      measure()
      observer?.observe(element)
    }
  },
  { flush: 'post' },
)
onBeforeUnmount(() => {
  observer?.disconnect()
  globalThis.removeEventListener('resize', measure)
})
</script>

<template>
  <div class="activity-chart-wrap">
    <template v-if="hasKnown">
      <div class="activity-axis-unit">{{ unit }}</div>
      <svg
        ref="plot"
        class="activity-plot"
        :viewBox="`0 0 ${width} ${height}`"
        role="img"
        :aria-label="`${label}最近30天趋势，可用左右方向键查看日期`"
        tabindex="0"
        @pointerdown="move"
        @pointermove="move"
        @pointerleave="leave"
        @focus="focus"
        @blur="selected = null"
        @keydown="keyboard"
      >
        <defs>
          <linearGradient :id="gradientId" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stop-color="#df8e34" stop-opacity="0.13" />
            <stop offset="100%" stop-color="#df8e34" stop-opacity="0.012" />
          </linearGradient>
        </defs>
        <g v-for="tick in geometry.ticks" :key="tick.value" class="activity-axis">
          <line :x1="geometry.left" :x2="width - geometry.right" :y1="tick.y" :y2="tick.y" />
          <text :x="geometry.left - 10" :y="tick.y + 4" text-anchor="end">
            {{ compact(tick.value) }}
          </text>
        </g>
        <g v-for="segment in geometry.segments" :key="segment.points[0]!.date">
          <path v-if="segment.area" :d="segment.area" :fill="`url(#${gradientId})`" />
          <path class="activity-trend-line" :d="segment.line" />
          <circle
            v-if="segment.points.length === 1"
            class="activity-trend-point"
            :cx="segment.points[0]!.x"
            :cy="segment.points[0]!.y!"
            r="4"
          />
        </g>
        <text
          v-for="tick in xTicks"
          :key="tick.date"
          class="activity-axis-label"
          :x="tick.x"
          :y="height - 8"
          :text-anchor="
            tick.index === 0 ? 'start' : tick.index === points.length - 1 ? 'end' : 'middle'
          "
        >
          {{ tick.date.slice(5).replace('-', '/') }}
        </text>
        <circle
          v-if="last?.y !== null && last?.y !== undefined"
          class="activity-trend-point"
          :cx="last.x"
          :cy="last.y"
          r="4"
        />
        <g v-if="active">
          <line
            class="activity-hover-line"
            :x1="active.x"
            :x2="active.x"
            :y1="geometry.top"
            :y2="geometry.bottom"
          />
          <circle
            v-if="active.y !== null"
            class="activity-trend-point"
            :cx="active.x"
            :cy="active.y"
            r="4.5"
          />
        </g>
      </svg>
      <div v-if="active" class="activity-tooltip" :style="tooltipStyle" role="status">
        <div class="activity-tooltip-date">
          {{ activityDateLabel(active.date) }}<span v-if="active.date === today"> · 截至当前</span>
        </div>
        <div class="activity-tooltip-value">
          <span class="activity-dot" /><span>{{ label }}</span
          ><strong>{{ activityNumber(active.value) }}</strong>
        </div>
        <div v-if="active.value === null" class="activity-tooltip-status">
          {{ statusText(active) }}
        </div>
      </div>
    </template>
    <div v-else class="activity-chart-empty" role="status">暂无可展示的数据</div>
    <div class="activity-sr-only">
      <table>
        <caption>
          {{
            label
          }}最近30天数据
        </caption>
        <thead>
          <tr>
            <th scope="col">日期</th>
            <th scope="col">{{ label }}（{{ unit }}）</th>
            <th scope="col">状态</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="point in points" :key="point.date">
            <th scope="row">{{ point.date }}</th>
            <td>{{ activityNumber(point.value) }}</td>
            <td>{{ statusText(point) || (point.date === today ? '截至当前' : '正常采集') }}</td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>
