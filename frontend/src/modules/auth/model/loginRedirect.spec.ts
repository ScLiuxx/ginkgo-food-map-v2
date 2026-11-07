import { describe, expect, it } from 'vitest'
import { loginRedirect } from './loginRedirect'

/** 社区登录回跳不能引入开放重定向，也不能把未确认的页面当成可用目标。 */
describe('社区登录回跳', () => {
  it.each([
    '/account',
    '/account/settings',
    '/account/favorites',
    '/messages',
    '/messages/comments',
    '/users/42',
    '/restaurants/submit',
    '/restaurants/12/correction',
    '/restaurants/12?writeReview=1',
  ])('保留站内已知目标 %s', (path) => {
    expect(loginRedirect(path)).toBe(path)
  })
  it.each([
    'https://example.com',
    '//example.com/account',
    '/account?redirect=https://example.com',
    '/account/../admin',
    '/users/0',
    '/users/42/secret',
    '/messages/private',
    ['/account'],
    undefined,
  ])('拒绝未授权或格式异常的目标 %s', (path) => {
    expect(loginRedirect(path)).toBe('/')
  })
})
