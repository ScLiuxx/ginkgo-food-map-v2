<script setup lang="ts">
import { formatRating } from '@/shared/lib/formatRating'
import { useUserLocation } from '@/modules/map/model/userLocation.store'
const userLocation = useUserLocation()
import { computed, ref, watch } from 'vue'
import { campusDistance, priceLabel } from '../model/catalog.logic'
import type { RestaurantReadModel } from '../model/restaurant.types'
import { displayRestaurantTags } from './restaurantPresentation'

const props = defineProps<{ restaurant: RestaurantReadModel }>()
// 封面随目录页批量返回；卡片只加载JPEG，不再为每家店额外请求点评或相册元数据。
const photo = computed(() => props.restaurant.coverPhoto)
const imageFailed = ref(false)
watch([() => props.restaurant.id, () => photo.value?.url], () => {
  imageFailed.value = false
})
const distance = computed(() => campusDistance(props.restaurant, userLocation.location?.center))
// 类型优先采用服务端明确字段；缺省时才兼容旧食堂分类。
const cafeteria = computed(
  () =>
    props.restaurant.restaurantType === 'CANTEEN' ||
    (!props.restaurant.restaurantType && props.restaurant.category === '食堂'),
)
const businessTags = computed(() =>
  displayRestaurantTags(props.restaurant.tags, props.restaurant.category),
)
const distanceLabel = computed(() =>
  distance.value === null
    ? '位置待补充'
    : `距你 ${distance.value < 1 ? Math.round(distance.value * 1000) + '米' : distance.value.toFixed(1) + '公里'}`,
)
</script>

<template>
  <RouterLink class="restaurant-card" :to="`/restaurants/${encodeURIComponent(restaurant.id)}`">
    <div class="card-media">
      <img
        v-if="photo && !imageFailed"
        :src="photo.url"
        :alt="`${restaurant.name}的商户相册封面`"
        loading="lazy"
        decoding="async"
        @error="imageFailed = true"
      />
      <span v-if="photo && !imageFailed" class="photo-credit">商户相册</span>
      <div
        v-else
        class="catalog-photo-placeholder"
        :aria-label="imageFailed ? '图片暂时无法加载' : '暂无商户相册照片'"
      >
        <svg viewBox="0 0 64 64" fill="none" aria-hidden="true">
          <path
            d="M12 28h40c-2 17-9 22-20 22S14 45 12 28Zm7 27h26M25 10v10m9-12v12m9-10v10"
            stroke="currentColor"
            stroke-width="1.6"
            stroke-linecap="round"
          /></svg
        ><small>{{ imageFailed ? '图片暂时无法加载' : '暂无配图' }}</small>
      </div>
    </div>
    <div class="card-copy">
      <h3>{{ restaurant.name }}</h3>
      <template v-if="!cafeteria">
        <p class="card-rating" :class="{ unrated: restaurant.score === null }">
          <span v-if="restaurant.score !== null" class="score-value"
            ><span aria-hidden="true">★</span> {{ formatRating(restaurant.score) }}</span
          >
          <span v-else>暂无评分</span>
          <small class="review-count" :title="`${restaurant.participantCount}人参与评分`"
            >{{ restaurant.reviewCount }}条点评</small
          >
          <span
            class="card-price"
            :class="{ unknown: restaurant.price === null || restaurant.price <= 0 }"
          >
            <template v-if="restaurant.price !== null && restaurant.price > 0"
              ><small>人均</small><b>{{ priceLabel(restaurant) }}</b
              ><small v-if="restaurant.priceKind === 'estimate'">估值</small></template
            >
            <template v-else>人均待补</template>
          </span>
        </p>
        <p class="card-service">
          <span class="category-name" title="用餐方式待补充">{{
            restaurant.category || '类型待补充'
          }}</span>
          <span v-for="tag in businessTags" :key="tag" class="business-tag">{{ tag }}</span>
          <span class="card-accessible-note">用餐方式待补充</span>
          <span v-if="restaurant.closed" class="closed-label">已停业</span>
        </p>
        <p
          v-if="distance !== null || restaurant.address"
          class="card-distance"
          :title="restaurant.address || '距用户当前位置的直线距离'"
        >
          <span v-if="distance !== null">{{ distanceLabel }}</span
          ><span v-if="restaurant.address" class="merchant-address">{{ restaurant.address }}</span>
        </p>
        <span v-else class="card-accessible-note">位置待补充</span>
      </template>
      <template v-else>
        <p class="card-address">{{ restaurant.address || '位置资料待补充' }}</p>
        <p class="card-service">
          食堂 · 窗口资料待补充 <span v-if="restaurant.closed" class="closed-label">· 已停业</span>
        </p>
        <span class="cafeteria-entry">查看食堂 <span aria-hidden="true">→</span></span>
      </template>
    </div>
  </RouterLink>
</template>

<style scoped src="./restaurant-card.css"></style>
