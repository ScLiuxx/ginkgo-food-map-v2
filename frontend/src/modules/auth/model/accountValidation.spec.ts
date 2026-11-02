import { describe, expect, it } from 'vitest'
import { usernameError, passwordError, accountErrorField } from './accountValidation'

describe('account input rules', () => {
  it('accepts distinct username casing and the agreed length boundaries', () => {
    for (const name of ['Ka', 'ka', '同学', 'a'.repeat(24)]) expect(usernameError(name)).toBeNull()
    expect(usernameError('a')).toContain('至少需要2位')
    expect(usernameError('a'.repeat(25))).toContain('不能超过24位')
    expect(usernameError('a b')).toContain('不允许空格')
  })

  it('allows four-character passwords without requiring a character combination', () => {
    for (const password of ['1234', 'abcd', '!!!!', 'A'.repeat(32)]) {
      expect(passwordError(password)).toBeNull()
    }
    expect(passwordError('123')).toContain('至少需要4位')
    expect(passwordError('a'.repeat(33))).toContain('不能超过32位')
    for (const password of ['中文密码', 'abcd ', 'abc\n'])
      expect(passwordError(password)).not.toBeNull()
  })

  it('places explicit server errors under their fields without inventing username existence', () => {
    expect(accountErrorField('该用户名已被使用')).toBe('username')
    expect(accountErrorField('验证码错误或已失效')).toBe('code')
    expect(accountErrorField('用户名或密码错误')).toBeNull()
    expect(accountErrorField('网络请求失败')).toBeNull()
  })
})
