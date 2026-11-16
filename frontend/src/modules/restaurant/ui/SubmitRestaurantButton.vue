<script setup lang="ts">
import { useRouter } from 'vue-router'
import {
  prepareMerchantSubmission,
  type MerchantSubmissionPlace,
} from '../model/merchantSubmissionDraft'

/** 首页与地图共用一个投稿入口；文案可按场景提供，进入表单时才生成内存草稿，不写数据库。 */
const props = defineProps<{
  keyword?: string
  place?: MerchantSubmissionPlace
}>()
const router = useRouter()
function open() {
  const draft = prepareMerchantSubmission(props.place)
  void router.push({
    path: '/restaurants/submit',
    query: {
      keyword: props.keyword?.trim() || props.place?.name || undefined,
      draft,
    },
  })
}
</script>
<template>
  <button type="button" class="submit-restaurant-button" @click="open">
    <slot>＋ 提交店铺</slot>
  </button>
</template>
<style scoped>
.submit-restaurant-button {
  display: inline-flex;
  justify-content: center;
  align-items: center;
  padding: 10px 16px;
  border: 1px solid #e6a14d;
  border-radius: 8px;
  background: #fff7e9;
  color: #b46d18;
  font: inherit;
  cursor: pointer;
}
.submit-restaurant-button:focus-visible {
  outline: 2px solid #ad6f26;
  outline-offset: 3px;
}
</style>
