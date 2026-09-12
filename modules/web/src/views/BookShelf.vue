<template>
  <main class="shelf-page" :class="{ night: isNight }">
    <header class="shelf-header">
      <div class="brand">
        <span class="brand-mark">阅</span>
        <div>
          <strong>Legado</strong>
          <small>我的书架 <span class="shelf-count">{{ shownBooks.length }} 本书</span></small>
        </div>
      </div>
      <el-input
        v-model="query"
        class="search"
        clearable
        placeholder="搜索书名或作者"
        :prefix-icon="Search"
      />
      <div class="header-actions">
        <el-select v-model="sortKey" size="small" aria-label="书架排序"
          ><el-option
            v-for="item in sortOptions"
            :key="item.value"
            :label="item.label"
            :value="item.value" /></el-select
        ><button
          class="icon-button"
          type="button"
          :title="viewMode === 'grid' ? '列表视图' : '网格视图'"
          @click="viewMode = viewMode === 'grid' ? 'list' : 'grid'"
        >
          <Grid v-if="viewMode === 'grid'" /><List v-else />
        </button>
      </div>
    </header>
    <section class="shelf-content" aria-label="书架">
      <div v-if="loading" class="state">正在加载书架...</div>
      <div v-else-if="loadError" class="state">
        <Reading />
        <p>{{ loadError }}</p>
        <button type="button" class="retry-button" @click="loadShelf">重新加载</button>
      </div>
      <div v-else-if="shownBooks.length === 0" class="state">
        <Reading />
        <p>{{ query ? '没有找到匹配的书籍' : '书架还是空的' }}</p>
      </div>
      <div v-else class="book-layout" :class="viewMode">
        <button
          v-for="book in shownBooks"
          :key="book.bookUrl"
          class="book-entry"
          type="button"
          @click="openBook(book)"
        >
          <span class="cover-wrap">
            <span class="cover-placeholder" :style="placeholderStyle(book)" />
            <span
              class="cover-copy"
              :class="{ 'has-author': hasKnownAuthor(book.author) }"
            >
              <strong>{{ book.name }}</strong>
              <small v-if="hasKnownAuthor(book.author)">{{ book.author }}</small>
            </span>
            <span v-if="unreadOf(book)" class="unread">{{
              unreadOf(book) > 99 ? '99+' : unreadOf(book)
            }}</span>
            <span class="progress"
              ><i :style="{ width: `${progressOf(book)}%` }"
            /></span>
          </span>
          <span class="book-meta"
            ><strong>{{ book.name }}</strong></span
          >
        </button>
      </div>
    </section>
  </main>
</template>

<script setup lang="ts">
import { Reading, Grid, List, Search } from '@element-plus/icons-vue'
import type { Book } from '@/book'
import { useBookStore } from '@/store'
import { demoBook } from '@/features/demo/demoBook'

const store = useBookStore()
const router = useRouter()
const demoMode = ref(new URLSearchParams(location.search).get('demo') === '1')
const shelfScrollKey = 'legado.web.shelfScrollY'
const saveShelfScroll = () =>
  sessionStorage.setItem(shelfScrollKey, String(window.scrollY || 0))
const restoreShelfScroll = async () => {
  const saved = Number(sessionStorage.getItem(shelfScrollKey))
  if (saved <= 0) return
  sessionStorage.removeItem(shelfScrollKey)
  await nextTick()
  window.scrollTo(0, saved)
}
const query = ref('')
const loading = ref(true)
const loadError = ref('')
const viewMode = ref<'grid' | 'list'>(
  (localStorage.getItem('legado.web.shelfView') as 'grid' | 'list') || 'grid',
)
const sortKey = ref(localStorage.getItem('legado.web.shelfSort') || 'recent')
const isNight = computed(() => store.isNight)
const sortOptions = [
  { label: '最近阅读', value: 'recent' },
  { label: '最近更新', value: 'update' },
  { label: '书名排序', value: 'name' },
  { label: '作者排序', value: 'author' },
]
watch(viewMode, value => localStorage.setItem('legado.web.shelfView', value))
watch(sortKey, value => localStorage.setItem('legado.web.shelfSort', value))
const compare = (a?: string | null, b?: string | null) =>
  (a || '').localeCompare(b || '', 'zh-Hans-CN')
const sortedShelf = computed(() => {
  const result = [...store.shelf]
  if (sortKey.value === 'name')
    return result.sort((a, b) => compare(a.name, b.name))
  if (sortKey.value === 'author')
    return result.sort((a, b) => compare(a.author, b.author))
  if (sortKey.value === 'update')
    return result.sort((a, b) => b.latestChapterTime - a.latestChapterTime)
  return result.sort((a, b) => b.durChapterTime - a.durChapterTime)
})
const shownBooks = computed<Book[]>(() => {
  const key = query.value.trim()
  return key
    ? sortedShelf.value.filter(
        book =>
          (book.name || '').includes(key) || (book.author || '').includes(key),
      )
    : sortedShelf.value
})
const hasKnownAuthor = (author?: string | null) => {
  const value = author?.trim()
  if (value?.toLowerCase() === 'legado web') return false
  return Boolean(
    value &&
      !/^(未知作者|作者未知|未知|佚名|未详|anonymous|unknown|n\/a|-|--|\?)$/i.test(
        value,
      ),
  )
}
const placeholderStyle = (book: Book) => {
  const palettes = [
    '#3f6870',
    '#78564d',
    '#68764f',
    '#53678d',
    '#a07943',
    '#5e6065',
  ]
  const hash = Array.from(book.bookUrl).reduce(
    (sum, char) => sum + char.charCodeAt(0),
    0,
  )
  return { background: palettes[hash % palettes.length] }
}
const unreadOf = (book: Book) =>
  Math.max(book.totalChapterNum - book.durChapterIndex - 1, 0)
const progressOf = (book: Book) =>
  book.totalChapterNum > 1
    ? Math.round((book.durChapterIndex / (book.totalChapterNum - 1)) * 100)
    : 0
const openBook = (book: Book) => {
  sessionStorage.setItem('bookUrl', book.bookUrl)
  sessionStorage.setItem('bookName', book.name)
  sessionStorage.setItem('bookAuthor', book.author)
  sessionStorage.setItem(
    'chapterIndex',
    String('durChapterIndex' in book ? book.durChapterIndex : 0),
  )
  sessionStorage.setItem(
    'chapterPos',
    String('durChapterPos' in book ? book.durChapterPos : 0),
  )
  sessionStorage.setItem('isSeachBook', 'false')
  sessionStorage.setItem('demoMode', String(book.bookUrl.startsWith('demo:')))
  router.push({ name: 'chapter' })
}
const loadShelf = async () => {
  loading.value = true
  loadError.value = ''
  if (demoMode.value) {
    store.shelf = [{ ...demoBook }]
    loading.value = false
    return
  }
  try {
    await store.loadBookShelf()
    void store.loadWebConfig().catch(() => {})
  } catch (error) {
    loadError.value = error instanceof Error ? error.message : '书架加载失败'
  } finally {
    loading.value = false
  }
}
onMounted(async () => {
  await loadShelf()
  await restoreShelfScroll()
})
onBeforeUnmount(saveShelfScroll)
</script>

<style scoped>
.shelf-page {
  min-height: 100%;
  background: #f5f6f8;
  color: #25272b;
}
.shelf-header {
  position: sticky;
  top: 0;
  z-index: 5;
  display: grid;
  grid-template-columns: 220px minmax(180px, 520px) auto;
  align-items: center;
  gap: 20px;
  padding: 12px clamp(18px, 4vw, 56px);
  background: rgba(255, 255, 255, 0.92);
  border-bottom: 1px solid #e5e7eb;
  backdrop-filter: blur(14px);
}
.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}
.brand > div {
  min-width: 0;
}
.brand-mark {
  display: grid;
  place-items: center;
  width: 34px;
  height: 34px;
  border-radius: 9px;
  background: #d94c5a;
  color: #fff;
  font-weight: 700;
}
.brand strong,
.brand small {
  display: block;
}
.brand strong {
  font-size: 16px;
}
.brand small {
  margin-top: 2px;
  color: #858a93;
  font-size: 11px;
}
.search {
  width: 100%;
}
.header-actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
}
.header-actions .el-select {
  width: 112px;
}
.icon-button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  border: 1px solid #dfe2e8;
  border-radius: 7px;
  background: #fff;
  color: #50545c;
  cursor: pointer;
}
.icon-button {
  width: 34px;
  height: 32px;
}
.icon-button svg {
  width: 16px;
  height: 16px;
}
.shelf-content {
  width: min(1320px, calc(100% - 36px));
  margin: 0 auto;
  padding: 22px 0 56px;
}
.shelf-count {
  color: #8b9098;
  font-size: 12px;
}
.book-layout {
  display: grid;
  gap: 26px 22px;
}
.book-layout.grid {
  grid-template-columns: repeat(auto-fill, minmax(132px, 154px));
  justify-content: start;
  gap: 28px 18px;
}
.book-layout.list {
  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
}
.book-entry {
  min-width: 0;
  border: 0;
  padding: 0;
  background: none;
  color: inherit;
  text-align: left;
  cursor: pointer;
}
.cover-wrap {
  position: relative;
  display: block;
  aspect-ratio: 2 / 3;
  overflow: hidden;
  border-radius: 8px;
  background: #e2e4e8;
  box-shadow: 0 6px 16px rgba(30, 35, 45, 0.12);
  isolation: isolate;
}
.cover-placeholder {
  display: grid;
  place-content: center;
  align-content: center;
  gap: 18px;
  height: 100%;
  padding: 16px;
  box-sizing: border-box;
  color: rgba(255, 255, 255, 0.94);
  text-align: center;
}
.cover-placeholder small {
  font-size: 8px;
  opacity: 0.72;
}
.cover-copy {
  position: absolute;
  right: 0;
  bottom: 4px;
  left: 0;
  z-index: 2;
  display: grid;
  gap: 3px;
  padding: 10px;
  background: transparent;
  color: #fff;
  text-shadow: 0 1px 3px rgba(0, 0, 0, 0.78);
  pointer-events: none;
}
.cover-copy:not(.has-author) {
  padding-top: 10px;
}
.cover-copy strong,
.cover-copy small {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
}
.cover-copy strong {
  font-size: 12px;
  font-weight: 600;
  line-height: 1.25;
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  overflow-wrap: anywhere;
  white-space: normal;
}
.cover-copy small {
  font-size: 10px;
  opacity: 0.76;
  white-space: nowrap;
}
.grid .book-entry:hover .cover-wrap {
  transform: translateY(-3px);
  box-shadow: 0 12px 24px rgba(30, 35, 45, 0.18);
  transition: 0.18s ease;
}
.cover-wrap img {
  position: relative;
  z-index: 1;
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.unread {
  position: absolute;
  top: 7px;
  right: 7px;
  padding: 3px 6px;
  border-radius: 10px;
  background: #df5663;
  color: #fff;
  font-size: 10px;
  font-weight: 600;
}
.progress {
  position: absolute;
  right: 0;
  bottom: 0;
  left: 0;
  height: 4px;
  background: rgba(0, 0, 0, 0.2);
}
.progress i {
  display: block;
  height: 100%;
  background: #e25b67;
}
.book-meta {
  display: block;
  min-width: 0;
  padding-top: 10px;
}
.book-meta strong,
.book-meta small,
.book-meta em {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.book-meta strong {
  font-size: 14px;
  font-weight: 600;
}
.book-meta small {
  margin-top: 4px;
  color: #7e838c;
  font-size: 12px;
}
.book-meta em {
  margin-top: 6px;
  color: #a0a4ab;
  font-size: 11px;
  font-style: normal;
}
.list .book-entry {
  display: grid;
  grid-template-columns: 86px minmax(0, 1fr);
  gap: 14px;
  align-items: start;
  padding: 10px;
  border: 1px solid #e4e6ea;
  border-radius: 8px;
  background: #fff;
}
.list .cover-wrap {
  width: 86px;
}
.list .book-meta {
  padding-top: 8px;
}
.state {
  display: grid;
  place-items: center;
  gap: 10px;
  min-height: 42vh;
  color: #969ba3;
}
.state svg {
  width: 38px;
  height: 38px;
  stroke-width: 1.4;
}
.state p {
  margin: 0;
}
.retry-button {
  border: 0;
  border-radius: 6px;
  padding: 8px 14px;
  background: #d94c5a;
  color: #fff;
  cursor: pointer;
}
.night {
  background: #16181b;
  color: #e5e7eb;
}
.night .shelf-header {
  background: rgba(29, 31, 35, 0.94);
  border-color: #30343a;
}
.night .icon-button,
.night .list .book-entry {
  border-color: #383d45;
  background: #24272c;
  color: #d7d9dd;
}
.night .book-meta small,
.night .book-meta em,
.night .shelf-count {
  color: #8f959e;
}
@media (max-width: 960px) {
  .shelf-header {
    grid-template-columns: 1fr auto;
    gap: 12px;
  }
  .brand {
    grid-column: 1;
  }
  .search {
    grid-column: 1 / -1;
    grid-row: 2;
  }
  .header-actions {
    grid-column: 2;
    grid-row: 1;
  }
}
@media (max-width: 760px) {
  .shelf-header {
    padding-right: 12px;
    padding-left: 12px;
  }
  .shelf-content {
    width: min(100% - 20px, 680px);
  }
  .book-layout.grid {
    grid-template-columns: repeat(4, minmax(0, 1fr));
    gap: 20px 8px;
  }
  .book-meta strong {
    font-size: 12px;
  }
}
@media (max-width: 520px) {
  .book-layout.grid {
    grid-template-columns: repeat(4, minmax(0, 1fr));
    gap: 18px 7px;
  }
  .cover-wrap {
    border-radius: 6px;
  }
  .book-meta {
    padding-top: 7px;
  }
  .book-meta strong {
    font-size: 11px;
  }
  .cover-copy {
    padding-right: 7px;
    padding-left: 7px;
  }
  .cover-copy strong {
    font-size: 10px;
  }
  .cover-copy small {
    font-size: 9px;
  }
}
</style>
