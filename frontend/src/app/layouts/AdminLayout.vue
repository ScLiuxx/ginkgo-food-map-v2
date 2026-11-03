<script setup lang="ts">
import { onBeforeUnmount, ref, watch, watchEffect } from 'vue'
import { useRouter } from 'vue-router'
import { useLoginStore } from '@/modules/auth'
import { verifyAdminSession } from '@/modules/admin'
import { authToken, getAccessToken } from '@/shared/api/authSession'
import { toErrorMessage } from '@/shared/lib/toErrorMessage'
import { isAxiosError } from 'axios'

const router = useRouter()
const auth = useLoginStore()
const verified = ref(false)
const verifying = ref(false)
const verificationError = ref('')
const forbidden = ref(false)
let revision = 0

/** 进入工作台须通过真实管理接口校验，后端403与临时故障均不能降级为仅前端放行。 */
async function verifyAccess() {
  const token = getAccessToken()
  if (!token || !auth.isAdministrator || auth.restoring) return
  const current = ++revision
  verified.value = false
  verifying.value = true
  verificationError.value = ''
  forbidden.value = false
  try {
    await verifyAdminSession(token)
    if (current === revision && token === getAccessToken() && auth.isAdministrator)
      verified.value = true
  } catch (reason) {
    if (current === revision && token === getAccessToken()) {
      forbidden.value = isAxiosError(reason) && reason.response?.status === 403
      verificationError.value = toErrorMessage(reason)
    }
  } finally {
    if (current === revision) verifying.value = false
  }
}

watch(
  [() => auth.isAdministrator, () => auth.restoring, authToken],
  () => {
    revision++
    verified.value = false
    verifying.value = false
    verificationError.value = ''
    forbidden.value = false
    if (auth.isAdministrator && !auth.restoring) void verifyAccess()
  },
  { immediate: true },
)
onBeforeUnmount(() => {
  revision++
})

// 会话恢复由 App 统一触发；恢复完成前不挂载审核组件，账号切换后也立即销毁旧内容。
watchEffect(() => {
  if (!auth.hasSession && !auth.loggingIn && !auth.restoring) {
    void router.replace('/login')
  }
})
</script>

<template>
  <RouterView v-if="auth.isAdministrator && !auth.restoring && verified" />
  <div v-else class="admin-access">
    <header>
      <span class="access-brand"><em>泥电</em>美食地图</span><span>管理工作台</span>
    </header>
    <section
      v-if="auth.restoring || verifying || (!auth.authenticated && !auth.error)"
      role="status"
    >
      <span class="access-spinner" />
      <h1>正在验证登录状态</h1>
      <p>验证完成后进入管理工作台。</p>
    </section>
    <section v-else-if="forbidden || (auth.authenticated && !auth.isAdministrator)">
      <h1>当前账号没有管理权限</h1>
      <p>「{{ auth.user?.username }}」可继续使用前台功能，或更换管理员账号登录。</p>
      <button class="access-primary" :disabled="auth.loggingOut" @click="auth.signOut">
        退出并更换账号</button
      ><RouterLink v-if="!auth.isAdministrator" to="/">返回前台</RouterLink>
    </section>
    <section v-else>
      <h1>暂时无法验证登录状态</h1>
      <p role="alert">{{ verificationError || auth.error }}</p>
      <button
        class="access-primary"
        :disabled="auth.restoring || verifying"
        @click="auth.isAdministrator ? verifyAccess() : auth.restore()"
      >
        重新验证</button
      ><button :disabled="auth.loggingOut" @click="auth.signOut">退出登录</button>
    </section>
  </div>
</template>

<style scoped>
/* 身份等待和拒绝访问沿用管理端品牌，验证失败时不显示任何审核内容。 */
.admin-access {
  min-height: 100vh;
  color: #25313d;
  background: #fff;
}
header {
  height: 84px;
  margin: 0 40px;
  display: flex;
  gap: 24px;
  align-items: center;
  border-bottom: 1px solid #e9ecef;
}
header > span {
  font-size: 13px;
  color: #818a94;
}
.access-brand {
  display: flex;
  align-items: baseline;
  gap: 14px;
  text-decoration: none;
  font-size: 23px;
  font-weight: 650;
}
.access-brand em {
  color: #e97913;
  font-size: 35px;
  font-style: normal;
  font-weight: 750;
}
section {
  max-width: 560px;
  margin: 110px auto;
  padding: 0 24px;
  text-align: center;
}
h1 {
  font-size: 24px;
  font-weight: 650;
}
p {
  color: #818a94;
  font-size: 13px;
  line-height: 1.9;
  margin-bottom: 28px;
}
section a,
section button {
  display: inline-block;
  font: inherit;
  font-size: 13px;
  text-decoration: none;
  padding: 12px 16px;
  margin: 4px;
}
.access-primary {
  border: 0;
  border-radius: 5px;
  background: #e98a20;
  color: white;
  cursor: pointer;
}
.access-spinner {
  display: inline-block;
  width: 26px;
  height: 26px;
  border: 2px solid #f4e8d8;
  border-top-color: #e98a20;
  border-radius: 50%;
  animation: access-spin 0.8s linear infinite;
}
@keyframes access-spin {
  to {
    transform: rotate(360deg);
  }
}
@media (max-width: 600px) {
  header {
    margin: 0 18px;
    gap: 15px;
  }
  .access-brand {
    font-size: 17px;
    gap: 8px;
  }
  .access-brand em {
    font-size: 27px;
  }
}
</style>
