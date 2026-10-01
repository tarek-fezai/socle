# Deploy artifacts

Production container images are built and published by [`.github/workflows/release.yml`](../.github/workflows/release.yml) when a `v*` tag is pushed.

## Published images

| Service | Image |
|---------|--------|
| Backend | `ghcr.io/tarek-fezai/socle-backend:<semver>` |
| Frontend | `ghcr.io/tarek-fezai/socle-frontend:<semver>` |
| Webhook worker | `ghcr.io/tarek-fezai/socle-worker:<semver>` |

Tags follow the semver from the git tag (without the leading `v`). Images are multi-arch (`linux/amd64`, `linux/arm64`), signed with Cosign (keyless), and accompanied by SPDX SBOMs on the GitHub Release.

## Mirroring to a private registry

For air-gapped or policy-constrained environments, mirror images into your registry and deploy from there:

```bash
VERSION=1.2.3   # semver without v

for img in socle-backend socle-frontend socle-worker; do
  crane copy \
    "ghcr.io/tarek-fezai/${img}:${VERSION}" \
    "registry.example.com/socle/${img}:${VERSION}"
done
```

Prefer digest pins in production manifests after mirroring:

```text
registry.example.com/socle/socle-backend@sha256:...
```

Verify signatures with Cosign against the upstream GHCR digest before mirroring, or re-sign in your registry according to your security policy.
