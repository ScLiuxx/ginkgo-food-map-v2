<script setup lang="ts">
import { onBeforeUnmount, ref, watch, useTemplateRef, onMounted } from 'vue'
import { isAxiosError } from 'axios'
import { authToken } from '@/shared/api/authSession'
import { fetchAnnouncement, type AnnouncementDetail } from '../api/announcementApi'
import { toErrorMessage } from '@/shared/lib/toErrorMessage'
import { notificationRevision } from '@/modules/message/model/useRealtimeUnread'
const props = defineProps<{ id: string }>()
const emit = defineEmits<{ close: [] }>()
const dialog = useTemplateRef('dialog')
const detail = ref<AnnouncementDetail | null>(null),
  error = ref(''),
  loading = ref(false)
let version = 0,
  controller: globalThis.AbortController | undefined
/** 重查时先移除旧正文；撤回、换号及迟到结果不能继续显示旧内容。 */
async function load() {
  const token = authToken.value,
    id = props.id,
    ticket = ++version
  controller?.abort()
  controller = new globalThis.AbortController()
  detail.value = null
  error.value = ''
  if (!token) {
    emit('close')
    return
  }
  loading.value = true
  try {
    const result = await fetchAnnouncement(id, token, controller.signal)
    if (ticket === version && token === authToken.value && props.id === id) detail.value = result
  } catch (reason) {
    if (ticket === version && token === authToken.value)
      error.value =
        isAxiosError(reason) && reason.response?.status === 404
          ? '公告已撤回或不可访问。'
          : toErrorMessage(reason)
  } finally {
    if (ticket === version) loading.value = false
  }
}
onMounted(() => dialog.value?.showModal())
watch(
  () => props.id,
  () => void load(),
  { immediate: true },
)
watch(
  authToken,
  () => {
    version++
    controller?.abort()
    detail.value = null
    emit('close')
  },
  { flush: 'sync' },
)
watch(notificationRevision, () => void load())
onBeforeUnmount(() => {
  version++
  controller?.abort()
  dialog.value?.close()
})
</script>
<template>
  <dialog
    ref="dialog"
    class="announcement-reader content-dialog"
    aria-labelledby="announcement-reader-heading"
    @cancel.prevent="$emit('close')"
  >
    <header class="detail-dialog-header">
      <span class="reader-tag">系统公告</span>
      <button
        type="button"
        class="detail-dialog-close"
        aria-label="关闭公告"
        @click="$emit('close')"
      >
        ×
      </button>
    </header>
    <h2 id="announcement-reader-heading">{{ detail?.title || '公告详情' }}</h2>
    <p v-if="loading" class="detail-dialog-state" role="status">正在加载公告…</p>
    <div v-else-if="error" class="detail-dialog-state" role="alert">
      <p>{{ error }}</p>
      <button type="button" class="dialog-secondary" @click="load">重新读取</button>
    </div>
    <template v-else-if="detail">
      <time class="detail-dialog-date" :datetime="detail.publishedAt">{{
        new Date(detail.publishedAt).toLocaleString('zh-CN', {
          year: 'numeric',
          month: 'numeric',
          day: 'numeric',
          hour: '2-digit',
          minute: '2-digit',
          hour12: false,
        })
      }}</time>
      <p class="reader-content">{{ detail.content }}</p>
    </template>
    <footer class="detail-dialog-actions">
      <button type="button" class="dialog-secondary" @click="$emit('close')">关闭</button>
    </footer>
  </dialog>
</template>
<style scoped src="../../../shared/styles/content-dialog.css"></style>
<style scoped>
.reader-tag {
  color: #e18b42;
  font-size: 11px;
  letter-spacing: 0.5px;
}
.announcement-reader h2 {
  margin: 20px 0 10px;
  color: #273443;
  font-size: 23px;
  line-height: 1.5;
}
.reader-content {
  margin: 20px 0 0;
  padding-top: 20px;
  border-top: 1px solid #edf0f3;
  white-space: pre-wrap;
  color: #475566;
  font-size: 15px;
  line-height: 1.95;
}
@media (max-width: 500px) {
  .announcement-reader h2 {
    font-size: 20px;
  }
}
</style>
