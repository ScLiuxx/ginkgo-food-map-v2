import { ApiError } from '../../shared/api/request'
import { isLoggedIn } from '../../shared/auth/session'
import {
  categoryLabel,
  deleteNotification,
  fetchAnnouncement,
  fetchNotifications,
  fetchReplyTarget,
  markAllNotificationsRead,
  markNotificationRead,
  messageErrorText,
  toNotificationView,
  type NotificationCategory,
  type NotificationView,
} from '../../modules/message/api'

type DetailBlock = { label: string; text: string }

type UnreadCounts = {
  SYSTEM: number
  COMMENTS: number
  LIKES: number
  FOLLOWERS: number
}

type MessagesData = {
  loggedIn: boolean
  category: NotificationCategory
  categories: Array<{ id: NotificationCategory; label: string; unread: number }>
  items: NotificationView[]
  latestSystem: NotificationView | null
  emptyText: string
  total: number
  totalUnread: number
  unreadCounts: UnreadCounts
  page: number
  totalPages: number
  loading: boolean
  busy: boolean
  errorMessage: string
  notice: string
  detailVisible: boolean
  detailLoading: boolean
  detailError: string
  detailTitle: string
  detailMeta: string
  detailBlocks: DetailBlock[]
  detailTargetId: string
}

type MessagesCustom = {
  load(page: number, silent?: boolean): Promise<void>
  handleOpen(item: NotificationView): Promise<void>
  switchCategory(event: WxTapEvent): void
  refresh(): void
  prevPage(): void
  nextPage(): void
  markAll(): void
  performMarkAll(): Promise<void>
  openItem(event: WxTapEvent): void
  openDetail(item: NotificationView): Promise<void>
  closeDetail(): void
  deleteItem(event: WxTapEvent): void
  performDelete(id: string): Promise<void>
  openSystemSession(): void
  openRestaurant(): void
  login(): void
  retry(): void
  syncBadge(count: number): void
  resetForGuest(): void
}

const CATEGORY_ORDER: NotificationCategory[] = [
  'ALL',
  'SYSTEM',
  'COMMENTS',
  'LIKES',
  'FOLLOWERS',
]

const EMPTY_TEXT: Record<NotificationCategory, string> = {
  ALL: '还没有消息。收到回复、获赞、关注或系统通知后会在这里显示。',
  SYSTEM: '暂无系统通知。',
  COMMENTS: '暂无评论回复提醒。',
  LIKES: '暂无获赞提醒。',
  FOLLOWERS: '暂无新增关注提醒。',
}

function emptyCounts(): UnreadCounts {
  return { SYSTEM: 0, COMMENTS: 0, LIKES: 0, FOLLOWERS: 0 }
}

function sumCounts(counts: UnreadCounts): number {
  return counts.SYSTEM + counts.COMMENTS + counts.LIKES + counts.FOLLOWERS
}

/** 请求序号放在模块作用域，页面隐藏或卸载后迟到的响应会被丢弃。 */
let requestSeq = 0

/** 分类标签上的未读数由服务端快照提供，不在前端按当前页推算。 */
function buildCategories(counts: UnreadCounts): MessagesData['categories'] {
  return CATEGORY_ORDER.map((id) => ({
    id,
    label: categoryLabel(id),
    unread: id === 'ALL' ? sumCounts(counts) : counts[id],
  }))
}

/**
 * 消息中心。
 *
 * 登录后读取分类收件箱：浏览不自动已读，单条已读/全部已读/删除都以服务端回执为准。
 * 点击消息先按类型重新读取原文（公告或回复目标），读取失败保留通知并提示内容失效。
 */
Page<MessagesData, MessagesCustom>({
  data: {
    loggedIn: false,
    category: 'ALL',
    categories: buildCategories(emptyCounts()),
    items: [],
    latestSystem: null,
    emptyText: EMPTY_TEXT.ALL,
    total: 0,
    totalUnread: 0,
    unreadCounts: emptyCounts(),
    page: 1,
    totalPages: 0,
    loading: false,
    busy: false,
    errorMessage: '',
    notice: '',
    detailVisible: false,
    detailLoading: false,
    detailError: '',
    detailTitle: '',
    detailMeta: '',
    detailBlocks: [],
    detailTargetId: '',
  },

  onShow() {
    const tabBar = this.getTabBar?.()
    if (tabBar) tabBar.setData({ selected: 2 })
    const loggedIn = isLoggedIn()
    this.setData({ loggedIn })
    if (!loggedIn) {
      this.resetForGuest()
      this.syncBadge(0)
      return
    }
    // 每次回到消息页都重新读取，避免其他端已读/删除后仍显示旧未读数。
    void this.load(this.data.page, this.data.items.length > 0)
  },

  onHide() {
    // 页面隐藏后迟到的响应不能再覆盖下一次进入时的数据。
    requestSeq++
  },

  onUnload() {
    requestSeq++
  },

  async onPullDownRefresh() {
    if (isLoggedIn()) await this.load(this.data.page, true)
    wx.stopPullDownRefresh()
  },

  onReachBottom() {
    const { page, totalPages, loading, busy } = this.data
    if (!loading && !busy && page < totalPages) void this.load(page + 1)
  },

  /** 读取指定页；silent 用于回到页面时静默刷新，不闪烁整页加载态。 */
  async load(page: number, silent = false) {
    if (!isLoggedIn()) {
      this.resetForGuest()
      return
    }
    const ticket = ++requestSeq
    if (!silent) this.setData({ loading: true, errorMessage: '' })
    try {
      const result = await fetchNotifications(this.data.category, Math.max(1, page))
      if (ticket !== requestSeq) return
      const totalUnread = sumCounts(result.unreadCounts)
      this.setData({
        items: result.records.map(toNotificationView),
        latestSystem: result.latestSystem ? toNotificationView(result.latestSystem) : null,
        categories: buildCategories(result.unreadCounts),
        unreadCounts: result.unreadCounts,
        totalUnread,
        total: result.total,
        page: result.page,
        totalPages: result.totalPages,
        emptyText: EMPTY_TEXT[this.data.category],
        loading: false,
        errorMessage: '',
      })
      this.syncBadge(totalUnread)
    } catch (reason) {
      if (ticket !== requestSeq) return
      const unauthorized = reason instanceof ApiError && reason.status === 401
      this.setData({
        loading: false,
        errorMessage: messageErrorText(reason),
        ...(unauthorized
          ? { loggedIn: false, items: [], latestSystem: null, total: 0, totalUnread: 0 }
          : {}),
      })
      if (unauthorized) {
        this.setData({ categories: buildCategories(emptyCounts()), unreadCounts: emptyCounts() })
        this.syncBadge(0)
      }
    }
  },

  switchCategory(event: WxTapEvent) {
    const id = String(event.currentTarget.dataset.id ?? '') as NotificationCategory
    if (!CATEGORY_ORDER.includes(id) || id === this.data.category) return
    this.closeDetail()
    this.setData({
      category: id,
      emptyText: EMPTY_TEXT[id],
      items: [],
      latestSystem: null,
      page: 1,
      totalPages: 0,
      total: 0,
      errorMessage: '',
      notice: '',
    })
    void this.load(1)
  },

  refresh() {
    void this.load(this.data.page)
  },

  prevPage() {
    if (this.data.busy || this.data.loading || this.data.page <= 1) return
    void this.load(this.data.page - 1)
  },

  nextPage() {
    if (this.data.busy || this.data.loading || this.data.page >= this.data.totalPages) return
    void this.load(this.data.page + 1)
  },

  markAll() {
    if (this.data.busy || this.data.loading || this.data.totalUnread === 0) return
    wx.showModal({
      title: '全部已读',
      content:
        this.data.category === 'ALL'
          ? '将全部已有消息标为已读？只改变阅读状态，不删除任何消息。'
          : `将“${categoryLabel(this.data.category)}”中的已有消息标为已读？`,
      confirmText: '标为已读',
      success: (result) => {
        if (result.confirm) void this.performMarkAll()
      },
    })
  },

  async performMarkAll() {
    if (this.data.busy) return
    this.setData({ busy: true, errorMessage: '', notice: '' })
    try {
      await markAllNotificationsRead(this.data.category)
      this.setData({ notice: '当前分类的已有消息已标为已读。' })
      await this.load(this.data.page, true)
    } catch (reason) {
      this.setData({ errorMessage: messageErrorText(reason) })
    } finally {
      this.setData({ busy: false })
    }
  },

  openItem(event: WxTapEvent) {
    if (this.data.busy) return
    const id = String(event.currentTarget.dataset.id ?? '')
    const item = this.data.items.find((row) => row.id === id)
    if (!item) return
    void this.handleOpen(item)
  },

  /** 未读消息先提交单条已读并重读计数，成功后才展示原文。 */
  async handleOpen(item: NotificationView) {
    this.setData({ busy: true, errorMessage: '', notice: '' })
    try {
      if (item.unread) {
        await markNotificationRead(item.id)
        await this.load(this.data.page, true)
      }
      await this.openDetail(item)
    } catch (reason) {
      this.setData({ errorMessage: messageErrorText(reason) })
    } finally {
      this.setData({ busy: false })
    }
  },

  async openDetail(item: NotificationView) {
    this.setData({
      detailVisible: true,
      detailTitle: item.headline,
      detailMeta: item.timeLabel,
      detailBlocks: [],
      detailError: '',
      detailLoading: false,
      detailTargetId: '',
    })
    if (item.notificationType === 'ANNOUNCEMENT') {
      const announcementId = item.announcementId
      if (!announcementId) {
        this.setData({ detailError: '公告编号无法确认。' })
        return
      }
      this.setData({ detailLoading: true })
      try {
        const announcement = await fetchAnnouncement(announcementId)
        this.setData({
          detailLoading: false,
          detailTitle: announcement.title,
          detailBlocks: [{ label: '', text: announcement.content }],
        })
      } catch (reason) {
        this.setData({ detailLoading: false, detailError: messageErrorText(reason) })
      }
      return
    }
    if (item.notificationType === 'REPORT_RESULT') {
      this.setData({
        detailBlocks: [
          { label: '处理结果', text: item.decision === 'REMOVED' ? '内容已删除' : '未认定违规' },
          {
            label: '说明',
            text: '举报不会公开处理人。原内容是否仍可访问，以对应店铺页面为准。',
          },
        ],
      })
      return
    }
    if (item.category === 'SYSTEM') {
      const blocks: DetailBlock[] = [
        { label: '店铺', text: item.restaurantName ?? '店铺资料不可用' },
        {
          label: '结果',
          text: item.decision === 'APPROVED' ? '审核通过' : '审核未通过',
        },
      ]
      if (item.rejectReason) blocks.push({ label: '驳回原因', text: item.rejectReason })
      blocks.push({
        label: '说明',
        text: '投稿详情与审核记录暂未在小程序开放，可在网页端“我的投稿 / 纠错”查看。',
      })
      this.setData({ detailBlocks: blocks })
      return
    }
    if (item.category === 'COMMENTS') {
      const reviewId = item.reviewId
      if (!reviewId) {
        this.setData({ detailError: '回复编号无法确认。' })
        return
      }
      this.setData({ detailLoading: true })
      try {
        const target = await fetchReplyTarget(item.sourceId, reviewId)
        this.setData({
          detailLoading: false,
          detailTitle: `${target.replyAuthor} 回复了你的点评`,
          detailBlocks: [
            { label: `原点评 · ${target.reviewAuthor}`, text: target.reviewBody },
            { label: '回复', text: `${target.replyAuthor}：${target.replyBody}` },
          ],
          detailTargetId: target.restaurantId ?? '',
        })
      } catch (reason) {
        this.setData({ detailLoading: false, detailError: messageErrorText(reason) })
      }
      return
    }
    if (item.category === 'LIKES') {
      this.setData({
        detailBlocks: [
          {
            label: '提醒',
            text: `${item.actorUsername ?? '用户'} 赞了你的${
              item.notificationType === 'REPLY_LIKED' ? '回复' : '点评'
            }。`,
          },
          { label: '说明', text: '获赞原文的跳转暂未在小程序开放，可在网页端查看。' },
        ],
      })
      return
    }
    this.setData({
      detailBlocks: [
        { label: '提醒', text: `${item.actorUsername ?? '用户'} 关注了你。` },
        { label: '说明', text: '个人主页与关注名单暂未在小程序开放。' },
      ],
    })
  },

  closeDetail() {
    this.setData({
      detailVisible: false,
      detailLoading: false,
      detailError: '',
      detailTitle: '',
      detailMeta: '',
      detailBlocks: [],
      detailTargetId: '',
    })
  },

  deleteItem(event: WxTapEvent) {
    if (this.data.busy) return
    const id = String(event.currentTarget.dataset.id ?? '')
    if (!this.data.items.some((row) => row.id === id)) return
    wx.showModal({
      title: '删除消息',
      content: '确定删除这条消息？原点评、回复或申请不受影响。',
      confirmText: '删除',
      success: (result) => {
        if (result.confirm) void this.performDelete(id)
      },
    })
  },

  /** 删除成功后回到第一页，重新读取真实总数、未读数和摘要。 */
  async performDelete(id: string) {
    this.setData({ busy: true, errorMessage: '', notice: '' })
    try {
      await deleteNotification(id)
      this.setData({ notice: '消息已删除。', detailVisible: false })
      await this.load(1, true)
    } catch (reason) {
      const text = messageErrorText(reason)
      this.setData({ errorMessage: `${text}删除未确认，可刷新核对或重试。` })
    } finally {
      this.setData({ busy: false })
    }
  },

  openSystemSession() {
    if (this.data.category === 'SYSTEM') return
    this.closeDetail()
    this.setData({
      category: 'SYSTEM',
      emptyText: EMPTY_TEXT.SYSTEM,
      items: [],
      latestSystem: null,
      page: 1,
      totalPages: 0,
      total: 0,
      errorMessage: '',
      notice: '',
    })
    void this.load(1)
  },

  openRestaurant() {
    const id = this.data.detailTargetId
    if (!id) return
    this.closeDetail()
    wx.navigateTo({ url: `/pages/restaurant/detail/index?id=${encodeURIComponent(id)}` })
  },

  login() {
    wx.navigateTo({
      url: `/pages/login/index?redirect=${encodeURIComponent('/pages/messages/index')}`,
    })
  },

  retry() {
    void this.load(this.data.page)
  },

  syncBadge(count: number) {
    this.getTabBar?.()?.setUnread?.(count)
  },

  resetForGuest() {
    this.closeDetail()
    this.setData({
      items: [],
      latestSystem: null,
      total: 0,
      totalUnread: 0,
      unreadCounts: emptyCounts(),
      categories: buildCategories(emptyCounts()),
      page: 1,
      totalPages: 0,
      loading: false,
      busy: false,
      errorMessage: '',
      notice: '',
    })
  },
})
