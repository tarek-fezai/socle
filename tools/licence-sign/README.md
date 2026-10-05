# Signature de fichiers de licence Socle

Outil **hors ligne** : lit une clé privée Ed25519 depuis un fichier local et produit un JSON signé.
Aucun appel réseau. La clé privée **ne doit jamais** être commitée ni placée dans la CI.

## Format du fichier de licence

```json
{
  "licenseId": "LIC-DEMO-9F2A-44B1",
  "licensee": "Organisation Démo",
  "edition": "Entreprise",
  "issuedAt": "2026-01-01T00:00:00Z",
  "expiresAt": "2027-01-01T00:00:00Z",
  "maxUsers": 250
}
```

Après signature, le champ `signature` (Base64 Ed25519) est ajouté. Le backend vérifie la signature
avec la clé publique embarquée (`backend/src/main/resources/licence/ed25519-public.b64`).

## Clé privée

Fichier texte contenant la **graine** Ed25519 (32 octets) encodée en Base64, une seule ligne.
Exemple de génération (Java 21+) :

```bash
# Une seule fois, hors dépôt — conserver la graine privée hors git
java …  # ou tout outil Ed25519 ; la graine publique correspondante doit être celle embarquée au build
```

La clé publique de production est versionnée dans le dépôt. Toute rotation de clé implique un nouveau
build backend avec la nouvelle clé publique.

## Usage

```bash
node tools/licence-sign/sign.mjs \
  --key /chemin/secret/private.ed25519.b64 \
  --in licence.json \
  --out licence.signed.json
```

Importer `licence.signed.json` via l'écran Administration → Licence.
