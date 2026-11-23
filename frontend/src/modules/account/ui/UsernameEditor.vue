<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import { useLoginStore } from '@/modules/auth'
import { usernameError } from '@/modules/auth/model/accountValidation'
import { authToken } from '@/shared/api/authSession'
import { toErrorMessage } from '@/shared/lib/toErrorMessage'
import AppLink from '@/shared/ui/AppLink.vue'
import { updateOwnUsername } from '../api/profileApi'

const settingsPath = '/account/settings'
const route = useRoute()
const router = useRouter()
const auth = useLoginStore()
const draft = ref('')
const error = ref('')
const saving = ref(false)
const canSave = computed(
  () => !!draft.value.trim() && draft.value.trim() !== auth.user?.username && !auth.loggingOut,
)
let pending: InstanceType<typeof globalThis.AbortController> | undefined

watch(
  [authToken, () => auth.user?.id],
  () => {
    pending?.abort()
    pending = undefined
    draft.value = auth.user?.username ?? ''
    error.value = ''
    saving.value = false
  },
  { immediate: true, flush: 'sync' },
)
onBeforeRouteLeave(() => !saving.value)
onBeforeUnmount(() => pending?.abort())

function returnToSettings() {
  if (router.options.history.state.back === settingsPath) router.back()
  else void router.replace(settingsPath)
}

async function save() {
  const user = auth.user
  const token = authToken.value
  if (!user || !token || saving.value || !canSave.value) return
  error.value = usernameError(draft.value) ?? ''
  if (error.value) return

  const request = new globalThis.AbortController()
  pending = request
  saving.value = true
  let saved = false
  try {
    const result = await updateOwnUsername(
      String(user.id),
      token,
      draft.value.trim(),
      request.signal,
    )
    if (request.signal.aborted) return
    saved = auth.acceptUsername(result.userId, token, result.username)
    if (!saved) error.value = '账号状态已变化，请返回账号设置核对。'
  } catch (reason) {
    if (!request.signal.aborted) error.value = toErrorMessage(reason)
  } finally {
    if (pending === request) {
      pending = undefined
      saving.value = false
    }
  }
  if (saved) returnToSettings()
}
</script>

<template>
  <section class="account-center username-settings-page" aria-labelledby="username-title">
    <header>
      <button
        type="button"
        class="username-back"
        :disabled="saving"
        aria-label="返回账号设置"
        @click="returnToSettings"
      >
        <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m14 5-7 7 7 7" /></svg>
      </button>
      <h1 id="username-title">修改用户名</h1>
    </header>
    <form v-if="auth.authenticated" @submit.prevent="save">
      <label for="new-username">新用户名</label>
      <input
        id="new-username"
        v-model="draft"
        type="text"
        autocomplete="off"
        autocapitalize="none"
        spellcheck="false"
        :disabled="saving"
        :aria-invalid="!!error"
        :aria-describedby="error ? 'username-error' : 'username-hint'"
        @input="error = ''"
      />
      <p v-if="error" id="username-error" class="username-error" role="alert">{{ error }}</p>
      <p v-else id="username-hint" class="username-hint">保存后，用新用户名登录。</p>
      <div class="username-actions">
        <button type="submit" :disabled="saving || !canSave">
          {{ saving ? '正在保存…' : '保存' }}
        </button>
      </div>
    </form>
    <AppLink
      v-else
      class="username-login"
      :to="{ path: '/login', query: { redirect: route.path } }"
    >
      登录后修改用户名
    </AppLink>
  </section>
</template>

<style scoped>
:global(body:has(.username-settings-page)) {
  background: #f6f7f9;
}
.username-settings-page {
  width: min(500px, calc(100% - 36px));
  margin: 0 auto;
  padding: 36px 0 60px;
  color: #27313c;
}
header {
  display: flex;
  align-items: center;
  gap: 18px;
  margin-bottom: 24px;
}
h1 {
  margin: 0;
  font-size: 24px;
  font-weight: 650;
}
.username-back {
  display: grid;
  place-items: center;
  width: 36px;
  min-height: 44px;
  padding: 0;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: #858e98;
  cursor: pointer;
}
.username-back svg {
  width: 20px;
  height: 20px;
  fill: none;
  stroke: currentColor;
  stroke-width: 1.7;
}
label {
  display: block;
  margin-bottom: 11px;
  font-size: 15px;
}
input {
  box-sizing: border-box;
  width: 100%;
  min-height: 44px;
  padding: 0 12px;
  border: 1px solid #dfe4ea;
  border-radius: 8px;
  background: #fff;
  color: inherit;
  font: inherit;
  font-size: 16px;
}
input:focus {
  border-color: #dfe4ea;
  outline: none;
  box-shadow: none;
}
.username-hint,
.username-error {
  margin: 9px 0 18px;
  color: #677582;
  font-size: 13px;
  line-height: 1.6;
  overflow-wrap: anywhere;
}
.username-error {
  color: #ae4237;
}
.username-actions {
  display: flex;
  justify-content: flex-end;
}
.username-actions button {
  min-width: 80px;
  min-height: 42px;
  padding: 0 19px;
  border: 1px solid #b6540d;
  border-radius: 8px;
  background: #b6540d;
  color: white;
  font: inherit;
  font-size: 14px;
  cursor: pointer;
}
button:disabled {
  opacity: 0.55;
  cursor: default;
}
button:focus-visible,
.username-login:focus-visible {
  outline: 2px solid #9da7b1;
  outline-offset: 3px;
}
.username-login {
  color: #9b490c;
}
@media (max-width: 600px) {
  .username-settings-page {
    padding-top: 25px;
  }
}
</style>
