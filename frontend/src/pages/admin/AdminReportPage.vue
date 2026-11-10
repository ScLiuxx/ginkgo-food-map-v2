<script setup lang="ts">
import { useLoginStore } from '@/modules/auth'
import AdminReportsWorkspace from '@/modules/admin/ui/AdminReportsWorkspace.vue'
const auth = useLoginStore()
</script>
<template>
  <AdminReportsWorkspace
    :username="auth.user?.username ?? ''"
    :logging-out="auth.loggingOut"
    :session-error="auth.error"
    @logout="auth.signOut"
  />
</template>
