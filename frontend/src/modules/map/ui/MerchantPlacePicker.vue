<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { searchAMapPlaces, type AMapPlace } from '../sdk/places'
import { toErrorMessage } from '@/shared/lib/toErrorMessage'

/** 只在用户主动搜索后读取高德门店；选择结果才向业务表单提供位置。 */
const props = defineProps<{
  disabled?: boolean
  invalid?: boolean
  nameError?: string
}>()
const emit = defineEmits<{ select: [place: AMapPlace] }>()
// 名称即搜索词，由父表单统一保存，避免两个输入框的值相互脱节。
const keyword = defineModel<string>({ required: true })
const places = ref<AMapPlace[]>([])
const searching = ref(false)
const searched = ref(false)
const error = ref('')
const page = ref(1)
const total = ref(0)
let controller: globalThis.AbortController | undefined
let revision = 0

/** SDK请求不一定能真正取消；版本号与取消信号共同隔离旧关键词和迟到回调。 */
function cancel() {
  revision++
  controller?.abort()
  searching.value = false
}
watch(keyword, () => {
  cancel()
  places.value = []
  searched.value = false
  error.value = ''
  page.value = 1
  total.value = 0
})
watch(
  () => props.disabled,
  (disabled) => {
    if (disabled) cancel()
  },
)
onBeforeUnmount(cancel)

async function search(nextPage = 1) {
  if (props.disabled) return
  cancel()
  const query = keyword.value.trim()
  places.value = []
  searched.value = false
  error.value = ''
  total.value = 0
  if (!query || Array.from(query).length > 100) {
    error.value = '请输入1–100字的门店名称，可加上街道或分店名。'
    return
  }
  const ticket = revision
  const request = new globalThis.AbortController()
  controller = request
  searching.value = true
  page.value = nextPage
  try {
    const result = await searchAMapPlaces(query, nextPage, request.signal)
    if (ticket !== revision || request.signal.aborted) return
    places.value = result.places
    total.value = result.total
    searched.value = true
  } catch (reason) {
    if (ticket === revision && !request.signal.aborted)
      error.value = toErrorMessage(reason)
  } finally {
    if (ticket === revision) searching.value = false
  }
}

function choose(place: AMapPlace) {
  if (props.disabled || searching.value) return
  // 与商户业务位置范围保持一致；异常结果不能替换表单中已有的有效位置。
  if (
    place.latitude < 3 ||
    place.latitude > 54 ||
    place.longitude < 73 ||
    place.longitude > 136
  ) {
    error.value = '这家门店的位置不在支持范围内，请重新选择。'
    return
  }
  emit('select', place)
  places.value = []
  searched.value = false
  total.value = 0
}
</script>

<template>
  <div class="merchant-picker" :aria-busy="searching">
    <label
      >商户名称
      <span class="search-row">
        <input
          v-model="keyword"
          type="search"
          maxlength="40"
          :disabled="disabled"
          :aria-invalid="invalid"
          placeholder="输入商户名称，搜索高德门店"
          @keydown.enter.prevent.stop="search()"
        />
        <button
          type="button"
          :disabled="disabled || searching"
          @click="search()"
        >
          {{ searching ? '搜索中…' : '搜索门店' }}
        </button>
      </span>
    </label>
    <small :class="{ 'name-error': invalid }">{{
      props.nameError || `${Array.from(keyword.trim()).length}/40 字`
    }}</small>
    <p class="hint">
      数据来自高德地图，请核对分店和地址后选择。选择后自动填写店名、地址及位置。
    </p>
    <p v-if="error" role="alert">{{ error }}</p>
    <p v-else-if="searching" role="status">正在搜索高德门店…</p>
    <p v-else-if="searched && !places.length" role="status">
      没有找到门店，请换个关键词。现有资料会保留。
    </p>
    <ul v-if="places.length" aria-label="高德门店搜索结果">
      <li v-for="place in places" :key="place.id">
        <div>
          <strong>{{ place.name }}</strong>
          <p>{{ place.district }} {{ place.address || '地址未提供' }}</p>
        </div>
        <button
          type="button"
          :disabled="disabled"
          :aria-label="`选择${place.name}`"
          @click="choose(place)"
        >
          选择这家
        </button>
      </li>
    </ul>
    <div v-if="searched && (page > 1 || total > 10)" class="pages">
      <button
        type="button"
        :disabled="disabled || searching || page <= 1"
        @click="search(page - 1)"
      >
        上一页
      </button>
      <span>第{{ page }}页</span>
      <button
        type="button"
        :disabled="disabled || searching || page >= 100 || page * 10 >= total"
        @click="search(page + 1)"
      >
        下一页
      </button>
    </div>
  </div>
</template>

<style scoped>
.merchant-picker {
  min-width: 0;
  margin-bottom: 20px;
}
.name-error {
  color: #b33224;
}
small {
  display: block;
  margin-top: 6px;
  color: #777;
}
label {
  display: grid;
  gap: 8px;
}
.search-row {
  display: flex;
  gap: 8px;
}
input {
  min-width: 0;
  flex: 1;
  width: 100%;
  padding: 10px;
  border: 1px solid #dedede;
  border-radius: 7px;
  font: inherit;
}
button {
  flex-shrink: 0;
  padding: 9px 12px;
  border: 1px solid #e6af66;
  border-radius: 7px;
  background: #fff8ed;
  color: #9c601b;
  font: inherit;
  cursor: pointer;
}
button:disabled {
  opacity: 0.5;
  cursor: default;
}
.hint,
li p {
  color: #777;
  font-size: 13px;
  line-height: 1.7;
}
[role='alert'] {
  color: #b33224;
}
ul {
  list-style: none;
  padding: 0;
  margin: 12px 0;
  max-height: 360px;
  overflow-y: auto;
}
li {
  display: flex;
  align-items: center;
  gap: 12px;
  justify-content: space-between;
  padding: 12px 0;
  border-bottom: 1px solid #eee;
}
li div {
  min-width: 0;
  overflow-wrap: anywhere;
}
li p {
  margin: 5px 0 0;
}
.pages {
  display: flex;
  justify-content: center;
  align-items: center;
  gap: 12px;
}
</style>
