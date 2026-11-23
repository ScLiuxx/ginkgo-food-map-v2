<script setup lang="ts">
import { computed } from 'vue'
import { contributionFields } from '../model/myContributionFields'

/** 历史快照只读展示，不套用现行编辑校验；未知值保持未知，文字通过插值转义。 */
const props = defineProps<{ values: Record<string, unknown> }>()
const fields = computed(() => contributionFields(props.values))
// 店名已在详情标题显示，分类和营业状态压缩为标签；无标签时省去空行。
const primary = computed(() => {
  const tags = props.values.tags
  const hasTags = Array.isArray(tags) && tags.some((tag) => typeof tag === 'string' && tag.trim())
  return fields.value.filter(
    (field) =>
      ['address', 'price', 'dining'].includes(field.key) || (field.key === 'tags' && hasTags),
  )
})
const chips = computed(() =>
  fields.value.filter((field) => ['category', 'businessStatus'].includes(field.key)),
)
</script>
<template>
  <div class="contribution-property-chips">
    <span
      v-for="field in chips"
      :key="field.key"
      :class="{
        'is-operating': field.key === 'businessStatus' && values.businessStatus === 'OPERATING',
      }"
      >{{ field.text }}</span
    >
  </div>
  <dl class="contribution-snapshot">
    <template v-for="field in primary" :key="field.key"
      ><dt>{{ field.label }}</dt>
      <dd>{{ field.text }}</dd></template
    >
  </dl>
</template>
<style scoped>
.contribution-snapshot {
  display: grid;
  grid-template-columns: 72px minmax(0, 1fr);
  gap: 9px 10px;
  margin: 0;
  font-size: 12px;
  line-height: 1.7;
}
dt {
  color: #74818c;
}
.contribution-property-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 7px;
  margin-bottom: 15px;
}
.contribution-property-chips span {
  background: #f3f5f7;
  color: #65737e;
  font-size: 11px;
  padding: 3px 8px;
  border-radius: 5px;
}
.contribution-property-chips .is-operating {
  background: #eff7f1;
  color: #447759;
}
dd {
  margin: 0;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
@media (max-width: 380px) {
  .contribution-snapshot {
    grid-template-columns: 70px minmax(0, 1fr);
    gap: 12px 8px;
  }
}
</style>
