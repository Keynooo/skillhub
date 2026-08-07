#!/usr/bin/env bash
# Wrapper: 加载七牛凭证并运行导出
set -euo pipefail
cd "$(dirname "$0")/.."
. ./.env.qiniu
exec bash scripts/skill-sync-export.sh
