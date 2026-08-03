FROM node:22-alpine AS build
RUN corepack enable
# 国内网络：pnpm 默认从 npmjs.org 拉包会超时，写 .npmrc 切淘宝镜像
# 环境变量 npm_config_registry 对 frozen-lockfile 也生效
RUN echo "registry=https://registry.npmmirror.com" > /root/.npmrc
WORKDIR /app
COPY package.json pnpm-lock.yaml ./
ENV npm_config_registry=https://registry.npmmirror.com
# --ignore-scripts 跳过 esbuild/playwright 等 postinstall（构建期不需要）
RUN pnpm install --frozen-lockfile --ignore-scripts
COPY . .
RUN pnpm build

FROM nginx:alpine
ENV SKILLHUB_TRUST_FORWARDED_PROTO=false
COPY --from=build /app/dist /usr/share/nginx/html
COPY --from=build /app/src/docs/skill.md.template /usr/share/nginx/html/registry/skill.md.template
COPY nginx.conf.template /etc/nginx/templates/default.conf.template
COPY runtime-config.js.template /usr/share/nginx/html/runtime-config.js.template
COPY docker-entrypoint.d/30-runtime-config.sh /docker-entrypoint.d/30-runtime-config.sh
# Windows checkout 会把脚本转成 CRLF，导致 #!/bin/sh\r 解释器找不到
RUN sed -i 's/\r$//' /docker-entrypoint.d/30-runtime-config.sh && chmod +x /docker-entrypoint.d/30-runtime-config.sh
EXPOSE 80
HEALTHCHECK --interval=10s --timeout=3s \
  CMD wget -qO- http://127.0.0.1/nginx-health || exit 1
