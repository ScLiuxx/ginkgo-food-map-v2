<script setup lang="ts">
import AppLink from '@/shared/ui/AppLink.vue'
import { computed } from 'vue'
import { PasswordResetForm } from '@/modules/auth'
import { authToken } from '@/shared/api/authSession'

// 已登录用户可取消重置回到个人设置；固定站内目标也支持刷新后返回，不依赖浏览器历史。
const returnTo = computed(() => (authToken.value ? '/account/settings' : '/login'))
</script>

<template>
  <div class="password-reset-page">
    <AppLink class="back-link" :to="returnTo">
      ← {{ returnTo === '/account/settings' ? '返回个人设置' : '返回登录' }}
    </AppLink>
    <PasswordResetForm :return-to="returnTo" />
  </div>
</template>

<style scoped>
.password-reset-page {
  position: relative;
  width: min(520px, calc(100% - 40px));
  margin: 0 auto;
  padding: 80px 0 100px;
  display: grid;
  grid-template-columns: minmax(0, 1fr);
}
.back-link {
  position: absolute;
  top: 24px;
  left: 0;
  color: #80786d;
  text-decoration: none;
}
.back-link:hover,
.back-link:focus-visible {
  color: #b67c00;
}
@media (max-width: 760px) {
  .password-reset-page {
    width: min(520px, calc(100% - 28px));
    padding-top: 70px;
  }
}
</style>
