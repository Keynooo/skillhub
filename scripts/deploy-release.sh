#!/usr/bin/env bash
# 一键拉起 skillhub release 栈（在服务器上跑）。
#
# 用法:
#   SERVER_IP=10.16.13.106 ADMIN_PWD=... PG_PWD=... ./scripts/deploy-release.sh
#
# 第一次部署前确认:
#   1. 服务器 Ubuntu/Debian 已装 docker + compose plugin（`docker --version` && `docker compose version`）
#   2. 你在 skillhub 仓库根目录（git clone 下来 cd 进去那个）
#   3. 镜像 tag 已经推到 GHCR（在开发机打 tag + 发 release 触发 CI）
#   4. fork 仓库 + GHCR 包都 public 时不用 GITHUB_PAT；否则先 export GITHUB_PAT=<你的PAT>
#
# 环境变量（必填）:
#   SERVER_IP   服务器公网 IP（无域名场景，浏览器访问用 http://$SERVER_IP:9001）
#   ADMIN_PWD   bootstrap admin 登录密码（建议强密码，登录后立刻在 UI 改）
#   PG_PWD      PostgreSQL skillhub 用户密码（建议强密码）
#
# 环境变量（可选）:
#   IMAGE_TAG   镜像 tag，默认 v0.1.1（改了记得开发机也打对应 tag）
#   GHCR_USER   GHCR 用户名，默认 Keynooo；fork 仓库+包都 public 时留空跳过 login
#   GITHUB_PAT  GHCR PAT，配合 GHCR_USER 用

set -euo pipefail
set +H  # 关 histexpand，避免 !2026 之类触发 bash 历史扩展

# 必填参数检查
: "${SERVER_IP:?需要 SERVER_IP=服务器公网IP}"
: "${ADMIN_PWD:?需要 ADMIN_PWD=管理员密码}"
: "${PG_PWD:?需要 PG_PWD=数据库密码}"
IMAGE_TAG="${IMAGE_TAG:-v0.1.1}"
GHCR_USER="${GHCR_USER:-Keynooo}"

# 必须在仓库根
if [[ ! -f .env.release.example ]]; then
  echo "❌ 当前目录没有 .env.release.example，请先 cd 到 skillhub 仓库根目录" >&2
  exit 1
fi

if [[ ! -f compose.verify.yml ]]; then
  echo "❌ 当前目录没有 compose.verify.yml（scanner healthcheck override）" >&2
  echo "   确认 git pull 拉到最新 dev 分支" >&2
  exit 1
fi

echo "==> 1/4 从模板生成 .env.release"
cp .env.release.example .env.release

echo "==> 2/4 填镜像名/IP/密码"
# 生成下载限流签名密钥（32 字节随机串，校验器拒绝占位值且要求≥32字符）
ANON_SECRET=$(openssl rand -hex 32)
sed -i \
  -e "s|ghcr.io/iflytek/|ghcr.io/${GHCR_USER}/|g" \
  -e "s|SKILLHUB_VERSION=latest|SKILLHUB_VERSION=${IMAGE_TAG}|" \
  -e "s|SKILLHUB_PUBLIC_BASE_URL=http://localhost|SKILLHUB_PUBLIC_BASE_URL=http://${SERVER_IP}|" \
  -e "s|change-this-postgres-password|${PG_PWD}|" \
  -e "s|BOOTSTRAP_ADMIN_PASSWORD=ChangeMe!2026|BOOTSTRAP_ADMIN_PASSWORD=${ADMIN_PWD}|" \
  -e "s|SKILLHUB_DOWNLOAD_ANON_COOKIE_SECRET=replace-with-random-download-secret-32-bytes|SKILLHUB_DOWNLOAD_ANON_COOKIE_SECRET=${ANON_SECRET}|" \
  -e "s|SKILLHUB_STORAGE_S3_ACCESS_KEY=replace-me|SKILLHUB_STORAGE_S3_ACCESS_KEY=local-not-used|" \
  -e "s|SKILLHUB_STORAGE_S3_SECRET_KEY=replace-me|SKILLHUB_STORAGE_S3_SECRET_KEY=local-not-used|" \
  .env.release

cat >> .env.release <<'SMTP_EOF'
# QQ 邮箱 SMTP（忘记密码邮件发送）
SPRING_MAIL_HOST=smtp.qq.com
SPRING_MAIL_PORT=465
SPRING_MAIL_USERNAME=641563099@qq.com
SPRING_MAIL_PASSWORD=vfjensncobdrbdib
SPRING_MAIL_SMTP_AUTH=true
SPRING_MAIL_SMTP_STARTTLS_ENABLE=false
SPRING_MAIL_PROPERTIES_MAIL_SMTP_SSL_ENABLE=true
SPRING_MAIL_PROPERTIES_MAIL_SMTP_SSL_TRUST=smtp.qq.com
SKILLHUB_AUTH_PASSWORD_RESET_FROM_ADDRESS=641563099@qq.com
SKILLHUB_AUTH_PASSWORD_RESET_FROM_NAME=SkillHub
SMTP_EOF

echo "==> 3/4 校验配置"
if ! ./scripts/validate-release-config.sh .env.release; then
  echo "❌ 配置校验失败，请按提示修改 .env.release 后重跑 docker compose up" >&2
  exit 1
fi

echo "==> 4/4 起 release 栈（首次拉镜像约 5-15 分钟）"
if [[ -n "${GITHUB_PAT:-}" ]]; then
  echo "$GITHUB_PAT" | docker login ghcr.io -u "$GHCR_USER" --password-stdin
fi

docker compose --env-file .env.release \
  -f compose.release.yml \
  -f compose.verify.yml \
  up -d --wait

echo ""
echo "✅ 部署完成"
echo "   访问: http://${SERVER_IP}:9001"
echo "   登录: admin / ${ADMIN_PWD}"
echo "   登录后请立刻在 UI 改密码"
