// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
/**
 * Serves design mockups with Google Fonts imports rewritten to local OFL fonts.
 */
import http from 'node:http'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const root = path.resolve(__dirname, '../..')
const mockupDir = path.join(root, 'systeme-documentation-direction-ui')
const fontsDir = path.join(root, 'frontend/public/fonts')
const PORT = 4174

const LOCAL_FONT_LINK = `<link rel="stylesheet" href="/fonts/fonts.css" />`

/**
 * Main.dc.html uses a few design-canvas template bindings for its collapsible sidebar.
 * Resolve them to the expanded state so the maquette renders like the design preview.
 */
function resolveTemplates(html) {
  return html
    .replace(/<sc-if value="\{\{collapsed\}\}"[^>]*>[\s\S]*?<\/sc-if>/g, '')
    .replace(/\{\{sidebarWidth\}\}/g, '268')
    .replace(/\{\{sidebarPad\}\}/g, '28px 22px')
    .replace(/\{\{logoJustify\}\}/g, 'space-between')
}

/**
 * Maquettes dont le rendu dépend des classes définies dans leur <helmet><style> (lignes de diff,
 * blocs, numéros de ligne…). Les autres maquettes n'utilisent que du style inline : leurs règles
 * de <helmet> (survols) restent écartées pour ne pas déplacer les références visuelles existantes.
 */
const KEEP_HELMET_STYLES = new Set([
  'Diff.dc.html',
  'DiffApproval.dc.html',
  // .nav-item.on { background; font-weight } — sinon l'état actif n'existe pas (inline color seule)
  'TagsAdmin.dc.html',
  'CustomFields.dc.html',
  'Retention.dc.html',
  'Branding.dc.html',
])

function helmetStyles(rawHtml) {
  const blocks = [...rawHtml.matchAll(/<helmet>([\s\S]*?)<\/helmet>/g)].flatMap((m) =>
    [...m[1].matchAll(/<style>([\s\S]*?)<\/style>/g)].map((s) => s[1]),
  )
  const css = blocks.join('\n').replace(/@import url\([^)]*\);?/g, '')
  return css ? `<style>${css}</style>` : ''
}

function rewriteHtml(rawHtml, name = '') {
  const html = resolveTemplates(rawHtml)
  const kept = KEEP_HELMET_STYLES.has(name) ? helmetStyles(rawHtml) : ''
  return html
    .replace(/@import url\('https:\/\/fonts\.googleapis\.com[^']*'\);?/g, '')
    .replace(/<script src="\.\/support\.js"><\/script>/, '')
    .replace(/<\/?x-dc>/g, '')
    .replace(/<helmet>[\s\S]*?<\/helmet>/g, '')
    .replace(/<script type="text\/x-dc"[\s\S]*?<\/script>/g, '')
    .replace(
      /<\/head>/,
      `${LOCAL_FONT_LINK}
<style>
  html, body { margin: 0; padding: 0; overflow: hidden; background: #FFFFFF;
    font-family: 'IBM Plex Sans', sans-serif; color: #0E0E10; }
  .serif { font-family: 'Instrument Serif', serif; }
  a { color: inherit; text-decoration: none; }
  .sso:hover { background: #2C27C7; }
</style>
${kept}
</head>`,
    )
}

const mime = {
  '.html': 'text/html; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.woff2': 'font/woff2',
  '.js': 'text/javascript; charset=utf-8',
  '.txt': 'text/plain; charset=utf-8',
}

const server = http.createServer((req, res) => {
  const url = new URL(req.url || '/', `http://127.0.0.1:${PORT}`)
  let filePath
  if (url.pathname.startsWith('/fonts/')) {
    filePath = path.join(fontsDir, url.pathname.slice('/fonts/'.length))
  } else {
    const name = url.pathname === '/' ? 'Login.dc.html' : path.basename(url.pathname)
    filePath = path.join(mockupDir, name)
  }

  if (!filePath.startsWith(mockupDir) && !filePath.startsWith(fontsDir)) {
    res.writeHead(403)
    res.end('forbidden')
    return
  }

  fs.readFile(filePath, (err, data) => {
    if (err) {
      res.writeHead(404)
      res.end('not found')
      return
    }
    const ext = path.extname(filePath)
    let body = data
    if (ext === '.html') {
      body = Buffer.from(rewriteHtml(data.toString('utf8'), path.basename(filePath)), 'utf8')
    }
    res.writeHead(200, { 'Content-Type': mime[ext] || 'application/octet-stream' })
    res.end(body)
  })
})

server.listen(PORT, '127.0.0.1', () => {
  console.log(`mockups on http://127.0.0.1:${PORT}`)
})
