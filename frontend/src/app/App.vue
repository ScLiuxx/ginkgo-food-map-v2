<script setup lang="ts">
import DefaultLayout from './layouts/DefaultLayout.vue'
import { onMounted, watch } from 'vue'
import { useLoginStore } from '@/modules/auth'
import { authToken } from '@/shared/api/authSession'

import { useRealtimeUnread } from '@/modules/message/model/useRealtimeUnread'

const auth = useLoginStore()
useRealtimeUnread()
// 启动时向服务端校验凭证，不能仅凭本地存储就展示为已登录。
onMounted(() => {
  void auth.restore()
})

// 其他标签页登录或更换账号后重新核对身份，不能沿用旧账号的导航信息。
watch(authToken, (token) => {
  if (token && !auth.authenticated) void auth.restore()
})
</script>

<template>
  <DefaultLayout />
</template>
