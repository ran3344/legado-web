<template>
  <section class="reader-settings" aria-label="阅读设置">
    <div class="settings-section">
      <span class="settings-label">主题</span>
      <div class="theme-list">
        <button
          v-for="(color, index) in themeColors"
          :key="index"
          class="theme-button"
          :class="{ selected: store.config.theme === index }"
          :style="{ background: color }"
          type="button"
          :aria-label="`主题 ${index + 1}`"
          @click="store.config.theme = index"
        />
      </div>
    </div>
    <div class="settings-section">
      <span class="settings-label">字体</span>
      <div class="font-list">
        <button
          v-for="(font, index) in fonts"
          :key="font"
          class="font-button"
          :class="{ selected: store.config.font === index }"
          type="button"
          @click="store.config.font = index"
        >
          {{ font }}
        </button>
      </div>
    </div>
    <label class="range-setting">
      <span
        >字号 <strong>{{ store.config.fontSize }}px</strong></span
      >
      <input
        v-model.number="store.config.fontSize"
        type="range"
        min="12"
        max="36"
        step="1"
      />
    </label>
    <label class="range-setting">
      <span
        >行距 <strong>{{ store.config.spacing.line.toFixed(1) }}</strong></span
      >
      <input
        v-model.number="store.config.spacing.line"
        type="range"
        min="0.3"
        max="1.8"
        step="0.1"
      />
    </label>
    <label class="toggle-setting">
      <span>
        <strong>智能分段</strong>
        <small>按标点和对话重新整理异常段落</small>
      </span>
      <input v-model="store.config.reSegment" type="checkbox" />
    </label>
    <div class="settings-section">
      <span class="settings-label">阅读方式</span>
      <div class="mode-list">
        <button
          class="mode-button"
          :class="{ selected: store.config.readMode === 'scroll' }"
          type="button"
          @click="store.config.readMode = 'scroll'"
        >
          连续滚动
        </button>
        <button
          class="mode-button"
          :class="{ selected: store.config.readMode === 'horizontal' }"
          type="button"
          @click="store.config.readMode = 'horizontal'"
        >
          左右翻页
        </button>
        <button
          class="mode-button"
          :class="{ selected: store.config.readMode === 'click' }"
          type="button"
          @click="store.config.readMode = 'click'"
        >
          点击翻页
        </button>
      </div>
    </div>
    <div v-if="store.config.readMode === 'click'" class="click-config">
      <div class="click-config-heading">
        <span class="settings-label">点击区域</span>
        <button type="button" class="reset-button" @click="resetClickActions">
          恢复默认
        </button>
      </div>
      <div class="click-grid" aria-label="九宫格点击区域">
        <label v-for="area in clickAreas" :key="area.key" class="click-cell">
          <span>{{ area.label }}</span>
          <select v-model="store.config.clickActions[area.key]">
            <option
              v-for="option in clickActionOptions"
              :key="option.value"
              :value="option.value"
            >
              {{ option.label }}
            </option>
          </select>
        </label>
      </div>
    </div>
  </section>
</template>

<script setup lang="ts">
import { useBookStore } from '@/store'
import {
  defaultReadClickActions,
  readClickActionOptions,
  readClickAreas,
} from '@/config/readClickActions'

const store = useBookStore()
const fonts = ['雅黑', '宋体', '楷书']
const themeColors = [
  '#f8f3e8',
  '#f3eacc',
  '#e8f1e8',
  '#e5f0f4',
  '#f5e5e5',
  '#eeeeee',
  '#17191c',
]
const clickAreas = readClickAreas
const clickActionOptions = readClickActionOptions
const resetClickActions = () => {
  store.config.clickActions = { ...defaultReadClickActions }
}
</script>

<style scoped>
.reader-settings {
  display: grid;
  gap: 18px;
  width: 100%;
  box-sizing: border-box;
  padding: 4px;
  color: var(--settings-fg);
}
.settings-section,
.range-setting {
  display: grid;
  gap: 9px;
}
.settings-label,
.range-setting > span {
  color: var(--settings-muted);
  font-size: 12px;
}
.toggle-setting {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}
.toggle-setting > span {
  display: grid;
  gap: 4px;
}
.toggle-setting strong {
  color: var(--settings-fg);
  font-size: 13px;
  font-weight: 500;
}
.toggle-setting small {
  color: var(--settings-muted);
  font-size: 11px;
}
.toggle-setting input {
  width: 38px;
  height: 22px;
  accent-color: #d64958;
}
.range-setting > span {
  display: flex;
  justify-content: space-between;
}
.range-setting strong {
  color: var(--settings-fg);
  font-weight: 500;
}
.theme-list,
.font-list,
.mode-list {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.theme-button {
  width: 28px;
  height: 28px;
  border: 2px solid rgba(128, 128, 128, 0.25);
  border-radius: 50%;
  cursor: pointer;
}
.theme-button.selected {
  outline: 2px solid #e45b67;
  outline-offset: 2px;
}
.font-button,
.mode-button {
  border: 1px solid rgba(128, 128, 128, 0.3);
  border-radius: 6px;
  background: transparent;
  color: inherit;
  padding: 7px 10px;
  cursor: pointer;
}
.font-button.selected,
.mode-button.selected {
  border-color: #e45b67;
  color: #d64958;
}
.click-config {
  display: grid;
  gap: 10px;
  padding-top: 2px;
}
.click-config-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}
.reset-button {
  border: 0;
  background: transparent;
  color: #d64958;
  font-size: 11px;
  cursor: pointer;
}
.click-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 5px;
  padding: 5px;
  border: 1px solid rgba(128, 128, 128, 0.26);
  border-radius: 7px;
}
.click-cell {
  display: grid;
  gap: 5px;
  min-width: 0;
  padding: 7px 5px;
  border: 1px solid rgba(128, 128, 128, 0.22);
  border-radius: 5px;
  background: color-mix(in srgb, var(--settings-fg) 4%, transparent);
}
.click-cell > span {
  color: var(--settings-muted);
  font-size: 10px;
  text-align: center;
}
.click-cell select {
  width: 100%;
  min-width: 0;
  border: 1px solid rgba(128, 128, 128, 0.26);
  border-radius: 4px;
  padding: 4px 2px;
  background: transparent;
  color: inherit;
  font-size: 10px;
}
input[type='range'] {
  width: 100%;
  accent-color: #d64958;
}
</style>
