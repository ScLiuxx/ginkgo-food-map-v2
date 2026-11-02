<script setup lang="ts">
// 展示组件只接收布局层提供的状态，不反向依赖认证业务模块。
withDefaults(
  defineProps<{
    discovery?: boolean
    authenticated: boolean
    username?: string
    restoring: boolean
    loggingOut: boolean
    hasSession: boolean
    error: string | null
  }>(),
  { discovery: false, username: '' },
)
defineEmits<{ logout: []; retry: [] }>()
</script>

<template>
  <header class="topbar" :class="{ 'is-discovery': discovery }">
    <RouterLink class="brand" to="/" aria-label="返回首页">
      <!-- 页头品牌使用文字展示，移除左侧装饰图标。 -->
      <span>
        <strong><em>泥电</em>美食地图</strong>
        <small>校园美食评价社区</small>
      </span>
    </RouterLink>

    <!-- 顶部只承担身份操作，页面切换统一使用既有底部导航。 -->
    <nav class="account-actions" aria-label="账号操作">
      <template v-if="authenticated">
        <span class="account-name" :title="username">{{ username }}</span>
        <button class="login-button" type="button" :disabled="loggingOut" @click="$emit('logout')">
          {{ loggingOut ? '正在退出…' : '退出登录' }}
        </button>
      </template>
      <span v-else-if="restoring" role="status">正在恢复登录…</span>
      <template v-else>
        <RouterLink class="login-button" to="/login">登录</RouterLink>
      </template>
    </nav>
  </header>
  <div v-if="error" class="auth-feedback" role="alert">
    {{ error }}
    <button v-if="hasSession && !authenticated" type="button" @click="$emit('retry')">重试</button>
  </div>
</template>

<style scoped>
/* 共享页头右侧只保留身份操作，移动端也可查看账号并退出；完整用户名通过title保留。 */
.topbar .account-actions {
  display: flex;
  align-items: center;
  gap: 20px;
  margin-left: auto;
  flex-shrink: 0;
}
.account-actions .account-name {
  max-width: 160px;
  color: #25313d;
  font-size: 14px;
  font-weight: 600;
}
.topbar .account-actions .login-button {
  min-height: 38px;
  padding: 8px 10px;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: #596675;
  font-size: 13px;
  font-weight: 400;
  cursor: pointer;
}
.topbar .account-actions .login-button:hover:enabled {
  background: #f3f5f6;
  color: #273645;
}
.account-actions > [role='status'] {
  color: #657382;
  font-size: 12px;
}
.topbar a:focus-visible,
.topbar button:focus-visible {
  outline: 2px solid #ad6f26;
  outline-offset: 3px;
}
.brand em {
  color: #f39a08;
  font-size: 24px;
  font-style: normal;
  font-weight: 850;
}
.topbar.is-discovery {
  width: min(1296px, calc(100% - 64px));
  height: 84px;
  border-bottom-color: #e9ecef;
}
.is-discovery .brand small {
  display: none;
}
.is-discovery .brand strong {
  display: flex;
  align-items: baseline;
  gap: 16px;
  font-size: 25px;
  font-weight: 650;
  letter-spacing: -0.4px;
}
.is-discovery .brand em {
  font-size: 36px;
  font-weight: 750;
  color: #e97913;
  letter-spacing: -1px;
}
@media (max-width: 1000px) and (min-width: 601px) {
  .is-discovery .brand strong {
    font-size: 19px;
    gap: 9px;
  }
  .is-discovery .brand em {
    font-size: 28px;
  }
}
@media (max-width: 600px) {
  .topbar.is-discovery {
    width: calc(100% - 32px);
    height: 72px;
  }
  .brand small {
    display: none;
  }
  .brand strong,
  .is-discovery .brand strong {
    display: flex;
    align-items: baseline;
    gap: 6px;
    font-size: 13px;
    white-space: nowrap;
    letter-spacing: 0;
  }
  .brand em,
  .is-discovery .brand em {
    font-size: 22px;
    letter-spacing: -0.5px;
  }
  .topbar .account-actions {
    display: flex;
    gap: 6px;
  }
  .account-actions .account-name {
    max-width: clamp(40px, 16vw, 70px);
    font-size: 11px;
  }
  .topbar .account-actions .login-button {
    min-height: 36px;
    padding: 6px;
    font-size: 11px;
  }
  .account-actions > [role='status'] {
    max-width: 108px;
    font-size: 10px;
  }
}
</style>
