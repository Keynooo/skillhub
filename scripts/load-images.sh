#!/usr/bin/env bash
# 在主服务器上加载离线打包的 skillhub 镜像，免去慢速拉 GHCR。
#
# 用法:
#   bash scripts/load-images.sh <镜像包>
#   例: bash scripts/load-images.sh /root/skillhub-images-v0.2.6.tar.gz
#
# Docker 镜像没有「文件位置」可解压——docker load 会把镜像直接装入 Docker 内部存储，
# load 完 docker images 即可看到，随后照常起栈。

set -euo pipefail

TARBALL="${1:?用法: bash scripts/load-images.sh <镜像包.tar.gz>}"

if [[ ! -f "$TARBALL" ]]; then
  echo "❌ 找不到文件: $TARBALL" >&2
  exit 1
fi

echo "==> docker load: $TARBALL ($(du -h "$TARBALL" | cut -f1))"
case "$TARBALL" in
  *.tar.gz|*.tgz) gunzip -c "$TARBALL" | docker load ;;
  *.tar)          docker load -i "$TARBALL" ;;
  *) echo "❌ 未知格式（支持 .tar.gz / .tgz / .tar）" >&2; exit 1 ;;
esac

echo ""
echo "✅ 加载完成。当前 skillhub 镜像:"
docker images | grep -E 'skillhub-(server|web|scanner|sandbox)' || true
echo ""
echo "下一步部署（用本地镜像，跳过 pull）:"
echo "  SKIP_IMAGE_PULL=1 bash scripts/upgrade-release.sh <tag>"
