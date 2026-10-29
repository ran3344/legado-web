<template>
  <main
    class="reader-page"
    :class="{ night: store.config.theme === 6 }"
    :style="pageStyle"
  >
    <header
      class="reader-header"
      :class="{ visible: menuVisible }"
      @click.self="hideReaderMenu"
    >
      <button
        class="reader-button"
        type="button"
        title="返回书架"
        @click="toShelf"
      >
        <ArrowLeft /><span class="header-title">{{ book.name }}</span>
      </button>
      <div class="reader-tools">
        <button
          class="reader-button"
          type="button"
          title="阅读设置"
          @click="settingsVisible = !settingsVisible"
        >
          <Setting /></button
        ><button
          class="reader-button"
          type="button"
          :title="isFullscreen ? '退出全屏' : '全屏阅读'"
          :aria-label="isFullscreen ? '退出全屏' : '全屏阅读'"
          @click="toggleFullscreen"
        >
          <FullScreen /></button
        ><button
          class="reader-button danger"
          type="button"
          title="删除书籍"
          @click="deleteBook"
        >
          <Delete />
        </button>
      </div>
    </header>
    <aside v-if="catalogVisible" class="catalog-panel">
      <div class="panel-heading">
        <strong>目录</strong
        ><button
          class="reader-button"
          type="button"
          @click="catalogVisible = false"
        >
          <Close />
        </button>
      </div>
      <div v-if="catalogNavigationError" class="catalog-error">
        <span>{{ catalogNavigationError }}</span>
        <button type="button" @click="retryCatalogNavigation">重试</button>
      </div>
      <div
        ref="catalogScroll"
        class="catalog-list"
        @scroll="handleCatalogScroll"
      >
        <div
          class="catalog-spacer"
          :style="{ height: `${catalogTopSpacer}px` }"
        />
        <button
          v-for="item in virtualCatalog"
          :key="item.chapter.url || item.index"
          class="catalog-item"
          :class="{ selected: item.index === currentIndex }"
          type="button"
          @click="goChapter(item.index)"
        >
          {{ item.chapter.title }}
        </button>
        <div
          class="catalog-spacer"
          :style="{ height: `${catalogBottomSpacer}px` }"
        />
      </div>
    </aside>
    <div
      v-if="catalogVisible"
      class="catalog-backdrop"
      aria-hidden="true"
      @click="catalogVisible = false"
    />
    <div
      v-if="settingsVisible"
      class="settings-backdrop"
      aria-hidden="true"
      @click="settingsVisible = false"
    />
    <aside v-if="settingsVisible" class="settings-panel">
      <FoliateReaderSettings />
    </aside>
    <section ref="readerStage" class="reader-stage" @click="handleStageClick">
      <foliate-view ref="viewElement" class="foliate-view" />
      <div
        v-if="store.config.readMode === 'click'"
        class="click-layer"
        @touchstart="handleClickModeTouchStart"
        @touchmove="handleClickModeTouchMove"
        @touchend="handleClickModeTouchEnd"
        aria-label="九宫格点击区域"
      >
        <button
          v-for="area in readClickAreas"
          :key="area.key"
          class="click-cell"
          type="button"
          :aria-label="area.label"
          @pointerdown.stop
          @click.stop="
            handleClickCellClick($event, store.config.clickActions[area.key])
          "
        />
      </div>
      <button
        v-if="store.config.readMode === 'click' && !menuVisible"
        class="reader-menu-edge reader-menu-edge-top"
        type="button"
        aria-label="显示阅读菜单"
        @click.stop="toggleReaderMenu"
      />
      <button
        v-if="store.config.readMode === 'click' && !menuVisible"
        class="reader-menu-edge reader-menu-edge-bottom"
        type="button"
        aria-label="显示阅读菜单"
        @click.stop="toggleReaderMenu"
      />
      <div v-if="errorMessage" class="reader-error">
        <p>{{ errorMessage }}</p>
        <button type="button" @click="toShelf">返回书架</button>
      </div>
      <div v-if="loading" class="reader-loading">正在打开章节...</div>
    </section>
    <footer
      class="reader-footer"
      :class="{ visible: menuVisible }"
      @click.self="hideReaderMenu"
    >
      <button
        class="reader-button"
        type="button"
        title="目录"
        @click="catalogVisible = !catalogVisible"
      >
        <Memo />
      </button>
      <button
        class="reader-button"
        type="button"
        title="上一章"
        @click="previousChapter"
      >
        <ArrowLeft />
      </button>
      <div class="progress-control">
        <input
          v-model.number="progressDraft"
          type="range"
          min="0"
          max="100"
          step="0.1"
          aria-label="当前章节进度"
          @pointerup="commitSeek"
          @change="commitSeek"
        /><span
          >{{ chapterPageInfo.current }} / {{ chapterPageInfo.total }}</span
        >
      </div>
      <button
        class="reader-button"
        type="button"
        title="下一章"
        @click="nextChapter"
      >
        <ArrowRight />
      </button>
      <button
        class="reader-button"
        type="button"
        title="目录"
        @click="catalogVisible = !catalogVisible"
      >
        <Memo />
      </button>
    </footer>
    <div class="lan-scrollbar" :class="{ 'lan-scrolling': lanScrolling }" aria-hidden="true">
      <div class="lan-thumb" :style="lanThumbStyle" />
    </div>
    <div
      v-if="catalog.length && !loading && !errorMessage"
      class="book-progress"
      :class="{ 'above-footer': menuVisible, 'lan-visible': lanScrolling }"
      aria-label="全书阅读进度"
    >
      全书 {{ bookProgressText }}
    </div>
  </main>
</template>

<script setup lang="ts">
import {
  ArrowLeft,
  ArrowRight,
  Memo,
  Setting,
  Delete,
  Close,
  FullScreen,
} from '@element-plus/icons-vue'
import API from '@api'
import type { Book, BookChapter } from '@/book'
import { useBookStore } from '@/store'
import { getReadClickArea, readClickAreas } from '@/config/readClickActions'
import type { ReadClickAction } from '@/web'
import {
  createLegadoFoliateBook,
  createContinuousLegadoFoliateBook,
  type LegadoFoliateBook,
  type ContinuousLegadoFoliateBook,
} from '@/features/reader/legadoFoliateBook'
import {
  getChapterProgressPosition,
  getBookFraction,
  getChapterFraction,
  getReaderPosition,
  type ChapterLengths,
  type ReaderPosition,
} from '@/features/reader/readerPosition'
import {
  getFoliateStyles,
  getReaderColors,
  getReaderFlow,
} from '@/features/reader/foliateStyles'
import FoliateReaderSettings from '@/features/reader/FoliateReaderSettings.vue'
import '@/vendor/foliate/view.js'
import { demoBook, demoCatalog, getDemoContent } from '@/features/demo/demoBook'

type FoliateRenderer = HTMLElement & {
  setStyles: (styles: string) => void
  goTo: (target: {
    index: number
    anchor?: number | (() => Element | Range | null)
  }) => Promise<void>
  prev: () => Promise<void>
  next: () => Promise<void>
  prevSection: () => Promise<void>
  nextSection: () => Promise<void>
  render: () => void
  cancelNavigation?: () => void
  goToPage?: (page: number) => Promise<void>
  getPageRangeForRange?: (
    range: Range,
  ) => { firstPage: number; lastPage: number } | null
  scrollToAnchor: (anchor: Element | Range | number) => Promise<void>
  scrolled: boolean
  atStart: boolean
  atEnd: boolean
  page: number
  pages: number
  start: number
  end: number
  viewSize: number
  size: number
  getContents: () => Array<{ index: number; doc: Document }>
}
type FoliateView = HTMLElement & {
  open: (book: unknown) => Promise<void>
  close?: () => void
  renderer: FoliateRenderer
}
const MAX_READER_WINDOW_CHAPTERS = 12
const RETAINED_PREVIOUS_CHAPTERS = 4
const store = useBookStore()
const router = useRouter()
const demoMode = ref(sessionStorage.getItem('demoMode') === 'true')
const readModeStorageKey = 'legado.web.readMode'
const reSegmentStorageKey = 'legado.web.reSegment'
const clickActionsStorageKey = 'legado.web.clickActions'
const storedReadMode = localStorage.getItem(readModeStorageKey)
const storedReSegment = localStorage.getItem(reSegmentStorageKey)
const storedClickActions = (() => {
  const raw = localStorage.getItem(clickActionsStorageKey)
  if (!raw) return undefined
  try {
    const value = JSON.parse(raw)
    return value && typeof value === 'object' ? value : undefined
  } catch {
    return undefined
  }
})()
const persistedReadMode =
  storedReadMode === 'scroll' ||
  storedReadMode === 'horizontal' ||
  storedReadMode === 'click'
    ? storedReadMode
    : undefined
const persistedReSegment = storedReSegment === 'true'
const viewElement = ref<FoliateView | null>(null)
const readerStage = ref<HTMLElement | null>(null)
const catalogScroll = ref<HTMLElement | null>(null)
const continuousBook = shallowRef<ContinuousLegadoFoliateBook>()
const paginatedBook = shallowRef<LegadoFoliateBook>()
const activeBook = shallowRef<LegadoFoliateBook | ContinuousLegadoFoliateBook>()
const catalog = ref<BookChapter[]>([])
const book = reactive<Book>({} as Book)
const readerPosition = ref<ReaderPosition>(getReaderPosition([], 0, 0))
const currentIndex = computed(() => readerPosition.value.chapterIndex)
const progressDraft = ref(0)
const chapterPageInfo = ref({ current: 1, total: 1 })
const loading = ref(true)
const catalogVisible = ref(false)
const catalogNavigationError = ref('')
const catalogRetryIndex = ref<number | undefined>()
const CATALOG_ITEM_HEIGHT = 40
const CATALOG_OVERSCAN = 8
const catalogScrollTop = ref(0)
const catalogViewportHeight = ref(480)
const virtualCatalog = computed(() => {
  const visibleCount = Math.ceil(
    catalogViewportHeight.value / CATALOG_ITEM_HEIGHT,
  )
  const start = Math.max(
    0,
    Math.floor(catalogScrollTop.value / CATALOG_ITEM_HEIGHT) - CATALOG_OVERSCAN,
  )
  const end = Math.min(
    catalog.value.length,
    start + visibleCount + CATALOG_OVERSCAN * 2,
  )
  return catalog.value.slice(start, end).map((chapter, offset) => ({
    chapter,
    index: start + offset,
  }))
})
const catalogTopSpacer = computed(
  () => Math.max(0, virtualCatalog.value[0]?.index || 0) * CATALOG_ITEM_HEIGHT,
)
const catalogBottomSpacer = computed(
  () =>
    Math.max(
      0,
      catalog.value.length -
        (virtualCatalog.value[virtualCatalog.value.length - 1]?.index ?? -1) -
        1,
    ) * CATALOG_ITEM_HEIGHT,
)
const settingsVisible = ref(false)
const menuVisible = ref(false)

// ---- 滚动手势自动隐藏工具栏（局域网共享定制）----
function hideChromeOnGesture() {
  menuVisible.value = false
}
document.addEventListener('wheel', hideChromeOnGesture, { capture: true, passive: true })
let lanTouchStartY: number | null = null
document.addEventListener(
  'touchstart',
  (e) => {
    if (e.touches.length === 1) lanTouchStartY = e.touches[0].clientY
  },
  { capture: true, passive: true },
)
document.addEventListener(
  'touchmove',
  (e) => {
    if (lanTouchStartY !== null && e.touches.length === 1) {
      if (Math.abs(e.touches[0].clientY - lanTouchStartY) > 14) {
        hideChromeOnGesture()
        lanTouchStartY = e.touches[0].clientY
      }
    }
  },
  { capture: true, passive: true },
)
document.addEventListener(
  'touchend',
  () => {
    lanTouchStartY = null
  },
  { capture: true, passive: true },
)
document.addEventListener(
  'keydown',
  (e) => {
    if (['ArrowDown', 'ArrowUp', 'PageDown', 'PageUp', ' '].includes(e.key)) {
      hideChromeOnGesture()
    }
  },
  { capture: true },
)

// ---- 细条滚动指示器（cool18 风格）----
const lanPercent = ref(0)
const lanScrollBarEl = ref<HTMLElement | null>(null)
const lanThumbEl = ref<HTMLElement | null>(null)
const lanScrolling = ref(false)
let lanFadeTimer: ReturnType<typeof setTimeout> | null = null
const lanThumbStyle = computed(() => {
  const pct = Math.max(0, Math.min(100, lanPercent.value))
  const thumbH = 36
  const track = 100 - thumbH
  return {
    height: `${thumbH}px`,
    top: `${Math.round(track * (pct / 100))}px`,
    opacity: lanScrolling.value ? 0.6 : 0,
  }
})
function refreshLanScrollPercent() {
  const input = document.querySelector<HTMLInputElement>(
    '.reader-footer input[type="range"]',
  )
  lanPercent.value = input ? Number(input.value) || 0 : 0
  lanScrolling.value = true
  if (lanFadeTimer) clearTimeout(lanFadeTimer)
  lanFadeTimer = setTimeout(() => {
    lanScrolling.value = false
  }, 900)
}
const errorMessage = ref('')
const isFullscreen = ref(false)
let boundaryTimer: ReturnType<typeof setTimeout> | undefined
let boundaryIndex = -1
let suppressBoundaryUntil = 0
let readerStarted = false
let modeReloading = false
const boundReaderDocuments = new WeakSet<Document>()
let pagePrefetch: Promise<boolean> | undefined
let pageNavigationPromise: Promise<void> | undefined
let pendingChapterTarget: { index: number; navigationId: number } | undefined
let seekingOverall = false
let lastProgressAxis = 0
let lastObservedScrollAxis = 0
let lastVisibleRange: Range | undefined
let scrollDirection: 'forward' | 'backward' = 'forward'
let suppressProgressUntil = 0
let lastRenderedStageSize = ''
let progressSaveTimer: ReturnType<typeof setTimeout> | undefined
let progressSaveQueued = false
let lastSavedProgressKey = ''
let viewportRefreshFrame = 0
let viewportRefreshTimer: ReturnType<typeof setTimeout> | undefined
let viewportRefreshRunning = false
let lastSeekCommit = { value: -1, at: 0 }
let clickTouchStart: { x: number; y: number } | undefined
let suppressClickUntil = 0
type NavigationStatus = 'idle' | 'running'
type NavigationKind =
  | 'catalog'
  | 'progress'
  | 'previous-chapter'
  | 'next-chapter'
  | 'previous-page'
  | 'next-page'
  | 'boundary'
type NavigationToken = {
  id: number
  kind: NavigationKind
  signal: AbortSignal
  isCurrent: () => boolean
}
let navigationController: AbortController | undefined
const navigationState = reactive<{
  id: number
  kind: NavigationKind | 'idle'
  status: NavigationStatus
}>({ id: 0, kind: 'idle', status: 'idle' })
let contentLoader:
  ((_book: Book, chapter: BookChapter) => Promise<string>) | undefined
const createReaderBook = async (): Promise<
  LegadoFoliateBook | ContinuousLegadoFoliateBook
> => {
  const create =
    store.config.readMode === 'scroll'
      ? createContinuousLegadoFoliateBook
      : createLegadoFoliateBook
  try {
    return await create(
      book,
      catalog.value,
      contentLoader,
      store.config.reSegment,
    )
  } catch (firstError) {
    await new Promise<void>(resolve => setTimeout(resolve, 160))
    return create(
      book,
      catalog.value,
      contentLoader,
      store.config.reSegment,
    ).catch(() => {
      throw firstError
    })
  }
}
const getChapterLengths = (): ChapterLengths =>
  catalog.value.map((_chapter, index) =>
    continuousBook.value?.continuous.getChapterLength(index),
  )
const getBookProgress = (position: ReaderPosition) =>
  getBookFraction(catalog.value, position, getChapterLengths())
const bookProgressPercent = computed(() => {
  if (!catalog.value.length) return 0
  const lengths = getChapterLengths()
  const hasChapterRanges = catalog.value.some(
    chapter =>
      typeof chapter.start === 'number' &&
      typeof chapter.end === 'number' &&
      chapter.end > chapter.start,
  )
  const chapterFraction = Number.isFinite(progressDraft.value)
    ? Math.max(0, Math.min(1, progressDraft.value / 100))
    : 0
  const value =
    lengths.some(length => typeof length === 'number') || hasChapterRanges
      ? getBookProgress(readerPosition.value) * 100
      : ((currentIndex.value + chapterFraction) / catalog.value.length) * 100
  return Math.max(0, Math.min(100, value))
})
const bookProgressText = computed(() => {
  const rounded = Math.round(bookProgressPercent.value * 10) / 10
  return `${Number.isInteger(rounded) ? rounded : rounded.toFixed(1)}%`
})
const saveProgressNow = () => {
  if (progressSaveTimer) clearTimeout(progressSaveTimer)
  progressSaveTimer = undefined
  progressSaveQueued = false
  if (demoMode.value) return
  const { chapterIndex, chapterPos } = store.readingBook
  const key = `${chapterIndex}:${chapterPos}`
  if (key === lastSavedProgressKey) return
  lastSavedProgressKey = key
  void store.saveBookProgress()
}
const scheduleProgressSave = () => {
  if (demoMode.value || progressSaveQueued) return
  progressSaveQueued = true
  progressSaveTimer = setTimeout(() => {
    progressSaveTimer = undefined
    progressSaveQueued = false
    saveProgressNow()
  }, 1000)
}
const setReaderPosition = (
  position: ReaderPosition,
  displayedProgress?: number,
) => {
  readerPosition.value = position
  if (!seekingOverall || displayedProgress !== undefined)
    progressDraft.value =
      displayedProgress ??
      getChapterFraction(catalog.value, position, getChapterLengths()) * 100
  store.readingBook.chapterIndex = position.chapterIndex
  store.readingBook.chapterPos = position.chapterOffset
  scheduleProgressSave()
}
const invalidateNavigation = () => {
  navigationState.id += 1
  navigationController?.abort()
  navigationController = undefined
  navigationState.status = 'idle'
  navigationState.kind = 'idle'
  seekingOverall = false
  suppressProgressUntil = 0
  pendingChapterTarget = undefined
  if (boundaryTimer) {
    clearTimeout(boundaryTimer)
    boundaryTimer = undefined
  }
  pagePrefetch = undefined
  continuousBook.value?.continuous.cancelPending()
  viewElement.value?.renderer?.cancelNavigation?.()
}
const beginNavigation = (kind: NavigationKind): NavigationToken => {
  invalidateNavigation()
  const controller = new AbortController()
  const id = navigationState.id
  navigationController = controller
  navigationState.kind = kind
  navigationState.status = 'running'
  return {
    id,
    kind,
    signal: controller.signal,
    isCurrent: () =>
      id === navigationState.id &&
      navigationController === controller &&
      !controller.signal.aborted,
  }
}
const finishNavigation = (token: NavigationToken) => {
  if (!token.isCurrent()) return
  navigationState.status = 'idle'
  navigationState.kind = 'idle'
  navigationController = undefined
}
const scrollCatalogToCurrent = async () => {
  await nextTick()
  const container = catalogScroll.value
  if (!container) return
  catalogViewportHeight.value = Math.max(container.clientHeight, 240)
  const targetTop = Math.max(
    0,
    currentIndex.value * CATALOG_ITEM_HEIGHT -
      (catalogViewportHeight.value - CATALOG_ITEM_HEIGHT) / 2,
  )
  container.scrollTop = targetTop
  catalogScrollTop.value = targetTop
}
const handleCatalogScroll = (event: Event) => {
  const container = event.currentTarget as HTMLElement
  catalogScrollTop.value = container.scrollTop
  catalogViewportHeight.value = Math.max(container.clientHeight, 240)
}
const pageStyle = computed(() => {
  const colors = getReaderColors(store.config.theme)
  return { '--page-bg': colors.background, '--page-fg': colors.foreground }
})
const applyReaderConfig = () => {
  const renderer = viewElement.value?.renderer
  if (!renderer) return
  renderer.setAttribute('flow', getReaderFlow(store.config.readMode))
  renderer.toggleAttribute('animated', store.config.readMode !== 'scroll')
  renderer.setAttribute('max-column-count', '1')
  const inlineSize = readerStage.value?.clientWidth
  // 页边距（左右）通过 max-inline-size 收窄内容列宽实现：网格会把收窄后的
  // 列居中，两侧留出对称空白；比 body padding 更可靠（分栏模式下 body
  // padding 只影响首尾页）。
  const marginCfg = store.config.margin || { top: 16, right: 16, bottom: 16, left: 16 }
  if (inlineSize) {
    const inset = Math.max(0, Math.min(marginCfg.left, 200))
    renderer.setAttribute('max-inline-size', `${Math.max(inlineSize - inset * 2, 200)}px`)
  }
  renderer.setAttribute(
    'max-block-size',
    `${Math.max(readerStage.value?.clientHeight || 0, 320)}px`,
  )
  // foliate 翻页模式：margin 属性必须保持 '0px'（非 0 会形成上下边距带并
  // 干扰分栏，导致文字重叠）。滚动模式：margin 值映射为左右内边距。
  renderer.setAttribute('margin', '0px')
  renderer.setAttribute('gap', '0%')
  // 左右页边距统一只经由上面那个 max-inline-size 收窄内容列宽实现，
  // 不能再额外叠加 body 水平 padding：两处同时生效会双重内缩，且分栏
  // 模式下 padding 只影响可视列的起点，导致左右留白不对称（预览与刷新
  // 后样式不一致）。
  let extra = ' body { padding-bottom: 48px !important; }'
  if (renderer.scrolled)
    extra += ' article[data-legado-chapter] { padding-bottom: 48px !important; }'
  renderer.setStyles(getFoliateStyles(store.config) + extra)
}
const updateProgress = (
  index: number,
  fraction: number,
  displayedProgress?: number,
) => {
  suppressBoundaryUntil = Date.now() + 500
  boundaryIndex = -1
  const position = getReaderPosition(
    catalog.value,
    index,
    getChapterProgressPosition(
      catalog.value,
      index,
      fraction || 0,
      getChapterLengths(),
    ),
  )
  setReaderPosition(position, displayedProgress)
  lastProgressAxis = 0
  lastObservedScrollAxis = 0
}
const updateScrollDirection = (renderer: FoliateRenderer) => {
  const axis = renderer.start
  if (axis > lastObservedScrollAxis + 2) scrollDirection = 'forward'
  else if (axis < lastObservedScrollAxis - 2) scrollDirection = 'backward'
  lastObservedScrollAxis = axis
}
const measureChapterPages = (
  chapter: HTMLElement | null,
  renderer: FoliateRenderer,
) => {
  if (!chapter) return { current: 1, total: 1, firstPage: 1, measured: false }
  const size = Math.max(renderer.size, 1)
  if (renderer.scrolled) {
    const height = Math.max(chapter.offsetHeight, 1)
    const start = Math.max(renderer.start - chapter.offsetTop, 0)
    const total = Math.max(1, Math.ceil(height / size))
    const chapterEnd = chapter.offsetTop + height
    const atEnd = renderer.end >= chapterEnd - Math.max(8, size * 0.02)
    return {
      current: atEnd ? total : Math.min(total, Math.floor(start / size) + 1),
      total,
      firstPage: 1,
      measured: true,
    }
  }
  const document = chapter.ownerDocument
  const textNodes: Text[] = []
  const walker = document.createTreeWalker(chapter, NodeFilter.SHOW_TEXT)
  let node: Node | null
  while ((node = walker.nextNode())) {
    if ((node.textContent || '').trim()) textNodes.push(node as Text)
  }
  const startRange = document.createRange()
  const endRange = document.createRange()
  if (textNodes.length) {
    startRange.setStart(textNodes[0], 0)
    startRange.collapse(true)
    const last = textNodes[textNodes.length - 1]
    endRange.setStart(last, last.data.length)
    endRange.collapse(true)
  } else {
    startRange.selectNodeContents(chapter)
    startRange.collapse(true)
    endRange.selectNodeContents(chapter)
    endRange.collapse(false)
  }
  const startPageRange = renderer.getPageRangeForRange?.(startRange)
  const endPageRange = renderer.getPageRangeForRange?.(endRange)
  if (startPageRange && endPageRange) {
    const firstPage = Math.max(1, startPageRange.firstPage)
    const lastPage = Math.max(firstPage, endPageRange.lastPage)
    const total = lastPage - firstPage + 1
    return {
      current: Math.min(total, Math.max(1, renderer.page - firstPage + 1)),
      total,
      firstPage,
      measured: true,
    }
  }
  const range = document.createRange()
  range.selectNodeContents(chapter)
  const rects = Array.from(range.getClientRects()).filter(
    rect => rect.width > 0 && rect.height > 0,
  )
  if (!rects.length)
    return { current: 1, total: 1, firstPage: renderer.page, measured: false }
  const left = Math.min(...rects.map(rect => rect.left)) + renderer.start
  const right = Math.max(...rects.map(rect => rect.right)) + renderer.start
  const firstPage = Math.max(1, Math.floor((left + 1) / size))
  const lastPage = Math.max(firstPage, Math.ceil((right - 1) / size) - 1)
  const total = lastPage - firstPage + 1
  const current = Math.min(total, Math.max(1, renderer.page - firstPage + 1))
  return { current, total, firstPage, measured: true }
}
const refreshChapterPageInfo = (
  renderer: FoliateRenderer,
  index = currentIndex.value,
  pageOverride?: { page?: number; pages?: number },
) => {
  if (!renderer.scrolled && pageOverride?.page && pageOverride.pages) {
    const total = Math.max(1, pageOverride.pages - 2)
    chapterPageInfo.value = {
      current: Math.min(total, Math.max(1, pageOverride.page)),
      total,
    }
    return
  }
  const document = renderer.getContents()[0]?.doc
  if (!document) return
  const chapter = document.querySelector<HTMLElement>(
    `[data-legado-chapter="${index}"]`,
  )
  const measured = measureChapterPages(chapter, renderer)
  chapterPageInfo.value = { current: measured.current, total: measured.total }
}
const getChapterOffsetFromRange = (chapter: HTMLElement, range?: Range) => {
  if (!range) return undefined
  const container =
    range.startContainer.nodeType === Node.TEXT_NODE
      ? range.startContainer.parentElement
      : (range.startContainer as Element)
  if (!container || !chapter.contains(container)) return undefined
  const prefix = chapter.ownerDocument.createRange()
  prefix.selectNodeContents(chapter)
  prefix.setEnd(range.startContainer, range.startOffset)
  return prefix.toString().length
}
const commitReaderPosition = (
  renderer: FoliateRenderer,
  position: ReaderPosition,
  displayedProgress?: number,
) => {
  const axis = renderer.start
  const movingForward = renderer.scrolled
    ? scrollDirection === 'forward'
    : axis >= lastProgressAxis - 2
  const measuredProgress = getBookProgress(position)
  const currentProgress = getBookProgress(readerPosition.value)
  if (movingForward && measuredProgress + 0.0005 < currentProgress) {
    lastProgressAxis = axis
    return
  }
  const stablePosition =
    movingForward && measuredProgress + 0.0005 < currentProgress
      ? readerPosition.value
      : position
  setReaderPosition(stablePosition, displayedProgress)
  lastProgressAxis = axis
}
const commitMeasuredProgress = (
  renderer: FoliateRenderer,
  index: number,
  measuredFraction: number,
  displayedProgress?: number,
) => {
  const measuredOffset = getChapterProgressPosition(
    catalog.value,
    index,
    measuredFraction,
    getChapterLengths(),
  )
  const measuredPosition = getReaderPosition(
    catalog.value,
    index,
    measuredOffset,
  )
  commitReaderPosition(
    renderer,
    measuredPosition,
    displayedProgress ?? measuredFraction * 100,
  )
}
const updateScrollProgress = (
  document: Document,
  renderer: FoliateRenderer,
) => {
  const index =
    continuousBook.value?.continuous.getChapterAt(
      document,
      renderer.start,
      false,
    ) || 0
  const chapter = document.querySelector<HTMLElement>(
    `[data-legado-chapter="${index}"]`,
  )
  if (!chapter) return
  const chapterHeight = Math.max(chapter.scrollHeight, chapter.offsetHeight, 1)
  const chapterEnd = chapter.offsetTop + chapterHeight
  const atEnd = renderer.end >= chapterEnd - 8
  const localFraction = atEnd
    ? 1
    : (Math.max(renderer.start, chapter.offsetTop) - chapter.offsetTop) /
      chapterHeight
  chapterPageInfo.value = measureChapterPages(chapter, renderer)
  commitMeasuredProgress(
    renderer,
    index,
    Math.max(0, Math.min(1, localFraction)),
  )
}
const updatePaginatedProgress = (
  document: Document,
  renderer: FoliateRenderer,
  visibleRange?: Range,
  pageOverride?: { page?: number; pages?: number },
) => {
  const index =
    continuousBook.value?.continuous.getChapterAt(
      document,
      renderer.start,
      true,
      visibleRange,
    ) || 0
  const chapter = document.querySelector<HTMLElement>(
    `[data-legado-chapter="${index}"]`,
  )
  if (!chapter) return
  const pageInfo = measureChapterPages(chapter, renderer)
  const displayedPageInfo =
    !pageInfo.measured && pageOverride?.page && pageOverride.pages
      ? {
          current: Math.min(
            Math.max(1, pageOverride.pages - 2),
            Math.max(1, pageOverride.page),
          ),
          total: Math.max(1, pageOverride.pages - 2),
        }
      : { current: pageInfo.current, total: pageInfo.total }
  chapterPageInfo.value = displayedPageInfo
  const rects = Array.from(chapter.getClientRects())
  const localFraction = rects.length
    ? (() => {
        const left = Math.min(...rects.map(rect => rect.left))
        const right = Math.max(...rects.map(rect => rect.right))
        const center = (document.defaultView?.innerWidth || 0) / 2
        return (center - left) / Math.max(right - left, 1)
      })()
    : pageOverride?.page && pageOverride.pages
      ? (pageOverride.page - 1) / Math.max(pageOverride.pages - 2, 1)
      : 0
  const pageProgress =
    displayedPageInfo.total <= 1
      ? 1
      : (displayedPageInfo.current - 1) /
        Math.max(displayedPageInfo.total - 1, 1)
  const rangeOffset = getChapterOffsetFromRange(chapter, visibleRange)
  if (rangeOffset !== undefined) {
    commitReaderPosition(
      renderer,
      getReaderPosition(catalog.value, index, rangeOffset),
      pageProgress * 100,
    )
  } else {
    commitMeasuredProgress(
      renderer,
      index,
      Math.max(0, Math.min(1, localFraction)),
      pageProgress * 100,
    )
  }
}
const updateContinuousProgress = (
  document: Document,
  renderer: FoliateRenderer,
  visibleRange?: Range,
  pageOverride?: { page?: number; pages?: number },
) => {
  if (seekingOverall || Date.now() < suppressProgressUntil) return
  if (renderer.scrolled) updateScrollProgress(document, renderer)
  else updatePaginatedProgress(document, renderer, visibleRange, pageOverride)
}
const waitForReaderLayout = () =>
  new Promise<void>(resolve =>
    requestAnimationFrame(() => requestAnimationFrame(() => resolve())),
  )
const commitReaderSnapshot = (
  renderer: FoliateRenderer,
  document: Document,
  visibleRange = lastVisibleRange,
) => {
  seekingOverall = false
  suppressProgressUntil = 0
  updateContinuousProgress(
    document,
    renderer,
    visibleRange,
    renderer.scrolled
      ? undefined
      : { page: renderer.page, pages: renderer.pages },
  )
}
const saveProgress = saveProgressNow
const toShelf = () => {
  saveProgress()
  if (document.fullscreenElement) void document.exitFullscreen().catch(() => {})
  router.push({
    name: 'shelf',
    query: demoMode.value ? { demo: '1' } : undefined,
  })
}
const previous = () => {
  const renderer = viewElement.value?.renderer
  if (!renderer) return Promise.resolve()
  const token = beginNavigation('previous-page')
  scrollDirection = 'backward'
  const run = async () => {
    if (renderer.atStart) {
      const document = renderer.getContents()[0]?.doc
      if (document) {
        const added = await prependPreviousChapter(renderer, document)
        if (!token.isCurrent()) return
        if (added) {
          await renderer.prev()
          return
        }
      }
    }
    if (!continuousBook.value && !renderer.scrolled && renderer.atStart) {
      if (currentIndex.value > 0)
        await renderer.goTo({ index: currentIndex.value - 1, anchor: 1 })
      return
    }
    if (!token.isCurrent()) return
    await renderer.prev()
  }
  const pending = run()
    .catch(() => {
      if (token.isCurrent()) renderer.render()
    })
    .finally(() => {
      finishNavigation(token)
      if (pageNavigationPromise === tracked) pageNavigationPromise = undefined
    })
  const tracked = pending
  pageNavigationPromise = tracked
  return tracked
}
const appendNextChapter = async (
  renderer: FoliateRenderer,
  document: Document,
) => {
  if (!continuousBook.value) return false
  const navigationId = navigationState.id
  const added = await continuousBook.value.continuous.appendNext(document)
  if (added && navigationId === navigationState.id) {
    renderer.render()
    await waitForReaderLayout()
    commitReaderSnapshot(renderer, document)
  }
  return added
}
const prependPreviousChapter = async (
  renderer: FoliateRenderer,
  document: Document,
) => {
  if (!continuousBook.value) return false
  const navigationId = navigationState.id
  const currentArticle = document.querySelector<HTMLElement>(
    `[data-legado-chapter="${currentIndex.value}"]`,
  )
  const anchor = currentArticle
    ? createTextAnchor(
        document,
        currentArticle,
        readerPosition.value.chapterOffset,
      ) || currentArticle
    : null
  const added = await continuousBook.value.continuous.prependPrevious(document)
  if (added && navigationId === navigationState.id) {
    renderer.render()
    await waitForReaderLayout()
    if (anchor) await renderer.scrollToAnchor(anchor).catch(() => {})
    await waitForReaderLayout()
    commitReaderSnapshot(renderer, document)
  }
  return added
}
const trimReaderWindow = async (
  renderer: FoliateRenderer,
  document: Document,
  currentChapter: number,
) => {
  if (!continuousBook.value) return
  const navigationId = navigationState.id
  const chapters = document.querySelectorAll('[data-legado-chapter]').length
  if (chapters <= MAX_READER_WINDOW_CHAPTERS) return
  const keepFrom = Math.max(0, currentChapter - RETAINED_PREVIOUS_CHAPTERS)
  const currentArticle = document.querySelector<HTMLElement>(
    `[data-legado-chapter="${currentChapter}"]`,
  )
  const anchor = currentArticle
    ? createTextAnchor(
        document,
        currentArticle,
        readerPosition.value.chapterOffset,
      ) || currentArticle
    : null
  if (continuousBook.value.continuous.trimWindow(document, keepFrom)) {
    renderer.render()
    await waitForReaderLayout()
    if (anchor) await renderer.scrollToAnchor(anchor).catch(() => {})
    await waitForReaderLayout()
    if (navigationId === navigationState.id)
      commitReaderSnapshot(renderer, document)
  }
}
const requestPagePrefetch = (renderer: FoliateRenderer, document: Document) => {
  if (pagePrefetch) return pagePrefetch
  const pending = appendNextChapter(renderer, document)
  const tracked = pending.finally(() => {
    if (pagePrefetch === tracked) pagePrefetch = undefined
  })
  pagePrefetch = tracked
  return tracked
}
const ensureScrollableContent = async (
  renderer: FoliateRenderer,
  document: Document,
) => {
  if (!renderer.scrolled) return
  for (let attempt = 0; attempt < 3; attempt += 1) {
    const chapters = Array.from(
      document.querySelectorAll<HTMLElement>('[data-legado-chapter]'),
    )
    const contentEnd = chapters.length
      ? Math.max(
          ...chapters.map(chapter => chapter.offsetTop + chapter.offsetHeight),
        )
      : document.body.scrollHeight
    if (contentEnd - renderer.start > renderer.size + 8) return
    if (!(await appendNextChapter(renderer, document))) return
  }
}
const createTextAnchor = (
  document: Document,
  root: HTMLElement,
  offset: number,
) => {
  const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT)
  const nodes: Text[] = []
  let total = 0
  let node: Node | null
  while ((node = walker.nextNode())) {
    const text = node as Text
    nodes.push(text)
    total += text.data.length
  }
  if (!nodes.length || total === 0 || offset <= 0) return null
  let remaining = Math.min(total, Math.max(0, Math.round(offset)))
  for (const text of nodes) {
    if (remaining <= text.data.length) {
      const range = document.createRange()
      range.setStart(text, remaining)
      range.collapse(true)
      return range
    }
    remaining -= text.data.length
  }
  return null
}
const restoreChapterPosition = async (
  renderer: FoliateRenderer,
  index: number,
  chapterOffset = 0,
  isCurrent: () => boolean = () => true,
  rendered?: { value: boolean },
): Promise<boolean> => {
  const document = renderer.getContents()[0]?.doc
  if (!continuousBook.value || !document || !isCurrent()) return false
  let article = document.querySelector<HTMLElement>(
    `[data-legado-chapter="${index}"]`,
  )
  if (!article) {
    const jumped = await continuousBook.value.continuous.jumpToChapter(
      document,
      index,
    )
    if (!jumped || !isCurrent()) return false
    if (rendered) rendered.value = true
    renderer.render()
    await new Promise<void>(resolve =>
      requestAnimationFrame(() => requestAnimationFrame(() => resolve())),
    )
    article = document.querySelector<HTMLElement>(
      `[data-legado-chapter="${index}"]`,
    )
  }
  if (!isCurrent()) return false
  await ensureScrollableContent(renderer, document)
  if (!article || !isCurrent()) return false
  const anchor = createTextAnchor(document, article, chapterOffset) || article
  if (renderer.scrolled) await renderer.scrollToAnchor(anchor)
  else await renderer.goTo({ index: 0, anchor: () => anchor })
  return isCurrent()
}
const next = async () => {
  const renderer = viewElement.value?.renderer
  if (!renderer) return Promise.resolve()
  const token = beginNavigation('next-page')
  scrollDirection = 'forward'
  const run = async () => {
    if (!renderer.scrolled) {
      const document = renderer.getContents()[0]?.doc
      if (document) {
        const nearEnd = renderer.end >= renderer.viewSize - renderer.size * 1.5
        if (renderer.atEnd) {
          const added = await requestPagePrefetch(renderer, document)
          if (!token.isCurrent()) return
          if (added) {
            await renderer.next()
            return
          }
        } else if (nearEnd) {
          void requestPagePrefetch(renderer, document)
        }
      }
      if (!continuousBook.value && renderer.atEnd) {
        if (currentIndex.value < catalog.value.length - 1)
          await renderer.goTo({ index: currentIndex.value + 1, anchor: 0 })
        return
      }
    }
    if (!token.isCurrent()) return
    await renderer.next()
  }
  const pending = run()
    .catch(() => {
      if (token.isCurrent()) renderer.render()
    })
    .finally(() => {
      finishNavigation(token)
      if (pageNavigationPromise === tracked) pageNavigationPromise = undefined
    })
  const tracked = pending
  pageNavigationPromise = tracked
  return tracked
}
const navigateSection = (direction: -1 | 1) => {
  const renderer = viewElement.value?.renderer
  if (!renderer) return
  const token = beginNavigation('boundary')
  scrollDirection = direction > 0 ? 'forward' : 'backward'
  const operation =
    direction > 0 ? renderer.nextSection() : renderer.prevSection()
  const tracked = operation.finally(() => {
    finishNavigation(token)
    if (pageNavigationPromise === tracked) pageNavigationPromise = undefined
  })
  pageNavigationPromise = tracked
}
const previousChapter = () => {
  const base = pendingChapterTarget?.index ?? currentIndex.value
  if (base > 0) void goChapter(base - 1, 'previous-chapter')
}
const nextChapter = () => {
  const base = pendingChapterTarget?.index ?? currentIndex.value
  if (base < catalog.value.length - 1) void goChapter(base + 1, 'next-chapter')
}
const showReaderMenu = () => {
  menuVisible.value = true
}
const hideReaderMenu = () => {
  menuVisible.value = false
  catalogVisible.value = false
  settingsVisible.value = false
}
const toggleReaderMenu = () => {
  if (menuVisible.value) hideReaderMenu()
  else showReaderMenu()
}
const executeClickAction = (action: ReadClickAction) => {
  switch (action) {
    case 'menu':
      toggleReaderMenu()
      break
    case 'prevPage':
      void previous()
      break
    case 'nextPage':
      void next()
      break
    case 'prevChapter':
      previousChapter()
      break
    case 'nextChapter':
      nextChapter()
      break
  }
}
const handleReaderDocumentClick = (event: MouseEvent) => {
  if (menuVisible.value) {
    event.preventDefault()
    event.stopPropagation()
    hideReaderMenu()
    return
  }
  const target = event.target
  if (
    target instanceof Element &&
    target.closest('a,button,input,select,textarea')
  )
    return
  const document = event.currentTarget as Document
  if (!document.getSelection()?.isCollapsed) return
  if (store.config.readMode !== 'click') {
    showReaderMenu()
    return
  }
  const window = document.defaultView
  if (!window) return
  const area = getReadClickArea(
    event.clientX,
    event.clientY,
    window.innerWidth,
    window.innerHeight,
  )
  executeClickAction(store.config.clickActions[area])
}
const bindReaderDocument = (document?: Document) => {
  if (!document || boundReaderDocuments.has(document)) return
  boundReaderDocuments.add(document)
  document.addEventListener('click', handleReaderDocumentClick, true)
}
const handleReaderScroll = () => {
  const renderer = viewElement.value?.renderer
  if (!renderer?.scrolled) return
  const document = renderer.getContents()[0]?.doc
  if (continuousBook.value && document) {
    updateScrollDirection(renderer)
    updateContinuousProgress(document, renderer)
    void trimReaderWindow(renderer, document, currentIndex.value)
    if (renderer.start <= renderer.size * 1.5) {
      void prependPreviousChapter(renderer, document)
    }
    void ensureScrollableContent(renderer, document)
    if (renderer.end >= renderer.viewSize - renderer.size * 1.5) {
      void appendNextChapter(renderer, document)
    }
    return
  }
  if (Date.now() < suppressBoundaryUntil) return
  const atBottom = renderer.end >= renderer.viewSize - 8
  const atTop = renderer.start <= 8
  const targetIndex = atBottom
    ? currentIndex.value + 1
    : atTop
      ? currentIndex.value - 1
      : -1
  if (targetIndex < 0 || targetIndex >= catalog.value.length) return
  if (boundaryIndex === currentIndex.value) return
  boundaryIndex = currentIndex.value
  if (boundaryTimer) clearTimeout(boundaryTimer)
  boundaryTimer = setTimeout(() => {
    boundaryTimer = undefined
    if (targetIndex > currentIndex.value) navigateSection(1)
    else if (targetIndex < currentIndex.value) navigateSection(-1)
  }, 420)
}
const goChapter = async (index: number, kind: NavigationKind = 'catalog') => {
  const renderer = viewElement.value?.renderer
  if (!renderer) return
  if (index < 0 || index >= catalog.value.length) return
  const token = beginNavigation(kind)
  pendingChapterTarget = { index, navigationId: token.id }
  if (kind === 'catalog') {
    catalogNavigationError.value = ''
    catalogRetryIndex.value = undefined
  }
  suppressProgressUntil = Date.now() + 600
  try {
    if (continuousBook.value) {
      const document = renderer.getContents()[0]?.doc
      let restored = false
      if (document) {
        if (index > currentIndex.value) scrollDirection = 'forward'
        else if (index < currentIndex.value) scrollDirection = 'backward'
        restored = await restoreChapterPosition(
          renderer,
          index,
          0,
          token.isCurrent,
        )
        if (!restored && token.isCurrent()) {
          await new Promise<void>(resolve => setTimeout(resolve, 120))
          restored = await restoreChapterPosition(
            renderer,
            index,
            0,
            token.isCurrent,
          )
        }
        if (restored && token.isCurrent()) {
          setReaderPosition(getReaderPosition(catalog.value, index, 0))
          refreshChapterPageInfo(renderer, index)
          suppressProgressUntil = 0
        }
      }
      if (!restored) throw new Error('章节定位失败')
      if (token.isCurrent()) {
        catalogNavigationError.value = ''
        catalogRetryIndex.value = undefined
        catalogVisible.value = false
      }
      return
    }
    await renderer.goTo({ index, anchor: 0 })
    if (token.isCurrent()) {
      catalogNavigationError.value = ''
      catalogRetryIndex.value = undefined
      catalogVisible.value = false
    }
  } catch (error) {
    if (token.isCurrent() && kind === 'catalog') {
      catalogVisible.value = true
      catalogRetryIndex.value = index
      catalogNavigationError.value =
        error instanceof Error && error.message
          ? error.message
          : '章节打开失败，请重试'
    }
  } finally {
    if (pendingChapterTarget?.navigationId === token.id)
      pendingChapterTarget = undefined
    finishNavigation(token)
  }
}
const retryCatalogNavigation = () => {
  const target = catalogRetryIndex.value
  if (target === undefined || !catalogNavigationError.value) return
  void goChapter(target, 'catalog')
}
const seekChapter = () => {
  const renderer = viewElement.value?.renderer
  if (!renderer || !catalog.value.length) return
  const requestedProgress = Math.max(0, Math.min(100, progressDraft.value))
  const requestedOffset = getChapterProgressPosition(
    catalog.value,
    currentIndex.value,
    requestedProgress / 100,
    getChapterLengths(),
  )
  const currentPageTotal = Math.max(chapterPageInfo.value.total, 1)
  chapterPageInfo.value = {
    current: Math.min(
      currentPageTotal,
      Math.max(
        1,
        Math.round((requestedProgress / 100) * (currentPageTotal - 1)) + 1,
      ),
    ),
    total: currentPageTotal,
  }
  const target = currentIndex.value
  const requestedPosition = getReaderPosition(
    catalog.value,
    target,
    requestedOffset,
  )
  const token = beginNavigation('progress')
  if (continuousBook.value) {
    const document = renderer.getContents()[0]?.doc
    if (document) {
      if (!renderer.scrolled) {
        const chapter = document.querySelector<HTMLElement>(
          `[data-legado-chapter="${target}"]`,
        )
        const pageInfo = measureChapterPages(chapter, renderer)
        const rendererTotalPages = Math.max(renderer.pages - 2, 1)
        const targetPage = pageInfo.measured
          ? pageInfo.firstPage +
            Math.round(
              (requestedProgress / 100) * Math.max(pageInfo.total - 1, 0),
            )
          : 1 +
            Math.round(
              (requestedProgress / 100) * Math.max(rendererTotalPages - 1, 0),
            )
        const previousPosition = readerPosition.value
        seekingOverall = true
        const operation = renderer.goToPage
          ? renderer.goToPage(targetPage)
          : renderer.goTo({ index: 0, anchor: requestedProgress / 100 })
        void operation
          .then(async () => {
            if (!token.isCurrent()) return
            await waitForReaderLayout()
            commitReaderSnapshot(renderer, document)
          })
          .catch(() => {
            if (token.isCurrent()) setReaderPosition(previousPosition)
          })
          .finally(() => {
            if (!token.isCurrent()) return
            seekingOverall = false
            suppressProgressUntil = 0
            finishNavigation(token)
          })
        return
      }
      const previousPosition = readerPosition.value
      const continuous = continuousBook.value
      const targetLoaded = Boolean(
        document.querySelector(`[data-legado-chapter="${target}"]`),
      )
      scrollDirection =
        requestedOffset >= previousPosition.chapterOffset
          ? 'forward'
          : 'backward'
      seekingOverall = true
      suppressProgressUntil = Date.now() + 600
      const rendered = { value: false }
      const jump = targetLoaded
        ? Promise.resolve(true)
        : continuous.continuous.jumpToChapter(document, target)
      void jump
        .then(async jumped => {
          if (!token.isCurrent()) return
          if (!jumped) throw new Error('目标章节跳转已取消')
          let restored = await restoreChapterPosition(
            renderer,
            target,
            requestedPosition.chapterOffset,
            token.isCurrent,
            rendered,
          )
          if (!restored && token.isCurrent()) {
            await new Promise<void>(resolve => setTimeout(resolve, 120))
            restored = await restoreChapterPosition(
              renderer,
              target,
              requestedPosition.chapterOffset,
              token.isCurrent,
              rendered,
            )
          }
          if (!restored) throw new Error('目标章节定位失败')
          if (!targetLoaded && !rendered.value) {
            renderer.render()
            await new Promise<void>(resolve =>
              requestAnimationFrame(() =>
                requestAnimationFrame(() => resolve()),
              ),
            )
          }
          if (!token.isCurrent()) return
          setReaderPosition(requestedPosition)
          refreshChapterPageInfo(renderer, target)
          suppressProgressUntil = Date.now() + 600
          lastProgressAxis = renderer.start
        })
        .catch(() => {
          if (token.isCurrent()) {
            setReaderPosition(previousPosition)
            suppressProgressUntil = 0
          }
        })
        .finally(() => {
          if (!token.isCurrent()) return
          seekingOverall = false
          progressDraft.value =
            getChapterFraction(
              catalog.value,
              readerPosition.value,
              getChapterLengths(),
            ) * 100
          refreshChapterPageInfo(renderer, readerPosition.value.chapterIndex)
          suppressProgressUntil = 0
          finishNavigation(token)
        })
    } else {
      seekingOverall = false
      finishNavigation(token)
    }
    return
  }
  const totalPages = Math.max(renderer.pages - 2, 1)
  const targetPage =
    1 + Math.round((requestedProgress / 100) * Math.max(totalPages - 1, 0))
  void (
    renderer.goToPage
      ? renderer.goToPage(targetPage)
      : renderer.goTo({ index: target, anchor: requestedProgress / 100 })
  ).finally(() => finishNavigation(token))
}
const commitSeek = () => {
  const value = Number(progressDraft.value)
  const now = Date.now()
  if (
    Math.abs(value - lastSeekCommit.value) < 0.01 &&
    now - lastSeekCommit.at < 180
  )
    return
  lastSeekCommit = { value, at: now }
  seekChapter()
}
const handleStageClick = (event: MouseEvent) => {
  const target = event.target
  if (
    target instanceof Element &&
    target.closest('a,button,input,select,textarea')
  )
    return
  if (Date.now() < suppressClickUntil) {
    event.preventDefault()
    return
  }
  if (menuVisible.value) {
    event.preventDefault()
    hideReaderMenu()
    return
  }
  if (store.config.readMode !== 'click' || !viewElement.value?.renderer) {
    showReaderMenu()
    return
  }
  const stage = event.currentTarget as HTMLElement
  const area = getReadClickArea(
    event.clientX,
    event.clientY,
    stage.clientWidth,
    stage.clientHeight,
  )
  executeClickAction(store.config.clickActions[area])
}
const handleClickModeTouchStart = (event: TouchEvent) => {
  if (store.config.readMode !== 'click') return
  const touch = event.changedTouches[0]
  if (!touch) return
  clickTouchStart = { x: touch.clientX, y: touch.clientY }
}
const handleClickModeTouchMove = (event: TouchEvent) => {
  if (store.config.readMode !== 'click' || !clickTouchStart) return
  const touch = event.changedTouches[0]
  if (!touch) return
  const dx = touch.clientX - clickTouchStart.x
  const dy = touch.clientY - clickTouchStart.y
  if (Math.abs(dx) > 12 && Math.abs(dx) > Math.abs(dy)) {
    event.preventDefault()
    event.stopPropagation()
  }
}
const handleClickModeTouchEnd = (event: TouchEvent) => {
  if (store.config.readMode !== 'click' || !clickTouchStart) return
  const touch = event.changedTouches[0]
  const start = clickTouchStart
  clickTouchStart = undefined
  if (!touch) return
  const dx = touch.clientX - start.x
  const dy = touch.clientY - start.y
  if (Math.abs(dx) < 48 || Math.abs(dx) <= Math.abs(dy)) return
  event.preventDefault()
  event.stopPropagation()
  suppressClickUntil = Date.now() + 450
  if (dx < 0) void next()
  else void previous()
}
const handleClickCellClick = (event: MouseEvent, action: ReadClickAction) => {
  if (Date.now() < suppressClickUntil) {
    event.preventDefault()
    return
  }
  if (menuVisible.value) {
    event.preventDefault()
    hideReaderMenu()
    return
  }
  executeClickAction(action)
}
const deleteBook = async () => {
  try {
    await ElMessageBox.confirm(
      `确定从书架删除《${book.name}》吗？`,
      '删除书籍',
      { type: 'warning' },
    )
    if (!demoMode.value) await API.deleteBook(book)
    store.removeBook(book.bookUrl)
    toShelf()
  } catch {}
}
const syncFullscreenState = () => {
  isFullscreen.value = Boolean(document.fullscreenElement)
}
const refreshReaderViewport = () => {
  const refresh = () => {
    if (viewportRefreshRunning) return
    viewportRefreshRunning = true
    const viewportHeight = Math.round(
      Math.max(
        window.innerHeight,
        document.documentElement.clientHeight,
        window.visualViewport?.height || 0,
      ),
    )
    const page = readerStage.value?.closest<HTMLElement>('.reader-page')
    const stage = readerStage.value
    try {
      if (!page || !stage || viewportHeight <= 0) return
      const previousHeight = page.clientHeight
      if (Math.abs(previousHeight - viewportHeight) > 1) {
        page.style.height = `${viewportHeight}px`
        page.style.minHeight = `${viewportHeight}px`
      }
      const stageSize = `${stage.clientWidth}x${stage.clientHeight}`
      const sizeChanged = stageSize !== lastRenderedStageSize
      lastRenderedStageSize = stageSize
      applyReaderConfig()
      if (sizeChanged) viewElement.value?.renderer?.render()
    } finally {
      viewportRefreshRunning = false
    }
  }
  if (viewportRefreshFrame) cancelAnimationFrame(viewportRefreshFrame)
  if (viewportRefreshTimer) clearTimeout(viewportRefreshTimer)
  viewportRefreshFrame = requestAnimationFrame(() => {
    viewportRefreshFrame = requestAnimationFrame(() => {
      viewportRefreshFrame = 0
      refresh()
    })
  })
  viewportRefreshTimer = setTimeout(() => {
    viewportRefreshTimer = undefined
    if (viewportRefreshFrame) cancelAnimationFrame(viewportRefreshFrame)
    viewportRefreshFrame = 0
    refresh()
  }, 180)
}
const handleFullscreenChange = () => {
  syncFullscreenState()
  refreshReaderViewport()
}
const toggleFullscreen = async () => {
  try {
    if (document.fullscreenElement) await document.exitFullscreen()
    else if (document.documentElement.requestFullscreen)
      await document.documentElement.requestFullscreen()
  } catch {}
}
const handleSettingsOutsidePointer = (event: PointerEvent) => {
  if (!settingsVisible.value) return
  const target = event.target
  if (!(target instanceof Element)) return
  if (target.closest('.settings-panel') || target.closest('[title="阅读设置"]'))
    return
  settingsVisible.value = false
}
const bindRendererEvents = (view: FoliateView) => {
  bindReaderDocument(view.renderer.getContents()[0]?.doc)
  view.renderer.addEventListener('load', (event: Event) => {
    bindReaderDocument(
      (event as CustomEvent<{ doc?: Document }>).detail?.doc ||
        view.renderer.getContents()[0]?.doc,
    )
  })
  view.renderer.addEventListener('relocate', (event: Event) => {
    const detail = (
      event as CustomEvent<{
        index: number
        fraction: number
        range?: Range
        page?: number
        pages?: number
      }>
    ).detail
    if (detail.range) lastVisibleRange = detail.range.cloneRange()
    const document = view.renderer.getContents()[0]?.doc
    if (continuousBook.value && document) {
      updateContinuousProgress(document, view.renderer, detail.range, detail)
      void trimReaderWindow(view.renderer, document, currentIndex.value)
      if (
        !view.renderer.scrolled &&
        view.renderer.start <= view.renderer.size * 1.5
      ) {
        void prependPreviousChapter(view.renderer, document)
      }
      if (
        !view.renderer.scrolled &&
        view.renderer.end >= view.renderer.viewSize - view.renderer.size * 1.5
      ) {
        void appendNextChapter(view.renderer, document)
      }
    } else {
      const displayedProgress =
        detail.page != null && detail.pages != null
          ? (() => {
              const total = Math.max(1, detail.pages - 2)
              const current = Math.min(total, Math.max(1, detail.page))
              return total <= 1
                ? 100
                : ((current - 1) / Math.max(total - 1, 1)) * 100
            })()
          : detail.fraction * 100
      updateProgress(detail.index, detail.fraction, displayedProgress)
      refreshChapterPageInfo(view.renderer, detail.index, detail)
    }
    // 细条滚动指示器随内容位置刷新（局域网共享定制）
    refreshLanScrollPercent()
  })
  view.renderer.addEventListener('scroll', handleReaderScroll)
}
const reloadReaderForMode = async () => {
  const view = viewElement.value
  if (!readerStarted || modeReloading || !view) return
  modeReloading = true
  invalidateNavigation()
  const previousPosition = readerPosition.value
  try {
    pagePrefetch = undefined
    continuousBook.value?.continuous.dispose()
    continuousBook.value = undefined
    paginatedBook.value = undefined
    view.close?.()
    const createdBook = await createReaderBook()
    if (store.config.readMode === 'scroll')
      continuousBook.value = createdBook as ContinuousLegadoFoliateBook
    else paginatedBook.value = createdBook as LegadoFoliateBook
    activeBook.value = createdBook
    const foliateBook = createdBook
    await view.open(foliateBook)
    applyReaderConfig()
    bindRendererEvents(view)
    await view.renderer.goTo({ index: 0, anchor: 0 })
    if (store.config.readMode === 'scroll' && continuousBook.value) {
      await restoreChapterPosition(
        view.renderer,
        previousPosition.chapterIndex,
        previousPosition.chapterOffset,
      )
    } else if (store.config.readMode !== 'scroll') {
      await view.renderer.goTo({
        index: previousPosition.chapterIndex,
        anchor: getChapterFraction(
          catalog.value,
          previousPosition,
          getChapterLengths(),
        ),
      })
    }
    if (
      store.config.readMode === 'scroll' &&
      continuousBook.value &&
      previousPosition.chapterIndex === 0 &&
      getChapterFraction(
        catalog.value,
        previousPosition,
        getChapterLengths(),
      ) === 0
    ) {
      const document = view.renderer.getContents()[0]?.doc
      if (document) await ensureScrollableContent(view.renderer, document)
    }
  } finally {
    modeReloading = false
  }
}
watch(
  () => store.config,
  () => {
    applyReaderConfig()
    localStorage.setItem(
      clickActionsStorageKey,
      JSON.stringify(store.config.clickActions),
    )
    if (!demoMode.value) void API.saveReadConfig(store.config)
  },
  { deep: true },
)
watch(
  () => store.config.readMode,
  () => {
    localStorage.setItem(readModeStorageKey, store.config.readMode)
    void reloadReaderForMode()
  },
)
watch(
  () => store.config.reSegment,
  () => {
    localStorage.setItem(reSegmentStorageKey, String(store.config.reSegment))
    void reloadReaderForMode()
  },
)
watch(catalogVisible, visible => {
  if (visible) void scrollCatalogToCurrent()
  else {
    catalogNavigationError.value = ''
    catalogRetryIndex.value = undefined
  }
})
watch(currentIndex, () => {
  if (catalogVisible.value) void scrollCatalogToCurrent()
})
onMounted(async () => {
  syncFullscreenState()
  document.addEventListener('fullscreenchange', handleFullscreenChange)
  window.addEventListener('resize', refreshReaderViewport)
  window.visualViewport?.addEventListener('resize', refreshReaderViewport)
  window.addEventListener('pagehide', saveProgressNow)
  document.addEventListener('pointerdown', handleSettingsOutsidePointer)
  try {
    if (!demoMode.value) await store.loadWebConfig()
    if (persistedReadMode) store.config.readMode = persistedReadMode
    if (storedReSegment !== null) store.config.reSegment = persistedReSegment
    if (storedClickActions)
      store.setConfig({ clickActions: storedClickActions })
    Object.assign(
      book,
      demoMode.value
        ? demoBook
        : {
            bookUrl: sessionStorage.getItem('bookUrl') || '',
            name: sessionStorage.getItem('bookName') || '',
            author: sessionStorage.getItem('bookAuthor') || '',
          },
    )
    if (!book.bookUrl) throw new Error('书籍信息为空')
    const shelfBook = demoMode.value
      ? demoBook
      : store.shelf.find(item => item.bookUrl === book.bookUrl)
    if (shelfBook) Object.assign(book, shelfBook)
    store.setReadingBook({
      ...book,
      chapterIndex: Number(
        sessionStorage.getItem('chapterIndex') ||
          shelfBook?.durChapterIndex ||
          0,
      ),
      chapterPos: Number(
        sessionStorage.getItem('chapterPos') || shelfBook?.durChapterPos || 0,
      ),
    })
    if (demoMode.value) {
      catalog.value = [...demoCatalog]
    } else {
      await store.loadWebCatalog(store.readingBook)
      catalog.value = [...store.catalog]
    }
    contentLoader = demoMode.value
      ? (_book: Book, chapter: BookChapter) => getDemoContent(chapter)
      : undefined
    const createdBook = await createReaderBook()
    if (store.config.readMode === 'scroll')
      continuousBook.value = createdBook as ContinuousLegadoFoliateBook
    else paginatedBook.value = createdBook as LegadoFoliateBook
    activeBook.value = createdBook
    const foliateBook = createdBook
    const view = viewElement.value
    if (!view) throw new Error('阅读器初始化失败')
    await view.open(foliateBook)
    applyReaderConfig()
    if (readerStage.value) {
      stageObserver = new ResizeObserver(() => refreshReaderViewport())
      stageObserver.observe(readerStage.value)
    }
    bindRendererEvents(view)
    const initialIndex = Math.max(
      0,
      Math.min(catalog.value.length - 1, store.readingBook.chapterIndex || 0),
    )
    setReaderPosition(
      getReaderPosition(
        catalog.value,
        initialIndex,
        store.readingBook.chapterPos || 0,
      ),
    )
    await view.renderer.goTo({ index: 0, anchor: 0 })
    if (
      store.config.readMode === 'scroll' &&
      continuousBook.value &&
      initialIndex > 0
    ) {
      await restoreChapterPosition(
        view.renderer,
        initialIndex,
        readerPosition.value.chapterOffset,
      )
    } else if (store.config.readMode !== 'scroll')
      await view.renderer.goTo({
        index: initialIndex,
        anchor: getChapterFraction(
          catalog.value,
          readerPosition.value,
          getChapterLengths(),
        ),
      })
    if (
      store.config.readMode === 'scroll' &&
      continuousBook.value &&
      initialIndex === 0
    )
      await restoreChapterPosition(
        view.renderer,
        0,
        readerPosition.value.chapterOffset,
      )
    readerStarted = true
  } catch (error) {
    errorMessage.value =
      error instanceof Error ? error.message : '阅读器加载失败'
  } finally {
    loading.value = false
  }
})
let stageObserver: ResizeObserver | undefined
onBeforeUnmount(() => {
  invalidateNavigation()
  document.removeEventListener('fullscreenchange', handleFullscreenChange)
  window.removeEventListener('resize', refreshReaderViewport)
  window.visualViewport?.removeEventListener('resize', refreshReaderViewport)
  window.removeEventListener('pagehide', saveProgressNow)
  document.removeEventListener('pointerdown', handleSettingsOutsidePointer)
  stageObserver?.disconnect()
  if (boundaryTimer) clearTimeout(boundaryTimer)
  if (progressSaveTimer) clearTimeout(progressSaveTimer)
  if (viewportRefreshFrame) cancelAnimationFrame(viewportRefreshFrame)
  if (viewportRefreshTimer) clearTimeout(viewportRefreshTimer)
  continuousBook.value?.continuous.dispose()
  saveProgress()
  viewElement.value?.close?.()
  if (document.fullscreenElement) void document.exitFullscreen().catch(() => {})
})
</script>

<style scoped>
.reader-page {
  position: relative;
  height: 100%;
  height: 100dvh;
  min-height: 100vh;
  min-height: 100dvh;
  overflow: hidden;
  background: var(--page-bg);
  color: var(--page-fg);
  --reader-header-height: calc(54px + env(safe-area-inset-top, 0px));
  --reader-footer-height: calc(50px + env(safe-area-inset-bottom, 0px));
  --reader-surface: var(--page-bg);
  --reader-border: color-mix(in srgb, var(--page-fg) 10%, transparent);
  --reader-muted: color-mix(in srgb, var(--page-fg) 58%, transparent);
  --reader-track: color-mix(in srgb, var(--page-fg) 18%, transparent);
  --reader-accent: #cf4c58;
}
.reader-header,
.reader-footer {
  position: absolute;
  z-index: 10;
  right: 0;
  left: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 18px;
  background: var(--reader-surface);
  border-color: var(--reader-border);
  visibility: hidden;
  pointer-events: none;
  will-change: transform;
  transition:
    transform 200ms cubic-bezier(0.2, 0.8, 0.2, 1),
    visibility 0s linear 200ms;
}
.reader-header {
  top: 0;
  height: var(--reader-header-height);
  box-sizing: border-box;
  padding-top: env(safe-area-inset-top, 0px);
  border-bottom: 1px solid var(--reader-border);
  transform: translate3d(0, -100%, 0);
}
.reader-footer {
  bottom: 0;
  height: var(--reader-footer-height);
  box-sizing: border-box;
  gap: 8px;
  padding-top: 5px;
  padding-bottom: max(5px, env(safe-area-inset-bottom, 0px));
  border-top: 1px solid var(--reader-border);
  transform: translate3d(0, 100%, 0);
}
.reader-header.visible,
.reader-footer.visible {
  visibility: visible;
  pointer-events: auto;
  transform: translate3d(0, 0, 0);
  transition-delay: 0s;
}
.reader-footer.visible {
  transition-duration: 150ms, 0s;
}
.reader-tools {
  display: flex;
  gap: 8px;
}
.reader-button {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  min-width: 34px;
  height: 34px;
  justify-content: center;
  border: 0;
  border-radius: 7px;
  background: transparent;
  color: inherit;
  cursor: pointer;
}
.reader-button:hover {
  background: color-mix(in srgb, var(--page-fg) 8%, transparent);
}
.reader-button svg {
  width: 18px;
  height: 18px;
}
.reader-button.danger {
  color: #cf4c58;
}
.header-title {
  max-width: min(52vw, 540px);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 14px;
  font-weight: 600;
}
.reader-stage {
  position: absolute;
  inset: 0;
  min-height: 0;
  overflow: hidden;
  overscroll-behavior: none;
  box-sizing: border-box;
}
.foliate-view {
  display: block;
  width: 100%;
  height: 100%;
}
.click-layer {
  position: absolute;
  z-index: 2;
  inset: 0;
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  grid-template-rows: repeat(3, minmax(0, 1fr));
  touch-action: none;
  overscroll-behavior: none;
}
.click-cell {
  min-width: 0;
  min-height: 0;
  border: 0;
  padding: 0;
  background: transparent;
  cursor: pointer;
  outline: none;
  border-color: transparent;
  box-shadow: none;
  appearance: none;
  -webkit-tap-highlight-color: transparent;
}
.click-cell:focus,
.click-cell:focus-visible,
.click-cell:active {
  outline: none !important;
  border: 0;
  box-shadow: none;
}
.reader-menu-edge {
  position: absolute;
  z-index: 3;
  right: 0;
  left: 0;
  border: 0;
  padding: 0;
  background: transparent;
  cursor: pointer;
  outline: none;
  appearance: none;
  -webkit-tap-highlight-color: transparent;
}
.reader-menu-edge-top {
  top: 0;
  height: var(--reader-header-height);
}
.reader-menu-edge-bottom {
  bottom: 0;
  height: var(--reader-footer-height);
}
.book-progress {
  position: absolute;
  z-index: 11;
  right: 10px;
  bottom: max(5px, env(safe-area-inset-bottom, 0px));
  padding: 2px 4px;
  border-radius: 4px;
  background: color-mix(in srgb, var(--page-bg) 72%, transparent);
  color: var(--reader-muted);
  font-size: 10px;
  line-height: 1;
  letter-spacing: 0.02em;
  pointer-events: none;
  user-select: none;
  /* 平时隐藏，滚动/翻页时浮现，避免常驻盖住最后一行 */
  opacity: 0;
  transition:
    opacity 0.45s ease,
    bottom 150ms ease;
}
.book-progress.lan-visible {
  opacity: 0.85;
}
.book-progress.above-footer {
  bottom: calc(var(--reader-footer-height) + 5px);
}
.catalog-panel,
.settings-panel {
  position: absolute;
  z-index: 20;
  top: var(--reader-header-height);
  bottom: var(--reader-footer-height);
  width: min(280px, calc(100vw - 32px));
  overflow: hidden;
  background: var(--page-bg);
  box-shadow: 0 8px 28px rgba(0, 0, 0, 0.18);
}
/* 拖动页边距滑块时临时半透明，实时预览边距效果 */
.settings-panel.lan-dragging {
  background: color-mix(in srgb, var(--page-bg) 55%, transparent);
  backdrop-filter: blur(8px);
  -webkit-backdrop-filter: blur(8px);
}
.catalog-backdrop {
  position: absolute;
  z-index: 4;
  inset: 0;
  background: transparent;
}
.settings-backdrop {
  position: absolute;
  z-index: 4;
  top: var(--reader-header-height);
  right: 0;
  bottom: var(--reader-footer-height);
  left: 0;
  background: transparent;
}
.catalog-panel {
  left: 0;
  padding: 16px 12px;
}
.catalog-list {
  height: calc(100% - 42px);
  overflow: auto;
  overscroll-behavior: contain;
  contain: strict;
}
.catalog-spacer {
  flex: none;
  pointer-events: none;
}
.settings-panel {
  z-index: 20;
  right: 0;
  padding: 20px;
  top: calc(var(--reader-header-height) + 8px);
  right: 8px;
  bottom: auto;
  width: min(380px, calc(100vw - 16px));
  max-height: calc(
    100% - var(--reader-header-height) - var(--reader-footer-height) - 16px
  );
  box-sizing: border-box;
  overflow: auto;
  border-radius: 8px;
  --settings-fg: var(--page-fg);
  --settings-muted: color-mix(in srgb, var(--page-fg) 55%, transparent);
}
.panel-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 6px 12px;
}
.catalog-error {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin: 0 4px 10px;
  padding: 8px 9px;
  border: 1px solid color-mix(in srgb, #cf4c58 32%, transparent);
  border-radius: 6px;
  color: #cf4c58;
  font-size: 12px;
}
.catalog-error span {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.catalog-error button {
  flex: 0 0 auto;
  border: 0;
  background: transparent;
  color: inherit;
  cursor: pointer;
  font-size: 12px;
}
.catalog-item {
  display: block;
  width: 100%;
  height: 40px;
  padding: 0 9px;
  overflow: hidden;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: inherit;
  text-align: left;
  text-overflow: ellipsis;
  white-space: nowrap;
  cursor: pointer;
}
.catalog-item:hover {
  background: color-mix(in srgb, var(--page-fg) 10%, transparent);
}
.catalog-item.selected {
  background: color-mix(in srgb, var(--page-fg) 10%, transparent);
  color: #cf4c58;
}
.progress-control {
  display: flex;
  align-items: center;
  gap: 10px;
  flex: 1;
  min-width: 0;
  width: auto;
  margin: 0 auto;
}
.progress-control input {
  flex: 1;
  height: 22px;
  min-width: 0;
  accent-color: var(--reader-accent);
  -webkit-appearance: none;
  appearance: none;
  background: transparent;
}
.progress-control input::-webkit-slider-runnable-track {
  height: 3px;
  border-radius: 999px;
  background: var(--reader-track);
}
.progress-control input::-webkit-slider-thumb {
  width: 14px;
  height: 14px;
  margin-top: -5.5px;
  border: 0;
  border-radius: 50%;
  background: var(--reader-accent);
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.2);
  -webkit-appearance: none;
  appearance: none;
}
.progress-control input::-moz-range-track {
  height: 3px;
  border-radius: 999px;
  background: var(--reader-track);
}
.progress-control input::-moz-range-progress {
  height: 3px;
  border-radius: 999px;
  background: var(--reader-accent);
}
.progress-control input::-moz-range-thumb {
  width: 14px;
  height: 14px;
  border: 0;
  border-radius: 50%;
  background: var(--reader-accent);
}
.progress-control span {
  flex: 0 0 52px;
  width: 52px;
  min-width: 0;
  color: var(--reader-muted);
  font-size: 11px;
  text-align: center;
}
.reader-loading,
.reader-error {
  position: absolute;
  inset: 0;
  display: grid;
  place-content: center;
  gap: 12px;
  background: var(--page-bg);
  text-align: center;
}
.reader-error button {
  border: 0;
  border-radius: 6px;
  padding: 8px 14px;
  background: #cf4c58;
  color: #fff;
  cursor: pointer;
}
@media (max-width: 680px) {
  .reader-page {
    --reader-header-height: calc(48px + env(safe-area-inset-top, 0px));
    --reader-footer-height: calc(48px + env(safe-area-inset-bottom, 0px));
  }
  .reader-header,
  .reader-footer {
    padding-right: 8px;
    padding-left: 8px;
  }
  .header-title {
    max-width: 44vw;
  }
  .progress-control {
    width: auto;
    margin: 0;
  }
  .progress-control span {
    flex-basis: 60px;
    width: 60px;
  }
  .catalog-panel,
  .settings-panel {
    top: var(--reader-header-height);
    bottom: var(--reader-footer-height);
  }
  .catalog-panel {
    width: min(280px, calc(100vw - 32px));
  }
  .settings-backdrop {
    top: var(--reader-header-height);
    bottom: var(--reader-footer-height);
  }
  .settings-panel {
    top: calc(var(--reader-header-height) + 8px);
    right: 8px;
    bottom: auto;
    width: calc(100vw - 16px);
    max-height: calc(
      100% - var(--reader-header-height) - var(--reader-footer-height) - 16px
    );
    padding: 12px;
  }
  .reader-footer .reader-button {
    min-width: 30px;
  }
}
.reader-page.night {
  --reader-border: rgba(216, 217, 220, 0.12);
  --reader-muted: rgba(216, 217, 220, 0.62);
  --reader-track: rgba(216, 217, 220, 0.22);
  --reader-accent: #e06a74;
}
@media (prefers-reduced-motion: reduce) {
  .reader-header,
  .reader-footer {
    transition: none;
  }
}
@supports (-webkit-touch-callout: none) {
  .reader-page {
    min-height: -webkit-fill-available;
  }
  .reader-footer {
    padding-bottom: max(6px, env(safe-area-inset-bottom, 0px));
  }
  .progress-control input {
    margin: 0;
  }
}

.lan-scrollbar {
  position: fixed;
  right: 3px;
  top: calc(var(--reader-header-height, 54px) + 10px);
  bottom: calc(var(--reader-footer-height, 50px) + 10px);
  width: 5px;
  z-index: 9;
  pointer-events: none;
}
.lan-scrollbar .lan-thumb {
  position: absolute;
  right: 0;
  width: 100%;
  min-height: 36px;
  border-radius: 3px;
  background: #9aa0ab;
  opacity: 0;
  transition:
    opacity 0.45s ease,
    top 0.12s linear,
    height 0.12s linear;
}
.lan-scrollbar.lan-scrolling .lan-thumb {
  opacity: 0.6;
}
.night .lan-scrollbar .lan-thumb {
  background: #5b616b;
}
.reader-page.lan-cool18 .lan-scrollbar .lan-thumb {
  background: #a49e8c;
}
</style>
