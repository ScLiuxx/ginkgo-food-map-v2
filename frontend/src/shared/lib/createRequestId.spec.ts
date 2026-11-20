import { webcrypto } from 'node:crypto'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createRequestId } from './createRequestId'

afterEach(() => vi.unstubAllGlobals())

describe('HTTP-compatible request UUIDs', () => {
  it('prefers native UUID generation and preserves its receiver', () => {
    const native = {
      randomUUID: vi.fn(function (this: unknown) {
        expect(this).toBe(native)
        return '12345678-1234-4234-8234-123456789abc'
      }),
      getRandomValues: vi.fn(),
    }
    vi.stubGlobal('crypto', native)
    expect(createRequestId()).toBe('12345678-1234-4234-8234-123456789abc')
    expect(native.getRandomValues).not.toHaveBeenCalled()
  })

  it('creates distinct valid version 4 UUIDs when randomUUID is unavailable', () => {
    vi.stubGlobal('crypto', { getRandomValues: webcrypto.getRandomValues.bind(webcrypto) })
    const first = createRequestId(),
      second = createRequestId()
    for (const id of [first, second]) {
      expect(id).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/)
    }
    expect(first).not.toBe(second)
  })

  it.each([
    [0, '00000000-0000-4000-8000-000000000000'],
    [255, 'ffffffff-ffff-4fff-bfff-ffffffffffff'],
  ])('sets version and variant bits for random bytes filled with %i', (byte, expected) => {
    vi.stubGlobal('crypto', {
      getRandomValues: (bytes: Uint8Array) => bytes.fill(byte as number),
    })
    expect(createRequestId()).toBe(expected)
  })

  it('fails explicitly if secure randomness is unavailable', () => {
    vi.stubGlobal('crypto', undefined)
    expect(() => createRequestId()).toThrow('当前浏览器无法生成请求编号')
  })
})
