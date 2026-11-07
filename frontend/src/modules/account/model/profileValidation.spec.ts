import { describe, expect, it } from 'vitest'
import { bioValidationError, normalizeBio } from './profileValidation'

/** 与后端规则相同的边界样例，正常食物表达不应被QQ/微信关键词误伤。 */
describe('简介基础检查', () => {
  it('按Unicode码点计数，保留emoji连接符，首尾空白与换行规范化', () => {
    expect(bioValidationError('🥟'.repeat(100))).toBe('')
    expect(bioValidationError('🥟'.repeat(101))).toContain('100字')
    expect(normalizeBio('\u00a0爱吃１号面馆👨‍👩‍👧\r\n也喜欢QQ糖\u00a0')).toBe(
      '爱吃１号面馆👨‍👩‍👧\n也喜欢QQ糖',
    )
    expect(normalizeBio('\uFEFF简介\uFEFF')).toBe('简介')
    expect(bioValidationError('坏字符\ud800')).toContain('不支持')
    expect(bioValidationError('简介\u0000内容')).toContain('不支持')
  })
  it.each([
    '',
    '  \n\t ',
    '喜欢米线、QQ糖，也支持微信支付。',
    '9.5分的小店，Lv.2食友',
    '周末想吃第123456号套餐',
    '纯文本<script>alert(1)</script>',
  ])('接受正常简介 %s', (value) => {
    expect(bioValidationError(value)).toBe('')
  })
  it.each([
    '微信：food_123',
    'WeChat:food123',
    'wxid_abcdef12345',
    'QQ号：12345678',
    'Q Q:12 34 56 78',
    '138-0013-8000',
    '＋８６ １３８００１３８０００',
    'wx\u200b:food123',
    'https://example.com',
    'www.example.cn',
    'ｗｗｗ．ｅｘａｍｐｌｅ．ｃｎ',
    'example.com/path',
    '测试.中国',
    '192.168.1.2',
    '电话：028-12345678',
  ])('拦截常见联系方式或网址 %s', (value) => {
    expect(bioValidationError(value)).toContain('不能包含')
  })
})
