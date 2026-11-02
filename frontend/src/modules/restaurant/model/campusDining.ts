/** 校园餐饮的前端展示契约。当前服务端尚未提供 dining 字段；不从名称推断校区、楼层或窗口关系。
 * 窗口仍须拥有独立餐厅记录 ID，现有点评接口按该 ID 读写，禁止借用父食堂 ID。
 */
export interface DiningDish {
  id: string
  name: string
  imageUrl?: string | null
  price?: number | null
  description?: string | null
}

/** 稳定 ID 不以名称或楼层拼接；关闭窗口仍展示原记录与历史点评入口。 */
export interface DiningWindow {
  id: string
  canteenId: string
  name: string
  floor: string | null
  category: string | null
  description?: string | null
  imageUrl?: string | null
  score: number | null
  reviewCount: number
  price: number | null
  closed: boolean
  specialties: string[]
  /** 列表仅展示确有资料的菜品与价格；旧接口缺省时不补造。 */
  dishes?: DiningDish[]
}

export interface CampusDining {
  kind: 'restaurant' | 'canteen' | 'window'
  campus: string | null
  /** 只有窗口设置父食堂；评分和点评不得读取此 ID。 */
  parent: { id: string; name: string } | null
  floor: string | null
  description: string | null
  /** null 表示未接入窗口数据，[] 才表示已接入且没有收录。必须是该食堂完整窗口集合。 */
  windows: DiningWindow[] | null
  dishes: DiningDish[]
}

/** 老接口仅能从现有正式分类识别食堂，不推断服务方式或生成窗口。 */
export function diningDetails(restaurant: {
  category: string
  restaurantType?: 'REGULAR' | 'CANTEEN'
  dining?: CampusDining
}): CampusDining {
  return (
    restaurant.dining ?? {
      kind: restaurant.restaurantType
        ? restaurant.restaurantType === 'CANTEEN'
          ? 'canteen'
          : 'restaurant'
        : restaurant.category === '食堂'
          ? 'canteen'
          : 'restaurant',
      campus: null,
      parent: null,
      floor: null,
      description: null,
      windows: null,
      dishes: [],
    }
  )
}

/** 防止脏关联将其他食堂的窗口混入；不修改原始数据，不计算或继承父食堂评分。 */
export function canteenWindows(canteenId: string, windows: DiningWindow[] | null): DiningWindow[] {
  const seen = new Set<string>()
  return (windows ?? []).filter((window) => {
    if (
      !window.id ||
      window.canteenId !== canteenId ||
      window.id === canteenId ||
      seen.has(window.id)
    )
      return false
    seen.add(window.id)
    return true
  })
}

/** 至少有一条点评且评分合法才算已评；真实0分仍有效，不用真值判断跳过。 */
export function windowHasRating(window: DiningWindow): boolean {
  return (
    window.reviewCount > 0 &&
    window.score !== null &&
    Number.isFinite(window.score) &&
    window.score >= 0 &&
    window.score <= 10
  )
}

/** 前端食堂汇总：完整关联集合中，营业且有评分的窗口等权平均；不混入食堂历史总分。 */
export function canteenRating(canteenId: string, windows: DiningWindow[] | null) {
  const rated = canteenWindows(canteenId, windows).filter(
    (window) => !window.closed && windowHasRating(window),
  )
  return {
    score: rated.length
      ? rated.reduce((sum, window) => sum + window.score!, 0) / rated.length
      : null,
    count: rated.length,
  }
}

/** 常见中文楼层与数字楼层按高度排列，未知命名保留自然字符串排序。 */
export function compareFloors(a: string, b: string): number {
  const value = (name: string) => {
    const match = name.match(/^([负-]?)([一二两三四五六七八九十\d]+)|^[bB](\d+)/)
    if (!match) return null
    if (match[3]) return -Number(match[3])
    const chinese: Record<string, number> = {
      一: 1,
      二: 2,
      两: 2,
      三: 3,
      四: 4,
      五: 5,
      六: 6,
      七: 7,
      八: 8,
      九: 9,
      十: 10,
    }
    const raw = match[2]!
    const number = /^\d+$/.test(raw)
      ? Number(raw)
      : raw.includes('十')
        ? (chinese[raw.split('十')[0]!] || 1) * 10 + (chinese[raw.split('十')[1]!] || 0)
        : chinese[raw]
    return number === undefined ? null : match[1] ? -number : number
  }
  const first = value(a),
    second = value(b)
  if (first === null && second !== null) return 1
  if (first !== null && second === null) return -1
  return first !== null && second !== null
    ? first - second
    : a.localeCompare(b, 'zh-CN', { numeric: true })
}
