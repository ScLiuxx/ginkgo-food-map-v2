<script setup lang="ts">
import { RegistrationForm } from '@/modules/auth'
</script>

<template>
  <div class="register-page">
    <RouterLink class="back-link" to="/">← 返回首页</RouterLink>
    <RegistrationForm />
  </div>
</template>

<style scoped>
.register-page {
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
  .register-page {
    padding-top: 70px;
  }
}

@media (max-width: 760px) {
  .register-page {
    width: min(100% - 28px, 520px);
  }
}
</style>
