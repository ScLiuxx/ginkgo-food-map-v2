import { AxiosHeaders } from 'axios'
import type { InternalAxiosRequestConfig } from 'axios'
import { afterEach, describe, expect, it } from 'vitest'
import { httpClient } from '@/shared/api/httpClient'
import { setAccessToken, clearAccessToken } from '@/shared/api/authSession'
import { fetchRestaurantAlbum, fetchRestaurantAlbumImage } from './restaurantAlbumApi'

const originalAdapter = httpClient.defaults.adapter
afterEach(() => {
  httpClient.defaults.adapter = originalAdapter
  clearAccessToken()
})
describe('正式相册HTTP契约', () => {
  it('匿名读取有界游标列表和JPEG，忽略外部地址，不携带过期凭证', async () => {
    setAccessToken('expired-session')
    const sent: InternalAxiosRequestConfig[] = []
    httpClient.defaults.adapter = async (config) => {
      sent.push(config)
      return {
        config,
        status: 200,
        statusText: 'OK',
        headers: new AxiosHeaders(),
        data:
          config.responseType === 'blob'
            ? new Blob(['jpeg'], { type: 'image/jpeg' })
            : {
                data: {
                  records: [
                    { id: '801', position: 0, cover: true, url: 'https://invalid.example/private' },
                  ],
                  nextCursor: '801',
                },
              },
      }
    }
    const signal = new AbortController().signal
    const data = await fetchRestaurantAlbum('4967', null, signal)
    expect(data.records[0]?.url).toBe('/api/restaurants/4967/photos/801')
    expect(sent[0]?.params).toEqual({ limit: 20, cursor: undefined })
    await fetchRestaurantAlbum('4967', '801', signal)
    expect(sent[1]?.params.cursor).toBe('801')
    await fetchRestaurantAlbumImage('4967', '801', signal)
    expect(sent[2]?.url).toBe('/api/restaurants/4967/photos/801')
    expect(
      sent.every((entry) => entry.headers.Authorization === undefined && entry.signal === signal),
    ).toBe(true)
  })

  it('错误元数据及无效JPEG作为失败，不伪装成空相册', async () => {
    httpClient.defaults.adapter = async (config) => ({
      config,
      status: 200,
      statusText: 'OK',
      headers: new AxiosHeaders(),
      data: { data: { records: null, nextCursor: null } },
    })
    const signal = new AbortController().signal
    await expect(fetchRestaurantAlbum('1', null, signal)).rejects.toThrow('格式不正确')
    httpClient.defaults.adapter = async (config) => ({
      config,
      status: 200,
      statusText: 'OK',
      headers: new AxiosHeaders(),
      data: new Blob(['html'], { type: 'text/html' }),
    })
    await expect(fetchRestaurantAlbumImage('1', '801', signal)).rejects.toThrow('文件无效')
  })
})
