<script setup lang="ts">
import { computed, onBeforeUnmount, ref, useTemplateRef, watch } from 'vue'
import { fetchWindowPhotos, type WindowPhoto } from '../api/canteenWindowApi'
import { toErrorMessage } from '@/shared/lib/toErrorMessage'

/** 窗口相册只读取管理员发布的照片，与点评配图保持分开。 */
const props = defineProps<{ id: string; name: string; compact?: boolean }>()
const emit = defineEmits<{ visibility: [visible: boolean] }>()
const photos = ref<WindowPhoto[]>([]),
  selectedId = ref(''),
  loading = ref(false),
  error = ref('')
const failed = ref<string[]>([]),
  retryVersion = ref(0)
const selected = computed(() => photos.value.find((photo) => photo.id === selectedId.value))
const dialog = useTemplateRef('dialog')
// 详情空相册收起；读取错误和单图错误保留重试，窗口图片不回退到其他来源。
const visible = computed(
  () => !props.compact || loading.value || !!error.value || photos.value.length > 0,
)
watch(visible, (value) => emit('visibility', value), { immediate: true })
let controller: globalThis.AbortController | undefined
let generation = 0
async function load() {
  controller?.abort()
  controller = new globalThis.AbortController()
  const ticket = ++generation
  loading.value = true
  error.value = ''
  photos.value = []
  failed.value = []
  dialog.value?.close?.()
  try {
    const result = await fetchWindowPhotos(props.id, controller.signal)
    if (ticket !== generation) return
    photos.value = result
    selectedId.value = result[0]?.id ?? ''
  } catch (reason) {
    if (ticket === generation) error.value = toErrorMessage(reason)
  } finally {
    if (ticket === generation) loading.value = false
  }
}
function retryImage() {
  failed.value = failed.value.filter((id) => id !== selectedId.value)
  retryVersion.value++
}
watch(() => props.id, load, { immediate: true })
onBeforeUnmount(() => {
  generation++
  controller?.abort()
  dialog.value?.close?.()
})
</script>

<template>
  <section
    v-show="visible"
    class="detail-gallery restaurant-album"
    :class="{ 'album-compact': compact }"
    :aria-label="`${name}的窗口相册`"
    :aria-busy="loading"
  >
    <div v-if="loading" class="gallery-empty" role="status">正在读取窗口相册…</div>
    <div v-else-if="error" class="gallery-empty" role="alert">
      <strong>窗口相册暂时无法读取</strong>
      <p>{{ error }}</p>
      <button type="button" @click="load">重试相册</button>
    </div>
    <template v-else-if="selected">
      <div v-if="failed.includes(selected.id)" class="gallery-empty" role="alert">
        <strong>这张照片暂时无法显示</strong
        ><button type="button" @click="retryImage">重试图片</button>
        <button v-if="compact && photos.length > 1" type="button" @click="dialog?.showModal()">
          查看其他照片
        </button>
      </div>
      <button
        v-else
        class="gallery-main"
        type="button"
        :aria-label="`查看${name}的窗口照片大图`"
        @click="dialog?.showModal()"
      >
        <img
          :src="`${selected.url}?retry=${retryVersion}`"
          :alt="`${name}的窗口照片`"
          @error="failed.push(selected.id)"
        />
        <span class="gallery-credit" :class="{ 'gallery-count': compact }">
          <template v-if="compact"
            ><svg viewBox="0 0 24 24" aria-hidden="true">
              <rect x="3" y="4" width="18" height="16" rx="2" />
              <path d="m3 17 5-5 4 4 3-4 6 6" /></svg
            >{{ photos.length }} 张照片</template
          >
          <template v-else>窗口相册<span>查看大图 ↗</span></template>
        </span>
      </button>
      <div v-if="!compact" class="gallery-thumbnails" aria-label="选择窗口照片">
        <button
          v-for="(photo, index) in photos"
          :key="photo.id"
          type="button"
          :aria-label="`选择窗口照片 ${index + 1}`"
          :aria-pressed="selectedId === photo.id"
          @click="selectedId = photo.id"
        >
          <img
            v-if="!failed.includes(photo.id)"
            :src="`${photo.url}?retry=${retryVersion}`"
            alt=""
            @error="failed.push(photo.id)"
          /><span v-else>重试</span>
        </button>
      </div>
    </template>
    <div v-else class="gallery-empty" role="status"><strong>暂无窗口照片</strong></div>
    <Teleport to="body"
      ><dialog ref="dialog" class="album-lightbox" aria-label="窗口相册大图">
        <button type="button" autofocus @click="dialog?.close()">关闭 ×</button>
        <img
          v-if="selected && !failed.includes(selected.id)"
          :src="`${selected.url}?retry=${retryVersion}`"
          :alt="`${name}的窗口照片`"
          @error="failed.push(selected.id)"
        />
        <template v-else-if="compact && selected">
          <p role="alert">图片暂时无法显示。</p>
          <button type="button" @click="retryImage">重试图片</button>
        </template>
        <p v-else>图片暂时无法显示，请关闭后重试。</p>
        <div v-if="compact" class="gallery-thumbnails" aria-label="选择窗口照片">
          <button
            v-for="(entry, index) in photos"
            :key="entry.id"
            type="button"
            :aria-label="`选择窗口照片 ${index + 1}`"
            :aria-pressed="selectedId === entry.id"
            @click="selectedId = entry.id"
          >
            <img
              v-if="!failed.includes(entry.id)"
              :src="`${entry.url}?retry=${retryVersion}`"
              alt=""
              @error="failed.push(entry.id)"
            /><span v-else>重试</span>
          </button>
        </div>
      </dialog></Teleport
    >
  </section>
</template>

<style scoped src="./restaurant-album.css"></style>
