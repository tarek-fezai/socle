# OpenFGA — versions Socle

## Serveur

| Contexte | Image |
|----------|--------|
| `infra/docker-compose.yml` | `openfga/openfga:v1.8.16` |
| Tests Java Testcontainers (`AuthorizationServiceOpenFgaTest`) | même tag |

Montée depuis `v1.8.4` pour rester sur la ligne 1.8.x compatible avec le SDK Java
utilisé par Socle, tout en bénéficiant des correctifs 1.8.x.

## SDK Java

| Propriété Maven | Version |
|-----------------|---------|
| `openfga.version` (`backend/pom.xml`) | `0.10.1` |

### Changelog 0.9.1 → 0.10.1 (résumé)

- **0.10.0** : `batchCheck` rejette `maxParallelRequests` / `maxBatchSize` non positifs ;
  parallélisme borné des requêtes in-flight ; `ClientBatchCheckClientResponse.getStatusCode()`
  passe de `int` à `Integer` (binaire incompatible → recompiler).
- **0.10.0** : `disableTransactions` déprécié au profit de `transactions()`.
- **0.10.1** : pont de sérialisation JSON ; wrappers Jackson 2 encore fonctionnels mais dépréciés.

### Écritures atomiques (`writeAtomic`)

Preuve SDK **0.10.1** (`ClientWriteOptions` + `OpenFgaClient.write`) :

1. **Défaut** : `transactionsEnabled = true` dans le constructeur de
   `ClientWriteOptions` (remplace `disableTransactions(false)`).
2. **Une seule requête** : si `isTransactionsEnabled()`, `write()` appelle
   `writeTransactions`, qui construit **un** `WriteRequest` (writes + deletes)
   et invoque **une fois** `api.write(storeId, body, …)`.
3. **Chunking** : `transactionChunkSize` ne s’applique qu’au mode
   `transactions(false)` (`writeNonTransaction`) — hors chemin Socle.
4. **Socle** : `AuthorizationService.doWrite` force
   `new ClientWriteOptions().transactions(true)` (pas `disableTransactions`).

IT : `AuthorizationServiceOpenFgaTest` — batch avec type inconnu ⇒ exception et
aucun tuple écrit ; même garantie pour `reparentFolder` / `reparentDocument`.

Socle n’utilise pas `getStatusCode()` ni `disableTransactions`.

## Tests du modèle (CLI)

| Outil | Version épinglée |
|-------|------------------|
| CLI `fga` | `v0.8.1` |

Fichier : `infra/openfga/model.fga.yaml` — lancé en CI par le job `authz-model`
(`fga model test --tests infra/openfga/model.fga.yaml`).
