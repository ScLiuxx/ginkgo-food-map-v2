<script setup lang="ts">
import PublicProfile from '@/modules/account/ui/PublicProfile.vue'
/** 独立登录可见主页，不复用本人资料或设置入口。 */
defineProps<{ publicUserId: string; section?: string }>()
</script>
<template><PublicProfile :public-user-id="publicUserId" :section="section" /></template>
