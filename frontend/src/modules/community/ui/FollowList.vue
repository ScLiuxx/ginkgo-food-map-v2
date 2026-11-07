<script setup lang="ts">
import { computed, ref, watch, onBeforeUnmount } from 'vue'
import { useLoginStore } from '@/modules/auth'
import { authToken } from '@/shared/api/authSession'
import { toErrorMessage } from '@/shared/lib/toErrorMessage'
import { fetchFollowPage, setFollow, type FollowKind, type FollowMember } from '../api/followsApi'
const props = defineProps<{ userId?: string; kind: FollowKind }>(),
  auth = useLoginStore()
const subject = computed(() => props.userId || String(auth.user?.id ?? '')),
  own = computed(() => subject.value === String(auth.user?.id))
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
  error.value = ''
  loading.value = false
  const token = authToken.value
  if (!auth.authenticated || !token) return
  controller = new globalThis.AbortController()
  requested = target
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
    }
  } catch (reason) {
    if (ticket === generation) error.value = toErrorMessage(reason)
  } finally {
    if (ticket === generation) loading.value = false
  }
}
async function cancel(member: FollowMember) {
  if (!own.value || props.kind !== 'FOLLOWING' || busy.value) return
  const token = authToken.value,
    owner = context
  if (!token) return
  busy.value = true
  notice.value = ''
  try {
    await setFollow(member.userId, false, token)
    if (owner === context) {
      notice.value = '已取消关注'
      await load(1)
    }
  } catch (reason) {
    if (owner === context) {
      notice.value = `取消结果未确认：${toErrorMessage(reason)}，请刷新核对。`
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
    void load(1)
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
  <section class="follow-list" :aria-label="kind === 'FOLLOWING' ? '关注名单' : '粉丝名单'">
    <header>
      <h2>{{ kind === 'FOLLOWING' ? '关注名单' : '粉丝名单' }}</h2>
      <button :disabled="loading || busy" @click="load(1)">刷新名单</button>
    </header>
    <p v-if="notice" role="status">{{ notice }}</p>
    <p v-if="loading" role="status">正在加载名单…</p>
    <div v-else-if="error" role="alert">
      <p>{{ error }}</p>
      <button @click="load(requested)">重新加载</button>
    </div>
    <template v-else-if="loaded"
      ><p>共 {{ total }} 人</p>
      <p v-if="!records.length">
        {{ total ? '当前页已变化，请返回第一页。' : '暂无可展示的用户。' }}
      </p>
      <article v-for="member in records" :key="member.userId">
        <RouterLink :to="`/users/${member.userId}`"
          >{{ member.username }}
          <small v-if="member.level">Lv.{{ member.level }}</small></RouterLink
        ><button v-if="own && kind === 'FOLLOWING'" :disabled="busy" @click="cancel(member)">
          取消关注</button
        ><span v-else-if="member.following">已关注</span>
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
  padding: 20px 0;
}
.follow-list header,
.follow-list article,
.follow-list nav {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
}
.follow-list article {
  padding: 20px 0;
  border-bottom: 1px solid #eee;
}
.follow-list a {
  color: #6e5326;
  overflow-wrap: anywhere;
}
.follow-list small,
.follow-list p,
.follow-list span {
  font-size: 13px;
  color: #87919e;
}
.follow-list button {
  min-height: 44px;
  border: 1px solid #ded6c8;
  background: white;
  border-radius: 6px;
  padding: 7px 12px;
  color: #876323;
}
.follow-list button:disabled {
  opacity: 0.5;
}
.follow-list nav {
  justify-content: center;
  margin-top: 20px;
}
</style>
