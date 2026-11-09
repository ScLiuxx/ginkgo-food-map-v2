<script setup lang="ts">
import { formatRating } from '@/shared/lib/formatRating'
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
  /** 个人主页已展示作者，仅当前点评省去重复署名，历史记录保持完整展示。 */
  profileEntry?: boolean
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
    :class="{
      'history-review': item.scoringStatus === 'history',
      'star-review': starScale,
      'profile-review': profileEntry,
    }"
  >
    <header>
      <div v-if="!profileEntry" class="entry-author">
        <span v-if="starScale" class="entry-avatar" aria-hidden="true">{{
          Array.from(item.username.trim())[0]?.toUpperCase() || '同'
        }}</span>
        <div class="author-identity">
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
      </div>
      <template v-if="starScale"
        ><div class="entry-rating">
          <StarScore :score="item.overallScore ?? overall(item.scores)" /><span
            class="attitude-badge"
            :class="{ negative: !item.recommended }"
            >{{ item.recommended ? '推荐' : '不推荐' }}</span
          >
        </div>
        <time v-if="!profileEntry" class="entry-date" :datetime="item.createdAt">{{
          new Date(item.createdAt).toLocaleDateString('zh-CN')
        }}</time></template
      >
      <span v-else class="verdict"
        >{{ item.recommended ? '推荐' : '不推荐' }} ·
        {{ formatRating(item.overallScore ?? overall(item.scores), 2) }}</span
      >
    </header>
    <p v-if="preview || hasExampleMarker(item.body)" class="example-review">
      示例点评 · 非真实体验
    </p>
    <p
      v-if="item.scoringStatus && (!starScale || item.scoringStatus === 'history')"
      class="scoring-state"
      :class="item.scoringStatus"
    >
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
        >{{ d.label }} {{ formatRating(item.scores[d.key]) }}</span
      >
    </p>

    <div class="photos">
      <button v-for="p in item.photos" :key="p.id" type="button" @click="$emit('photo', p.url)">
        <img :src="p.url" alt="评价照片" loading="lazy" />
      </button>
    </div>
    <!-- 同一行放置互动入口；展开的回复列表及编辑框独占后续整行，避免压缩正文。 -->
    <div class="entry-interactions">
      <time v-if="profileEntry" class="entry-footer-date" :datetime="item.createdAt">{{
        new Date(item.createdAt).toLocaleDateString('zh-CN')
      }}</time>
      <ContentActions
        v-if="!preview"
        kind="review"
        :target-id="item.id"
        :viewer-id="viewerId"
        :authenticated="authenticated"
        :own="authenticated && !!viewerId && item.authorId === viewerId"
      />
      <ReplyThread
        v-if="!preview && repliesEnabled !== false && gateway.listReplies"
        :key="item.id"
        :review-id="item.id"
        :username="item.username"
        :level="item.authorLevel"
      >
        <template v-if="$slots.history" #actions><slot name="history" /></template>
      </ReplyThread>
      <div
        v-else-if="(repliesEnabled !== false && !preview) || item.replies.length"
        class="replies"
      >
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
      <button
        v-if="preview ? item.canDelete : authenticated && !!viewerId && item.authorId === viewerId"
        type="button"
        class="delete-review"
        :disabled="busy"
        @click="$emit('delete', item)"
      >
        {{ preview ? '演示删除此点评' : '删除评价' }}
      </button>
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
/* 星级详情卡按确认稿排列：作者与日期一行，评分、正文沿头像后的文字列对齐。 */
.star-review.review-card {
  --entry-indent: 47px;
  padding: 25px 0 22px;
  margin: 0;
  border-color: #edf0f3;
}
.star-review header {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 14px 12px;
}
.entry-author {
  display: flex;
  align-items: center;
  gap: 11px;
  min-width: 0;
}
.author-identity {
  min-width: 0;
}
.entry-avatar {
  flex-shrink: 0;
  width: 36px;
  height: 36px;
  display: grid;
  place-items: center;
  border-radius: 50%;
  background: #fff0e3;
  color: #e89952;
  font-size: 15px;
  font-weight: 600;
}
.star-review .entry-author {
  grid-column: 1;
  grid-row: 1;
}
.star-review .author-identity :deep(.user-identity) {
  font-size: 15px;
  font-weight: 700;
  color: #2b3441;
}
.star-review .author-identity :deep(.identity-level) {
  display: inline-block;
  font-size: 10px;
  font-weight: 500;
  color: #d99a62;
  background: #fff7ef;
  padding: 2px 5px;
  margin-left: 7px;
  border-radius: 3px;
}
.entry-rating {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}
.star-review .entry-rating {
  grid-column: 1 / -1;
  grid-row: 2;
  margin-left: var(--entry-indent);
}
.star-review .entry-rating :deep(.star-score) {
  font-size: 14px;
  color: #e77c22;
}
.star-review .entry-rating :deep(.star-score strong) {
  font-weight: 700;
}
.star-review .entry-rating :deep(.star-symbols) {
  font-size: 16px;
}
.star-review .attitude-badge {
  background: #fff4e9;
  color: #d58a46;
  font-size: 10px;
  padding: 3px 6px;
  border-radius: 4px;
}
.star-review .attitude-badge.negative {
  background: #f2f3f5;
  color: #8b939d;
}
.star-review .entry-date {
  grid-column: 2;
  grid-row: 1;
  margin: 0;
  color: #929ca9;
  font-size: 12px;
  white-space: nowrap;
}
.star-review .body {
  margin: 18px 0 15px var(--entry-indent);
  color: #303b49;
  font-size: 16px;
  line-height: 1.85;
}
.star-review .chips {
  margin: 0 0 0 var(--entry-indent);
  font-size: 12px;
  color: #8a95a2;
  gap: 8px 17px;
}
.star-review .scoring-state.history {
  padding: 0;
  background: none;
  color: #a5adb5;
  margin: 8px 12px 0 var(--entry-indent);
}
.star-review .example-review {
  font-size: 11px;
  margin: 8px 0 0 var(--entry-indent);
}
.star-review .example-body {
  margin: 8px 0;
}
.star-review .photos {
  margin-left: var(--entry-indent);
}
.star-review .photos img {
  width: 104px;
  height: 104px;
  border-radius: 5px;
}
/* 仅星级卡将回复容器展开为网格子项，回复逻辑、焦点与草稿生命周期继续由原组件管理。 */
.star-review .entry-interactions {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  gap: 0 23px;
  align-items: center;
  margin: 16px 0 0 var(--entry-indent);
  color: #6c7a8b;
  font-size: 12px;
}
.star-review .entry-interactions > :deep(.content-actions) {
  grid-column: 1;
  grid-row: 1;
  align-self: start;
  margin: 6px 0 0;
  gap: 16px;
  color: #6c7a8b;
}
.star-review .entry-interactions > :deep(.reply-thread) {
  display: contents;
}
.star-review .entry-interactions > :deep(.reply-thread > .thread-actions) {
  grid-column: 2;
  grid-row: 1;
  flex-wrap: wrap;
  gap: 8px 23px;
  font-size: 12px;
}
.star-review .entry-interactions > :deep(.reply-thread > .thread-actions .text-action) {
  color: #6c7a8b;
}
.star-review .entry-interactions > :deep(.reply-thread > .thread-content),
.star-review .entry-interactions > :deep(.reply-thread > .reply-notice),
.star-review .entry-interactions > :deep(.reply-thread > .reply-composer),
.star-review .entry-interactions > .replies {
  grid-column: 1 / -1;
}
.star-review .delete-review {
  grid-column: 3;
  grid-row: 1;
  border: 0;
  padding: 8px 0;
  color: #98a1ad;
  background: none;
  font-size: 12px;
  min-height: 44px;
  white-space: nowrap;
}
/* 主页只有一个作者，正文沿店名左对齐；日期移至底部，回复展开仍独占整行。 */
.profile-review.star-review {
  --entry-indent: 0px;
}
.profile-review.star-review header {
  display: block;
}
.profile-review.star-review .body {
  font-size: 15px;
  margin-top: 15px;
  margin-bottom: 9px;
}
.profile-review.star-review .chips {
  font-size: 11px;
}
.profile-review.star-review .entry-interactions {
  grid-template-columns: minmax(0, 1fr) auto auto auto;
  gap: 0 18px;
  margin-top: 18px;
}
.entry-footer-date {
  grid-column: 1;
  grid-row: 1;
  color: #969eaa;
  font-size: 12px;
}
.profile-review.star-review .entry-interactions > :deep(.content-actions) {
  grid-column: 2;
}
.profile-review.star-review .entry-interactions > :deep(.reply-thread > .thread-actions) {
  grid-column: 3;
  gap: 8px 14px;
}
.profile-review.star-review .delete-review {
  grid-column: 4;
  grid-row: 1;
  font-size: 11px;
}
@media (max-width: 600px) {
  .star-review .entry-interactions {
    gap: 0 14px;
  }
  .star-review .entry-interactions > :deep(.reply-thread > .thread-actions) {
    gap: 0 16px;
  }
  .star-review .delete-review {
    grid-row: 2;
    grid-column: 1 / -1;
    justify-self: end;
  }
}
@media (max-width: 480px) {
  .profile-review.star-review .entry-interactions {
    grid-template-columns: minmax(0, 1fr) auto auto;
    gap: 0 14px;
  }
  .profile-review .entry-footer-date {
    grid-row: 1;
    grid-column: 1 / -1;
    margin-bottom: 6px;
  }
  .profile-review.star-review .entry-interactions > :deep(.content-actions) {
    grid-column: 1;
    grid-row: 2;
  }
  .profile-review.star-review .entry-interactions > :deep(.reply-thread > .thread-actions) {
    grid-column: 2;
    grid-row: 2;
  }
  .profile-review.star-review .delete-review {
    grid-column: 3;
    grid-row: 2;
  }
  .star-review.review-card {
    --entry-indent: 0px;
  }
  .star-review .entry-date {
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
