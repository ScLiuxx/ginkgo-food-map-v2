<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useLoginStore } from '@/modules/auth'
import { authToken } from '@/shared/api/authSession'
import { httpClient } from '@/shared/api/httpClient'
import { toErrorMessage } from '@/shared/lib/toErrorMessage'
import { uploadImage, cancelImage } from '@/modules/file/api/mediaApi'
import { avatarUpdated, prepareAvatar } from '../model/avatar'
import UserAvatar from './UserAvatar.vue'

/** 草稿仅存弹窗内存；保存才上传，固定凭证贯穿上传/绑定/清理，迟到回执不能污染新账号。 */
const auth = useLoginStore()
const route = useRoute()
const dialog = ref<globalThis.HTMLDialogElement>()
const input = ref<globalThis.HTMLInputElement>()
const file = ref<globalThis.File>()
const preview = ref(''),
  error = ref(''),
  notice = ref('')
const busy = ref(false)
let fileId: string | undefined,
  token = '',
  userId = '',
  generation = 0
const cleanup = (id: string, credential: string) => {
  void cancelImage(id, credential).catch(() => {
    /* 未确认删除由后台TTL继续回收；已绑定文件不会被取消接口删除。 */
  })
}
function reset() {
  generation++
  if (fileId) cleanup(fileId, token)
  fileId = undefined
  if (preview.value) globalThis.URL.revokeObjectURL(preview.value)
  preview.value = ''
  file.value = undefined
  error.value = ''
  busy.value = false
  dialog.value?.close()
}
function open() {
  reset()
  token = authToken.value ?? ''
  userId = auth.user ? String(auth.user.id) : ''
  if (!token || !userId || !auth.authenticated) return
  notice.value = ''
  dialog.value?.showModal()
}
function close() {
  if (!busy.value) reset()
}
async function select(event: globalThis.Event) {
  const element = event.target as globalThis.HTMLInputElement
  const selected = element.files?.[0]
  element.value = ''
  if (!selected || busy.value) return
  const ticket = generation
  busy.value = true
  error.value = ''
  try {
    const result = await prepareAvatar(selected)
    if (ticket !== generation) return
    if (fileId) cleanup(fileId, token)
    fileId = undefined
    if (preview.value) globalThis.URL.revokeObjectURL(preview.value)
    file.value = result
    preview.value = globalThis.URL.createObjectURL(result)
  } catch (reason) {
    if (ticket === generation) error.value = toErrorMessage(reason)
  } finally {
    if (ticket === generation) busy.value = false
  }
}
async function save() {
  if (!file.value || busy.value) return
  const ticket = generation,
    credential = token,
    owner = userId
  busy.value = true
  error.value = ''
  try {
    // 绑定失败沿用同一文件重试；响应丢失后服务端同fileId幂等确认，不重复释放旧头像。
    const uploaded = fileId ?? (await uploadImage(file.value, credential, 'AVATAR'))
    if (ticket !== generation) {
      cleanup(uploaded, credential)
      return
    }
    fileId = uploaded
    const response = await httpClient.put(
      '/api/me/avatar',
      { fileId },
      { headers: { Authorization: `Bearer ${credential}` } },
    )
    if (response.status !== 204) throw new Error('头像保存结果无法确认，请重试。')
    if (ticket !== generation) return
    fileId = undefined
    avatarUpdated(owner)
    reset()
    notice.value = '头像已更新'
  } catch (reason) {
    if (ticket === generation) error.value = toErrorMessage(reason)
  } finally {
    if (ticket === generation) busy.value = false
  }
}
watch(
  () => [route.path, authToken.value, auth.user?.id],
  () => {
    reset()
    notice.value = ''
  },
  { flush: 'sync' },
)
onBeforeUnmount(reset)
</script>

<template>
  <div class="avatar-setting">
    <button type="button" class="avatar-row" @click="open">
      <span>头像</span
      ><span class="avatar-value"
        ><UserAvatar :user-id="String(auth.user?.id ?? '')" :username="auth.user?.username" /><span
          aria-hidden="true"
          >›</span
        ></span
      >
    </button>
    <p v-if="notice" class="avatar-notice" role="status">{{ notice }}</p>
    <dialog
      ref="dialog"
      class="avatar-dialog"
      aria-labelledby="avatar-title"
      @cancel.prevent="close"
    >
      <header>
        <h2 id="avatar-title">修改头像</h2>
        <button type="button" aria-label="关闭" :disabled="busy" @click="close">×</button>
      </header>
      <div class="avatar-preview">
        <img v-if="preview" :src="preview" alt="新头像预览" />
        <UserAvatar v-else :user-id="String(auth.user?.id ?? '')" :username="auth.user?.username" />
      </div>
      <input
        ref="input"
        class="avatar-input"
        type="file"
        accept="image/jpeg,image/png,image/webp"
        :disabled="busy"
        aria-label="选择头像图片"
        @change="select"
      />
      <button type="button" class="avatar-choose" :disabled="busy" @click="input?.click()">
        {{ file ? '重新选择' : '选择图片' }}
      </button>
      <p v-if="error" class="avatar-error" role="alert">{{ error }}</p>
      <footer>
        <button type="button" :disabled="busy" @click="close">取消</button
        ><button type="button" class="avatar-save" :disabled="busy || !file" @click="save">
          {{ busy ? '处理中…' : '保存' }}
        </button>
      </footer>
    </dialog>
  </div>
</template>

<style scoped>
.avatar-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  min-height: 72px;
  padding: 10px 0;
  border: 0;
  border-bottom: 1px solid #edf0f3;
  background: none;
  color: inherit;
  font: inherit;
  font-size: 15px;
  cursor: pointer;
}
.avatar-value {
  display: flex;
  align-items: center;
  gap: 15px;
  color: #a1aab5;
}
.avatar-notice {
  font-size: 13px;
  color: #57805e;
}
.avatar-dialog {
  width: min(360px, calc(100vw - 40px));
  padding: 24px;
  box-sizing: border-box;
  border: 1px solid #e5e8ec;
  border-radius: 16px;
  color: #34404d;
  background: #fff;
}
.avatar-dialog::backdrop {
  background: rgb(22 30 40 / 38%);
}
.avatar-dialog header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.avatar-dialog h2 {
  margin: 0;
  font-size: 18px;
}
.avatar-dialog button {
  font: inherit;
  cursor: pointer;
}
.avatar-dialog header button {
  border: 0;
  background: none;
  font-size: 26px;
  color: #8b96a3;
}
.avatar-preview {
  display: grid;
  place-items: center;
  margin: 28px auto 20px;
  width: 144px;
  height: 144px;
}
.avatar-preview img,
.avatar-preview :deep(.user-avatar) {
  width: 144px;
  height: 144px;
  border-radius: 50%;
  object-fit: cover;
  font-size: 52px;
}
.avatar-input {
  display: none;
}
.avatar-choose {
  display: block;
  margin: auto;
  padding: 8px 16px;
  border: 1px solid #dfe4ea;
  border-radius: 8px;
  background: #fff;
  color: #53606e;
}
.avatar-error {
  font-size: 13px;
  color: #b54e43;
  overflow-wrap: anywhere;
}
.avatar-dialog footer {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  margin-top: 24px;
}
.avatar-dialog footer button {
  padding: 9px 22px;
  border: 1px solid #e5e8ec;
  border-radius: 8px;
  background: #f5f6f8;
  color: #697586;
}
.avatar-dialog footer .avatar-save {
  background: #e99432;
  border-color: #e99432;
  color: #fff;
}
.avatar-dialog button:disabled {
  opacity: 0.5;
  cursor: default;
}
.avatar-dialog button:focus-visible,
.avatar-row:focus-visible {
  outline: 2px solid #95a0ab;
  outline-offset: 3px;
}
</style>
