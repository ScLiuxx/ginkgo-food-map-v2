<script setup lang="ts">
import AppLink from '@/shared/ui/AppLink.vue'
import { computed, nextTick, onBeforeUnmount, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import PasswordInput from './PasswordInput.vue'
import './auth-inputs.css'
import { useLoginStore } from '../model/login.store'
import { consumeResetLogin } from '../model/loginPrefill'
import { loginRedirect } from '../model/loginRedirect'
import { campusEmail, CAMPUS_EMAIL_SUFFIX } from '../model/campusEmail'

const router = useRouter()
const route = useRoute()
const auth = useLoginStore()
const resetUsername = consumeResetLogin()
const modes = [
  { key: 'username', label: '用户名登录' },
  { key: 'student', label: '学号登录' },
] as const
const mode = ref<'username' | 'student'>('username')
const form = reactive({ username: resetUsername ?? '', student: '', password: '' })
const errors = reactive({ username: '', password: '' })
const disabled = computed(() => auth.loggingIn || auth.restoring)
const accountLabel = computed(() => (mode.value === 'student' ? '学号' : '用户名'))
const tabs = ref<globalThis.HTMLDivElement>()
let active = true

function checkField(field: 'username' | 'password') {
  if (field === 'password') {
    errors.password = form.password ? '' : '请输入密码'
    return
  }
  const value = form[mode.value].trim()
  errors.username = !value
    ? `请输入${accountLabel.value}`
    : mode.value === 'student' && !/^[0-9]+$/.test(value)
      ? '学号仅支持数字，请勿输入邮箱后缀'
      : mode.value === 'username' && value.includes('@')
        ? '请输入用户名，使用学号请切换至学号登录'
        : ''
}

function changeMode(next: 'username' | 'student') {
  if (disabled.value || mode.value === next) return
  mode.value = next
  form.password = ''
  errors.username = ''
  errors.password = ''
  auth.clearError()
}

async function navigateTabs(event: globalThis.KeyboardEvent) {
  if (disabled.value || !['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return
  event.preventDefault()
  const next =
    event.key === 'Home'
      ? 'username'
      : event.key === 'End'
        ? 'student'
        : mode.value === 'username'
          ? 'student'
          : 'username'
  changeMode(next)
  await nextTick()
  tabs.value?.querySelector<globalThis.HTMLButtonElement>('[aria-selected="true"]')?.focus()
}

function clearField(field: 'username' | 'password') {
  errors[field] = ''
  auth.clearError()
}
auth.clearError()

/** 密码只留在表单内，完成提交或离开页面后清除，绝不规范化密码中的空格。 */
async function handleSubmit() {
  if (auth.loggingIn || auth.restoring) return
  auth.clearError()
  checkField('username')
  checkField('password')
  if (errors.username || errors.password) return
  const username = mode.value === 'student' ? campusEmail(form.student) : form.username.trim()
  const success = await auth.submit({ username, password: form.password })
  form.password = ''
  if (success && active) {
    // 管理账号专用工作台，管理员优先于普通回跳；客户端不能自报角色或跳转到外部地址。
    if (auth.isAdministrator) {
      await router.replace('/admin/reviews')
      return
    }
    // 社区页面和投稿入口也可登录后返回；统一白名单继续拒绝外部地址。
    await router.replace(loginRedirect(route.query.redirect))
  }
}

onBeforeUnmount(() => {
  active = false
  form.password = ''
})
</script>

<template>
  <form class="login-form" aria-labelledby="login-title" @submit.prevent="handleSubmit">
    <p class="eyebrow">WELCOME BACK</p>
    <h1 id="login-title">欢迎回来</h1>
    <p v-if="resetUsername" class="form-intro" role="status">密码已重置，请输入新密码登录。</p>
    <div class="form-field">
      <div
        ref="tabs"
        class="login-modes"
        role="tablist"
        aria-label="登录方式"
        @keydown="navigateTabs"
      >
        <button
          v-for="item in modes"
          :id="`login-tab-${item.key}`"
          :key="item.key"
          type="button"
          role="tab"
          :aria-selected="mode === item.key"
          aria-controls="login-account-panel"
          :tabindex="mode === item.key ? 0 : -1"
          :disabled="disabled"
          @click="changeMode(item.key)"
        >
          {{ item.label }}
        </button>
      </div>
      <div id="login-account-panel" role="tabpanel" :aria-labelledby="`login-tab-${mode}`">
        <div :class="{ 'email-input': mode === 'student' }">
          <input
            id="login-username"
            v-model="form[mode]"
            :aria-label="accountLabel"
            autocomplete="username"
            :inputmode="mode === 'student' ? 'numeric' : 'text'"
            :maxlength="mode === 'student' ? 64 : 254"
            autocapitalize="none"
            :spellcheck="false"
            :aria-invalid="!!errors.username"
            :aria-describedby="
              [
                mode === 'student' ? 'login-email-suffix' : '',
                errors.username ? 'login-username-error' : '',
              ]
                .filter(Boolean)
                .join(' ') || undefined
            "
            :disabled="disabled"
            @blur="checkField('username')"
            @input="clearField('username')"
          />
          <span v-if="mode === 'student'" id="login-email-suffix">{{ CAMPUS_EMAIL_SUFFIX }}</span>
        </div>
        <p v-if="errors.username" id="login-username-error" class="field-error" role="alert">
          {{ errors.username }}
        </p>
      </div>
    </div>
    <div class="form-field">
      <label for="login-password">密码</label>
      <PasswordInput
        id="login-password"
        v-model="form.password"
        autocomplete="current-password"
        :aria-invalid="!!(errors.password || auth.error)"
        :aria-describedby="errors.password || auth.error ? 'login-password-error' : undefined"
        :disabled="disabled"
        @blur="checkField('password')"
        @input="clearField('password')"
      />
      <p
        v-if="errors.password || auth.error"
        id="login-password-error"
        class="field-error"
        role="alert"
      >
        {{ errors.password || auth.error }}
      </p>
    </div>
    <p class="recovery-link"><AppLink to="/password-reset">忘记密码？</AppLink></p>
    <button class="primary-action" type="submit" :disabled="auth.loggingIn || auth.restoring">
      {{ auth.loggingIn ? '正在登录…' : '登录' }}
    </button>
    <p class="form-footnote">还没有账号？<AppLink to="/register">使用校园邮箱注册</AppLink></p>
  </form>
</template>

<style scoped>
.login-form {
  padding: clamp(28px, 5vw, 46px);
  border: 0;
  border-radius: 34px;
  background: #fff;
}
h1 {
  margin: 8px 0 12px;
  font-family: Georgia, 'Songti SC', serif;
  font-size: clamp(36px, 5vw, 52px);
}
.form-intro,
.form-footnote {
  color: #6b6357;
  line-height: 1.7;
}
.form-field {
  margin-top: 24px;
}
label {
  display: block;
  margin-bottom: 9px;
  font-size: 14px;
  font-weight: 800;
}
input {
  width: 100%;
  height: 50px;
  padding: 0 15px;
  border: 1px solid #d8d0c2;
  border-radius: 14px;
  color: #2b2a26;
  background: #fff;
  font: inherit;
}
input:focus {
  outline: 2px solid #b67c00;
  outline-offset: 2px;
}
.form-error {
  margin: 20px 0 0;
  padding: 14px 16px;
  border-radius: 14px;
  color: #8b2f29;
  background: #fbe7e3;
  line-height: 1.6;
}
.recovery-link {
  margin: 12px 0 0;
  text-align: right;
  font-size: 14px;
}
button {
  width: 100%;
  margin-top: 28px;
  cursor: pointer;
}
.login-modes {
  display: flex;
  gap: 28px;
  margin-bottom: 18px;
}
.login-modes button {
  position: relative;
  width: auto;
  margin: 0;
  padding: 0 0 11px;
  border: 0;
  color: #96968e;
  background: transparent;
  font: inherit;
  font-size: 14px;
  font-weight: 600;
}
.login-modes button[aria-selected='true'] {
  color: #2b2a26;
  font-weight: 800;
}
.login-modes button[aria-selected='true']::after {
  position: absolute;
  bottom: 0;
  left: 0;
  width: 30px;
  height: 2px;
  border-radius: 2px;
  background: #2b2a26;
  content: '';
}
.login-modes button:focus-visible {
  outline: 2px solid #a2a8af;
  outline-offset: 4px;
}
.form-footnote {
  margin: 20px 0 0;
  font-size: 14px;
  text-align: center;
}
a {
  text-decoration: none;
  transition: color 160ms ease;
}
a:hover,
a:focus-visible {
  color: #b67c00;
}
</style>
