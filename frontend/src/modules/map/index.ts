/** 地图模块公开页面入口，餐厅数据通过restaurant公开能力读取。 */
export { default as MapWorkspace } from './ui/MapWorkspace.vue'
// 详情与地图共用目的地校验，不在详情内另造起点或请求路线规划。
export { navigationUrl } from './model/map'
// 商户纠错通过公开组件选择高德门店，不直接依赖地图模块内部SDK。
export { default as MerchantPlacePicker } from './ui/MerchantPlacePicker.vue'
export type { AMapPlace } from './sdk/places'
