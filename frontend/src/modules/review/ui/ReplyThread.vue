<script setup lang="ts">
import { computed, inject, nextTick, onUnmounted, ref, useId, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { authToken } from '@/shared/api/authSession'
import { useLoginStore } from '@/modules/auth/model/login.store'
import { reviewGatewayKey, unavailableReviewGateway, validBody } from '../model/review'
import { activeReplyEditorKey, replyScopeVisibleKey } from '../model/replyEditor'
import type { ReplyPage } from '../model/review'
import { UserIdentity, ContentActions } from '@/modules/community'

/** 阅读与编辑独立展开；主卡和历史卡共享编辑焦点，各自保留正文、游标与幂等编号。 */
const props = defineProps<{ reviewId: string; username: string }>()
const gateway = inject(reviewGatewayKey, unavailableReviewGateway)
const activeEditor = inject(activeReplyEditorKey, ref<string | null>(null))
const scopeVisible = inject(replyScopeVisibleKey, ref(true))
const auth = useLoginStore(),
  router = useRouter(),
  route = useRoute()
const open = ref(false),
  loaded = ref(false),
  loading = ref(false),
  sending = ref(false)
const records = ref<ReplyPage['records']>([]),
  hasMore = ref(false)
const draft = ref(''),
  readError = ref(''),
  sendError = ref(''),
  notice = ref('')
const input = ref<globalThis.HTMLTextAreaElement>()
const pendingDelete = ref<string | null>(null)
const deleting = ref(false)
const deleteError = ref('')
let deleteController: globalThis.AbortController | undefined
const replyAction = ref<globalThis.HTMLButtonElement>()
const uniqueId = useId(),
  listId = 'reply-list-' + uniqueId,
  editorId = 'reply-editor-' + uniqueId
const editing = computed(() => activeEditor.value === props.reviewId)
const length = computed(() => Array.from(draft.value.trim()).length)
let cursor: string | undefined
let readController: globalThis.AbortController | undefined,
  postController: globalThis.AbortController | undefined
let readGeneration = 0,
  context = 0
let request: { body: string; id: string } | undefined

/** 关闭阅读仅取消GET；切换账号、点评或卸载才使发布回执失效。 */
function cancelRead() {
  readGeneration++
  readController?.abort()
  loading.value = false
}
function reset() {
  context++
  cancelRead()
  postController?.abort()
  deleteController?.abort()
  pendingDelete.value = null
  deleting.value = false
  deleteError.value = ''
  if (editing.value) activeEditor.value = null
  sending.value = false
  draft.value = readError.value = sendError.value = notice.value = ''
  request = undefined
  cursor = undefined
  records.value = []
  loaded.value = hasMore.value = false
  if (open.value && scopeVisible.value) void load()
}
watch(
  () => props.reviewId,
  (_id, previous) => {
    if (activeEditor.value === previous) activeEditor.value = null
    reset()
  },
)
watch(authToken, reset, { flush: 'sync' })
watch(scopeVisible, (visible) => {
  if (!visible) {
    cancelRead()
    if (editing.value) activeEditor.value = null
  } else if (open.value && !loaded.value) void load()
})
onUnmounted(() => {
  context++
  cancelRead()
  postController?.abort()
  deleteController?.abort()
  if (editing.value) activeEditor.value = null
})

function toggleReplies() {
  open.value = !open.value
  if (!open.value) cancelRead()
  else if (!loaded.value) void load()
}

/** 点击回复才打开编辑器；切换其他点评只隐藏原草稿，不丢弃失败请求号。 */
async function startReply() {
  if (!auth.authenticated) {
    await router.push({ path: '/login', query: { redirect: route.fullPath } })
    return
  }
  activeEditor.value = props.reviewId
  sendError.value = ''
  await nextTick()
  input.value?.focus({ preventScroll: true })
}
function cancelEditing() {
  if (sending.value) return
  activeEditor.value = null
  replyAction.value?.focus({ preventScroll: true })
}

/** 只显示当前账号自己的入口；后端仍独立校验，点评作者不获得代删权限。 */
function ownsReply(authorId: string) {
  return auth.authenticated && !auth.restoring && authorId === String(auth.user?.id)
}

function requestDelete(replyId: string) {
  if (deleting.value) return
  pendingDelete.value = replyId
  deleteError.value = ''
}

/** 成功后丢弃旧游标，从头读取，避免删除分页锚点后继续使用失效边界。 */
async function removeReply() {
  const replyId = pendingDelete.value
  const answer = records.value.find((row) => row.id === replyId)
  if (!replyId || !answer || !ownsReply(answer.author.id) || !gateway.removeReply || deleting.value)
    return
  const owner = context
  deleteController = new globalThis.AbortController()
  deleting.value = true
  deleteError.value = ''
  notice.value = ''
  try {
    await gateway.removeReply(replyId, deleteController.signal)
    if (owner !== context) return
    pendingDelete.value = null
    notice.value = '回复已删除。'
    cancelRead()
    records.value = []
    cursor = undefined
    loaded.value = hasMore.value = false
    if (open.value && scopeVisible.value) await load(true)
  } catch (reason) {
    if (owner === context)
      deleteError.value =
        reason instanceof Error ? reason.message : '删除未确认，可重试或刷新核对。'
  } finally {
    if (owner === context) deleting.value = false
  }
}

/** 失败保留已读内容和原游标；只有失效游标时用户才需要从头重载。 */
async function load(refresh = false) {
  if (!gateway.listReplies || loading.value) return
  if (refresh) {
    records.value = []
    cursor = undefined
    loaded.value = hasMore.value = false
  }
  const ticket = ++readGeneration,
    owner = context
  readController = new globalThis.AbortController()
  loading.value = true
  readError.value = ''
  try {
    const page = await gateway.listReplies(props.reviewId, readController.signal, cursor)
    if (ticket !== readGeneration || owner !== context) return
    const known = new Set(records.value.map((row) => row.id))
    records.value.push(...page.records.filter((row) => !known.has(row.id)))
    cursor = page.nextCursor ?? undefined
    hasMore.value = page.hasMore
    loaded.value = true
  } catch (reason) {
    if (ticket === readGeneration && owner === context)
      readError.value = reason instanceof Error ? reason.message : '回复加载失败，请重试。'
  } finally {
    if (ticket === readGeneration && owner === context) loading.value = false
  }
}

/** 回车发送、Shift+回车换行，输入法确认候选字时不触发提交。 */
function enter(event: globalThis.KeyboardEvent) {
  if (event.isComposing || event.shiftKey) return
  event.preventDefault()
  void send()
}

/** 校验成功回执后关闭自己的编辑框，再刷新回复；读取失败不重发POST。 */
async function send() {
  if (sending.value) return
  if (!auth.authenticated) {
    await startReply()
    return
  }
  if (!validBody(draft.value, 1, 200)) {
    sendError.value = '回复需要1–200字。'
    return
  }
  const body = draft.value.trim(),
    owner = context
  if (!request || request.body !== body) request = { body, id: globalThis.crypto.randomUUID() }
  const current = request
  postController = new globalThis.AbortController()
  sending.value = true
  sendError.value = notice.value = ''
  try {
    await gateway.reply(props.reviewId, current.body, current.id, postController.signal)
    if (owner !== context) return
    request = undefined
    draft.value = ''
    if (editing.value) {
      activeEditor.value = null
      replyAction.value?.focus({ preventScroll: true })
    }
    notice.value = '回复已发送。'
    cancelRead()
    records.value = []
    cursor = undefined
    hasMore.value = loaded.value = false
    open.value = true
    if (scopeVisible.value) await load(true)
  } catch (reason) {
    if (owner === context)
      sendError.value = reason instanceof Error ? reason.message : '回复失败，文字已保留。'
  } finally {
    if (owner === context) sending.value = false
  }
}
</script>

<template>
  <div class="reply-thread">
    <div class="thread-actions">
      <button
        ref="replyAction"
        type="button"
        class="text-action"
        :aria-expanded="editing"
        :aria-controls="editorId"
        :disabled="auth.restoring || sending"
        @click="startReply"
      >
        回复
      </button>
      <button
        type="button"
        class="text-action"
        :aria-expanded="open"
        :aria-controls="listId"
        @click="toggleReplies"
      >
        {{ open ? '收起回复' : '查看回复' }}
      </button>
      <span v-if="$slots.actions" class="extra-actions"><slot name="actions" /></span>
    </div>

    <div v-show="open" :id="listId" class="thread-content">
      <ol v-if="records.length" class="reply-list">
        <li v-for="answer in records" :key="answer.id">
          <div class="reply-copy">
            <div class="reply-meta">
              <UserIdentity
                :user-id="answer.author.id"
                :username="answer.author.username"
                :authenticated="auth.authenticated"
              />
              <time :datetime="answer.createdAt">{{
                new Date(answer.createdAt).toLocaleDateString('zh-CN')
              }}</time>
            </div>
            <p class="reply-body">{{ answer.body }}</p>
            <ContentActions
              kind="reply"
              :target-id="answer.id"
              :viewer-id="auth.user ? String(auth.user.id) : undefined"
              :authenticated="auth.authenticated"
              :own="auth.authenticated && answer.author.id === String(auth.user?.id)"
            />
            <template v-if="gateway.removeReply && ownsReply(answer.author.id)">
              <div
                v-if="pendingDelete === answer.id"
                class="delete-confirmation"
                role="group"
                aria-label="确认删除回复"
              >
                <p>确定删除这条回复？删除后无法恢复。</p>
                <p v-if="deleteError" role="alert" class="reply-error">{{ deleteError }}</p>
                <button
                  type="button"
                  class="text-action"
                  :disabled="deleting"
                  @click="pendingDelete = null"
                >
                  取消删除
                </button>
                <button type="button" class="text-action" :disabled="deleting" @click="removeReply">
                  {{ deleting ? '删除中…' : '确认删除回复' }}
                </button>
              </div>
              <button
                v-else
                type="button"
                class="text-action"
                :disabled="deleting"
                @click="requestDelete(answer.id)"
              >
                删除回复
              </button>
            </template>
          </div>
        </li>
      </ol>
      <p v-else-if="loaded && !loading && !readError" class="empty-replies">还没有回复</p>
      <p v-if="loading" role="status" class="muted">正在加载回复…</p>
      <div v-if="readError" role="alert" class="reply-error">
        <p>{{ notice ? notice.replace('。', '，但列表刷新失败：') : '' }}{{ readError }}</p>
        <button type="button" class="text-action" @click="load()">重试加载回复</button>
        <button type="button" class="text-action" @click="load(true)">从头加载</button>
      </div>
      <button
        v-else-if="loaded && hasMore"
        type="button"
        class="text-action"
        :disabled="loading"
        @click="load()"
      >
        加载更多回复
      </button>
    </div>

    <p v-if="notice && !readError" role="status" class="reply-notice">{{ notice }}</p>
    <form v-show="editing" :id="editorId" class="reply-composer" @submit.prevent="send">
      <label :for="'reply-input-' + uniqueId">回复 {{ username }}</label>
      <textarea
        :id="'reply-input-' + uniqueId"
        ref="input"
        v-model="draft"
        rows="2"
        placeholder="说说你的想法…"
        :disabled="sending"
        :aria-invalid="length > 200 || !!sendError"
        @keydown.enter="enter"
      />
      <p v-if="sendError" role="alert" class="reply-error">{{ sendError }}</p>
      <div class="composer-footer">
        <span class="word-count" :class="{ invalid: length > 200 }">{{ length }} / 200</span>
        <button type="button" class="cancel-action" :disabled="sending" @click="cancelEditing">
          取消
        </button>
        <button class="send-action" :disabled="sending || auth.restoring">
          {{ sending ? '发送中…' : '发送' }}
        </button>
      </div>
    </form>
  </div>
</template>

<style scoped>
.reply-thread {
  min-width: 0;
  margin-top: 8px;
  font-size: 13px;
}
.thread-actions {
  display: flex;
  align-items: center;
  gap: 20px;
  min-height: 44px;
}
.extra-actions {
  margin-left: auto;
  min-width: 0;
}
.text-action,
.cancel-action {
  font: inherit;
  border: 0;
  background: transparent;
  color: #85755f;
  cursor: pointer;
  min-height: 44px;
  padding: 8px 0;
}
.text-action:hover,
.cancel-action:hover {
  color: #674714;
}
button:focus-visible {
  outline: 2px solid #ad7618;
  outline-offset: 3px;
}
button:disabled {
  opacity: 0.5;
  cursor: default;
}
.thread-content {
  padding-left: 16px;
  border-left: 2px solid #eee8df;
  margin: 0 0 12px 4px;
  overflow-wrap: anywhere;
}
.reply-list {
  padding: 0;
  margin: 0;
  list-style: none;
}
li {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 10px 0;
}
.reply-avatar {
  width: 26px;
  height: 26px;
  border-radius: 50%;
  background: #f3f0e9;
  color: #968365;
  display: grid;
  place-items: center;
  flex-shrink: 0;
  font-size: 11px;
}
.reply-copy {
  min-width: 0;
  flex: 1;
}
.reply-meta {
  display: flex;
  align-items: baseline;
  flex-wrap: wrap;
  gap: 6px 12px;
}
.reply-meta strong {
  font-weight: 600;
  color: #655741;
  overflow-wrap: anywhere;
}
time,
.word-count,
.muted,
.empty-replies {
  color: #9a9081;
  font-size: 12px;
}
time {
  white-space: nowrap;
}
.reply-body {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  line-height: 1.7;
  color: #423d35;
  margin: 4px 0 0;
}
.reply-composer {
  margin: 8px 0 16px;
  padding: 14px;
  border: 1px solid #e8dfd0;
  border-radius: 12px;
  background: #fffcf7;
}
label {
  display: block;
  font-size: 12px;
  color: #8c7a62;
  margin-bottom: 10px;
  overflow-wrap: anywhere;
}
textarea {
  display: block;
  width: 100%;
  min-height: 64px;
  box-sizing: border-box;
  resize: vertical;
  max-height: 180px;
  font: inherit;
  line-height: 1.7;
  border: 0;
  border-radius: 0;
  padding: 0;
  background: transparent;
  color: #423d35;
}
textarea::placeholder {
  color: #aaa091;
}
/* 文本框通过整个编辑区的边框提示焦点，避免出现框中框。 */
textarea:focus-visible {
  outline: none;
}
.reply-composer:focus-within {
  border-color: #ccab72;
  box-shadow: 0 0 0 2px #e9b94912;
}
.composer-footer {
  display: flex;
  align-items: center;
  gap: 16px;
  margin-top: 8px;
}
.word-count {
  margin-right: auto;
}
.cancel-action {
  padding: 0 4px;
}
.send-action {
  border: 0;
  border-radius: 20px;
  background: #e9b949;
  color: #4c3816;
  font: inherit;
  font-weight: 600;
  padding: 0 20px;
  min-height: 40px;
  cursor: pointer;
}
.reply-error {
  color: #a53c2e;
  font-size: 12px;
  line-height: 1.7;
  overflow-wrap: anywhere;
}
.reply-notice {
  color: #68804b;
  font-size: 12px;
}
.delete-confirmation {
  padding: 8px 12px;
  border: 1px solid #e8dfd0;
  border-radius: 8px;
}
.delete-confirmation p {
  margin: 4px 0;
}
.delete-confirmation button + button {
  margin-left: 20px;
}
.invalid {
  color: #a53c2e;
}
@media (max-width: 480px) {
  .thread-actions {
    gap: 16px;
  }
  .thread-content {
    padding-left: 10px;
  }
  .reply-composer {
    padding: 12px;
  }
}
</style>
