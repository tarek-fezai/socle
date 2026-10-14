import { execFileSync } from 'node:child_process'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')

type ComposeRow = { Name?: string; Project?: string }

function rowsFrom(raw: string): ComposeRow[] {
  const trimmed = raw.trim()
  if (!trimmed) return []
  if (trimmed.startsWith('[')) {
    const parsed: unknown = JSON.parse(trimmed)
    return Array.isArray(parsed) ? (parsed as ComposeRow[]) : []
  }
  if (trimmed.startsWith('{')) {
    return [JSON.parse(trimmed) as ComposeRow]
  }
  return trimmed
    .split('\n')
    .map((line) => line.trim())
    .filter((line) => line.startsWith('{'))
    .map((line) => JSON.parse(line) as ComposeRow)
}

export default function globalSetup(): void {
  const composeFile = path.join(repoRoot, 'deploy/compose/docker-compose.yml')
  const overlay = path.join(repoRoot, 'e2e/docker-compose.e2e.yml')
  const envFile = path.join(repoRoot, 'deploy/compose/.env.ci')
  const args = [
    'compose',
    '-p',
    'socle-e2e',
    '-f',
    composeFile,
    '-f',
    overlay,
    '--env-file',
    envFile,
    '--profile',
    'demo-idp',
    'ps',
    '--format',
    'json',
  ]
  let raw: string
  try {
    raw = execFileSync('docker', args, {
      cwd: repoRoot,
      encoding: 'utf8',
      env: { ...process.env, COMPOSE_PROJECT_NAME: 'socle-e2e' },
    })
  } catch (err) {
    const message = err instanceof Error ? err.message : String(err)
    throw new Error(`globalSetup: docker compose -p socle-e2e ps a échoué. ${message}`)
  }

  const rows = rowsFrom(raw)
  if (rows.length === 0) {
    throw new Error('globalSetup: docker compose -p socle-e2e ps ne montre aucune pile socle-e2e.')
  }
  const names: string[] = []
  for (const row of rows) {
    const name = row.Name ?? ''
    const project = row.Project ?? ''
    const targeted =
      project === 'socle-e2e' || (project === '' && name.startsWith('socle-e2e-'))
    const production = project === 'socle-production' || name.startsWith('socle-production-')
    if (production || !targeted) {
      throw new Error(
        `globalSetup: la pile ciblée n'est pas socle-e2e (${project || name || 'inconnue'}).`,
      )
    }
    names.push(name)
  }
  console.log(`globalSetup: docker compose -p socle-e2e ps — projet socle-e2e (${names.join(', ')})`)
}
