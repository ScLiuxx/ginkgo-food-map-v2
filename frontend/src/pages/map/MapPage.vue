<script setup lang="ts">
import { MapWorkspace } from '@/modules/map'
withDefaults(defineProps<{ preview?: boolean }>(), { preview: false })
</script>
<template><MapWorkspace :key="preview ? 'preview' : 'live'" :preview="preview" /></template>
