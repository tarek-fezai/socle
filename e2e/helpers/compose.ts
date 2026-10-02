import { execSync } from 'node:child_process'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const here = path.dirname(fileURLToPath(import.meta.url))
const repoRoot = path.resolve(here, '../..')
const composeDir = path.join(repoRoot, 'deploy/compose')

function composeCmd(args: string, envFile = '.env.ci'): string {
  return `docker compose -f docker-compose.yml -f ../../e2e/docker-compose.e2e.yml --env-file ${envFile} --profile demo-idp ${args}`
}

export function composeExec(args: string, envFile = '.env.ci'): string {
  return execSync(composeCmd(args, envFile), {
    cwd: composeDir,
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
  const pgUser = postgresUser()
  execSync(`mkdir -p "${backupDir}"`, { shell: '/bin/bash' })
  execSync(
    `${composeCmd(`exec -T postgres pg_dump -U ${pgUser} -Fc socle_core`)} > "${backupDir}/socle_core.dump"`,
    { cwd: composeDir, shell: '/bin/bash' },
  )
  const project = process.env.COMPOSE_PROJECT_NAME ?? 'socle-production'
  execSync(
    `docker run --rm -v ${project}_git-content:/data -v "${backupDir}:/backup" alpine tar czf /backup/git-content.tgz -C /data .`,
    { encoding: 'utf8', shell: '/bin/bash' },
  )
}

export function restoreSocleCoreAndGit(backupDir: string): void {
  const pgUser = postgresUser()
  composeExec('stop backend webhook-worker frontend caddy')
  execSync(
    `${composeCmd(`exec -T postgres pg_restore -U ${pgUser} -d socle_core --clean --if-exists`)} < "${backupDir}/socle_core.dump"`,
    { cwd: composeDir, shell: '/bin/bash' },
  )
  const project = process.env.COMPOSE_PROJECT_NAME ?? 'socle-production'
  execSync(
    `docker run --rm -v ${project}_git-content:/data -v "${backupDir}:/backup" alpine sh -c "cd /data && rm -rf ./* ./.[!.]* 2>/dev/null; tar xzf /backup/git-content.tgz"`,
    { encoding: 'utf8', shell: '/bin/bash' },
  )
  composeExec('start backend frontend caddy webhook-worker')
  // Wait for backend readiness after restore.
  execSync(
    `bash -c 'for i in $(seq 1 60); do curl -sf http://127.0.0.1/actuator/health/readiness >/dev/null && exit 0; sleep 2; done; exit 1'`,
    { shell: '/bin/bash' },
  )
}
