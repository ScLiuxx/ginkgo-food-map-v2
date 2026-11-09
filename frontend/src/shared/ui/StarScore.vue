<script setup lang="ts">
import { formatRating } from '@/shared/lib/formatRating'
import { computed } from 'vue'
/** 只转换展示：后端0–10分按比例显示五星，未知/异常不显示成0星。 */
const props = defineProps<{ score: number | null }>()
const valid = computed(
  () =>
    props.score !== null && Number.isFinite(props.score) && props.score >= 0 && props.score <= 10,
)
</script>

<template>
  <span class="star-score" :class="{ unrated: !valid }">
    <template v-if="valid">
      <span class="star-symbols" aria-hidden="true"
        >★★★★★<span :style="{ width: `${score! * 10}%` }">★★★★★</span></span
      >
      <strong>{{ formatRating(score) }}</strong
      ><span class="sr-only"> / 5 星</span>
    </template>
    <span v-else>待评级</span>
  </span>
</template>

<style scoped>
.star-score {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  color: #f08a28;
  white-space: nowrap;
  font-variant-numeric: tabular-nums;
}
.star-symbols {
  position: relative;
  color: #e4e6e9;
  font-size: 19px;
  line-height: 1;
  letter-spacing: 1px;
}
.star-symbols > span {
  position: absolute;
  inset: 0 auto 0 0;
  overflow: hidden;
  color: #f08a28;
}
.star-score strong {
  font-size: inherit;
  font-weight: 650;
}
.unrated {
  color: #8a929c;
  font-size: 14px;
  font-weight: 400;
}
.sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip-path: inset(50%);
}
</style>
