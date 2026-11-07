<script setup lang="ts">
import { ref, watch, onBeforeUnmount } from 'vue'
import { useRoute } from 'vue-router'
import { useLoginStore } from '@/modules/auth'
import { authToken } from '@/shared/api/authSession'
import { toErrorMessage } from '@/shared/lib/toErrorMessage'
import { MyReviews } from '@/modules/review'
import FollowSummary from '@/modules/community/ui/FollowSummary.vue'
import FollowList from '@/modules/community/ui/FollowList.vue'
import RestaurantFavorites from '@/modules/restaurant/ui/RestaurantFavorites.vue'
import { fetchPublicProfile, type PublicProfile } from '../api/publicProfileApi'

/** 登录可见主页，换主体或会话立即清理资料，取消旧请求并阻止迟到结果回填。 */
const props = defineProps<{ publicUserId: string; section?: string }>()
const auth = useLoginStore()
const route = useRoute()
const profile = ref<PublicProfile | null>(null)
const tab = ref<'reviews' | 'favorites'>('reviews')
const error = ref(''),
  loading = ref(false)
let controller: globalThis.AbortController | undefined,
  generation = 0
async function load() {
  const ticket = ++generation
  controller?.abort()
  profile.value = null
  tab.value = 'reviews'
  error.value = ''
  loading.value = false
  const token = authToken.value
  if (!auth.authenticated || !token) return
  controller = new globalThis.AbortController()
  loading.value = true
  try {
    const result = await fetchPublicProfile(props.publicUserId, token, controller.signal)
    if (ticket === generation) profile.value = result
  } catch (reason) {
    if (ticket === generation) error.value = toErrorMessage(reason)
  } finally {
    if (ticket === generation) loading.value = false
  }
}
watch(
  () => [props.publicUserId, props.section, auth.authenticated, auth.user?.id, authToken.value],
  load,
  {
    immediate: true,
    flush: 'sync',
  },
)
onBeforeUnmount(() => {
  generation++
  controller?.abort()
})
</script>
<template>
  <section class="account-center" aria-label="个人主页">
    <header class="account-topline">
      <RouterLink to="/account">‹ 我的</RouterLink>
      <h1>个人主页</h1>
    </header>
    <div v-if="!auth.authenticated" class="account-guest">
      <h2>登录后查看个人主页</h2>
      <p>个人主页仅向登录用户开放。</p>
      <RouterLink class="account-primary" :to="{ path: '/login', query: { redirect: route.path } }"
        >登录账号</RouterLink
      >
    </div>
    <p v-else-if="loading" role="status">正在加载个人主页…</p>
    <div v-else-if="error" class="account-block" role="alert">
      <p>{{ error }}</p>
      <button class="account-secondary" @click="load">重新加载</button>
    </div>
    <template v-else-if="profile">
      <div class="account-profile">
        <div class="profile-heading">
          <h2>{{ profile.username }}</h2>
          <span class="level-badge">Lv.{{ profile.level }}</span>
        </div>
        <p class="profile-bio">{{ profile.bio || '还没有填写个人简介' }}</p>
        <p class="profile-bio">
          当前获赞 <strong>{{ profile.receivedLikes }}</strong>
        </p>
      </div>
      <FollowSummary :key="profile.userId" :user-id="profile.userId" />
      <FollowList
        v-if="section"
        :user-id="profile.userId"
        :kind="section === 'following' ? 'FOLLOWING' : 'FOLLOWERS'"
      />
      <nav v-if="!section" class="profile-tabs" aria-label="个人主页内容">
        <button type="button" :aria-pressed="tab === 'reviews'" @click="tab = 'reviews'">
          点评
        </button>
        <button type="button" :aria-pressed="tab === 'favorites'" @click="tab = 'favorites'">
          收藏店铺
        </button>
      </nav>
      <MyReviews
        v-if="!section && tab === 'reviews'"
        :key="profile.userId"
        :public-user-id="profile.userId"
      />
      <RestaurantFavorites v-else-if="!section" :key="profile.userId" :user-id="profile.userId" />
    </template>
  </section>
</template>
<style scoped src="./account-center.css"></style>
