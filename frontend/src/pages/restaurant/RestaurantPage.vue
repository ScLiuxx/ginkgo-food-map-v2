<script setup lang="ts">
import { RestaurantDetail } from '@/modules/restaurant'

defineProps<{ id: string; window?: boolean }>()
</script>

<template><RestaurantDetail :id="id" :window="window" /></template>
