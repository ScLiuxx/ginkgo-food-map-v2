/** 评价模块的页面组件入口；页面不依赖内部传输适配器和草稿实现。 */
export { default as ReviewComposer } from './ui/ReviewComposer.vue'
export { default as ReviewPanel } from './ui/ReviewPanel.vue'
export { default as ScoreRadar } from './ui/ScoreRadar.vue'
export { default as ReviewHistoryPreview } from './ui/ReviewHistoryPreview.vue'
export { textReviewGateway } from './api/reviewApi'
export { reviewGatewayKey } from './model/review'

export { useRestaurantReviewPreview } from './model/useRestaurantReviewPreview'
export { default as DetailPhotoGallery } from './ui/DetailPhotoGallery.vue'
export type { DetailPhotoUpdate } from './model/detailPhotos'
