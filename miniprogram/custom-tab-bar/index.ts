import { fetchUnreadCount } from '../modules/message/api'
import { isLoggedIn } from '../shared/auth/session'

type TabBarItem = {
  pagePath: string
  text: string
  icon: string
  activeIcon: string
}

type TabBarData = {
  selected: number
  unreadCount: number
  list: TabBarItem[]
}

type TabBarMethods = {
  switchTab(event: WxTapEvent): void
  setUnread(count: number): void
  refreshUnread(): void
}

/** 所有 Tab 页共享未读序号，较旧的红点响应不能覆盖较新的查询结果。 */
let unreadSeq = 0

/**
 * 悬浮玻璃 TabBar。
 *
 * 微信原生 TabBar 无法做半透明模糊，因此使用 custom-tab-bar；
 * 每个 Tab 页在 onShow 时回写 selected，避免从详情返回后高亮停留在旧项。
 * 消息红点由消息页按收件箱快照直接回写，其他 Tab 页进入时再轻量查询未读数。
 */
Component<TabBarData, TabBarMethods>({
  data: {
    selected: 0,
    unreadCount: 0,
    list: [
      {
        pagePath: 'pages/home/index',
        text: '找店',
        icon: '/assets/tabbar/home.png',
        activeIcon: '/assets/tabbar/home-active.png',
      },
      {
        pagePath: 'pages/map/index',
        text: '地图',
        icon: '/assets/tabbar/map.png',
        activeIcon: '/assets/tabbar/map-active.png',
      },
      {
        pagePath: 'pages/messages/index',
        text: '消息',
        icon: '/assets/tabbar/messages.png',
        activeIcon: '/assets/tabbar/messages-active.png',
      },
      {
        pagePath: 'pages/account/index',
        text: '我的',
        icon: '/assets/tabbar/account.png',
        activeIcon: '/assets/tabbar/account-active.png',
      },
    ],
  },

  lifetimes: {
    attached() {
      this.refreshUnread()
    },
  },

  methods: {
    switchTab(event: WxTapEvent) {
      const index = Number(event.currentTarget.dataset.index)
      const item = this.data.list[index]
      if (!item) return
      if (index === this.data.selected) return
      this.setData({ selected: index })
      wx.switchTab({ url: `/${item.pagePath}` })
    },

    setUnread(count: number) {
      const safe = Number.isFinite(count) && count > 0 ? Math.min(Math.floor(count), 999) : 0
      this.setData({ unreadCount: safe })
    },

    refreshUnread() {
      if (!isLoggedIn()) {
        this.setData({ unreadCount: 0 })
        return
      }
      const ticket = ++unreadSeq
      fetchUnreadCount()
        .then((count) => {
          if (ticket === unreadSeq) this.setUnread(count)
        })
        .catch(() => {
          // 未读数查询失败时保留旧红点，页面内容仍按自身请求展示错误。
        })
    },
  },
})
