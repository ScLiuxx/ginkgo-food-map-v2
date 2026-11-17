<script setup lang="ts">
import { computed, onBeforeUnmount, useTemplateRef, watch } from 'vue'
import { useRestaurantAlbum } from '../model/useRestaurantAlbum'

/** 商户/食堂正式相册，不读取点评页照片；图片错误和分页错误均保留可重试的独立状态。 */
const props = defineProps<{
  restaurantId: string
  name: string
  /** 详情只留封面，选图及分页放入大图；纠错等既有入口继续使用完整相册。 */
  compact?: boolean
}>()
const emit = defineEmits<{ visibility: [visible: boolean] }>()
const {
  photos,
  selected,
  nextCursor,
  loading,
  loadingMore,
  error,
  moreError,
  reload,
  loadMore,
  selectPhoto,
  loadPhoto,
  failPhoto,
} = useRestaurantAlbum(() => props.restaurantId)
const dialog = useTemplateRef('dialog')
// 加载和故障仍保留入口；不能因暂时没有数据隐藏重试，更不能将首批数量冒充相册总数。
const visible = computed(
  () => !props.compact || loading.value || !!error.value || photos.value.length > 0,
)
watch(visible, (value) => emit('visibility', value), { immediate: true })
watch(
  () => props.restaurantId,
  () => dialog.value?.close?.(),
)
onBeforeUnmount(() => dialog.value?.close?.())
</script>

<template>
  <section
    v-show="visible"
    class="detail-gallery restaurant-album"
    :class="{ 'album-compact': compact }"
    :aria-label="`${name}的商户相册`"
    :aria-busy="loading"
  >
    <div v-if="loading" class="gallery-empty" role="status">正在读取商户相册…</div>
    <div v-else-if="error" class="gallery-empty" role="alert">
      <strong>商户相册暂时无法读取</strong>
      <p>{{ error }}</p>
      <button type="button" @click="reload">重试相册</button>
    </div>
    <template v-else-if="photos.length && selected">
      <button
        v-if="selected.src"
        class="gallery-main"
        type="button"
        :aria-label="`查看${name}的商户相册大图`"
        @click="dialog?.showModal()"
      >
        <img
          :src="selected.src"
          :alt="`${name}的商户照片 ${selected.position + 1}`"
          @error="failPhoto(selected.id)"
        />
        <span class="gallery-credit" :class="{ 'gallery-count': compact }">
          <template v-if="compact"
            ><svg viewBox="0 0 24 24" aria-hidden="true">
              <rect x="3" y="4" width="18" height="16" rx="2" />
              <path d="m3 17 5-5 4 4 3-4 6 6" /></svg
            >{{ photos.length }}{{ nextCursor ? '+' : '' }} 张照片</template
          >
          <template v-else>商户相册<span>查看大图 ↗</span></template>
        </span>
      </button>
      <div v-else class="gallery-empty photo-error" :role="selected.loading ? 'status' : 'alert'">
        <strong>{{ selected.loading ? '照片加载中…' : '这张照片暂时无法显示' }}</strong>
        <p v-if="selected.error">{{ selected.error }}</p>
        <button v-if="!selected.loading" type="button" @click="loadPhoto(selected.id)">
          重试图片
        </button>
        <button v-if="compact && photos.length > 1" type="button" @click="dialog?.showModal()">
          查看其他照片
        </button>
      </div>
      <div v-if="!compact" class="gallery-thumbnails" aria-label="选择商户照片">
        <button
          v-for="photo in photos"
          :key="photo.id"
          type="button"
          :aria-label="`选择商户照片 ${photo.position + 1}`"
          :aria-pressed="selected.id === photo.id"
          @click="selectPhoto(photo.id)"
        >
          <img v-if="photo.src" :src="photo.src" alt="" @error="failPhoto(photo.id)" />
          <span v-else>{{ photo.error ? '重试' : '…' }}</span>
        </button>
      </div>
      <div v-if="!compact" class="album-pagination">
        <span>已加载 {{ photos.length }} 张</span
        ><button v-if="nextCursor" type="button" :disabled="loadingMore" @click="loadMore">
          {{ loadingMore ? '加载中…' : moreError ? '重试加载更多' : '加载更多照片' }}
        </button>
      </div>
      <p v-if="!compact && moreError" class="album-error" role="alert">{{ moreError }}</p>
    </template>
    <div v-else class="gallery-empty" role="status">
      <svg viewBox="0 0 80 80" aria-hidden="true">
        <rect x="14" y="18" width="52" height="44" rx="5" />
        <circle cx="31" cy="33" r="5" />
        <path d="m17 57 17-16 10 9 9-11 10 13" /></svg
      ><strong>暂无商户照片</strong>
    </div>
    <Teleport to="body"
      ><dialog ref="dialog" class="album-lightbox" aria-label="商户相册大图">
        <button type="button" autofocus @click="dialog?.close()">关闭 ×</button
        ><template v-if="selected?.src"
          ><img
            :src="selected.src"
            :alt="`${name}的商户照片 ${selected.position + 1}`"
            @error="failPhoto(selected.id)"
          />
          <p>商户相册 · 第 {{ selected.position + 1 }} 张</p></template
        >
        <template v-else-if="compact && selected">
          <p v-if="selected.loading" role="status">照片加载中…</p>
          <p v-else role="alert">{{ selected.error || '图片暂时无法显示。' }}</p>
          <button type="button" :disabled="selected.loading" @click="loadPhoto(selected.id)">
            重试图片
          </button>
        </template>
        <p v-else>图片暂时无法显示，请关闭后重试。</p>
        <template v-if="compact">
          <div class="gallery-thumbnails" aria-label="选择商户照片">
            <button
              v-for="entry in photos"
              :key="entry.id"
              type="button"
              :aria-label="`选择商户照片 ${entry.position + 1}`"
              :aria-pressed="selected?.id === entry.id"
              @click="selectPhoto(entry.id)"
            >
              <img v-if="entry.src" :src="entry.src" alt="" @error="failPhoto(entry.id)" />
              <span v-else>{{ entry.error ? '重试' : '…' }}</span>
            </button>
          </div>
          <div class="album-pagination">
            <span>已加载 {{ photos.length }} 张</span>
            <button v-if="nextCursor" type="button" :disabled="loadingMore" @click="loadMore">
              {{ loadingMore ? '加载中…' : moreError ? '重试加载更多' : '加载更多照片' }}
            </button>
          </div>
          <p v-if="moreError" class="album-error" role="alert">{{ moreError }}</p>
        </template>
      </dialog></Teleport
    >
  </section>
</template>

<style scoped src="./restaurant-album.css"></style>
