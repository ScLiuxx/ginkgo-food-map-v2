<script setup lang="ts">
import { onBeforeUnmount, reactive } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import PasswordInput from './PasswordInput.vue'
import './auth-inputs.css'
import { useLoginStore } from '../model/login.store'
import { consumeResetLogin } from '../model/loginPrefill'
import { loginRedirect } from '../model/loginRedirect'

const router = useRouter()
const route = useRoute()
const auth = useLoginStore()
const resetUsername = consumeResetLogin()
const form = reactive({ username: resetUsername ?? '', password: '' })
const errors = reactive({ username: '', password: '' })
let active = true

function checkField(field: 'username' | 'password') {
  errors[field] = (field === 'username' ? form.username.trim() : form.password)
    ? ''
    : field === 'username'
      ? '请输入用户名或邮箱'
      : '请输入密码'
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
  const success = await auth.submit({ username: form.username.trim(), password: form.password })
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
      <label for="login-username">用户名或邮箱</label>
      <input
        id="login-username"
        v-model="form.username"
        autocomplete="username"
        maxlength="254"
        placeholder="请输入用户名或邮箱"
        autocapitalize="none"
        :spellcheck="false"
        :aria-invalid="!!errors.username"
        :aria-describedby="errors.username ? 'login-username-error' : undefined"
        :disabled="auth.loggingIn"
        @blur="checkField('username')"
        @input="clearField('username')"
      />
      <p v-if="errors.username" id="login-username-error" class="field-error" role="alert">
        {{ errors.username }}
      </p>
    </div>
    <div class="form-field">
      <label for="login-password">密码</label>
      <PasswordInput
        id="login-password"
        v-model="form.password"
        autocomplete="current-password"
        :aria-invalid="!!(errors.password || auth.error)"
        :aria-describedby="errors.password || auth.error ? 'login-password-error' : undefined"
        :disabled="auth.loggingIn"
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
    <p class="recovery-link"><RouterLink to="/password-reset">忘记密码？</RouterLink></p>
    <button class="primary-action" type="submit" :disabled="auth.loggingIn || auth.restoring">
      {{ auth.loggingIn ? '正在登录…' : '登录' }}
    </button>
    <p class="form-footnote">
      还没有账号？<RouterLink to="/register">使用校园邮箱注册</RouterLink>
    </p>
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
