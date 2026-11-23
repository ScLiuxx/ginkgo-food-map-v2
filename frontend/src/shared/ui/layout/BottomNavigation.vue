<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { reviewDestination } from '@/shared/lib/reviewNavigation'

const route = useRoute()
// 沿用全站写评目的地及replace语义，在当前页面打开并保留来源现场。
const composeDestination = computed(() => reviewDestination(route))
defineProps<{ authenticated: boolean; discovery?: boolean; hasUnreadNotifications?: boolean }>()
</script>

<template>
  <nav class="bottom-navigation" aria-label="应用导航">
    <div class="navigation-items">
      <RouterLink v-slot="{ navigate, isExactActive }" to="/" custom>
        <button
          type="button"
          :class="{ selected: discovery || isExactActive }"
          :aria-current="isExactActive ? 'page' : undefined"
          aria-label="首页"
          @click="navigate"
        >
          <span class="navigation-tooltip" aria-hidden="true">首页</span>
          <svg viewBox="0 0 24 24" aria-hidden="true">
            <path d="m3 11 9-8 9 8M5 10v11h5v-7h4v7h5V10" />
          </svg>
        </button>
      </RouterLink>
      <RouterLink v-slot="{ navigate, isActive }" to="/map" custom>
        <button
          type="button"
          :class="{ selected: isActive }"
          :aria-current="isActive ? 'page' : undefined"
          aria-label="地图"
          @click="navigate"
        >
          <span class="navigation-tooltip" aria-hidden="true">地图</span>
          <svg viewBox="0 0 24 24" aria-hidden="true">
            <path d="M19 10c0 5-7 11-7 11S5 15 5 10a7 7 0 1 1 14 0Z" />
            <circle cx="12" cy="10" r="2" />
          </svg>
        </button>
      </RouterLink>
      <RouterLink v-slot="{ navigate }" :to="composeDestination" replace custom>
        <button type="button" class="compose-nav" aria-label="写点评" @click="navigate">
          <span class="navigation-tooltip" aria-hidden="true">写点评</span>
          <span class="compose-icon" aria-hidden="true">
            <svg viewBox="0 0 24 24"><path d="M12 5v14M5 12h14" /></svg>
          </span>
        </button>
      </RouterLink>
      <RouterLink v-slot="{ navigate, isActive }" to="/messages" custom>
        <button
          type="button"
          :class="{ selected: isActive }"
          :aria-current="isActive ? 'page' : undefined"
          :aria-label="authenticated && hasUnreadNotifications ? '消息（有未读）' : '消息'"
          class="message-nav"
          @click="navigate"
        >
          <span class="navigation-tooltip" aria-hidden="true">消息</span>
          <span
            v-if="authenticated && hasUnreadNotifications"
            class="unread-dot"
            aria-hidden="true"
          />
          <svg viewBox="0 0 24 24" aria-hidden="true">
            <path d="M5 17h14l-2-4V9a5 5 0 0 0-10 0v4ZM10 21h4M12 2v2" />
          </svg>
        </button>
      </RouterLink>
      <RouterLink v-slot="{ navigate, isActive }" to="/account" custom>
        <button
          type="button"
          :class="{ selected: isActive }"
          :aria-current="isActive ? 'page' : undefined"
          aria-label="我的"
          @click="navigate"
        >
          <span class="navigation-tooltip" aria-hidden="true">我的</span>
          <svg viewBox="0 0 24 24" aria-hidden="true">
            <circle cx="12" cy="7" r="4" />
            <path d="M4 22v-3a8 8 0 0 1 16 0v3Z" />
          </svg>
        </button>
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
  bottom: max(18px, env(safe-area-inset-bottom));
  left: 50%;
  transform: translateX(-50%);
  width: min(320px, calc(100% - 24px));
  padding: 7px 12px;
  background: #fff;
  border: 1px solid #e9ecef;
  border-radius: 40px;
  box-shadow: 0 6px 24px #28334309;
  box-sizing: border-box;
}
.navigation-items {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  align-items: center;
  width: 100%;
  height: 48px;
  margin: 0 auto;
}
.navigation-items button {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  justify-self: center;
  width: 100%;
  max-width: 48px;
  min-height: 48px;
  padding: 0;
  border: 0;
  background: transparent;
  font: inherit;
  color: #7f8a96;
  border-radius: 8px;
  cursor: pointer;
}
.navigation-items button:hover,
.navigation-items .selected {
  color: #e78525;
}
/* 隐藏可见文字后仍通过名称、悬停提示和键盘焦点保留入口识别能力。 */
.navigation-items button:focus-visible {
  outline: 2px solid #a8adb3;
  outline-offset: 2px;
}
.navigation-tooltip {
  position: absolute;
  bottom: calc(100% + 6px);
  left: 50%;
  transform: translateX(-50%);
  padding: 4px 8px;
  border-radius: 4px;
  background: #fff;
  border: 1px solid #edf0f2;
  box-shadow: 0 3px 12px #2833430a;
  color: #59616c;
  font-size: 12px;
  line-height: 1.5;
  white-space: nowrap;
  pointer-events: none;
  visibility: hidden;
}
.navigation-items button:focus-visible .navigation-tooltip {
  visibility: visible;
}
@media (hover: hover) {
  .navigation-items button:hover .navigation-tooltip {
    visibility: visible;
  }
}
.navigation-items svg {
  width: 21px;
  height: 21px;
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
  background: #e98527;
  color: #fff;
}
.compose-icon svg {
  width: 23px;
  height: 23px;
  stroke-width: 2;
}
.compose-nav:hover .compose-icon {
  background: #db791e;
}
@media (max-width: 600px) {
  .bottom-navigation {
    bottom: max(12px, env(safe-area-inset-bottom));
  }
}
</style>
