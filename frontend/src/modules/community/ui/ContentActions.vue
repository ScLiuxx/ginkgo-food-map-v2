<script setup lang="ts">
import { nextTick, ref, useId, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import LikeButton from './LikeButton.vue'
import { authToken } from '@/shared/api/authSession'

/** 点赞读取真实状态；举报保持待开放，不模拟提交。 */
const props = defineProps<{
  own: boolean
  authenticated: boolean
  kind: 'review' | 'reply'
  viewerId?: string
  targetId?: string
}>()
const dialog = ref<globalThis.HTMLDialogElement>()
const reportOpen = ref(false)
const id = useId()
const route = useRoute()
const router = useRouter()
watch(authToken, () => {
  dialog.value?.close()
  reportOpen.value = false
})
async function openReport() {
  if (!props.authenticated) {
    void router.push({ path: '/login', query: { redirect: route.fullPath } })
    return
  }
  // 仅操作时挂载弹窗，避免每条点评中的隐藏表单干扰回复编辑和浏览器表单识别。
  const token = authToken.value
  reportOpen.value = true
  await nextTick()
  if (props.authenticated && token === authToken.value) dialog.value?.showModal()
}
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
    <button v-else class="like-entry" disabled title="点赞目标不可用">赞</button>
    <button v-if="!own" class="report-entry" title="举报功能即将开放" @click="openReport">
      举报
    </button>
    <Teleport to="body">
      <dialog
        v-if="reportOpen"
        ref="dialog"
        :aria-labelledby="'report-title-' + id"
        @close="reportOpen = false"
      >
        <h2 :id="'report-title-' + id">举报{{ kind === 'review' ? '点评' : '回复' }}</h2>
        <p class="report-pending" role="status">举报功能即将开放，当前无法提交或保存。</p>
        <label :for="'report-reason-' + id">举报原因 <span>必选</span></label>
        <select :id="'report-reason-' + id" required disabled>
          <option>原因分类即将开放</option>
        </select>
        <label :for="'report-description-' + id">补充说明 <span>选填</span></label>
        <textarea :id="'report-description-' + id" rows="3" disabled />
        <p class="report-note">
          举报后由管理员核查，内容不会因举报立即下架。处理结果将通过系统通知告知。
        </p>
        <footer>
          <button @click="dialog?.close()">关闭</button
          ><button disabled>提交举报 · 即将开放</button>
        </footer>
      </dialog>
    </Teleport>
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
.report-entry {
  margin-left: auto;
}
dialog {
  width: min(420px, calc(100% - 32px));
  padding: 24px;
  border: 1px solid #ece6de;
  border-radius: 12px;
  color: #38434e;
  font-size: 13px;
}
dialog::backdrop {
  background: #1f293755;
}
dialog h2 {
  margin: 0 0 16px;
  font-size: 19px;
}
dialog label {
  display: flex;
  gap: 8px;
  margin: 16px 0 10px;
}
dialog label span,
.report-note {
  color: #98a0a8;
  font-size: 12px;
}
dialog textarea,
dialog select {
  display: block;
  width: 100%;
  border: 1px solid #e6e8eb;
  padding: 10px;
  font: inherit;
  border-radius: 5px;
  background: #fafbfc;
  color: #929aa3;
}
.report-note,
.report-pending {
  line-height: 1.9;
}
.report-pending {
  color: #a38350;
}
dialog footer {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  margin-top: 20px;
}
dialog footer button {
  padding: 9px 14px;
  border: 1px solid #e7dfd2;
  border-radius: 6px;
  background: white;
  color: #9c773e;
  font: inherit;
  cursor: pointer;
}
button:focus-visible {
  outline: 2px solid #cf9f53;
  outline-offset: 3px;
}
</style>
