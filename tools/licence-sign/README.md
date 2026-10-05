# Signature de fichiers de licence Socle

Outil **hors ligne** : lit une clé privée Ed25519 depuis un **fichier local fourni en argument**
(`--key`) et produit un JSON signé. Aucun appel réseau. La clé privée **ne doit jamais** être
commitée, placée sous `tools/licence-sign/`, ni fournie à la CI.

## Génération de clés (openssl)

```bash
# Clé privée PEM PKCS#8 (à conserver hors dépôt, hors CI)
openssl genpkey -algorithm ed25519 -out /chemin/secret/socle-licence-private.pem
chmod 600 /chemin/secret/socle-licence-private.pem

# Clé publique (à embarquer dans backend/src/main/resources/licence/ed25519-public.b64
# — corps Base64 SPKI d'une seule ligne, ou raw 32 octets Base64)
openssl pkey -in /chemin/secret/socle-licence-private.pem -pubout -outform DER \
  | openssl base64 -A
# → coller le résultat dans ed25519-public.b64 (une ligne), ou utiliser le PEM public
#   (le backend accepte PEM SPKI, DER SPKI Base64, ou raw 32 octets Base64).
```

## Format du fichier de licence (avant signature)

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

Après signature, le champ `signature` (Base64 Ed25519) est ajouté. Le backend vérifie hors ligne
avec la clé publique embarquée au build.

## Usage

```bash
node tools/licence-sign/sign.mjs \
  --key /chemin/secret/socle-licence-private.pem \
  --in licence.json \
  --out licence.signed.json
```

Importer `licence.signed.json` via Administration → Licence.
