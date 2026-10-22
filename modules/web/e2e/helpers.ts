import { expect, type Page } from '@playwright/test'

export type ReaderMode = 'scroll' | 'horizontal' | 'click'

export type ReaderState = {
  scrolled: boolean
  start: number
  end: number
  viewSize: number
  size: number
  pages: number
  page: number
  atStart: boolean
  atEnd: boolean
  chapterCount: number
  chapterIds: string[]
  currentChapterId: string | null
}

export type PageIndicator = { current: number; total: number } | null

export type Marker = {
  start: number
  contentTop: number
  viewOffset: number
  text: string
  chapterId: string
  paragraphIndex: number
  id: string
} | null

export const openReader = async (
  page: Page,
  mode: ReaderMode = 'scroll',
  chapterIndex = 0,
) => {
  await page.addInitScript(
    ({ mode, chapterIndex }) => {
      sessionStorage.setItem('demoMode', 'true')
      sessionStorage.setItem('bookUrl', 'demo:foliate-reading')
      sessionStorage.setItem('bookName', 'Foliate 阅读体验')
      sessionStorage.setItem('bookAuthor', 'Legado Web')
      sessionStorage.setItem('chapterIndex', String(chapterIndex))
      sessionStorage.setItem('chapterPos', '0')
      sessionStorage.setItem('isSeachBook', 'false')
      localStorage.setItem('legado.web.readMode', mode)
      localStorage.removeItem('legado.web.clickActions')
      localStorage.removeItem('legado.web.reSegment')
    },
    { mode, chapterIndex },
  )
  const beforeUrl = page.url()
  await page.goto('/#/chapter')
  // 同一 URL 下 goto 是 same-document 导航，不会真正重载页面；
  // 连续用例（或同用例内二次打开）会保留上一次的阅读位置，必须强制整页刷新。
  if (page.url() === beforeUrl && beforeUrl !== 'about:blank') {
    await page.reload()
  }
  await page.waitForFunction(() => {
    const view = document.querySelector('foliate-view') as {
      renderer?: { getContents?: () => unknown[] } | null
    } | null
    return Boolean(view?.renderer?.getContents?.().length)
  })
  await expect(page.locator('.reader-error')).toHaveCount(0)
  await expect(page.locator('.reader-loading')).toHaveCount(0)
  await expect(page.locator('.progress-control span')).not.toHaveText('0 / 0')
  await page.waitForTimeout(250)
}

export const readerState = (page: Page): Promise<ReaderState | null> =>
  page.evaluate(() => {
    const view = document.querySelector('foliate-view') as {
      renderer?: Record<string, unknown> | null
    } | null
    const r = view?.renderer as {
      scrolled?: boolean
      start?: number
      end?: number
      viewSize?: number
      size?: number
      pages?: number
      page?: number
      atStart?: boolean
      atEnd?: boolean
      getContents?: () => Array<{ doc?: Document }>
    } | null
    if (!r) return null
    const doc = r.getContents?.()?.[0]?.doc
    const articles = doc
      ? Array.from(doc.querySelectorAll<HTMLElement>('[data-legado-chapter]'))
      : []
    const center = (r.start ?? 0) + (r.size ?? 0) / 2
    let currentChapterId: string | null =
      articles[0]?.dataset.legadoChapter ?? null
    for (const el of articles) {
      if (el.offsetTop <= center)
        currentChapterId = el.dataset.legadoChapter ?? currentChapterId
    }
    return {
      scrolled: Boolean(r.scrolled),
      start: r.start ?? 0,
      end: r.end ?? 0,
      viewSize: r.viewSize ?? 0,
      size: r.size ?? 0,
      pages: r.pages ?? 0,
      page: r.page ?? 0,
      atStart: Boolean(r.atStart),
      atEnd: Boolean(r.atEnd),
      chapterCount: articles.length,
      chapterIds: articles.map(el => el.dataset.legadoChapter ?? ''),
      currentChapterId,
    }
  })

export const pageIndicator = async (page: Page): Promise<PageIndicator> => {
  const text =
    (await page.locator('.progress-control span').textContent())?.trim() ?? ''
  const match = text.match(/^(\d+)\s*\/\s*(\d+)$/)
  return match ? { current: Number(match[1]), total: Number(match[2]) } : null
}

export const setChapterProgress = async (page: Page, value: number) => {
  await page.locator('.progress-control input').evaluate((el, v) => {
    const input = el as HTMLInputElement
    input.value = String(v)
    input.dispatchEvent(new Event('input', { bubbles: true }))
    input.dispatchEvent(new Event('change', { bubbles: true }))
  }, value)
  await page.waitForTimeout(250)
}

export const scrollReaderBy = (page: Page, delta: number) =>
  page.evaluate(delta => {
    const view = document.querySelector('foliate-view') as any
    const r = view?.renderer
    if (!r) return
    // scrollBy 受 foliate #scrollBounds 钳制，无法连续滚动；
    // 直接滚动原生滚动容器（iframe -> 视图 div -> #container）以触发 scroll/relocate 事件。
    const doc = r.getContents?.()?.[0]?.doc
    const iframe = doc?.defaultView?.frameElement as HTMLElement | null
    const container = iframe?.parentElement?.parentElement as HTMLElement | null
    if (container) container.scrollTop += delta
    else if (r.scrollBy) r.scrollBy(delta, delta)
  }, delta)

export const scrollReaderToTop = async (page: Page) => {
  for (let i = 0; i < 80; i += 1) {
    const state = await readerState(page)
    if (!state || state.start <= state.size * 0.5) break
    await scrollReaderBy(page, -Math.ceil(state.size * 1.1))
    await page.waitForTimeout(30)
  }
}

export const swipe = async (
  page: Page,
  direction: 'left' | 'right',
  startX = 900,
  y = 420,
) => {
  const cdp = await page.context().newCDPSession(page)
  const dx = direction === 'left' ? -260 : 260
  const steps = 10
  try {
    for (let i = 0; i <= steps; i += 1) {
      const x = Math.round(startX + (dx * i) / steps)
      const type =
        i === 0 ? 'touchStart' : i === steps ? 'touchEnd' : 'touchMove'
      await cdp.send('Input.dispatchTouchEvent', {
        type,
        touchPoints:
          type === 'touchEnd'
            ? []
            : [{ x, y, id: 1, radiusX: 3, radiusY: 3, force: 1 }],
      })
      await page.waitForTimeout(16)
    }
  } finally {
    await cdp.detach()
  }
}

export const visibleMarker = (page: Page): Promise<Marker> =>
  page.evaluate(() => {
    const r = (document.querySelector('foliate-view') as any)?.renderer
    const doc = r?.getContents?.()?.[0]?.doc as Document | null
    if (!r || !doc) return null
    const paragraphs = Array.from(
      doc.querySelectorAll<HTMLElement>('article[data-legado-chapter] p'),
    )
    if (!paragraphs.length) return null
    const center = r.start + r.size / 2
    let best: HTMLElement | null = null
    let bestDist = Infinity
    for (const p of paragraphs) {
      const rect = p.getBoundingClientRect()
      if (rect.height <= 0) continue
      const mid = rect.top + rect.height / 2
      const dist = Math.abs(mid - center)
      if (dist < bestDist) {
        bestDist = dist
        best = p
      }
    }
    if (!best) return null
    const rect = best.getBoundingClientRect()
    const article = best.closest<HTMLElement>('[data-legado-chapter]')
    const chapterId = article?.dataset.legadoChapter ?? ''
    const paragraphsInArticle = article
      ? Array.from(article.querySelectorAll<HTMLElement>('p'))
      : []
    const paragraphIndex = paragraphsInArticle.indexOf(best)
    return {
      start: r.start,
      contentTop: rect.top,
      viewOffset: rect.top - r.start,
      text: best.textContent?.slice(0, 16) ?? '',
      chapterId,
      paragraphIndex,
      id: `${chapterId}:${paragraphIndex}`,
    }
  })

export const findMarkerById = (page: Page, id: string): Promise<Marker> =>
  page.evaluate(id => {
    const r = (document.querySelector('foliate-view') as any)?.renderer
    const doc = r?.getContents?.()?.[0]?.doc as Document | null
    if (!r || !doc) return null
    const [chapterId, rawIndex] = id.split(':')
    const article = doc.querySelector<HTMLElement>(
      `[data-legado-chapter="${chapterId}"]`,
    )
    if (!article) return null
    const paragraph = Array.from(article.querySelectorAll<HTMLElement>('p'))[
      Number(rawIndex)
    ]
    if (!paragraph) return null
    const rect = paragraph.getBoundingClientRect()
    return {
      start: r.start,
      contentTop: rect.top,
      viewOffset: rect.top - r.start,
      text: paragraph.textContent?.slice(0, 16) ?? '',
      chapterId,
      paragraphIndex: Number(rawIndex),
      id,
    }
  }, id)

export const watchOffsetStability = async (
  page: Page,
  durationMs = 500,
  intervalMs = 25,
): Promise<{ min: number; max: number; delta: number; samples: number }> => {
  return page.evaluate(
    async ({ durationMs, intervalMs }) => {
      const r = (document.querySelector('foliate-view') as any)?.renderer
      const doc = r?.getContents?.()?.[0]?.doc as Document | null
      if (!r || !doc) return { min: 0, max: 0, delta: 0, samples: 0 }
      const offsets: number[] = []
      const deadline = performance.now() + durationMs
      while (performance.now() < deadline) {
        const paragraphs = Array.from(
          doc.querySelectorAll<HTMLElement>('article[data-legado-chapter] p'),
        )
        const center = r.start + r.size / 2
        let best: HTMLElement | null = null
        let bestDist = Infinity
        for (const p of paragraphs) {
          const rect = p.getBoundingClientRect()
          if (rect.height <= 0) continue
          const dist = Math.abs(rect.top + rect.height / 2 - center)
          if (dist < bestDist) {
            bestDist = dist
            best = p
          }
        }
        if (best) {
          const rect = best.getBoundingClientRect()
          offsets.push(rect.top - r.start)
        }
        await new Promise(resolve => setTimeout(resolve, intervalMs))
      }
      if (!offsets.length) return { min: 0, max: 0, delta: 0, samples: 0 }
      return {
        min: Math.min(...offsets),
        max: Math.max(...offsets),
        delta: Math.max(...offsets) - Math.min(...offsets),
        samples: offsets.length,
      }
    },
    { durationMs, intervalMs },
  )
}

export const openReaderMenu = async (page: Page) => {
  const header = page.locator('.reader-header')
  if (await header.isVisible()) return
  await page.locator('.reader-stage').click()
  await expect(header).toBeVisible()
}

export const openCatalogAt = async (page: Page, chapterIndex: number) => {
  await openReaderMenu(page)
  await page.locator('.reader-footer button[title="目录"]').first().click()
  const list = page.locator('.catalog-list')
  await expect(list).toBeVisible()
  await list.evaluate(el => {
    el.scrollTop = el.scrollHeight
  })
  await page.waitForTimeout(100)
  const item = page.locator('.catalog-item', {
    hasText: `第${chapterIndex}章`,
  })
  await item.click()
  await expect(page.locator('.catalog-panel')).toHaveCount(0)
}
