<script setup lang="ts">
import { useUserLocation } from '@/modules/map/model/userLocation.store'
const userLocation = useUserLocation()
import { computed } from 'vue'
import type { RestaurantReadModel } from '../model/restaurant.types'
import { campusDistance, priceLabel } from '../model/catalog.logic'
import MapRestaurantPhoto from './MapRestaurantPhoto.vue'
import { displayRestaurantTags } from './restaurantPresentation'
/** 同一商户在列表及浮层共用明确的评分/消费分栏；缺失数据使用文字状态，不补造数值。 */
const props = defineProps<{ restaurant: RestaurantReadModel; selected: boolean }>()
const distance = computed(() => campusDistance(props.restaurant, userLocation.location?.center))
const businessTags = computed(() =>
  displayRestaurantTags(props.restaurant.tags, props.restaurant.category),
)
const categoryLabel = computed(() =>
  props.restaurant.category === '其他' ? '' : props.restaurant.category,
)
const distanceLabel = computed(() =>
  distance.value === null
    ? '位置待确认'
    : `距你 ${distance.value < 1 ? Math.round(distance.value * 1000) + '米' : distance.value.toFixed(1) + '公里'}`,
)
defineEmits<{ select: [] }>()
</script>
<template>
  <button
    class="map-restaurant"
    :class="{ selected }"
    :aria-pressed="selected"
    @click="$emit('select')"
  >
    <MapRestaurantPhoto
      v-if="!restaurant.id.startsWith('demo-')"
      :restaurant-id="restaurant.id"
      :cover-photo="restaurant.coverPhoto"
    />
    <span v-else class="dish-placeholder" aria-label="示例配图占位"
      ><svg viewBox="0 0 80 80" aria-hidden="true">
        <circle cx="40" cy="41" r="22" />
        <circle cx="40" cy="41" r="15" />
        <path d="M10 17v19m6-19v19m-3-19v46m54-46v46m0-46c-8 8-8 22 0 22" /></svg
      ><small>暂无配图</small></span
    >
    <span class="map-card-copy">
      <strong>{{ restaurant.name }}</strong>
      <span v-if="categoryLabel || businessTags.length || restaurant.closed" class="map-facts">
        <span v-if="categoryLabel" class="map-category">{{ categoryLabel }}</span>
        <span v-for="tag in businessTags" :key="tag">{{ tag }}</span>
        <span v-if="restaurant.closed" class="closed-state">已停业</span>
      </span>
      <span class="map-comparison">
        <span class="map-rating" :class="{ unrated: restaurant.score === null }">
          <small class="map-metric-label">综合评分</small>
          <span v-if="restaurant.score !== null" class="map-score"
            ><span aria-hidden="true">★</span> {{ restaurant.score.toFixed(1) }}</span
          >
          <span v-else class="metric-empty">暂无评分</span>
          <small v-if="restaurant.reviewCount > 0" class="map-review-count"
            >{{ restaurant.reviewCount }} 条点评</small
          >
        </span>
        <span class="map-price">
          <small class="map-metric-label">人均消费</small>
          <span v-if="restaurant.price === null || restaurant.price <= 0" class="metric-empty"
            >暂无消费信息</span
          >
          <b v-else class="map-price-value">{{ priceLabel(restaurant) }}</b>
          <small
            v-if="
              restaurant.priceKind === 'estimate' &&
              restaurant.price !== null &&
              restaurant.price > 0
            "
            class="map-price-kind"
            >价格估值</small
          >
        </span>
      </span>
      <span class="map-location">
        <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
          <path
            d="M19 10c0 5-7 11-7 11S5 15 5 10a7 7 0 1 1 14 0Z"
            stroke="currentColor"
            stroke-width="1.4"
          />
          <circle cx="12" cy="10" r="2.2" stroke="currentColor" stroke-width="1.4" />
        </svg>
        <span
          ><small class="map-distance" title="距用户当前位置的直线距离，不是距您的位置"
            >{{ distanceLabel }}<span v-if="distance !== null"> · 直线</span></small
          >
          <small v-if="restaurant.address" class="map-address">{{
            restaurant.address
          }}</small></span
        >
      </span>
    </span>
  </button>
</template>
<style scoped src="./map-restaurant-card.css"></style>
