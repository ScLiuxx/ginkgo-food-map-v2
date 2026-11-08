<script setup lang="ts">
import { useLoginStore } from '@/modules/auth'
import { AdminRestaurantWorkspace } from '@/modules/admin'
import WindowManagement from '@/modules/admin/ui/WindowManagement.vue'
defineProps<{ id: string }>()
const auth = useLoginStore()
</script>
<template>
  <AdminRestaurantWorkspace
    editing
    :username="auth.user?.username ?? ''"
    :logging-out="auth.loggingOut"
    :session-error="auth.error"
    @logout="auth.signOut"
  >
    <WindowManagement :id="id" :key="id" />
  </AdminRestaurantWorkspace>
</template>
