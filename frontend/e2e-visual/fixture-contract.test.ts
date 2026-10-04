// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * Valide les fixtures e2e-visual contre les schémas OpenAPI commités.
 * Une fixture non conforme doit faire échouer la CI.
 *
 * Springdoc omet souvent `nullable` sur les records Java : on autorise `null`
 * en plus du type déclaré, et on assouplit le format UUID (fixtures historiques).
 */
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import Ajv2020 from 'ajv/dist/2020.js'
import addFormats from 'ajv-formats'
import { describe, expect, it } from 'vitest'
import { HOME_SEED } from './dashboard-fixtures.mjs'
import { EDIT_CUSTOM_FIELDS, EDIT_WRITING_HINTS } from './edit-fixtures.mjs'
import { COMPARE_11_12, HIST_VERSIONS_DESKTOP, HIST_VERSIONS_MOBILE } from './history-fixtures.mjs'
import {
  APPR_DETAIL_DESKTOP,
  APPR_DETAIL_MOBILE,
  APPR_ITEM_DESKTOP,
  APPR_ITEM_MOBILE,
} from './approval-fixtures.mjs'
import {
  PAGE_ATTESTATION,
  PAGE_FEEDBACK_EDITOR,
  PAGE_FEEDBACK_VIEWER,
  pageDocument,
} from './page-fixtures.mjs'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..')
const openapi = JSON.parse(readFileSync(resolve(root, 'openapi/openapi.json'), 'utf8'))

/** Autorise null sur chaque type scalaire/objet (parité Jackson nullable). */
function allowNullTypes(node: unknown): unknown {
  if (Array.isArray(node)) return node.map(allowNullTypes)
  if (!node || typeof node !== 'object') return node
  const obj = node as Record<string, unknown>
  const out: Record<string, unknown> = {}
  for (const [k, v] of Object.entries(obj)) {
    if (k === 'type' && typeof v === 'string') {
      out.type = [v, 'null']
    } else if (k === 'type' && Array.isArray(v) && !v.includes('null')) {
      out.type = [...v, 'null']
    } else {
      out[k] = allowNullTypes(v)
    }
  }
  return out
}

const ajv = new Ajv2020({
  allErrors: true,
  strict: false,
  validateFormats: true,
})
addFormats(ajv)
// Fixtures historiques utilisent parfois des UUID non RFC (préfixe s-/d-/f-).
ajv.addFormat('uuid', true)

const doc = allowNullTypes({
  ...openapi,
  $id: 'https://socle.local/openapi.json',
}) as object
ajv.addSchema(doc)

function validate(schemaName: string, data: unknown) {
  const schema = {
    $ref: `https://socle.local/openapi.json#/components/schemas/${schemaName}`,
  }
  const validateFn = ajv.compile(schema)
  const ok = validateFn(data)
  if (!ok) {
    const detail = (validateFn.errors ?? [])
      .map((e) => `${e.instancePath || '/'} ${e.message}`)
      .join('; ')
    expect.fail(`${schemaName}: ${detail}`)
  }
}

describe('e2e-visual fixtures ↔ OpenAPI', () => {
  it('HOME_SEED matches HomeResponse', () => {
    validate('HomeResponse', HOME_SEED)
  })

  it('pageDocument matches DocumentResponse', () => {
    validate('DocumentResponse', pageDocument('desktop'))
  })

  it('PAGE_ATTESTATION matches ActiveAttestation', () => {
    validate('ActiveAttestation', PAGE_ATTESTATION)
  })

  it('PAGE_FEEDBACK_* match FeedbackView', () => {
    validate('FeedbackView', PAGE_FEEDBACK_EDITOR)
    validate('FeedbackView', PAGE_FEEDBACK_VIEWER)
  })

  it('HIST_VERSIONS_* items match VersionSummary', () => {
    for (const v of HIST_VERSIONS_DESKTOP) validate('VersionSummary', v)
    for (const v of HIST_VERSIONS_MOBILE) validate('VersionSummary', v)
  })

  it('COMPARE_11_12 matches VersionCompareResponse', () => {
    validate('VersionCompareResponse', COMPARE_11_12)
  })

  it('EDIT_WRITING_HINTS matches Hints', () => {
    validate('Hints', EDIT_WRITING_HINTS)
  })

  it('aucune fixture brokenLinks ne peut contenir accessible: true', () => {
    const fixtures: unknown[] = [EDIT_WRITING_HINTS]
    for (const fixture of fixtures) {
      const links = (fixture as { brokenLinks?: Array<{ accessible?: boolean }> }).brokenLinks ?? []
      for (const link of links) {
        expect(link.accessible, 'brokenLinks.accessible must not be true').not.toBe(true)
      }
    }
  })

  it('EDIT_CUSTOM_FIELDS items match CustomFieldView', () => {
    for (const f of EDIT_CUSTOM_FIELDS) validate('CustomFieldView', f)
  })

  it('APPR_ITEM_* match ApprovalView', () => {
    validate('ApprovalView', APPR_ITEM_DESKTOP)
    validate('ApprovalView', APPR_ITEM_MOBILE)
  })

  it('APPR_DETAIL_* match ApprovalDetailView', () => {
    validate('ApprovalDetailView', APPR_DETAIL_DESKTOP)
    validate('ApprovalDetailView', APPR_DETAIL_MOBILE)
  })
})
