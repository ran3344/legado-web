var e=e=>e?e.replace(/[\t\n\f\r ]+/g,` `).replace(/^[\t\n\f\r ]+/,``).replace(/[\t\n\f\r ]+$/,``):``,t=t=>e(t?.textContent),n={XLINK:`http://www.w3.org/1999/xlink`,EPUB:`http://www.idpf.org/2007/ops`},r={XML:`application/xml`,XHTML:`application/xhtml+xml`},i={strong:[`strong`,`self`],emphasis:[`em`,`self`],style:[`span`,`self`],a:`anchor`,strikethrough:[`s`,`self`],sub:[`sub`,`self`],sup:[`sup`,`self`],code:[`code`,`self`],image:`image`},a={tr:[`tr`,{th:[`th`,i,[`colspan`,`rowspan`,`align`,`valign`]],td:[`td`,i,[`colspan`,`rowspan`,`align`,`valign`]]},[`align`]]},o={epigraph:[`blockquote`],subtitle:[`h2`,i],"text-author":[`p`,i],date:[`p`,i],stanza:`stanza`},s={title:[`header`,{p:[`h1`,i],"empty-line":[`br`]}],epigraph:[`blockquote`,`self`],image:`image`,annotation:[`aside`],section:[`section`,`self`],p:[`p`,i],poem:[`blockquote`,o],subtitle:[`h2`,i],cite:[`blockquote`,`self`],"empty-line":[`br`],table:[`table`,a],"text-author":[`p`,i]};o.epigraph.push(s);var c={image:`image`,title:[`section`,{p:[`h1`,i],"empty-line":[`br`]}],epigraph:[`section`,s],section:[`section`,s]},l=class{constructor(e){this.fb2=e,this.doc=document.implementation.createDocument(n.XHTML,`html`),this.bins=new Map(Array.from(this.fb2.getElementsByTagName(`binary`),e=>[e.id,e]))}getImageSrc(e){let t=e.getAttributeNS(n.XLINK,`href`);if(!t)return`data:,`;let[,r]=t.split(`#`);if(!r)return t;let i=this.bins.get(r);return i?`data:${i.getAttribute(`content-type`)};base64,${i.textContent}`:t}image(e){let t=this.doc.createElement(`img`);return t.alt=e.getAttribute(`alt`),t.title=e.getAttribute(`title`),t.setAttribute(`src`,this.getImageSrc(e)),t}anchor(e){let t=this.convert(e,{a:[`a`,i]});return t.setAttribute(`href`,e.getAttributeNS(n.XLINK,`href`)),e.getAttribute(`type`)===`note`&&t.setAttributeNS(n.EPUB,`epub:type`,`noteref`),t}stanza(e){let t=this.convert(e,{stanza:[`p`,{title:[`header`,{p:[`strong`,i],"empty-line":[`br`]}],subtitle:[`p`,i]}]});for(let n of e.children)n.nodeName===`v`&&(t.append(this.doc.createTextNode(n.textContent)),t.append(this.doc.createElement(`br`)));return t}convert(e,t){if(e.nodeType===3)return this.doc.createTextNode(e.textContent);if(e.nodeType===4)return this.doc.createCDATASection(e.textContent);if(e.nodeType===8)return this.doc.createComment(e.textContent);let n=t?.[e.nodeName];if(!n)return null;if(typeof n==`string`)return this[n](e);let[r,i,a]=n,o=this.doc.createElement(r);if(e.id&&(o.id=e.id),o.classList.add(e.nodeName),Array.isArray(a))for(let t of a){let n=e.getAttribute(t);n&&o.setAttribute(t,n)}let s=i===`self`?t:i,c=e.firstChild;for(;c;){let e=this.convert(c,s);e&&o.append(e),c=c.nextSibling}return o}},u=async e=>{let t=await e.arrayBuffer(),n=new TextDecoder(`utf-8`).decode(t),i=new DOMParser,a=i.parseFromString(n,r.XML),o=a.xmlEncoding||n.match(/^<\?xml\s+version\s*=\s*["']1.\d+"\s+encoding\s*=\s*["']([A-Za-z0-9._-]*)["']/)?.[1];if(o&&o.toLowerCase()!==`utf-8`){let e=new TextDecoder(o).decode(t);return i.parseFromString(e,r.XML)}return a},d=URL.createObjectURL(new Blob([`
@namespace epub "http://www.idpf.org/2007/ops";
body > img, section > img {
    display: block;
    margin: auto;
}
.title h1 {
    text-align: center;
}
body > section > .title, body.notesBodyType > .title {
    margin: 3em 0;
}
body.notesBodyType > section .title h1 {
    text-align: start;
}
body.notesBodyType > section .title {
    margin: 1em 0;
}
p {
    text-indent: 1em;
    margin: 0;
}
:not(p) + p, p:first-child {
    text-indent: 0;
}
.poem p {
    text-indent: 0;
    margin: 1em 0;
}
.text-author, .date {
    text-align: end;
}
.text-author:before {
    content: "—";
}
table {
    border-collapse: collapse;
}
td, th {
    padding: .25em;
}
a[epub|type~="noteref"] {
    font-size: .75em;
    vertical-align: super;
}
body:not(.notesBodyType) > .title, body:not(.notesBodyType) > .epigraph {
    margin: 3em 0;
}
`],{type:`text/css`})),f=e=>`<?xml version="1.0" encoding="utf-8"?>
<html xmlns="http://www.w3.org/1999/xhtml">
    <head><link href="${d}" rel="stylesheet" type="text/css"/></head>
    <body>${e}</body>
</html>`,p=`data-foliate-id`,m=async n=>{let i={},a=await u(n),o=new l(a),d=e=>a.querySelector(e),m=e=>[...a.querySelectorAll(e)],h=e=>{let n=t(e.querySelector(`nickname`));if(n)return n;let r=t(e.querySelector(`first-name`)),i=t(e.querySelector(`middle-name`)),a=t(e.querySelector(`last-name`));return{name:[r,i,a].filter(e=>e).join(` `),sortAs:a?[a,[r,i].filter(e=>e).join(` `)].join(`, `):null}},g=e=>e?.getAttribute(`value`)??t(e),_=d(`title-info annotation`);if(i.metadata={title:t(d(`title-info book-title`)),identifier:t(d(`document-info id`)),language:t(d(`title-info lang`)),author:m(`title-info author`).map(h),translator:m(`title-info translator`).map(h),contributor:m(`document-info author`).map(h).concat(m(`document-info program-used`).map(t)).map(e=>Object.assign(typeof e==`string`?{name:e}:e,{role:`bkp`})),publisher:t(d(`publish-info publisher`)),published:g(d(`title-info date`)),modified:g(d(`document-info date`)),description:_?o.convert(_,{annotation:[`div`,s]}).innerHTML:null,subject:m(`title-info genre`).map(t)},d(`coverpage image`)){let e=o.getImageSrc(d(`coverpage image`));i.getCover=()=>fetch(e).then(e=>e.blob())}else i.getCover=()=>null;let v=Array.from(a.querySelectorAll(`body`),e=>{let t=o.convert(e,{body:[`body`,c]});return[Array.from(t.children,e=>({el:e,ids:[e,...e.querySelectorAll(`[id]`)].map(e=>e.id)})),t]}),y=[],b=v[0][0].map(({el:e,ids:n})=>({ids:n,titles:Array.from(e.querySelectorAll(`:scope > section > .title`),(e,n)=>(e.setAttribute(p,n),{title:t(e),index:n})),el:e})).concat(v.slice(1).map(([e,t])=>{let n=e.map(e=>e.ids).flat();return t.classList.add(`notesBodyType`),{ids:n,el:t,linear:`no`}})).map(({ids:t,titles:n,el:i,linear:a})=>{let o=f(i.outerHTML),s=new Blob([o],{type:r.XHTML}),c=URL.createObjectURL(s);return y.push(c),{ids:t,title:e(i.querySelector(`.title, .subtitle, p`)?.textContent??(i.classList.contains(`title`)?i.textContent:``)),titles:n,load:()=>c,createDocument:()=>new DOMParser().parseFromString(o,r.XHTML),size:s.size-Array.from(i.querySelectorAll(`[src]`),e=>e.getAttribute(`src`)?.length??0).reduce((e,t)=>e+t,0),linear:a}}),x=new Map;return i.sections=b.map((e,t)=>{let{ids:n,load:r,createDocument:i,size:a,linear:o}=e;for(let e of n)e&&x.set(e,t);return{id:t,load:r,createDocument:i,size:a,linear:o}}),i.toc=b.map(({title:e,titles:t},n)=>{let r=n.toString();return{label:e,href:r,subitems:t?.length?t.map(({title:e,index:t})=>({label:e,href:`${r}#${t}`})):null}}).filter(e=>e),i.resolveHref=e=>{let[t,n]=e.split(`#`);return t?{index:Number(t),anchor:e=>e.querySelector(`[${p}="${n}"]`)}:{index:x.get(n),anchor:e=>e.getElementById(n)}},i.splitTOCHref=e=>e?.split(`#`)?.map(e=>Number(e))??[],i.getTOCFragment=(e,t)=>e.querySelector(`[${p}="${t}"]`),i.isExternal=e=>/^\w+:/i.test(e),i.destroy=()=>{for(let e of y)URL.revokeObjectURL(e)},i};export{m as makeFB2};