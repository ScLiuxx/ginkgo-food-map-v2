<script setup lang="ts">
import { nextTick, ref, useTemplateRef } from 'vue'
import type { ReviewCategory } from '../api/restaurantRequestsApi'

/** 输入直接更新草稿，点击底栏不会丢失最后一个字符；Escape恢复进入编辑前的值。 */
const props = defineProps<{
  field: string
  value: unknown
  text: string
  categories: ReviewCategory[]
  disabled: boolean
}>()
const emit = defineEmits<{ change: [value: unknown] }>()
const editing = ref(false),
  original = ref<unknown>(),
  input = useTemplateRef<globalThis.HTMLInputElement | globalThis.HTMLSelectElement>('input')
async function start() {
  if (props.disabled) return
  original.value = Array.isArray(props.value) ? [...props.value] : props.value
  editing.value = true
  await nextTick()
  input.value?.focus()
}
function update(event: globalThis.Event) {
  const value = (event.target as globalThis.HTMLInputElement).value
  if (['latitude', 'longitude', 'pricePerPerson'].includes(props.field))
    emit('change', value === '' ? null : Number(value))
  else if (props.field === 'locationConfirmed') emit('change', value === 'true')
  else if (['supportsDineIn', 'supportsDelivery'].includes(props.field))
    emit('change', value === 'null' ? null : value === 'true')
  else if (props.field === 'tags')
    emit(
      'change',
      value
        .split(/[·,，\n]/u)
        .map((tag) => tag.trim())
        .filter(Boolean),
    )
  else emit('change', value)
}
function cancel() {
  emit('change', original.value)
  editing.value = false
}
</script>

<template>
  <button
    v-if="!editing"
    class="editable-value"
    type="button"
    :disabled="disabled"
    :aria-label="`编辑${text}`"
    @click="start"
  >
    <span>{{ text }}</span
    ><span class="edit-pencil" aria-hidden="true">✎</span>
  </button>
  <select
    v-else-if="
      [
        'categoryCode',
        'priceKind',
        'businessStatus',
        'locationConfirmed',
        'restaurantType',
        'supportsDineIn',
        'supportsDelivery',
      ].includes(field)
    "
    ref="input"
    class="inline-input"
    :aria-label="field"
    :value="String(value)"
    :disabled="disabled"
    @change="update"
    @blur="editing = false"
    @keydown.esc="cancel"
  >
    <template v-if="field === 'categoryCode'">
      <option v-if="!categories.some((entry) => entry.code === value)" :value="String(value)">
        {{ text }}
      </option>
      <option
        v-for="category in categories"
        :key="category.code"
        :value="category.code"
        :disabled="!category.enabled && category.code !== value"
      >
        {{ category.name }}
      </option>
    </template>
    <template v-else-if="field === 'priceKind'"
      ><option value="UNKNOWN">未知</option>
      <option value="EXACT">填报金额</option>
      <option value="ESTIMATE">估值</option></template
    >
    <template v-else-if="field === 'businessStatus'"
      ><option value="OPERATING">正常经营</option>
      <option value="CLOSED">停业</option></template
    >
    <template v-else-if="field === 'restaurantType'"
      ><option value="REGULAR">普通餐厅</option>
      <option value="CANTEEN">食堂</option></template
    >
    <template v-else-if="['supportsDineIn', 'supportsDelivery'].includes(field)"
      ><option value="null">未知</option>
      <option value="true">支持</option>
      <option value="false">不支持</option></template
    >
    <template v-else
      ><option value="true">已确认</option>
      <option value="false">未确认</option></template
    >
  </select>
  <input
    v-else
    ref="input"
    class="inline-input"
    :aria-label="field"
    :disabled="disabled"
    :type="['latitude', 'longitude', 'pricePerPerson'].includes(field) ? 'number' : 'text'"
    :step="field === 'pricePerPerson' ? 1 : 'any'"
    :value="Array.isArray(value) ? value.join(' · ') : (value ?? '')"
    @input="update"
    @blur="editing = false"
    @keydown.enter="editing = false"
    @keydown.esc="cancel"
  />
</template>

<style scoped>
.editable-value {
  display: inline-flex;
  align-items: baseline;
  gap: 8px;
  width: 100%;
  text-align: left;
  color: inherit;
  font: inherit;
  cursor: text;
  background: none;
  border: 0;
  padding: 3px 0;
  overflow-wrap: anywhere;
}
.editable-value:disabled {
  cursor: default;
}
.edit-pencil {
  opacity: 0;
  color: #c3a784;
  transition: opacity 0.16s ease;
}
.editable-value:hover .edit-pencil,
.editable-value:focus-visible .edit-pencil {
  opacity: 1;
}
.inline-input {
  width: 100%;
  min-width: 0;
  border: 1px solid #dfa04d;
  border-radius: 4px;
  padding: 6px;
  font: inherit;
  color: inherit;
  background: #fffdf8;
}
@media (hover: none) {
  .edit-pencil {
    opacity: 1;
  }
}
@media (prefers-reduced-motion: reduce) {
  .edit-pencil {
    transition: none;
  }
}
</style>
