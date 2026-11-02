<script setup lang="ts">
import { ref, toRef, watch } from 'vue'
import { useRestaurantReviewPreview } from '@/modules/review'
/** 仅真实餐厅挂载本组件，复用公开点评配图；失败不影响选店和评分。 */
const props = defineProps<{ restaurantId: string }>()
const { photo } = useRestaurantReviewPreview(toRef(() => props.restaurantId))
const failed = ref(false)
watch(photo, () => {
  failed.value = false
})
</script>
<template>
  <span class="dish-placeholder">
    <img v-if="photo && !failed" :src="photo.url" :alt="photo.label" @error="failed = true" />
    <span v-if="photo && !failed && photo.label.includes('非本店实拍')" class="map-photo-note"
      >非本店实拍</span
    >
    <template v-else
      ><svg viewBox="0 0 80 80" aria-hidden="true">
        <circle cx="40" cy="41" r="22" />
        <circle cx="40" cy="41" r="15" />
        <path d="M10 17v19m6-19v19m-3-19v46m54-46v46m0-46c-8 8-8 22 0 22" /></svg
      ><small>暂无配图</small></template
    >
  </span>
</template>
<style scoped>
.dish-placeholder {
  overflow: hidden;
  position: relative;
}
.dish-placeholder img {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.dish-placeholder svg {
  width: 34px;
  fill: none;
  stroke: currentColor;
  stroke-width: 1.5;
  stroke-linecap: round;
}
.dish-placeholder small {
  margin-top: 4px;
  font-size: 10px;
}
.map-photo-note {
  position: absolute;
  bottom: 0;
  left: 0;
  right: 0;
  padding: 3px 2px;
  background: #202830d9;
  color: #fff;
  text-align: center;
  font-size: 9px;
  line-height: 1.5;
}
</style>
