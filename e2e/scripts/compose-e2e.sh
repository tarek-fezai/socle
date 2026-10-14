#!/usr/bin/env bash
# Point d'entrée unique de la pile e2e.
# Force `docker compose -p socle-e2e` et `--env-file .env.ci`.
# Le -p de la ligne de commande l'emporte sur `name:` du fichier Compose.
# Refuse toute cible d'exploitation (socle-production, .env.production, tag d'image autre que e2e).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
COMPOSE_DIR="${ROOT}/deploy/compose"
ENV_FILE="${COMPOSE_DIR}/.env.ci"
PROJECT="socle-e2e"

refuse() {
  echo "e2e: refus — $*" >&2
  echo "e2e: ne jamais lancer l'e2e sur une instance d'exploitation." >&2
  exit 1
}

if [[ "${COMPOSE_PROJECT_NAME:-}" == "socle-production" ]]; then
  refuse "COMPOSE_PROJECT_NAME=socle-production"
fi

for var in COMPOSE_ENV_FILES COMPOSE_FILE; do
  if [[ "${!var:-}" == *".env.production"* ]]; then
    refuse "${var} pointe vers .env.production"
  fi
done

forward=()
pending=""
for arg in "$@"; do
  if [[ -n "${pending}" ]]; then
    case "${pending}" in
      -p|--project-name)
        if [[ "${arg}" == "socle-production" || "${arg}" == *socle-production* ]]; then
          refuse "le projet ${arg} viserait les conteneurs socle-production"
        fi
        ;;
      --env-file)
        if [[ "${arg}" == *".env.production"* ]]; then
          refuse ".env.production est passé (${arg})"
        fi
        ;;
    esac
    pending=""
    continue
  fi
  case "${arg}" in
    -p|--project-name|--env-file)
      pending="${arg}"
      continue
      ;;
    -p=*|--project-name=*)
      value="${arg#*=}"
      if [[ "${value}" == "socle-production" || "${value}" == *socle-production* ]]; then
        refuse "le projet ${value} viserait les conteneurs socle-production"
      fi
      continue
      ;;
    --env-file=*)
      value="${arg#*=}"
      if [[ "${value}" == *".env.production"* ]]; then
        refuse ".env.production est passé (${value})"
      fi
      continue
      ;;
    *".env.production"*)
      refuse ".env.production est passé (${arg})"
      ;;
    *socle-production*)
      refuse "la commande viserait les conteneurs socle-production (${arg})"
      ;;
  esac
  forward+=("${arg}")
done

if [[ -n "${pending}" ]]; then
  refuse "option ${pending} sans valeur"
fi

# Tag d'image : exactement e2e. `local` est celui de l'instance d'exploitation.
# Contrôle avant tout `docker compose` (et avant toute inspection Docker).
if [[ -n "${VERSION+x}" && "${VERSION}" != "e2e" ]]; then
  refuse "VERSION=${VERSION} dans l'environnement. L'e2e construit des images :e2e, jamais celles de l'instance d'exploitation."
fi
if [[ -f "${ENV_FILE}" ]]; then
  file_version="$(grep -E '^VERSION=' "${ENV_FILE}" | tail -1 | cut -d= -f2- | tr -d '\r' || true)"
  if [[ "${file_version}" != "e2e" ]]; then
    refuse "VERSION=${file_version:-<absente>} dans .env.ci. L'e2e construit des images :e2e, jamais celles de l'instance d'exploitation."
  fi
elif [[ "${VERSION:-}" != "e2e" ]]; then
  refuse "VERSION absente de .env.ci. Attendu : VERSION=e2e."
fi

# Un identifiant de conteneur d'exploitation passé en argument est refusé
# avant tout `docker compose` (lecture seule de la liste, aucune action).
if docker info >/dev/null 2>&1; then
  prod_ids="$(docker ps -a --filter "label=com.docker.compose.project=socle-production" --format '{{.ID}} {{.Names}}' 2>/dev/null || true)"
  if [[ -n "${prod_ids}" ]]; then
    for arg in "${forward[@]}"; do
      while read -r cid cname; do
        [[ -z "${cid}" ]] && continue
        if [[ "${arg}" == "${cid}" || "${arg}" == "${cname}" ]]; then
          refuse "la commande viserait le conteneur d'exploitation ${cname}"
        fi
      done <<< "${prod_ids}"
    done
  fi
fi

cmd=""
for arg in "${forward[@]}"; do
  case "${arg}" in
    -*) ;;
    *) cmd="${arg}"; break ;;
  esac
done

port_conflict() {
  local port="$1"
  local raw name holder e2e_holds
  if ! raw="$(docker ps --filter "publish=${port}" --format '{{.Names}}')"; then
    echo "e2e: impossible de vérifier le port ${port}. Aucune instance n'est modifiée." >&2
    exit 1
  fi
  holder=""
  e2e_holds=0
  while read -r name; do
    [[ -z "${name}" ]] && continue
    if [[ "${name}" == socle-e2e-* ]]; then
      e2e_holds=1
      continue
    fi
    holder="${name}"
    break
  done <<< "${raw}"
  if [[ -n "${holder}" ]]; then
    echo "e2e: le port ${port} est déjà utilisé par ${holder}." >&2
    echo "e2e: arrêt sans modifier cette instance." >&2
    exit 1
  fi
  if [[ "${e2e_holds}" -eq 0 ]] && command -v ss >/dev/null 2>&1; then
    if ss -H -ltn "sport = :${port}" 2>/dev/null | grep -q .; then
      echo "e2e: le port ${port} est déjà pris par un autre processus." >&2
      echo "e2e: arrêt sans modifier l'autre instance." >&2
      exit 1
    fi
  fi
}

case "${cmd}" in
  up|start|restart|create|run)
    if ! docker info >/dev/null 2>&1; then
      echo "e2e: docker est indisponible. Aucune instance n'est modifiée." >&2
      exit 1
    fi
    port_conflict 80
    port_conflict 8081
    ;;
esac

cd "${COMPOSE_DIR}"
export COMPOSE_PROJECT_NAME="${PROJECT}"
export VERSION="e2e"
echo "e2e: docker compose -p ${PROJECT} --env-file .env.ci ${forward[*]-}" >&2
exec docker compose \
  -p "${PROJECT}" \
  -f docker-compose.yml \
  -f ../../e2e/docker-compose.e2e.yml \
  --env-file "${ENV_FILE}" \
  --profile demo-idp \
  "${forward[@]}"
