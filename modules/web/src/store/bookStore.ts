import { defineStore } from 'pinia'
import API from '@api'
import type {
  BaseBook,
  Book,
  BookChapter,
  BookProgress,
  SeachBook,
} from '@/book'
import type { ReadClickActions, webReadConfig } from '@/web'
import { ElMessage } from 'element-plus/es'
import {
  defaultReadClickActions,
  isReadClickAction,
  readClickAreaKeys,
} from '@/config/readClickActions'

const default_config: webReadConfig = {
  theme: 0,
  font: 0,
  fontSize: 18,
  readWidth: 800,
  readMode: 'scroll',
  reSegment: false,
  customFontName: '',
  clickActions: { ...defaultReadClickActions },
  spacing: {
    paragraph: 1,
    line: 0.8,
    letter: 0,
  },
  margin: {
    top: 16,
    right: 16,
    bottom: 16,
    left: 16,
  },
}

const normalizeClickActions = (
  actions: Partial<ReadClickActions> = {},
): ReadClickActions => {
  const result = { ...defaultReadClickActions, ...actions }
  for (const key of readClickAreaKeys) {
    if (!isReadClickAction(result[key]))
      result[key] = defaultReadClickActions[key]
  }
  return result
}

let webReadConfigLoadedDate: Date | undefined
let catalogRequestId = 0

export const useBookStore = defineStore('book', {
  state: () => {
    return {
      searchBooks: [] as SeachBook[],
      shelf: [] as Book[],
      catalog: [] as BookChapter[],
      catalogBookUrl: '',
      readingBook: { chapterPos: 0, chapterIndex: 0 } as BaseBook & {
        chapterPos: number
        chapterIndex: number
        isSeachBook?: boolean
      },
      popCataVisible: false,
      contentLoading: true,
      showContent: false,
      config: default_config,
      miniInterface: false,
      readSettingsVisible: false,
    }
  },
  getters: {
    bookProgress: (state): BookProgress | undefined => {
      if (state.catalog.length == 0) return
      const { chapterIndex, chapterPos, name, author } = state.readingBook
      const title = state.catalog[chapterIndex]?.title
      if (!title) return
      return {
        name,
        author,
        durChapterIndex: chapterIndex,
        durChapterPos: chapterPos,
        durChapterTime: new Date().getTime(),
        durChapterTitle: title,
        bookUrl: state.readingBook.bookUrl,
        bookKey: state.readingBook.bookUrl?.startsWith('local:')
          ? state.readingBook.bookUrl.slice('local:'.length)
          : undefined,
      }
    },
    theme: state => {
      return state.config.theme
    },
    isNight: state => state.config.theme == 6,
  },
  actions: {
    /**
     * 从后端加载书架书籍，优先返回内存缓存
     * @param silent 后台轮询时静默失败，不弹错误提示
     */
    async loadBookShelf(silent = false): Promise<Book[]> {
      const fetchBookshellf_promise = API.getBookShelf().then(resp => {
        console.log('API.getBookShelf数据返回')
        const { isSuccess, data, errorMsg } = resp.data
        if (isSuccess === true) {
          this.shelf = data
        } else {
          if (errorMsg.includes('还没有添加小说') && this.shelf.length > 0) {
            return (this.shelf = [])
          }
          if (!silent) ElMessage.error(errorMsg ?? '后端返回格式错误！')
        }
        console.log('书架数据已更新')
        return this.shelf
      }).catch(error => {
        // 后台轮询失败时保留当前书架，下次轮询再试
        if (this.shelf.length === 0 || !silent) throw error
        console.warn('书架刷新失败，保留当前列表', error)
        return this.shelf
      })

      if (this.shelf.length > 0) {
        // bookshelf data fetched before:do not await
        console.log('返回缓存书架数据')
        return this.shelf
      } else {
        console.log('从阅读后端获取书架数据...')
        return await fetchBookshellf_promise
      }
    },
    /** 从后端加载书籍目录，优先返回内存缓存 */
    async loadWebCatalog(
      book: typeof this.readingBook,
    ): Promise<BookChapter[]> {
      const { bookUrl, name, chapterIndex } = book
      const requestId = ++catalogRequestId
      const fetchChapterList_promise = API.getChapterList(
        bookUrl as string,
      ).then(res => {
        const { isSuccess, data, errorMsg } = res.data
        if (isSuccess === false) {
          ElMessage.error(errorMsg)
          throw new Error()
        }
        if (requestId === catalogRequestId) {
          this.catalog = data
          this.catalogBookUrl = bookUrl as string
        }
        console.log(`书籍${name}: 章节目录已更新`)
        return data
      })
      if (
        bookUrl === this.readingBook.bookUrl &&
        this.catalogBookUrl === bookUrl &&
        this.catalog.length > 0 &&
        this.catalog.length - 1 >= chapterIndex
      ) {
        console.log(`返回书籍《${name}》 缓存的章节目录`)
        return this.catalog
      } else {
        console.log(`从阅读后端获取书籍《${name}》 章节目录数据...`)
        return await fetchChapterList_promise
      }
    },
    setPopCataVisible(visible: boolean) {
      this.popCataVisible = visible
    },
    setContentLoading(loading: boolean) {
      this.contentLoading = loading
    },
    setReadingBook(readingBook: typeof this.readingBook) {
      this.readingBook = readingBook
    },
    /** 只从从后端加载一次web阅读配置 */
    async loadWebConfig() {
      if (webReadConfigLoadedDate === undefined) {
        const _config = await API.getReadConfig()
        webReadConfigLoadedDate = new Date()
        console.log(
          `${this.$id}.loadWebConfig: ${webReadConfigLoadedDate.toLocaleString()}成功加载阅读配置`,
        )
        return this.setConfig(_config)
      }
      console.log(
        `${this.$id}.loadWebConfig: 已于${webReadConfigLoadedDate.toLocaleString()}成功加载`,
      )
    },
    setConfig(config?: Partial<webReadConfig>) {
      const nextConfig: Partial<webReadConfig> = config ?? {}
      this.config = {
        ...this.config,
        ...nextConfig,
        spacing: {
          ...default_config.spacing,
          ...this.config.spacing,
          ...nextConfig.spacing,
        },
        margin: (() => {
          const merged = {
            ...default_config.margin,
            ...this.config.margin,
            ...nextConfig.margin,
          }
          // 修复历史版本左右/上下不对称的数据（旧 UI 只写了单侧）
          merged.right = merged.left
          merged.bottom = merged.top
          return merged
        })(),
        clickActions: normalizeClickActions({
          ...this.config.clickActions,
          ...nextConfig.clickActions,
        }),
      }
    },
    setReadSettingsVisible(visible: boolean) {
      this.readSettingsVisible = visible
    },
    setShowContent(visible: boolean) {
      this.showContent = visible
    },
    setMiniInterface(mini: boolean) {
      this.miniInterface = mini
    },
    removeBook(bookUrl: string) {
      this.shelf = this.shelf.filter(book => book.bookUrl !== bookUrl)
    },
    async setSearchBooks(books: SeachBook[]) {
      books.forEach(book => {
        const isSeachBook = this.shelf.every(
          item => item.bookUrl !== book.bookUrl,
        )
        if (isSeachBook === true) {
          this.searchBooks.push(book)
        }
      })
    },
    clearSearchBooks() {
      this.searchBooks = []
    },
    /** 1.保存进度到app 2.修改内存中的数据*/
    async saveBookProgress() {
      if (!this.bookProgress) return Promise.resolve()
      const { bookUrl } = this.readingBook
      const shelfRaw = toRaw(this.shelf)
      const findIndex = shelfRaw.findIndex(book => book.bookUrl === bookUrl)
      if (findIndex > -1) {
        this.shelf[findIndex] = Object.assign(
          {},
          shelfRaw[findIndex],
          this.bookProgress,
        )
      }
      // 直接关闭浏览器时 http请求可能被取消
      // return API.saveBookProgress(this.bookProgress)
      return API.saveBookProgressWithBeacon(this.bookProgress)
    },
  },
})
