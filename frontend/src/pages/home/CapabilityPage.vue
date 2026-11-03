<script setup lang="ts">
import { useLoginStore } from '@/modules/auth'
defineProps<{ kind: 'map' | 'messages' | 'account' }>()
const auth = useLoginStore()
</script>
<template><section class="capability-page"><template v-if="kind === 'map'"><span class="symbol">⌖</span><h1>地图找店</h1><p>地图与高德地点搜索尚未开放。现在可以在餐厅目录筛选店铺，进入详情查看并复制地址。</p><RouterLink to="/">返回餐厅目录 →</RouterLink></template><template v-else-if="kind === 'messages'"><span class="symbol">◌</span><h1>消息</h1><p>回复通知和系统通知尚未开放。</p><RouterLink to="/">去发现美食 →</RouterLink></template><template v-else><span class="symbol">♙</span><h1>{{ auth.authenticated ? auth.user?.username : '我的账号' }}</h1><p>{{ auth.authenticated ? '分享用餐体验，从一条认真点评开始。' : '登录后可以添加餐厅和发布点评。' }}</p><RouterLink v-if="!auth.authenticated" to="/login">登录账号 →</RouterLink><RouterLink v-else to="/?writeReview=1">写一条点评 →</RouterLink></template></section></template>
<style scoped>.capability-page{max-width:620px;margin:60px auto;padding:32px 24px 100px;text-align:center}.symbol{font-size:64px;color:#c89528}.capability-page p{color:#81735d;line-height:1.9}.capability-page a{display:block;padding:16px;color:#9b6c16}</style>
