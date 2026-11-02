<script setup lang="ts">
import { RestaurantCatalog } from '@/modules/restaurant'
import { ReviewComposer } from '@/modules/review'
// 页面负责组合找店与写评入口，不把点评列表扩展为独立首页内容流。
</script>
<template>
  <div class="discovery-page">
    <h1 class="screen-reader-title">泥电美食地图</h1>
    <RestaurantCatalog />
    <div class="home-review"><ReviewComposer /></div>
    <footer class="discovery-footer">
      泥电美食地图 · 校园美食评价社区 <RouterLink to="/scoring-guide">评分说明</RouterLink>
    </footer>
  </div>
</template>
<style scoped src="./home.css"></style>
