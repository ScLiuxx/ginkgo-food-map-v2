<script setup lang="ts">
import { inject } from 'vue'
import ReplyThread from './ReplyThread.vue'
import StarScore from '@/shared/ui/StarScore.vue'
import { ratingDimensions } from '@/modules/restaurant/model/catalog.logic'
import { overall, reviewGatewayKey, unavailableReviewGateway } from '../model/review'
import type { ReviewItem } from '../model/review'
import { hasExampleMarker } from '../model/detailPhotos'
import { UserIdentity, ContentActions } from '@/modules/community'
const gateway = inject(reviewGatewayKey, unavailableReviewGateway)
/** 当前点评与历史点评使用同一展示组件，回复、照片和删除始终绑定各自记录ID。 */
defineProps<{
  item: ReviewItem
  preview?: boolean
  starScale?: boolean
  authenticated: boolean
  /** 已认证账号ID，仅用于入口展示；不能代替服务端授权。 */
  viewerId?: string
  busy: boolean
  replyTexts: Record<string, string>
  showHistory: boolean
  repliesEnabled?: boolean
}>()
defineEmits<{
  delete: [item: ReviewItem]
  photo: [url: string]
  reply: [item: ReviewItem]
  text: [id: string, text: string]
}>()
</script>
<template>
  <article
    class="review-card"
    :class="{ 'history-review': item.scoringStatus === 'history', 'star-review': starScale }"
  >
    <header>
      <div>
        <UserIdentity
          :user-id="item.authorId"
          :username="item.username"
          :level="item.authorLevel"
          :authenticated="authenticated"
          :preview="preview"
        />
        <time v-if="!starScale" :datetime="item.createdAt">{{
          new Date(item.createdAt).toLocaleDateString('zh-CN')
        }}</time>
      </div>
      <template v-if="starScale"
        ><StarScore :score="item.overallScore ?? overall(item.scores)" /><span
          class="attitude-badge"
          :class="{ negative: !item.recommended }"
          >{{ item.recommended ? '推荐' : '不推荐' }}</span
        ><time class="entry-date" :datetime="item.createdAt">{{
          new Date(item.createdAt).toLocaleDateString('zh-CN')
        }}</time></template
      >
      <span v-else class="verdict"
        >{{ item.recommended ? '推荐' : '不推荐' }} ·
        {{ (item.overallScore ?? overall(item.scores)).toFixed(2) }}</span
      >
    </header>
    <p v-if="preview || hasExampleMarker(item.body)" class="example-review">
      示例点评 · 非真实体验
    </p>
    <p v-if="item.scoringStatus" class="scoring-state" :class="item.scoringStatus">
      {{ item.scoringStatus === 'current' ? '最新点评' : '历史点评 · 不计分' }}
    </p>
    <!-- 仅明确标识的联调记录折叠原文；真实同学正文始终直接展示，不改写存储内容。 -->
    <details v-if="hasExampleMarker(item.body) && !preview" class="example-body">
      <summary>查看示例记录</summary>
      <p class="body">{{ item.body }}</p>
    </details>
    <p v-else class="body">{{ item.body }}</p>
    <p class="chips">
      <span v-for="d in ratingDimensions" :key="d.key"
        >{{ d.label }}
        {{ (starScale ? item.scores[d.key] / 2 : item.scores[d.key]).toFixed(1) }}</span
      >
    </p>

    <div class="photos">
      <button v-for="p in item.photos" :key="p.id" type="button" @click="$emit('photo', p.url)">
        <img :src="p.url" alt="评价照片" loading="lazy" />
      </button>
    </div>
    <ContentActions
      v-if="!preview"
      kind="review"
      :target-id="item.id"
      :viewer-id="viewerId"
      :authenticated="authenticated"
      :own="authenticated && !!viewerId && item.authorId === viewerId"
    />
    <button
      v-if="preview ? item.canDelete : authenticated && !!viewerId && item.authorId === viewerId"
      type="button"
      :disabled="busy"
      @click="$emit('delete', item)"
    >
      {{ preview ? '演示删除此点评' : '删除评价' }}
    </button>
    <ReplyThread
      v-if="!preview && repliesEnabled !== false && gateway.listReplies"
      :key="item.id"
      :review-id="item.id"
      :username="item.username"
      :level="item.authorLevel"
    >
      <template v-if="$slots.history" #actions><slot name="history" /></template>
    </ReplyThread>
    <div v-else-if="(repliesEnabled !== false && !preview) || item.replies.length" class="replies">
      <p v-for="answer in item.replies" :key="answer.id">
        <strong>{{ answer.username }}</strong
        >：{{ answer.body
        }}<time :datetime="answer.createdAt">{{
          new Date(answer.createdAt).toLocaleDateString('zh-CN')
        }}</time>
      </p>
      <form v-if="!preview && repliesEnabled !== false" @submit.prevent="$emit('reply', item)">
        <input
          :value="replyTexts[item.id] ?? ''"
          :aria-label="'回复' + item.username"
          placeholder="说点什么（1–200字）"
          @input="$emit('text', item.id, ($event.target as HTMLInputElement).value)"
          @keydown.enter="$event.isComposing && $event.preventDefault()"
        /><button :disabled="busy">回复</button>
      </form>
    </div>

    <slot v-if="preview || repliesEnabled === false || !gateway.listReplies" name="history">
      <p v-if="item.historyCount && !item.previousReviews?.length" class="history-note">
        另有{{ item.historyCount }}条历史点评，暂未开放展开。
      </p>
      <details v-if="showHistory && item.previousReviews?.length" class="review-history">
        <summary>
          查看 {{ item.username }} 的历史点评（{{ item.previousReviews.length }}条）<span
            >同一家店 · 评分更新前的记录</span
          >
        </summary>
        <p class="history-note">这些点评保留原有内容与回复，不参与当前评分。</p>
        <ReviewEntry
          v-for="previous in item.previousReviews"
          :key="previous.id"
          :item="previous"
          :preview="preview"
          :star-scale="starScale"
          :authenticated="authenticated"
          :viewer-id="viewerId"
          :busy="busy"
          :reply-texts="replyTexts"
          :show-history="false"
          @delete="$emit('delete', $event)"
          @photo="$emit('photo', $event)"
          @reply="$emit('reply', $event)"
          @text="(id, text) => $emit('text', id, text)"
        />
      </details>
    </slot>
  </article>
</template>
<style scoped>
/* 正文、照片优先；星级与态度并排，日期退到右侧，低优先级信息保持紧凑。 */
.star-review.review-card {
  padding: 24px 0;
  margin: 0;
  border-color: #edf0f2;
}
.star-review header {
  flex-wrap: wrap;
  gap: 9px 12px;
}
.star-review .avatar {
  width: 34px;
  height: 34px;
  background: #f3f5f6;
  color: #87939e;
}
.star-review .attitude-badge {
  background: #fff5eb;
  color: #df8b42;
  font-size: 11px;
  padding: 2px 6px;
  border-radius: 4px;
}
.star-review .attitude-badge.negative {
  background: #f2f3f5;
  color: #8b939d;
}
.star-review .entry-date {
  margin: 0 0 0 auto;
  color: #a0a8b1;
  font-size: 12px;
}
.star-review .body {
  margin: 16px 0 12px;
  color: #414c58;
}
.star-review .chips {
  font-size: 11px;
  color: #a0a8b0;
  gap: 10px;
}
.star-review .scoring-state.current,
.star-review .scoring-state.history {
  padding: 0;
  background: none;
  color: #a5adb5;
  margin: 8px 12px 0 0;
}
.star-review .example-review {
  display: inline-block;
  font-size: 11px;
  margin: 8px 12px 0 0;
}
.star-review .example-body {
  margin: 8px 0;
}
.star-review .photos img {
  width: 104px;
  height: 104px;
  border-radius: 5px;
}
@media (max-width: 480px) {
  .star-review header {
    gap: 6px 8px;
  }
  .star-review .entry-date {
    flex: 1 0 100%;
    text-align: right;
    font-size: 11px;
  }
  .star-review .photos img {
    width: 80px;
    height: 80px;
  }
}
.example-body {
  margin: 14px 0;
  color: #687583;
  font-size: 12px;
}
.example-body summary {
  cursor: pointer;
  padding: 6px 0;
}
.review-card {
  padding: 22px 0;
  border-top: 1px solid #e8dfd0;
  margin-top: 20px;
  min-width: 0;
}
.example-review {
  font-size: 12px;
  line-height: 1.8;
  color: #976319;
}
header {
  display: flex;
  align-items: center;
  gap: 12px;
  font-size: 13px;
}
header > div {
  min-width: 0;
  overflow-wrap: anywhere;
}
.avatar {
  background: #e9b949;
  border-radius: 50%;
  width: 36px;
  height: 36px;
  display: grid;
  place-items: center;
  flex-shrink: 0;
}
.verdict {
  margin-left: auto;
  color: #8d6535;
  white-space: nowrap;
}
time {
  display: block;
  font-size: 11px;
  color: #92836b;
  margin-top: 5px;
}
.scoring-state {
  display: inline-block;
  padding: 6px 10px;
  border-radius: 8px;
  font-size: 11px;
  line-height: 1.6;
}
.scoring-state.current {
  color: #8c610e;
  background: #fff1cf;
}
.scoring-state.history {
  color: #737985;
  background: #f0f2f5;
}
.body {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  line-height: 1.8;
  font-size: 14px;
}
.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  font-size: 12px;
  color: #8c754f;
}
button {
  font: inherit;
  background: transparent;
  border: 1px solid #d9cfbb;
  border-radius: 7px;
  padding: 8px 12px;
  color: #74603d;
  cursor: pointer;
}
.photos {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  margin: 14px 0;
}
.photos:empty {
  display: none;
}
.photos button {
  padding: 0;
  border: 0;
}
.photos img {
  width: 82px;
  height: 82px;
  object-fit: cover;
  border-radius: 8px;
}
.replies {
  background: #f6f0e4;
  padding: 14px;
  border-radius: 10px;
  margin-top: 14px;
  font-size: 13px;
  overflow-wrap: anywhere;
}
.replies form {
  display: flex;
  gap: 8px;
}
.replies form button {
  flex-shrink: 0;
  white-space: nowrap;
}
.replies input {
  min-width: 0;
  width: 100%;
  border: 1px solid #dfd2ba;
  border-radius: 7px;
  padding: 10px;
  font: inherit;
}
.review-history {
  margin-top: 20px;
  border: 1px solid #e6e0d4;
  border-radius: 12px;
  background: #faf9f6;
  padding: 0 14px;
}
.review-history > summary {
  padding: 14px 0;
  cursor: pointer;
  font-size: 13px;
  color: #78654a;
  line-height: 1.8;
}
.review-history > summary span {
  display: block;
  font-size: 11px;
  color: #9a9080;
  margin-left: 14px;
}
.history-note {
  font-size: 11px;
  color: #9a9080;
  line-height: 1.8;
}
.review-history .review-card {
  margin-top: 0;
  padding-top: 18px;
}
.review-history[open] {
  padding-bottom: 4px;
}
@media (max-width: 400px) {
  header {
    gap: 8px;
  }
  .verdict {
    font-size: 11px;
  }
  .replies {
    padding: 8px;
  }
  .review-history {
    padding: 0 10px;
  }
}
</style>
