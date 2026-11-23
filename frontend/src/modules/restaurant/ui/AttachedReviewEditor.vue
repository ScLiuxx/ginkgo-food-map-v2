<script setup lang="ts">
import HalfStarRating from '@/modules/review/ui/HalfStarRating.vue'
import { recommendationLabels, reviewRecommendations } from '@/modules/review/model/review'
import type { AttachedReviewDraft } from '../model/attachedReview'
import type { SubmissionPhotos as Photos } from '../model/useSubmissionPhotos'
import SubmissionPhotos from './SubmissionPhotos.vue'

/** 点评步骤只编辑内容；是否附带由上一步入口决定，草稿及图片始终由父组件保管。 */
defineProps<{
  draft: AttachedReviewDraft
  photos: Photos
  locked?: boolean
  disabled?: boolean
  error?: string
}>()
const emit = defineEmits<{ change: [patch: Partial<AttachedReviewDraft>] }>()
const dimensions = [
  { key: 'taste', name: '口味' },
  { key: 'value', name: '性价比' },
  { key: 'service', name: '服务' },
  { key: 'ambience', name: '环境' },
] as const
</script>
<template>
  <section class="attached-review" aria-labelledby="attached-review-title">
    <div class="review-heading">
      <div>
        <h2 id="attached-review-title">分享用餐体验</h2>
        <p>店铺审核通过后，点评会自动发布。</p>
      </div>
    </div>
    <div class="review-fields">
      <p class="review-note">审核前，点评内容和图片仅你自己可查看。</p>
      <fieldset :disabled="disabled">
        <legend>给这次体验打个分</legend>
        <div v-for="item in dimensions" :key="item.key" class="rating-row">
          <label :for="`attached-${item.key}`">{{ item.name }}</label
          ><HalfStarRating
            :id="`attached-${item.key}`"
            :model-value="draft.scores[item.key]"
            :label="item.name"
            @update:model-value="
              emit('change', { scores: { ...draft.scores, [item.key]: $event } })
            "
          /><span>{{ draft.scores[item.key] / 2 }}</span>
        </div>
      </fieldset>
      <fieldset :disabled="disabled">
        <legend>你会推荐这家店吗？ <em>*</em></legend>
        <div class="choices">
          <button
            v-for="value in reviewRecommendations"
            :key="value"
            type="button"
            :aria-pressed="draft.recommendation === value"
            @click="emit('change', { recommendation: value })"
          >
            {{ recommendationLabels[value] }}
          </button>
        </div>
      </fieldset>
      <label class="body-label"
        >说说你的用餐体验 <em>*</em
        ><textarea
          :value="draft.body"
          rows="4"
          :disabled="disabled"
          placeholder="哪道菜值得点？口味、分量或服务怎么样？"
          @input="emit('change', { body: ($event.target as HTMLTextAreaElement).value })"
        />
      </label>
      <p class="body-count">{{ Array.from(draft.body.trim()).length }}/500</p>
      <h3>点评照片 <span>选填</span></h3>
      <SubmissionPhotos
        :draft="photos"
        label="点评照片"
        hint="菜品和这次用餐的照片，会随点评一起发布。与店铺照片分别添加。"
        :disabled="disabled"
      />
      <p v-if="locked" class="review-off">这条点评已随申请保存，可修改内容，不能移除整条点评。</p>
      <p v-if="error" role="alert" class="error">{{ error }}</p>
    </div>
  </section>
</template>
<style scoped>
.attached-review {
  border: 1px solid #e9ece8;
  border-radius: 12px;
  padding: 24px;
  margin: 24px 0;
}
.review-heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}
h2 {
  font-size: 18px;
  margin: 0;
}
h2 span,
h3 span {
  font-size: 11px;
  font-weight: 400;
  color: #929a9e;
  margin-left: 5px;
}
.review-heading p,
.review-off,
.body-count {
  font-size: 12px;
  line-height: 1.8;
  color: #8a9399;
}
.review-heading p {
  margin: 9px 0 0;
}
.review-note {
  background: #fdf8ef;
  border-radius: 5px;
  color: #a17e4d;
  font-size: 12px;
  line-height: 1.8;
  padding: 10px 12px;
  margin: 23px 0;
}
fieldset {
  border: 0;
  margin: 0 0 24px;
  padding: 0;
  min-width: 0;
}
legend,
.body-label {
  font-size: 13px;
  color: #58636b;
  font-weight: 550;
  margin-bottom: 12px;
}
.rating-row {
  display: flex;
  align-items: center;
  gap: 13px;
  margin: 8px 0;
  font-size: 12px;
}
.rating-row label {
  width: 44px;
}
.rating-row > span {
  font-size: 12px;
  color: #a88650;
}
.choices {
  display: flex;
  gap: 10px;
}
.choices button {
  flex: 1;
  max-width: 115px;
  border: 1px solid #e1e5e4;
  border-radius: 6px;
  padding: 10px;
  font: inherit;
  color: #67737b;
  background: white;
  font-size: 12px;
  cursor: pointer;
}
.choices button[aria-pressed='true'] {
  border-color: #ed9a4b;
  color: #bd6413;
  background: #fff8ee;
}
.body-label {
  display: block;
}
textarea {
  box-sizing: border-box;
  width: 100%;
  margin-top: 10px;
  border: 1px solid #dfe4e5;
  border-radius: 6px;
  font: inherit;
  font-weight: 400;
  line-height: 1.9;
  resize: vertical;
  padding: 12px;
  color: #36434c;
  min-height: 100px;
}
.body-count {
  font-size: 11px;
  margin: 8px 0 24px;
}
h3 {
  border-top: 1px solid #eceeea;
  padding-top: 20px;
  font-size: 13px;
  margin: 0;
}
em {
  color: #c5742e;
  font-style: normal;
}
.error {
  font-size: 12px;
  color: #b64e37;
}
button:disabled {
  opacity: 0.65;
  cursor: default;
}
button:focus-visible,
textarea:focus-visible {
  outline: 2px solid #ed740a;
  outline-offset: 3px;
}
@media (max-width: 540px) {
  .attached-review {
    padding: 18px 14px;
  }
  .rating-row {
    gap: 8px;
  }
  .rating-row label {
    width: 40px;
  }
  h2 {
    font-size: 16px;
  }
  .review-note {
    font-size: 11px;
  }
}
</style>
