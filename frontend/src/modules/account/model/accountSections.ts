/** 仅列出已确认入口；私密列表没有接口时保持待开放，不用空数组冒充真实空数据。 */
export const accountSections = [
  { id: 'reviews', title: '我的点评', icon: 'review', detail: '按店铺整理你的用餐体验' },
  { id: 'favorites', title: '我的收藏', icon: 'star', detail: '把想去的店铺留在这里' },
  { id: 'contributions', title: '我的投稿 / 纠错', icon: 'edit', detail: '一起完善校园美食地图' },
  { id: 'settings', title: '账号设置', icon: 'settings', detail: '资料、隐私与账号安全' },
] as const

export const accountSectionTitles: Record<string, string> = {
  ...Object.fromEntries(accountSections.map((item) => [item.id, item.title])),
  growth: '成长等级',
  following: '我的关注',
  followers: '我的粉丝',
}
