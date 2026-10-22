import { expect, test } from '@playwright/test'

type ShelfBook = {
  name: string
  author: string
  bookUrl: string
  tocUrl: string
  origin: string
  originName: string
  type: number
  group: number
  latestChapterTime: number
  lastCheckTime: number
  lastCheckCount: number
  totalChapterNum: number
  durChapterTitle: string
  durChapterIndex: number
  durChapterPos: number
  durChapterTime: number
  canUpdate: boolean
  order: number
  originOrder: number
  syncTime: number
}

const makeBook = (id: string, name: string): ShelfBook => ({
  name,
  author: '测试作者',
  bookUrl: `local:${id}`,
  tocUrl: '',
  origin: 'local',
  originName: `${name}.txt`,
  type: 0,
  group: 0,
  latestChapterTime: 0,
  lastCheckTime: 0,
  lastCheckCount: 0,
  totalChapterNum: 10,
  durChapterTitle: '第一章',
  durChapterIndex: 0,
  durChapterPos: 0,
  durChapterTime: Date.now(),
  canUpdate: false,
  order: 0,
  originOrder: 0,
  syncTime: 0,
})

test('书架：自动轮询和刷新按钮都能显示设备新导入的书', async ({ page }) => {
  await page.clock.install()
  let books = [makeBook('1', '第一本')]
  await page.route('**/getBookshelf', route =>
    route.fulfill({
      contentType: 'application/json',
      body: JSON.stringify({ isSuccess: true, errorMsg: '', data: books }),
    }),
  )
  await page.route('**/getReadConfig', route =>
    route.fulfill({
      contentType: 'application/json',
      body: JSON.stringify({ isSuccess: true, errorMsg: '', data: '{}' }),
    }),
  )

  await page.goto('/')
  await expect(page.locator('.book-entry')).toHaveCount(1)

  books = [...books, makeBook('2', '第二本')]
  await page.clock.fastForward(16_000)
  await expect(page.locator('.book-entry')).toHaveCount(2)

  books = [...books, makeBook('3', '第三本')]
  await page.getByTitle('刷新书架').click()
  await expect(page.locator('.book-entry')).toHaveCount(3)
})
