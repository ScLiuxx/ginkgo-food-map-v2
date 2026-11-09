<script setup lang="ts">
import { computed, ref, watch, onBeforeUnmount } from 'vue'
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
// 标签数量仅采信已获授权的列表响应；未读取或私密拒绝时不伪造为零。
const reviewTotal = ref<number | null>(null)
const favoriteTotal = ref<number | null>(null)
const reviews = ref<InstanceType<typeof MyReviews>>()
const favorites = ref<InstanceType<typeof RestaurantFavorites>>()
const activeList = computed(() => (tab.value === 'reviews' ? reviews.value : favorites.value))
const avatarInitial = computed(() =>
  Array.from(profile.value?.username.trim() || '?')[0]!.toUpperCase(),
)
const error = ref(''),
  loading = ref(false)
let controller: globalThis.AbortController | undefined,
  generation = 0
async function load() {
  const ticket = ++generation
  controller?.abort()
  profile.value = null
  reviewTotal.value = favoriteTotal.value = null
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
  <section class="account-center public-profile-page" aria-label="个人主页">
    <header class="account-topline">
      <RouterLink class="public-profile-back" to="/account">‹ 我的</RouterLink>
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
        <div class="profile-identity">
          <span class="profile-avatar" aria-hidden="true">{{ avatarInitial }}</span>
          <div class="profile-info">
            <div class="profile-heading">
              <h2>{{ profile.username }}</h2>
              <span class="level-badge">Lv.{{ profile.level }}</span>
            </div>
            <p class="profile-bio">{{ profile.bio || '还没有填写个人简介' }}</p>
          </div>
        </div>
        <div class="public-profile-statistics" aria-label="互动统计">
          <FollowSummary :key="profile.userId" :user-id="profile.userId" />
          <span class="public-profile-likes"
            ><strong>{{ profile.receivedLikes }}</strong
            ><span>获赞</span></span
          >
        </div>
      </div>
      <FollowList
        v-if="section"
        :user-id="profile.userId"
        :kind="section === 'following' ? 'FOLLOWING' : 'FOLLOWERS'"
      />
      <div v-if="!section" class="public-profile-content">
        <nav class="profile-tabs" aria-label="个人主页内容">
          <button
            type="button"
            :aria-pressed="tab === 'reviews'"
            aria-controls="public-profile-content"
            @click="tab = 'reviews'"
          >
            点评
            <span v-if="reviewTotal !== null" class="profile-tab-count">{{ reviewTotal }}</span>
          </button>
          <button
            type="button"
            :aria-pressed="tab === 'favorites'"
            aria-label="收藏店铺"
            aria-controls="public-profile-content"
            @click="tab = 'favorites'"
          >
            收藏
            <span v-if="favoriteTotal !== null" class="profile-tab-count">{{ favoriteTotal }}</span>
          </button>
          <button
            type="button"
            class="profile-refresh"
            :disabled="!activeList || activeList.refreshing"
            @click="activeList?.refresh()"
          >
            <span aria-hidden="true">⟳</span> 刷新
          </button>
        </nav>
        <div id="public-profile-content">
          <MyReviews
            v-if="tab === 'reviews'"
            ref="reviews"
            :key="profile.userId"
            :public-user-id="profile.userId"
            profile-layout
            @total="reviewTotal = $event"
          />
          <RestaurantFavorites
            v-else
            ref="favorites"
            :key="profile.userId"
            :user-id="profile.userId"
            profile-layout
            @total="favoriteTotal = $event"
          />
        </div>
      </div>
    </template>
  </section>
</template>
<style scoped src="./account-center.css"></style>
<style scoped src="./public-profile.css"></style>
