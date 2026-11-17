import { describe, expect, it } from 'vitest'
import { notificationTime } from './notificationTime'

/** 以本地日历构造数据，验证跨午夜、月末与跨年的实际展示边界。 */
describe('消息简短时间', () => {
  const now = new Date(2026, 10, 17, 0, 5)
  it('同日显示今天，前一天即使只有十分钟差也显示昨天', () => {
    expect(notificationTime(new Date(2026, 10, 17, 0, 1).toISOString(), now)).toBe('今天 00:01')
    expect(notificationTime(new Date(2026, 10, 16, 23, 55).toISOString(), now)).toBe('昨天 23:55')
  })
  it('昨天按日历处理月末与年末，其余旧消息保留日期及跨年年份', () => {
    expect(
      notificationTime(new Date(2026, 9, 31, 20, 3).toISOString(), new Date(2026, 10, 1)),
    ).toBe('昨天 20:03')
    expect(
      notificationTime(new Date(2025, 11, 31, 20, 3).toISOString(), new Date(2026, 0, 1)),
    ).toBe('昨天 20:03')
    expect(notificationTime(new Date(2026, 10, 15, 8, 9).toISOString(), now)).toBe('11月15日 08:09')
    expect(notificationTime(new Date(2025, 10, 15, 8, 9).toISOString(), now)).toBe(
      '2025年11月15日 08:09',
    )
  })
})
