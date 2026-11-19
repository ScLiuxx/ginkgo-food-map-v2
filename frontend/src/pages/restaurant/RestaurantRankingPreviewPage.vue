<script setup lang="ts">
import AppLink from '@/shared/ui/AppLink.vue'
import { RestaurantRankings, type RankingLoader } from '@/modules/restaurant'
import noodles from './ranking-preview/noodles.jpg'
import grill from './ranking-preview/grill.jpg'
import dish from './ranking-preview/dish.jpg'

/** 只由DEV路由加载；示例不落数据库、不拦截真实API，也不进入正式榜单页面。 */
const loadPreview: RankingLoader = async (campusCode, periodType) => {
  const names =
    campusCode === 'SHAHE'
      ? ['街角牛肉面', '树下炭火烤肉', '小院家常菜', '老街手工水饺', '一碗鲜米线']
      : ['阿婆牛肉面（西门店）', '巷子里炭火烤肉', '小院家常菜', '清河手工水饺', '一碗鲜米线']
  const photos = [noodles, grill, dish, null, noodles]
  const scores = [4.7, 4.6, 4.8, 4.5, 4.4]
  const counts = [46, 33, 9, 18, 12]
  return {
    campusCode,
    periodType,
    status: 'READY',
    scoreScale: 5,
    windowStartAt: periodType === 'WEEK' ? '2026-11-04T04:00:00Z' : '2026-10-12T04:00:00Z',
    windowEndAt: '2026-11-11T04:00:00Z',
    generatedAt: '2026-11-11T04:00:00Z',
    items: names.map((name, index) => ({
      rank: index + 1,
      restaurantId: String(index + 1),
      name,
      coverUrl: photos[index] ?? null,
      tasteScore: scores[index]!,
      recommendationScore: [4.6, 4.5, 4.3, 4.2, 4.1][index]!,
      participantCount: counts[index]! * (periodType === 'MONTH' ? 3 : 1),
    })),
  }
}
</script>

<template>
  <aside class="ranking-preview-note">
    界面预览 · 店名、配图及评分为示例
    <AppLink to="/restaurant-rankings">查看真实榜单 →</AppLink>
  </aside>
  <RestaurantRankings :loader="loadPreview" />
</template>

<style scoped>
.ranking-preview-note {
  max-width: 900px;
  width: calc(100% - 36px);
  margin: 14px auto 0;
  color: #8b939d;
  font-size: 11px;
  line-height: 1.8;
}
.ranking-preview-note a {
  color: #a76d29;
  margin-left: 12px;
  text-decoration: none;
}
</style>
