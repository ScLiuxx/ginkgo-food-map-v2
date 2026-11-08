<script setup lang="ts">
// 共享导航只接收身份状态；写评回跳沿用认证页面的白名单验证。
defineProps<{ authenticated: boolean; discovery?: boolean; hasUnreadNotifications?: boolean }>()
</script>

<template>
  <nav class="bottom-navigation" aria-label="应用导航">
    <div class="navigation-items">
      <RouterLink to="/" exact-active-class="selected" aria-label="首页" title="首页">
        <svg viewBox="0 0 24 24" aria-hidden="true">
          <path d="m3 11 9-8 9 8M5 10v11h5v-7h4v7h5V10" />
        </svg>
      </RouterLink>
      <RouterLink to="/map" aria-label="地图" title="地图">
        <svg viewBox="0 0 24 24" aria-hidden="true">
          <path d="M19 10c0 5-7 11-7 11S5 15 5 10a7 7 0 1 1 14 0Z" />
          <circle cx="12" cy="10" r="2" />
        </svg>
      </RouterLink>
      <RouterLink
        class="compose-nav"
        :to="authenticated ? '/?writeReview=1' : '/login?redirect=%2F%3FwriteReview%3D1'"
        aria-label="写点评"
        title="写点评"
      >
        <span class="compose-icon" aria-hidden="true">
          <svg viewBox="0 0 24 24"><path d="M12 5v14M5 12h14" /></svg>
        </span>
      </RouterLink>
      <RouterLink
        to="/messages"
        :aria-label="authenticated && hasUnreadNotifications ? '消息（有未读）' : '消息'"
        title="消息"
        class="message-nav"
      >
        <span
          v-if="authenticated && hasUnreadNotifications"
          class="unread-dot"
          aria-hidden="true"
        />
        <svg viewBox="0 0 24 24" aria-hidden="true">
          <path d="M5 17h14l-2-4V9a5 5 0 0 0-10 0v4ZM10 21h4M12 2v2" />
        </svg>
      </RouterLink>
      <RouterLink to="/account" aria-label="我的" title="我的">
        <svg viewBox="0 0 24 24" aria-hidden="true">
          <circle cx="12" cy="7" r="4" />
          <path d="M4 22v-3a8 8 0 0 1 16 0v3Z" />
        </svg>
      </RouterLink>
    </div>
  </nav>
</template>

<style scoped>
.message-nav {
  position: relative;
}
.unread-dot {
  position: absolute;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #dc3545;
  top: 9px;
  left: calc(50% + 7px);
  box-shadow: 0 0 0 2px #fff;
}

/* 全站复用相同宽度和五等分布局，切换页面时导航位置保持稳定。 */
.bottom-navigation {
  position: fixed;
  z-index: 20;
  bottom: 0;
  left: 0;
  right: 0;
  padding-bottom: env(safe-area-inset-bottom);
  background: #fff;
  border-top: 1px solid #e9ecef;
}
.navigation-items {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  align-items: center;
  width: min(1100px, 100%);
  height: 64px;
  margin: 0 auto;
}
.navigation-items a {
  display: flex;
  align-items: center;
  justify-content: center;
  justify-self: center;
  width: 100%;
  max-width: 100px;
  min-height: 48px;
  color: #6d7280;
  text-decoration: none;
  border-radius: 8px;
}
.navigation-items a:hover,
.navigation-items .selected,
.navigation-items .router-link-active {
  color: #b75811;
}
/* 隐藏可见文字后仍通过名称、悬停提示和键盘焦点保留入口识别能力。 */
.navigation-items a:focus-visible {
  outline: 2px solid #ad6f26;
  outline-offset: 2px;
}
.navigation-items svg {
  width: 24px;
  height: 24px;
  fill: none;
  stroke: currentColor;
  stroke-width: 1.6;
  stroke-linecap: round;
  stroke-linejoin: round;
}
.compose-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 38px;
  height: 38px;
  border-radius: 50%;
  background: #e9b949;
  color: #fff;
}
.compose-icon svg {
  width: 23px;
  height: 23px;
  stroke-width: 2;
}
.compose-nav:hover .compose-icon {
  background: #dfa92e;
}
</style>
