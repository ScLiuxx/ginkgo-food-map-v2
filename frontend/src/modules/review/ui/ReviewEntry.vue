<script setup lang="ts">
import { formatRating } from '@/shared/lib/formatRating'
import { inject } from 'vue'
import ReplyThread from './ReplyThread.vue'
import ReviewActionsMenu from './ReviewActionsMenu.vue'
import ReviewDiscussion from './ReviewDiscussion.vue'
import ReviewDetailOriginal from './ReviewDetailOriginal.vue'
import StarScore from '@/shared/ui/StarScore.vue'
import { ratingDimensions } from '@/modules/restaurant/model/catalog.logic'
import {
  overall,
  recommendationLabels,
  reviewGatewayKey,
  unavailableReviewGateway,
} from '../model/review'
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
  /** 本人点评列表使用独立卡片，折叠细分评分与次要操作；店铺详情和历史保持原布局。 */
  listCard?: boolean
  /** 本人详情中的店铺摘要沿用当前分组，不另行拼接或猜测窗口父级。 */
  storeName?: string
  storeTo?: string
  authenticated: boolean
  /** 已认证账号ID，仅用于入口展示；不能代替服务端授权。 */
  viewerId?: string
  busy: boolean
  replyTexts: Record<string, string>
  showHistory: boolean
  repliesEnabled?: boolean
}>()
defineEmits<{
  delete: [item: ReviewItem, restoreFocus?: () => void]
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
      'list-card-review': listCard,
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
            :viewer-id="viewerId"
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
            :class="{
              negative: item.recommendation === 'NOT_RECOMMENDED',
              neutral: item.recommendation === 'NEUTRAL',
            }"
            >{{ recommendationLabels[item.recommendation] }}</span
          >
        </div>
        <time v-if="!profileEntry || listCard" class="entry-date" :datetime="item.createdAt">{{
          new Date(item.createdAt).toLocaleDateString('zh-CN')
        }}</time></template
      >
      <span v-else class="verdict"
        >{{ recommendationLabels[item.recommendation] }} ·
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
    <p v-if="!listCard" class="chips">
      <span v-for="d in ratingDimensions" :key="d.key"
        >{{ d.label }} {{ formatRating(item.scores[d.key]) }}</span
      >
    </p>

    <div class="photos">
      <button v-for="p in item.photos" :key="p.id" type="button" @click="$emit('photo', p.url)">
        <img :src="p.url" alt="评价照片" loading="lazy" />
      </button>
    </div>
    <!-- 细分评分仍直接来自原记录，只改变展开方式，不隐藏正文或照片。 -->
    <details v-if="listCard" class="dimension-details">
      <summary>
        四维评分<svg viewBox="0 0 24 24" aria-hidden="true"><path d="m7 10 5 5 5-5" /></svg>
      </summary>
      <div class="dimension-values">
        <span v-for="d in ratingDimensions" :key="d.key"
          >{{ d.label }}<b>{{ formatRating(item.scores[d.key]) }}</b></span
        >
      </div>
    </details>
    <!-- 同一行放置互动入口；展开的回复列表及编辑框独占后续整行，避免压缩正文。 -->
    <div class="entry-interactions">
      <time v-if="profileEntry && !listCard" class="entry-footer-date" :datetime="item.createdAt">{{
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
      <ReviewDiscussion
        v-if="listCard && !preview && repliesEnabled !== false && gateway.listReplies"
        :key="'discussion-' + item.id"
        :review-id="item.id"
        :username="item.username"
      >
        <template #original="{ focusInput }">
          <ReviewDetailOriginal
            :item="item"
            :authenticated="authenticated"
            :viewer-id="viewerId"
            :store-name="storeName"
            :store-to="storeTo"
            @reply="focusInput"
            @photo="$emit('photo', $event)"
          >
            <template v-if="authenticated && !!viewerId && item.authorId === viewerId" #actions>
              <ReviewActionsMenu>
                <template #default="{ restoreFocus }"
                  ><button
                    type="button"
                    class="delete-review"
                    :disabled="busy"
                    @click="$emit('delete', item, restoreFocus)"
                  >
                    删除点评
                  </button></template
                >
              </ReviewActionsMenu>
            </template>
          </ReviewDetailOriginal>
        </template>
      </ReviewDiscussion>
      <ReplyThread
        v-else-if="!preview && repliesEnabled !== false && gateway.listReplies"
        :key="item.id"
        :review-id="item.id"
        :username="item.username"
        :level="item.authorLevel"
        :combined="listCard"
      >
        <template v-if="$slots.history && !listCard" #actions><slot name="history" /></template>
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
        v-if="
          !listCard &&
          (preview ? item.canDelete : authenticated && !!viewerId && item.authorId === viewerId)
        "
        type="button"
        class="delete-review"
        :disabled="busy"
        @click="$emit('delete', item)"
      >
        {{ preview ? '演示删除此点评' : '删除评价' }}
      </button>
      <ReviewActionsMenu
        v-if="
          listCard &&
          ($slots.history || (authenticated && !!viewerId && item.authorId === viewerId))
        "
      >
        <template #default="{ restoreFocus }">
          <slot name="history" :restore-focus="restoreFocus" />
          <button
            v-if="authenticated && !!viewerId && item.authorId === viewerId"
            type="button"
            class="delete-review"
            :disabled="busy"
            @click="$emit('delete', item, restoreFocus)"
          >
            删除点评
          </button>
        </template>
      </ReviewActionsMenu>
    </div>

    <slot
      v-if="!listCard && (preview || repliesEnabled === false || !gateway.listReplies)"
      name="history"
    >
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
<style scoped src="./review-list-entry.css"></style>
