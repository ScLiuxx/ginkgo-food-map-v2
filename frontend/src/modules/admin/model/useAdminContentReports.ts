import { onBeforeUnmount, ref, watch } from 'vue'
import { isAxiosError } from 'axios'
import { useLoginStore } from '@/modules/auth'
import { authToken } from '@/shared/api/authSession'
import { toErrorMessage } from '@/shared/lib/toErrorMessage'
import {
  dismissContentReport,
  fetchContentReport,
  fetchContentReports,
  removeContentReport,
  type ContentReport,
  type ContentReportPage,
  type ContentReportStatus,
  type RemoveReceipt,
} from '@/modules/moderation'

/** 状态文案与后端筛选枚举固定对应，避免把已处理举报混入待处理队列。 */
export const contentReportStatusNames: Record<ContentReportStatus, string> = {
  PENDING: '待处理',
  RESOLVED: '已删除内容',
  DISMISSED: '未认定违规',
}

type ActionResult = 'ok' | 'conflict' | 'failed'

/**
 * 管理端举报列表与处置状态。
 *
 * <p>身份、令牌和筛选共同决定一次快照；换号会取消在途请求并丢弃迟到回执。处置成功后从服务端
 * 重读当前页，409表示已被其他管理员处理，也必须刷新而不是继续展示旧状态。
 */
export function useAdminContentReports() {
  const auth = useLoginStore()
  const status = ref<ContentReportStatus>('PENDING'),
    page = ref(1)
  const result = ref<ContentReportPage | null>(null),
    detail = ref<ContentReport | null>(null)
  const loading = ref(false),
    inspecting = ref(false),
    busy = ref(false),
    error = ref(''),
    actionError = ref(''),
    notice = ref('')
  let context = 0,
    listVersion = 0,
    detailVersion = 0
  let listAbort: AbortController | undefined,
    detailAbort: AbortController | undefined,
    writeAbort: AbortController | undefined

  const identity = () =>
    auth.isAdministrator && authToken.value && auth.user
      ? { token: authToken.value, userId: auth.user.id, context }
      : null
  const valid = (actor: NonNullable<ReturnType<typeof identity>>) =>
    actor.context === context &&
    actor.token === authToken.value &&
    actor.userId === auth.user?.id &&
    auth.isAdministrator
  /** 空白备注不传，超长备注在发起请求前拒绝，避免服务端400与半完成操作。 */
  function noteValue(note: string): string | null | undefined {
    const value = note.trim()
    if (!value) return null
    return Array.from(value).length <= 200 ? value : undefined
  }

  async function load(next = page.value) {
    const actor = identity()
    if (!actor) return
    const ticket = ++listVersion
    listAbort?.abort()
    listAbort = new AbortController()
    page.value = next
    loading.value = true
    error.value = ''
    result.value = null
    detail.value = null
    try {
      const data = await fetchContentReports(status.value, next, actor.token, listAbort.signal)
      if (valid(actor) && ticket === listVersion) result.value = data
    } catch (reason) {
      if (valid(actor) && ticket === listVersion) error.value = toErrorMessage(reason)
    } finally {
      if (valid(actor) && ticket === listVersion) loading.value = false
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
      const value = await fetchContentReport(id, actor.token, detailAbort.signal)
      if (valid(actor) && ticket === detailVersion) detail.value = value
    } catch (reason) {
      if (valid(actor) && ticket === detailVersion) actionError.value = toErrorMessage(reason)
    } finally {
      if (valid(actor) && ticket === detailVersion) inspecting.value = false
    }
  }

  async function dismiss(id: string, note: string): Promise<ActionResult> {
    const actor = identity()
    const decisionNote = noteValue(note)
    if (!actor || busy.value) return 'failed'
    if (decisionNote === undefined) {
      actionError.value = '处理备注最多200字。'
      return 'failed'
    }
    busy.value = true
    actionError.value = notice.value = ''
    writeAbort = new AbortController()
    try {
      const receipt = await dismissContentReport(id, decisionNote, actor.token, writeAbort.signal)
      if (!valid(actor)) return 'failed'
      notice.value =
        receipt.affectedReports > 0
          ? `举报已驳回，共处理 ${receipt.affectedReports} 条举报。`
          : '该举报此前已驳回。'
      detail.value = null
      await load(page.value)
      return valid(actor) ? 'ok' : 'failed'
    } catch (reason) {
      if (valid(actor)) {
        if (isAxiosError(reason) && reason.response?.status === 409) {
          actionError.value = '该举报已被其他管理员处理，已刷新列表。'
          detail.value = null
          await load(page.value)
          return 'conflict'
        }
        actionError.value = `${toErrorMessage(reason)}。可刷新列表核对后重试。`
      }
      return 'failed'
    } finally {
      if (valid(actor)) busy.value = false
    }
  }

  async function remove(id: string, note: string): Promise<ActionResult> {
    const actor = identity()
    const decisionNote = noteValue(note)
    if (!actor || busy.value) return 'failed'
    if (decisionNote === undefined) {
      actionError.value = '处理备注最多200字。'
      return 'failed'
    }
    busy.value = true
    actionError.value = notice.value = ''
    writeAbort = new AbortController()
    try {
      const receipt: RemoveReceipt = await removeContentReport(
        id,
        decisionNote,
        actor.token,
        writeAbort.signal,
      )
      if (!valid(actor)) return 'failed'
      notice.value = receipt.contentDeleted
        ? `内容已永久删除，共结案 ${receipt.affectedReports} 条举报。`
        : `内容此前已删除，本次共结案 ${receipt.affectedReports} 条举报。`
      detail.value = null
      await load(page.value)
      return valid(actor) ? 'ok' : 'failed'
    } catch (reason) {
      if (valid(actor)) {
        if (isAxiosError(reason) && reason.response?.status === 409) {
          actionError.value = '该举报已被其他管理员处理，已刷新列表。'
          detail.value = null
          await load(page.value)
          return 'conflict'
        }
        actionError.value = `${toErrorMessage(reason)}。可刷新列表核对后重试。`
      }
      return 'failed'
    } finally {
      if (valid(actor)) busy.value = false
    }
  }

  function reset() {
    context++
    listVersion++
    detailVersion++
    listAbort?.abort()
    detailAbort?.abort()
    writeAbort?.abort()
    status.value = 'PENDING'
    page.value = 1
    result.value = detail.value = null
    loading.value = inspecting.value = busy.value = false
    error.value = actionError.value = notice.value = ''
  }

  watch(
    () => [auth.user?.id, authToken.value, auth.isAdministrator],
    reset,
    { flush: 'sync' },
  )
  watch(status, () => {
    if (identity()) void load(1)
  })
  onBeforeUnmount(reset)

  return {
    status,
    page,
    result,
    detail,
    loading,
    inspecting,
    busy,
    error,
    actionError,
    notice,
    load,
    inspect,
    dismiss,
    remove,
  }
}
