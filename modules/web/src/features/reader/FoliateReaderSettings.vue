<template>
  <section class="reader-settings" aria-label="阅读设置">
    <div class="lan-reset-row">
      <button type="button" class="reset-button" @click="resetAllSettings">
        恢复默认
      </button>
    </div>
    <details class="settings-group">
      <summary>基础样式</summary>
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
        <button
          class="font-button"
          :class="{ selected: store.config.font === -1 }"
          type="button"
          @click="store.config.font = -1"
        >
          自定义
        </button>
      </div>
        <label
          v-if="store.config.font === -1"
          class="range-setting"
        >
          <span>字体名称（系统已安装）</span>
          <input
            v-model.trim="store.config.customFontName"
            type="text"
            placeholder="如：霞鹜文楷 GB Screen"
          />
        </label>
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
        @pointerdown="setDragging(true)"
        @pointerup="setDragging(false)"
        @pointercancel="setDragging(false)"
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
        @pointerdown="setDragging(true)"
        @pointerup="setDragging(false)"
        @pointercancel="setDragging(false)"
      />
    </label>
    </details>
    <details class="settings-group">
      <summary>版式与页边距</summary>
    <div class="settings-section">
      <span class="settings-label">页边距</span>
      <label class="range-setting">
        <span
          >左右 <strong>{{ store.config.margin.left }}px</strong></span
        >
        <input
          :value="store.config.margin.left"
          type="range"
          min="0"
          max="64"
          step="2"
          @input="setMarginHorizontal($event)"
          @pointerdown="setDragging(true)"
          @pointerup="setDragging(false)"
          @pointercancel="setDragging(false)"
        />
      </label>
    </div>
    <label class="toggle-setting">
      <span>
        <strong>智能分段</strong>
        <small>按标点和对话重新整理异常段落</small>
      </span>
      <input v-model="store.config.reSegment" type="checkbox" />
    </label>
    </details>
    <details class="settings-group">
      <summary>阅读方式</summary>
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
    </details>
    <div v-if="store.config.readMode === 'click'" class="click-config">
      <div class="click-config-heading">
        <span class="settings-label">点击区域</span>
        <button type="button" class="mode-button" @click="clickAreaOpen = true">
          配置点击区域
        </button>
      </div>
    </div>
    <Teleport to="body">
      <div
        v-if="clickAreaOpen"
        class="lan-click-overlay"
        @click.self="clickAreaOpen = false"
      >
        <div class="lan-click-modal">
          <div class="lan-click-head">
            <strong>点击区域</strong>
            <button
              type="button"
              class="lan-click-close"
              @click="clickAreaOpen = false"
            >
              ✕
            </button>
          </div>
          <p class="click-hint">点击屏幕对应区域时显示或隐藏菜单</p>
          <div class="click-grid" aria-label="九宫格点击区域">
            <label
              v-for="area in clickAreas"
              :key="area.key"
              class="click-cell"
            >
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
          <div class="lan-click-foot">
            <button type="button" class="reset-button" @click="resetClickActions">
              恢复默认
            </button>
          </div>
        </div>
      </div>
    </Teleport>
  </section>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useBookStore } from '@/store'
import {
  defaultReadClickActions,
  readClickActionOptions,
  readClickAreas,
} from '@/config/readClickActions'

const store = useBookStore()
const clickAreaOpen = ref(false)
function resetClickActions() {
  store.config.clickActions = { ...defaultReadClickActions }
}
function resetAllSettings() {
  Object.assign(store.config, {
    theme: 0,
    font: 0,
    fontSize: 18,
    readWidth: 800,
    reSegment: false,
    customFontName: '',
    readMode: 'scroll',
    spacing: { paragraph: 1, line: 0.8, letter: 0 },
    margin: { top: 16, right: 16, bottom: 16, left: 16 },
  })
  store.config.clickActions = { ...defaultReadClickActions }
}
const fonts = ['雅黑', '宋体', '楷书', '仿宋']

function setDragging(on: boolean) {
  document.querySelector('.settings-panel')?.classList.toggle('lan-dragging', on)
}
function setMarginHorizontal(e: Event) {
  const v = Number((e.target as HTMLInputElement).value) || 0
  store.config.margin.left = v
  store.config.margin.right = v
}
const themeColors = [
  '#f8f3e8',
  '#f3eacc',
  '#e8f1e8',
  '#e5f0f4',
  '#f5e5e5',
  '#eeeeee',
  '#17191c',
  '#E6E6DD',
]
const clickAreas = readClickAreas
const clickActionOptions = readClickActionOptions
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
.settings-group {
  border: 1px solid rgba(128, 128, 128, 0.22);
  border-radius: 10px;
  padding: 10px 12px 12px;
}
.settings-group > summary {
  cursor: pointer;
  font-size: 13px;
  font-weight: 600;
  user-select: none;
  padding: 2px 0 8px;
  opacity: 0.85;
}
.settings-group[open] > summary {
  padding-bottom: 10px;
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
.click-hint {
  margin: 0;
  color: var(--settings-muted);
  font-size: 11px;
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

<style>
.lan-reset-row {
  display: flex;
  justify-content: flex-end;
}
.lan-click-overlay {
  position: fixed;
  inset: 0;
  z-index: 60;
  background: rgba(0, 0, 0, 0.35);
  display: grid;
  place-items: center;
  padding: 16px;
}
.lan-click-modal {
  width: min(360px, 100%);
  max-height: 80vh;
  overflow: auto;
  border-radius: 12px;
  background: var(--page-bg, #fff);
  color: var(--page-fg, #222);
  box-shadow: 0 12px 40px rgba(0, 0, 0, 0.25);
  padding: 14px;
}
.lan-click-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 6px;
}
.lan-click-close {
  border: 0;
  background: transparent;
  color: inherit;
  font-size: 14px;
  cursor: pointer;
  padding: 2px 6px;
}
.lan-click-foot {
  display: flex;
  justify-content: flex-end;
  margin-top: 10px;
}
</style>
