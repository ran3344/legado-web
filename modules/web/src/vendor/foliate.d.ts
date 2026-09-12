declare module '@/vendor/foliate/view.js' {
  export class View extends HTMLElement {}
}

declare module '@/vendor/foliate/overlayer.js' {
  export class Overlayer {
    static highlight: (
      rects: DOMRect[],
      options?: { color?: string },
    ) => SVGElement
  }
}
