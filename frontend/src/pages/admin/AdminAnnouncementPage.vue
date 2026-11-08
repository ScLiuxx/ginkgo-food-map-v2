<script setup lang="ts">
import { useLoginStore } from '@/modules/auth'
import AdminAnnouncementWorkspace from '@/modules/admin/ui/AdminAnnouncementWorkspace.vue'
const auth = useLoginStore()
</script>
<template>
  <AdminAnnouncementWorkspace
    :username="auth.user?.username ?? ''"
    :logging-out="auth.loggingOut"
    :session-error="auth.error"
    @logout="auth.signOut"
  />
</template>
