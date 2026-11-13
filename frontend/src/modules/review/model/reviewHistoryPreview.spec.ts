import { describe, expect, it } from 'vitest'
import { initialPreviewReviews, previewSnapshot } from './reviewHistoryPreview'

/** 证明多条记录与独立评价人数不同，且移除最新记录后能回退到上一条有效评分。 */
describe('review history preview', () => {
  it('retains three experiences but counts each account once', () => {
    const snapshot = previewSnapshot(initialPreviewReviews())
    expect(snapshot).toMatchObject({ count: 3, people: 2, score: 7.5 })
    expect(snapshot.records.find((item) => item.id === 'wang-old')?.scoringStatus).toBe('history')
    expect(snapshot.records.find((item) => item.id === 'wang-new')?.scoringStatus).toBe('current')
    expect(snapshot.groupedRecords).toHaveLength(2)
    // 单条态度继续展示；汇总只计算评分、人数与条数，不生成推荐率。
    expect(snapshot).not.toHaveProperty('recommendRate')
    expect(snapshot.records.find((item) => item.id === 'wang-new')?.recommendation).toBe(
      'NOT_RECOMMENDED',
    )
    expect(snapshot.records.find((item) => item.id === 'wang-old')?.recommendation).toBe(
      'RECOMMENDED',
    )
    expect(snapshot.groupedRecords[0]?.id).toBe('wang-new')
    expect(snapshot.groupedRecords[0]?.previousReviews.map((item) => item.id)).toEqual(['wang-old'])
    expect(snapshot.groupedRecords[0]?.previousReviews[0]?.replies[0]?.body).toBe(
      '我也准备去试试招牌！',
    )
  })
  it('reactivates the previous review when the current review is removed', () => {
    const snapshot = previewSnapshot(
      initialPreviewReviews().filter((item) => item.id !== 'wang-new'),
    )
    expect(snapshot).toMatchObject({ count: 2, people: 2, score: 8.5 })
    expect(snapshot.records.find((item) => item.id === 'wang-old')?.scoringStatus).toBe('current')
  })
  it('does not merge accounts with identical display names or mutate source records', () => {
    const records = initialPreviewReviews().map((item) => ({ ...item, username: '同名同学' }))
    expect(previewSnapshot(records).people).toBe(2)
    expect(records.every((item) => !('scoringStatus' in item))).toBe(true)
    expect(previewSnapshot([])).toMatchObject({
      count: 0,
      people: 0,
      score: null,
    })
  })
})
