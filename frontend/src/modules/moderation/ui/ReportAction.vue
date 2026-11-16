<script setup lang="ts">
import { computed, nextTick, onUnmounted, ref, useId, useTemplateRef, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { authToken } from '@/shared/api/authSession'
import { toErrorMessage } from '@/shared/lib/toErrorMessage'
import { submitContentReport } from '../api/contentReportsApi'
import { reportTargetLabel, type ReportReason } from '../model/reportReasons'
import ReportReasonSelect from './ReportReasonSelect.vue'
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
const successDialog = useTemplateRef('successDialog')
const entry = useTemplateRef('entry')
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
  successDialog.value?.close()
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
/** 成功反馈只存在于独立弹窗；关闭后入口始终保持“举报”，不在原内容旁追加回执。 */
function closeSuccess() {
  successDialog.value?.close()
  notice.value = ''
  entry.value?.focus({ preventScroll: true })
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
    dialog.value?.close()
    open.value = false
    reason.value = ''
    detail.value = ''
    notice.value = '举报成功'
    await nextTick()
    if (owner === context) successDialog.value?.showModal()
  } catch (reasonValue) {
    if (owner === context) error.value = `${toErrorMessage(reasonValue)}。未确认提交，可重试。`
  } finally {
    if (owner === context) sending.value = false
  }
}
</script>

<template>
  <span class="report-action">
    <button ref="entry" type="button" class="report-entry" :disabled="sending" @click="show">
      举报
    </button>
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
          <ReportReasonSelect
            v-model="reason"
            :input-id="'report-reason-' + uniqueId"
            :disabled="sending"
          />
          <label :for="'report-detail-' + uniqueId">补充说明 <span>选填</span></label>
          <textarea
            :id="'report-detail-' + uniqueId"
            v-model="detail"
            rows="3"
            placeholder="可补充具体问题，最多200字"
            :disabled="sending"
            :aria-invalid="length > 200 || !!error"
          />
          <p v-if="error || length > 200" role="alert" class="report-error">
            {{ error || '补充说明最多200字。' }}
          </p>
          <footer>
            <button type="button" :disabled="sending" @click="close">取消</button>
            <button type="submit" class="primary" :disabled="sending || length > 200">
              {{ sending ? '提交中…' : '提交举报' }}
            </button>
          </footer>
        </form>
      </dialog>
      <dialog
        v-if="notice"
        ref="successDialog"
        class="report-success-dialog"
        :aria-labelledby="'report-success-title-' + uniqueId"
        @cancel.prevent="closeSuccess"
        @close="notice = ''"
      >
        <button
          type="button"
          class="report-success-close"
          aria-label="关闭举报成功提示"
          autofocus
          @click="closeSuccess"
        >
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m6 6 12 12M6 18 18 6" /></svg>
        </button>
        <span class="report-success-icon" aria-hidden="true"
          ><svg viewBox="0 0 24 24"><path d="m6 12 4 4 8-8" /></svg
        ></span>
        <h2 :id="'report-success-title-' + uniqueId" role="status">{{ notice }}</h2>
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
.report-dialog {
  width: min(440px, calc(100% - 32px));
  padding: 24px;
  border: 1px solid #ece6de;
  border-radius: 12px;
  color: #38434e;
  font-size: 13px;
  overflow: visible;
}
.report-dialog::backdrop,
.report-success-dialog::backdrop {
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
.report-error {
  color: #a53c2e;
}
.report-error {
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
button:focus-visible {
  outline: 2px solid #a5adb7;
  outline-offset: 3px;
}
/* 补充说明保留原浅灰边框，输入时只显示光标，不加描边或光晕。 */
.report-dialog textarea:focus {
  outline: none;
  box-shadow: none;
  border-color: #e6e8eb;
}
.report-success-dialog {
  width: min(300px, calc(100% - 32px));
  box-sizing: border-box;
  padding: 38px 24px 30px;
  border: 1px solid #e6e8eb;
  border-radius: 12px;
  background: #fff;
  color: #38434e;
  text-align: center;
}
.report-success-close {
  position: absolute;
  top: 6px;
  right: 6px;
  width: 40px;
  height: 40px;
  display: grid;
  place-items: center;
  padding: 0;
  color: #9aa4af;
}
.report-success-close svg {
  width: 17px;
  height: 17px;
  fill: none;
  stroke: currentColor;
  stroke-width: 1.5;
  stroke-linecap: round;
}
.report-success-close:focus-visible {
  outline: none;
  box-shadow: none;
}
.report-success-icon {
  display: grid;
  place-items: center;
  width: 42px;
  height: 42px;
  margin: 0 auto;
  border-radius: 50%;
  background: #edf5eb;
  color: #7e9d70;
}
.report-success-icon svg {
  width: 25px;
  height: 25px;
  fill: none;
  stroke: currentColor;
  stroke-width: 1.7;
  stroke-linecap: round;
  stroke-linejoin: round;
}
.report-success-dialog h2 {
  margin: 13px 0 0;
  font: inherit;
  font-size: 18px;
  font-weight: 600;
}
</style>
