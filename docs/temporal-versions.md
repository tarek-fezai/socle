# Versions Temporal (SDK ↔ serveur ↔ UI)

## Compatibilité

Temporal publie SDK et serveur **indépendamment**. D’après la doc officielle
([Versions and support](https://docs.temporal.io/temporal-service/temporal-server#versions-and-support)) :

> All SDK versions support all server versions.

Il n’existe pas de matrice formelle SDK ↔ serveur ; la pratique recommandée est
de monter régulièrement les deux.

## Versions Socle

| Composant | Version | Source du pin |
|-----------|---------|---------------|
| `temporal.version` (`backend/pom.xml`) | `1.39.0` | Dependabot / Maven Central |
| `temporalio/auto-setup` | `1.29.1` | [temporalio/docker-compose `.env`](https://github.com/temporalio/docker-compose/blob/main/.env) (`TEMPORAL_VERSION`) |
| `temporalio/ui` | `2.34.0` | même `.env` (`TEMPORAL_UI_VERSION`) |

Le serveur / UI locaux suivent le couple publié par `temporalio/docker-compose`
(main au moment de la montée). Le SDK Java peut être plus récent que le serveur
local sans rupture de protocole.

## Rejeu de workflows

Historique de référence capturé sous SDK **1.27.x** :
`backend/src/test/resources/temporal/document-approval-history-1.27.json`.

Le test `DocumentApprovalWorkflowReplayTest` le rejoue via `WorkflowReplayer`
après chaque montée SDK.
