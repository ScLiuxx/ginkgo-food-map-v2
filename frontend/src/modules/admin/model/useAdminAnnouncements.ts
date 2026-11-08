import { computed, ref, watch, onBeforeUnmount } from 'vue'
import { useLoginStore } from '@/modules/auth'
import { authToken } from '@/shared/api/authSession'
import { toErrorMessage } from '@/shared/lib/toErrorMessage'
import { isAxiosError } from 'axios'
import {
  fetchAdminAnnouncements,
  fetchAdminAnnouncement,
  publishAnnouncement,
  withdrawAnnouncement,
  normalizeAnnouncementText,
  type AnnouncementDetail,
  type AnnouncementPage,
  type AnnouncementInput,
  type AnnouncementStatus,
} from '@/modules/announcement'
/** 公告发布与记录共用身份上下文；换号取消在途请求并丢弃旧账号全部草稿和回执。 */
export function useAdminAnnouncements() {
  const auth = useLoginStore()
  const tab = ref<'publish' | 'records'>('publish'),
    title = ref(''),
    content = ref(''),
    status = ref<AnnouncementStatus>('ALL')
  const result = ref<AnnouncementPage | null>(null),
    detail = ref<AnnouncementDetail | null>(null)
  const busy = ref(false),
    loading = ref(false),
    inspecting = ref(false),
    error = ref(''),
    actionError = ref(''),
    notice = ref('')
  const pending = ref<AnnouncementInput | null>(null),
    page = ref(1)
  let context = 0,
    version = 0,
    detailVersion = 0
  let readAbort: AbortController | undefined,
    writeAbort: AbortController | undefined,
    detailAbort: AbortController | undefined
  const dirty = computed(() => !!title.value || !!content.value || !!pending.value)
  const identity = () =>
    auth.isAdministrator && authToken.value && auth.user
      ? { token: authToken.value, userId: auth.user.id, context }
      : null
  const valid = (actor: NonNullable<ReturnType<typeof identity>>) =>
    actor.context === context &&
    actor.token === authToken.value &&
    actor.userId === auth.user?.id &&
    auth.isAdministrator
  const validForm = computed(() => {
    const a = normalizeAnnouncementText(title.value),
      b = normalizeAnnouncementText(content.value)
    return !!a && !!b && Array.from(a).length <= 120 && Array.from(b).length <= 10000
  })
  async function load(next = page.value) {
    const actor = identity()
    if (!actor) return
    const ticket = ++version
    readAbort?.abort()
    readAbort = new AbortController()
    page.value = next
    loading.value = true
    error.value = ''
    result.value = null
    try {
      const data = await fetchAdminAnnouncements(status.value, next, actor.token, readAbort.signal)
      if (valid(actor) && ticket === version) result.value = data
    } catch (reason) {
      if (valid(actor) && ticket === version) error.value = toErrorMessage(reason)
    } finally {
      if (valid(actor) && ticket === version) loading.value = false
    }
  }
  /** 发布失败保留原UUID及冻结的正文，重试不会创建第二条；成功后的读取失败只重试GET。 */
  async function publish() {
    const actor = identity()
    if (!actor || busy.value || (!pending.value && !validForm.value)) return false
    pending.value ??= {
      clientRequestId: crypto.randomUUID(),
      title: normalizeAnnouncementText(title.value),
      content: normalizeAnnouncementText(content.value),
    }
    busy.value = true
    actionError.value = ''
    notice.value = ''
    writeAbort = new AbortController()
    try {
      const receipt = await publishAnnouncement(
        { ...pending.value },
        actor.token,
        writeAbort.signal,
      )
      if (!valid(actor)) return false
      pending.value = null
      title.value = ''
      content.value = ''
      tab.value = 'records'
      status.value = 'ALL'
      notice.value =
        receipt.status === 'WITHDRAWN' ? '已确认原公告已撤回，未重新发布。' : '公告已发布。'
      await load(1)
      return valid(actor)
    } catch (reason) {
      if (valid(actor)) {
        if (
          isAxiosError(reason) &&
          reason.response &&
          [400, 403, 409, 422].includes(reason.response.status)
        )
          pending.value = null
        actionError.value = pending.value
          ? `${toErrorMessage(reason)}。发布结果尚未确认，请重试同一公告。`
          : toErrorMessage(reason)
      }
      return false
    } finally {
      if (valid(actor)) busy.value = false
    }
  }
  async function inspect(id: string) {
    const actor = identity()
    if (!actor || busy.value) return
    const ticket = ++detailVersion
    detailAbort?.abort()
    detailAbort = new AbortController()
    detail.value = null
    inspecting.value = true
    actionError.value = ''
    try {
      const value = await fetchAdminAnnouncement(id, actor.token, detailAbort.signal)
      if (valid(actor) && ticket === detailVersion) detail.value = value
    } catch (reason) {
      if (valid(actor) && ticket === detailVersion) actionError.value = toErrorMessage(reason)
    } finally {
      if (valid(actor) && ticket === detailVersion) inspecting.value = false
    }
  }
  async function withdraw(id: string) {
    const actor = identity()
    if (!actor || busy.value) return false
    busy.value = true
    actionError.value = ''
    notice.value = ''
    writeAbort = new AbortController()
    try {
      await withdrawAnnouncement(id, actor.token, writeAbort.signal)
      if (!valid(actor)) return false
      notice.value = '公告已撤回。'
      detail.value = null
      await load(1)
      return valid(actor)
    } catch (reason) {
      if (valid(actor)) actionError.value = `${toErrorMessage(reason)}。可刷新记录核对或重试撤回。`
      return false
    } finally {
      if (valid(actor)) busy.value = false
    }
  }
  function clearDraft() {
    if (busy.value || pending.value) return
    title.value = content.value = actionError.value = ''
  }
  function reset() {
    context++
    version++
    detailVersion++
    readAbort?.abort()
    writeAbort?.abort()
    detailAbort?.abort()
    title.value = content.value = error.value = actionError.value = notice.value = ''
    pending.value = null
    result.value = detail.value = null
    busy.value = loading.value = inspecting.value = false
    tab.value = 'publish'
    status.value = 'ALL'
    page.value = 1
  }
  watch(() => [auth.user?.id, authToken.value, auth.isAdministrator], reset, { flush: 'sync' })
  watch([tab, status], () => {
    if (tab.value === 'records' && !busy.value) void load(1)
  })
  onBeforeUnmount(reset)
  return {
    tab,
    title,
    content,
    status,
    result,
    detail,
    busy,
    loading,
    inspecting,
    error,
    actionError,
    notice,
    pending,
    page,
    dirty,
    validForm,
    load,
    publish,
    inspect,
    withdraw,
    clearDraft,
  }
}
