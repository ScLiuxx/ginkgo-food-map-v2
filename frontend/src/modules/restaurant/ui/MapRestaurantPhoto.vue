<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { RestaurantReadModel } from '../model/restaurant.types'
/** 地图列表/浮层直接使用目录或详情返回的正式封面，避免逐商户读取点评配图。 */
const props = defineProps<{
  restaurantId: string
  coverPhoto?: RestaurantReadModel['coverPhoto']
}>()
const photo = computed(() => props.coverPhoto)
const failed = ref(false)
watch([() => props.restaurantId, () => photo.value?.url], () => {
  failed.value = false
})
</script>
<template>
  <span class="dish-placeholder">
    <img
      v-if="photo && !failed"
      :src="photo.url"
      alt="商户相册封面"
      loading="lazy"
      decoding="async"
      @error="failed = true"
    />
    <template v-else
      ><svg viewBox="0 0 80 80" aria-hidden="true">
        <circle cx="40" cy="41" r="22" />
        <circle cx="40" cy="41" r="15" />
        <path d="M10 17v19m6-19v19m-3-19v46m54-46v46m0-46c-8 8-8 22 0 22" /></svg
      ><small>{{ failed ? '图片暂不可用' : '暂无配图' }}</small></template
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
