import { useRoute, useRouter } from 'vue-router'

/** URL只保存分页、筛选等展示选项，不存正文、图片、令牌或表单草稿。 */
export function useListRouteState(enabled: () => boolean) {
  const route = useRoute(),
    router = useRouter()
  function read(key: string) {
    return enabled() && typeof route.query[key] === 'string'
      ? (route.query[key] as string)
      : undefined
  }
  function page() {
    const value = read('page')
    return value && /^[1-9]\d{0,5}$/.test(value) ? Number(value) : 1
  }
  /** 替换当前历史条目的选项，不为每次翻页新增返回步骤，保留来源与申请定位参数。 */
  async function save(values: Record<string, string | number | undefined>) {
    if (!enabled()) return
    const query = { ...route.query }
    for (const [key, value] of Object.entries(values)) {
      if (value === undefined) delete query[key]
      else query[key] = String(value)
    }
    if (router.resolve({ path: route.path, query, hash: route.hash }).fullPath !== route.fullPath)
      await router.replace({ query, hash: route.hash })
  }
  return { read, page, save }
}
