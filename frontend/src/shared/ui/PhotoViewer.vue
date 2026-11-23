<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import type { PhotoSelection, ViewerPhoto } from './photoViewer'

const props = withDefaults(
  defineProps<{
    label?: string
    galleryLabel?: string
    hasMore?: boolean
    loadingMore?: boolean
    moreError?: string
  }>(),
  {
    label: '点评照片大图',
    galleryLabel: '本条点评照片',
    hasMore: false,
    loadingMore: false,
    moreError: '',
  },
)
const emit = defineEmits<{
  select: [id: string]
  retry: [id: string]
  imageError: [id: string]
  more: []
  close: []
}>()
const opened = ref(false)

const dialog = ref<globalThis.HTMLDialogElement>()
const image = ref<globalThis.HTMLImageElement>()
const photos = ref<PhotoSelection['photos']>([])
const index = ref(0),
  loading = ref(false),
  failed = ref(false),
  attempt = ref(0)
const current = computed(() => photos.value[index.value])
const multiple = computed(() => photos.value.length > 1 || props.hasMore)
const hasFailure = computed(() => failed.value || !!current.value?.error)
const pending = computed(() => !hasFailure.value && (loading.value || !!current.value?.loading))
const failedThumbnails = ref(new Set<string>())
let opener: globalThis.HTMLElement | undefined, oldOverflow: string | undefined
let generation = 0,
  disposed = false,
  opening = false
let gesture: { id: number; x: number; y: number } | undefined

/** 每次打开固定本组照片；分页更新通过update保留当前ID，迟到打开不能覆盖新页面。 */
async function open(selection: PhotoSelection) {
  const ticket = ++generation
  if (!selection.photos.length) {
    close()
    return
  }
  opening = true
  opened.value = true
  if (!dialog.value?.open) {
    const active = globalThis.document.activeElement
    opener = active instanceof globalThis.HTMLElement ? active : undefined
  }
  photos.value = selection.photos.map((photo) => ({ ...photo }))
  index.value = Number.isInteger(selection.index)
    ? Math.max(0, Math.min(selection.index, photos.value.length - 1))
    : 0
  failedThumbnails.value = new Set()
  resetImage()
  if (current.value) emit('select', current.value.id)
  await nextTick()
  if (disposed || ticket !== generation || !dialog.value) return
  opening = false
  if (!dialog.value.open) {
    if (
      oldOverflow === undefined &&
      globalThis.document.documentElement.style.overflow !== 'hidden'
    )
      oldOverflow = globalThis.document.documentElement.style.overflow
    globalThis.document.documentElement.style.overflow = 'hidden'
    dialog.value.showModal()
  }
}
function cleanup() {
  // 旧close事件可能晚于重新打开到达，不能清理新的相册或释放它持有的滚动锁。
  if (dialog.value?.open || opening) return
  photos.value = []
  gesture = undefined
  if (oldOverflow !== undefined) {
    globalThis.document.documentElement.style.overflow = oldOverflow
    oldOverflow = undefined
  }
  if (opener?.isConnected) opener.focus({ preventScroll: true })
  opener = undefined
  if (opened.value) {
    opened.value = false
    emit('close')
  }
}
function close() {
  generation++
  opening = false
  dialog.value?.close()
  cleanup()
}
function resetImage() {
  loading.value = true
  failed.value = false
  if (current.value) failedThumbnails.value.delete(current.value.id)
  attempt.value++
}
/** 外部相册可追加页面或完成Blob读取，不重开弹窗、不改变当前选择或入口焦点。 */
function update(entries: ViewerPhoto[]) {
  if (!opened.value) return
  const previous = current.value
  photos.value = entries.map((photo) => ({ ...photo }))
  if (!photos.value.length) {
    close()
    return
  }
  const position = photos.value.findIndex((photo) => photo.id === previous?.id)
  index.value = position >= 0 ? position : 0
  if (current.value?.url !== previous?.url || current.value?.error !== previous?.error) resetImage()
}
function retry() {
  if (!current.value) return
  resetImage()
  emit('retry', current.value.id)
}
function choose(next: number) {
  if (next < 0 || next >= photos.value.length || next === index.value) return
  const active = globalThis.document.activeElement
  const arrow =
    active instanceof globalThis.HTMLButtonElement && active.matches('.photo-arrow')
      ? active
      : undefined
  index.value = next
  gesture = undefined
  resetImage()
  if (current.value) emit('select', current.value.id)
  // 首尾箭头禁用会使部分浏览器把焦点退到body，需保留方向键操作的焦点上下文。
  if (arrow)
    void nextTick(() => {
      if (dialog.value?.open && arrow.disabled)
        dialog.value
          .querySelector<globalThis.HTMLButtonElement>('.photo-arrow:not(:disabled)')
          ?.focus({ preventScroll: true })
    })
}
function loaded(event: globalThis.Event) {
  if (event.currentTarget === image.value) loading.value = false
}
function imageFailed(event: globalThis.Event) {
  if (event.currentTarget === image.value) {
    loading.value = false
    failed.value = true
    if (current.value) emit('imageError', current.value.id)
  }
}
function keydown(event: globalThis.KeyboardEvent) {
  if (event.altKey || event.ctrlKey || event.metaKey) return
  if (event.key === 'ArrowLeft' || event.key === 'ArrowRight') {
    event.preventDefault()
    choose(index.value + (event.key === 'ArrowLeft' ? -1 : 1))
  }
}
function pointerStart(event: globalThis.PointerEvent) {
  if (!event.isPrimary) {
    gesture = undefined
    return
  }
  if (event.pointerType === 'mouse' || event.button !== 0) return
  gesture = { id: event.pointerId, x: event.clientX, y: event.clientY }
}
function pointerEnd(event: globalThis.PointerEvent) {
  const start = gesture
  gesture = undefined
  if (!start || start.id !== event.pointerId) return
  const x = event.clientX - start.x,
    y = event.clientY - start.y
  if (Math.abs(x) >= 50 && Math.abs(x) > Math.abs(y) * 1.2) choose(index.value + (x < 0 ? 1 : -1))
}
watch(index, () => {
  void nextTick(() => {
    const selected = dialog.value?.querySelector<globalThis.HTMLButtonElement>(
      '.photo-thumb[aria-current="true"]',
    )
    const strip = selected?.parentElement
    if (selected && strip)
      strip.scrollLeft = Math.max(
        0,
        selected.offsetLeft - (strip.clientWidth - selected.clientWidth) / 2,
      )
  })
})
watch(
  () => props.hasMore,
  (value) => {
    const active = globalThis.document.activeElement
    if (!value && active instanceof globalThis.HTMLElement && active.matches('.photo-more'))
      void nextTick(() =>
        dialog.value
          ?.querySelector<globalThis.HTMLButtonElement>('.photo-thumb[aria-current="true"]')
          ?.focus({ preventScroll: true }),
      )
  },
)
onBeforeUnmount(() => {
  disposed = true
  close()
})
defineExpose({ open, close, update })
</script>

<template>
  <Teleport to="body">
    <dialog
      ref="dialog"
      class="photo-viewer"
      :aria-label="label"
      @cancel.stop.prevent="close"
      @close="cleanup"
      @keydown.stop="keydown"
    >
      <header class="photo-viewer-top">
        <span v-if="multiple" class="photo-counter" aria-live="polite" aria-atomic="true"
          >{{ index + 1 }} / {{ photos.length }}{{ hasMore ? '+' : '' }}</span
        >
        <button type="button" class="photo-close" aria-label="关闭大图" autofocus @click="close">
          <svg
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            stroke-width="1.6"
            stroke-linecap="round"
            aria-hidden="true"
          >
            <path d="m6 6 12 12M18 6 6 18" />
          </svg>
        </button>
      </header>
      <div
        class="photo-stage"
        :class="{ 'single-photo': !multiple }"
        @pointerdown="pointerStart"
        @pointerup="pointerEnd"
        @pointercancel="gesture = undefined"
      >
        <img
          v-if="current?.url"
          :key="`${current.id}:${attempt}`"
          ref="image"
          class="viewer-image"
          :class="{ invisible: pending || hasFailure }"
          :src="current.url"
          :alt="current.alt || `照片 ${index + 1}`"
          draggable="false"
          @load="loaded"
          @error="imageFailed"
        />
        <p v-if="pending" class="photo-load-state" role="status">正在加载照片…</p>
        <div v-else-if="hasFailure" class="photo-load-state" role="alert">
          <p>照片暂时无法显示</p>
          <button type="button" @click="retry">重新加载</button>
        </div>
      </div>
      <template v-if="multiple">
        <button
          type="button"
          class="photo-arrow previous-photo"
          aria-label="上一张"
          :disabled="index === 0"
          @click="choose(index - 1)"
        >
          <svg
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            stroke-width="1.7"
            stroke-linecap="round"
            stroke-linejoin="round"
            aria-hidden="true"
          >
            <path d="m14 5-7 7 7 7" />
          </svg>
        </button>
        <button
          type="button"
          class="photo-arrow next-photo"
          aria-label="下一张"
          :disabled="index === photos.length - 1"
          @click="choose(index + 1)"
        >
          <svg
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            stroke-width="1.7"
            stroke-linecap="round"
            stroke-linejoin="round"
            aria-hidden="true"
          >
            <path d="m10 5 7 7-7 7" />
          </svg>
        </button>
        <nav class="photo-filmstrip" :aria-label="galleryLabel">
          <button
            v-for="(photo, position) in photos"
            :key="photo.id"
            type="button"
            class="photo-thumb"
            :aria-label="`查看第${position + 1}张`"
            :aria-current="index === position ? 'true' : undefined"
            @click="choose(position)"
          >
            <img
              v-if="photo.url && !failedThumbnails.has(photo.id)"
              :src="photo.url"
              alt=""
              @error="failedThumbnails.add(photo.id)"
            /><span v-else>{{ position + 1 }}</span>
          </button>
          <button
            v-if="hasMore"
            type="button"
            class="photo-more"
            :aria-label="moreError ? '重试加载更多' : '加载更多照片'"
            :disabled="loadingMore"
            @click="emit('more')"
          >
            <span aria-hidden="true">{{ loadingMore ? '…' : moreError ? '↻' : '＋' }}</span>
          </button>
        </nav>
      </template>
      <p v-if="moreError" class="photo-more-error" role="alert">{{ moreError }}</p>
      <p v-if="current?.caption" class="photo-source">{{ current.caption }}</p>
    </dialog>
  </Teleport>
</template>

<style scoped>
.photo-viewer {
  position: fixed;
  inset: 0;
  width: 100vw;
  height: 100vh;
  height: 100dvh;
  max-width: none;
  max-height: none;
  margin: 0;
  padding: 0;
  overflow: hidden;
  border: 0;
  border-radius: 0;
  outline: none;
  color: #e5e7eb;
  background: radial-gradient(ellipse at center, #222326 0, #161719 78%);
  font:
    14px/1.5 Inter,
    'Microsoft YaHei',
    sans-serif;
}
.photo-viewer::backdrop {
  background: #161719;
}
.photo-viewer button {
  box-sizing: border-box;
  font: inherit;
  cursor: pointer;
  -webkit-tap-highlight-color: transparent;
}
.photo-viewer button:focus-visible {
  outline: 2px solid #a6adb8;
  outline-offset: 4px;
}
.photo-viewer-top {
  height: 80px;
  position: absolute;
  inset: 0 0 auto;
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 3;
}
.photo-counter {
  font-size: 14px;
  letter-spacing: 2px;
  color: #c6c8cd;
  font-variant-numeric: tabular-nums;
}
.photo-close {
  position: absolute;
  right: 30px;
  top: 20px;
  border: 0;
  width: 42px;
  height: 42px;
  display: grid;
  place-items: center;
  padding: 0;
  background: #ffffff0a;
  color: #d3d5da;
  border-radius: 50%;
}
.photo-close svg {
  width: 20px;
  height: 20px;
}
.photo-stage {
  position: absolute;
  inset: 85px 108px 116px;
  display: flex;
  align-items: center;
  justify-content: center;
  touch-action: pan-y pinch-zoom;
}
.photo-stage.single-photo {
  bottom: 72px;
}
.viewer-image {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: contain;
  user-select: none;
  -webkit-user-drag: none;
}
.viewer-image.invisible {
  opacity: 0;
}
.photo-arrow {
  position: absolute;
  top: calc(50% - 30px);
  width: 48px;
  height: 48px;
  padding: 0;
  border: 0;
  background: #ffffff0a;
  color: #d4d6dc;
  border-radius: 50%;
  display: grid;
  place-items: center;
  z-index: 2;
}
.photo-arrow svg {
  width: 21px;
  height: 21px;
}
.previous-photo {
  left: 34px;
}
.next-photo {
  right: 34px;
}
.photo-arrow:disabled {
  opacity: 0.22;
  cursor: default;
}
.photo-filmstrip {
  position: absolute;
  bottom: 29px;
  bottom: max(29px, env(safe-area-inset-bottom));
  left: 24px;
  right: 24px;
  display: flex;
  align-items: center;
  justify-content: center;
  justify-content: safe center;
  gap: 12px;
  overflow: auto;
  padding: 4px;
  scrollbar-width: none;
}
.photo-thumb {
  flex: 0 0 58px;
  border: 2px solid transparent;
  border-radius: 7px;
  height: 58px;
  padding: 3px;
  background: none;
  opacity: 0.45;
  color: #d3d5da;
}
.photo-thumb img,
.photo-thumb span {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
  border-radius: 3px;
}
.photo-thumb span {
  display: grid;
  place-items: center;
  background: #292c31;
}
.photo-thumb[aria-current='true'] {
  opacity: 1;
  border-color: #e8e9ec;
}
.photo-load-state {
  position: absolute;
  color: #bfc3ca;
  font-size: 13px;
  text-align: center;
}
.photo-load-state button {
  background: #ffffff0c;
  color: #e4e5e8;
  border: 1px solid #636872;
  border-radius: 6px;
  padding: 7px 12px;
}
.photo-source {
  position: absolute;
  bottom: 4px;
  left: 16px;
  right: 16px;
  margin: 0;
  color: #b9bdc4;
  font-size: 11px;
  text-align: center;
  pointer-events: none;
}
.photo-viewer .photo-more {
  flex: 0 0 48px;
  height: 48px;
  padding: 0;
  border: 1px solid #53565d;
  border-radius: 7px;
  background: #ffffff08;
  color: #c6c8cd;
  font-size: 23px;
}
.photo-more:disabled {
  opacity: 0.5;
  cursor: wait;
}
.photo-more-error {
  position: absolute;
  bottom: 98px;
  left: 24px;
  right: 24px;
  margin: 0;
  color: #dfa9a0;
  text-align: center;
  font-size: 12px;
}
.photo-close:hover,
.photo-arrow:not(:disabled):hover {
  background: #ffffff20;
  color: white;
}
.photo-thumb:hover {
  opacity: 1;
}
@media (max-width: 700px) {
  .photo-viewer-top {
    height: 76px;
    padding-top: env(safe-area-inset-top);
  }
  .photo-counter {
    font-size: 13px;
    letter-spacing: 1.5px;
  }
  .photo-close {
    width: 36px;
    height: 36px;
    right: 15px;
    top: 20px;
    top: max(20px, env(safe-area-inset-top));
    background: none;
  }
  .photo-stage {
    inset: 83px 0 115px;
  }
  .photo-stage.single-photo {
    bottom: 60px;
  }
  .photo-arrow {
    top: calc(50% - 25px);
    width: 34px;
    height: 34px;
    background: #11131566;
    color: #f5f5f5;
  }
  .photo-arrow svg {
    width: 17px;
    height: 17px;
  }
  .previous-photo {
    left: 12px;
  }
  .next-photo {
    right: 12px;
  }
  .photo-filmstrip {
    gap: 10px;
    bottom: max(30px, env(safe-area-inset-bottom));
  }
  .photo-thumb {
    flex-basis: 53px;
    height: 53px;
    padding: 3px;
    border-radius: 6px;
  }
  .photo-thumb img {
    border-radius: 2px;
  }
}
@media (max-height: 580px) {
  .photo-viewer-top {
    height: 52px;
  }
  .photo-close {
    top: 8px;
  }
  .photo-stage {
    inset: 58px 65px 82px;
  }
  .photo-stage.single-photo {
    bottom: 28px;
  }
  .photo-filmstrip {
    bottom: 14px;
  }
  .photo-thumb {
    flex-basis: 47px;
    height: 47px;
  }
}
</style>
