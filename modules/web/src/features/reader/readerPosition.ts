import type { BookChapter } from '@/book'

export type ReaderPosition = {
  chapterIndex: number
  chapterOffset: number
}

export type ChapterLengths = ReadonlyArray<number | undefined>

const clamp = (value: number, min = 0, max = 1) =>
  Math.max(min, Math.min(max, Number.isFinite(value) ? value : min))

export const chapterWeight = (chapter?: BookChapter) => {
  const start = chapter?.start
  const end = chapter?.end
  const hasRange =
    typeof start === 'number' &&
    typeof end === 'number' &&
    Number.isFinite(start) &&
    Number.isFinite(end) &&
    end > start
  return Math.max(
    hasRange ? end - start : 1,
    1,
  )
}

const getWeight = (
  catalog: BookChapter[],
  index: number,
  lengths?: ChapterLengths,
) => {
  const actualLength = lengths?.[index]
  return typeof actualLength === 'number' && Number.isFinite(actualLength)
    ? Math.max(Math.round(actualLength), 1)
    : chapterWeight(catalog[index])
}

export const getReaderPosition = (
  catalog: BookChapter[],
  chapterIndex: number,
  chapterOffset: number,
): ReaderPosition => {
  const maxIndex = Math.max(catalog.length - 1, 0)
  const index = Math.max(0, Math.min(maxIndex, Math.round(chapterIndex)))
  const offset = Math.max(0, Math.round(chapterOffset))
  return {
    chapterIndex: index,
    chapterOffset: offset,
  }
}

export const getChapterFraction = (
  catalog: BookChapter[],
  position: ReaderPosition,
  lengths?: ChapterLengths,
) => {
  const weight = getWeight(catalog, position.chapterIndex, lengths)
  return weight > 0 ? clamp(position.chapterOffset / weight) : 0
}

export const getBookFraction = (
  catalog: BookChapter[],
  position: ReaderPosition,
  lengths?: ChapterLengths,
) => {
  const total = catalog.reduce(
    (sum, _chapter, index) => sum + getWeight(catalog, index, lengths),
    0,
  )
  const before = catalog
    .slice(0, position.chapterIndex)
    .reduce(
      (sum, _chapter, index) => sum + getWeight(catalog, index, lengths),
      0,
    )
  return total > 0
    ? clamp((before + position.chapterOffset) / total)
    : 0
}

export const getReaderPositionFromBookFraction = (
  catalog: BookChapter[],
  bookFraction: number,
  lengths?: ChapterLengths,
): ReaderPosition => {
  const target = clamp(bookFraction)
  if (!catalog.length) return getReaderPosition([], 0, 0)
  const total = catalog.reduce(
    (sum, _chapter, index) => sum + getWeight(catalog, index, lengths),
    0,
  )
  let cursor = target * total
  for (let index = 0; index < catalog.length; index += 1) {
    const weight = getWeight(catalog, index, lengths)
    if (cursor <= weight || index === catalog.length - 1)
      return getReaderPosition(catalog, index, cursor)
    cursor -= weight
  }
  return getReaderPosition(
    catalog,
    catalog.length - 1,
    getWeight(catalog, catalog.length - 1, lengths),
  )
}

export const getChapterProgressPosition = (
  catalog: BookChapter[],
  index: number,
  fraction: number,
  lengths?: ChapterLengths,
) => {
  const chapterIndex = Math.max(0, Math.min(catalog.length - 1, index))
  return Math.round(getWeight(catalog, chapterIndex, lengths) * clamp(fraction))
}
