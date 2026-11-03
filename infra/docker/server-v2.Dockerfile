FROM node:22-bookworm-slim AS build
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates openssl python3 make g++ \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
ENV PRISMA_SKIP_POSTINSTALL_GENERATE=true
COPY apps/server/package.json apps/server/package-lock.json ./
COPY apps/server/prisma ./prisma
RUN npm ci --no-audit --no-fund
COPY apps/server/tsconfig*.json apps/server/nest-cli.json ./
COPY apps/server/src ./src
RUN npx --no-install prisma generate && npm run build && npm prune --omit=dev --no-audit --no-fund

FROM node:22-bookworm-slim AS runtime
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates openssl \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
ENV NODE_ENV=production
COPY --from=build --chown=node:node /app/node_modules ./node_modules
COPY --from=build --chown=node:node /app/dist ./dist
COPY --from=build --chown=node:node /app/package.json ./package.json
COPY infra/docker/start-v2.cjs ./start-v2.cjs
RUN mkdir uploads && chown node:node uploads
USER node
EXPOSE 3000
CMD ["node", "start-v2.cjs"]
