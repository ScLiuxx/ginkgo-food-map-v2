<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, useId } from 'vue'
import type { RestaurantMapLocation } from '../model/restaurantLocation'
import { loadAMap } from '../sdk/amap'
import type { SDKMap } from '../sdk/types'

const props = defineProps<{
  location: RestaurantMapLocation
  backLabel: string
  opener?: globalThis.HTMLElement
}>()
const emit = defineEmits<{ close: [] }>()
const titleId = useId()
const dialog = ref<globalThis.HTMLDialogElement>()
const host = ref<globalThis.HTMLDivElement>()
const state = ref<'loading' | 'ready' | 'failed'>('loading')
let map: SDKMap | undefined
let timer: ReturnType<typeof globalThis.setTimeout> | undefined
let generation = 0
let previousOverflow: string | undefined

function disposeMap() {
  generation++
  globalThis.clearTimeout(timer)
  map?.destroy()
  map = undefined
}

async function start() {
  disposeMap()
  const ticket = generation
  state.value = 'loading'
  const fail = () => {
    if (ticket !== generation) return
    disposeMap()
    state.value = 'failed'
  }
  // 同一时限覆盖 SDK 请求和底图初始化。关闭与重试都会丢弃之前的回调。
  timer = globalThis.setTimeout(fail, 15000)
  try {
    const sdk = await loadAMap()
    if (ticket !== generation || !host.value) return
    const center = props.location.center
    map = new sdk.Map(host.value, {
      center,
      zoom: 17,
      zooms: [3, 20],
      viewMode: '2D',
      mapStyle: 'amap://styles/whitesmoke',
      features: ['bg', 'road', 'building'],
    })
    map.on('complete', () => {
      if (ticket !== generation) return
      globalThis.clearTimeout(timer)
      state.value = 'ready'
    })
    const pin = globalThis.document.createElement('span')
    pin.className = 'restaurant-location-pin'
    pin.setAttribute('role', 'img')
    pin.setAttribute('aria-label', props.location.name + '的位置')
    map.add([new sdk.Marker({ position: center, content: pin, anchor: 'bottom-center' })])
  } catch {
    fail()
  }
}

onMounted(() => {
  if (dialog.value?.showModal) dialog.value.showModal()
  else if (dialog.value) dialog.value.open = true
  const root = globalThis.document.documentElement
  if (root.style.overflow !== 'hidden') {
    previousOverflow = root.style.overflow
    root.style.overflow = 'hidden'
  }
  void start()
})
onBeforeUnmount(() => {
  disposeMap()
  if (dialog.value?.open) dialog.value.close?.()
  if (previousOverflow !== undefined) {
    globalThis.document.documentElement.style.overflow = previousOverflow
  }
  props.opener?.focus({ preventScroll: true })
})
</script>

<template>
  <Teleport to="body">
    <dialog
      ref="dialog"
      class="restaurant-location-preview"
      :aria-labelledby="titleId"
      @cancel.prevent="emit('close')"
    >
      <header>
        <button type="button" autofocus @click="emit('close')">
          <span aria-hidden="true">←</span> {{ backLabel }}
        </button>
        <h2 :id="titleId">核对店铺位置</h2>
      </header>
      <div class="location-map">
        <div ref="host" class="location-map-host" aria-label="店铺位置地图" />
        <div v-if="state === 'ready'" class="location-map-zoom">
          <button type="button" aria-label="放大地图" @click="map?.zoomIn()">＋</button>
          <button type="button" aria-label="缩小地图" @click="map?.zoomOut()">−</button>
        </div>
        <div v-else class="location-map-state" :role="state === 'failed' ? 'alert' : 'status'">
          <template v-if="state === 'failed'">
            <p>地图暂时无法加载。</p>
            <button type="button" @click="start">重试</button>
          </template>
          <p v-else>正在加载地图…</p>
        </div>
      </div>
      <footer>
        <strong>{{ location.name }}</strong>
        <p>{{ location.address || '地址待补充' }}</p>
      </footer>
    </dialog>
  </Teleport>
</template>

<style scoped>
.restaurant-location-preview {
  position: fixed;
  inset: 0;
  width: 100%;
  height: 100%;
  height: 100dvh;
  max-width: none;
  max-height: none;
  box-sizing: border-box;
  margin: 0;
  padding: 0;
  border: 0;
  background: white;
  color: #303946;
}
.restaurant-location-preview[open] {
  display: grid;
  grid-template-rows: auto minmax(0, 1fr) auto;
}
header {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: max(4px, env(safe-area-inset-top)) 16px 4px;
  border-bottom: 1px solid #edf0f2;
}
h2 {
  margin: 0;
  font-size: 16px;
}
button {
  flex: none;
  min-height: 42px;
  padding: 0 6px;
  border: 0;
  border-radius: 6px;
  background: white;
  color: #825322;
  font: inherit;
  cursor: pointer;
}
button:focus-visible {
  outline: 2px solid #a2a8af;
  outline-offset: -2px;
}
.location-map {
  position: relative;
  background: #f1f3ef;
}
.location-map-host,
.location-map-state {
  position: absolute;
  inset: 0;
}
.location-map-host {
  isolation: isolate;
}
.location-map-state {
  z-index: 2;
  display: grid;
  place-content: center;
  justify-items: center;
  padding: 12px;
  background: #f5f6f5;
}
.location-map-state p {
  margin: 0 0 8px;
}
.location-map-state button {
  padding: 0 14px;
}
.location-map-zoom {
  position: absolute;
  top: 12px;
  right: 12px;
  z-index: 2;
  display: grid;
  border-radius: 6px;
  background: white;
  box-shadow: 0 2px 8px #26332824;
}
.location-map-zoom button {
  width: 42px;
  color: #37444b;
}
footer {
  padding: 12px 16px max(12px, env(safe-area-inset-bottom));
  border-top: 1px solid #edf0f2;
  overflow-wrap: anywhere;
}
footer p {
  margin: 3px 0 0;
  color: #657180;
  font-size: 13px;
}
:deep(.restaurant-location-pin) {
  display: grid;
  place-items: center;
  box-sizing: border-box;
  width: 28px;
  height: 28px;
  border: 2px solid white;
  border-radius: 50% 50% 50% 0;
  background: #d76b15;
  box-shadow: 0 1px 6px #3f2d2960;
  transform: translateY(-6px) rotate(-45deg);
}
:deep(.restaurant-location-pin::after) {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: white;
  content: '';
}
</style>
