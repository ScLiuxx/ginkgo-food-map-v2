<script setup lang="ts">
import { UserIdentity, ContentActions } from '@/modules/community'
import StarScore from '@/shared/ui/StarScore.vue'
import { formatRating } from '@/shared/lib/formatRating'
import { ratingDimensions } from '@/modules/restaurant/model/catalog.logic'
import { overall, recommendationLabels, type ReviewItem } from '../model/review'
import { hasExampleMarker } from '../model/detailPhotos'

/** 独立详情展示当前原点评；直接使用同一记录，不另外请求或复制可编辑数据。 */
defineProps<{
  item: ReviewItem
  authenticated: boolean
  viewerId?: string
  storeName?: string
  storeTo?: string
}>()
defineEmits<{ photo: [url: string]; reply: [] }>()
</script>
<template>
  <article class="detail-original">
    <header class="detail-original-person">
      <span class="detail-original-avatar" aria-hidden="true">{{
        Array.from(item.username.trim())[0]?.toUpperCase() || '同'
      }}</span>
      <div>
        <UserIdentity
          :user-id="item.authorId"
          :username="item.username"
          :level="item.authorLevel"
          :authenticated="authenticated"
          :viewer-id="viewerId"
        />
        <RouterLink v-if="storeName && storeTo" class="detail-original-store" :to="storeTo"
          >{{ storeName }} ›</RouterLink
        >
      </div>
    </header>
    <div class="detail-original-rating">
      <StarScore :score="item.overallScore ?? overall(item.scores)" /><span
        class="detail-original-attitude"
        >{{ recommendationLabels[item.recommendation] }}</span
      >
    </div>
    <p v-if="hasExampleMarker(item.body)" class="detail-original-example">示例点评 · 非真实体验</p>
    <p v-if="item.scoringStatus === 'history'" class="detail-original-example">历史点评 · 不计分</p>
    <p class="detail-original-body">{{ item.body }}</p>
    <div v-if="item.photos.length" class="detail-original-photos">
      <button
        v-for="photo in item.photos"
        :key="photo.id"
        type="button"
        @click="$emit('photo', photo.url)"
      >
        <img :src="photo.url" alt="评价照片" loading="lazy" />
      </button>
    </div>
    <details class="detail-original-dimensions">
      <summary>四维评分 <span aria-hidden="true">⌄</span></summary>
      <div>
        <span v-for="dimension in ratingDimensions" :key="dimension.key"
          >{{ dimension.label }}<b>{{ formatRating(item.scores[dimension.key]) }}</b></span
        >
      </div>
    </details>
    <footer class="detail-original-actions">
      <time :datetime="item.createdAt">{{
        new Date(item.createdAt).toLocaleDateString('zh-CN')
      }}</time
      ><ContentActions
        kind="review"
        :target-id="item.id"
        :viewer-id="viewerId"
        :authenticated="authenticated"
        :own="authenticated && !!viewerId && item.authorId === viewerId"
      /><button type="button" class="detail-original-reply" @click="$emit('reply')">回复</button
      ><slot name="actions" />
    </footer>
  </article>
</template>
<style scoped>
.detail-original {
  padding: 22px 20px 9px;
  color: #344252;
}
.detail-original-person {
  display: flex;
  align-items: center;
  gap: 10px;
}
.detail-original-avatar {
  display: grid;
  place-items: center;
  width: 34px;
  height: 34px;
  flex-shrink: 0;
  border-radius: 50%;
  background: #faf0de;
  color: #b08d55;
  font-size: 14px;
}
.detail-original-person :deep(.user-identity) {
  font-size: 12px;
}
.detail-original-store {
  display: block;
  color: #98a2af;
  font-size: 11px;
  text-decoration: none;
  margin-top: 2px;
  overflow-wrap: anywhere;
}
.detail-original-rating {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 14px;
}
.detail-original-attitude {
  font-size: 10px;
  color: #b7803f;
  background: #fff3e7;
  padding: 1px 5px;
  border-radius: 3px;
}
.detail-original-body {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  font-size: 14px;
  line-height: 1.8;
  margin: 12px 0 5px;
}
.detail-original-example {
  color: #9a9080;
  font-size: 11px;
}
.detail-original-photos {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  margin: 12px 0;
}
.detail-original-photos button {
  border: 0;
  padding: 0;
  background: none;
  cursor: pointer;
}
.detail-original-photos img {
  width: 80px;
  height: 80px;
  object-fit: cover;
  border-radius: 8px;
}
.detail-original-dimensions {
  font-size: 11px;
  color: #929dab;
}
.detail-original-dimensions summary {
  cursor: pointer;
  min-height: 35px;
  display: flex;
  align-items: center;
  gap: 4px;
  list-style: none;
}
.detail-original-dimensions summary::-webkit-details-marker {
  display: none;
}
.detail-original-dimensions > div {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  background: #f6f7f9;
  border-radius: 7px;
  padding: 9px 10px;
  margin-bottom: 8px;
  gap: 8px;
}
.detail-original-dimensions b {
  display: block;
  font-weight: 500;
  color: #64707d;
  font-size: 12px;
}
.detail-original-actions {
  display: flex;
  gap: 14px;
  align-items: center;
  min-height: 44px;
  color: #929dab;
  font-size: 11px;
}
.detail-original-actions time {
  margin-right: auto;
  white-space: nowrap;
  font-size: 10px;
}
.detail-original-actions :deep(.content-actions) {
  margin: 0;
}
.detail-original-reply {
  font: inherit;
  color: inherit;
  padding: 0;
  min-height: 44px;
  border: 0;
  background: none;
  cursor: pointer;
}
.detail-original-actions :deep(.review-actions-menu) {
  margin-left: 0;
}
.detail-original :is(button, a, summary):focus-visible {
  outline: 2px solid #a5adb7;
  outline-offset: 2px;
}
</style>
