<script setup lang="ts">
import { useLoginStore } from '@/modules/auth'
import { AdminRestaurantWorkspace } from '@/modules/admin'
import { RestaurantCorrection } from '@/modules/restaurant'

defineProps<{ editing?: boolean; id?: string }>()
const auth = useLoginStore()
</script>

<template>
  <AdminRestaurantWorkspace
    :editing="editing"
    :username="auth.user?.username ?? ''"
    :logging-out="auth.loggingOut"
    :session-error="auth.error"
    @logout="auth.signOut"
  >
    <RestaurantCorrection v-if="editing" :id="id" :key="id ?? 'new'" :submission="!id" admin />
  </AdminRestaurantWorkspace>
</template>
