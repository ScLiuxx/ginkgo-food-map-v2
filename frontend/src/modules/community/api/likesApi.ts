import { httpClient } from '@/shared/api/httpClient'
import type { ApiResponse } from '@/shared/api/api.types'
export type LikeType = 'REVIEW' | 'REPLY'
export interface LikeState {
  targetId: string
  count: number
  liked: boolean
  available: boolean
}
const validId = (id: unknown): id is string =>
  typeof id === 'string' && /^[1-9]\d{0,18}$/.test(id) && BigInt(id) <= 9223372036854775807n
const invalid = () => new Error('点赞资料无法确认，请重新加载。')
function state(row: LikeState, id: string): LikeState {
  if (
    !row ||
    row.targetId !== id ||
    !Number.isSafeInteger(row.count) ||
    row.count < 0 ||
    typeof row.liked !== 'boolean' ||
    typeof row.available !== 'boolean' ||
    (!row.available && (row.count !== 0 || row.liked))
  )
    throw invalid()
  return { targetId: id, count: row.count, liked: row.liked, available: row.available }
}
interface Pending {
  type: LikeType
  id: string
  user: string | null
  token: string | null
  signal: AbortSignal
  resolve: (state: LikeState) => void
  reject: (error: unknown) => void
}
let pending: Pending[] = []
/** 同一次挂载的内容按身份和类型合批，每批最多50条；不缓存跨账号的本人点赞状态。 */
export function fetchLike(
  type: LikeType,
  id: string,
  user: string | null,
  token: string | null,
  signal: AbortSignal,
): Promise<LikeState> {
  if (!validId(id)) return Promise.reject(invalid())
  return new Promise((resolve, reject) => {
    pending.push({ type, id, user, token, signal, resolve, reject })
    if (pending.length === 1) void Promise.resolve().then(flush)
  })
}
async function flush() {
  const queue = pending
  pending = []
  while (queue.length) {
    const first = queue.shift()!,
      group = [first]
    for (let i = queue.length - 1; i >= 0 && group.length < 50; i--)
      if (
        queue[i]!.type === first.type &&
        queue[i]!.token === first.token &&
        queue[i]!.user === first.user
      )
        group.push(queue.splice(i, 1)[0]!)
    const ids = [...new Set(group.map((x) => x.id))]
    try {
      const response = await httpClient.get<
        ApiResponse<{ userId: string | null; type: LikeType; records: LikeState[] }>
      >('/api/content-likes', {
        params: { type: first.type, ids: ids.join(',') },
        headers: { Authorization: first.token ? `Bearer ${first.token}` : undefined },
      })
      const data = response.data.data
      if (
        !response.data.success ||
        !data ||
        data.userId !== first.user ||
        data.type !== first.type ||
        !Array.isArray(data.records) ||
        data.records.length !== ids.length ||
        new Set(data.records.map((x) => x.targetId)).size !== ids.length
      )
        throw invalid()
      const map = new Map(
        ids.map((id) => [
          id,
          state(
            data.records.find((x) => x.targetId === id)!,
            id,
          ),
        ]),
      )
      for (const task of group) {
        if (task.signal.aborted) task.reject(new Error('请求已取消'))
        else task.resolve(map.get(task.id)!)
      }
    } catch (error) {
      group.forEach((x) => x.reject(error))
    }
  }
}
/** PUT/DELETE是明确目标状态，不用toggle接口；结果只使用服务端权威计数。 */
export async function setLike(
  type: LikeType,
  id: string,
  active: boolean,
  token: string,
  signal: AbortSignal,
): Promise<LikeState> {
  if (!validId(id)) throw invalid()
  const path = `/api/${type === 'REVIEW' ? 'reviews' : 'review-replies'}/${id}/like`
  const response = active
    ? await httpClient.put<ApiResponse<LikeState>>(path, null, {
        signal,
        headers: { Authorization: `Bearer ${token}` },
      })
    : await httpClient.delete<ApiResponse<LikeState>>(path, {
        signal,
        headers: { Authorization: `Bearer ${token}` },
      })
  if (!response.data.success) throw invalid()
  return state(response.data.data, id)
}
