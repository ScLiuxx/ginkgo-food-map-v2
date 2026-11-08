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
    class="announcement-reader"
    aria-labelledby="announcement-reader-heading"
    @cancel.prevent="$emit('close')"
  >
    <button class="reader-close" aria-label="关闭公告" @click="$emit('close')">×</button
    ><span class="reader-tag">系统公告</span>
    <h2 id="announcement-reader-heading">{{ detail?.title || '公告详情' }}</h2>
    <p v-if="loading" role="status">正在加载公告…</p>
    <div v-else-if="error" role="alert">
      <p>{{ error }}</p>
      <button @click="load">重新读取</button>
    </div>
    <template v-else-if="detail"
      ><time>{{ new Date(detail.publishedAt).toLocaleString('zh-CN', { hour12: false }) }}</time>
      <p class="reader-content">{{ detail.content }}</p>
      <footer>泥电美食地图</footer></template
    >
  </dialog>
</template>
<style scoped>
.announcement-reader {
  box-sizing: border-box;
  width: min(660px, calc(100% - 32px));
  max-height: 85vh;
  overflow: auto;
  border: 1px solid #e3ddd4;
  border-radius: 10px;
  padding: 32px;
  color: #30343a;
  background: #fff;
  font-family: Inter, 'PingFang SC', 'Microsoft YaHei', sans-serif;
}
.announcement-reader::backdrop {
  background: rgb(30 25 20 / 30%);
}
.reader-close {
  position: absolute;
  right: 12px;
  top: 8px;
  border: 0;
  background: transparent;
  font-size: 26px;
  padding: 4px 10px;
  cursor: pointer;
}
.reader-tag {
  color: #e97913;
  font-size: 13px;
}
.announcement-reader h2 {
  font-size: 23px;
  line-height: 1.6;
  margin: 14px 0;
  overflow-wrap: anywhere;
}
.announcement-reader time {
  color: #888;
  font-size: 13px;
}
.reader-content {
  border-top: 1px solid #ece8e2;
  padding-top: 22px;
  margin-top: 22px;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  line-height: 1.9;
}
.announcement-reader footer {
  text-align: right;
  color: #888;
  font-size: 13px;
  margin-top: 32px;
}
.announcement-reader button:focus-visible {
  outline: 2px solid #bd6816;
  outline-offset: 3px;
}
@media (max-width: 500px) {
  .announcement-reader {
    padding: 26px 20px;
  }
  .announcement-reader h2 {
    font-size: 20px;
  }
}
</style>
