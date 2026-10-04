# Stockage binaire (pièces jointes)

Les fichiers sont stockés derrière l'interface `BlobStore`, **indépendante** de `DocumentStore`
(corps TipTap relationnel ou Git). Provider exclusif par instance :

| Provider | Env | Scalabilité |
|----------|-----|-------------|
| `local` (défaut) | `SOCLE_BLOB_PROVIDER=local`, `SOCLE_BLOB_LOCAL_DIR` | Un seul réplica backend (PVC RWO ; Helm refuse `replicas>1`) |
| `s3` | endpoint + bucket + clés | Multi-réplicas OK (Garage, SeaweedFS, MinIO…) |

Aucun endpoint cloud US n'est requis ni configuré par défaut.

## Compose

- Volume nommé `blob-content` monté sur `/data/blobs` (mode local).
- Profil optionnel `s3` : service Garage (`deploy/compose/garage/garage.toml`).

```bash
# Local (défaut)
docker compose -f deploy/compose/docker-compose.yml --env-file .env up -d

# S3-compatible (après création du bucket / clés Garage)
SOCLE_BLOB_PROVIDER=s3 \
SOCLE_BLOB_S3_ENDPOINT=http://garage:3900 \
SOCLE_BLOB_S3_BUCKET=socle-blobs \
SOCLE_BLOB_S3_ACCESS_KEY=… \
SOCLE_BLOB_S3_SECRET_KEY=… \
docker compose -f deploy/compose/docker-compose.yml --env-file .env --profile s3 up -d
```

## Sauvegarde

Toujours sauvegarder le magasin binaire **avec** Postgres `socle_core` (table `attachments`).
Voir [backup-restore.md](backup-restore.md).

## Sécurité

- Clés = UUID (jamais le nom de fichier utilisateur).
- Type MIME détecté par octets magiques (Apache Tika), pas par l'extension ni le `Content-Type` client.
- SVG refusé par défaut ; liste blanche configurable (`SOCLE_ATTACHMENT_ALLOWED_TYPES`).
- Images : métadonnées EXIF (dont GPS) retirées avant stockage.
- Lecture uniquement via `GET /api/v1/attachments/{id}` (pas d'URL publique ni présignée).
