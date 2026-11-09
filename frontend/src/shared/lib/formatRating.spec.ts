import { expect, it } from 'vitest'
import { formatRating } from './formatRating'

/** 分制换算保留零分，未知分数不伪装为零；只影响文本，不修改源数据。 */
it.each([
  [0, '0.0'],
  [7, '3.5'],
  [8.4, '4.2'],
  [10, '5.0'],
] as const)('displays API score %s on the five-point scale', (score, expected) => {
  expect(formatRating(score)).toBe(expected)
})
it.each([null, undefined, NaN, Infinity, -1, 11])('keeps invalid score %s unknown', (score) => {
  expect(formatRating(score)).toBe('—')
})
it('supports review precision without rounding to whole or half stars', () => {
  expect(formatRating(7.5, 2)).toBe('3.75')
})
