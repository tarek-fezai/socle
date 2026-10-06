#!/usr/bin/env node
// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
/**
 * Signe un fichier de licence Socle (Ed25519, hors ligne).
 *
 * Usage :
 *   node sign.mjs --key /chemin/secret/private.pem --in licence.json [--out licence.signed.json]
 *
 * `--key` : chemin local (hors dépôt) vers une clé privée Ed25519 au format PEM PKCS#8,
 * produit typiquement par :
 *   openssl genpkey -algorithm ed25519 -out private.pem
 *
 * Ne jamais committer cette clé. Aucun appel réseau. Aucune lecture depuis le dépôt.
 */
import { createPrivateKey, sign } from 'node:crypto'
import { readFileSync, writeFileSync } from 'node:fs'
import { resolve } from 'node:path'

function usage() {
  console.error(`Usage: node sign.mjs --key <private.pem> --in <licence.json> [--out <out.json>]`)
  console.error(`  --key  chemin absolu ou relatif vers une clé PEM PKCS#8 Ed25519 (hors dépôt)`)
  process.exit(2)
}

function parseArgs(argv) {
  const out = { key: null, inn: null, out: null }
  for (let i = 2; i < argv.length; i++) {
    if (argv[i] === '--key') out.key = argv[++i]
    else if (argv[i] === '--in') out.inn = argv[++i]
    else if (argv[i] === '--out') out.out = argv[++i]
    else usage()
  }
  if (!out.key || !out.inn) usage()
  return out
}

/** Payload canonique (clés triées, sans signature). */
export function canonicalPayload(obj) {
  const ordered = {
    edition: obj.edition,
    expiresAt: obj.expiresAt,
    issuedAt: obj.issuedAt,
    licenseId: obj.licenseId,
    licensee: obj.licensee,
    maxUsers: obj.maxUsers,
  }
  for (const [k, v] of Object.entries(ordered)) {
    if (v === undefined || v === null || v === '') {
      throw new Error(`champ requis manquant : ${k}`)
    }
  }
  if (!Number.isInteger(ordered.maxUsers) || ordered.maxUsers < 1) {
    throw new Error('maxUsers doit être un entier ≥ 1')
  }
  return Buffer.from(JSON.stringify(ordered), 'utf8')
}

/**
 * Charge une clé privée depuis un fichier local uniquement.
 * Accepte PEM PKCS#8 (`-----BEGIN PRIVATE KEY-----`, openssl genpkey -algorithm ed25519).
 */
function loadPrivateKeyFromFile(keyPath) {
  const abs = resolve(keyPath)
  const pem = readFileSync(abs, 'utf8')
  if (!pem.includes('BEGIN PRIVATE KEY') && !pem.includes('BEGIN ED25519 PRIVATE KEY')) {
    throw new Error(
      `Le fichier ${abs} doit être une clé PEM PKCS#8 Ed25519 ` +
        `(openssl genpkey -algorithm ed25519 -out private.pem)`,
    )
  }
  return createPrivateKey(pem)
}

const args = parseArgs(process.argv)
const raw = JSON.parse(readFileSync(args.inn, 'utf8'))
const payload = canonicalPayload(raw)
const key = loadPrivateKeyFromFile(args.key)
const signature = sign(null, payload, key).toString('base64')
const signed = {
  licenseId: raw.licenseId,
  licensee: raw.licensee,
  edition: raw.edition,
  issuedAt: raw.issuedAt,
  expiresAt: raw.expiresAt,
  maxUsers: raw.maxUsers,
  signature,
}
const outPath = args.out || args.inn.replace(/\.json$/i, '') + '.signed.json'
writeFileSync(outPath, JSON.stringify(signed, null, 2) + '\n', 'utf8')
console.log(`Licence signée → ${outPath}`)
