import { computed, onScopeDispose, ref, watch, nextTick } from 'vue'
import { getAccessToken } from '@/shared/api/authSession'
import { toErrorMessage } from '@/shared/lib/toErrorMessage'
import axios from 'axios'
import {
  fetchRequestDetail,
  fetchRequestPage,
  fetchRequestPhoto,
  approveRequest,
  rejectRequest,
  fetchReviewCategories,
  type ReviewInput,
  type ReviewCategory,
  type RequestDetail,
  type RequestItem,
  type RequestStatus,
} from '../api/restaurantRequestsApi'

export const requestStatusNames: Record<RequestStatus, string> = {
  PENDING: '待审核',
  APPROVED: '已通过',
  REJECTED: '已拒绝',
}
/** 服务端UTC时间按浏览器本地时间展示，缺失或非法值不显示成有效日期。 */
export function formatRequestTime(value: string) {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '时间未知' : date.toLocaleString('zh-CN', { hour12: false })
}

/** 真实审核工作区；查询隔离迟到响应，写入只在服务端确认后推进队列。 */
export function useRestaurantRequests() {
  const token = getAccessToken()
  const draft = ref<Record<string, unknown>>({})
  const categories = ref<ReviewCategory[]>([]),
    categoryError = ref('')
  const submitting = ref(false),
    continuous = ref(true),
    decisionError = ref(''),
    notice = ref('')
  const rejectOpen = ref(false)
  let disposed = false
  const categoryAbort = new AbortController()
  /** 分类失败只影响分类选择，可单独重试；其他字段和驳回仍可处理。 */
  async function loadCategories() {
    categoryError.value = ''
    try {
      categories.value = await fetchReviewCategories(categoryAbort.signal)
    } catch (error) {
      if (!disposed) categoryError.value = toErrorMessage(error)
    }
  }
  void loadCategories()
  const tab = ref<RequestStatus>('PENDING'),
    query = ref(''),
    page = ref(1)
  const items = ref<RequestItem[]>([]),
    selectedId = ref<string | null>(null),
    detail = ref<RequestDetail | null>(null)
  const total = ref(0),
    totalPages = ref(0)
  const counts = ref<Record<RequestStatus, number>>({ PENDING: 0, APPROVED: 0, REJECTED: 0 })
  const loadingList = ref(false),
    loadingDetail = ref(false),
    listError = ref(''),
    detailError = ref('')
  const photos = ref<{ position: number; src: string; loading: boolean; error: string }[]>([])
  const selected = computed(
    () =>
      detail.value?.application ?? items.value.find((item) => item.id === selectedId.value) ?? null,
  )
  let listVersion = 0,
    detailVersion = 0
  let listAbort: AbortController | undefined, detailAbort: AbortController | undefined
  let timer: ReturnType<typeof globalThis.setTimeout> | undefined
  let internalPageChange = false

  function clearDetail() {
    detailVersion++
    detailAbort?.abort()
    detail.value = null
    detailError.value = ''
    loadingDetail.value = false
    photos.value.forEach((photo) => {
      if (photo.src) URL.revokeObjectURL(photo.src)
    })
    photos.value = []
    draft.value = {}
    decisionError.value = ''
    rejectOpen.value = false
  }

  /** 图片失败可单独重试，切换申请后不得恢复旧Blob或串用新账号凭证。 */
  async function loadPhoto(position: number) {
    const photo = photos.value.find((entry) => entry.position === position)
    if (!photo || !token || !selectedId.value || !detailAbort) return
    const version = detailVersion,
      id = selectedId.value,
      signal = detailAbort.signal
    photo.loading = true
    photo.error = ''
    try {
      const blob = await fetchRequestPhoto(id, position, token, signal)
      if (version !== detailVersion || signal.aborted) return
      if (photo.src) URL.revokeObjectURL(photo.src)
      photo.src = URL.createObjectURL(blob)
    } catch (error) {
      if (version === detailVersion && !signal.aborted) photo.error = toErrorMessage(error)
    } finally {
      if (version === detailVersion) photo.loading = false
    }
  }

  async function selectRequest(id: string, afterDecision = false) {
    if (submitting.value && !afterDecision) return
    clearDetail()
    selectedId.value = id
    loadingDetail.value = true
    detailAbort = new AbortController()
    const version = detailVersion,
      signal = detailAbort.signal
    try {
      if (!token) throw new Error('请重新登录')
      const result = await fetchRequestDetail(id, token, signal)
      if (version !== detailVersion || signal.aborted) return
      detail.value = result
      draft.value = structuredClone(result.approved ?? result.proposed)
      if (result.application.status === 'PENDING' && draft.value.restaurantType == null)
        draft.value.restaurantType = result.current?.restaurantType ?? 'REGULAR'
      // 旧待审申请可能仍带食堂分类，只修订本次草稿，不改写原投稿或已完成的历史快照。
      if (result.application.status === 'PENDING' && draft.value.restaurantType === 'CANTEEN') {
        draft.value.categoryCode = null
        draft.value.categoryName = null
      }
      photos.value = result.photos.map((photo) => ({
        position: photo.position,
        src: '',
        loading: true,
        error: '',
      }))
      for (const photo of photos.value) void loadPhoto(photo.position)
    } catch (error) {
      if (version === detailVersion && !signal.aborted) detailError.value = toErrorMessage(error)
    } finally {
      if (version === detailVersion) loadingDetail.value = false
    }
  }

  async function loadList(version: number, autoSelect = true) {
    listAbort = new AbortController()
    const signal = listAbort.signal
    try {
      if (!token) throw new Error('请重新登录')
      const result = await fetchRequestPage(
        { status: tab.value, keyword: query.value.trim(), page: page.value, pageSize: 20 },
        token,
        signal,
      )
      if (version !== listVersion || signal.aborted) return
      if (page.value > Math.max(1, result.totalPages)) {
        // 队列缩页时沿用本次是否自动选中的策略，不触发普通翻页清空审核回执。
        internalPageChange = true
        page.value = Math.max(1, result.totalPages)
        await nextTick()
        if (version === listVersion && !signal.aborted) await loadList(version, autoSelect)
        return
      }
      items.value = result.records
      total.value = result.total
      totalPages.value = result.totalPages
      counts.value = result.counts
      if (autoSelect && items.value[0]) await selectRequest(items.value[0].id, true)
    } catch (error) {
      if (version === listVersion && !signal.aborted) listError.value = toErrorMessage(error)
    } finally {
      if (version === listVersion) loadingList.value = false
    }
  }

  /** 搜索立即清除旧详情，再延迟请求；不会把失败或未完成查询当成没有申请。 */
  function reload(delay = 0) {
    if (submitting.value) return
    const version = ++listVersion
    listAbort?.abort()
    globalThis.clearTimeout(timer)
    clearDetail()
    selectedId.value = null
    items.value = []
    total.value = 0
    totalPages.value = 0
    counts.value = { PENDING: 0, APPROVED: 0, REJECTED: 0 }
    listError.value = ''
    loadingList.value = true
    timer = globalThis.setTimeout(() => {
      void loadList(version)
    }, delay)
  }
  watch(
    [tab, query, page],
    ([status, keyword], old) => {
      if (internalPageChange) {
        internalPageChange = false
        return
      }
      if ((status !== old[0] || keyword !== old[1]) && page.value !== 1) {
        page.value = 1
        return
      }
      reload(old[1] !== undefined && keyword !== old[1] ? 250 : 0)
    },
    { immediate: true },
  )
  onScopeDispose(() => {
    disposed = true
    categoryAbort.abort()
    listVersion++
    listAbort?.abort()
    globalThis.clearTimeout(timer)
    clearDetail()
  })

  /** 统一展示全部可编辑业务字段；保留零值/false，不能把未知状态误显示为支持。 */
  const comparison = computed(() => {
    if (!detail.value) return []
    const before =
        detail.value.application.status === 'APPROVED' ? detail.value.before : detail.value.current,
      after = draft.value
    const text = (value: unknown) => (value == null || value === '' ? '未填写' : String(value))
    const yes = (value: unknown) => (value === true ? '支持' : value === false ? '不支持' : '未知')
    const fields: [string, (data: Record<string, unknown>) => string][] = [
      ['商户名称', (data) => text(data.name)],
      [
        '美食品类',
        (data) =>
          data.restaurantType === 'CANTEEN' && data.categoryCode == null
            ? '不适用（食堂）'
            : text(data.categoryName || data.categoryCode),
      ],
      [
        '商户类型',
        (data) =>
          data.restaurantType === 'CANTEEN'
            ? '食堂'
            : data.restaurantType === 'REGULAR'
              ? '普通餐厅'
              : '未知',
      ],
      ['详细地址', (data) => text(data.address)],
      [
        '位置 GCJ-02',
        (data) =>
          data.latitude == null || data.longitude == null
            ? '待定位'
            : `${data.latitude}, ${data.longitude}`,
      ],
      ['人均消费', (data) => (data.pricePerPerson == null ? '未知' : `¥${data.pricePerPerson}`)],
      [
        '价格口径',
        (data) =>
          ({ UNKNOWN: '未知', EXACT: '填报金额', ESTIMATE: '估值' })[String(data.priceKind)] ??
          '未知',
      ],
      ['堂食情况', (data) => yes(data.supportsDineIn)],
      ['外卖情况', (data) => yes(data.supportsDelivery)],
      [
        '经营状态',
        (data) =>
          data.businessStatus === 'CLOSED'
            ? '停业'
            : data.businessStatus === 'OPERATING'
              ? '正常经营'
              : '未知',
      ],
      ['位置确认', (data) => (data.locationConfirmed === true ? '已确认' : '未确认')],
      [
        '商户标签',
        (data) => (Array.isArray(data.tags) && data.tags.length ? data.tags.join(' · ') : '无'),
      ],
    ]
    const keys = [
      'name',
      'categoryCode',
      'restaurantType',
      'address',
      'position',
      'pricePerPerson',
      'priceKind',
      'supportsDineIn',
      'supportsDelivery',
      'businessStatus',
      'locationConfirmed',
      'tags',
    ]
    return fields.map(([label, format], index) => ({
      key: keys[index] ?? '',
      label,
      before: before ? format(before) : '—',
      after: format(after),
      changed: !before || format(before) !== format(after),
    }))
  })
  /** 所有编辑都落在当前申请草稿；不从格式化后的金额、未知文案或分类名称构造请求。 */
  function editField(key: string, value: unknown) {
    if (submitting.value || detail.value?.application.status !== 'PENDING') return
    if (key === 'categoryCode' && draft.value.restaurantType === 'CANTEEN') return
    draft.value[key] = value
    if (key === 'restaurantType') {
      draft.value.categoryCode = null
      draft.value.categoryName = null
    }
    if (key === 'categoryCode')
      draft.value.categoryName =
        categories.value.find((entry) => entry.code === value)?.name ?? value
    if (key === 'priceKind' && value === 'UNKNOWN') draft.value.pricePerPerson = null
    if (key === 'pricePerPerson' && value != null && draft.value.priceKind === 'UNKNOWN')
      draft.value.priceKind = 'EXACT'
    decisionError.value = ''
  }

  function input(): ReviewInput {
    const data = draft.value
    if (typeof data.name !== 'string' || !data.name.trim()) throw new Error('请填写商户名称')
    if (data.restaurantType !== 'CANTEEN' && !data.categoryCode) throw new Error('请选择美食品类')
    if (
      typeof data.latitude !== 'number' ||
      !Number.isFinite(data.latitude) ||
      typeof data.longitude !== 'number' ||
      !Number.isFinite(data.longitude)
    )
      throw new Error('请填写有效的经纬度')
    if (data.locationConfirmed !== true) throw new Error('请确认商户位置')
    if (
      data.priceKind !== 'UNKNOWN' &&
      (typeof data.pricePerPerson !== 'number' ||
        !Number.isInteger(data.pricePerPerson) ||
        data.pricePerPerson < 1 ||
        data.pricePerPerson > 5000)
    )
      throw new Error('人均消费须为1至5000的整数')
    return {
      restaurantType: data.restaurantType === 'CANTEEN' ? 'CANTEEN' : 'REGULAR',
      supportsDineIn: typeof data.supportsDineIn === 'boolean' ? data.supportsDineIn : null,
      supportsDelivery: typeof data.supportsDelivery === 'boolean' ? data.supportsDelivery : null,
      name: data.name,
      categoryCode: data.restaurantType === 'CANTEEN' ? null : String(data.categoryCode),
      address: data.address == null ? null : String(data.address),
      latitude: data.latitude,
      longitude: data.longitude,
      locationConfirmed: true,
      priceKind: data.priceKind as ReviewInput['priceKind'],
      pricePerPerson: data.priceKind === 'UNKNOWN' ? null : Number(data.pricePerPerson),
      businessStatus: data.businessStatus as ReviewInput['businessStatus'],
      tags: Array.isArray(data.tags) ? [...data.tags] : [],
    }
  }

  /** 完成写入后单独刷新队列；刷新失败不能被描述为审核失败，更不能重发已确认的写请求。 */
  async function decide(kind: 'approve' | 'reject', reason = '') {
    if (submitting.value || !token || detail.value?.application.status !== 'PENDING') return
    const id = detail.value.application.id
    let payload: ReviewInput | undefined
    decisionError.value = ''
    notice.value = ''
    try {
      if (kind === 'approve') payload = input()
    } catch (error) {
      decisionError.value = toErrorMessage(error)
      return
    }
    submitting.value = true
    listAbort?.abort()
    globalThis.clearTimeout(timer)
    ++listVersion
    try {
      const receipt =
        kind === 'approve'
          ? await approveRequest(id, payload!, token)
          : await rejectRequest(id, reason, token)
      if (disposed) return
      // 立即反映服务器已确认的终态，即使后续列表读取失败也不能再次操作此申请。
      detail.value!.application.status = receipt.status
      detail.value!.application.restaurantId = receipt.restaurantId
      detail.value!.reviewedBy = receipt.reviewedBy
      detail.value!.reviewedAt = receipt.reviewedAt
      detail.value!.rejectReason = receipt.rejectReason
      rejectOpen.value = false
      notice.value =
        kind === 'approve'
          ? `申请 #${id} 已审核通过并更新；${receipt.publishedPhotos?.length ? `已发布 ${receipt.publishedPhotos.length} 张商户相册图片。` : '本次未新增相册图片。'}`
          : `申请 #${id} 已驳回，正式商户资料未改变。`
      // 审核前快照和规范化后的通过值只认服务端；读取失败保留已确认终态，不重发写入。
      loadingDetail.value = true
      try {
        const confirmed = await fetchRequestDetail(id, token, detailAbort!.signal)
        if (disposed) return
        if (confirmed.application.status !== receipt.status)
          throw new Error('审核详情状态尚未同步，请刷新详情')
        detail.value = confirmed
        draft.value = structuredClone(confirmed.approved ?? confirmed.proposed)
      } catch (error) {
        if (disposed) return
        detailError.value = '审核已成功，详情刷新失败：' + toErrorMessage(error)
      } finally {
        loadingDetail.value = false
      }
      loadingList.value = true
      listError.value = ''
      const version = ++listVersion
      // 不勾选连续审核则保留当前处理回执；勾选后才根据刷新后的队列进入下一条。
      await loadList(version, continuous.value && tab.value === 'PENDING')
      if (continuous.value && tab.value === 'PENDING' && !listError.value && !items.value.length) {
        clearDetail()
        selectedId.value = null
      }
    } catch (error) {
      if (disposed) return
      decisionError.value = toErrorMessage(error)
      if (axios.isAxiosError(error) && error.response?.status === 409) {
        await selectRequest(id, true)
        decisionError.value = '申请状态已变化，请核对最新详情后再操作。'
      }
    } finally {
      submitting.value = false
    }
  }
  return {
    draft,
    categories,
    categoryError,
    loadCategories,
    submitting,
    continuous,
    decisionError,
    notice,
    rejectOpen,
    editField,
    decide,
    tab,
    query,
    page,
    items,
    selectedId,
    selected,
    detail,
    total,
    totalPages,
    counts,
    loadingList,
    loadingDetail,
    listError,
    detailError,
    photos,
    comparison,
    reload,
    selectRequest,
    loadPhoto,
  }
}
