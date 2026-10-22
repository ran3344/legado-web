import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import {
  MAX_RETAINED_CHAPTERS,
  trimChapterWindowBeforeAppend,
} from '../src/utils/chapterWindow.js'
import { parseLegacyReviewClick } from '../src/utils/reviewClick.js'

test('reserves one chapter slot before the next chapter is rendered', () => {
  const loaded = Array.from(
    { length: MAX_RETAINED_CHAPTERS },
    (_, index) => index,
  )

  const prepared = trimChapterWindowBeforeAppend(loaded)
  prepared.push(MAX_RETAINED_CHAPTERS)

  assert.deepEqual(
    prepared,
    Array.from({ length: MAX_RETAINED_CHAPTERS }, (_, index) => index + 1),
  )
  assert.deepEqual(
    loaded,
    Array.from({ length: MAX_RETAINED_CHAPTERS }, (_, index) => index),
  )
})

test('does not trim while the window has room', () => {
  const loaded = [1]

  assert.equal(trimChapterWindowBeforeAppend(loaded), loaded)
})

test('keeps legacy paragraph and chapter review image clicks on their script path', () => {
  const paragraph =
    'http://,{"style":"text","js":"getDPSvg(3,0)","click":"getDP(12,3)"}'
  const proxy = `http://localhost/image?path=${encodeURIComponent(paragraph)}`

  assert.deepEqual(parseLegacyReviewClick(proxy), {
    kind: 'paragraph',
    src: paragraph,
  })
  assert.deepEqual(
    parseLegacyReviewClick(
      "http://,{'style':'full','click':'getZP(3)'}",
    ),
    {
      kind: 'chapter',
      src: "http://,{'style':'full','click':'getZP(3)'}",
    },
  )
  assert.equal(
    parseLegacyReviewClick(
      'http://,{"style":"text","click":"getZS(3)"}',
    ),
    null,
  )
  assert.equal(
    parseLegacyReviewClick(
      'http://,{"style":"text","click":"getDP(3)"}',
    ),
    null,
  )
})

test('does not report a canceled token prompt as a backend failure', () => {
  const apiIndex = readFileSync(
    new URL('../src/api/index.ts', import.meta.url),
    'utf8',
  )
  const cancelGuard = apiIndex.indexOf(
    "err === 'cancel' ||",
  )
  const backendFailureNotice = apiIndex.indexOf(
    "message: '后端连接失败，请检查阅读WEB服务或者设置其它可用链接'",
  )

  assert.ok(cancelGuard >= 0)
  assert.ok(cancelGuard < backendFailureNotice)
})

test('covers the Foliate reader architecture and its navigation contracts', () => {
  const bookChapter = readFileSync(
    new URL('../src/views/BookChapter.vue', import.meta.url),
    'utf8',
  )
  const bookShelf = readFileSync(
    new URL('../src/views/BookShelf.vue', import.meta.url),
    'utf8',
  )
  const foliateBook = readFileSync(
    new URL('../src/features/reader/legadoFoliateBook.ts', import.meta.url),
    'utf8',
  )
  const settings = readFileSync(
    new URL('../src/features/reader/FoliateReaderSettings.vue', import.meta.url),
    'utf8',
  )
  const clickActions = readFileSync(
    new URL('../src/config/readClickActions.ts', import.meta.url),
    'utf8',
  )
  const bookStore = readFileSync(
    new URL('../src/store/bookStore.ts', import.meta.url),
    'utf8',
  )
  const readerStyles = readFileSync(
    new URL('../src/features/reader/foliateStyles.ts', import.meta.url),
    'utf8',
  )
  const paginator = readFileSync(
    new URL('../src/vendor/foliate/paginator.js', import.meta.url),
    'utf8',
  )
  const positionModel = readFileSync(
    new URL('../src/features/reader/readerPosition.ts', import.meta.url),
    'utf8',
  )

  assert.match(bookChapter, /<foliate-view[^>]+class="foliate-view"/)
  assert.match(bookChapter, /createContinuousLegadoFoliateBook/)
  assert.match(bookChapter, /prependPreviousChapter/)
  assert.match(bookChapter, /appendNextChapter/)
  assert.match(bookChapter, /jumpToChapter/)
  assert.match(bookChapter, /getChapterLengths/)
  assert.match(bookChapter, /(?:const|let) restored = await restoreChapterPosition/)
  assert.match(bookChapter, /const beginNavigation = \(kind: NavigationKind\)/)
  assert.match(bookChapter, /const invalidateNavigation = \(\)/)
  assert.match(bookChapter, /continuousBook\.value\?\.continuous\.cancelPending\(\)/)
  assert.match(bookChapter, /renderer\?\.cancelNavigation\?\.\(\)/)
  assert.match(bookChapter, /seekingOverall = false/)
  assert.match(bookChapter, /if \(!isCurrent\(\)\) return/)
  assert.match(bookChapter, /const targetLoaded = Boolean/)
  assert.match(bookChapter, /let pageNavigationPromise: Promise<void> \| undefined/)
  assert.doesNotMatch(bookChapter, /if \(pageNavigationPromise\) return pageNavigationPromise/)
  assert.match(bookChapter, /let pendingChapterTarget:/)
  assert.match(bookChapter, /pendingChapterTarget\?\.navigationId === token\.id/)
  assert.match(bookChapter, /const retryCatalogNavigation =/)
  assert.match(bookChapter, /catalogNavigationError/)
  assert.match(bookChapter, /const virtualCatalog = computed/)
  assert.match(bookChapter, /class="catalog-list"/)
  assert.match(bookChapter, /document\.addEventListener\('click', handleReaderDocumentClick, true\)/)
  assert.match(bookChapter, /class="click-layer"/)
  assert.match(bookChapter, /@touchstart="handleClickModeTouchStart"/)
  assert.match(bookChapter, /@touchmove="handleClickModeTouchMove"/)
  assert.match(bookChapter, /@touchend="handleClickModeTouchEnd"/)
  assert.match(bookChapter, /const handleClickCellClick =/)
  assert.match(bookChapter, /v-for="area in readClickAreas"/)
  assert.match(bookChapter, /@click\.stop="\s*handleClickCellClick\(\$event, store\.config\.clickActions\[area\.key\]\)\s*"/)
  assert.match(bookChapter, /@pointerdown\.stop/)
  assert.match(bookChapter, /outline: none !important/)
  assert.match(bookChapter, /const commitSeek =/)
  assert.match(bookChapter, /const createReaderBook = async/)
  assert.match(bookChapter, /createLegadoFoliateBook/)
  assert.match(bookChapter, /paginatedBook = shallowRef/)
  assert.match(bookChapter, /store\.config\.readMode === 'scroll'/)
  assert.match(bookChapter, /legado\.web\.clickActions/)
  assert.match(bookChapter, /JSON\.stringify\(store\.config\.clickActions\)/)
  assert.match(bookChapter, /store\.setConfig\(\{ clickActions: storedClickActions \}\)/)
  assert.match(bookChapter, /window\.addEventListener\('pagehide', saveProgressNow\)/)
  assert.match(bookChapter, /const trimReaderWindow/)
  assert.match(bookChapter, /scrollCatalogToCurrent/)
  assert.match(bookChapter, /class="catalog-backdrop"/)
  assert.match(bookChapter, /scrollDirection === 'forward'/)
  assert.match(bookChapter, /suppressProgressUntil/)
  assert.match(bookChapter, /width: auto;/)
  assert.match(bookChapter, /flex: 0 0 52px;/)
  assert.match(bookChapter, /width: min\(280px, calc\(100vw - 32px\)\)/)
  assert.match(bookChapter, /\.catalog-item:hover \{[\s\S]*?\}\n\.catalog-item\.selected \{/)
  assert.match(bookChapter, /max="100"[\s\S]*step="0\.1"/)
  assert.match(bookChapter, /aria-label="当前章节进度"/)
  assert.match(bookChapter, /chapterPageInfo\.current/)
  assert.match(bookChapter, /const measureChapterPages/)
  assert.match(bookChapter, /const atEnd = renderer\.end >= chapterEnd/)
  assert.match(bookChapter, /const updateScrollProgress =/)
  assert.match(bookChapter, /chapter\.scrollHeight/)
  assert.match(bookChapter, /if \(renderer\.scrolled\) updateScrollProgress/)
  assert.match(bookChapter, /const updatePaginatedProgress =/)
  assert.match(bookChapter, /pageOverride\.page - 1/)
  assert.match(bookChapter, /const getChapterOffsetFromRange =/)
  assert.match(bookChapter, /renderer\.goToPage\(targetPage\)/)
  assert.match(bookChapter, /fullscreenchange/)
  assert.match(bookChapter, /visualViewport/)

  assert.match(foliateBook, /prependPrevious: \(doc: Document\)/)
  assert.match(foliateBook, /const jumpToChapter =/)
  assert.match(foliateBook, /const firstIndex =|let firstIndex/)
  assert.match(foliateBook, /doc\.body\.replaceChildren\(fragment\)/)
  assert.match(foliateBook, /firstArticle\.before\(/)
  assert.match(foliateBook, /Promise\.all\([\s\S]*chapters\.map\(/)
  assert.match(foliateBook, /getChapterLength: index => chapterLengths\.get\(index\)/)
  assert.match(foliateBook, /data-legado-chapter="\$\{catalogIndex\}"/)
  assert.match(foliateBook, /CHAPTER_CACHE_LIMIT = 24/)
  assert.match(foliateBook, /createTaskQueue/)
  assert.match(foliateBook, /invalidate\(\)/)
  assert.match(foliateBook, /requestedGeneration === generation/)
  assert.match(foliateBook, /const documentQueue = createTaskQueue\(\)/)
  assert.match(foliateBook, /return documentQueue\.enqueue/)
  assert.match(foliateBook, /new AbortController\(\)/)
  assert.match(foliateBook, /CHAPTER_REQUEST_TIMEOUT = 20_000/)
  assert.match(foliateBook, /cache\.isSettled\(catalogIndex\)/)
  assert.match(foliateBook, /looksLikeHtml/)
  assert.match(foliateBook, /dispose: \(\) =>/)
  assert.match(bookChapter, /viewportRefreshFrame/)
  assert.match(bookChapter, /cancelAnimationFrame\(viewportRefreshFrame\)/)

  assert.match(settings, /@click="store\.config\.readMode = 'click'"/)
  assert.match(settings, /v-if="store\.config\.readMode === 'click'"/)
  assert.match(settings, /九宫格点击区域/)
  assert.match(clickActions, /const defaultReadClickActions/)
  assert.match(clickActions, /const getReadClickArea/)
  assert.doesNotMatch(
    bookStore,
    /Object\.values\(result\)\.includes\('menu'\)[\s\S]*result\.mc\s*=\s*'menu'/,
  )
  assert.match(readerStyles, /widows: 1; orphans: 1/)
  assert.match(readerStyles, /-webkit-column-break-inside: auto !important/)
  assert.match(paginator, /clamp\(100 \+ distance \/ Math\.max\(size, 1\) \* 24, 100, 150\)/)
  assert.match(paginator, /await wait\(40\)/)
  assert.match(paginator, /detail\.page = page/)
  assert.match(paginator, /detail\.pages = pages/)
  assert.match(paginator, /cancelNavigation\(\)/)
  assert.match(paginator, /goToPage\(page\)/)
  assert.match(paginator, /\.then\(\(\) => page > contentPages\)/)
  assert.doesNotMatch(paginator, /\.then\(\(\) => page >= contentPages\)/)
  assert.match(positionModel, /export type ReaderPosition/)
  assert.match(positionModel, /chapterIndex: number[\s\S]*chapterOffset: number/)
  assert.doesNotMatch(
    positionModel,
    /export type ReaderPosition = \{[^}]*\bchapterFraction\s*:/,
  )
  assert.doesNotMatch(
    positionModel,
    /export type ReaderPosition = \{[^}]*\bbookFraction\s*:/,
  )
  assert.match(positionModel, /getReaderPosition =/)
  assert.match(positionModel, /getReaderPositionFromBookFraction =/)
  assert.match(bookChapter, /const readerPosition = ref<ReaderPosition>/)
  assert.match(bookShelf, /const loadShelf = async/)
  assert.match(bookShelf, /void store\.loadWebConfig\(\)\.catch\(\(\) => \{\}\)/)
  assert.match(bookShelf, /localeCompare\(b \|\| '', 'zh-Hans-CN'\)/)
  assert.match(bookShelf, /book\.author \|\| ''/)
  assert.match(bookStore, /catalogBookUrl: ''/)
  assert.match(bookStore, /this\.catalogBookUrl === bookUrl/)
  assert.match(bookStore, /const requestId = \+\+catalogRequestId/)
  assert.match(bookChapter, /const seekChapter =/)
  assert.match(bookChapter, /const commitSeek =/)
  assert.match(bookChapter, /@pointerup="commitSeek"/)
  assert.match(bookChapter, /await new Promise<void>\(resolve => setTimeout\(resolve, 120\)\)/)
  assert.match(bookChapter, /\.click-cell:focus-visible[\s\S]*outline: none !important/)
  assert.match(bookChapter, /getChapterProgressPosition\(/)
  assert.match(bookChapter, /const refreshChapterPageInfo/)
  assert.match(bookChapter, /refreshChapterPageInfo\(renderer, target\)/)
})
