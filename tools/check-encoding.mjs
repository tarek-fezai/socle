#!/usr/bin/env node
// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
// Échoue si un fichier texte suivi par git contient une séquence typique de double encodage
// (UTF-8 relu en Windows-1252 ou Latin-1 puis réécrit en UTF-8).
// Usage : node tools/check-encoding.mjs (depuis n'importe quel dossier du dépôt).
import { execFileSync } from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'

const EXTENSIONS = ['ts', 'tsx', 'mjs', 'js', 'md', 'java', 'yml', 'html', 'css', 'sql']
// Ã + octet de continuation ; Â + « » ou espace insécable ; a circonflexe + euro (Windows-1252) ou + contrôle C1 (Latin-1).
const MOJIBAKE = /\u00C3[\u0080-\u00BF]|\u00C2[\u00AB\u00BB\u00A0]|\u00E2\u20AC|\u00E2[\u0080-\u009F]/g
const show = (s) => [...s].map((c) => (c.charCodeAt(0) > 0x7e ? `U+${c.charCodeAt(0).toString(16).toUpperCase().padStart(4, '0')}` : c)).join(' ')

const root = execFileSync('git', ['rev-parse', '--show-toplevel'], { encoding: 'utf8' }).trim()
const files = execFileSync('git', ['ls-files', '-z', '--', ...EXTENSIONS.map((e) => `*.${e}`)], {
  cwd: root,
  encoding: 'utf8',
  maxBuffer: 64 * 1024 * 1024,
})
  .split('\0')
  .filter(Boolean)

const byFile = new Map()
for (const file of files) {
  const abs = path.join(root, file)
  if (!fs.existsSync(abs)) continue
  fs.readFileSync(abs, 'utf8')
    .split('\n')
    .forEach((line, i) => {
      const found = line.match(MOJIBAKE)
      if (!found) return
      byFile.set(file, [...(byFile.get(file) ?? []), { line: i + 1, found: [...new Set(found)].map(show).join(', ') }])
    })
}

if (byFile.size) {
  let total = 0
  for (const [file, hits] of byFile) {
    total += hits.length
    console.error(`${file} : ${hits.length} ligne(s)`)
    for (const h of hits.slice(0, 5)) console.error(`  l.${h.line} : ${h.found}`)
  }
  console.error(`\nDouble encodage détecté dans ${byFile.size} fichier(s) (${total} lignes).`)
  process.exit(1)
}
console.log(`Encodage OK : ${files.length} fichiers texte suivis vérifiés.`)
