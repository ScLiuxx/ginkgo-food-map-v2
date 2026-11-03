<script setup lang="ts">
import { AdminReviewWorkspace } from '@/modules/admin'
import { useLoginStore } from '@/modules/auth'
const auth = useLoginStore()
</script>

<template>
  <AdminReviewWorkspace
    :username="auth.user?.username ?? ''"
    :logging-out="auth.loggingOut"
    :session-error="auth.error"
    @logout="auth.signOut"
  />
</template>
