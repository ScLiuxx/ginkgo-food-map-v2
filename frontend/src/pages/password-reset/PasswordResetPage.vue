<script setup lang="ts">
import { PasswordResetForm } from '@/modules/auth'
</script>

<template>
  <div class="password-reset-page">
    <RouterLink class="back-link" to="/login">← 返回登录</RouterLink>
    <PasswordResetForm />
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
