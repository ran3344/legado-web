import type {
  ReadClickAction,
  ReadClickActions,
  ReadClickAreaKey,
} from '@/web'

export const readClickActionOptions: Array<{
  label: string
  value: ReadClickAction
}> = [
  { label: '无动作', value: 'none' },
  { label: '菜单', value: 'menu' },
  { label: '上一页', value: 'prevPage' },
  { label: '下一页', value: 'nextPage' },
  { label: '上一章', value: 'prevChapter' },
  { label: '下一章', value: 'nextChapter' },
]

export const readClickAreas: Array<{
  label: string
  key: ReadClickAreaKey
}> = [
  { key: 'tl', label: '左上' },
  { key: 'tc', label: '中上' },
  { key: 'tr', label: '右上' },
  { key: 'ml', label: '左中' },
  { key: 'mc', label: '正中' },
  { key: 'mr', label: '右中' },
  { key: 'bl', label: '左下' },
  { key: 'bc', label: '中下' },
  { key: 'br', label: '右下' },
]

export const readClickAreaKeys = readClickAreas.map(area => area.key)

export const defaultReadClickActions: ReadClickActions = {
  tl: 'prevPage',
  tc: 'prevPage',
  tr: 'nextPage',
  ml: 'prevPage',
  mc: 'menu',
  mr: 'nextPage',
  bl: 'prevPage',
  bc: 'nextPage',
  br: 'nextPage',
}

const readClickActionValues = readClickActionOptions.map(option => option.value)

export const isReadClickAction = (
  value: unknown,
): value is ReadClickAction =>
  typeof value === 'string' &&
  readClickActionValues.includes(value as ReadClickAction)

export const getReadClickArea = (
  x: number,
  y: number,
  width: number,
  height: number,
): ReadClickAreaKey => {
  const column = x < width / 3 ? 0 : x < (width * 2) / 3 ? 1 : 2
  const row = y < height / 3 ? 0 : y < (height * 2) / 3 ? 1 : 2
  const areas: ReadClickAreaKey[][] = [
    ['tl', 'tc', 'tr'],
    ['ml', 'mc', 'mr'],
    ['bl', 'bc', 'br'],
  ]
  return areas[row][column]
}
