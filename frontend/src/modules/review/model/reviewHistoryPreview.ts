import type { ReviewItem, Scores } from './review'
import { overall } from './review'

interface PreviewReview extends ReviewItem {
  authorId: string
}
const scores = (n: number): Scores => ({ taste: n, value: n, service: n, ambience: n })
/** 独立预览的数据，不进入餐厅API或数据库；账号关联使用ID，绝不按昵称去重。 */
export function initialPreviewReviews(): PreviewReview[] {
  return [
    {
      id: 'wang-new',
      authorId: 'wang',
      username: '小王',
      createdAt: '2026-10-29T12:30:00+08:00',
      scores: scores(6),
      recommendation: 'NOT_RECOMMENDED',
      body: '第二次来吃。今天米饭有些偏硬，出餐也慢了些，和上次的体验不一样，希望之后能稳定一点。',
      photos: [],
      canDelete: true,
      replies: [],
    },
    {
      id: 'li-first',
      authorId: 'li',
      username: '小李',
      createdAt: '2026-10-28T18:00:00+08:00',
      scores: scores(9),
      recommendation: 'RECOMMENDED',
      body: '第一次来，点了招牌铁板饭，分量足，口味也合适。对我来说是愿意再来的店。',
      photos: [],
      canDelete: false,
      replies: [],
    },
    {
      id: 'wang-old',
      authorId: 'wang',
      username: '小王',
      createdAt: '2026-10-20T12:10:00+08:00',
      scores: scores(8),
      recommendation: 'RECOMMENDED',
      body: '第一次来，鸡肉很嫩，米饭口感不错，整体挺满意的，下次想尝尝其他口味。',
      photos: [],
      canDelete: true,
      replies: [
        {
          id: 'reply-1',
          username: '小李',
          body: '我也准备去试试招牌！',
          createdAt: '2026-10-21T09:00:00+08:00',
        },
      ],
    },
  ]
}
/** 只供全量示例计算。真实接口须在服务端确定最新记录，不能使用分页记录计算全店分数。 */
export function previewSnapshot(reviews: PreviewReview[]) {
  const ordered = [...reviews].sort(
    (a, b) => Date.parse(b.createdAt) - Date.parse(a.createdAt) || b.id.localeCompare(a.id),
  )
  const authors = new Set<string>()
  const records = ordered.map((item) => {
    const current = !authors.has(item.authorId)
    authors.add(item.authorId)
    return { ...item, scoringStatus: current ? ('current' as const) : ('history' as const) }
  })
  const active = records.filter((item) => item.scoringStatus === 'current')
  return {
    records,
    groupedRecords: active.map((item) => ({
      ...item,
      previousReviews: records.filter(
        (previous) => previous.authorId === item.authorId && previous.id !== item.id,
      ),
    })),
    count: records.length,
    people: active.length,
    score: active.length
      ? active.reduce((sum, item) => sum + overall(item.scores), 0) / active.length
      : null,
  }
}
