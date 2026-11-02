<script setup lang="ts">
import { ref } from 'vue'
/** 独立预览的矢量示意底图；所有像素位置均为设计坐标，禁止用于真实经纬度投影。 */
defineProps<{ ids: string[]; selected: string | null }>()
defineEmits<{ select: [id: string]; hover: [id: string]; leave: [] }>()
const zoom = ref(1)
const positions: Record<string, { x: number; y: number; score: string }> = {
  'demo-1': { x: 31, y: 47, score: '8.6' },
  'demo-2': { x: 69, y: 51, score: '8.2' },
  'demo-3': { x: 62, y: 77, score: '7.9' },
}
</script>
<template>
  <div class="illustrated-map">
    <div class="map-art" :style="{ transform: `scale(${zoom})` }">
      <svg
        class="street-map"
        viewBox="0 0 900 800"
        preserveAspectRatio="xMidYMid slice"
        role="img"
        aria-label="清水河校园风格示意地图，非真实地图"
      >
        <defs>
          <pattern
            id="map-blocks"
            width="140"
            height="120"
            patternUnits="userSpaceOnUse"
            patternTransform="rotate(-13)"
          >
            <rect width="140" height="120" fill="#f0eee6" />
            <path d="M0 6H140M6 0V120" stroke="#d5d4cb" stroke-width="16" />
            <path d="M0 6H140M6 0V120" stroke="#fffdf7" stroke-width="10" />
            <rect x="27" y="26" width="35" height="24" rx="2" fill="#e3dfd3" stroke="#d7d4ca" />
            <rect x="77" y="30" width="45" height="17" rx="2" fill="#e7e3d8" />
            <rect x="29" y="69" width="50" height="28" rx="3" fill="#e6e0d4" />
            <rect x="91" y="68" width="28" height="30" rx="3" fill="#e3e3d6" />
          </pattern>
        </defs>
        <rect width="900" height="800" fill="url(#map-blocks)" />
        <path d="M285 70 675 10 790 610 315 720 200 430Z" fill="#dce8cf" opacity=".8" />
        <path
          d="M785-30C680 120 850 170 790 320S740 530 820 660 820 790 780 850"
          fill="none"
          stroke="#d8e7ca"
          stroke-width="105"
        />
        <path
          d="M785-30C680 120 850 170 790 320S740 530 820 660 820 790 780 850"
          fill="none"
          stroke="#aad6e4"
          stroke-width="31"
        />
        <g fill="#cedfbc">
          <ellipse cx="330" cy="120" rx="52" ry="62" />
          <ellipse cx="530" cy="630" rx="91" ry="60" />
          <ellipse cx="646" cy="704" rx="90" ry="64" />
          <ellipse cx="216" cy="330" rx="49" ry="78" />
        </g>
        <g fill="#ece8de" stroke="#d9d7cd" stroke-width="2">
          <rect x="330" y="222" width="97" height="42" rx="3" />
          <rect x="510" y="180" width="92" height="65" rx="3" />
          <rect x="350" y="470" width="68" height="90" rx="3" />
          <rect x="510" y="462" width="80" height="45" rx="3" />
          <rect x="140" y="550" width="95" height="40" rx="3" />
        </g>
        <path
          d="M-30 270 930 90M-30 625 930 470M155-30 340 850M580-30 717 850"
          stroke="#d7d6cb"
          stroke-width="19"
          fill="none"
        />
        <path
          d="M-30 270 930 90M-30 625 930 470M155-30 340 850M580-30 717 850"
          stroke="#fffdf7"
          stroke-width="12"
          fill="none"
        />
        <g fill="#516b78" font-family="Microsoft YaHei,sans-serif" font-size="16">
          <text x="330" y="210">图书馆</text>
          <text x="515" y="170">体育馆</text>
          <text x="350" y="465">教学楼</text>
          <text x="135" y="540">学生生活区</text>
          <text x="550" y="705" fill="#6f8e61">清水河公园</text>
          <text x="450" y="562" transform="rotate(-10 450 562)">校园南路</text>
          <text x="183" y="205" transform="rotate(-10 183 205)">校园大道</text>
          <text x="780" y="382" transform="rotate(25 780 382)" fill="#629db8">清水河</text>
          <text x="305" y="381" font-size="29" font-weight="600" fill="#58748d">清水河校区</text>
        </g>
      </svg>
      <button
        v-for="id in ids"
        :key="id"
        class="score-pin"
        :class="{ active: id === selected }"
        :style="{ left: `${positions[id]!.x}%`, top: `${positions[id]!.y}%` }"
        :aria-label="`选中${positions[id]!.score}分示例餐厅`"
        :aria-pressed="id === selected"
        @click="$emit('select', id)"
        @mouseenter="$emit('hover', id)"
        @mouseleave="$emit('leave')"
        @focus="$emit('hover', id)"
        @blur="$emit('leave')"
      >
        {{ positions[id]!.score }}
      </button>
    </div>
    <div class="map-tools">
      <div>
        <button
          aria-label="放大示意地图"
          :disabled="zoom >= 1.6"
          @click="zoom = Math.min(1.6, zoom + 0.2)"
        >
          ＋</button
        ><button
          aria-label="缩小示意地图"
          :disabled="zoom <= 1"
          @click="zoom = Math.max(1, zoom - 0.2)"
        >
          −
        </button>
      </div>
      <button class="campus-reset" @click="zoom = 1"><span>⌖</span>回校区</button>
    </div>
    <span class="map-attribution">界面预览 · 非真实地图</span>
  </div>
</template>
<style scoped>
.illustrated-map {
  position: absolute;
  inset: 0;
  overflow: hidden;
  background: #e9ecdf;
}
.map-art {
  position: absolute;
  inset: 0;
  transition: transform 0.25s;
}
.street-map {
  width: 100%;
  height: 100%;
}
.score-pin {
  position: absolute;
  transform: translate(-50%, -100%);
  min-width: 38px;
  height: 30px;
  padding: 0 8px;
  border: 1px solid #d8dfdb;
  border-radius: 16px;
  background: white;
  color: #39473e;
  font-size: 12px;
  font-weight: 650;
  box-shadow: 0 1px 4px #25352b24;
  cursor: pointer;
}
/* 示意点位延续真实地图的选中语言，仍明确标注为非真实地图。 */
.score-pin.active {
  background: #f5a928;
  color: #33250e;
  box-shadow:
    0 0 0 2px #b67525,
    0 2px 6px #26342c22;
  z-index: 1;
}
.score-pin:focus-visible {
  outline: 3px solid #775213;
  outline-offset: 5px;
}
.map-tools {
  position: absolute;
  right: 18px;
  top: 18px;
  display: grid;
  gap: 8px;
}
.map-tools > div {
  display: grid;
  box-shadow: 0 1px 6px #1e2c2c1c;
  border-radius: 7px;
  overflow: hidden;
}
.map-tools button {
  border: 0;
  background: #fff;
  width: 43px;
  min-height: 43px;
  font-size: 21px;
  cursor: pointer;
  color: #485353;
}
.map-tools > div button + button {
  border-top: 1px solid #eee;
}
.map-tools .campus-reset {
  display: grid;
  place-items: center;
  padding: 5px;
  font-size: 10px;
  border-radius: 7px;
  box-shadow: 0 1px 6px #1e2c2c1c;
}
.campus-reset span {
  font-size: 24px;
}
.map-attribution {
  position: absolute;
  left: 12px;
  bottom: 8px;
  font-size: 10px;
  color: #647267;
  background: #ffffffc9;
  padding: 3px 6px;
  border-radius: 3px;
}
/* 手机底部卡片占用独立空间，点位始终落在可操作的地图区域。 */
@media (max-width: 700px) {
  .map-art {
    bottom: 185px;
  }
}
</style>
