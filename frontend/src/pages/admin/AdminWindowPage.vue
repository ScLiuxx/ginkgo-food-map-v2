<script setup lang="ts">
import { useLoginStore } from '@/modules/auth'
import { AdminRestaurantWorkspace } from '@/modules/admin'
import WindowManagement from '@/modules/admin/ui/WindowManagement.vue'
import WindowEditor from '@/modules/admin/ui/WindowEditor.vue'
defineProps<{ id: string; editing?: boolean; windowId?: string }>()
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
    <WindowEditor
      v-if="editing"
      :id="id"
      :key="`${id}:${windowId ?? 'new'}`"
      :window-id="windowId"
    />
    <WindowManagement v-else :id="id" :key="id" />
  </AdminRestaurantWorkspace>
</template>
