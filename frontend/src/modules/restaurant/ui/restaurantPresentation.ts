/** 内部数据标识不进入业务标签展示；保留原始数据，必要来源署名由页面独立呈现。 */
export const isProvenanceTag = (tag: string) =>
  /OSM|OpenStreetMap|ODbL|©|导入|模拟|测试|联调|未经实地|待核实/i.test(tag)

/** 只使用原有标签，去掉重复分类及技术来源，不按名称猜测外卖、堂食或所谓招牌菜。 */
export function displayRestaurantTags(tags: string[], category: string): string[] {
  return [...new Set(tags)].filter((tag) => tag !== category && !isProvenanceTag(tag)).slice(0, 2)
}
