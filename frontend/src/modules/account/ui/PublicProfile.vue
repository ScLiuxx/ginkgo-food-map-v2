<script setup lang="ts">
import { ref, watch, onBeforeUnmount } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useLoginStore } from '@/modules/auth'
import { authToken } from '@/shared/api/authSession'
import { toErrorMessage } from '@/shared/lib/toErrorMessage'
import { MyReviews } from '@/modules/review'
import FollowSummary from '@/modules/community/ui/FollowSummary.vue'
import FollowList from '@/modules/community/ui/FollowList.vue'
import RestaurantFavorites from '@/modules/restaurant/ui/RestaurantFavorites.vue'
import { fetchPublicProfile, type PublicProfile } from '../api/publicProfileApi'
import { useReturnNavigation } from '@/shared/lib/useReturnNavigation'
import { useListRouteState } from '@/shared/lib/useListRouteState'
import UserAvatar from './UserAvatar.vue'

/** 登录可见主页，换主体或会话立即清理资料，取消旧请求并阻止迟到结果回填。 */
const props = defineProps<{ publicUserId: string; section?: string }>()
const auth = useLoginStore()
const route = useRoute()
const router = useRouter()
const { returnTarget, returnToPrevious } = useReturnNavigation(() =>
  props.section ? `/users/${props.publicUserId}` : '/account',
)
const viewState = useListRouteState(() => route.path === `/users/${props.publicUserId}`)
const profile = ref<PublicProfile | null>(null)
const tab = ref<'reviews' | 'favorites'>('reviews')
// 标签数量仅采信已获授权的列表响应；未读取或私密拒绝时不伪造为零。
const reviewTotal = ref<number | null>(null)
const favoriteTotal = ref<number | null>(null)
const followTotal = ref<number | null>(null)
const error = ref(''),
  loading = ref(false)
let controller: globalThis.AbortController | undefined,
  generation = 0
async function load() {
  const ticket = ++generation
  controller?.abort()
  profile.value = null
  reviewTotal.value = favoriteTotal.value = followTotal.value = null
  tab.value = viewState.read('tab') === 'favorites' ? 'favorites' : 'reviews'
  error.value = ''
  loading.value = false
  const token = authToken.value
  if (!auth.authenticated || !token) return
  // 旧链接、登录恢复及换号均重新判断本人；替换历史条目，避免返回时再次进入已取消的本人主页。
  if (props.publicUserId === String(auth.user?.id)) {
    await router.replace({
      path: props.section ? `/account/${props.section}` : '/account',
      query:
        props.section && typeof route.query.page === 'string' ? { page: route.query.page } : {},
    })
    return
  }
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
/** 切换内容从第一页读取；返回店铺来源时从当前URL恢复选中的标签和页码。 */
async function selectTab(value: 'reviews' | 'favorites') {
  if (tab.value === value) return
  await viewState.save({ tab: value === 'favorites' ? value : undefined, page: undefined })
  tab.value = value
}
</script>
<template>
  <section
    class="account-center public-profile-page"
    :class="{ 'account-follow-page': !!section }"
    :aria-label="section ? (section === 'following' ? '关注' : '粉丝') : '个人主页'"
    :aria-busy="loading"
  >
    <header class="account-topline">
      <RouterLink
        class="account-back"
        :to="returnTarget"
        aria-label="返回"
        @click.capture="returnToPrevious"
        ><svg viewBox="0 0 24 24" aria-hidden="true"><path d="m14 5-7 7 7 7" /></svg
      ></RouterLink>
      <h1>{{ section ? (section === 'following' ? '关注' : '粉丝') : '个人主页' }}</h1>
      <span
        v-if="section && followTotal !== null"
        class="follow-page-total"
        :aria-label="`${followTotal}人`"
        >{{ followTotal }}</span
      >
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
      <div v-if="!section" class="account-profile">
        <FollowSummary :key="profile.userId" :user-id="profile.userId" profile-layout>
          <template #identity>
            <div class="profile-identity">
              <UserAvatar
                class="profile-avatar"
                :user-id="profile.userId"
                :username="profile.username"
              />
              <div class="profile-info">
                <div class="profile-heading">
                  <h2 :title="profile.username">{{ profile.username }}</h2>
                  <span class="level-badge">Lv.{{ profile.level }}</span>
                </div>
                <p class="profile-bio">{{ profile.bio || '还没有填写个人简介' }}</p>
              </div>
            </div>
          </template>
          <template #likes>
            <span class="public-profile-likes"
              ><strong>{{ profile.receivedLikes }}</strong
              ><span>获赞</span></span
            >
          </template>
        </FollowSummary>
      </div>
      <FollowList
        v-if="section"
        :user-id="profile.userId"
        :kind="section === 'following' ? 'FOLLOWING' : 'FOLLOWERS'"
        @total="followTotal = $event"
      />
      <div
        v-if="!section"
        class="public-profile-content"
        :class="{ 'profile-reviews-content': tab === 'reviews' }"
      >
        <nav class="profile-tabs" aria-label="个人主页内容">
          <button
            type="button"
            :aria-pressed="tab === 'reviews'"
            aria-controls="public-profile-content"
            @click="selectTab('reviews')"
          >
            点评
            <span v-if="reviewTotal !== null" class="profile-tab-count">{{ reviewTotal }}</span>
          </button>
          <button
            type="button"
            :aria-pressed="tab === 'favorites'"
            aria-label="收藏店铺"
            aria-controls="public-profile-content"
            @click="selectTab('favorites')"
          >
            收藏
            <span v-if="favoriteTotal !== null" class="profile-tab-count">{{ favoriteTotal }}</span>
          </button>
        </nav>
        <div id="public-profile-content">
          <MyReviews
            v-if="tab === 'reviews'"
            :key="profile.userId"
            :public-user-id="profile.userId"
            profile-layout
            card-layout
            @total="reviewTotal = $event"
          />
          <RestaurantFavorites
            v-else
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
