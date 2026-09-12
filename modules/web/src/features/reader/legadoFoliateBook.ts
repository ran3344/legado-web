import DOMPurify from 'dompurify'
import API from '@/api'
import type { Book, BookChapter } from '@/book'
import { reSegmentText } from './reSegmentText'

type FoliateSection = {
  id: string
  index: number
  size: number
  linear: string
  load: () => Promise<string>
  unload: () => void
  createDocument: () => Promise<Document>
}

export type ChapterContentLoader = (
  book: Book,
  chapter: BookChapter,
  signal?: AbortSignal,
) => Promise<string>

export type LegadoFoliateBook = {
  dir: 'ltr'
  metadata: { title: string; author: string; language: string }
  rendition: { layout: 'reflowable' }
  sections: FoliateSection[]
  toc: Array<{ label: string; href: number; subitems?: never[] }>
  resolveHref: (target: string | number) => { index: number; anchor?: number }
  getCover?: () => Promise<Blob | undefined>
}

export type ContinuousLegadoFoliateBook = LegadoFoliateBook & {
  continuous: {
    appendNext: (doc: Document) => Promise<boolean>
    prependPrevious: (doc: Document) => Promise<boolean>
    ensureChapter: (doc: Document, index: number) => Promise<void>
    jumpToChapter: (doc: Document, index: number) => Promise<boolean>
    trimWindow: (doc: Document, keepFromIndex: number) => boolean
    getChapterLength: (index: number) => number | undefined
    cancelPending: () => void
    dispose: () => void
    getChapterAt: (
      doc: Document,
      position: number,
      horizontal?: boolean,
      range?: Range,
    ) => number
  }
}

const escapeHtml = (value: string) =>
  value
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#039;')

type ChapterPayload = { html: string; length: number }
const CHAPTER_CACHE_LIMIT = 24
const CHAPTER_REQUEST_TIMEOUT = 20_000
const CHAPTER_RETRY_DELAY = 120

const waitForRetry = (signal?: AbortSignal) =>
  new Promise<void>((resolve, reject) => {
    const cleanup = () => signal?.removeEventListener('abort', abort)
    const finish = () => {
      cleanup()
      resolve()
    }
    const abort = () => {
      clearTimeout(timer)
      cleanup()
      reject(new DOMException('章节请求已取消', 'AbortError'))
    }
    const timer = setTimeout(finish, CHAPTER_RETRY_DELAY)
    signal?.addEventListener('abort', abort, { once: true })
  })

const createChapterCache = () => {
  const cache = new Map<number, Promise<ChapterPayload>>()
  const settled = new Set<number>()
  const touch = (index: number, request: Promise<ChapterPayload>) => {
    cache.delete(index)
    cache.set(index, request)
    while (cache.size > CHAPTER_CACHE_LIMIT) {
      const oldest = cache.keys().next().value as number | undefined
      if (oldest === undefined) break
      cache.delete(oldest)
    }
  }
  return {
    get(index: number) {
      const request = cache.get(index)
      if (request) touch(index, request)
      return request
    },
    set(index: number, request: Promise<ChapterPayload>) {
      settled.delete(index)
      touch(index, request)
      void request.then(
        () => settled.add(index),
        () => settled.delete(index),
      )
      void request.catch(() => {
        if (cache.get(index) === request) cache.delete(index)
      })
      return request
    },
    isSettled(index: number) {
      return settled.has(index)
    },
  }
}

const createTaskQueue = () => {
  let tail = Promise.resolve()
  let generation = 0
  return {
    enqueue<T>(task: () => Promise<T>) {
      const requestedGeneration = generation
      const run = tail.then(
        () =>
          requestedGeneration === generation
            ? task()
            : Promise.resolve(undefined as T),
        () =>
          requestedGeneration === generation
            ? task()
            : Promise.resolve(undefined as T),
      )
      tail = run.then(
        () => undefined,
        () => undefined,
      )
      return run
    },
    invalidate() {
      generation += 1
    },
  }
}

const getTextLength = (html: string) => {
  const doc = new DOMParser().parseFromString(html, 'text/html')
  return Math.max((doc.body.textContent || '').length, 1)
}

const plainTextToHtml = (content: string) => {
  const paragraphs = content
    .replace(/\r\n?/g, '\n')
    .split('\n')
    .map(line => line.replace(/\u3000/g, ' ').trim())
    .filter(Boolean)
  return (paragraphs.length ? paragraphs : [''])
    .map(line => `<p>${escapeHtml(line)}</p>`)
    .join('')
}

const looksLikeHtml = (content: string) => /<\/?[a-z][^>]*>/i.test(content)

const sanitizeChapter = (
  content: string,
  chapter: BookChapter,
  book: Book,
  reSegment = false,
  catalogIndex = chapter.index,
) => {
  const source = looksLikeHtml(content)
    ? content
    : plainTextToHtml(reSegment ? reSegmentText(content, chapter.title) : content)
  const sanitized = DOMPurify.sanitize(source, {
    USE_PROFILES: { html: true },
    FORBID_TAGS: ['form', 'iframe', 'object', 'embed', 'style', 'script'],
    FORBID_ATTR: ['style', 'srcdoc', 'onerror', 'onload'],
  })
  const doc = document.implementation.createHTMLDocument(chapter.title)
  doc.documentElement.lang = 'zh-CN'
  doc.head.innerHTML =
    '<meta charset="utf-8"><meta name="color-scheme" content="light dark">'
  doc.body.innerHTML = `<article data-legado-chapter="${catalogIndex}"><h1>${escapeHtml(chapter.title)}</h1>${sanitized}</article>`
  doc.querySelectorAll<HTMLImageElement>('img').forEach(image => {
    const src = image.getAttribute('src')
    if (!src || src.startsWith('data:') || src.startsWith('blob:')) return
    image.src = API.getProxyImageUrl(book.bookUrl, src, 1200)
    image.loading = 'lazy'
    image.alt ||= chapter.title
  })
  return '<!doctype html>' + doc.documentElement.outerHTML
}

const chapterDocument = async (
  book: Book,
  chapter: BookChapter,
  loadContent?: ChapterContentLoader,
  reSegment = false,
  catalogIndex = chapter.index,
  parentSignal?: AbortSignal,
) => {
  const controller = new AbortController()
  const timeout = setTimeout(() => controller.abort(), CHAPTER_REQUEST_TIMEOUT)
  const abortParent = () => controller.abort()
  parentSignal?.addEventListener('abort', abortParent, { once: true })
  try {
    let content = ''
    let lastError: unknown
    for (let attempt = 0; attempt < 2; attempt += 1) {
      try {
        content = loadContent
          ? await loadContent(book, chapter, controller.signal)
          : await (async () => {
              const response = await API.getBookContent(
                book.bookUrl,
                chapter.index,
                controller.signal,
              )
              if (!response.data.isSuccess)
                throw new Error(response.data.errorMsg || '章节内容获取失败')
              return response.data.data || ''
            })()
        lastError = undefined
        break
      } catch (error) {
        lastError = error
        if (controller.signal.aborted || attempt === 1) throw error
        await waitForRetry(controller.signal)
      }
    }
    if (lastError && !content) throw lastError
    const html = sanitizeChapter(content, chapter, book, reSegment, catalogIndex)
    return { html, length: getTextLength(html) }
  } finally {
    clearTimeout(timeout)
    parentSignal?.removeEventListener('abort', abortParent)
  }
}

export const createLegadoFoliateBook = async (
  book: Book,
  catalog: BookChapter[],
  loadContent?: ChapterContentLoader,
  reSegment = false,
): Promise<LegadoFoliateBook> => {
  const cache = createChapterCache()
  const getChapter = (chapter: BookChapter, catalogIndex: number) => {
    const cached = cache.get(catalogIndex)
    if (cached) return cached
    const request = chapterDocument(
      book,
      chapter,
      loadContent,
      reSegment,
      catalogIndex,
    )
    return cache.set(catalogIndex, request)
  }
  const urls = new Map<number, string>()
  const sections = catalog.map((chapter, index) => ({
    id: `chapter-${index}`,
    index,
    size: 1,
    linear: 'yes',
    load: async () => {
      const { html, length } = await getChapter(chapter, index)
      const url = `data:text/html;charset=utf-8,${encodeURIComponent(html)}`
      urls.set(index, url)
      sections[index].size = length
      return url
    },
    unload: () => {
      const url = urls.get(index)
      if (url?.startsWith('blob:')) URL.revokeObjectURL(url)
      urls.delete(index)
    },
    createDocument: async () => {
      const { html } = await getChapter(chapter, index)
      return new DOMParser().parseFromString(html, 'text/html')
    },
  }))

  return {
    dir: 'ltr',
    metadata: { title: book.name, author: book.author, language: 'zh-CN' },
    rendition: { layout: 'reflowable' },
    sections,
    toc: catalog.map((chapter, index) => ({
      label: chapter.title,
      href: index,
    })),
    resolveHref: target => {
      if (typeof target === 'number')
        return { index: Math.max(0, Math.min(target, sections.length - 1)) }
      const match = target.match(/chapter-(\d+)/)
      const chapterIndex = match ? Number(match[1]) : Number(target)
      return { index: Number.isFinite(chapterIndex) ? chapterIndex : 0 }
    },
    getCover: async () => {
      const cover =
        book.customCoverUrl || book.coverUrl || book.persistedCoverUrl
      if (!cover) return undefined
      const response = await fetch(API.getProxyCoverUrl(cover))
      return response.ok ? response.blob() : undefined
    },
  }
}

export const createContinuousLegadoFoliateBook = async (
  book: Book,
  catalog: BookChapter[],
  loadContent?: ChapterContentLoader,
  reSegment = false,
): Promise<ContinuousLegadoFoliateBook> => {
  const cache = createChapterCache()
  const chapterLengths = new Map<number, number>()
  const getChapter = (
    chapter: BookChapter,
    catalogIndex: number,
    signal?: AbortSignal,
  ) => {
    const cached = cache.get(catalogIndex)
    if (cached && (!signal || cache.isSettled(catalogIndex))) return cached
    const request = chapterDocument(
      book,
      chapter,
      loadContent,
      reSegment,
      catalogIndex,
      signal,
    )
    return cache.set(catalogIndex, request)
  }
  let nextIndex = 1
  let firstIndex = 0
  const appendQueue = createTaskQueue()
  const prependQueue = createTaskQueue()
  const jumpQueue = createTaskQueue()
  const documentQueue = createTaskQueue()
  let navigationGeneration = 0
  const activeControllers = new Set<AbortController>()
  const beginOperation = () => {
    const controller = new AbortController()
    activeControllers.add(controller)
    return controller
  }
  const endOperation = (controller: AbortController) => {
    activeControllers.delete(controller)
  }
  const cancelActiveOperations = () => {
    activeControllers.forEach(controller => controller.abort())
    activeControllers.clear()
  }
  const invalidateQueues = () => {
    appendQueue.invalidate()
    prependQueue.invalidate()
    jumpQueue.invalidate()
    documentQueue.invalidate()
  }
  const cancelPending = () => {
    navigationGeneration += 1
    invalidateQueues()
    cancelActiveOperations()
  }
  const firstChapter = catalog[0]
  if (!firstChapter) throw new Error('章节目录为空')
  const first = await getChapter(firstChapter, 0)
  chapterLengths.set(0, first.length)
  const section = {
    id: 'continuous-book',
    index: 0,
    size: first.length,
    linear: 'yes',
    load: async () =>
      `data:text/html;charset=utf-8,${encodeURIComponent(first.html)}`,
    unload: () => {},
    createDocument: async () =>
      new DOMParser().parseFromString(first.html, 'text/html'),
  }
  const jumpToChapter = (doc: Document, index: number) => {
    cancelPending()
    const requestedGeneration = navigationGeneration
    return jumpQueue
      .enqueue(async () => {
        if (requestedGeneration !== navigationGeneration) return false
        const startIndex = Math.max(0, index - 1)
        const chapters = catalog.slice(startIndex, index + 1)
        if (!chapters.length) return false
        const controller = beginOperation()
        try {
          const loaded = await Promise.all(
            chapters.map((chapter, offset) =>
              getChapter(chapter, startIndex + offset, controller.signal),
            ),
          )
          return documentQueue.enqueue(async () => {
            if (controller.signal.aborted || requestedGeneration !== navigationGeneration)
              return false
            const fragment = doc.createDocumentFragment()
            let totalLength = 0
            loaded.forEach(({ html, length }) => {
              const chapterDoc = new DOMParser().parseFromString(html, 'text/html')
              const article = chapterDoc.body.firstElementChild
              if (!article) return
              fragment.append(article.cloneNode(true))
              totalLength += length
            })
            if (!fragment.childNodes.length) return false
            doc.body.replaceChildren(fragment)
            section.size = totalLength
            loaded.forEach(({ length }, offset) => {
              chapterLengths.set(startIndex + offset, length)
            })
            firstIndex = startIndex
            nextIndex = index + 1
            return true
          })
        } finally {
          endOperation(controller)
        }
      })
      .catch(() => false)
  }
  const appendNext = (doc: Document) => {
    const requestedGeneration = navigationGeneration
    return appendQueue
      .enqueue(async () => {
        if (
          requestedGeneration !== navigationGeneration ||
          nextIndex >= catalog.length
        )
          return false
        const index = nextIndex
        const controller = beginOperation()
        try {
          const chapter = catalog[index]
          const { html, length } = await getChapter(
            chapter,
            index,
            controller.signal,
          )
          return documentQueue.enqueue(async () => {
            if (controller.signal.aborted || requestedGeneration !== navigationGeneration)
              return false
            const chapterDoc = new DOMParser().parseFromString(html, 'text/html')
            const article = chapterDoc.body.firstElementChild
            if (!article) return false
            doc.body.append(article.cloneNode(true))
            section.size += length
            chapterLengths.set(index, length)
            nextIndex = index + 1
            return true
          })
        } finally {
          endOperation(controller)
        }
      })
      .catch(() => false)
  }
  const prependPrevious = (doc: Document) => {
    const requestedGeneration = navigationGeneration
    return prependQueue
      .enqueue(async () => {
        if (requestedGeneration !== navigationGeneration || firstIndex <= 0)
          return false
        const index = firstIndex - 1
        const controller = beginOperation()
        try {
          const { html, length } = await getChapter(
            catalog[index],
            index,
            controller.signal,
          )
          return documentQueue.enqueue(async () => {
            if (controller.signal.aborted || requestedGeneration !== navigationGeneration)
              return false
            const chapterDoc = new DOMParser().parseFromString(html, 'text/html')
            const article = chapterDoc.body.firstElementChild
            if (!article) return false
            const firstArticle = doc.querySelector<HTMLElement>('[data-legado-chapter]')
            if (firstArticle) firstArticle.before(article.cloneNode(true))
            else doc.body.append(article.cloneNode(true))
            section.size += length
            chapterLengths.set(index, length)
            firstIndex = index
            return true
          })
        } finally {
          endOperation(controller)
        }
      })
      .catch(() => false)
  }
  const ensureChapter = (doc: Document, index: number) => {
    const requestedGeneration = navigationGeneration
    return appendQueue
      .enqueue(async () => {
        if (
          requestedGeneration !== navigationGeneration ||
          nextIndex > index ||
          nextIndex >= catalog.length
        )
          return
        const target = Math.min(index, catalog.length - 1)
        const start = nextIndex
        const controller = beginOperation()
        try {
          const chapters = catalog.slice(start, target + 1)
          const loaded = await Promise.all(
            chapters.map((chapter, offset) =>
              getChapter(chapter, start + offset, controller.signal),
            ),
          )
          await documentQueue.enqueue(async () => {
            if (controller.signal.aborted || requestedGeneration !== navigationGeneration)
              return
            const fragment = doc.createDocumentFragment()
            let totalLength = 0
            loaded.forEach(({ html, length }) => {
              const chapterDoc = new DOMParser().parseFromString(html, 'text/html')
              const article = chapterDoc.body.firstElementChild
              if (!article) return
              fragment.append(article.cloneNode(true))
              totalLength += length
            })
            doc.body.append(fragment)
            section.size += totalLength
            loaded.forEach(({ length }, offset) => {
              chapterLengths.set(start + offset, length)
            })
            nextIndex = target + 1
          })
        } finally {
          endOperation(controller)
        }
      })
      .catch(() => undefined)
  }
  const trimWindow = (doc: Document, keepFromIndex: number) => {
    const keepFrom = Math.max(0, Math.floor(keepFromIndex))
    if (keepFrom <= firstIndex) return false
    const articles = Array.from(
      doc.querySelectorAll<HTMLElement>('[data-legado-chapter]'),
    )
    let removed = false
    let removedLength = 0
    articles.forEach(article => {
      const index = Number(article.dataset.legadoChapter)
      if (!Number.isInteger(index) || index >= keepFrom) return
      removed = true
      removedLength += chapterLengths.get(index) || 0
      article.remove()
      chapterLengths.delete(index)
    })
    if (!removed) return false
    section.size = Math.max(1, section.size - removedLength)
    firstIndex = keepFrom
    return true
  }
  const getChapterAt = (
    doc: Document,
    position: number,
    horizontal = false,
    range?: Range,
  ) => {
    const chapters = Array.from(
      doc.querySelectorAll<HTMLElement>('[data-legado-chapter]'),
    )
    let current = 0
    if (range) {
      const chaptersInRange = [range.startContainer, range.endContainer]
        .map(container => {
          const element =
            container.nodeType === Node.ELEMENT_NODE
              ? (container as Element)
              : container.parentElement
          return element?.closest<HTMLElement>('[data-legado-chapter]')
        })
        .filter((chapter): chapter is HTMLElement => Boolean(chapter))
        .map(chapter => Number(chapter.dataset.legadoChapter || 0))
      if (chaptersInRange.length) return chaptersInRange[0]
    }
    if (!horizontal) {
      chapters.forEach(chapter => {
        if (chapter.offsetTop <= position + 80)
          current = Number(chapter.dataset.legadoChapter || 0)
      })
      return current
    }
    const viewport = doc.defaultView?.innerWidth || 0
    const center = viewport > 0 ? viewport / 2 : 0
    chapters.forEach(chapter => {
      const visible = Array.from(chapter.getClientRects()).some(
        rect => rect.left <= center && rect.right >= center,
      )
      if (visible) current = Number(chapter.dataset.legadoChapter || 0)
    })
    return current
  }
  return {
    dir: 'ltr',
    metadata: { title: book.name, author: book.author, language: 'zh-CN' },
    rendition: { layout: 'reflowable' },
    sections: [section],
    toc: catalog.map((chapter, index) => ({
      label: chapter.title,
      href: index,
    })),
    resolveHref: target => ({ index: typeof target === 'number' ? 0 : 0 }),
    continuous: {
      appendNext,
      prependPrevious,
      ensureChapter,
      jumpToChapter,
      trimWindow,
      getChapterAt,
      getChapterLength: index => chapterLengths.get(index),
      cancelPending: () => {
        cancelPending()
      },
      dispose: () => {
        cancelPending()
      },
    },
  }
}
