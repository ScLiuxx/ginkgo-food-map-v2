<script setup lang="ts">
import { RestaurantDetail } from '@/modules/restaurant'

defineProps<{ id: string }>()
</script>

<template><RestaurantDetail :id="id" /></template>
