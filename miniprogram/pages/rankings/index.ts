import { toErrorMessage } from '../../shared/api/request'
import {
  fetchRestaurantRankings,
  type RankingCampus,
  type RankingPeriod,
  type RankingView,
} from '../../modules/restaurant/rankings'

type RankingsData = {
  campusLabels: string[]
  campusValues: RankingCampus[]
  campusIndex: number
  periodLabels: string[]
  periodValues: RankingPeriod[]
  periodIndex: number
  loading: boolean
  errorMessage: string
  ranking: RankingView | null
}

type RankingsCustom = {
  requestSequence: number
  load(): Promise<void>
  onCampusChange(event: WxPickerEvent): void
  onPeriodChange(event: WxPickerEvent): void
  openRestaurant(event: WxTapEvent): void
  retry(): void
}

/**
 * 近期推荐榜。
 *
 * 榜单由服务端快照生成，客户端只负责切换校区与周期；
 * PREPARING 与 UNAVAILABLE 是明确状态，不能当作空榜或本地重排。
 */
Page<RankingsData, RankingsCustom>({
  data: {
    campusLabels: ['清水河校区', '沙河校区'],
    campusValues: ['QINGSHUIHE', 'SHAHE'],
    campusIndex: 0,
    periodLabels: ['近 7 天', '近 30 天'],
    periodValues: ['WEEK', 'MONTH'],
    periodIndex: 0,
    loading: true,
    errorMessage: '',
    ranking: null,
  },

  requestSequence: 0,

  onLoad() {
    void this.load()
  },

  async load() {
    const sequence = ++this.requestSequence
    this.setData({ loading: true, errorMessage: '' })
    try {
      const ranking = await fetchRestaurantRankings(
        this.data.campusValues[this.data.campusIndex],
        this.data.periodValues[this.data.periodIndex],
      )
      if (sequence !== this.requestSequence) return
      this.setData({ loading: false, ranking })
    } catch (reason) {
      if (sequence !== this.requestSequence) return
      this.setData({ loading: false, errorMessage: toErrorMessage(reason), ranking: null })
    }
  },

  onCampusChange(event: WxPickerEvent) {
    const index = Number(event.detail.value)
    if (!Number.isInteger(index) || index < 0 || index >= this.data.campusValues.length) return
    if (index === this.data.campusIndex) return
    this.setData({ campusIndex: index })
    void this.load()
  },

  onPeriodChange(event: WxPickerEvent) {
    const index = Number(event.detail.value)
    if (!Number.isInteger(index) || index < 0 || index >= this.data.periodValues.length) return
    if (index === this.data.periodIndex) return
    this.setData({ periodIndex: index })
    void this.load()
  },

  openRestaurant(event: WxTapEvent) {
    const id = String(event.currentTarget.dataset.id ?? '')
    if (!id) return
    wx.navigateTo({ url: `/pages/restaurant/detail/index?id=${encodeURIComponent(id)}` })
  },

  retry() {
    void this.load()
  },
})
