<script setup lang="ts">
import { ref, watch, onBeforeUnmount } from 'vue'
import { useLoginStore } from '@/modules/auth'
import { authToken } from '@/shared/api/authSession'
import { toErrorMessage } from '@/shared/lib/toErrorMessage'
import { followPrivacy, type FollowPrivacy } from '../api/followsApi'
const auth = useLoginStore(),
  data = ref<FollowPrivacy | null>(null),
  loading = ref(false),
  busy = ref(false),
  known = ref(false),
  error = ref(''),
  notice = ref('')
let generation = 0,
  controller: globalThis.AbortController | undefined
async function load() {
  const ticket = ++generation
  controller?.abort()
  data.value = null
  known.value = false
  error.value = ''
  notice.value = ''
  loading.value = false
  busy.value = false
  const token = authToken.value,
    user = auth.user?.id
  if (!auth.authenticated || !token || !user) return
  controller = new globalThis.AbortController()
  loading.value = true
  try {
    const result = await followPrivacy(String(user), token, controller.signal)
    if (ticket === generation) {
      data.value = result
      known.value = true
    }
  } catch (reason) {
    if (ticket === generation) error.value = toErrorMessage(reason)
  } finally {
    if (ticket === generation) loading.value = false
  }
}
/** 开关独立选择，显式发送完整目标状态；回执确认前恢复旧DOM，失败要求重读。 */
async function change(field: 'followingPublic' | 'followersPublic', event: globalThis.Event) {
  const element = event.target as globalThis.HTMLInputElement,
    next = element.checked
  element.checked = data.value?.[field] ?? false
  if (!known.value || busy.value || !data.value) return
  const token = authToken.value,
    user = auth.user?.id,
    ticket = generation
  if (!token || !user) return
  const value = {
    followingPublic: data.value.followingPublic,
    followersPublic: data.value.followersPublic,
    [field]: next,
  }
  busy.value = true
  error.value = ''
  notice.value = ''
  try {
    const result = await followPrivacy(String(user), token, undefined, value)
    if (ticket === generation) {
      data.value = result
      notice.value = '名单可见性已保存'
    }
  } catch (reason) {
    if (ticket === generation) {
      known.value = false
      error.value = `${toErrorMessage(reason)}。保存结果未确认，请重新读取。`
    }
  } finally {
    if (ticket === generation) busy.value = false
  }
}
watch(() => [auth.authenticated, auth.user?.id, authToken.value], load, {
  immediate: true,
  flush: 'sync',
})
onBeforeUnmount(() => {
  generation++
  controller?.abort()
})
</script>
<template>
  <div class="follow-privacy">
    <label
      >向其他登录用户公开关注名单<input
        type="checkbox"
        :checked="data?.followingPublic ?? false"
        :disabled="!known || busy || loading"
        @change="change('followingPublic', $event)" /></label
    ><label
      >向其他登录用户公开粉丝名单<input
        type="checkbox"
        :checked="data?.followersPublic ?? false"
        :disabled="!known || busy || loading"
        @change="change('followersPublic', $event)"
    /></label>
    <p>两个名单默认仅本人可见；关注数和粉丝数仍向其他登录用户展示。</p>
    <p v-if="loading || busy" role="status">{{ busy ? '正在保存…' : '正在读取名单设置…' }}</p>
    <p v-if="notice" role="status">{{ notice }}</p>
    <div v-if="error" role="alert">
      <p>{{ error }}</p>
      <button @click="load">重新读取名单设置</button>
    </div>
  </div>
</template>
<style scoped>
.follow-privacy {
  padding: 16px 0;
}
.follow-privacy label {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 16px;
  margin-bottom: 16px;
  line-height: 1.7;
}
.follow-privacy input {
  width: 20px;
  height: 20px;
  flex-shrink: 0;
  accent-color: #c98f2b;
}
.follow-privacy p {
  font-size: 12px;
  color: #87919e;
  line-height: 1.8;
}
.follow-privacy button {
  min-height: 44px;
  padding: 6px 12px;
  border: 1px solid #ddd;
  background: white;
  border-radius: 6px;
}
</style>
