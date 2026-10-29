<template>
  <section class="reader-settings" aria-label="阅读设置">
    <div class="lan-reset-row">
      <button
        v-if="!renaming"
        type="button"
        class="book-title-button"
        title="点击修改书名"
        @click="startRename"
      >
        {{ bookTitle }}
      </button>
      <input
        v-else
        ref="renameInput"
        v-model="renameDraft"
        class="book-title-input"
        type="text"
        maxlength="120"
        aria-label="书名"
        @keydown.enter.prevent="commitRename"
        @keydown.esc.prevent="cancelRename"
        @blur="commitRename"
      />
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
  </section>
</template>

<script setup lang="ts">
import { computed, nextTick, ref } from 'vue'
import API from '@api'
import { useBookStore } from '@/store'
import { defaultReadClickActions } from '@/config/readClickActions'

const store = useBookStore()
const bookTitle = computed(() => store.readingBook.name || '未命名')
const renaming = ref(false)
const renameDraft = ref('')
const renameInput = ref<HTMLInputElement | null>(null)

const startRename = async () => {
  if (renaming.value) return
  renameDraft.value = store.readingBook.name || ''
  renaming.value = true
  await nextTick()
  renameInput.value?.focus()
  renameInput.value?.select()
}

const cancelRename = () => {
  renaming.value = false
  renameDraft.value = ''
}

/**
 * 改书名。远程书库（局域网/WebDAV 目录）里 rename 会真改文件名并返回新的
 * bookUrl，所以改完必须按新地址重新载入；App 内置后端只更新书名，地址不变。
 */
const commitRename = async () => {
  if (!renaming.value) return
  const name = renameDraft.value.trim()
  const bookUrl = store.readingBook.bookUrl || ''
  renaming.value = false
  renameDraft.value = ''
  if (!name || !bookUrl || name === (store.readingBook.name || '')) return
  // 演示书籍是前端内置数据，改不了名字
  if (bookUrl.startsWith('demo:')) return
  const shelfBook = store.shelf.find(item => item.bookUrl === bookUrl)
  try {
    const response = await API.saveBook({ ...(shelfBook ?? store.readingBook), name })
    const { isSuccess, data, errorMsg } = response.data
    if (!isSuccess) {
      ElMessage.error(errorMsg || '修改书名失败')
      return
    }
    const newUrl = typeof data === 'string' && data.trim() ? data.trim() : bookUrl
    store.readingBook.name = name
    store.readingBook.bookUrl = newUrl
    if (shelfBook) {
      shelfBook.name = name
      shelfBook.bookUrl = newUrl
    }
    sessionStorage.setItem('bookName', name)
    sessionStorage.setItem('bookUrl', newUrl)
    sessionStorage.setItem(
      'chapterIndex',
      String(store.readingBook.chapterIndex || 0),
    )
    sessionStorage.setItem(
      'chapterPos',
      String(store.readingBook.chapterPos || 0),
    )
    ElMessage.success('书名已修改，正在重新载入')
    window.setTimeout(() => window.location.reload(), 300)
  } catch (error) {
    ElMessage.error('修改书名失败')
    console.error(error)
  }
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
.book-title-button {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  text-align: left;
  border: 0;
  border-bottom: 1px dashed transparent;
  background: transparent;
  color: inherit;
  font-size: 13px;
  font-weight: 600;
  padding: 4px 2px;
  cursor: pointer;
}
.book-title-button:hover {
  border-bottom-color: rgba(128, 128, 128, 0.5);
}
.book-title-input {
  flex: 1;
  min-width: 0;
  border: 1px solid #d64958;
  border-radius: 5px;
  background: transparent;
  color: inherit;
  font-size: 13px;
  font-weight: 600;
  padding: 3px 6px;
}
.reset-button {
  border: 0;
  background: transparent;
  color: #d64958;
  font-size: 11px;
  cursor: pointer;
}
input[type='range'] {
  width: 100%;
  accent-color: #d64958;
}
</style>

<style>
.lan-reset-row {
  display: flex;
  align-items: center;
  gap: 8px;
  justify-content: space-between;
}
</style>
