#!/usr/bin/env bash
# 把 skillhub 的 Docker 镜像离线打包，方便搬运到内网主服务器（绕开主服务器慢速拉 GHCR）。
#
# 用法:
#   bash scripts/pack-images.sh <tag> [输出目录]
#   例: bash scripts/pack-images.sh v0.2.6              # 产物 ./dist/skillhub-images-v0.2.6.tar.gz
#       bash scripts/pack-images.sh v0.2.6 /tmp/out     # 指定输出目录
#
# 在能正常拉到镜像的机器上跑（如副服务器 106，或本地），打包后把产物传给主服务器，
# 主服务器用 scripts/load-images.sh 一键 docker load。
#
# 镜像前缀可用环境变量覆盖（默认 ghcr.io/keynooo）:
#   REGISTRY=registry.example.com NAMESPACE=myorg bash scripts/pack-images.sh v0.2.6
#
# 注: postgres/redis 是固定大版本外部依赖（postgres:16-alpine / redis:7-alpine），
#     主服务器已有、随版本不更新，故不打包。

set -euo pipefail

TAG="${1:?用法: bash scripts/pack-images.sh <tag> [输出目录]}"
OUT_DIR="${2:-dist}"
REGISTRY="${REGISTRY:-ghcr.io}"
NAMESPACE="${NAMESPACE:-keynooo}"

PREFIX="${REGISTRY}/${NAMESPACE}"
IMAGES=(
  "${PREFIX}/skillhub-server:${TAG}"
  "${PREFIX}/skillhub-web:${TAG}"
  "${PREFIX}/skillhub-scanner:${TAG}"
  "${PREFIX}/skillhub-sandbox:${TAG}"
)

mkdir -p "$OUT_DIR"

echo "==> 检查/拉取镜像（${#IMAGES[@]} 个）"
for img in "${IMAGES[@]}"; do
  if docker image inspect "$img" >/dev/null 2>&1; then
    echo "    ✅ $img"
  else
    echo "    ⬇️  $img（本地缺失，docker pull）"
    docker pull "$img"
  fi
done

if command -v gzip >/dev/null 2>&1; then
  TARBALL="$OUT_DIR/skillhub-images-${TAG}.tar.gz"
  echo "==> docker save + gzip -> $TARBALL"
  docker save "${IMAGES[@]}" | gzip > "$TARBALL"
else
  TARBALL="$OUT_DIR/skillhub-images-${TAG}.tar"
  echo "==> 无 gzip，输出未压缩 .tar -> $TARBALL"
  docker save "${IMAGES[@]}" -o "$TARBALL"
fi

echo ""
echo "✅ 打包完成: $TARBALL ($(du -h "$TARBALL" | cut -f1))"
echo "   传到主服务器后: bash scripts/load-images.sh <此文件>"
