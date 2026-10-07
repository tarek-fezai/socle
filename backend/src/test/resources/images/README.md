# Fixtures WebP (référence libwebp)

Fichiers produit avec **libwebp 1.5.0** (`cwebp` / `webpmux`). Ne pas régénérer via ImageIO / TwelveMonkeys.

## Prérequis

Binaires Windows : [libwebp releases](https://storage.googleapis.com/downloads.webmproject.org/releases/webp/index.html)
(`libwebp-*-windows-x64.zip`, dossier `bin/`).

## Génération (commandes exactes)

Depuis un répertoire de travail contenant `source.png` (4×4 RGB) et `gps.exif`
(payload EXIF = `Exif\0\0` + TIFF big-endian avec IFD GPS et la chaîne ASCII
`SECRET-GPS-MARKER`) :

```bash
cwebp -q 80 source.png -o sample-lossy-raw.webp
cwebp -lossless source.png -o sample-lossless.webp
webpmux -set exif gps.exif sample-lossy-raw.webp -o sample-lossy.webp
```

Contrôle :

```bash
webpinfo sample-lossy.webp
webpinfo sample-lossless.webp
```

`sample-lossy.webp` doit signaler `EXIF: 1` et contenir `SECRET-GPS-MARKER`.
`sample-lossless.webp` : VP8L valide, sans chunk EXIF (fichier < 1 Ko).

## Fichiers livrés

| Fichier | Rôle |
|---------|------|
| `sample-lossy.webp` | VP8 lossy 4×4 + EXIF/GPS |
| `sample-lossless.webp` | VP8L lossless 4×4 |
