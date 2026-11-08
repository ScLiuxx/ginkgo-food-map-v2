import { describe, expect, it, vi } from 'vitest'
import { readNotificationEvents } from './useRealtimeUnread'
describe('消息流解析', () => {
  it('跨网络分片识别CRLF和多事件，忽略注释及无关事件', async () => {
    const chunks = [
      'event: notifications-ch',
      'anged\r\ndata: {}\r\n\r',
      '\n:heartbeat\n\nevent: sync\ndata: {}\n\nevent: other\n\n',
    ]
    const response = new Response(
      new ReadableStream({
        start(c) {
          chunks.forEach((s) => c.enqueue(new TextEncoder().encode(s)))
          c.close()
        },
      }),
      { headers: { 'Content-Type': 'text/event-stream' } },
    )
    const changed = vi.fn()
    await readNotificationEvents(response, new AbortController().signal, changed)
    expect(changed).toHaveBeenCalledTimes(2)
  })
  it('拒绝非事件流响应', async () => {
    await expect(
      readNotificationEvents(new Response('{}'), new AbortController().signal, vi.fn()),
    ).rejects.toThrow()
  })
})
