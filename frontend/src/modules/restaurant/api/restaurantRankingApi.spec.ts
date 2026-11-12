import { AxiosHeaders } from 'axios'
import type { InternalAxiosRequestConfig } from 'axios'
import { afterEach, describe, expect, it } from 'vitest'
import { httpClient } from '@/shared/api/httpClient'
import { clearAccessToken, setAccessToken } from '@/shared/api/authSession'
import { fetchRestaurantRanking } from './restaurantRankingApi'
import type { RestaurantRanking } from './restaurantRankingApi'

const adapter = httpClient.defaults.adapter
const fixture = (): RestaurantRanking => ({
  campusCode: 'QINGSHUIHE',
  periodType: 'WEEK',
  status: 'READY',
  scoreScale: 5,
  windowStartAt: '2026-11-04T04:00:00Z',
  windowEndAt: '2026-11-11T04:00:00Z',
  generatedAt: '2026-11-11T04:00:00Z',
  items: [
    {
      rank: 1,
      restaurantId: '9007199254740993',
      name: '测试餐厅',
      coverUrl: '/api/restaurants/9007199254740993/photos/81',
      tasteScore: 4.7,
      recommendationScore: 4.2,
      participantCount: 46,
    },
  ],
})

/** 使用真实HTTP拦截器验证匿名契约，不通过真实网络或开发数据库制造榜单。 */
function respond(data: unknown, sent?: InternalAxiosRequestConfig[]) {
  httpClient.defaults.adapter = async (config) => {
    sent?.push(config)
    return {
      config,
      status: 200,
      statusText: 'OK',
      headers: new AxiosHeaders(),
      data: { data },
    }
  }
}

afterEach(() => {
  httpClient.defaults.adapter = adapter
  clearAccessToken()
})

describe('近期榜单HTTP契约', () => {
  it('只匿名请求一份榜单，保留五分制与大ID，不输出内部证据', async () => {
    setAccessToken('expired-session')
    const sent: InternalAxiosRequestConfig[] = []
    const data = fixture()
    respond(
      {
        ...data,
        rules: { c: 3.5 },
        items: [{ ...data.items[0], sortingScore: 3.9 }],
      },
      sent,
    )
    const signal = new AbortController().signal
    const result = await fetchRestaurantRanking('QINGSHUIHE', 'WEEK', signal)
    expect(sent).toHaveLength(1)
    expect(sent[0]?.url).toBe('/api/restaurant-rankings')
    expect(sent[0]?.params).toEqual({
      campusCode: 'QINGSHUIHE',
      periodType: 'WEEK',
    })
    expect(sent[0]?.headers.Authorization).toBeUndefined()
    expect(sent[0]?.signal).toBe(signal)
    expect(result).toEqual(data)
    expect(result.items[0]?.tasteScore).toBe(4.7)
    expect(result.items[0]?.restaurantId).toBe('9007199254740993')
  })

  it('准备中、成功空榜与过期不可用保持区别', async () => {
    for (const status of ['READY', 'PREPARING', 'UNAVAILABLE'] as const) {
      const data = fixture()
      data.status = status
      data.items = []
      if (status === 'PREPARING') data.windowStartAt = data.windowEndAt = data.generatedAt = null
      respond(data)
      const result = await fetchRestaurantRanking(
        'QINGSHUIHE',
        'WEEK',
        new AbortController().signal,
      )
      expect(result.status).toBe(status)
      expect(result.items).toEqual([])
    }
  })

  it('忽略外部或其他商户的封面地址', async () => {
    for (const url of [
      'https://example.test/photo.jpg',
      '//example.test/x',
      '/api/restaurants/2/photos/81',
      '/api/restaurants/9007199254740993/photos/9223372036854775808',
    ]) {
      const data = fixture()
      data.items[0]!.coverUrl = url
      respond(data)
      const result = await fetchRestaurantRanking(
        'QINGSHUIHE',
        'WEEK',
        new AbortController().signal,
      )
      expect(result.items[0]?.coverUrl).toBeNull()
    }
  })

  it('错校区、错分制、非法排名或分数不伪装成正常空榜', async () => {
    for (const mutate of [
      (data: RestaurantRanking) => {
        data.campusCode = 'SHAHE'
      },
      (data: RestaurantRanking) => {
        data.items[0]!.rank = 2
      },
      (data: RestaurantRanking) => {
        data.items[0]!.tasteScore = 9.4
      },
      (data: RestaurantRanking) => {
        data.items[0]!.restaurantId = '9223372036854775808'
      },
      (data: RestaurantRanking) => {
        data.generatedAt = 'invalid'
      },
      (data: RestaurantRanking) => {
        data.status = 'UNAVAILABLE'
      },
    ]) {
      const data = fixture()
      mutate(data)
      respond(data)
      await expect(
        fetchRestaurantRanking('QINGSHUIHE', 'WEEK', new AbortController().signal),
      ).rejects.toThrow()
    }
    respond({ ...fixture(), scoreScale: 10 })
    await expect(
      fetchRestaurantRanking('QINGSHUIHE', 'WEEK', new AbortController().signal),
    ).rejects.toThrow()
  })
})
