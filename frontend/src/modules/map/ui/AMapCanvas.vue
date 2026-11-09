<script setup lang="ts">
import { formatRating } from '@/shared/lib/formatRating'
import { onMounted, onUnmounted, ref, watch } from 'vue'
import { loadAMap } from '../sdk/amap'
import type { AMapSDK, SDKMap, SDKMarker, SDKBounds } from '../sdk/types'
import type { MapBounds, MapPoint, MapFocus } from '../api/mapApi'
import { storeToRefs } from 'pinia'
import { useUserLocation } from '../model/userLocation.store'

const props = withDefaults(
  defineProps<{
    points: MapPoint[]
    selected: string | null
    focus?: MapFocus | null
  }>(),
  { focus: null },
)
const emit = defineEmits<{
  move: [bounds: MapBounds]
  select: [id: string]
  hover: [id: string]
  leave: []
  ready: []
}>()
const host = ref<globalThis.HTMLDivElement>()
const error = ref<string | null>(null)
const loading = ref(false)
let map: SDKMap | undefined, sdk: AMapSDK | undefined
let markers: SDKMarker[] = []
let destroyed = false,
  attempt = 0
let resize: globalThis.ResizeObserver | undefined
const userLocation = useUserLocation()
const { locating, location, message: locationMessage } = storeToRefs(userLocation)
const locate = userLocation.locate
let myMarker: SDKMarker | undefined
let lastLocated: number | undefined

/** 本人位置单独管理，餐厅点位刷新不会清掉蓝点，也不会把蓝点当作餐厅。 */
watch([location, () => props.points], ([position]) => {
  if (!map || !sdk || destroyed) return
  if (myMarker) map.remove([myMarker])
  myMarker = undefined
  if (!position) return
  // 点位刷新时仅恢复蓝点，不反复拉回视野。
  const shouldCenter = !lastLocated || lastLocated !== position.timestamp
  lastLocated = position.timestamp
  const dot = globalThis.document.createElement('span')
  dot.className = 'amap-my-location'
  dot.setAttribute('role', 'img')
  dot.setAttribute('aria-label', '我的位置（点击定位时获取）')
  dot.title = '我的位置（单次定位，不持续跟踪）'
  myMarker = new sdk.Marker({
    position: position.center,
    content: dot,
    anchor: 'center',
    zIndex: 200,
  })
  map.add([myMarker])
  // 使用现有moveend/zoomend -> 防抖视口查询，继续只展示本站数据库里的餐厅。
  if (shouldCenter) map.setZoomAndCenter(16, position.center)
})

/** 读取SDK实际视口，经度在前；固定精度避免浮点微动引起相同区域反复查询。 */
function moved() {
  if (!map || destroyed) return
  const box = map.getBounds() as SDKBounds
  if (!box?.getSouthWest) return
  const sw = box.getSouthWest(),
    ne = box.getNorthEast()
  const round = (n: number) => Number(n.toFixed(6))
  emit('move', {
    west: round(sw.getLng()),
    south: round(sw.getLat()),
    east: round(ne.getLng()),
    north: round(ne.getLat()),
  })
}
/** 点位HTML只生成受控数字/固定文本，店名使用DOM textContent，避免内容注入。 */
function draw() {
  if (!map || !sdk) return
  map.remove(markers)

  markers = props.points.map((point) => {
    const button = globalThis.document.createElement('button')
    // 未评分用小圆点，详情仍通过名称访问；避免100个“待评”文本挤占地图。
    button.className = `amap-score-pin${point.kind === 'amap' ? ' external' : ''}${point.score === null ? ' unrated' : ''}${point.id === props.selected ? ' active' : ''}${point.businessStatus === 'CLOSED' ? ' closed' : ''}`
    button.textContent = point.score === null ? '' : formatRating(point.score)
    button.setAttribute('aria-label', point.name)
    button.setAttribute('aria-pressed', String(point.id === props.selected))
    button.addEventListener('click', () => emit('select', point.id))
    button.addEventListener('mouseenter', () => emit('hover', point.id))
    button.addEventListener('mouseleave', () => emit('leave'))
    button.addEventListener('focus', () => emit('hover', point.id))
    button.addEventListener('blur', () => emit('leave'))
    return new sdk!.Marker({
      position: [point.longitude, point.latitude],
      content: button,
      anchor: 'bottom-center',
    })
  })
  map.add(markers)
}
function highlight() {
  markers.forEach((marker, i) => {
    const element = marker.getContent()
    if (element instanceof globalThis.HTMLElement) {
      const active = props.points[i]?.id === props.selected
      element.classList.toggle('active', active)
      element.setAttribute('aria-pressed', String(active))
    }
  })
}
async function start() {
  const ticket = ++attempt
  loading.value = true
  error.value = null
  let timer: ReturnType<typeof globalThis.setTimeout> | undefined
  try {
    sdk = await Promise.race([
      loadAMap(),
      new Promise<never>((_, reject) => {
        timer = globalThis.setTimeout(() => reject(new Error('地图加载超时，请重试。')), 15000)
      }),
    ])
    if (destroyed || ticket !== attempt || !host.value) return
    // 初始成都视野覆盖两个校区；回校区仍指既有清水河参考点，不暗改距离口径。
    map = new sdk.Map(host.value, {
      center: [104.02, 30.67],
      zoom: 11,
      viewMode: '2D',
      // 使用官方底图配色减少道路和标注的饱和度，不改写第三方DOM或版权样式。
      mapStyle: 'amap://styles/whitesmoke',
    })
    map.on('complete', () => {
      if (ticket === attempt && !destroyed) {
        emit('ready')
        moved()
      }
    })
    map.on('moveend', moved)
    map.on('zoomend', moved)
    resize = new globalThis.ResizeObserver(() => moved())
    resize.observe(host.value)
    draw()
    focusResult()
  } catch (reason) {
    if (!destroyed && ticket === attempt)
      error.value = reason instanceof Error ? reason.message : '高德地图加载失败，请重试。'
  } finally {
    globalThis.clearTimeout(timer)
    if (!destroyed && ticket === attempt) loading.value = false
  }
}
watch(() => props.points, draw)
watch(() => props.selected, highlight)
/** 搜索结果可在当前视口外，主动点击才移动；悬停和后台搜索不抢走用户视角。 */
function focusResult() {
  if (!destroyed && props.focus && map)
    map.setZoomAndCenter(props.focus.zoom ?? 16, [props.focus.longitude, props.focus.latitude])
}
watch(() => props.focus, focusResult)
onMounted(() => {
  void start()
})
onUnmounted(() => {
  destroyed = true
  attempt++
  resize?.disconnect()
  map?.off('moveend', moved)
  map?.off('zoomend', moved)
  map?.destroy()
  map = undefined
  markers = []
  myMarker = undefined
})
</script>
<template>
  <div class="amap-canvas">
    <div ref="host" class="amap-host" aria-label="高德餐厅地图" />
    <div v-if="error || loading" class="sdk-state" :role="error ? 'alert' : 'status'">
      <h2>{{ error ? '地图暂不可用' : '正在加载高德地图…' }}</h2>
      <p>{{ error }}</p>
      <p>仍可筛选餐厅、查看点评和打开高德路线。</p>
      <button v-if="error" @click="start">重试加载</button
      ><RouterLink to="/previews/map">查看界面示意 →</RouterLink>
    </div>
    <div v-if="!error && map" class="amap-controls">
      <button aria-label="放大地图" @click="map?.zoomIn()">＋</button
      ><button aria-label="缩小地图" @click="map?.zoomOut()">−</button
      ><button class="reset" @click="map?.setZoomAndCenter(11, [104.02, 30.67])">
        ⌖<small>总览</small>
      </button>
      <button
        class="reset locate"
        :disabled="locating"
        aria-describedby="location-notice"
        @click="locate"
      >
        <svg width="20" height="20" viewBox="0 0 24 24" fill="none" aria-hidden="true">
          <circle cx="12" cy="12" r="7" stroke="currentColor" stroke-width="1.6" />
          <circle cx="12" cy="12" r="2.5" fill="currentColor" />
          <path d="M12 1v4m0 14v4M1 12h4m14 0h4" stroke="currentColor" stroke-width="1.6" />
        </svg>
        <small>{{ locating ? '定位中…' : '我的位置' }}</small>
      </button>
    </div>
    <details v-if="!error && map" class="location-notice">
      <summary>定位与隐私</summary>
      <p id="location-notice">点击后申请一次定位，位置交由高德转换坐标；本站不绑定账号保存。</p>
    </details>
    <p v-if="!error && locationMessage" class="location-status" role="status" aria-live="polite">
      {{ locationMessage }}
    </p>
  </div>
</template>
<style scoped>
.amap-canvas,
.amap-host {
  position: absolute;
  inset: 0;
}
.amap-canvas {
  background: #f1f3f3;
}
.sdk-state {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-direction: column;
  text-align: center;
  padding: 30px;
  background: #f7f8f8;
}
.sdk-state h2 {
  font-size: 20px;
  color: #3b4545;
}
.sdk-state p {
  max-width: 430px;
  font-size: 12px;
  color: #667070;
  line-height: 1.8;
}
.sdk-state button {
  background: white;
  border: 1px solid #dfe3e3;
  border-radius: 8px;
  padding: 10px;
  color: #555f5f;
}
.sdk-state a {
  font-size: 12px;
  color: #7e5b27;
  margin-top: 15px;
}
.amap-controls {
  position: absolute;
  right: 16px;
  top: 16px;
  display: grid;
  gap: 0;
  z-index: 2;
  border-radius: 7px;
  overflow: hidden;
  box-shadow: 0 1px 6px #1e2c2c1c;
}
.amap-controls button {
  border: 0;
  min-width: 42px;
  min-height: 42px;
  background: white;
  font-size: 21px;
  cursor: pointer;
  color: #485353;
}
.amap-controls button:hover:enabled {
  background: #f4f6f6;
}
.amap-controls button:focus-visible,
.location-notice summary:focus-visible {
  outline: 2px solid #9b713b;
  outline-offset: -3px;
}
.amap-controls .reset {
  display: grid;
  font-size: 20px;
  padding: 8px 5px;
  min-height: 52px;
  border-top: 1px solid #eff1f1;
}
.reset small {
  font-size: 10px;
}
.amap-controls .locate {
  justify-items: center;
  align-content: center;
  gap: 3px;
  color: #2876d9;
}
.locate:disabled {
  cursor: wait;
  opacity: 0.6;
}
.location-notice,
.location-status {
  position: absolute;
  top: 16px;
  right: 70px;
  z-index: 2;
  max-width: min(230px, calc(100% - 90px));
  margin: 0;
  padding: 7px 10px;
  border-radius: 6px;
  background: #fffffff0;
  color: #606a6a;
  font-size: 11px;
  line-height: 1.6;
}
.location-notice summary {
  cursor: pointer;
  font-size: 10px;
}
.location-notice[open] {
  z-index: 5;
  box-shadow: 0 2px 12px #25353512;
}
.location-notice[open] summary {
  margin-bottom: 8px;
}
.location-status {
  top: 56px;
  pointer-events: none;
}
.location-notice p {
  margin: 0;
}
.location-notice p + p {
  margin-top: 5px;
}
/* 固定的装饰光晕只突出蓝点，不代表按米绘制的定位精度范围。 */
:deep(.amap-my-location) {
  display: block;
  width: 18px;
  height: 18px;
  border: 3px solid white;
  border-radius: 50%;
  background: #2876ed;
  box-shadow:
    0 0 0 7px #2876ed30,
    0 2px 6px #1237;
}
:deep(.amap-score-pin) {
  position: relative;
  white-space: nowrap;
  padding: 0 7px;
  border: 1px solid #d8dfdb;
  min-width: 36px;
  height: 28px;
  border-radius: 16px;
  background: white;
  color: #39473e;
  font-size: 12px;
  font-weight: 650;
  box-shadow: 0 1px 4px #25352b24;
  cursor: pointer;
}
:deep(.amap-score-pin::before) {
  content: '';
  position: absolute;
  inset: -6px;
}
:deep(.amap-score-pin.unrated) {
  width: 18px;
  height: 18px;
  min-width: 18px;
  padding: 0;
  border: 3px solid white;
  background: #65776c;
}
:deep(.amap-score-pin:hover) {
  background: #e8eeea;
}
:deep(.amap-score-pin.external:not(.active)) {
  background: white;
  border-color: #65776c;
}
:deep(.amap-score-pin.active) {
  background: #f5a928;
  color: #33250e;
  border-color: white;
  box-shadow:
    0 0 0 2px #b67525,
    0 2px 6px #26342c22;
  z-index: 1;
}
:deep(.amap-score-pin.closed) {
  background: #8e9592;
}
:deep(.amap-score-pin:focus-visible) {
  outline: 3px solid #6b4d1b;
  outline-offset: 3px;
}
@media (max-width: 700px) {
  .amap-host {
    bottom: 0;
  }
  .sdk-state {
    padding-bottom: 200px;
    justify-content: flex-start;
    padding-top: 25px;
  }
  .sdk-state h2 {
    font-size: 17px;
  }
}
</style>
