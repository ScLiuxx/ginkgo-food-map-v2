<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { DetailPhotoUpdate } from '../model/detailPhotos'

const props = defineProps<{
  update: DetailPhotoUpdate
  name: string
  canteen?: boolean
  compact?: boolean
}>()
const selectedId = ref('')
const failed = ref<string[]>([])
const dialog = ref<globalThis.HTMLDialogElement>()
const usable = computed(() =>
  props.update.photos
    .filter((photo) => !failed.value.includes(photo.id))
    .sort((a, b) => Number(a.example) - Number(b.example)),
)
const selected = computed(
  () => usable.value.find((photo) => photo.id === selectedId.value) ?? usable.value[0],
)
watch(
  () => props.update.restaurantId,
  () => {
    selectedId.value = ''
    failed.value = []
    dialog.value?.close?.()
  },
)
watch(
  () => props.update.photos,
  () => {
    failed.value = []
    dialog.value?.close?.()
  },
)
// 页面不另发图片请求；复用点评列表当前页的响应，发布、翻页和删除后同步更新。
</script>

<template>
  <section
    class="detail-gallery"
    :class="{
      'has-photos': selected,
      'compact-gallery': compact || canteen || (selected && usable.every((photo) => photo.example)),
    }"
    :aria-label="`${name}的点评配图`"
  >
    <template v-if="selected">
      <button
        class="gallery-main"
        type="button"
        :aria-label="`查看${selected.username}的点评配图大图`"
        @click="dialog?.showModal()"
      >
        <img
          :src="selected.url"
          :alt="`${selected.username}的${selected.example ? '示意配图，非本店实拍' : '点评配图'}`"
          @error="failed.push(selected.id)"
        />
        <span class="gallery-credit"
          >{{ selected.example ? '示意配图 · 非本店实拍' : `同学点评配图 · ${selected.username}`
          }}<span>查看大图 ↗</span></span
        >
      </button>
      <div class="gallery-side">
        <div class="gallery-thumbnails" aria-label="选择点评配图">
          <button
            v-for="photo in compact ? usable : usable.slice(0, 6)"
            :key="photo.id"
            type="button"
            :aria-pressed="selected.id === photo.id"
            :aria-label="`选择${photo.username}的${photo.example ? '示意配图' : '点评配图'}`"
            @click="selectedId = photo.id"
          >
            <img :src="photo.url" alt="" loading="lazy" @error="failed.push(photo.id)" />
            <small v-if="photo.example">非本店实拍</small>
          </button>
        </div>
      </div>
    </template>
    <div v-else class="gallery-empty" :aria-live="update.state === 'loading' ? 'off' : 'polite'">
      <svg viewBox="0 0 80 80" aria-hidden="true">
        <rect x="14" y="18" width="52" height="44" rx="5" />
        <circle cx="31" cy="33" r="5" />
        <path d="m17 57 17-16 10 9 9-11 10 13" />
      </svg>
      <strong>{{
        update.state === 'loading'
          ? '正在读取点评照片…'
          : update.state === 'error'
            ? '点评照片暂时无法读取'
            : failed.length
              ? '照片暂时无法显示'
              : canteen
                ? '还没有食堂环境照片'
                : '暂无实拍照片'
      }}</strong>
    </div>
    <Teleport to="body"
      ><dialog ref="dialog" class="gallery-lightbox" aria-label="点评配图大图">
        <button type="button" autofocus @click="dialog?.close()">关闭 ×</button
        ><template v-if="selected"
          ><img
            :src="selected.url"
            :alt="selected.example ? '示意配图，非本店实拍' : `${selected.username}的点评配图`"
          />
          <p>
            {{ selected.example ? '示意配图 · 非本店实拍' : `来自 ${selected.username} 的点评` }}
          </p></template
        >
      </dialog></Teleport
    >
  </section>
</template>

<style scoped>
.detail-gallery {
  margin: 24px 0;
}
.has-photos {
  display: grid;
  grid-template-columns: minmax(0, 2fr) minmax(0, 1fr);
  gap: 16px;
}
.compact-gallery .gallery-main {
  height: 150px;
}
.compact-gallery .gallery-thumbnails button {
  height: 70px;
}
.gallery-main {
  display: block;
  position: relative;
  border: 0;
  padding: 0;
  overflow: hidden;
  background: #f3f4f5;
  border-radius: 6px;
  cursor: zoom-in;
  min-width: 0;
  height: 310px;
}
.gallery-main > img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}
.gallery-credit {
  position: absolute;
  bottom: 0;
  left: 0;
  right: 0;
  padding: 12px 14px;
  background: linear-gradient(transparent, #18212bd9);
  color: white;
  text-align: left;
  font-size: 12px;
  display: flex;
  justify-content: space-between;
  gap: 8px;
}
.gallery-side {
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  min-width: 0;
}
.gallery-thumbnails {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}
.gallery-thumbnails button {
  position: relative;
  border: 2px solid transparent;
  background: #f3f4f5;
  border-radius: 5px;
  height: 148px;
  padding: 0;
  cursor: pointer;
  overflow: hidden;
}
.gallery-thumbnails button[aria-pressed='true'] {
  border-color: #c77d19;
}
.gallery-thumbnails img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}
.gallery-thumbnails small {
  position: absolute;
  bottom: 0;
  left: 0;
  right: 0;
  font-size: 10px;
  background: #202830d9;
  color: #fff;
}
.gallery-caption {
  padding: 16px 0 2px;
}
.gallery-caption strong {
  font-size: 16px;
  font-weight: 600;
  color: #303b47;
}
.gallery-caption p,
.gallery-caption a {
  font-size: 12px;
  color: #626e7b;
  line-height: 1.9;
}
.gallery-caption a {
  color: #9b6219;
  text-decoration: none;
}
.gallery-empty {
  display: flex;
  align-items: center;
  justify-content: center;
  flex-direction: column;
  min-height: 160px;
  padding: 24px;
  background: #f5f6f7;
  border-radius: 6px;
  color: #626e7b;
  text-align: center;
}
.gallery-empty svg {
  width: 46px;
  height: 46px;
  stroke: #9ba5af;
  fill: none;
  stroke-width: 1.5;
  margin-bottom: 10px;
}
.gallery-empty strong {
  font-size: 14px;
  font-weight: 500;
}
.gallery-empty p {
  margin: 6px 0 0;
  font-size: 12px;
  line-height: 1.8;
}
.gallery-lightbox {
  border: 0;
  border-radius: 8px;
  padding: 16px;
  max-width: calc(100vw - 24px);
  background: #fff;
}
.gallery-lightbox::backdrop {
  background: #151d27cc;
}
.gallery-lightbox img {
  display: block;
  max-width: 85vw;
  max-height: 72dvh;
  margin-top: 12px;
}
.gallery-lightbox p {
  font-size: 13px;
  color: #626e7b;
}
.gallery-lightbox button {
  background: none;
  border: 1px solid #dde1e5;
  border-radius: 5px;
  padding: 10px 16px;
  font: inherit;
  cursor: pointer;
}
button:focus-visible,
a:focus-visible {
  outline: 2px solid #ad6f26;
  outline-offset: 3px;
}
@media (max-width: 700px) {
  .has-photos {
    display: block;
  }
  .gallery-main {
    width: 100%;
    height: 240px;
  }
  .gallery-thumbnails {
    display: flex;
    margin-top: 10px;
    overflow-x: auto;
  }
  .gallery-thumbnails button {
    flex: 0 0 70px;
    height: 64px;
  }
  .gallery-caption {
    display: none;
  }
  .gallery-empty {
    min-height: 145px;
    padding: 20px;
  }
  .gallery-credit {
    font-size: 11px;
  }
}
</style>
