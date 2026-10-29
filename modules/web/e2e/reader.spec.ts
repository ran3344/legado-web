import { expect, test, type Page } from '@playwright/test'
import {
  findMarkerById,
  openCatalogAt,
  openReader,
  openReaderMenu,
  pageIndicator,
  readerState,
  scrollReaderBy,
  setChapterProgress,
  swipe,
  visibleMarker,
  watchOffsetStability,
  type Marker,
} from './helpers'

const STABLE_OFFSET_DELTA = 4

type ColumnMetrics = {
  left: number
  right: number
  textWidth: number
  bodyPaddingLeft: string
  pages: number
}

const measureColumn = (page: Page): Promise<ColumnMetrics | null> =>
  page.evaluate(() => {
    const view = document.querySelector('foliate-view') as any
    const renderer = view?.renderer
    const doc: Document | null = renderer?.getContents?.()?.[0]?.doc ?? null
    if (!renderer || !doc) return null
    const win = doc.defaultView!
    const iframeRect = (win.frameElement as HTMLElement).getBoundingClientRect()
    const viewRect = view.getBoundingClientRect()
    const paragraph = doc.querySelector<HTMLElement>(
      'article[data-legado-chapter] p',
    )
    const rect = paragraph?.getClientRects()[0]
    if (!rect) return null
    const bodyStyle = win.getComputedStyle(doc.body)
    return {
      left: Math.round(rect.left + iframeRect.left - viewRect.left),
      right: Math.round(viewRect.right - (rect.right + iframeRect.left)),
      textWidth: Math.round(rect.width),
      bodyPaddingLeft: bodyStyle.paddingLeft,
      pages: renderer.pages as number,
    }
  })

const setRangeSetting = async (page: Page, label: string, value: string) => {
  await page
    .locator('.settings-panel label.range-setting', { hasText: label })
    .locator('input')
    .evaluate((el, v) => {
      const input = el as HTMLInputElement
      input.value = v
      input.dispatchEvent(new Event('input', { bubbles: true }))
    }, value)
  await page.waitForTimeout(200)
}

const dismissReaderMenu = async (page: Page) => {
  const box = await page.locator('.reader-stage').boundingBox()
  if (!box) throw new Error('reader stage missing')
  await page.mouse.click(box.x + box.width / 2, box.y + box.height / 2)
}

test('覆盖式阅读菜单：点击正文显示，再点空白隐藏，且不改变正文尺寸', async ({
  page,
}) => {
  await openReader(page, 'scroll')
  await expect(page.locator('.reader-header')).toBeHidden()
  await expect(page.locator('.reader-footer')).toBeHidden()

  const stage = page.locator('.reader-stage')
  const sizeBefore = await stage.boundingBox()
  await stage.click({ position: { x: 10, y: 10 } })
  await expect(page.locator('.reader-header')).toBeVisible()
  await expect(page.locator('.reader-footer')).toBeVisible()
  const sizeAfterOpen = await stage.boundingBox()
  expect(sizeAfterOpen).toEqual(sizeBefore)

  await dismissReaderMenu(page)
  await expect(page.locator('.reader-header')).toBeHidden()
  await expect(page.locator('.reader-footer')).toBeHidden()
  const sizeAfterClose = await stage.boundingBox()
  expect(sizeAfterClose).toEqual(sizeBefore)
})

test('覆盖式阅读菜单：左右翻页模式点击正文呼出且不翻页', async ({
  page,
}) => {
  await openReader(page, 'horizontal')
  await expect(page.locator('.reader-header')).toBeHidden()
  await expect(page.locator('.reader-footer')).toBeHidden()

  const stage = page.locator('.reader-stage')
  const sizeBefore = await stage.boundingBox()
  const indicatorBefore = await pageIndicator(page)
  await stage.click({ position: { x: 10, y: 10 } })
  await expect(page.locator('.reader-header')).toBeVisible()
  await expect(page.locator('.reader-footer')).toBeVisible()
  expect(await stage.boundingBox()).toEqual(sizeBefore)
  expect(await pageIndicator(page)).toEqual(indicatorBefore)

  await dismissReaderMenu(page)
  await expect(page.locator('.reader-header')).toBeHidden()
  await expect(page.locator('.reader-footer')).toBeHidden()
})

test('覆盖式阅读菜单：显示时左右滑动仍可翻页', async ({ page }) => {
  await openReader(page, 'horizontal')
  const before = await pageIndicator(page)
  expect(before).not.toBeNull()

  await page.locator('.reader-stage').click({ position: { x: 10, y: 10 } })
  await expect(page.locator('.reader-header')).toBeVisible()

  await swipe(page, 'left')
  await expect
    .poll(async () => (await pageIndicator(page))?.current)
    .toBe(before!.current + 1)
  await expect(page.locator('.reader-header')).toBeVisible()
})

test('覆盖式阅读菜单：显示时纵向滚动仍可用', async ({ page }) => {
  await openReader(page, 'scroll')
  const before = await readerState(page)
  expect(before).not.toBeNull()

  await page.locator('.reader-stage').click({ position: { x: 10, y: 10 } })
  await expect(page.locator('.reader-header')).toBeVisible()

  await page.mouse.move(640, 420)
  await page.mouse.wheel(0, 500)
  await expect
    .poll(async () => (await readerState(page))?.start)
    .toBeGreaterThan(before!.start)
  await expect(page.locator('.reader-header')).toBeVisible()
})

test('阅读器底部显示全书进度百分比', async ({ page }) => {
  await openReader(page, 'scroll')
  const indicator = page.locator('.book-progress')
  await expect(indicator).toBeVisible()
  await expect(indicator).toContainText('全书')
  await expect(indicator).toContainText(/%/)

  await page.locator('.reader-stage').click({ position: { x: 10, y: 10 } })
  await expect(page.locator('.reader-header')).toBeVisible()
  await expect(indicator).toHaveClass(/above-footer/)
})

test('书架入口：demo 书籍可以打开阅读器', async ({ page }) => {
  await page.goto('/?demo=1')
  const entry = page.locator('.book-entry')
  await expect(entry).toHaveCount(1)
  await expect(entry).toContainText('Foliate 阅读体验')
  await entry.click()
  await page.waitForURL(/#\/chapter/)
  await page.waitForFunction(() => {
    const view = document.querySelector('foliate-view') as {
      renderer?: { getContents?: () => unknown[] } | null
    } | null
    return Boolean(view?.renderer?.getContents?.().length)
  })
  await expect(page.locator('.reader-error')).toHaveCount(0)
  const state = await readerState(page)
  expect(state?.currentChapterId).toBe('0')
})

test('连续滚动：滚到底自动加载下一章，正文不跳动', async ({ page }) => {
  await openReader(page, 'scroll')
  const initial = await readerState(page)
  expect(initial?.scrolled).toBe(true)
  expect(initial?.chapterIds).toEqual(['0'])

  // 逐步向下滚动，直到触发自动 append
  for (let i = 0; i < 80; i += 1) {
    const state = await readerState(page)
    if (!state) break
    if (state.chapterIds.includes('1')) break
    await scrollReaderBy(page, Math.ceil(state.size * 1.1))
    await page.waitForTimeout(30)
  }
  await expect
    .poll(async () => (await readerState(page))?.chapterIds)
    .toContain('1')

  // append 之后正文不应再移动（轻微跳动回归）
  const stability = await watchOffsetStability(page, 600, 25)
  expect(stability.samples).toBeGreaterThan(3)
  expect(stability.delta).toBeLessThanOrEqual(STABLE_OFFSET_DELTA)
})

test('连续滚动：从后章滚回顶部补上前一章，正文不跳动', async ({ page }) => {
  await openReader(page, 'scroll')
  await openCatalogAt(page, 13)
  await expect
    .poll(async () => (await readerState(page))?.chapterIds)
    .toEqual(expect.arrayContaining(['12', '13']))

  // 逐小步向上滚动：第一次越过补章阈值时锁定当前可见段落，
  // 随后触发补章（11）；补偿完成后，同一段落的视口位置应保持不变。
  let markerBefore: Marker | null = null
  let crossed = false
  let prepended = false
  for (let i = 0; i < 90 && !prepended; i += 1) {
    const state = await readerState(page)
    if (!state) break
    if (!crossed && state.start <= state.size * 1.5) {
      crossed = true
      markerBefore = await visibleMarker(page)
    }
    await scrollReaderBy(page, -Math.ceil(state.size * 0.8))
    await page.waitForTimeout(60)
    prepended = ((await readerState(page))?.chapterIds ?? []).includes('11')
  }
  expect(prepended).toBe(true)

  const stability = await watchOffsetStability(page, 600, 25)
  expect(stability.samples).toBeGreaterThan(3)
  expect(stability.delta).toBeLessThanOrEqual(STABLE_OFFSET_DELTA)

  if (markerBefore?.id) {
    const markerAfter = await findMarkerById(page, markerBefore.id)
    expect(markerAfter).not.toBeNull()
    expect(
      Math.abs(markerAfter!.viewOffset - markerBefore.viewOffset),
    ).toBeLessThanOrEqual(STABLE_OFFSET_DELTA)
  }
})

test('进度条 seek：章节内定位稳定不抖动', async ({ page }) => {
  await openReader(page, 'scroll')
  const before = await readerState(page)
  expect(before?.currentChapterId).toBe('0')

  await setChapterProgress(page, 30)
  const after = await readerState(page)
  expect(after?.currentChapterId).toBe('0')
  expect(after!.start).toBeGreaterThan(before!.start + 200)

  const stability = await watchOffsetStability(page, 500, 25)
  expect(stability.samples).toBeGreaterThan(3)
  expect(stability.delta).toBeLessThanOrEqual(STABLE_OFFSET_DELTA)

  // 拖回接近章节开头
  await setChapterProgress(page, 3)
  const nearStart = await readerState(page)
  expect(nearStart?.currentChapterId).toBe('0')
  expect(nearStart!.start).toBeLessThan(after!.start - 200)
})

test('点击模式：九宫格左右翻页与中间菜单', async ({ page }) => {
  await openReader(page, 'click')
  const initial = await pageIndicator(page)
  expect(initial).not.toBeNull()
  expect(initial!.current).toBe(1)

  // 右中 → 下一页
  await page.locator('.click-cell[aria-label="右中"]').click()
  await expect
    .poll(async () => (await pageIndicator(page))?.current)
    .toBe(initial!.current + 1)

  // 左中 → 上一页
  await page.locator('.click-cell[aria-label="左中"]').click()
  await expect
    .poll(async () => (await pageIndicator(page))?.current)
    .toBe(initial!.current)

  // 正中 → 呼出覆盖式阅读菜单
  await page.locator('.click-cell[aria-label="正中"]').click()
  await expect(page.locator('.reader-header')).toBeVisible()
  await expect(page.locator('.reader-footer')).toBeVisible()

  // 顶部/底部边缘 → 也能呼出菜单，且不触发翻页
  await dismissReaderMenu(page)
  await expect(page.locator('.reader-header')).toBeHidden()
  const beforeEdgeTap = await pageIndicator(page)
  await page.locator('.reader-menu-edge-top').click()
  await expect(page.locator('.reader-header')).toBeVisible()
  expect(await pageIndicator(page)).toEqual(beforeEdgeTap)

  await dismissReaderMenu(page)
  await expect(page.locator('.reader-footer')).toBeHidden()
  await page.locator('.reader-menu-edge-bottom').click()
  await expect(page.locator('.reader-footer')).toBeVisible()
  expect(await pageIndicator(page)).toEqual(beforeEdgeTap)

  // 菜单显示时左滑 → 仍然翻页
  const beforeMenuSwipe = await pageIndicator(page)
  await swipe(page, 'left')
  await expect
    .poll(async () => (await pageIndicator(page))?.current)
    .toBe(beforeMenuSwipe!.current + 1)
  await expect(page.locator('.reader-footer')).toBeVisible()

  // 再点底部栏空白处 → 收起菜单
  await page.locator('.reader-footer').click({ position: { x: 2, y: 2 } })
  await expect(page.locator('.reader-footer')).toBeHidden()
})

test('左右翻页：逐页前进不跳页（章节边界回归）', async ({ page }) => {
  await openReader(page, 'horizontal')
  const first = await pageIndicator(page)
  expect(first).not.toBeNull()
  expect(first!.current).toBe(1)

  const keyOf = (indicator: { current: number; total: number }) =>
    `${indicator.current}/${indicator.total}`
  let previous = first!
  const visited = new Set<string>([keyOf(first!)])
  let transitions = 0

  // 从第 1 页连续左滑，直到进入下一章；期间每一页都必须按 +1 前进
  for (let i = 0; i < 50 && transitions < 1; i += 1) {
    await swipe(page, 'left')
    const next = await waitForPageMove(page, keyOf(previous))
    if (!next) continue
    const key = keyOf(next)
    if (visited.has(key)) continue
    visited.add(key)
    if (next.total !== previous.total) {
      // 章节边界：上一章必须已经停在其最后一页，新章从第 1 页开始
      expect(previous.current).toBe(previous.total)
      expect(next.current).toBe(1)
      transitions += 1
    } else {
      expect(next.current).toBe(previous.current + 1)
    }
    previous = next
  }
  expect(transitions).toBe(1)

  // 新章第 1 页向左滑回，应回到上一章最后一页
  await swipe(page, 'right')
  const back = await waitForPageMove(page, keyOf(previous))
  expect(back).not.toBeNull()
  expect(back!.current).toBe(back!.total)

  // 第一章第 1 页继续右滑不应越界
  await openReader(page, 'horizontal')
  const atStart = await pageIndicator(page)
  expect(atStart?.current).toBe(1)
  await swipe(page, 'right')
  await page.waitForTimeout(400)
  const stillStart = await pageIndicator(page)
  expect(stillStart?.current).toBe(1)
})

test('左右翻页：章末甩动不落空白页，末页进度顶满', async ({ page }) => {
  await page.setViewportSize({ width: 412, height: 915 })
  await openReader(page, 'horizontal')

  // 模拟真实书籍：章节末尾存在空白列（正文后还有占位元素），
  // 导致 renderer.pages > contentPages + 2，旧逻辑会停在空白列上。
  await page.evaluate(() => {
    const view = document.querySelector('foliate-view') as {
      renderer?: {
        getContents?: () => Array<{ doc?: Document }>
        render?: () => void
      } | null
    } | null
    const renderer = view?.renderer
    const doc = renderer?.getContents?.()?.[0]?.doc
    if (!renderer?.render || !doc) return
    const div = doc.createElement('div')
    div.style.height = '800px'
    div.style.width = '1px'
    doc.body.append(div)
    renderer.render()
  })
  await page.waitForTimeout(300)

  const pageState = () =>
    page.evaluate(() => {
      const renderer = (
        document.querySelector('foliate-view') as {
          renderer?: {
            page?: number
            pages?: number
            contentPages?: number
          } | null
        } | null
      )?.renderer
      return {
        page: renderer?.page ?? 0,
        pages: renderer?.pages ?? 0,
        contentPages: renderer?.contentPages ?? 0,
      }
    })

  // 逐页慢滑到最后一页
  let state = await pageState()
  expect(state.contentPages).toBeGreaterThan(0)
  for (let i = 0; i < 40; i += 1) {
    await swipe(page, 'left', 330)
    await page.waitForTimeout(450)
    state = await pageState()
    const indicator = await pageIndicator(page)
    if (
      indicator &&
      indicator.current === indicator.total &&
      state.page >= state.contentPages
    )
      break
  }
  const lastIndicator = await pageIndicator(page)
  expect(lastIndicator?.current).toBe(lastIndicator?.total)

  // 末页进度条应顶满（旧逻辑最多到 (total-1)/total）
  const progress = Number(
    await page.locator('.progress-control input').inputValue(),
  )
  expect(progress).toBeGreaterThanOrEqual(99)

  // 最后一页快速左滑：应直接进入下一章第 1 页，而不是停在空白页
  const cdp = await page.context().newCDPSession(page)
  try {
    const steps = 3
    for (let i = 0; i <= steps; i += 1) {
      const x = Math.round(330 + ((80 - 330) * i) / steps)
      const type =
        i === 0 ? 'touchStart' : i === steps ? 'touchEnd' : 'touchMove'
      await cdp.send('Input.dispatchTouchEvent', {
        type,
        touchPoints:
          type === 'touchEnd'
            ? []
            : [{ x, y: 450, id: 1, radiusX: 3, radiusY: 3, force: 1 }],
      })
      await page.waitForTimeout(i === steps ? 1 : 12)
    }
  } finally {
    await cdp.detach()
  }
  await page.waitForTimeout(900)

  const after = await pageIndicator(page)
  expect(after?.current).toBe(1)
  const chapterId = (await readerState(page))?.currentChapterId
  expect(chapterId).not.toBe('0')
})

const waitForPageMove = async (
  page: Page,
  previousKey: string,
): Promise<{ current: number; total: number } | null> => {
  let lastKey = previousKey
  let lastIndicator: { current: number; total: number } | null = null
  let stableSamples = 0
  for (let i = 0; i < 40; i += 1) {
    await page.waitForTimeout(60)
    const indicator = await pageIndicator(page)
    if (!indicator) continue
    const key = `${indicator.current}/${indicator.total}`
    if (key === lastKey) {
      stableSamples += 1
    } else {
      lastKey = key
      lastIndicator = indicator
      stableSamples = 0
    }
    if (stableSamples >= 2 && lastKey !== previousKey) return lastIndicator
  }
  return lastIndicator
}

for (const mode of ['scroll', 'horizontal'] as const) {
  test(`阅读设置：${mode} 调整页边距当场重排，无需刷新且左右对称`, async ({
    page,
  }) => {
    await openReader(page, mode)
    await openReaderMenu(page)
    await page.locator('.reader-tools button[title="阅读设置"]').click()
    await expect(page.locator('.settings-panel')).toBeVisible()

    const before = await measureColumn(page)
    expect(before).not.toBeNull()

    await setRangeSetting(page, '左右', '40')
    const after = await measureColumn(page)
    expect(after).not.toBeNull()
    // 页边距只能生效一次：左右各 40px。以前拖动时会额外叠一层 body padding，
    // 且 paginator 的动态重排被 `!this.document` 守卫拦掉，导致预览错位、
    // 必须刷新页面才恢复正确版式。
    expect(Math.abs(after!.left - 40)).toBeLessThanOrEqual(2)
    expect(Math.abs(after!.right - 40)).toBeLessThanOrEqual(2)
    expect(Math.abs(after!.left - after!.right)).toBeLessThanOrEqual(2)
    expect(after!.textWidth).toBeLessThan(before!.textWidth - 30)

    // 字号变化同样要即时重排（页数跟着变），而不是等刷新
    await setRangeSetting(page, '字号', '26')
    const bigger = await measureColumn(page)
    expect(bigger!.pages).not.toBe(after!.pages)
  })
}
