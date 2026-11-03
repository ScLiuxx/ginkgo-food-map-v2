import { AxiosError, AxiosHeaders } from 'axios'
import type { AxiosResponse, InternalAxiosRequestConfig } from 'axios'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { clearAccessToken, getAccessToken, setAccessToken } from './authSession'
import { httpClient } from './httpClient'

const originalAdapter = httpClient.defaults.adapter

/** 不发出网络请求，通过 Axios adapter 验证真实拦截器与竞态处理。 */
function response(config: InternalAxiosRequestConfig, status = 200): AxiosResponse {
  return { config, status, statusText: '', data: {}, headers: new AxiosHeaders() }
}

describe('authenticated HTTP client', () => {
  beforeEach(() => {
    clearAccessToken()
    setAccessToken('current-session')
  })
  afterEach(() => {
    httpClient.defaults.adapter = originalAdapter
    clearAccessToken()
  })

  it('adds Bearer authentication only to protected requests', async () => {
    const sent: InternalAxiosRequestConfig[] = []
    httpClient.defaults.adapter = async (config) => {
      sent.push(config)
      return response(config)
    }
    await httpClient.get('/auth/me')
    await httpClient.post('/auth/login', {})
    await httpClient.post('/auth/register', {})
    await httpClient.post('/auth/password-reset', {})
    await httpClient.post('/auth/email/code/password-reset', {})
    await httpClient.get('/api/public/status')
    await httpClient.get('/api/restaurant-categories')
    await httpClient.get('/api/restaurants')
    await httpClient.get('/api/restaurants/1')
    await httpClient.get('/api/restaurants/1/photos')
    await httpClient.get('/api/restaurants/1/photos/801')
    await httpClient.get('/api/restaurants/1/reviews')
    await httpClient.get('/api/restaurants/1/reviews/authors/22/history')
    await httpClient.get('/api/reviews/10/replies')
    expect(sent[0]?.headers.Authorization).toBe('Bearer current-session')
    for (const config of sent.slice(1)) expect(config.headers.Authorization).toBeUndefined()
  })

  it('keeps authentication on restaurant management and all writes', async () => {
    const sent: InternalAxiosRequestConfig[] = []
    httpClient.defaults.adapter = async (config) => {
      sent.push(config)
      return response(config)
    }
    await httpClient.get('/api/restaurants/1/management')
    await httpClient.get('/api/restaurants/1/reviews/me/context')
    await httpClient.post('/api/restaurants', {})
    await httpClient.put('/api/restaurants/1', {})
    await httpClient.delete('/api/restaurants/1')
    await httpClient.post('/api/restaurants/1/restore')
    await httpClient.post('/api/reviews/10/replies', {})
    for (const config of sent) expect(config.headers.Authorization).toBe('Bearer current-session')
  })

  it('clears a rejected session but keeps it on temporary service failures', async () => {
    httpClient.defaults.adapter = async (config) => {
      throw new AxiosError(
        'unavailable',
        'ERR_BAD_RESPONSE',
        config,
        undefined,
        response(config, 503),
      )
    }
    await expect(httpClient.get('/auth/me')).rejects.toThrow()
    expect(getAccessToken()).toBe('current-session')
    httpClient.defaults.adapter = async (config) => {
      throw new AxiosError('expired', 'ERR_BAD_REQUEST', config, undefined, response(config, 401))
    }
    await expect(httpClient.get('/auth/me')).rejects.toThrow()
    expect(getAccessToken()).toBeNull()
    expect(localStorage.getItem('ginkgo.access-token')).toBeNull()
  })

  it('does not clear a new session when an old request returns 401', async () => {
    httpClient.defaults.adapter = async (config) => {
      setAccessToken('new-session')
      throw new AxiosError(
        'old session expired',
        'ERR_BAD_REQUEST',
        config,
        undefined,
        response(config, 401),
      )
    }
    await expect(httpClient.get('/auth/me')).rejects.toThrow()
    expect(getAccessToken()).toBe('new-session')
  })

  it('does not clear an existing session when a public login fails', async () => {
    httpClient.defaults.adapter = async (config) => {
      throw new AxiosError(
        'wrong password',
        'ERR_BAD_REQUEST',
        config,
        undefined,
        response(config, 401),
      )
    }
    await expect(httpClient.post('/auth/login', {})).rejects.toThrow()
    expect(getAccessToken()).toBe('current-session')
  })
})
