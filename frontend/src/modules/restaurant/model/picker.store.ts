import { computed, reactive, ref } from 'vue'
import { defineStore } from 'pinia'
import { fetchPickerCandidates, type PickerConditions } from '../api/restaurantPickerApi'
import { toErrorMessage } from '@/shared/lib/toErrorMessage'
import type { RestaurantReadModel } from './restaurant.types'

export type PickerDraft = {
  range: '' | PickerConditions['range']
  distanceMeters: 0 | PickerConditions['distanceMeters']
  budget: '' | PickerConditions['budget']
  minRating: '' | PickerConditions['minRating']
  category: string
  diningMode: '' | PickerConditions['diningMode']
}

const emptyDraft = (): PickerDraft => ({
  range: '',
  distanceMeters: 0,
  budget: '',
  minRating: '',
  category: '',
  diningMode: '',
})

/** 一轮选店只保留会话内存；详情往返可恢复，主动关闭清空，绝不写入首页catalog或账号。 */
export const useRestaurantPicker = defineStore('restaurant-picker', () => {
  const draft = reactive(emptyDraft())
  const confirmed = ref<PickerConditions | null>(null)
  const phase = ref<'conditions' | 'results'>('conditions')
  const history = ref<RestaurantReadModel[]>([])
  const cursor = ref(-1)
  const queue = ref<RestaurantReadModel[]>([])
  const reservedIds = ref<string[]>([])
  const matchedCount = ref(0)
  const remainingCount = ref(0)
  const exhausted = ref(false)
  const unavailable = ref(false)
  const loading = ref(false)
  const error = ref('')
  const pendingReturn = ref(false)
  const originRoute = ref('/')
  const editing = ref(false)
  const retryAction = ref<'next' | 'history'>('next')
  let retryCursor = -1
  let generation = 0
  let controller: AbortController | undefined
  const completedCount = computed(() => Object.values(draft).filter(Boolean).length)
  const complete = computed(() => completedCount.value === Object.keys(draft).length)
  const current = computed(() => history.value[cursor.value] ?? null)

  /** 取消和代次一起使用，避免关窗/改条件后已到达网络层的旧结果重新填回。 */
  function cancel() {
    generation++
    controller?.abort()
    loading.value = false
  }
  function resetRound() {
    cancel()
    history.value = []
    queue.value = []
    reservedIds.value = []
    cursor.value = -1
    matchedCount.value = 0
    remainingCount.value = 0
    exhausted.value = false
    unavailable.value = false
    error.value = ''
  }
  function reset() {
    resetRound()
    Object.assign(draft, emptyDraft())
    confirmed.value = null
    phase.value = 'conditions'
    pendingReturn.value = false
    editing.value = false
    originRoute.value = '/'
  }

  /** 只有用户确认完整条件才启动；位置来自本次选择，校区请求不携带共享定位值。 */
  async function start(location?: [number, number]) {
    if (!complete.value || loading.value) return
    if (draft.range === 'USER' && !location) {
      error.value = '请先完成定位，或选择清水河、沙河附近。'
      return
    }
    confirmed.value = {
      range: draft.range as PickerConditions['range'],
      distanceMeters: draft.distanceMeters as PickerConditions['distanceMeters'],
      budget: draft.budget as PickerConditions['budget'],
      minRating: draft.minRating as PickerConditions['minRating'],
      category: draft.category,
      diningMode: draft.diningMode as PickerConditions['diningMode'],
      ...(draft.range === 'USER' && location
        ? { longitude: location[0], latitude: location[1] }
        : {}),
    }
    resetRound()
    phase.value = 'results'
    editing.value = false
    await next()
  }

  /** 当前批先复核，再按服务端给出的随机顺序展示；失效项仍留在排除集合，防止本轮重新出现。 */
  async function next() {
    if (loading.value || !confirmed.value) return
    if (cursor.value < history.value.length - 1) {
      await visit(cursor.value + 1)
      return
    }
    const ticket = ++generation
    controller = new AbortController()
    loading.value = true
    error.value = ''
    retryAction.value = 'next'
    try {
      if (queue.value.length) {
        const checked = await fetchPickerCandidates(
          confirmed.value,
          reservedIds.value,
          queue.value.map((r) => r.id),
          controller.signal,
        )
        if (ticket !== generation) return
        queue.value = [...checked.records]
        matchedCount.value = checked.matchedCount
        remainingCount.value = checked.remainingCount
      }
      if (!queue.value.length) {
        const batch = await fetchPickerCandidates(
          confirmed.value,
          reservedIds.value,
          [],
          controller.signal,
        )
        if (ticket !== generation) return
        // 排除项和重复项属于协议故障，不能作为“候选看完”静默吞掉。
        const ids = batch.records.map((r) => r.id)
        if (new Set(ids).size !== ids.length || ids.some((id) => reservedIds.value.includes(id))) {
          throw new Error('候选响应重复，请重试。')
        }
        queue.value = [...batch.records]
        reservedIds.value = [...reservedIds.value, ...ids]
        matchedCount.value = batch.matchedCount
        remainingCount.value = batch.remainingCount
      }
      const selected = queue.value.shift()
      if (!selected) {
        exhausted.value = true
        return
      }
      history.value.push(selected)
      cursor.value = history.value.length - 1
      unavailable.value = false
      exhausted.value = queue.value.length === 0 && remainingCount.value === 0
    } catch (reason) {
      if (ticket === generation) error.value = toErrorMessage(reason)
    } finally {
      if (ticket === generation) loading.value = false
    }
  }

  /** 回看/详情返回只复核指定店铺，不消耗新候选；失效历史仍可辨认，但不再提供前往操作。 */
  async function visit(index: number) {
    const item = history.value[index]
    if (!item || !confirmed.value || loading.value) return
    const ticket = ++generation
    controller = new AbortController()
    loading.value = true
    error.value = ''
    retryAction.value = 'history'
    retryCursor = index
    try {
      const checked = await fetchPickerCandidates(
        confirmed.value,
        reservedIds.value,
        [item.id],
        controller.signal,
      )
      if (ticket !== generation) return
      const fresh = checked.records.find((r) => r.id === item.id)
      if (fresh) history.value[index] = fresh
      cursor.value = index
      unavailable.value = !fresh
    } catch (reason) {
      if (ticket === generation) error.value = toErrorMessage(reason)
    } finally {
      if (ticket === generation) loading.value = false
    }
  }
  async function retry() {
    if (retryAction.value === 'history') await visit(retryCursor)
    else await next()
  }
  async function restart() {
    if (loading.value || !confirmed.value) return
    resetRound()
    await next()
  }
  function edit() {
    cancel()
    error.value = ''
    editing.value = true
    phase.value = 'conditions'
  }
  async function cancelEdit() {
    if (!confirmed.value) return
    Object.assign(draft, {
      range: confirmed.value.range,
      distanceMeters: confirmed.value.distanceMeters,
      budget: confirmed.value.budget,
      minRating: confirmed.value.minRating,
      category: confirmed.value.category,
      diningMode: confirmed.value.diningMode,
    })
    editing.value = false
    phase.value = 'results'
    // 首次抽取可能被修改操作中断；恢复原条件后继续查询，不能把尚未返回误判为空候选。
    if (!history.value.length && !exhausted.value) await next()
  }
  return {
    draft,
    confirmed,
    phase,
    history,
    cursor,
    queue,
    reservedIds,
    matchedCount,
    remainingCount,
    exhausted,
    unavailable,
    loading,
    error,
    pendingReturn,
    originRoute,
    editing,
    completedCount,
    complete,
    current,
    cancel,
    reset,
    start,
    next,
    visit,
    retry,
    restart,
    edit,
    cancelEdit,
  }
})
