import { execSync } from 'node:child_process'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const here = path.dirname(fileURLToPath(import.meta.url))
const repoRoot = path.resolve(here, '../..')
const script = path.join(repoRoot, 'e2e/scripts/compose-e2e.sh')
const PROJECT = 'socle-e2e'

function assertE2eProject(): void {
  if (process.env.COMPOSE_PROJECT_NAME === 'socle-production') {
    throw new Error(
      "Refus : COMPOSE_PROJECT_NAME=socle-production. Ne jamais lancer l'e2e sur une instance d'exploitation.",
    )
  }
}

function composeCmd(args: string): string {
  return `bash "${script}" ${args}`
}

export function composeExec(args: string, envFile = '.env.ci'): string {
  assertE2eProject()
  if (envFile.includes('.env.production')) {
    throw new Error("Refus : .env.production. Ne jamais lancer l'e2e sur une instance d'exploitation.")
  }
  if (envFile !== '.env.ci') {
    throw new Error('e2e: seul --env-file .env.ci est autorisé.')
  }
  return execSync(composeCmd(args), {
    cwd: repoRoot,
    encoding: 'utf8',
    stdio: ['ignore', 'pipe', 'pipe'],
    shell: '/bin/bash',
  })
}

function postgresUser(): string {
  return process.env.POSTGRES_USER ?? 'socle'
}

/** Postgres + git volume backup for restore smoke (stack should be running). */
export function backupSocleCoreAndGit(backupDir: string): void {
  assertE2eProject()
  const pgUser = postgresUser()
  execSync(`mkdir -p "${backupDir}"`, { shell: '/bin/bash' })
  execSync(
    `${composeCmd(`exec -T postgres pg_dump -U ${pgUser} -Fc socle_core`)} > "${backupDir}/socle_core.dump"`,
    { cwd: repoRoot, shell: '/bin/bash' },
  )
  execSync(
    `docker run --rm -v ${PROJECT}_git-content:/data -v "${backupDir}:/backup" alpine tar czf /backup/git-content.tgz -C /data .`,
    { encoding: 'utf8', shell: '/bin/bash' },
  )
}

export function restoreSocleCoreAndGit(backupDir: string): void {
  assertE2eProject()
  const pgUser = postgresUser()
  composeExec('stop backend webhook-worker frontend caddy')
  execSync(
    `${composeCmd(`exec -T postgres pg_restore -U ${pgUser} -d socle_core --clean --if-exists`)} < "${backupDir}/socle_core.dump"`,
    { cwd: repoRoot, shell: '/bin/bash' },
  )
  execSync(
    `docker run --rm -v ${PROJECT}_git-content:/data -v "${backupDir}:/backup" alpine sh -c "cd /data && rm -rf ./* ./.[!.]* 2>/dev/null; tar xzf /backup/git-content.tgz"`,
    { encoding: 'utf8', shell: '/bin/bash' },
  )
  composeExec('start backend frontend caddy webhook-worker')
  execSync(
    `bash -c 'for i in $(seq 1 60); do curl -sf http://127.0.0.1/actuator/health/readiness >/dev/null && exit 0; sleep 2; done; exit 1'`,
    { shell: '/bin/bash' },
  )
}
