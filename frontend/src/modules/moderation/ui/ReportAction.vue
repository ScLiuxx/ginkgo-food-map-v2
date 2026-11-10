<script setup lang="ts">
import { computed, nextTick, onUnmounted, ref, useId, useTemplateRef, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { authToken } from '@/shared/api/authSession'
import { toErrorMessage } from '@/shared/lib/toErrorMessage'
import { submitContentReport } from '../api/contentReportsApi'
import {
  reportReasons,
  reportTargetLabel,
  type ReportReason,
} from '../model/reportReasons'
import type { ReportTargetType } from '../api/contentReportsApi'

/** 举报入口只出现在他人内容旁；本人内容不渲染，服务端仍独立校验归属与目标可见性。 */
const props = defineProps<{
  targetType: ReportTargetType
  targetId: string
  authenticated: boolean
}>()
const router = useRouter(),
  route = useRoute()
const dialog = useTemplateRef('dialog')
const open = ref(false),
  reason = ref<ReportReason | ''>(''),
  detail = ref(''),
  error = ref(''),
  notice = ref(''),
  sending = ref(false)
const uniqueId = useId()
const length = computed(() => Array.from(detail.value).length)
const title = computed(() => `举报${reportTargetLabel(props.targetType)}`)
let context = 0,
  controller: globalThis.AbortController | undefined

/** 切换账号或目标时立即取消在途请求并清空草稿，旧回执不能写入新身份。 */
function reset() {
  context++
  controller?.abort()
  dialog.value?.close()
  open.value = false
  reason.value = ''
  detail.value = ''
  error.value = notice.value = ''
  sending.value = false
}

watch(() => [authToken.value, props.targetType, props.targetId], reset, { flush: 'sync' })
onUnmounted(reset)

async function show() {
  if (!props.authenticated) {
    await router.push({ path: '/login', query: { redirect: route.fullPath } })
    return
  }
  const owner = context
  reason.value = ''
  detail.value = ''
  error.value = notice.value = ''
  open.value = true
  await nextTick()
  if (owner === context) dialog.value?.showModal()
}

function close() {
  if (sending.value) return
  dialog.value?.close()
  open.value = false
  error.value = ''
}

/** 重复提交由服务端返回原记录，与首次提交一样视为成功，避免用户重复点击产生歧义。 */
async function submit() {
  if (sending.value) return
  if (!reason.value) {
    error.value = '请选择举报原因。'
    return
  }
  if (length.value > 200) {
    error.value = '补充说明最多200字。'
    return
  }
  const token = authToken.value
  if (!props.authenticated || !token) {
    await show()
    return
  }
  const owner = context
  controller = new globalThis.AbortController()
  sending.value = true
  error.value = ''
  try {
    await submitContentReport(
      props.targetType,
      props.targetId,
      { reason: reason.value, detail: detail.value.trim() || null },
      token,
      controller.signal,
    )
    if (owner !== context) return
    notice.value = '已提交举报。'
    dialog.value?.close()
    open.value = false
    reason.value = ''
    detail.value = ''
  } catch (reasonValue) {
    if (owner === context)
      error.value = `${toErrorMessage(reasonValue)}。未确认提交，可重试。`
  } finally {
    if (owner === context) sending.value = false
  }
}
</script>

<template>
  <span class="report-action">
    <button type="button" class="report-entry" :disabled="sending" @click="show">举报</button>
    <span v-if="notice" role="status" class="report-notice">{{ notice }}</span>
    <Teleport to="body">
      <dialog
        v-if="open"
        ref="dialog"
        class="report-dialog"
        :aria-labelledby="'report-title-' + uniqueId"
        @cancel="sending ? $event.preventDefault() : close()"
        @close="open = false"
      >
        <form @submit.prevent="submit">
          <h2 :id="'report-title-' + uniqueId">{{ title }}</h2>
          <label :for="'report-reason-' + uniqueId">举报原因 <span>必选</span></label>
          <select
            :id="'report-reason-' + uniqueId"
            v-model="reason"
            required
            :disabled="sending"
          >
            <option value="" disabled>请选择举报原因</option>
            <option v-for="item in reportReasons" :key="item.value" :value="item.value">
              {{ item.label }}
            </option>
          </select>
          <label :for="'report-detail-' + uniqueId">补充说明 <span>选填</span></label>
          <textarea
            :id="'report-detail-' + uniqueId"
            v-model="detail"
            rows="3"
            placeholder="可补充具体问题，最多200字"
            :disabled="sending"
            :aria-invalid="length > 200 || !!error"
          />
          <p class="report-count" :class="{ invalid: length > 200 }">{{ length }} / 200</p>
          <p v-if="error" role="alert" class="report-error">{{ error }}</p>
          <p class="report-note">
            举报后由管理员核查，内容不会因举报立即下架。处理结果将通过系统通知告知。
          </p>
          <footer>
            <button type="button" :disabled="sending" @click="close">取消</button>
            <button type="submit" class="primary" :disabled="sending || length > 200">
              {{ sending ? '提交中…' : '提交举报' }}
            </button>
          </footer>
        </form>
      </dialog>
    </Teleport>
  </span>
</template>

<style scoped>
.report-action {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  margin-left: auto;
  min-width: 0;
}
.report-entry,
dialog button {
  font: inherit;
  border: 0;
  background: none;
  color: inherit;
  padding: 6px 0;
  cursor: pointer;
}
.report-entry {
  border: 0;
  background: none;
  color: inherit;
}
.report-entry:disabled {
  cursor: default;
  opacity: 0.55;
}
.report-notice {
  color: #68804b;
  font-size: 11px;
  white-space: nowrap;
}
.report-dialog {
  width: min(440px, calc(100% - 32px));
  padding: 24px;
  border: 1px solid #ece6de;
  border-radius: 12px;
  color: #38434e;
  font-size: 13px;
}
.report-dialog::backdrop {
  background: #1f293755;
}
.report-dialog h2 {
  margin: 0 0 16px;
  font-size: 19px;
}
.report-dialog label {
  display: flex;
  gap: 8px;
  margin: 16px 0 10px;
  color: #59636e;
}
.report-dialog label span {
  color: #98a0a8;
  font-size: 12px;
}
.report-dialog select,
.report-dialog textarea {
  display: block;
  width: 100%;
  box-sizing: border-box;
  border: 1px solid #e6e8eb;
  padding: 10px;
  font: inherit;
  border-radius: 5px;
  background: #fafbfc;
  color: #38434e;
}
.report-count {
  margin: 6px 0 0;
  text-align: right;
  color: #98a0a8;
  font-size: 12px;
}
.report-count.invalid,
.report-error {
  color: #a53c2e;
}
.report-error,
.report-note {
  line-height: 1.8;
  overflow-wrap: anywhere;
}
.report-dialog footer {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  margin-top: 20px;
}
.report-dialog footer button {
  padding: 9px 14px;
  border: 1px solid #e7dfd2;
  border-radius: 6px;
  background: white;
  color: #9c773e;
}
.report-dialog footer .primary {
  border-color: #d9a05e;
  background: #fb8b24;
  color: #fff;
  font-weight: 600;
}
.report-dialog footer button:disabled {
  opacity: 0.55;
  cursor: default;
}
button:focus-visible,
select:focus-visible,
textarea:focus-visible {
  outline: 2px solid #cf9f53;
  outline-offset: 3px;
}
</style>
