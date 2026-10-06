# SPDX-License-Identifier: LicenseRef-Socle-Proprietary
# syntax=docker/dockerfile:1

# node:20-alpine (build)
FROM node@sha256:0a7108bf6c7bf5de370ffb1a3ed6be93d405b43ff159f681a8d18c0e2bc2e402 AS build

WORKDIR /app

RUN corepack enable && corepack prepare pnpm@9 --activate

COPY package.json pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile

COPY . .

ENV VITE_API_BASE_URL=""
ENV VITE_YJS_WS_URL=""

RUN pnpm build

# nginxinc/nginx-unprivileged:1.27-alpine — runtime UID/GID 101
FROM nginxinc/nginx-unprivileged@sha256:65e3e85dbaed8ba248841d9d58a899b6197106c23cb0ff1a132b7bfe0547e4c0 AS runtime

LABEL org.opencontainers.image.licenses="LicenseRef-Socle-Proprietary"

COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
  CMD wget -qO- http://127.0.0.1:8080/ || exit 1
