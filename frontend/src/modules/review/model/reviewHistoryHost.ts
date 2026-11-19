import type { InjectionKey, Ref, ShallowRef } from 'vue'

/** 电脑历史详情借用外层模态窗口；移动端继续使用原来的全屏详情。 */
export interface ReviewHistoryHost {
  desktop: Ref<boolean>
  storeName: Readonly<Ref<string | undefined>>
  target: Ref<HTMLElement | undefined>
  activeId: Ref<string | undefined>
  back: ShallowRef<(() => void) | undefined>
  close: () => void
}
export const reviewHistoryHostKey: InjectionKey<ReviewHistoryHost> = Symbol('review-history-host')
