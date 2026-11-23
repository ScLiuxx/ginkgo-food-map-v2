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
    '/restaurants/12?writeReview=1',
    '/account/reviews?page=2&writeReview=1',
    '/account/contributions?status=APPROVED&page=2&pageSize=20&writeReview=1',
    '/users/42?tab=favorites&page=2&writeReview=1',
    '/map?writeReview=1',
  ])('保留站内已知目标 %s', (path) => {
    expect(loginRedirect(path)).toBe(path)
  })
  it.each([
    'https://example.com',
    '//example.com/account',
    '/account?redirect=https://example.com',
    '/account?writeReview=1&redirect=https://example.com',
    '/account?writeReview=1&writeReview=1',
    '/account?writeReview=1&page=-1',
    '/account?writeReview=1&unknown=value',
    '/account?writeReview=1&constructor=value',
    '/account?writeReview=1&__proto__=value',
    '/login?writeReview=1',
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
