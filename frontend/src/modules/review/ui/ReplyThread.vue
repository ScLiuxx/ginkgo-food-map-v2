<script setup lang="ts">
import { computed, inject, nextTick, onMounted, onUnmounted, ref, useId, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { authToken } from '@/shared/api/authSession'
import { useLoginStore } from '@/modules/auth/model/login.store'
import { reviewGatewayKey, unavailableReviewGateway, validBody } from '../model/review'
import { activeReplyEditorKey, replyScopeVisibleKey } from '../model/replyEditor'
import type { ReplyPage } from '../model/review'
import { UserIdentity, ContentActions } from '@/modules/community'
import ReviewActionsMenu from './ReviewActionsMenu.vue'

/** 阅读与编辑独立展开；主卡和历史卡共享编辑焦点，各自保留正文、游标与幂等编号。 */
const props = defineProps<{
  reviewId: string
  username: string
  combined?: boolean
  /** 独立详情使用常驻阅读和底部编辑器，仍沿用同一套请求隔离与失败重试。 */
  detailMode?: boolean
  initialPage?: ReplyPage
  /** 通知已重新鉴权的指定回复先展示，避免为定位连续扫描所有历史分页。 */
  focusedReply?: ReplyPage['records'][number]
}>()
const emit = defineEmits<{ page: [value: ReplyPage]; invalidate: [] }>()
const gateway = inject(reviewGatewayKey, unavailableReviewGateway)
const activeEditor = inject(activeReplyEditorKey, ref<string | null>(null))
const scopeVisible = inject(replyScopeVisibleKey, ref(true))
const auth = useLoginStore(),
  router = useRouter(),
  route = useRoute()
const open = ref(!!props.detailMode),
  loaded = ref(!!props.initialPage),
  loading = ref(false),
  sending = ref(false)
const records = ref<ReplyPage['records']>(props.initialPage?.records.slice() ?? []),
  hasMore = ref(props.initialPage?.hasMore ?? false)
const removedTarget = ref(false),
  highlighted = ref(false)
let highlightTimer: ReturnType<typeof globalThis.setTimeout> | undefined
const listRoot = ref<globalThis.HTMLOListElement>()
const displayedRecords = computed(() => {
  const target = !removedTarget.value && props.focusedReply
  if (!target) return records.value
  // 分页后来读到相同ID时采用新记录并去重，原游标顺序不变，不伪造总条数。
  return [
    records.value.find((row) => row.id === target.id) ?? target,
    ...records.value.filter((row) => row.id !== target.id),
  ]
})
const draft = ref(''),
  readError = ref(''),
  sendError = ref(''),
  notice = ref('')
const input = ref<globalThis.HTMLTextAreaElement>()
const pendingDelete = ref<string | null>(null)
const deleting = ref(false)
const deleteError = ref('')
let restoreDeleteFocus: (() => void) | undefined
let deleteController: globalThis.AbortController | undefined
const replyAction = ref<globalThis.HTMLButtonElement>()
const uniqueId = useId(),
  listId = 'reply-list-' + uniqueId,
  editorId = 'reply-editor-' + uniqueId
const editing = computed(() =>
  props.detailMode
    ? scopeVisible.value && auth.authenticated
    : activeEditor.value === props.reviewId,
)
const length = computed(() => Array.from(draft.value.trim()).length)
let cursor: string | undefined = props.initialPage?.nextCursor ?? undefined
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
  restoreDeleteFocus = undefined
  if (!props.detailMode && editing.value) activeEditor.value = null
  sending.value = false
  draft.value = readError.value = sendError.value = notice.value = ''
  request = undefined
  cursor = undefined
  records.value = []
  removedTarget.value = false
  highlighted.value = false
  globalThis.clearTimeout(highlightTimer)
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
    if (!props.detailMode && editing.value) activeEditor.value = null
    if (!deleting.value) pendingDelete.value = null
  } else if (open.value && !loaded.value) void load()
})
onMounted(() => {
  if (props.detailMode && scopeVisible.value && !loaded.value) void load()
  if (props.detailMode && props.focusedReply) void focusTarget()
})
onUnmounted(() => {
  context++
  cancelRead()
  postController?.abort()
  deleteController?.abort()
  globalThis.clearTimeout(highlightTimer)
  if (!props.detailMode && editing.value) activeEditor.value = null
})

function toggleReplies() {
  open.value = !open.value
  if (!open.value) cancelRead()
  else if (!loaded.value) void load()
}

/** 紧凑卡片用一个入口展开阅读和编辑；收起只隐藏草稿，重开仍复用失败请求号。 */
function toggleCombined() {
  if (sending.value) return
  if (open.value) {
    open.value = false
    cancelRead()
    if (editing.value) activeEditor.value = null
  } else {
    open.value = true
    if (!loaded.value) void load()
    if (auth.authenticated) void startReply()
  }
}

/** 点击回复才打开编辑器；切换其他点评只隐藏原草稿，不丢弃失败请求号。 */
async function startReply() {
  if (!auth.authenticated) {
    await router.push({ path: '/login', query: { redirect: route.fullPath } })
    return
  }
  if (!props.detailMode) activeEditor.value = props.reviewId
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

async function requestDelete(replyId: string, restoreFocus?: () => void) {
  if (deleting.value) return
  pendingDelete.value = replyId
  deleteError.value = ''
  restoreDeleteFocus = restoreFocus
  if (props.detailMode) {
    // 更多菜单选中后会隐藏，先把焦点交给可见的取消确认，避免键盘停在隐藏按钮上。
    await nextTick()
    globalThis.document
      .getElementById('reply-delete-' + uniqueId)
      ?.querySelector('button')
      ?.focus()
  }
}
function cancelDeleteReply() {
  if (deleting.value) return
  pendingDelete.value = null
  deleteError.value = ''
  restoreDeleteFocus?.()
  restoreDeleteFocus = undefined
}

/** 成功后丢弃旧游标，从头读取，避免删除分页锚点后继续使用失效边界。 */
async function removeReply() {
  const replyId = pendingDelete.value
  const answer = displayedRecords.value.find((row) => row.id === replyId)
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
    restoreDeleteFocus = undefined
    notice.value = '回复已删除。'
    if (props.focusedReply?.id === replyId) removedTarget.value = true
    emit('invalidate')
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
    emit('page', {
      records: records.value.slice(),
      hasMore: hasMore.value,
      nextCursor: cursor ?? null,
    })
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
    if (!props.detailMode && editing.value) {
      activeEditor.value = null
      replyAction.value?.focus({ preventScroll: true })
    }
    notice.value = '回复已发送。'
    emit('invalidate')
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
/** 详情顶部“回复”入口仅将焦点交给常驻输入框，不创建第二份草稿。 */
defineExpose({ focusInput: startReply })

/** 只滚动详情自身，不移动背后的消息列表；目标来自精确接口，无需逐页查找。 */
async function focusTarget() {
  await nextTick()
  const target = listRoot.value?.querySelector<globalThis.HTMLElement>('[data-target-reply="true"]')
  const panel = target?.closest<globalThis.HTMLElement>('.discussion-scroll')
  if (!target || !panel || !scopeVisible.value) return
  highlighted.value = true
  const top =
    target.getBoundingClientRect().top - panel.getBoundingClientRect().top + panel.scrollTop
  panel.scrollTop = Math.max(0, top - 24)
  globalThis.clearTimeout(highlightTimer)
  highlightTimer = globalThis.setTimeout(() => {
    highlighted.value = false
  }, 2400)
}
</script>

<template>
  <div class="reply-thread" :class="{ 'combined-replies': combined, 'detail-replies': detailMode }">
    <div v-if="!detailMode" class="thread-actions">
      <button
        ref="replyAction"
        type="button"
        class="text-action"
        :aria-expanded="combined ? open : editing"
        :aria-controls="combined ? `${listId} ${editorId}` : editorId"
        :disabled="auth.restoring || sending"
        @click="combined ? toggleCombined() : startReply()"
      >
        <svg v-if="combined" viewBox="0 0 24 24" aria-hidden="true">
          <path d="M21 11.5a8.5 8.5 0 0 1-12.7 7.4L3 21l1.8-5.4A8.5 8.5 0 1 1 21 11.5Z" />
        </svg>
        {{ combined && open ? '收起回复' : '回复' }}
      </button>
      <button
        v-if="!combined"
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
      <h3 v-if="detailMode" class="all-replies-title">
        全部回复<span v-if="loaded && !hasMore && !readError">{{ displayedRecords.length }}</span>
      </h3>
      <button
        v-if="combined && !auth.authenticated"
        type="button"
        class="text-action"
        @click="startReply"
      >
        登录后回复
      </button>
      <ol v-if="displayedRecords.length" ref="listRoot" class="reply-list">
        <li
          v-for="answer in displayedRecords"
          :key="answer.id"
          :data-target-reply="answer.id === focusedReply?.id"
          :class="{ 'target-reply-highlight': highlighted && answer.id === focusedReply?.id }"
        >
          <span v-if="detailMode" class="detail-reply-avatar" aria-hidden="true">{{
            Array.from(answer.author.username.trim())[0]?.toUpperCase() || '同'
          }}</span>
          <div class="reply-copy">
            <div class="reply-meta">
              <UserIdentity
                :user-id="answer.author.id"
                :username="answer.author.username"
                :authenticated="auth.authenticated"
                :viewer-id="auth.user ? String(auth.user.id) : undefined"
              />
              <time v-if="!detailMode" :datetime="answer.createdAt">{{
                new Date(answer.createdAt).toLocaleDateString('zh-CN')
              }}</time>
            </div>
            <p class="reply-body">{{ answer.body }}</p>
            <div class="reply-row-actions">
              <time v-if="detailMode" :datetime="answer.createdAt">{{
                new Date(answer.createdAt).toLocaleDateString('zh-CN')
              }}</time>
              <ContentActions
                kind="reply"
                :target-id="answer.id"
                :viewer-id="auth.user ? String(auth.user.id) : undefined"
                :authenticated="auth.authenticated"
                :own="auth.authenticated && answer.author.id === String(auth.user?.id)"
              />
              <ReviewActionsMenu
                v-if="detailMode && gateway.removeReply && ownsReply(answer.author.id)"
              >
                <template #default="{ restoreFocus }"
                  ><button
                    type="button"
                    :disabled="deleting"
                    @click="requestDelete(answer.id, restoreFocus)"
                  >
                    删除回复
                  </button></template
                >
              </ReviewActionsMenu>
            </div>
            <template v-if="gateway.removeReply && ownsReply(answer.author.id)">
              <div
                v-if="pendingDelete === answer.id"
                :id="'reply-delete-' + uniqueId"
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
                  @click="cancelDeleteReply"
                >
                  取消删除
                </button>
                <button type="button" class="text-action" :disabled="deleting" @click="removeReply">
                  {{ deleting ? '删除中…' : '确认删除回复' }}
                </button>
              </div>
              <button
                v-else-if="!detailMode"
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

    <div class="reply-editor-host" :class="{ 'detail-composer-dock': detailMode }">
      <p v-if="notice && !readError" role="status" class="reply-notice">{{ notice }}</p>
      <button
        v-if="detailMode && !auth.authenticated"
        class="detail-login-reply"
        type="button"
        @click="startReply"
      >
        登录后回复
      </button>
      <form v-show="editing" :id="editorId" class="reply-composer" @submit.prevent="send">
        <label :class="{ 'detail-input-label': detailMode }" :for="'reply-input-' + uniqueId"
          >回复 {{ username }}</label
        >
        <textarea
          :id="'reply-input-' + uniqueId"
          ref="input"
          v-model="draft"
          :rows="detailMode ? 1 : 2"
          :placeholder="detailMode ? `回复 ${username}…` : '说说你的想法…'"
          :disabled="sending"
          :aria-invalid="length > 200 || !!sendError"
          @keydown.enter="enter"
        />
        <p v-if="sendError" role="alert" class="reply-error">{{ sendError }}</p>
        <div class="composer-footer">
          <span
            v-if="!detailMode || length > 150"
            class="word-count"
            :class="{ invalid: length > 200 }"
            >{{ length }} / 200</span
          >
          <button
            v-if="!detailMode"
            type="button"
            class="cancel-action"
            :disabled="sending"
            @click="cancelEditing"
          >
            取消
          </button>
          <button
            class="send-action"
            :disabled="sending || auth.restoring || (detailMode && !length)"
          >
            {{ sending ? '发送中…' : '发送' }}
          </button>
        </div>
      </form>
    </div>
  </div>
</template>

<style scoped src="./review-thread-detail.css"></style>
