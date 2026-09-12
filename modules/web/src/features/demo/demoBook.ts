import type { Book, BookChapter } from '@/book'

export const demoBook: Book = {
  name: 'Foliate 阅读体验',
  author: 'Legado Web',
  bookUrl: 'demo:foliate-reading',
  tocUrl: 'demo:foliate-reading',
  origin: 'demo',
  originName: '本地演示',
  type: 0,
  group: 0,
  latestChapterTime: Date.now(),
  lastCheckTime: Date.now(),
  lastCheckCount: 0,
  durChapterIndex: 0,
  durChapterPos: 0,
  durChapterTime: Date.now(),
  canUpdate: false,
  order: 0,
  originOrder: 0,
  syncTime: Date.now(),
  durChapterTitle: '序章：把书打开',
  totalChapterNum: 14,
  coverUrl: '',
}

const firstChapters: BookChapter[] = [
  {
    url: 'demo:chapter-0',
    title: '序章：把书打开',
    isVolume: false,
    baseUrl: '',
    bookUrl: demoBook.bookUrl,
    index: 0,
    isVip: false,
    isPay: false,
  },
  {
    url: 'demo:chapter-1',
    title: '第一章：一页之内的远方',
    isVolume: false,
    baseUrl: '',
    bookUrl: demoBook.bookUrl,
    index: 1,
    isVip: false,
    isPay: false,
  },
  {
    url: 'demo:chapter-2',
    title: '第二章：继续向前翻',
    isVolume: false,
    baseUrl: '',
    bookUrl: demoBook.bookUrl,
    index: 2,
    isVip: false,
    isPay: false,
  },
]

// 额外章节用于验证连续滚动下的自动加载、上一章预取与窗口裁剪。
// 各章长度不同，左右翻页时可以观察章节边界和每章页数变化。
const extraChapters: BookChapter[] = Array.from({ length: 11 }, (_, i) => {
  const index = i + 3
  return {
    url: `demo:chapter-${index}`,
    title: `第${index}章：扩展演示 ${index}`,
    isVolume: false,
    baseUrl: '',
    bookUrl: demoBook.bookUrl,
    index,
    isVip: false,
    isPay: false,
  }
})

export const demoCatalog: BookChapter[] = [...firstChapters, ...extraChapters]

const makeLongChapter = (paragraphs: string[], target = 6000) => {
  const result: string[] = []
  let length = 0
  let index = 0
  while (length < target) {
    const paragraph = paragraphs[index % paragraphs.length]
    result.push(`<p>${paragraph}</p>`)
    length += paragraph.length
    index += 1
  }
  return result.join('')
}

const extraParagraphs = [
  '扩展演示章节的正文，用来验证连续滚动模式下的自动加载、上一章预取和窗口裁剪。滚动到章节末尾附近，阅读器会自动把下一章接到正文里。',
  '每一章的正文长度并不相同，这样在左右翻页时可以看到各章页数的差异，也能检验章节边界的翻页逻辑是否按页前进。',
  '当阅读位置接近视口底部时，阅读器会提前加载下一章；回到顶部附近时，则会补上上一章的内容，保证可以继续向上阅读。',
  '拖动底部进度条时，位置会换算成章节和章节内偏移；跳转完成后，正文应当停在目标位置，页数和进度显示也要与之一致。',
  '连续阅读较长书籍时，窗口内只会保留最近若干章，更早的章节会被裁剪，以避免文档体积和渲染开销持续增长。',
]

const extraContent = Array.from({ length: 11 }, (_, i) => {
  const index = i + 3
  const target = 4200 + ((index * 1361) % 5800)
  return makeLongChapter(extraParagraphs, target)
})

const demoContent = [
  makeLongChapter([
    '这是一个离线演示章节，用来体验新的 Foliate Web 阅读器。真正的阅读不只是把文字放到屏幕上，还要让目录、章节、进度和每一次翻页都保持清晰的节奏。',
    '你可以打开底部目录，切换连续滚动和左右翻页，调整主题、字号、行距和页面宽度。正文会在进入章节时按需加载，和真实的 Legado 章节请求走同一套阅读适配器。',
    '当阅读区域足够稳定，注意力就不会被按钮和装饰打断。顶部只保留书名和必要操作，底部负责章节导航与进度，正文把空间留给文字本身。',
    '在连续滚动模式下，章节的末尾会被识别为一个明确的边界。继续向下滚动，阅读器会进入下一章；向上回到顶部，则可以回到上一章。',
    '阅读位置会随着滚动和翻页更新。返回书架后，当前章节和进度会保留在页面会话中，连接 Legado WebService 后也会同步回真实书籍。',
  ]),
  makeLongChapter([
    '一本书不一定要从远方寄来。只要有文字，屏幕就能成为一扇小小的窗，让人在很短的时间里抵达另一个人的经历、想象和记忆。',
    'Foliate 负责把章节变成可分页的文档，Legado 负责书源、目录和正文。两者之间只隔着一个很薄的适配层，所以网络章节和本地演示章节可以使用相同的阅读体验。',
    '当你拖动进度条时，阅读器会先确定目标章节，再把章节内的位置换算成一个稳定的比例。这个比例不依赖某一台设备的屏幕高度，窗口变化后仍然可以回到相近的位置。',
    '长篇正文最容易暴露排版问题：段落间距太大，页面会显得松散；正文宽度太窄，眼睛会不断换行。合适的边距、字号和行距，应该让每一行都保持自然的呼吸。',
    '慢一点读，让每一页都有自己的呼吸。阅读器的工作不是替你决定速度，而是把干扰收起来，让你可以在需要的时候快速跳转，也可以在喜欢的段落停留。',
  ]),
  makeLongChapter([
    '现在试着打开底部的目录，换到下一章，再回到这一章。目录是长书的地图，它不应该遮住正文，也不应该占用顶部最重要的位置。',
    '你也可以打开右上角的设置，换一个适合自己的阅读主题。浅色主题适合白天，深色主题适合夜晚，字号和行距则应该根据距离和光线随时调整。',
    '移动端上，底部按钮会避开系统手势区域，进度条不会贴着小白条。屏幕变窄时，正文仍然保持完整的阅读宽度，目录和设置面板会自动适应可用空间。',
    '当真正连接 Legado WebService 后，书架里的每一本书都会进入同一个阅读页面。书源搜索、章节更新、图片代理和阅读进度仍然由 Legado 提供，Foliate 专注于把正文呈现得更稳定。',
    '演示到这里还没有结束。请继续向下滚动，观察章节边界和进度变化；再切换到左右翻页，看看同一批文字如何在另一种节奏中展开。',
  ]),
  ...extraContent,
]

export const getDemoContent = async (chapter: BookChapter) =>
  demoContent[chapter.index] || ''
