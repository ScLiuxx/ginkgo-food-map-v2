import { readonly, ref } from 'vue'

const revision = ref(0)
/** 同页发布完成后通知已挂载列表重新读取，仍保留当前分页；不保存点评或身份信息。 */
export const reviewPublicationRevision = readonly(revision)
export function notifyReviewPublished() {
  revision.value++
}
