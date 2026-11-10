<script setup lang="ts">
import LikeButton from './LikeButton.vue'
import { ReportAction } from '@/modules/moderation'

/** 点赞读取真实状态；举报提交、失败重试和弹窗生命周期由 moderation 模块独立管理。 */
defineProps<{
  own: boolean
  authenticated: boolean
  kind: 'review' | 'reply'
  viewerId?: string
  targetId?: string
}>()
</script>
<template>
  <div class="content-actions">
    <LikeButton
      v-if="targetId"
      :id="targetId"
      :type="kind === 'review' ? 'REVIEW' : 'REPLY'"
      :own="own"
      :authenticated="authenticated"
      :viewer-id="viewerId"
    />
    <button v-else class="like-entry" disabled title="点赞目标不可用" aria-label="点赞目标不可用">
      <svg viewBox="0 0 24 24" aria-hidden="true">
        <path
          d="M20.8 4.6a5.5 5.5 0 0 0-7.8 0L12 5.7l-1.1-1.1a5.5 5.5 0 0 0-7.8 7.8L12 21l8.8-8.6a5.5 5.5 0 0 0 0-7.8Z"
        />
      </svg>
    </button>
    <ReportAction
      v-if="!own && targetId"
      :target-type="kind === 'review' ? 'REVIEW' : 'REPLY'"
      :target-id="targetId"
      :authenticated="authenticated"
    />
  </div>
</template>
<style scoped>
.content-actions {
  display: flex;
  align-items: center;
  gap: 18px;
  min-height: 32px;
  margin-top: 10px;
  color: #929ba4;
  font-size: 12px;
}
.content-actions > button {
  border: 0;
  background: none;
  color: inherit;
  padding: 6px 0;
  font: inherit;
  cursor: pointer;
}
.content-actions > button:disabled {
  cursor: not-allowed;
}
.like-entry {
  display: inline-flex;
  align-items: center;
  gap: 5px;
}
.like-entry svg {
  width: 15px;
  height: 15px;
  fill: none;
  stroke: currentColor;
  stroke-width: 1.5;
  stroke-linecap: round;
  stroke-linejoin: round;
}
</style>
