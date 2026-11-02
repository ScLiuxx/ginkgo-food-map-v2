import { AxiosHeaders } from 'axios'
import type { InternalAxiosRequestConfig } from 'axios'
import { afterEach, describe, expect, it } from 'vitest'
import { httpClient } from '@/shared/api/httpClient'
import { clearAccessToken, setAccessToken } from '@/shared/api/authSession'
import {
  fetchRestaurant,
  fetchRestaurantCategories,
  fetchRestaurants,
  toRestaurantModel,
  createRestaurant,
  updateRestaurant,
  deleteRestaurant,
  fetchRestaurantManagement,
} from './restaurantApi'
import { restaurantDraft, restaurantInput } from '../model/restaurantForm'
import { categoryFixtures, responseFixture } from './restaurant.test-data'

const originalAdapter = httpClient.defaults.adapter
afterEach(() => {
  httpClient.defaults.adapter = originalAdapter
  clearAccessToken()
})

/** 使用真实Axios配置验证查询白名单、路径和模型转换，防止前后端协议漂移。 */
describe('restaurant HTTP API', () => {
  it('filters all legacy pages before pagination and excludes unknown delivery data', async () => {
    const requests: InternalAxiosRequestConfig[] = []
    const signal = new AbortController().signal
    httpClient.defaults.adapter = async (config) => {
      requests.push(config)
      const page = config.params.page
      const records =
        page === 1
          ? [{ ...responseFixture, id: '1', categoryName: '火锅', supportsDelivery: true }]
          : [
              { ...responseFixture, id: '2', categoryName: '食堂', supportsDelivery: true },
              { ...responseFixture, id: '3', categoryName: '食堂' },
            ]
      return {
        config,
        status: 200,
        statusText: '',
        headers: new AxiosHeaders(),
        data: { data: { records, page, pageSize: 100, total: 3, totalPages: 2 } },
      }
    }
    const result = await fetchRestaurants(
      {
        keyword: '',
        category: '',
        sort: 'score',
        hideClosed: false,
        maxPrice: null,
        maxDistanceMeters: null,
        restaurantType: 'CANTEEN',
        diningMode: 'delivery',
      },
      1,
      1,
      signal,
    )
    expect(result.total).toBe(1)
    expect(result.records.map((record) => record.id)).toEqual(['2'])
    expect(requests.map((request) => request.params.page)).toEqual([1, 2])
    expect(requests.every((request) => request.signal === signal)).toBe(true)
    for (const request of requests) {
      expect(request.params).not.toHaveProperty('restaurantType')
      expect(request.params).not.toHaveProperty('diningMode')
    }
  })
  it('keeps authenticated CRUD on the captured session and never adds a version or DELETE body', async () => {
    const requests: InternalAxiosRequestConfig[] = []
    setAccessToken('newer-account')
    httpClient.defaults.adapter = async (config) => {
      requests.push(config)
      return {
        config,
        status: 200,
        statusText: '',
        headers: new AxiosHeaders(),
        data: { data: { restaurant: responseFixture, deleted: config.method === 'delete' } },
      }
    }
    const input = restaurantInput({
      ...restaurantDraft(),
      name: '测试',
      categoryCode: 'HOT_POT',
      latitude: '31',
      longitude: '104',
      locationConfirmed: true,
    })
    await fetchRestaurantManagement(responseFixture.id, 'captured-session')
    await createRestaurant(input, 'captured-session')
    await updateRestaurant(responseFixture.id, input, 'captured-session')
    await deleteRestaurant(responseFixture.id, 'captured-session')
    expect(requests.map((request) => request.method)).toEqual(['get', 'post', 'put', 'delete'])
    expect(
      requests.every((request) => request.headers.Authorization === 'Bearer captured-session'),
    ).toBe(true)
    expect(requests[0]?.url).toBe(`/api/restaurants/${responseFixture.id}/management`)
    expect(JSON.parse(requests[2]!.data)).toEqual(input)
    expect(JSON.parse(requests[2]!.data)).not.toHaveProperty('rowVersion')
    expect(requests[3]?.data).toBeUndefined()
    expect(requests[3]?.params).toBeUndefined()
  })
  it('preserves string IDs, unknown values, backend distances and enum meanings', () => {
    const value = toRestaurantModel(responseFixture)
    expect(value.id).toBe('9007199254740993')
    expect(value.price).toBeNull()
    expect(value.score).toBeNull()
    expect(
      toRestaurantModel({ ...responseFixture, score: 0, participantCount: 2, reviewCount: 7 }),
    ).toMatchObject({ score: 0, participantCount: 2, reviewCount: 7 })
    expect(value.distanceKm).toBeNull()
    expect(value.closed).toBe(false)
    expect(
      toRestaurantModel({
        ...responseFixture,
        pricePerPerson: 65,
        priceKind: 'ESTIMATE',
        businessStatus: 'CLOSED',
        sourceType: 'USER',
        distanceKm: 2.34,
      }),
    ).toMatchObject({
      price: 65,
      priceKind: 'estimate',
      closed: true,
      source: 'USER',
      distanceKm: 2.34,
    })
  })

  it('sends only current filters and pages without legacy rating conditions', async () => {
    let requested!: InternalAxiosRequestConfig
    httpClient.defaults.adapter = async (config) => {
      requested = config
      return {
        config,
        status: 200,
        statusText: '',
        headers: new AxiosHeaders(),
        data: {
          success: true,
          data: { page: 3, pageSize: 8, total: 19, totalPages: 3, records: [responseFixture] },
        },
      }
    }
    const result = await fetchRestaurants(
      {
        keyword: '火锅%_',
        category: 'HOT_POT',
        sort: 'price',
        hideClosed: true,
        maxPrice: 30,
        maxDistanceMeters: 1000,
      },
      3,
      8,
    )
    const url = new URL(httpClient.getUri(requested), 'http://test.local')
    expect(url.searchParams.has('tiers')).toBe(false)
    expect(url.searchParams.has('view')).toBe(false)
    expect(url.searchParams.has('tiers[]')).toBe(false)
    expect(url.searchParams.get('keyword')).toBe('火锅%_')
    expect(url.searchParams.get('page')).toBe('3')
    expect(url.searchParams.get('hideClosed')).toBe('true')
    expect(url.searchParams.get('maxPrice')).toBe('30')
    expect(url.searchParams.get('maxDistanceMeters')).toBe('1000')
    expect(result.total).toBe(19)
    expect(result.records[0]?.id).toBe(responseFixture.id)
  })

  it('loads categories and a direct detail through their independent endpoints', async () => {
    const paths: string[] = []
    httpClient.defaults.adapter = async (config) => {
      paths.push(config.url!)
      return {
        config,
        status: 200,
        statusText: '',
        headers: new AxiosHeaders(),
        data: {
          data: config.url === '/api/restaurant-categories' ? categoryFixtures : responseFixture,
        },
      }
    }
    expect(await fetchRestaurantCategories()).toEqual(categoryFixtures)
    expect((await fetchRestaurant(responseFixture.id)).id).toBe(responseFixture.id)
    expect(paths).toEqual(['/api/restaurant-categories', `/api/restaurants/${responseFixture.id}`])
  })
})
