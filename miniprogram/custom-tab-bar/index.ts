type TabBarItem = {
  pagePath: string
  text: string
  icon: string
  activeIcon: string
}

type TabBarData = {
  selected: number
  list: TabBarItem[]
}

type TabBarMethods = {
  switchTab(event: WxTapEvent): void
}

/**
 * 悬浮玻璃 TabBar。
 *
 * 微信原生 TabBar 无法做半透明模糊，因此使用 custom-tab-bar；
 * 每个 Tab 页在 onShow 时回写 selected，避免从详情返回后高亮停留在旧项。
 */
Component<TabBarData, TabBarMethods>({
  data: {
    selected: 0,
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

  methods: {
    switchTab(event: WxTapEvent) {
      const index = Number(event.currentTarget.dataset.index)
      const item = this.data.list[index]
      if (!item) return
      if (index === this.data.selected) return
      this.setData({ selected: index })
      wx.switchTab({ url: `/${item.pagePath}` })
    },
  },
})
