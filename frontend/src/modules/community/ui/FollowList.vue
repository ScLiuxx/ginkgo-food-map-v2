<script setup lang="ts">
import { computed, ref, watch, onBeforeUnmount } from 'vue'
import { useLoginStore } from '@/modules/auth'
import { authToken } from '@/shared/api/authSession'
import { toErrorMessage } from '@/shared/lib/toErrorMessage'
import { fetchFollowPage, setFollow, type FollowKind, type FollowMember } from '../api/followsApi'
import { useRoute } from 'vue-router'
import { useListRouteState } from '@/shared/lib/useListRouteState'
import { userProfilePath } from '@/shared/lib/userProfileNavigation'
const props = defineProps<{ userId?: string; kind: FollowKind }>(),
  auth = useLoginStore()
// 总数只来自已获授权的名单；读取中或私密拒绝时通知页头隐藏数量，不冒充零人。
const emit = defineEmits<{ total: [value: number | null] }>()
const subject = computed(() => props.userId || String(auth.user?.id ?? '')),
  own = computed(() => subject.value === String(auth.user?.id))
const route = useRoute()
const viewState = useListRouteState(
  () =>
    route.path ===
    `${props.userId ? `/users/${props.userId}` : '/account'}/${props.kind === 'FOLLOWING' ? 'following' : 'followers'}`,
)
let initialized = false
const records = ref<FollowMember[]>([]),
  page = ref(1),
  total = ref(0),
  pages = ref(0),
  loading = ref(false),
  busy = ref(false),
  loaded = ref(false),
  error = ref(''),
  notice = ref('')
let context = 0,
  generation = 0,
  requested = 1,
  controller: globalThis.AbortController | undefined
/** 名单私密返回明确拒绝；换号、换方向或主体时不保留旧名单。 */
async function load(target = page.value) {
  const ticket = ++generation
  controller?.abort()
  records.value = []
  loaded.value = false
  emit('total', null)
  error.value = ''
  loading.value = false
  const token = authToken.value
  if (!auth.authenticated || !token) return
  controller = new globalThis.AbortController()
  requested = target
  void viewState.save({ page: target === 1 ? undefined : target })
  loading.value = true
  try {
    const result = await fetchFollowPage(
      subject.value,
      props.kind,
      target,
      token,
      controller.signal,
    )
    if (ticket === generation) {
      records.value = result.records
      page.value = result.page
      total.value = result.total
      pages.value = result.totalPages
      loaded.value = true
      emit('total', result.total)
    }
  } catch (reason) {
    if (ticket === generation) error.value = toErrorMessage(reason)
  } finally {
    if (ticket === generation) loading.value = false
  }
}
/** 每行操作作用于访问者与该用户的关系；仅本人关注名单取消后回首页，其他名单保留当前页。 */
async function toggle(member: FollowMember) {
  if (member.userId === String(auth.user?.id) || busy.value) return
  const token = authToken.value,
    owner = context
  if (!token) return
  busy.value = true
  notice.value = ''
  try {
    await setFollow(member.userId, !member.following, token)
    if (owner === context) {
      notice.value = member.following ? '已取消关注' : '已关注'
      await load(own.value && props.kind === 'FOLLOWING' ? 1 : page.value)
    }
  } catch (reason) {
    if (owner === context) {
      notice.value = `关注结果未确认：${toErrorMessage(reason)}，请核对当前名单。`
      await load()
    }
  } finally {
    if (owner === context) busy.value = false
  }
}
watch(
  () => [props.userId, props.kind, auth.authenticated, auth.user?.id, authToken.value],
  () => {
    context++
    generation++
    controller?.abort()
    page.value = 1
    total.value = pages.value = 0
    busy.value = false
    notice.value = ''
    const target = !initialized ? viewState.page() : 1
    if (auth.authenticated && authToken.value) initialized = true
    void load(target)
  },
  { immediate: true, flush: 'sync' },
)
onBeforeUnmount(() => {
  context++
  generation++
  controller?.abort()
})
</script>
<template>
  <section
    class="follow-list"
    :aria-label="kind === 'FOLLOWING' ? '关注名单' : '粉丝名单'"
    :aria-busy="loading"
  >
    <p v-if="notice" class="follow-feedback" role="status">{{ notice }}</p>
    <p v-if="loading" class="follow-message" role="status">正在加载名单…</p>
    <div v-else-if="error" class="follow-message" role="alert">
      <p>{{ error }}</p>
      <button @click="load(requested)">重新加载</button>
    </div>
    <template v-else-if="loaded">
      <p v-if="!records.length" class="follow-message">
        {{ total ? '当前页已变化，请返回第一页。' : '暂无可展示的用户。' }}
      </p>
      <article v-for="member in records" :key="member.userId" class="follow-member">
        <RouterLink class="follow-identity" :to="userProfilePath(member.userId, auth.user?.id)">
          <span class="follow-avatar" aria-hidden="true">{{
            Array.from(member.username.trim() || '?')[0]!.toUpperCase()
          }}</span>
          <span class="follow-info">
            <span class="follow-name">{{ member.username }}</span>
            <small v-if="member.level" class="follow-level">Lv.{{ member.level }}</small>
          </span>
        </RouterLink>
        <button
          v-if="member.userId !== String(auth.user?.id)"
          class="follow-toggle"
          :class="{ 'is-following': member.following }"
          :aria-label="`${member.following ? '取消关注' : '关注'} ${member.username}`"
          :disabled="busy"
          @click="toggle(member)"
        >
          {{ member.following ? '已关注' : '关注' }}
        </button>
      </article>
      <nav v-if="pages > 1 || page > 1" aria-label="名单分页">
        <button :disabled="page <= 1 || busy" @click="load(page - 1)">上一页</button
        ><span>{{ page }} / {{ pages || 1 }}</span
        ><button :disabled="page >= pages || busy" @click="load(page + 1)">下一页</button
        ><button v-if="!records.length" @click="load(1)">第一页</button>
      </nav>
    </template>
  </section>
</template>
<style scoped>
.follow-list {
  min-width: 0;
  padding: 0;
}
.follow-list nav {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}
.follow-member {
  display: flex;
  align-items: center;
  gap: 16px;
  min-height: 80px;
  padding: 12px 0;
  box-sizing: border-box;
  border-bottom: 1px solid #f0f1f3;
}
.follow-identity {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
  flex: 1;
  color: #303943;
  text-decoration: none;
}
.follow-avatar {
  display: grid;
  place-items: center;
  flex-shrink: 0;
  width: 44px;
  height: 44px;
  border-radius: 50%;
  background: #f5f0e8;
  color: #a58b67;
  font-size: 18px;
  font-weight: 600;
}
.follow-info {
  display: flex;
  flex-direction: column;
  gap: 6px;
  min-width: 0;
}
.follow-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 15px;
  font-weight: 500;
  line-height: 1.4;
}
.follow-level {
  color: #929ba5;
  font-size: 11px;
  line-height: 1.2;
}
.follow-message,
.follow-feedback {
  font-size: 13px;
  color: #87919e;
  line-height: 1.8;
  overflow-wrap: anywhere;
}
.follow-message {
  padding: 24px 0;
  margin: 0;
}
.follow-feedback {
  margin: 0 0 8px;
}
.follow-list button {
  min-height: 44px;
  border: 0;
  background: #f3f4f6;
  border-radius: 6px;
  padding: 7px 12px;
  color: #78828e;
  font: inherit;
  font-size: 12px;
  cursor: pointer;
}
.follow-list .follow-toggle {
  min-width: 76px;
  flex-shrink: 0;
  background: #fb8b24;
  color: #fff;
}
.follow-list .follow-toggle.is-following {
  background: #f3f4f6;
  color: #87919e;
}
.follow-list button:disabled {
  opacity: 0.5;
  cursor: default;
}
.follow-list :is(a, button):focus-visible {
  outline: 2px solid #a5adb7;
  outline-offset: 3px;
}
.follow-list nav {
  justify-content: center;
  margin-top: 20px;
  color: #87919e;
  font-size: 12px;
}
</style>
