<script setup lang="ts">
import { onBeforeUnmount, onMounted, useTemplateRef, watch } from 'vue'
import { authToken } from '@/shared/api/authSession'
import { reportDecisionLabel } from '../model/reportReasons'

/** 结果通知只展示处置结论，不提供内容跳转：被删除的内容已无法访问。 */
defineProps<{ decision: 'REMOVED' | 'DISMISSED' }>()
const emit = defineEmits<{ close: [] }>()
const dialog = useTemplateRef('dialog')
onMounted(() => dialog.value?.showModal())
watch(authToken, () => emit('close'), { flush: 'sync' })
onBeforeUnmount(() => dialog.value?.close())
</script>

<template>
  <dialog
    ref="dialog"
    class="report-result-dialog"
    aria-labelledby="report-result-heading"
    @cancel.prevent="emit('close')"
  >
    <header>
      <span class="result-tag">举报处理结果</span>
      <button type="button" class="result-close" aria-label="关闭举报处理结果" @click="emit('close')">
        ×
      </button>
    </header>
    <h2 id="report-result-heading">{{ reportDecisionLabel(decision) }}</h2>
    <p class="result-content">
      {{
        decision === 'REMOVED'
          ? '你举报的内容经管理员核查后被永久删除。'
          : '管理员核查后未认定该内容违规，内容保持原样。'
      }}
    </p>
    <p class="result-note">
      这是处理结果通知。为避免打开已删除的内容，本通知不提供内容跳转。
    </p>
    <footer>
      <button type="button" class="result-close-action" @click="emit('close')">关闭</button>
    </footer>
  </dialog>
</template>

<style scoped src="../../../shared/styles/content-dialog.css"></style>
<style scoped>
.result-tag {
  color: #e18b42;
  font-size: 11px;
  letter-spacing: 0.5px;
}
.report-result-dialog h2 {
  margin: 20px 0 10px;
  color: #273443;
  font-size: 23px;
  line-height: 1.5;
}
.result-content {
  margin: 20px 0 0;
  padding-top: 20px;
  border-top: 1px solid #edf0f3;
  color: #475566;
  font-size: 15px;
  line-height: 1.95;
}
.result-note {
  color: #8a95a2;
  font-size: 12px;
  line-height: 1.8;
}
.report-result-dialog footer {
  display: flex;
  justify-content: flex-end;
  margin-top: 18px;
}
</style>
