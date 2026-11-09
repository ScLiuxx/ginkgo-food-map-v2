<script setup lang="ts">
import { formatRating } from '@/shared/lib/formatRating'
import { computed, provide, ref } from 'vue'
import ReviewPanel from './ReviewPanel.vue'
import { reviewGatewayKey } from '../model/review'
import { initialPreviewReviews, previewSnapshot } from '../model/reviewHistoryPreview'

const reviews = ref(initialPreviewReviews())
const revision = ref(0)
const snapshot = computed(() => previewSnapshot(reviews.value))
/** 传输仅注入当前演示组件的子树，无发布/回复实现，不影响真实餐厅页。 */
provide(reviewGatewayKey, {
  list: async (_restaurantId, _signal, page = 1) => ({
    records: snapshot.value.groupedRecords.slice((page - 1) * 8, page * 8),
    page,
    pageSize: 8,
    totalGroups: snapshot.value.people,
    totalReviews: snapshot.value.count,
    totalPages: Math.ceil(snapshot.value.people / 8),
  }),
  publish: async () => {
    throw new Error('预览不支持发布。')
  },
  reply: async () => {
    throw new Error('预览不支持回复。')
  },
  remove: async (id) => {
    reviews.value = reviews.value.filter((item) => item.id !== id)
  },
})
function reset() {
  reviews.value = initialPreviewReviews()
  revision.value++
}
</script>
<template>
  <div class="review-history-preview">
    <div class="preview-notice">
      <strong>界面预览 · 示例数据</strong
      ><span>下方餐厅、账号和点评均为演示，不代表真实评价，不会写入数据库。</span>
    </div>
    <RouterLink class="back-link" to="/">← 返回发现餐厅</RouterLink>
    <header class="restaurant-heading">
      <div>
        <p class="eyebrow">同一家店，不同次用餐</p>
        <h1>校园铁板饭 <small>示例餐厅</small></h1>
        <p>快餐 · 人均约 ¥20 · 位置为示意</p>
      </div>
      <button type="button" @click="reset">重置示例</button>
    </header>
    <div class="preview-layout">
      <div class="preview-main">
        <section class="summary" aria-label="餐厅评分示例">
          <div>
            <span>综合评分</span
            ><strong data-testid="score"
              >{{ formatRating(snapshot.score, 2) }}<small> / 5</small></strong
            >
          </div>
          <div>
            <span>用餐记录</span
            ><strong data-testid="count">{{ snapshot.count }}<small> 条点评</small></strong>
          </div>
          <div>
            <span>评价人数</span
            ><strong data-testid="people">{{ snapshot.people }}<small> 人</small></strong>
          </div>
          <div>
            <span>推荐率</span
            ><strong>{{
              snapshot.recommendRate === null ? '—' : snapshot.recommendRate + '%'
            }}</strong>
          </div>
          <p>每人只取最新一条有效点评计算四维均分与推荐率，历史记录不重复计分。</p>
        </section>
        <ReviewPanel :key="revision" restaurant-id="preview-restaurant" preview />
      </div>
      <aside>
        <h2>这家店的评分怎么来？</h2>
        <p>
          小王来了两次，小李来了一次。三条点评全部保留，按同学归组，当前由两位同学各贡献一份评分。
        </p>
        <ol>
          <li>小王首次给 <b>8分</b>，作为历史体验保留。</li>
          <li>小王再次给 <b>6分</b>，更新自己的当前评分。</li>
          <li>小李给 <b>9分</b>，独立参与评分。</li>
        </ol>
        <div class="formula">初始综合分：<strong>(6 + 9) ÷ 2 = 7.50</strong></div>
        <p>
          展开小王卡片下方的“历史点评”可以查看首次体验与原回复。也可以试着删除小王的最新点评：旧的8分会重新参与，综合分变为8.50。点击“重置示例”可恢复。
        </p>
        <p class="preview-boundary">
          此页用于确认展示方式。正式评价读取、写入和评分统计仍待后端接入。
        </p>
      </aside>
    </div>
  </div>
</template>
<style scoped>
.review-history-preview {
  width: min(1100px, calc(100% - 32px));
  margin: 20px auto 40px;
  color: #2b2a26;
}
.preview-notice {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 18px;
  padding: 12px 16px;
  border: 1px solid #ebd194;
  background: #fff8e7;
  border-radius: 12px;
  font-size: 12px;
  line-height: 1.8;
  color: #876725;
  margin-bottom: 22px;
}
.back-link {
  font-size: 12px;
  color: #8f7955;
  text-decoration: none;
}
.restaurant-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin: 22px 0;
}
.eyebrow {
  color: #af7c24;
  font-size: 12px;
}
h1 {
  font-size: 28px;
  margin: 10px 0;
}
h1 small {
  display: inline-block;
  font-size: 11px;
  font-weight: 400;
  color: #8b806c;
  background: #f5f2ec;
  border-radius: 8px;
  padding: 5px 8px;
  vertical-align: middle;
}
.restaurant-heading p {
  font-size: 13px;
  color: #8a7b64;
}
.restaurant-heading button {
  flex-shrink: 0;
  min-height: 44px;
  border: 1px solid #ddc89c;
  border-radius: 10px;
  background: white;
  padding: 8px 14px;
  font: inherit;
  font-size: 12px;
  cursor: pointer;
}
.preview-layout {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 280px;
  gap: 24px;
  align-items: start;
}
.preview-main {
  min-width: 0;
}
.summary {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
  border: 1px solid #ecdfc1;
  border-radius: 16px;
  background: #fffaf0;
  padding: 22px;
  margin-bottom: 20px;
}
.summary span {
  font-size: 12px;
  color: #8c7c60;
}
.summary strong {
  display: block;
  font-size: 26px;
  margin-top: 12px;
}
.summary div:first-child strong {
  color: #d98b05;
}
.summary small {
  font-size: 11px;
  font-weight: 400;
  color: #8c7c60;
}
.summary p {
  grid-column: 1/-1;
  font-size: 12px;
  line-height: 1.8;
  color: #8c7c60;
  margin: 4px 0 0;
}
aside {
  border: 1px solid #e9e4d9;
  border-radius: 16px;
  padding: 22px;
  background: #faf9f6;
  font-size: 13px;
  line-height: 1.9;
  color: #7f725c;
}
h2 {
  font-size: 16px;
  color: #39342b;
  margin: 0 0 16px;
}
ol {
  padding-left: 20px;
}
li {
  margin: 12px 0;
}
.formula {
  padding: 12px;
  background: white;
  border-radius: 10px;
  font-size: 12px;
}
.formula strong {
  display: block;
  color: #a67320;
}
.preview-boundary {
  padding-top: 14px;
  border-top: 1px solid #e6dfd2;
  font-size: 12px;
}
@media (max-width: 760px) {
  .preview-layout {
    grid-template-columns: 1fr;
  }
  .restaurant-heading {
    align-items: start;
  }
  h1 {
    font-size: 23px;
  }
  h1 small {
    display: block;
    width: fit-content;
    margin-top: 8px;
  }
  .summary {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    padding: 18px;
  }
  .summary strong {
    font-size: 26px;
  }
  .preview-layout aside {
    order: 2;
  }
}
</style>
