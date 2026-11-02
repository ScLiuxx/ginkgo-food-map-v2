<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'

import PasswordInput from './PasswordInput.vue'
import './auth-inputs.css'
import {
  usernameError,
  passwordError,
  emailError,
  codeError,
  accountErrorField,
} from '../model/accountValidation'
import { useRegistrationStore } from '../model/registration.store'

const RESEND_SECONDS = 60

const registrationStore = useRegistrationStore()
const router = useRouter()
// Pinia 会跨路由保留状态，进入页面时主动清理上一次注册结果，但不持久化任何表单字段。
registrationStore.reset()
const form = reactive({
  email: '',
  verificationCode: '',
  username: '',
  password: '',
  confirmPassword: '',
})
const errors = reactive<Record<string, string | null>>({})
const remoteField = computed(() => accountErrorField(registrationStore.error))
function fieldError(field: string) {
  return errors[field] || (remoteField.value === field ? registrationStore.error : null)
}
function checkField(field: string) {
  if (field === 'email') errors.email = emailError(campusEmail.value)
  if (field === 'code') errors.code = codeError(form.verificationCode)
  if (field === 'username') errors.username = usernameError(form.username)
  if (field === 'password') errors.password = passwordError(form.password)
  if (field === 'confirm')
    errors.confirm =
      form.confirmPassword === form.password && form.confirmPassword ? null : '两次输入的密码不一致'
}
function clearField(field: string) {
  errors[field] = null
  registrationStore.clearError()
  if (field === 'password' && form.confirmPassword) {
    errors.confirm = form.confirmPassword === form.password ? null : '两次输入的密码不一致'
  }
}
const cooldown = ref(0)
let cooldownTimer: ReturnType<typeof globalThis.setInterval> | undefined

// 后缀固定展示与拼接，用户仅输入邮箱前缀，完整邮箱仍由服务端校验。
const campusEmail = computed(() => `${form.email.trim()}@std.uestc.edu.cn`)
const feedback = computed(() => (remoteField.value ? null : registrationStore.error))
const codeButtonText = computed(() => {
  if (registrationStore.sendingCode) return '正在发送…'
  return cooldown.value > 0 ? `${cooldown.value} 秒后重试` : '发送验证码'
})

function startCooldown() {
  cooldown.value = RESEND_SECONDS
  if (cooldownTimer) globalThis.clearInterval(cooldownTimer)
  cooldownTimer = globalThis.setInterval(() => {
    cooldown.value -= 1
    if (cooldown.value <= 0 && cooldownTimer) {
      globalThis.clearInterval(cooldownTimer)
      cooldownTimer = undefined
    }
  }, 1_000)
}

async function handleSendCode() {
  registrationStore.clearError()
  const normalizedEmail = campusEmail.value.toLowerCase()
  checkField('email')
  if (errors.email) return

  if (await registrationStore.sendCode(normalizedEmail)) {
    startCooldown()
  }
}

async function handleSubmit() {
  if (registrationStore.registering || registrationStore.registered) return
  registrationStore.clearError()
  for (const field of ['email', 'code', 'username', 'password', 'confirm']) checkField(field)
  if (Object.values(errors).some(Boolean)) return

  const success = await registrationStore.submit({
    email: campusEmail.value.toLowerCase(),
    verificationCode: form.verificationCode,
    username: form.username.trim(),
    password: form.password,
  })
  if (success) {
    // 请求完成后立即清除页面中的敏感字段；账号信息保留用于确认用户刚刚注册的身份。
    form.verificationCode = ''
    form.password = ''
    form.confirmPassword = ''
    await router.replace('/')
  }
}

onBeforeUnmount(() => {
  if (cooldownTimer) globalThis.clearInterval(cooldownTimer)
})
</script>

<template>
  <form class="registration-form" novalidate @submit.prevent="handleSubmit">
    <div class="form-heading">
      <p class="eyebrow">CREATE ACCOUNT</p>
      <h1>加入我们</h1>
    </div>

    <div class="form-field">
      <label for="register-email">学生邮箱</label>
      <div class="field-with-action">
        <div class="email-input">
          <input
            id="register-email"
            v-model="form.email"
            type="text"
            autocomplete="off"
            autocapitalize="none"
            spellcheck="false"
            :disabled="registrationStore.registered"
            :aria-invalid="!!fieldError('email')"
            :aria-describedby="
              fieldError('email') ? 'email-suffix register-email-error' : 'email-suffix'
            "
            @blur="checkField('email')"
            @input="clearField('email')"
          />
          <span id="email-suffix">@std.uestc.edu.cn</span>
        </div>
        <button
          class="code-button"
          type="button"
          :disabled="registrationStore.sendingCode || cooldown > 0 || registrationStore.registered"
          @click="handleSendCode"
        >
          {{ codeButtonText }}
        </button>
      </div>

      <p v-if="fieldError('email')" id="register-email-error" class="field-error" role="alert">
        {{ fieldError('email') }}
      </p>
      <small>验证码5分钟内有效，同一邮箱60秒内只能发送一次。</small>
    </div>

    <div class="form-field">
      <label for="register-code">邮箱验证码</label>
      <input
        id="register-code"
        v-model="form.verificationCode"
        type="text"
        inputmode="numeric"
        autocomplete="one-time-code"
        maxlength="6"
        :disabled="registrationStore.registered"
        :aria-invalid="!!fieldError('code')"
        :aria-describedby="fieldError('code') ? 'register-code-error' : undefined"
        @blur="checkField('code')"
        @input="clearField('code')"
      />
      <p v-if="fieldError('code')" id="register-code-error" class="field-error" role="alert">
        {{ fieldError('code') }}
      </p>
    </div>

    <div class="form-field">
      <label for="register-username">用户名</label>
      <input
        id="register-username"
        v-model="form.username"
        type="text"
        autocomplete="username"
        :disabled="registrationStore.registered"
        :aria-invalid="!!fieldError('username')"
        :aria-describedby="fieldError('username') ? 'register-username-error' : undefined"
        @blur="checkField('username')"
        @input="clearField('username')"
      />
      <p
        v-if="fieldError('username')"
        id="register-username-error"
        class="field-error"
        role="alert"
      >
        {{ fieldError('username') }}
      </p>
    </div>

    <div class="password-grid">
      <div class="form-field">
        <label for="register-password">密码</label>
        <PasswordInput
          id="register-password"
          v-model="form.password"
          autocomplete="new-password"
          :disabled="registrationStore.registered"
          :aria-invalid="!!fieldError('password')"
          :aria-describedby="fieldError('password') ? 'register-password-error' : undefined"
          @blur="checkField('password')"
          @input="clearField('password')"
        />
        <p
          v-if="fieldError('password')"
          id="register-password-error"
          class="field-error"
          role="alert"
        >
          {{ fieldError('password') }}
        </p>
      </div>
      <div class="form-field">
        <label for="register-confirm-password">确认密码</label>
        <PasswordInput
          id="register-confirm-password"
          v-model="form.confirmPassword"
          autocomplete="new-password"
          :disabled="registrationStore.registered"
          :aria-invalid="!!fieldError('confirm')"
          :aria-describedby="fieldError('confirm') ? 'register-confirm-password-error' : undefined"
          @blur="checkField('confirm')"
          @input="clearField('confirm')"
        />
        <p
          v-if="fieldError('confirm')"
          id="register-confirm-password-error"
          class="field-error"
          role="alert"
        >
          {{ fieldError('confirm') }}
        </p>
      </div>
    </div>

    <p v-if="feedback" class="form-message error-message" role="alert">{{ feedback }}</p>
    <div v-if="registrationStore.registered" class="form-message success-message" role="status">
      <strong>注册成功，正在进入首页…</strong>
    </div>

    <button
      class="submit-button"
      type="submit"
      :disabled="registrationStore.registering || registrationStore.registered"
    >
      {{ registrationStore.registering ? '正在创建账号…' : '创建账号' }}
    </button>

    <p class="form-footnote">已有账号？<RouterLink to="/login">前往登录</RouterLink></p>
  </form>
</template>

<style scoped>
.registration-form {
  padding: clamp(28px, 5vw, 46px);
  border: 0;
  border-radius: 34px;
  background: #fff;
}

.form-heading {
  margin-bottom: 0;
}

.form-heading h1 {
  margin: 8px 0 12px;
  font-family: Georgia, 'Songti SC', serif;
  font-size: clamp(36px, 5vw, 52px);
}

.form-heading p:last-child {
  margin: 0;
  color: #6b6357;
  line-height: 1.7;
}

.form-field {
  margin-top: 24px;
}

.form-field label {
  display: block;
  margin-bottom: 9px;
  font-size: 14px;
  font-weight: 800;
}

.form-field input {
  width: 100%;
  height: 50px;
  padding: 0 15px;
  border: 1px solid #d8d0c2;
  border-radius: 14px;
  outline: none;
  color: #2b2a26;
  background: #fff;
  font: inherit;
  transition:
    border-color 160ms ease,
    box-shadow 160ms ease;
}

.form-field input:focus {
  border-color: #b67c00;
  box-shadow: 0 0 0 4px rgb(233 185 73 / 20%);
}

.form-field input:disabled {
  color: #80786d;
  background: #f3eee5;
}

.form-field small {
  display: block;
  margin-top: 8px;
  color: #80786d;
  font-size: 12px;
  line-height: 1.5;
}

.field-with-action {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 10px;
}

.code-button {
  min-width: 124px;
  padding: 0 16px;
  border: 0;
  border-radius: 14px;
  color: #2b2a26;
  background: #e9b949;
  font-weight: 800;
  cursor: pointer;
}

.password-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
}

.form-message {
  margin: 20px 0 0;
  padding: 14px 16px;
  border-radius: 14px;
  font-size: 14px;
  line-height: 1.6;
}

.error-message {
  color: #8b2f29;
  background: #fbe7e3;
}

.success-message {
  display: flex;
  flex-direction: column;
  color: #225f3e;
  background: #dcefe3;
}

.submit-button {
  width: 100%;
  min-height: 50px;
  margin-top: 28px;
  border: 0;
  border-radius: 999px;
  color: #fff;
  background: #2b2a26;
  font-weight: 800;
  cursor: pointer;
}

.form-footnote {
  margin: 16px 0 0;
  color: #80786d;
  font-size: 12px;
  text-align: center;
}

@media (max-width: 620px) {
  .field-with-action,
  .password-grid {
    grid-template-columns: 1fr;
  }

  .code-button {
    min-height: 46px;
  }
}
.email-input {
  display: flex;
  align-items: center;
  min-width: 0;
  border: 1px solid #d8d0c2;
  border-radius: 14px;
  background: #fff;
}
.email-input:focus-within {
  outline: 2px solid #b67c00;
  outline-offset: 2px;
}
.email-input input {
  min-width: 0;
  border: 0;
  background: transparent;
}
.email-input input:focus {
  box-shadow: none;
  outline: none;
}
.email-input span {
  flex-shrink: 0;
  padding-right: 12px;
  color: #80786d;
  font-size: 13px;
}
a {
  text-decoration: none;
}
a:hover,
a:focus-visible {
  color: #b67c00;
}
</style>
