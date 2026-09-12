const sentenceEnd = /[。！？!?~]/
const sentenceEndWithQuote = /[。！？!?~]["”」』）)]?$/
const quoteChars = new Set(['"', '“', '”'])

const normalizeLine = (line: string) =>
  line.replace(/[\t\u3000]+/g, ' ').replace(/[ ]{2,}/g, ' ').trim()

const splitDialogueMarkers = (text: string) =>
  text
    .replace(/&quot;/gi, '“')
    .replace(/[:：]['"‘”“]+/g, '：“')
    .replace(/["”“]+\s*["”“][\s"”“]*/g, '”\n“')
    .replace(/["”“]+([。！？!?~])["”“]+/g, '”$1\n“')
    .replace(/["”“]+([。！？!?~])([^"”“])/g, '”$1\n$2')
    .replace(/([问说喊唱叫骂道着答])[.。]/g, '$1。\n')

const mergeBrokenLines = (content: string) => {
  const lines = content
    .replace(/\r\n?/g, '\n')
    .split(/\n\s*/)
    .map(normalizeLine)
    .filter(Boolean)
  const paragraphs: string[] = []
  let current = ''
  for (const line of lines) {
    if (!current) {
      current = line
      continue
    }
    if (sentenceEndWithQuote.test(current)) {
      paragraphs.push(current)
      current = line
    } else {
      current += line
    }
  }
  if (current) paragraphs.push(current)
  return paragraphs
}

const splitLongParagraph = (paragraph: string) => {
  if (paragraph.length < 260) return [paragraph]
  const breaks: number[] = []
  let quoteDepth = 0
  let sentences = 0
  for (let index = 0; index < paragraph.length; index += 1) {
    const char = paragraph[index]
    if (quoteChars.has(char)) {
      if (char === '“' || char === '"') quoteDepth += 1
      else if (quoteDepth > 0) quoteDepth -= 1
    }
    if (sentenceEnd.test(char)) {
      sentences += 1
      const next = paragraph[index + 1] || ''
      const closesQuote = quoteChars.has(next) || '」』）)'.includes(next)
      if (quoteDepth === 0 && (closesQuote || !next || !sentenceEnd.test(next))) {
        if (sentences % 3 === 0) breaks.push(index + 1)
      }
    }
  }
  if (!breaks.length) return [paragraph]
  const result: string[] = []
  let start = 0
  for (const end of breaks) {
    const part = paragraph.slice(start, end).trim()
    if (part) result.push(part)
    start = end
  }
  const tail = paragraph.slice(start).trim()
  if (tail) result.push(tail)
  return result.length > 1 ? result : [paragraph]
}

/** Port of Legado's ContentHelp.reSegment for deterministic Web output. */
export const reSegmentText = (content: string, chapterName = '') => {
  const normalized = splitDialogueMarkers(content)
  const paragraphs = mergeBrokenLines(normalized)
  const withoutDuplicateTitle = chapterName.trim()
    ? paragraphs.filter((paragraph, index) =>
        !(index === 0 &&
          normalizeLine(paragraph) === normalizeLine(chapterName)),
      )
    : paragraphs
  return withoutDuplicateTitle.flatMap(splitLongParagraph).join('\n')
}
