<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { clearAccessToken, getAccessToken } from '@/shared/api/authSession'
import { toErrorMessage } from '@/shared/lib/toErrorMessage'
import { resetPassword, sendPasswordResetCode } from '../api/authApi'
import { passwordError, emailError, codeError, accountErrorField } from '../model/accountValidation'
import PasswordInput from './PasswordInput.vue'
import './auth-inputs.css'
import { prepareResetLogin } from '../model/loginPrefill'

const form = reactive({ email: '', verificationCode: '', newPassword: '', confirmPassword: '' })
const router = useRouter()
const sendingCode = ref(false)
const submitting = ref(false)
const completed = ref(false)
const codeAccepted = ref(false)
const error = ref<string | null>(null)
const errors = reactive<Record<string, string | null>>({})
const remoteField = computed(() => accountErrorField(error.value))
function fieldError(field: string) {
  return errors[field] || (remoteField.value === field ? error.value : null)
}
function checkField(field: string) {
  if (field === 'email') errors.email = emailError(form.email)
  if (field === 'code') errors.code = codeError(form.verificationCode)
  if (field === 'password') errors.password = passwordError(form.newPassword)
  if (field === 'confirm')
    errors.confirm =
      form.confirmPassword === form.newPassword && form.confirmPassword
        ? null
        : '两次输入的新密码不一致'
}
function clearField(field: string) {
  errors[field] = null
  error.value = null
  if (field === 'email') codeAccepted.value = false
  if (field === 'password' && form.confirmPassword) {
    errors.confirm = form.confirmPassword === form.newPassword ? null : '两次输入的新密码不一致'
  }
}
const cooldown = ref(0)
const busy = computed(() => sendingCode.value || submitting.value)
let active = true
let cooldownTimer: ReturnType<typeof globalThis.setInterval> | undefined

/** 使用截止时间计算剩余秒数，后台标签页定时器暂停不会人为延长本地倒计时。 */
function startCooldown() {
  const deadline = Date.now() + 60_000
  cooldown.value = 60
  cooldownTimer = globalThis.setInterval(() => {
    cooldown.value = Math.max(0, Math.ceil((deadline - Date.now()) / 1_000))
    if (cooldown.value === 0) globalThis.clearInterval(cooldownTimer)
  }, 1_000)
}

/** 仅提示受理结果，不以文案或页面状态透露邮箱是否已注册。服务端仍执行最终限流。 */
async function handleSendCode() {
  if (busy.value || completed.value || cooldown.value > 0) return
  error.value = null
  codeAccepted.value = false
  const email = form.email.trim().toLowerCase()
  checkField('email')
  if (errors.email) return
  sendingCode.value = true
  try {
    await sendPasswordResetCode(email)
    if (active) {
      codeAccepted.value = true
      startCooldown()
    }
  } catch (reason) {
    if (active) error.value = toErrorMessage(reason)
  } finally {
    sendingCode.value = false
  }
}

/** 密码仅留在表单内；重置成功清理请求开始时的旧凭证，不能误删期间产生的新登录。 */
async function handleSubmit() {
  if (busy.value || completed.value) return
  error.value = null
  for (const field of ['email', 'code', 'password', 'confirm']) checkField(field)
  if (Object.values(errors).some(Boolean)) return
  submitting.value = true
  const previousToken = getAccessToken()
  try {
    const result = await resetPassword({
      email: form.email.trim().toLowerCase(),
      verificationCode: form.verificationCode,
      newPassword: form.newPassword,
    })
    if (previousToken) clearAccessToken(previousToken)
    form.verificationCode = ''
    form.newPassword = ''
    form.confirmPassword = ''
    if (active) {
      completed.value = true
      prepareResetLogin(result.username)
      await router.replace('/login')
    }
  } catch (reason) {
    if (active) error.value = toErrorMessage(reason)
  } finally {
    // 请求成功与失败均释放明文密码，避免敏感输入随错误页面长期驻留。
    form.newPassword = ''
    form.confirmPassword = ''
    submitting.value = false
  }
}

onBeforeUnmount(() => {
  active = false
  globalThis.clearInterval(cooldownTimer)
  form.verificationCode = ''
  form.newPassword = ''
  form.confirmPassword = ''
})
</script>

<template>
  <form
    class="password-reset-form"
    aria-labelledby="password-reset-title"
    novalidate
    @submit.prevent="handleSubmit"
  >
    <p class="eyebrow">RESET PASSWORD</p>
    <h1 id="password-reset-title">找回密码</h1>
    <p class="form-intro">通过账号绑定的学生邮箱验证身份，设置新的登录密码。</p>

    <fieldset :disabled="busy || completed">
      <legend class="sr-only">邮箱验证与新密码</legend>
      <div class="form-field">
        <label for="reset-email">学生邮箱</label>
        <div class="field-with-action">
          <input
            id="reset-email"
            v-model="form.email"
            name="email"
            type="email"
            autocomplete="email"
            :aria-invalid="!!fieldError('email')"
            :aria-describedby="fieldError('email') ? 'reset-email-error' : undefined"
            @blur="checkField('email')"
            @input="clearField('email')"
          />
          <button
            class="code-button"
            type="button"
            :disabled="cooldown > 0"
            @click="handleSendCode"
          >
            {{ sendingCode ? '正在发送…' : cooldown > 0 ? `${cooldown} 秒后重试` : '发送验证码' }}
          </button>
        </div>

        <p v-if="fieldError('email')" id="reset-email-error" class="field-error" role="alert">
          {{ fieldError('email') }}
        </p>
        <small>验证码5分钟内有效，与注册验证码不能混用。</small>
      </div>
      <div class="form-field">
        <label for="reset-code">邮箱验证码</label>
        <input
          id="reset-code"
          v-model="form.verificationCode"
          name="verificationCode"
          inputmode="numeric"
          autocomplete="one-time-code"
          maxlength="6"
          :aria-invalid="!!fieldError('code')"
          :aria-describedby="fieldError('code') ? 'reset-code-error' : undefined"
          @blur="checkField('code')"
          @input="clearField('code')"
        />
        <p v-if="fieldError('code')" id="reset-code-error" class="field-error" role="alert">
          {{ fieldError('code') }}
        </p>
      </div>
      <div class="form-field">
        <label for="reset-password">新密码</label>
        <PasswordInput
          id="reset-password"
          v-model="form.newPassword"
          name="newPassword"
          autocomplete="new-password"
          :disabled="busy || completed"
          :aria-invalid="!!fieldError('password')"
          :aria-describedby="fieldError('password') ? 'reset-password-error' : undefined"
          @blur="checkField('password')"
          @input="clearField('password')"
        />
        <p v-if="fieldError('password')" id="reset-password-error" class="field-error" role="alert">
          {{ fieldError('password') }}
        </p>
      </div>
      <div class="form-field">
        <label for="reset-confirm-password">确认新密码</label>
        <PasswordInput
          id="reset-confirm-password"
          v-model="form.confirmPassword"
          name="confirmPassword"
          autocomplete="new-password"
          :disabled="busy || completed"
          :aria-invalid="!!fieldError('confirm')"
          :aria-describedby="fieldError('confirm') ? 'reset-confirm-password-error' : undefined"
          @blur="checkField('confirm')"
          @input="clearField('confirm')"
        />
        <p
          v-if="fieldError('confirm')"
          id="reset-confirm-password-error"
          class="field-error"
          role="alert"
        >
          {{ fieldError('confirm') }}
        </p>
      </div>
      <button class="primary-action submit-button" type="submit">
        {{ submitting ? '正在重置…' : '设置新密码' }}
      </button>
    </fieldset>

    <p v-if="codeAccepted && !completed" class="form-message" role="status">
      请求已受理。如该邮箱已注册，验证码将发送至该邮箱，请查看收件箱。
    </p>
    <p v-if="error && !remoteField" class="form-message error-message" role="alert">{{ error }}</p>
    <div v-if="completed" class="form-message success-message" role="status">
      <strong>密码重置成功</strong>
      <p>请使用新密码重新登录。</p>
    </div>
    <p class="form-footnote"><RouterLink to="/login">返回登录</RouterLink></p>
  </form>
</template>

<style scoped>
.password-reset-form {
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
.form-footnote,
small {
  color: #6b6357;
  line-height: 1.7;
}
fieldset {
  min-width: 0;
  margin: 0;
  padding: 0;
  border: 0;
}
.sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip-path: inset(50%);
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
  min-width: 0;
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
small {
  display: block;
  margin-top: 8px;
  font-size: 12px;
}
.field-with-action {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 10px;
}
.code-button {
  padding: 0 16px;
  border: 0;
  border-radius: 14px;
  color: #2b2a26;
  background: #e9b949;
  font-weight: 800;
}
button {
  cursor: pointer;
}
.submit-button {
  width: 100%;
  margin-top: 28px;
}
.form-message {
  margin-top: 20px;
  padding: 14px 16px;
  border-radius: 14px;
  background: #f0eadf;
  line-height: 1.6;
}
.error-message {
  color: #8b2f29;
  background: #fbe7e3;
}
.success-message {
  color: #225f3e;
  background: #dcefe3;
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
@media (max-width: 480px) {
  .field-with-action {
    grid-template-columns: 1fr;
  }
  .code-button {
    min-height: 44px;
  }
}
</style>
