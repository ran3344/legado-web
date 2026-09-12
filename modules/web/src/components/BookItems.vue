<template>
  <div class="books-wrapper" :class="{ night: isNight }">
    <div v-if="books.length === 0" class="empty-shelf">书架还没有书籍</div>

    <div
      v-else-if="viewMode === 'grid' && !isSearch"
      class="book-grid"
      :style="gridStyle"
    >
      <button
        v-for="book in books"
        :key="book.bookUrl"
        class="book-card"
        type="button"
        :aria-label="book.name"
        @click="handleClick(book)"
      >
        <span class="cover-frame">
          <img
            class="cover"
            :src="getCover(book)"
            :key="book.coverUrl"
            alt=""
            loading="lazy"
            @error.once="proxyImage"
          />
          <span class="cover-book-name" :style="getCoverNameStyle(book.name)">
            <span>{{ book.name }}</span>
          </span>
          <span v-if="getUnreadCount(book) > 0" class="unread-badge">
            {{ formatUnreadCount(getUnreadCount(book)) }}
          </span>
          <span
            v-if="getReadProgress(book) > 0"
            class="read-progress"
            aria-hidden="true"
          >
            <span :style="{ width: `${getReadProgress(book)}%` }"></span>
          </span>
        </span>
      </button>
    </div>

    <div v-else class="book-list">
      <div
        class="book"
        v-for="book in books"
        :key="book.bookUrl"
        @click="handleClick(book)"
      >
        <div class="cover-img">
          <img
            class="cover"
            :src="getCover(book)"
            :key="book.coverUrl"
            @error.once="proxyImage"
            alt=""
            loading="lazy"
          />
        </div>
        <div class="info">
          <div class="name">{{ book.name }}</div>
          <div class="sub">
            <div class="author">{{ book.author }}</div>
            <div class="tags" v-if="isSearch">
              <el-tag
                v-for="tag in book.kind?.split(',').slice(0, 2)"
                :key="tag"
              >
                {{ tag }}
              </el-tag>
            </div>
            <div class="update-info" v-if="!isSearch">
              <div class="dot">•</div>
              <div class="size">共{{ (book as Book).totalChapterNum }}章</div>
              <div class="dot">•</div>
              <div class="date">
                {{ dateFormat((book as Book).lastCheckTime) }}
              </div>
            </div>
          </div>
          <div class="intro" v-if="isSearch">{{ book.intro }}</div>

          <div class="dur-chapter" v-if="!isSearch">
            已读：{{ (book as Book).durChapterTitle }}
          </div>
          <div class="last-chapter">最新：{{ book.latestChapterTitle }}</div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import type { Book, SeachBook } from '@/book'
import { dateFormat, isLegadoUrl } from '../utils/utils'
import API from '@api'

type BookViewMode = 'grid' | 'list'

const props = withDefaults(
  defineProps<{
    books: Array<Book | SeachBook>
    isSearch: boolean
    viewMode?: BookViewMode
    gridColumns?: number
    isNight?: boolean
  }>(),
  {
    viewMode: 'list',
    gridColumns: 3,
    isNight: false,
  },
)

const emit = defineEmits(['bookClick'])
const handleClick = (book: Book | SeachBook) => emit('bookClick', book)

const gridStyle = computed(() => ({
  gridTemplateColumns: `repeat(${props.gridColumns}, minmax(0, 1fr))`,
}))

const getCover = ({ bookUrl, coverUrl }: Book | SeachBook) => {
  if (coverUrl === undefined) return API.getProxyCoverUrl(bookUrl)
  return isLegadoUrl(coverUrl) ? API.getProxyCoverUrl(coverUrl) : coverUrl
}

const proxyImage = (evt: Event) => {
  const target = evt.target as HTMLImageElement
  target.src = API.getProxyCoverUrl(target.src)
}

const getUnreadCount = (book: Book | SeachBook) => {
  if (!('totalChapterNum' in book)) return 0
  return Math.max(book.totalChapterNum - book.durChapterIndex - 1, 0)
}

const formatUnreadCount = (count: number) => (count > 99 ? '99+' : count)

const getReadProgress = (book: Book | SeachBook) => {
  if (!('totalChapterNum' in book)) return 0
  if (book.durChapterIndex === 0 && book.durChapterPos === 0) return 0
  if (book.totalChapterNum <= 1) return 100
  return Math.round((book.durChapterIndex / (book.totalChapterNum - 1)) * 100)
}

const getCoverNameStyle = (name: string) => {
  const length = Array.from(name).length
  if (length > 72) {
    return {
      '--cover-title-font-size': '8px',
      '--cover-title-line-height': '10px',
    }
  }
  if (length > 48) {
    return {
      '--cover-title-font-size': '9px',
      '--cover-title-line-height': '11px',
    }
  }
  if (length > 30) {
    return {
      '--cover-title-font-size': '10px',
      '--cover-title-line-height': '12px',
    }
  }
  return {
    '--cover-title-font-size': '11px',
    '--cover-title-line-height': '14px',
  }
}

const subJustify = computed(() =>
  props.isSearch ? 'space-between' : 'flex-start',
)
</script>

<style lang="scss" scoped>
.books-wrapper {
  height: 100%;
  overflow: auto;
}

.empty-shelf {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
  color: #969ba3;
  font-size: 14px;
}

.book-grid {
  display: grid;
  gap: 14px 16px;
  padding: 0 8px 28px;
}

.book-card {
  display: block;
  border: 0;
  padding: 4px;
  background: transparent;
  color: inherit;
  font: inherit;
  text-align: left;
  cursor: pointer;
}

.book-card:focus-visible {
  outline: none;

  .cover-frame {
    outline: 2px solid #409eff;
    outline-offset: 2px;
  }
}

.book-card:hover .cover-frame {
  opacity: 0.92;
}

.cover-frame {
  position: relative;
  display: block;
  aspect-ratio: 3 / 4;
  overflow: hidden;
  box-sizing: border-box;
  border: 0;
  border-radius: 12px;
  background: #e8e8e8;
  transition: opacity 0.16s ease;
}

.cover {
  display: block;
  position: relative;
  z-index: 1;
  width: 100%;
  height: 100%;
  border-radius: inherit;
  background: #fff;
  image-rendering: auto;
  object-fit: cover;
}

.unread-badge {
  position: absolute;
  top: 6px;
  right: 6px;
  min-width: 22px;
  height: 18px;
  box-sizing: border-box;
  padding: 0 5px;
  border-radius: 9px;
  background: #f56c6c;
  color: #fff;
  font-size: 11px;
  line-height: 18px;
  font-weight: 600;
}

.read-progress {
  position: absolute;
  right: 0;
  bottom: 0;
  left: 0;
  z-index: 4;
  height: 4px;
  background: transparent;

  span {
    display: block;
    height: 2px;
    background: #409eff;
  }
}

.cover-book-name {
  position: absolute;
  inset: 0;
  z-index: 3;
  box-sizing: border-box;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 8px 6px 10px;
  overflow: hidden;
  background: transparent;
  color: #1f2329;
  font-size: var(--cover-title-font-size);
  line-height: var(--cover-title-line-height);
  text-align: center;
  font-weight: 500;

  span {
    display: block;
    max-height: 100%;
    overflow: hidden;
    overflow-wrap: anywhere;
    word-break: break-word;
  }
}

.book-list {
  display: grid;
  grid-template-columns: repeat(auto-fill, 380px);
  justify-content: space-around;
  grid-gap: 10px;

  .book {
    user-select: none;
    display: flex;
    cursor: pointer;
    margin-bottom: 18px;
    padding: 24px;
    width: 360px;
    flex-direction: row;
    justify-content: space-around;

    &:hover {
      background: rgba(0, 0, 0, 0.1);
      transition-duration: 0.5s;
    }
  }

  .cover-img {
    width: 84px;
    height: 112px;

    .cover {
      width: 84px;
      height: 112px;
    }
  }

  .info {
    display: flex;
    flex-direction: column;
    justify-content: space-around;
    align-items: left;
    height: 112px;
    margin-left: 20px;
    flex: 1;
    overflow: hidden;
  }

  .name {
    width: fit-content;
    font-size: 16px;
    font-weight: 700;
    color: #33373d;
  }

  .sub {
    display: flex;
    flex-direction: row;
    align-items: baseline;
    justify-content: v-bind('subJustify');
    font-size: 12px;
    font-weight: 600;
    color: #6b6b6b;

    .tags {
      :deep(.el-tag) {
        margin-right: 0.5em;
      }
    }

    .update-info {
      display: flex;

      .dot {
        margin: 0 7px;
      }
    }
  }

  .intro,
  .dur-chapter,
  .last-chapter {
    color: #969ba3;
    font-size: 13px;
    margin-top: 3px;
    font-weight: 500;
    word-wrap: break-word;
    overflow: hidden;
    text-overflow: ellipsis;
    display: -webkit-box;
    -webkit-box-orient: vertical;
    -webkit-line-clamp: 1;
    line-clamp: 1;
    text-align: left;
  }
}

.night {
  .empty-shelf {
    color: #7d828a;
  }

  .cover-frame {
    background: #2b2f36;
  }

  .book-card:focus-visible .cover-frame {
    outline-color: #79bbff;
  }

  .book-card:hover .cover-frame {
    opacity: 0.92;
  }

  .cover-book-name {
    color: #1f2329;
  }

  .read-progress span {
    background: #79bbff;
  }

  .book-list {
    .book:hover {
      background: rgba(255, 255, 255, 0.08);
    }

    .name {
      color: #d7d9dd;
    }
  }
}

.books-wrapper::-webkit-scrollbar {
  width: 0 !important;
}

@media screen and (max-width: 750px) {
  .book-grid {
    gap: 12px 8px;
    padding: 0 4px 20px;
  }

  .cover-book-name {
    padding: 7px 4px 9px;
    font-size: var(--cover-title-font-size);
    line-height: var(--cover-title-line-height);

    span {
      max-height: 100%;
    }
  }

  .book-list {
    display: flex;
    flex-direction: column;

    .book {
      box-sizing: border-box;
      width: 100%;
      margin-bottom: 0;
      padding: 10px 20px;
    }
  }
}
</style>
