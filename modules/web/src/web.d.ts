export type webReadConfig = {
  theme: number
  font: number
  fontSize: number
  readWidth: number
  readMode: ReadMode
  reSegment: boolean
  customFontName: string
  clickActions: ReadClickActions
  spacing: {
    paragraph: number
    line: number
    letter: number
  }
}

export type ReadMode = 'scroll' | 'horizontal' | 'click'

export type ReadClickAction =
  | 'none'
  | 'menu'
  | 'prevPage'
  | 'nextPage'
  | 'prevChapter'
  | 'nextChapter'

export type ReadClickAreaKey =
  | 'tl'
  | 'tc'
  | 'tr'
  | 'ml'
  | 'mc'
  | 'mr'
  | 'bl'
  | 'bc'
  | 'br'

export type ReadClickActions = Record<ReadClickAreaKey, ReadClickAction>
