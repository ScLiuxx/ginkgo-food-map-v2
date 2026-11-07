<script setup lang="ts">
import { computed } from 'vue'

/** 历史快照只读展示，不套用现行编辑校验；未知值保持未知，文字通过插值转义。 */
const props = defineProps<{ values: Record<string, unknown> }>()
const text = (value: unknown) => (typeof value === 'string' && value.trim() ? value : '未填写')
const support = (value: unknown) => (value === true ? '支持' : value === false ? '不支持' : '未知')
const type = computed(() =>
  props.values.restaurantType === 'CANTEEN'
    ? '食堂'
    : props.values.restaurantType === 'REGULAR'
      ? '普通餐厅'
      : '未记录',
)
const price = computed(() => {
  const value = props.values
  return typeof value.pricePerPerson === 'number' && value.priceKind !== 'UNKNOWN'
    ? `${value.priceKind === 'ESTIMATE' ? '约 ' : ''}¥${value.pricePerPerson} / 人`
    : '未知'
})
const tags = computed(() =>
  Array.isArray(props.values.tags)
    ? props.values.tags.filter((tag): tag is string => typeof tag === 'string').join('、')
    : '',
)
</script>
<template>
  <dl class="contribution-snapshot">
    <dt>店铺名称</dt>
    <dd>{{ text(values.name) }}</dd>
    <dt>类型 / 分类</dt>
    <dd>
      {{ type
      }}<template v-if="values.restaurantType !== 'CANTEEN'">
        · {{ text(values.categoryName || values.categoryCode) }}</template
      >
    </dd>
    <dt>人均消费</dt>
    <dd>{{ price }}</dd>
    <dt>店铺地址</dt>
    <dd>{{ text(values.address) }}</dd>
    <dt>营业状态</dt>
    <dd>
      {{
        values.businessStatus === 'OPERATING'
          ? '营业中'
          : values.businessStatus === 'CLOSED'
            ? '已停业'
            : '未知'
      }}
    </dd>
    <dt>堂食 / 外卖</dt>
    <dd>{{ support(values.supportsDineIn) }} / {{ support(values.supportsDelivery) }}</dd>
    <dt>地图位置</dt>
    <dd>
      {{
        typeof values.latitude === 'number' && typeof values.longitude === 'number'
          ? `${values.latitude}, ${values.longitude}`
          : '未记录'
      }}<template v-if="values.locationConfirmed === true">（提交时已确认）</template>
    </dd>
    <dt>标签</dt>
    <dd>{{ tags || '未填写' }}</dd>
  </dl>
</template>
<style scoped>
.contribution-snapshot {
  display: grid;
  grid-template-columns: 88px minmax(0, 1fr);
  gap: 13px 12px;
  margin: 0;
  font-size: 13px;
  line-height: 1.7;
}
dt {
  color: #8c959e;
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
