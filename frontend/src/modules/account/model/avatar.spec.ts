import { afterEach, expect, it, vi } from 'vitest'
import { prepareAvatar } from './avatar'

afterEach(() => vi.restoreAllMocks())

it('拒绝非图片和超大原图，不进入解码', async () => {
  await expect(
    prepareAvatar(new File(['svg'], 'x.svg', { type: 'image/svg+xml' })),
  ).rejects.toThrow('JPEG')
  const file = new File(['x'], 'x.jpg', { type: 'image/jpeg' })
  Object.defineProperty(file, 'size', { value: 21 * 1024 * 1024 })
  await expect(prepareAvatar(file)).rejects.toThrow('20MB')
})

it('横图居中裁剪到512方形，填充白底并释放位图', async () => {
  const close = vi.fn(),
    draw = vi.fn(),
    fill = vi.fn()
  vi.stubGlobal('createImageBitmap', vi.fn().mockResolvedValue({ width: 1600, height: 800, close }))
  const canvas = document.createElement('canvas')
  vi.spyOn(canvas, 'getContext').mockReturnValue({
    fillStyle: '',
    drawImage: draw,
    fillRect: fill,
  } as unknown as CanvasRenderingContext2D)
  vi.spyOn(canvas, 'toBlob').mockImplementation((done) =>
    done(new Blob(['jpg'], { type: 'image/jpeg' })),
  )
  vi.spyOn(document, 'createElement').mockReturnValue(canvas)
  const result = await prepareAvatar(new File(['png'], 'test.png', { type: 'image/png' }))
  expect([canvas.width, canvas.height]).toEqual([512, 512])
  expect(draw).toHaveBeenCalledWith(expect.anything(), 400, 0, 800, 800, 0, 0, 512, 512)
  expect(fill).toHaveBeenCalledWith(0, 0, 512, 512)
  expect(result.type).toBe('image/jpeg')
  expect(close).toHaveBeenCalledOnce()
  vi.unstubAllGlobals()
})
