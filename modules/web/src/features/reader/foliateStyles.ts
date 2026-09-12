import type { webReadConfig } from '@/web'

const fonts = [
  'Microsoft YaHei, PingFangSC-Regular, HelveticaNeue-Light, sans-serif',
  'PingFangSC-Regular, -apple-system, Simsun, sans-serif',
  'Kaiti, KaiTi, serif',
]

const backgrounds = [
  '#f8f3e8',
  '#f3eacc',
  '#e8f1e8',
  '#e5f0f4',
  '#f5e5e5',
  '#eeeeee',
  '#17191c',
]
const foregrounds = [
  '#29261f',
  '#2e2a20',
  '#253026',
  '#253238',
  '#36272b',
  '#242424',
  '#d8d9dc',
]

export const getReaderColors = (theme: number) => ({
  background: backgrounds[theme] || backgrounds[0],
  foreground: foregrounds[theme] || foregrounds[0],
})

export const getFoliateStyles = (config: webReadConfig) => {
  const { background, foreground } = getReaderColors(config.theme)
  const fontFamily =
    config.font === -1 && config.customFontName
      ? `'${config.customFontName}', sans-serif`
      : fonts[config.font] || fonts[0]
  return `
    html { background: ${background}; color: ${foreground}; }
    body { box-sizing: border-box; margin: 0; padding: 0 10px; background: ${background}; color: ${foreground}; font-family: ${fontFamily}; font-size: ${config.fontSize}px; line-height: ${1 + config.spacing.line}; letter-spacing: ${config.spacing.letter}em; text-rendering: optimizeLegibility; -webkit-font-smoothing: antialiased; }
     article { margin: 0; padding: .5rem 0 0; }
     article[data-legado-chapter] + article[data-legado-chapter] {
       break-before: page;
       page-break-before: always;
       -webkit-column-break-before: always !important;
     }
    h1 { margin: 0 0 1.5rem; font-size: 1.45em; line-height: 1.45; font-weight: 500; overflow-wrap: anywhere; line-break: strict; break-inside: avoid; }
    p, li, blockquote, dd { margin: ${config.spacing.paragraph}em 0; line-height: ${1 + config.spacing.line}; text-align: justify; text-justify: inter-ideograph; line-break: strict; word-break: normal; overflow-wrap: break-word; hyphens: none; break-inside: auto !important; page-break-inside: auto !important; -webkit-column-break-inside: auto !important; widows: 1; orphans: 1; }
    p { text-indent: 2em; }
    li, blockquote, dd { text-indent: 0; }
    :focus { outline: none !important; }
    img, svg, video { display: block; max-width: 100%; height: auto; object-fit: contain; break-inside: avoid; }
    a { color: ${config.theme === 6 ? '#8db9ff' : '#1769aa'}; }
    pre { white-space: pre-wrap; }
  `
}

export const getReaderFlow = (mode: webReadConfig['readMode']) =>
  mode === 'scroll' ? 'scrolled' : 'paginated'
