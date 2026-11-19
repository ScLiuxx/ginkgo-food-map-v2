<script setup lang="ts">
import AppLink from '@/shared/ui/AppLink.vue'
import './admin-theme.css'

/** 共用顶栏只展示名称和会话操作；各工作区继续控制原有的忙碌禁用条件。 */
withDefaults(defineProps<{ username: string; loggingOut: boolean; disabled?: boolean }>(), {
  disabled: false,
})
defineEmits<{ logout: [] }>()
</script>

<template>
  <header class="admin-header">
    <AppLink class="admin-brand" to="/admin/reviews">泥电美食地图</AppLink>
    <div class="header-account">
      <span class="header-avatar" aria-hidden="true">管</span>
      <span class="header-username" :title="username">{{ username }}</span>
      <button class="header-logout" :disabled="loggingOut || disabled" @click="$emit('logout')">
        {{ loggingOut ? '正在退出…' : '退出登录' }}
      </button>
    </div>
  </header>
</template>

<style scoped>
.admin-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex: none;
  gap: 20px;
  height: var(--admin-header-height);
  padding: 0 var(--admin-header-padding);
  border-bottom: 1px solid var(--admin-divider);
  background: var(--admin-surface);
  color: var(--admin-text);
}
.admin-brand {
  flex: none;
  color: inherit;
  font-size: 20px;
  font-weight: 650;
  line-height: 1.4;
  text-decoration: none;
  white-space: nowrap;
}
.header-account {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
  font-size: 13px;
}
.header-avatar {
  display: grid;
  place-items: center;
  flex: none;
  width: 30px;
  height: 30px;
  border-radius: 50%;
  background: var(--admin-surface-hover);
  color: var(--admin-secondary);
}
.header-username {
  max-width: 150px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.header-logout {
  flex: none;
  padding: 8px 0;
  border: 0;
  background: transparent;
  color: var(--admin-secondary);
  font: inherit;
  font-size: 12px;
  white-space: nowrap;
  cursor: pointer;
}
.header-logout:disabled {
  cursor: not-allowed;
  opacity: 0.55;
}
@media (max-width: 800px) {
  .admin-header {
    gap: 12px;
  }
  .admin-brand {
    font-size: 18px;
  }
  .header-account {
    gap: 10px;
    font-size: 12px;
  }
  .header-avatar {
    display: none;
  }
  .header-username {
    max-width: 80px;
  }
}
</style>
