<script setup lang="ts">
import { AccountCenter } from '@/modules/account'
// 本人页面与公开主页共用身份布局；具体公开资料仍须未来接口单独授权返回。
defineProps<{ section?: string; publicUserId?: string }>()
</script>
<template><AccountCenter :section="section" :public-user-id="publicUserId" /></template>
