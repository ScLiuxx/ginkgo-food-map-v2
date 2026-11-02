import { describe, expect, it } from 'vitest'
import { detailPhotos } from './detailPhotos'
import type { ReviewItem } from './review'

describe('detail photo provenance', () => {
  it('retains author and review IDs, marks examples and does not rewrite reviews', () => {
    const base: ReviewItem = {
      id: '1',
      username: '甲',
      createdAt: '2026-10-31',
      scores: { taste: 8, value: 8, service: 8, ambience: 8 },
      recommended: true,
      body: '真实输入的原始正文',
      photos: [{ id: 'p1', url: '/photo/1' }],
      canDelete: false,
      replies: [],
    }
    const example = {
      ...base,
      id: '2',
      username: '乙',
      body: '联调模拟：示意照片',
      photos: [{ id: 'p2', url: '/photo/2' }],
    }
    const photos = detailPhotos([base, example])
    expect(photos).toEqual([
      { id: 'p1', url: '/photo/1', username: '甲', reviewId: '1', example: false },
      { id: 'p2', url: '/photo/2', username: '乙', reviewId: '2', example: true },
    ])
    expect(example.body).toBe('联调模拟：示意照片')
    expect(base.photos[0]).not.toHaveProperty('example')
    expect(detailPhotos([])).toEqual([])
  })
})
