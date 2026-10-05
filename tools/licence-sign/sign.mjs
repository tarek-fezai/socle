#!/usr/bin/env node
// SPDX-License-Identifier: LicenseRef-Socle-Proprietary
/**
 * Signe un fichier de licence Socle (Ed25519, hors ligne).
 *
 * Usage :
 *   node sign.mjs --key /chemin/prive.ed25519.b64 --in licence.json [--out licence.signed.json]
 *
 * Le fichier --key contient la graine privée Ed25519 (32 octets) en Base64.
 * Ne jamais committer cette clé. Aucun appel réseau.
 */
import { createPrivateKey, sign } from 'node:crypto'
import { readFileSync, writeFileSync } from 'node:fs'

function usage() {
  console.error(`Usage: node sign.mjs --key <private.b64> --in <licence.json> [--out <out.json>]`)
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

function privateKeyFromSeedB64(b64) {
  const seed = Buffer.from(String(b64).trim(), 'base64')
  if (seed.length !== 32) {
    throw new Error(`clé privée : 32 octets attendus, obtenu ${seed.length}`)
  }
  // PKCS8 Ed25519 encapsulating the 32-byte seed
  const pkcs8 = Buffer.concat([
    Buffer.from('302e020100300506032b657004220420', 'hex'),
    seed,
  ])
  return createPrivateKey({ key: pkcs8, format: 'der', type: 'pkcs8' })
}

const args = parseArgs(process.argv)
const seedB64 = readFileSync(args.key, 'utf8')
const raw = JSON.parse(readFileSync(args.inn, 'utf8'))
const payload = canonicalPayload(raw)
const key = privateKeyFromSeedB64(seedB64)
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
