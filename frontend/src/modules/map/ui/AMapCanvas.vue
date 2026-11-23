<script setup lang="ts">
import AppLink from '@/shared/ui/AppLink.vue'
import { formatRating } from '@/shared/lib/formatRating'
import { onMounted, onUnmounted, ref, watch } from 'vue'
import { loadAMap } from '../sdk/amap'
import { createMyLocationMarker } from '../sdk/myLocationMarker'
import { clusterMapPoints, type MarkerCluster } from '../model/markerClusters'
import type { AMapSDK, SDKMap, SDKMarker, SDKBounds } from '../sdk/types'
import type { MapBounds, MapPoint, MapFocus, MapCamera } from '../api/mapApi'
import { storeToRefs } from 'pinia'
import { useUserLocation } from '../model/userLocation.store'
import MapControls from './MapControls.vue'

const props = withDefaults(
  defineProps<{
    points: MapPoint[]
    selected: string | null
    focus?: MapFocus | null
    initialCamera?: MapCamera | null
  }>(),
  { focus: null, initialCamera: null },
)
const emit = defineEmits<{
  move: [bounds: MapBounds]
  select: [id: string]
  hover: [id: string]
  leave: []
  ready: []
  view: [camera: MapCamera]
}>()
const host = ref<globalThis.HTMLDivElement>()
const error = ref<string | null>(null)
const loading = ref(false)
let map: SDKMap | undefined, sdk: AMapSDK | undefined
let markers: SDKMarker[] = []
let clusterKeys: string[] = []
const clusterMembers = ref<MapPoint[]>([])
let expandingCluster: string[] | undefined
let destroyed = false,
  attempt = 0
let resize: globalThis.ResizeObserver | undefined
const userLocation = useUserLocation()
const { locating, location, message: locationMessage } = storeToRefs(userLocation)
const locate = userLocation.locate
let myMarker: SDKMarker | undefined
let lastLocated: number | undefined
// 点选餐厅引起的程序定位只改变图面；直到用户主动操作地图，继续保留本轮查询范围。
let preserveResults = Boolean(props.initialCamera)
/** 路由离开前直接读取SDK，动画尚未结束时也不把旧视角当成当前位置。 */
function captureCamera(): MapCamera | null {
  if (!map || destroyed) return null
  const center = map.getCenter()
  return { center: [center.getLng(), center.getLat()], zoom: map.getZoom() }
}
defineExpose({ captureCamera })
function resumeViewport() {
  preserveResults = false
}
/** 普通聚合点逐级放大并按新范围查询；同坐标或最大缩放时提供可点选名单。 */
function expandCluster(cluster: MarkerCluster) {
  if (!map) return
  const first = cluster.points[0]!
  const coincident = cluster.points.every(
    (point) =>
      Math.abs(point.longitude - first.longitude) < 0.0000001 &&
      Math.abs(point.latitude - first.latitude) < 0.0000001,
  )
  if (coincident || map.getZoom() >= 20) {
    expandingCluster = undefined
    clusterMembers.value = cluster.points
    return
  }
  resumeViewport()
  expandingCluster = cluster.points.map((point) => point.id)
  map.setZoomAndCenter(Math.min(20, map.getZoom() + 2), cluster.center)
}
function selectMember(id: string) {
  clusterMembers.value = []
  emit('select', id)
}
/** 缩放即使由点选触发也须重算密度；候选范围是否重查仍由moved独立决定。 */
function zoomed() {
  const clusters = draw()
  // 最后一级仍未分开的点击目标直接展示名单，不能要求用户在同一个数字上再点一次。
  if (expandingCluster && map && map.getZoom() >= 20) {
    clusterMembers.value = findCluster(clusters ?? [], expandingCluster)?.points ?? []
  }
  expandingCluster = undefined
  moved()
}
/** 只保留完全相同的成员集合；顺序和资料更新不能关掉名单，成员变化或拆组则关闭。 */
function findCluster(clusters: MarkerCluster[], ids: string[]): MarkerCluster | undefined {
  const members = new Set(ids)
  return clusters.find(
    (cluster) =>
      cluster.points.length > 1 &&
      cluster.points.length === members.size &&
      cluster.points.every((point) => members.has(point.id)),
  )
}
function userKeydown(event: globalThis.KeyboardEvent) {
  if (
    ['ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight', '+', '-', '=', 'Enter', ' '].includes(
      event.key,
    )
  )
    resumeViewport()
}

/** 本人位置单独管理，餐厅点位刷新不会清掉蓝点，也不会把蓝点当作餐厅。 */
watch([location, () => props.points], ([position]) => {
  if (!map || !sdk || destroyed) return
  if (myMarker) map.remove([myMarker])
  myMarker = undefined
  if (!position) return
  // 点位刷新时仅恢复蓝点，不反复拉回视野。
  const shouldCenter = !lastLocated || lastLocated !== position.timestamp
  lastLocated = position.timestamp
  myMarker = createMyLocationMarker(sdk, position.center)
  map.add([myMarker])
  // 使用现有moveend/zoomend -> 防抖视口查询，继续只展示本站数据库里的餐厅。
  if (shouldCenter) map.setZoomAndCenter(16, position.center)
})

/** 读取SDK实际视口，经度在前；固定精度避免浮点微动引起相同区域反复查询。 */
function moved() {
  if (!map || destroyed) return
  const camera = captureCamera()
  if (camera) emit('view', camera)
  // 恢复与点选定位只改变图面；真实地图手势才重新确定候选范围。
  if (preserveResults) return
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
function draw(selectionOnly = false) {
  if (!map || !sdk) return
  const clusters = clusterMapPoints(
    props.points,
    (point) => {
      const pixel = map!.lngLatToContainer([point.longitude, point.latitude])
      return { x: pixel.getX(), y: pixel.getY() }
    },
    props.selected,
  )
  const keys = clusters.map((cluster) => JSON.stringify(cluster.points.map((point) => point.id)))
  // 悬停单店若未改变分组，只更新样式；保留原DOM，避免移除鼠标/键盘焦点引起反复进出。
  if (
    selectionOnly &&
    keys.length === clusterKeys.length &&
    keys.every((key) => clusterKeys.includes(key))
  ) {
    markers.forEach((marker) => {
      const element = marker.getContent()
      if (!(element instanceof globalThis.HTMLElement) || !element.dataset.id) return
      const active = element.dataset.id === props.selected
      element.classList.toggle('active', active)
      element.setAttribute('aria-pressed', String(active))
    })
    return clusters
  }
  if (clusterMembers.value.length) {
    clusterMembers.value =
      findCluster(
        clusters,
        clusterMembers.value.map((point) => point.id),
      )?.points ?? []
  }
  clusterKeys = keys
  map.remove(markers)
  markers = clusters.map((cluster) => {
    const button = globalThis.document.createElement('button')
    button.type = 'button'
    // 聚合数字与单店评分分开造型和读屏名称，统计本批成员且不包含本人位置。
    if (cluster.points.length > 1) {
      button.className = 'amap-cluster-pin'
      button.textContent = String(cluster.points.length)
      button.setAttribute('aria-label', `${cluster.points.length}家已加载店铺，点击展开`)
      button.dataset.count = String(cluster.points.length)
      button.addEventListener('click', () => expandCluster(cluster))
      return new sdk!.Marker({ position: cluster.center, content: button, anchor: 'center' })
    }
    const point = cluster.points[0]!
    // 未评分用小圆点，详情仍通过名称访问；避免100个“待评”文本挤占地图。
    button.className = `amap-score-pin${point.kind === 'amap' ? ' external' : ''}${point.score === null ? ' unrated' : ''}${point.id === props.selected ? ' active' : ''}${point.businessStatus === 'CLOSED' ? ' closed' : ''}`
    button.textContent = point.score === null ? '' : formatRating(point.score)
    button.setAttribute('aria-label', point.name)
    button.setAttribute('aria-pressed', String(point.id === props.selected))
    button.dataset.id = point.id
    button.addEventListener('click', () => emit('select', point.id))
    button.addEventListener('mouseenter', () => emit('hover', point.id))
    button.addEventListener('mouseleave', () => emit('leave'))
    button.addEventListener('focus', () => emit('hover', point.id))
    button.addEventListener('blur', () => emit('leave'))
    return new sdk!.Marker({
      position: [point.longitude, point.latitude],
      content: button,
      anchor: 'bottom-center',
      zIndex: 150,
    })
  })
  map.add(markers)
  return clusters
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
      center: props.initialCamera?.center ?? [104.02, 30.67],
      zoom: props.initialCamera?.zoom ?? 11,
      zooms: [3, 20],
      viewMode: '2D',
      // 使用官方底图配色；原生版权内容保留，手机端只随面板调整其位置。
      mapStyle: 'amap://styles/whitesmoke',
    })
    map.on('complete', () => {
      if (ticket === attempt && !destroyed) {
        emit('ready')
        moved()
      }
    })
    map.on('moveend', moved)
    map.on('zoomend', zoomed)
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
watch(
  () => props.points,
  () => draw(),
)
watch(
  () => props.selected,
  () => draw(true),
)
/** 主动点选才定位，是否保留候选范围由调用方明确指定，悬停不改变地图视角。 */
function focusResult() {
  if (!destroyed && props.focus && map) {
    preserveResults = props.focus.preserveResults ?? false
    map.setZoomAndCenter(props.focus.zoom ?? 16, [props.focus.longitude, props.focus.latitude])
  }
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
  map?.off('zoomend', zoomed)
  map?.destroy()
  map = undefined
  markers = []
  myMarker = undefined
})
</script>
<template>
  <div
    class="amap-canvas"
    @pointerdown.capture="resumeViewport"
    @wheel.capture="resumeViewport"
    @keydown.capture="userKeydown"
  >
    <div ref="host" class="amap-host" aria-label="高德餐厅地图" />
    <section v-if="clusterMembers.length" class="cluster-members" aria-label="聚合店铺">
      <div class="cluster-members-heading">
        <strong>{{ clusterMembers.length }}家已加载店铺</strong>
        <button aria-label="关闭聚合店铺" @click="clusterMembers = []">×</button>
      </div>
      <p>店铺位置接近，请选择一家查看</p>
      <ul>
        <li v-for="point in clusterMembers" :key="point.id">
          <button @click="selectMember(point.id)">{{ point.name }}</button>
        </li>
      </ul>
    </section>
    <div v-if="error || loading" class="sdk-state" :role="error ? 'alert' : 'status'">
      <h2>{{ error ? '地图暂不可用' : '正在加载高德地图…' }}</h2>
      <p>{{ error }}</p>
      <p>地图恢复后会按实际范围加载店铺，也可返回首页浏览餐厅和点评。</p>
      <button v-if="error" @click="start">重试加载</button><AppLink to="/">返回首页找店 →</AppLink>
    </div>
    <MapControls
      v-if="!error && map"
      :locating="locating"
      :message="locationMessage"
      @locate="locate"
    >
      <button type="button" aria-label="放大地图" @click="map?.zoomIn()">＋</button>
      <button type="button" aria-label="缩小地图" @click="map?.zoomOut()">−</button>
      <button type="button" class="reset" @click="map?.setZoomAndCenter(11, [104.02, 30.67])">
        ⌖<small>总览</small>
      </button>
    </MapControls>
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
/* 聚合采用数量圆标，与评分胶囊区分；44像素命中区方便手机点选。 */
:deep(.amap-cluster-pin) {
  width: 44px;
  height: 44px;
  padding: 0;
  border: 3px solid white;
  border-radius: 50%;
  background: #456650;
  color: white;
  font-size: 15px;
  font-weight: 700;
  box-shadow:
    0 0 0 5px #45665026,
    0 2px 6px #25352b30;
  cursor: pointer;
}
:deep(.amap-cluster-pin:hover) {
  background: #304e39;
}
:deep(.amap-cluster-pin:focus-visible) {
  outline: 3px solid #a1742e;
  outline-offset: 4px;
}
.cluster-members {
  position: absolute;
  top: 58px;
  left: 12px;
  z-index: 3;
  width: min(280px, calc(100% - 24px));
  max-height: min(280px, 40%);
  overflow: auto;
  padding: 12px;
  box-sizing: border-box;
  border: 1px solid #d8dfdb;
  border-radius: 12px;
  background: white;
  box-shadow: 0 3px 16px #25352b24;
  color: #39473e;
  font-size: 13px;
}
.cluster-members-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.cluster-members p {
  font-size: 12px;
  color: #667070;
}
.cluster-members ul {
  list-style: none;
  margin: 0;
  padding: 0;
}
.cluster-members button {
  min-height: 44px;
  border: 0;
  background: transparent;
  color: inherit;
  cursor: pointer;
}
.cluster-members li button {
  width: 100%;
  text-align: left;
  overflow-wrap: anywhere;
  border-top: 1px solid #eff1f1;
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
